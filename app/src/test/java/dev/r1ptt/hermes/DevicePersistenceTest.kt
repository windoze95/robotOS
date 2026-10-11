package dev.r1ptt.hermes

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevicePersistenceTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("device_receipts", "device_api", "device_notices"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
    }

    @Test fun interruptedMutationRemainsUnknownAfterRestartAndCannotExecuteTwice() {
        val id = "a-request-identity-1234"
        val receipts = DeviceReceipts(context)
        assertThrows(IllegalStateException::class.java) {
            receipts.perform(id, "token", "speak:text") { throw IllegalStateException("process stopped") }
        }
        val reopened = DeviceReceipts(context)
        val retry = reopened.perform(id, "token", "speak:text") { fail("Must not execute again"); 200 to JSONObject() }
        assertEquals(202, retry.first)
        assertEquals("unknown", retry.second.getString("status"))
        assertNull(reopened.get(id, "different-token"))
        assertEquals(409, reopened.perform(id, "token", "speak:other") { 200 to JSONObject() }.first)
    }

    @Test fun malformedExistingReceiptCannotBecomeANewAction() {
        val id = "existing-request-id-1234"
        val prefs = context.getSharedPreferences("device_receipts", 0)
        val corrupt = "{broken receipt"
        prefs.edit().putString(id, corrupt).commit()
        val receipts = DeviceReceipts(context)
        var executed = false
        assertThrows(IOException::class.java) {
            receipts.perform(id, "token", "payload") { executed = true; 200 to JSONObject() }
        }
        assertFalse(executed)
        assertEquals(corrupt, prefs.getString(id, null))
        assertThrows(IOException::class.java) { receipts.get(id, "token") }
        // A corrupt unrelated receipt also blocks pruning/replacement, preserving recovery data.
        assertThrows(IOException::class.java) {
            receipts.perform("another-request-id-1234", "token", "payload") { executed = true; 200 to JSONObject() }
        }
        assertFalse(executed)
        assertEquals(1, prefs.all.size)
    }

    @Test fun unreadableBackingFileCannotBeMistakenForEmptyStorage() {
        val receipts = DeviceReceipts(context)
        val file = File(context.dataDir, "shared_prefs/device_receipts.xml")
        file.parentFile!!.mkdirs()
        val corrupt = "<map><string name=\"existing-request-id-1234\">broken"
        file.writeText(corrupt)
        var executed = false
        assertThrows(IOException::class.java) {
            receipts.perform("existing-request-id-1234", "token", "payload") { executed = true; 200 to JSONObject() }
        }
        assertFalse(executed)
        assertEquals(corrupt, file.readText())
    }

    @Test fun receiptCapacityNeverEvictsAnUnexpiredRequest() {
        var now = 1_000_000L
        val receipts = DeviceReceipts(context) { now }
        repeat(512) { i ->
            assertEquals(200, receipts.perform("request-identity-$i", "token", "$i") { 200 to JSONObject().put("status", "saved") }.first)
        }
        var ran = false
        assertEquals(429, receipts.perform("request-identity-next", "token", "next") { ran = true; 200 to JSONObject() }.first)
        assertFalse(ran)
        now += DeviceReceipts.RETENTION_MS + 1
        assertEquals(200, receipts.perform("request-identity-next", "token", "next") { ran = true; 200 to JSONObject() }.first)
        assertTrue(ran)
    }

    @Test fun allAgentPathsShareQuotaAndUnknownAttemptsRetainTheirSlot() {
        var day = 100L
        val quota = HermesSmsQuota(context) { day }
        assertTrue(quota.claim("device-api", 2))
        assertTrue(quota.claim("owner-relay", 2))
        assertTrue(quota.claim("owner-relay", 2))
        assertEquals(2, quota.sentToday())
        assertFalse(quota.claim("another", 2))
        val reopened = HermesSmsQuota(context) { day }
        assertFalse(reopened.claim("after-restart", 2))
        reopened.release("device-api")
        assertTrue(reopened.claim("after-restart", 2))
        day++
        assertEquals(0, reopened.sentToday())
        assertTrue(reopened.claim("next-day", 2))
    }

    @Test fun noticesRemainBoundedAndDismissalSurvivesRestart() {
        val notices = DeviceNotices(context)
        repeat(25) { notices.add("Notice $it") }
        assertEquals(20, notices.state.value.size)
        assertEquals("Notice 5", notices.state.value.first().text)
        notices.dismiss(notices.state.value.last().id)
        assertEquals(19, DeviceNotices(context).state.value.size)
        assertEquals("Notice 23", DeviceNotices(context).state.value.last().text)
    }
}
