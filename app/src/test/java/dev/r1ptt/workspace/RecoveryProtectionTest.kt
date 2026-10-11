package dev.r1ptt.workspace

import android.app.KeyguardManager
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.sys.AdminReceiver
import dev.r1ptt.ui.DeviceActivity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class RecoveryProtectionTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    @After fun close() { app.deviceApi.stop(); app.history.close() }
    private fun enrolled(url: String = "http://127.0.0.1:9") {
        val data = JSONObject().put("device_id", "r1-a").put("base_url", url)
            .put("device_token", "content-token").put("recovery_token", "recovery-token")
            .put("permissions", JSONObject().put("remote_management", true))
        val disk = object : ConfigBlobStorage { var value: String? = null; override fun read() = value; override fun write(blob: String) { value = blob } }
        val cipher = object : ConfigCipher { override fun seal(plain: String, allowKeyCreation: Boolean) = plain; override fun open(blob: String) = blob }
        val codec = object : ConfigCodec<JSONObject> { override fun encode(value: JSONObject) = value.toString(); override fun decode(plain: String) = JSONObject(plain) }
        app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.workspace.identity, EncryptedConfigPersistence(data, disk, cipher, codec))
    }
    private fun policy(revision: Long, lost: Boolean, commands: JSONArray = JSONArray()) = JSONObject()
        .put("device_id", "r1-a").put("policy_revision", revision).put("lost", lost).put("revoked", false)
        .put("lost_message", if (lost) "Please return this R1" else "").put("commands", commands)
    private fun checkIn() = onWorker { app.workspace.management.checkIn() }
    private fun onWorker(work: suspend () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit { runBlocking { work() } }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!future.isDone && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            future.get(1, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }
    @Test fun wakeFenceHidesEveryScreenThenLostPersistsUntilNewOwnerRestore() {
        MockWebServer().use { server ->
            server.start(); enrolled(server.url("/").toString())
            app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
            val controller = Robolectric.buildActivity(DeviceActivity::class.java).create()
            val activity = controller.get()
            activity.setContentView(TextView(activity).apply { text = "Private cached history" })
            activity.applyRecoveryGate()
            val dialog = activity.showDeviceDialog(android.app.AlertDialog.Builder(activity).setMessage("A private saved reply"))
            assertTrue(dialog.isShowing)
            app.workspace.requireRecovery(checkNow = false)
            assertFalse(dialog.isShowing)
            assertEquals(View.INVISIBLE, activity.findViewById<View>(android.R.id.content).visibility)
            assertFalse(app.workspace.networkAllowed)
            server.enqueue(MockResponse().setBody(policy(2, true).toString()))
            checkIn()
            assertTrue(app.workspace.quarantined)
            assertFalse(app.workspace.recoveryPending)
            assertEquals(2L, app.workspace.identity.value.getLong("applied_policy_revision"))
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, activity.findViewById<View>(android.R.id.content).importantForAccessibility)
            server.enqueue(MockResponse().setResponseCode(503))
            runCatching { checkIn() }
            assertTrue(app.workspace.blocked)
            assertEquals(2L, app.workspace.identity.value.getLong("policy_revision"))
            server.enqueue(MockResponse().setBody(policy(3, false).toString()))
            checkIn()
            assertFalse(app.workspace.blocked)
            assertTrue(app.workspace.networkAllowed)
            assertEquals(View.VISIBLE, activity.findViewById<View>(android.R.id.content).visibility)
            controller.destroy()
        }
    }
    @Test fun newLostPolicyInReceivedReceiptPreventsTheAdmittedNormalAction() {
        MockWebServer().use { server ->
            val states = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.endsWith("check-in")) return MockResponse().setBody(policy(1, false, JSONArray().put(JSONObject()
                        .put("id", "cmd").put("device_id", "r1-a").put("type", "device_tool")
                        .put("expires_at", System.currentTimeMillis() + 60_000)
                        .put("payload", JSONObject().put("tool", "show").put("arguments", JSONObject().put("text", "Must never show"))))).toString())
                    val body = JSONObject(request.body.readUtf8()); states += body.getString("status")
                    return MockResponse().setBody(policy(2, true).toString())
                }
            }
            server.start(); enrolled(server.url("/").toString())
            checkIn()
            assertEquals(listOf("received", "failed"), states.toList())
            assertTrue(app.workspace.quarantined)
            assertTrue(app.notices.state.value.none { it.text == "Must never show" })
            assertEquals(2L, app.workspace.identity.value.getLong("applied_policy_revision"))
        }
    }
    @Test fun deniedReceiptAppliesLostPolicyWithoutExecutingOrRetryingTheCommand() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.endsWith("check-in"))
                    MockResponse().setBody(policy(1, false, JSONArray().put(JSONObject().put("id", "cmd").put("device_id", "r1-a")
                        .put("type", "show_message").put("expires_at", System.currentTimeMillis() + 60_000)
                        .put("payload", JSONObject().put("message", "Not admitted")))).toString())
                else MockResponse().setResponseCode(403).setBody(policy(2, true).put("code", "device_restricted").toString())
            }
            server.start(); enrolled(server.url("/").toString()); checkIn()
            assertTrue(app.workspace.quarantined)
            assertEquals("unknown", app.workspace.store.item("receipts", "cmd")!!.getString("status"))
            assertTrue(app.notices.state.value.none { it.text == "Not admitted" })
        }
    }
    @Test fun staleHealthyReplyCannotClearRestrictionAfterANewerWakeBoundary() {
        MockWebServer().use { server ->
            val admitted = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.endsWith("/notes")) return MockResponse().setResponseCode(403).setBody("{\"code\":\"device_restricted\"}")
                    admitted.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody(policy(1, false).toString())
                }
            }
            server.start(); enrolled(server.url("/").toString())
            app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit { runBlocking { app.workspace.management.checkIn() } }
                assertTrue(admitted.await(5, TimeUnit.SECONDS))
                try { app.workspace.api("GET", "/notes"); fail("Expected workspace authorization rejection") }
                catch (error: HubException) { assertEquals(403, error.status) }
                release.countDown()
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!future.isDone && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
                future.get(1, TimeUnit.SECONDS)
                assertTrue(app.workspace.identity.value.getBoolean("revoked"))
                assertTrue(app.workspace.blocked)
                assertFalse(app.workspace.networkAllowed)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }
    @Test fun strongerAndroidSecurityIsUsedAutomaticallyOnlyWithExistingLocalConsent() {
        enrolled()
        app.workspace.identity.update { it.put("lost", true) }
        val keys = app.getSystemService(KeyguardManager::class.java)
        val policies = app.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(app, AdminReceiver::class.java)
        app.workspace.management.refreshProtection()
        assertTrue(app.workspace.quarantined)
        assertFalse(keys.isDeviceLocked)
        assertFalse(app.workspace.management.security().getBoolean("secure_lock_available"))
        shadowOf(keys).setIsDeviceSecure(true)
        shadowOf(policies).setActiveAdmin(admin)
        shadowOf(policies).grantPolicy(admin, DeviceAdminInfo.USES_POLICY_FORCE_LOCK)
        app.workspace.management.refreshProtection()
        assertFalse(keys.isDeviceLocked) // Android capability alone does not grant local permission.
        app.workspace.identity.permission("secure_lock", true)
        app.workspace.management.refreshProtection()
        assertTrue(keys.isDeviceLocked)
        assertTrue(app.workspace.management.security().getBoolean("secure_lock_available"))
        assertTrue(app.workspace.management.security().getBoolean("secure_lock_applied"))
        app.workspace.identity.update { it.put("lost", false) }
        app.workspace.management.refreshProtection()
        assertFalse(app.workspace.management.security().getBoolean("secure_lock_applied"))
    }
    @Test fun clearingWorkspaceDoesNotPermitReEnrollmentWhileLostOrWithoutFreshRecovery() {
        enrolled()
        app.workspace.identity.update { it.put("local_workspace_cleared", true) }
        assertFalse(app.workspace.canReenroll)
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
        assertTrue(app.workspace.canReenroll)
        app.workspace.identity.update { it.put("lost", true) }
        assertFalse(app.workspace.canReenroll)
        app.workspace.identity.update { it.put("lost", false).put("revoked", true) }
        assertFalse(app.workspace.canReenroll)
    }
    @Test fun screenWakeFencesAPreviouslyVerifiedDeviceEvenWhenTheHostIsUnreachable() {
        enrolled()
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
        assertTrue(app.workspace.networkAllowed)
        app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.workspace.recoveryPending)
        assertTrue(app.workspace.blocked)
        assertFalse(app.workspace.networkAllowed)
    }
    @Test fun appliedPolicyIsAcknowledgedImmediatelyBeforeRecoveryCommandsAreRequested() {
        MockWebServer().use { server ->
            val packets = CopyOnWriteArrayList<JSONObject>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    packets += JSONObject(request.body.readUtf8())
                    return MockResponse().setBody(policy(2, true).toString())
                }
            }
            server.start(); enrolled(server.url("/").toString())
            onWorker { app.workspace.recoveryOnly() }
            assertEquals(2, packets.size)
            assertEquals(0L, packets[0].getLong("applied_policy_revision"))
            assertEquals(2L, packets[1].getLong("applied_policy_revision"))
            assertTrue(packets[1].getJSONObject("security").getBoolean("workspace_restricted"))
        }
    }
    @Test fun sleepingOwnerRelayWaitsForVerificationAndPreservesItsOriginalAdmission() {
        enrolled()
        val owner = "+14055550123"
        @Suppress("UNCHECKED_CAST")
        val config = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store)
            as kotlinx.coroutines.flow.MutableStateFlow<dev.r1ptt.data.Config>
        config.value = config.value.copy(hermes = config.value.hermes.copy(passthrough = true, owner = owner))
        app.workspace.requireRecovery(checkNow = false)
        val asked = CopyOnWriteArrayList<String>(); val replies = CopyOnWriteArrayList<String>()
        val sms = dev.r1ptt.messages.SmsController(app) { true }
        val relay = dev.r1ptt.hermes.HermesTexts(app, { sms }, ask = { asked += it; "A saved reply" },
            reply = { _, reply -> replies += reply; true })
        val record = dev.r1ptt.messages.SmsRecord("sleeping-sms", owner, "One request", System.currentTimeMillis(), 1, true)
        dev.r1ptt.messages.SmsStore(app).use { it.insert(record) }
        assertTrue(relay.received(record))
        val waiting = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!relay.last.startsWith("Waiting for device") && System.nanoTime() < waiting) Thread.sleep(5)
        assertTrue(relay.last.startsWith("Waiting for device"))
        assertTrue(asked.isEmpty()); assertTrue(replies.isEmpty())
        val prefs = app.getSharedPreferences("hermes_texts", 0)
        assertTrue(prefs.contains("pending.${record.id}"))
        assertFalse(prefs.getBoolean("asking.${record.id}", false))
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = android.net.NetworkCapabilities()
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
        relay.resume()
        val completed = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (replies.isEmpty() && System.nanoTime() < completed) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(listOf("One request"), asked.toList())
        assertEquals(listOf("A saved reply"), replies.toList())
        relay.resume(); Thread.sleep(50)
        assertEquals(1, asked.size); assertEquals(1, replies.size)
    }

    @Test fun aRecoveryWaitCannotSubmitAnExpiredOwnerRequestAfterReconnect() {
        enrolled()
        val owner = "+14055550123"
        @Suppress("UNCHECKED_CAST")
        val config = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store)
            as kotlinx.coroutines.flow.MutableStateFlow<dev.r1ptt.data.Config>
        config.value = config.value.copy(hermes = config.value.hermes.copy(passthrough = true, owner = owner))
        app.workspace.requireRecovery(checkNow = false)
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000)
        val asked = CopyOnWriteArrayList<String>()
        val sms = dev.r1ptt.messages.SmsController(app) { true }
        val relay = dev.r1ptt.hermes.HermesTexts(app, { sms }, ask = { asked += it; "Never" }, reply = { _, _ -> error("No reply allowed") }, clock = now::get)
        val record = dev.r1ptt.messages.SmsRecord("expired-sms", owner, "Stale action", now.get(), 1, true)
        dev.r1ptt.messages.SmsStore(app).use { it.insert(record) }
        assertTrue(relay.received(record))
        val prefs = app.getSharedPreferences("hermes_texts", 0)
        val waiting = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!prefs.getBoolean("recovery_wait.${record.id}", false) && System.nanoTime() < waiting) Thread.sleep(5)
        assertTrue(prefs.getBoolean("recovery_wait.${record.id}", false))
        now.addAndGet(dev.r1ptt.hermes.HermesTexts.GIVE_UP_MS + 1)
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
        relay.resume()
        val retired = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!prefs.getBoolean("done.${record.id}", false) && System.nanoTime() < retired) Thread.sleep(5)
        assertTrue(prefs.getBoolean("done.${record.id}", false))
        assertTrue(asked.isEmpty())
        assertTrue(relay.last.contains("no request was submitted"))
    }

    @Test fun intentionalPocketModeRadioLossFencesWithoutWakingRadiosAgain() {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = shadowOf(cm).networkCallbacks.single { it.javaClass.name.contains("RecoveryMonitor") }
        val network = requireNotNull(cm.activeNetwork)
        callback.onAvailable(network) // Before enrollment there is no recovery work to run.
        enrolled()
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
        app.getSharedPreferences("radio", 0).edit().putBoolean("cut", true).commit()
        callback.onLost(network)
        assertTrue(app.workspace.blocked)
        assertFalse(app.workspace.networkAllowed)
        @Suppress("UNCHECKED_CAST")
        val holds = app.radio.javaClass.getDeclaredField("holds").apply { isAccessible = true }.get(app.radio) as Set<String>
        assertTrue(holds.isEmpty())
        assertTrue(app.getSharedPreferences("radio", 0).getBoolean("cut", false))
    }

}
