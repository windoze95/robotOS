package dev.r1ptt.messages

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import dev.r1ptt.App
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class SmsControllerTest {
    private lateinit var app: App
    private lateinit var controller: SmsController
    private lateinit var transport: FakeTransport
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.deleteDatabase("messages.db")
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)
        transport = FakeTransport()
        controller = SmsController(app, transport) { true }
    }

    private inner class FakeTransport : SmsTransport {
        val records = mutableListOf<SmsRecord>()
        var fault: Exception? = null
        override fun divide(body: String, subscription: Int) = listOf(body.take(3), body.drop(3))
        override fun send(record: SmsRecord, texts: List<String>, sent: ArrayList<PendingIntent>, delivery: ArrayList<PendingIntent>) {
            SmsStore(app).use { assertEquals(record, it.conversation(record.peer).last()) }
            assertEquals(record.body, texts.joinToString(""))
            assertEquals(2, sent.size); assertEquals(2, delivery.size)
            records.add(record)
            fault?.let { throw it }
        }
    }
    private fun review() = controller.prepare(SmsDraft("+15551234567", "Synthetic message only"))
    private fun send(review: SmsReview): Pair<Boolean, String> {
        var result: Pair<Boolean, String>? = null
        controller.send(review) { consumed, message -> result = consumed to message }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (result == null && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        return requireNotNull(result) { "Send completion did not arrive" }
    }
    private fun status(review: SmsReview): SmsRecord = SmsStore(app).use { it.conversation(review.record.peer).single() }

    @Test fun preparationDoesNotSendAndReplayingOneReviewCannotSendTwice() {
        val review = review()
        assertTrue(transport.records.isEmpty())
        assertTrue(send(review).first)
        assertEquals(1, transport.records.size)
        assertFalse(send(review).first)
        assertEquals(1, transport.records.size)
        assertEquals(SmsStatus.SENDING, status(review).status(System.currentTimeMillis()))
    }

    @Test fun revokedPermissionAndChangedSimBlockAnAlreadyPreparedReview() {
        val review = review()
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        assertFalse(send(review).first)
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(2)
        assertFalse(send(review).first)
        assertTrue(transport.records.isEmpty())
        SmsStore(app).use { assertTrue(it.threads().isEmpty()) }
    }

    @Test fun missingDefaultSimCannotSilentlyChooseSlotZero() {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(-1)
        assertThrows(IllegalStateException::class.java) { review() }
        assertTrue(transport.records.isEmpty())
    }

    @Test fun transportExceptionRemainsUncertainAndIsNotRetried() {
        transport.fault = IOException("Synthetic binder failure")
        val review = review()
        assertTrue(send(review).first)
        val record = status(review)
        assertEquals(SmsStatus.UNKNOWN, record.status(record.createdAt + SmsRecord.SEND_WAIT_MS))
        assertEquals(1, transport.records.size)
        assertFalse(send(review).first)
        assertEquals(1, transport.records.size)
    }

    @Test fun allSentCallbacksAreRequiredAndDoNotClaimDelivery() {
        val review = review(); send(review)
        fun callback(part: Int) {
            val complete = CountDownLatch(1)
            val identity = SmsCallback(review.record.id, review.record.token, part, false)
            controller.result(Intent().setData(Uri.parse(identity.uri())), Activity.RESULT_OK) { complete.countDown() }
            assertTrue(complete.await(5, TimeUnit.SECONDS))
        }
        callback(1)
        assertEquals(SmsStatus.SENDING, status(review).status(System.currentTimeMillis()))
        callback(0)
        assertEquals(SmsStatus.SENT, status(review).status(System.currentTimeMillis()))
        assertFalse(controller.busy)
    }

    @Test fun incomingOptInDefaultsOffEvenWhenAndroidPermissionExists() {
        shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS)
        assertFalse(controller.receiveEnabled)
        assertFalse(app.smsAssistant.enabled)
    }

    @Test fun manifestProtectsDeliveryAndHasNoExportedSendAction() {
        val pm = app.packageManager
        assertFalse(pm.getReceiverInfo(ComponentName(app, SmsStatusReceiver::class.java), 0).exported)
        assertEquals(Manifest.permission.BROADCAST_SMS, pm.getReceiverInfo(ComponentName(app, SmsIncomingReceiver::class.java), 0).permission)
        assertFalse(pm.getActivityInfo(ComponentName(app, MessagesActivity::class.java), 0).exported)
    }
    @Test fun sentCallbackKeepsOriginalResultAndRadioErrorAcrossDuplicates() {
        val review = review(); send(review)
        val id = SmsCallback(review.record.id, review.record.token, 0, false)
        fun result(code: Int, radio: Int) {
            val done = CountDownLatch(1)
            controller.result(Intent().setData(Uri.parse(id.uri())).putExtra("errorCode", radio), code) { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        result(android.telephony.SmsManager.RESULT_NO_DEFAULT_SMS_APP, 42)
        result(android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE, 99)
        val part = status(review).parts[0]
        assertEquals(android.telephony.SmsManager.RESULT_NO_DEFAULT_SMS_APP, part.error)
        assertEquals(42, part.radioError); assertEquals("Android callback", part.failureSource)
        assertEquals(1, transport.records.size)
    }

    @Test fun localSecurityExceptionIsIdentifiedSeparatelyFromCarrierCallbacks() {
        transport.fault = SecurityException("Sensitive exception text must not be saved")
        val review = review(); assertTrue(send(review).first)
        val record = status(review)
        assertEquals(SmsStatus.FAILED, record.status(System.currentTimeMillis()))
        assertTrue(record.parts.all { it.failureSource == "SecurityException before handoff" })
        assertFalse(record.encode().contains("Sensitive exception"))
        assertEquals(1, transport.records.size)
    }
}
