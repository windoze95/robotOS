package dev.r1ptt.hermes

import android.Manifest
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Looper
import dev.r1ptt.App
import dev.r1ptt.data.Config
import dev.r1ptt.data.HermesLink
import dev.r1ptt.messages.SmsController
import dev.r1ptt.messages.SmsRecord
import dev.r1ptt.messages.SmsStore
import dev.r1ptt.messages.SmsTransport
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSubscriptionManager
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [34], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceApiTest {
    private lateinit var app: App
    private lateinit var api: DeviceApi
    private val sent = CopyOnWriteArrayList<SmsRecord>()
    private val token = "d".repeat(40)

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.deleteDatabase("messages.db")
        app.getSharedPreferences("device_api", 0).edit().clear().commit()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)
        val sms = SmsController(app, object : SmsTransport {
            override fun divide(body: String, subscription: Int) = listOf(body)
            override fun send(record: SmsRecord, texts: List<String>, sent: ArrayList<PendingIntent>, delivery: ArrayList<PendingIntent>) {
                this@DeviceApiTest.sent.add(record)
            }
        }) { true }
        api = DeviceApi(app, sms, settleMs = 300)
        configure { it.copy(hermes = HermesLink(deviceApi = true, token = token, dailySendLimit = 2)) }
        SmsStore(app).use { it.addRecipient("Sam", "+15551234567") }
    }

    @Suppress("UNCHECKED_CAST") private fun configure(update: (Config) -> Config) {
        val state = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store) as MutableStateFlow<Config>
        state.value = update(state.value)
    }

    /** handle() blocks on main-thread SMS work, so it runs off the (paused) main looper. */
    private fun call(raw: String): Pair<Int, JSONObject> {
        val request = requireNotNull(DeviceApi.Request.read(ByteArrayInputStream(raw.toByteArray())))
        val future = Executors.newSingleThreadExecutor().submit<Pair<Int, JSONObject>> { api.handle(request) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        return future.get(1, TimeUnit.SECONDS)
    }

    private fun get(path: String, auth: String? = token) = call("GET $path HTTP/1.1\r\nHost: r1\r\n" +
        (auth?.let { "Authorization: Bearer $it\r\n" } ?: "") + "\r\n")

    private fun post(path: String, body: JSONObject, auth: String = token): Pair<Int, JSONObject> {
        val bytes = body.toString().toByteArray()
        return call("POST $path HTTP/1.1\r\nAuthorization: Bearer $auth\r\nContent-Length: ${bytes.size}\r\n\r\n${body}")
    }

    @Test fun onlyTailnetAndLoopbackPeersWithTheTokenAreServed() {
        for (a in listOf("100.64.0.1", "100.101.102.103", "100.127.255.255", "127.0.0.1", "::1", "fd7a:115c:a1e0::1234"))
            assertTrue(a, DeviceApi.allowed(InetAddress.getByName(a)))
        for (a in listOf("100.128.0.1", "100.63.255.255", "192.168.1.20", "10.0.0.2", "fe80::1", "fd7a:115c:a1e1::1"))
            assertFalse(a, DeviceApi.allowed(InetAddress.getByName(a)))
        assertTrue(DeviceApi.authorized("Bearer $token", token))
        for (header in listOf(null, "Bearer ", "Bearer ${"x".repeat(40)}", token, "Basic $token")) assertFalse(DeviceApi.authorized(header, token))
        assertFalse(DeviceApi.authorized("Bearer short", "short"))
        assertEquals(401, get("/v1/status", auth = null).first)
        assertEquals(401, get("/v1/status", auth = "x".repeat(40)).first)
        configure { it.copy(hermes = it.hermes.copy(deviceApi = false)) }
        assertEquals(401, get("/v1/status").first)
    }

    @Test fun requestsAreBoundedAndParsed() {
        val ok = DeviceApi.Request.read(ByteArrayInputStream("GET /v1/texts?with=Mom%20%26%20Dad&limit=5 HTTP/1.1\r\nX-A: b\r\n\r\n".toByteArray()))!!
        assertEquals("/v1/texts", ok.path)
        assertEquals(mapOf("with" to "Mom & Dad", "limit" to "5"), ok.query)
        assertEquals("b", ok.headers["x-a"])
        for (raw in listOf("GET /\r\n\r\n", "GET / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n", "GET / HTTP/1.1\r\nX: ${"a".repeat(9000)}\r\n\r\n", "GET / HTTP/1.1\r\n"))
            assertNull(raw.take(40), DeviceApi.Request.read(ByteArrayInputStream(raw.toByteArray())))
    }

    @Test fun hermesSendsToSavedNamesAndSpokenNumbersOnceEach() {
        val (code, body) = post("/v1/sms", JSONObject().put("to", "Sam").put("body", "Running late"))
        assertEquals(body.toString(), 200, code)
        assertEquals("+15551234567", body.getString("to"))
        assertEquals("sending", body.getString("status"))
        assertEquals(listOf("+15551234567" to "Running late"), sent.map { it.peer to it.body })
        val status = get("/v1/sms/${body.getString("id")}")
        assertEquals(200, status.first)
        assertEquals("sending", status.second.getString("status"))
        val spoken = post("/v1/sms", JSONObject().put("to", "five five five one two three four five six eight").put("body", "Hi"))
        assertEquals(200, spoken.first)
        assertEquals(2, sent.size)
        assertEquals(1, SmsStore(app).use { it.conversation("+15551234567").size })
    }

    @Test fun unknownNamesBadInputAndTheDailyLimitSendNothing() {
        val unknown = post("/v1/sms", JSONObject().put("to", "Nobody").put("body", "Hi"))
        assertEquals(404, unknown.first)
        assertTrue(unknown.second.getString("error").contains("Nothing was sent"))
        assertEquals(400, post("/v1/sms", JSONObject().put("to", "Sam")).first)
        assertEquals(400, post("/v1/sms", JSONObject().put("to", "Sam").put("body", "x".repeat(1601))).first)
        assertEquals(400, call("POST /v1/sms HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: 3\r\n\r\n{x}").first)
        assertTrue(sent.isEmpty())
        repeat(2) { assertEquals(200, post("/v1/sms", JSONObject().put("to", "+15557654321").put("body", "Hi $it")).first) }
        val limited = post("/v1/sms", JSONObject().put("to", "+15557654321").put("body", "Hi again"))
        assertEquals(429, limited.first)
        assertEquals(2, sent.size)
        assertEquals(2, get("/v1/status").second.getInt("texts_sent_today"))
    }

    @Test fun statusTextsAndRecipientsDescribeTheDevice() {
        val status = get("/v1/status").second
        for (key in listOf("battery_percent", "charging", "online", "airplane_mode", "can_text", "texts_sent_today", "daily_text_limit"))
            assertTrue(key, status.has(key))
        assertEquals("Sam", get("/v1/recipients").second.getJSONArray("recipients").getJSONObject(0).getString("name"))
        post("/v1/sms", JSONObject().put("to", "Sam").put("body", "Hello"))
        val thread = get("/v1/texts?with=sam").second
        assertEquals("+15551234567", thread.getString("with"))
        assertEquals("Hello", thread.getJSONArray("messages").getJSONObject(0).getString("body"))
        assertEquals("out", thread.getJSONArray("messages").getJSONObject(0).getString("direction"))
        assertEquals(1, get("/v1/texts").second.getJSONArray("conversations").length())
        assertEquals(404, get("/v1/texts?with=Nobody").first)
        assertEquals(404, get("/v1/nothing").first)
    }
}
