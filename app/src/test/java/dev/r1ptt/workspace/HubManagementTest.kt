package dev.r1ptt.workspace

import android.os.Looper
import dev.r1ptt.App
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class HubManagementTest {
    @After fun closeHistory() { (RuntimeEnvironment.getApplication() as App).history.close() }
    @Test fun receivedAndAppliedReceiptsHaveObjectResultsAndReplayDoesNotRepeatAction() {
        MockWebServer().use { server ->
            val statuses = CopyOnWriteArrayList<String>()
            val id = newId()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Bearer recovery-token", request.getHeader("Authorization"))
                    val body = JSONObject(request.body.readUtf8())
                    if (request.path!!.endsWith("/check-in")) return MockResponse().setBody(JSONObject()
                        .put("device_id", "device-a").put("name", "Renamed in console").put("lost", false).put("revoked", false)
                        .put("settings_revision", 0).put("settings", JSONObject()).put("commands", JSONArray().put(JSONObject()
                            .put("id", id).put("device_id", "device-a").put("type", "show_message")
                            .put("expires_at", System.currentTimeMillis() + 60_000).put("payload", JSONObject().put("message", "Please return this R1")))).toString())
                    assertTrue(body.get("result") is JSONObject)
                    assertTrue(body.getJSONObject("result").has("message"))
                    if (request.path!!.contains("/old-expired/")) return MockResponse().setResponseCode(409).setBody("{\"code\":\"receipt_conflict\"}")
                    statuses += body.getString("status")
                    return MockResponse().setBody("{}")
                }
            }
            server.start()
            val app = RuntimeEnvironment.getApplication() as App
            val identity = JSONObject().put("device_id", "device-a").put("base_url", server.url("/").toString())
                .put("device_token", "workspace-token").put("recovery_token", "recovery-token")
                .put("permissions", JSONObject().put("remote_management", true))
            val memory = object : ConfigBlobStorage { var blob: String? = null; override fun read() = blob; override fun write(blob: String) { this.blob = blob } }
            val cipher = object : ConfigCipher { override fun seal(plain: String, allowKeyCreation: Boolean) = plain; override fun open(blob: String) = blob }
            val codec = object : ConfigCodec<JSONObject> { override fun encode(value: JSONObject) = value.toString(); override fun decode(plain: String) = JSONObject(plain) }
            app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
                .set(app.workspace.identity, EncryptedConfigPersistence(identity, memory, cipher, codec))
            app.workspace.store.put("receipts", JSONObject().put("id", "old-expired").put("device_id", "device-a")
                .put("status", "unknown").put("ack_pending", true).put("result", "Legacy string result"))
            val executor = Executors.newSingleThreadExecutor()
            try {
                repeat(2) {
                    val future = executor.submit { runBlocking { app.workspace.management.checkIn() } }
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!future.isDone && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
                    future.get(1, TimeUnit.SECONDS)
                }
                assertEquals(listOf("received", "applied"), statuses.toList())
                assertEquals(1, app.notices.state.value.count { it.text == "Please return this R1" })
                assertEquals("Renamed in console", app.workspace.identity.value.getString("name"))
                assertEquals("receipt_conflict", app.workspace.store.item("receipts", "old-expired")!!.getString("ack_error"))
                assertFalse(app.workspace.store.item("receipts", "old-expired")!!.getBoolean("ack_pending"))
            } finally { executor.shutdownNow() }
        }
    }
}
