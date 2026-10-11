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
        app.getSharedPreferences("device_receipts", 0).edit().clear().commit()
        app.getSharedPreferences("device_notices", 0).edit().clear().commit()
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

    /** Saves stay in memory: Robolectric has no Android Keystore. Install after [configure] so it starts from that state. */
    private fun memoryStore() {
        val memory = object : dev.r1ptt.data.ConfigBlobStorage {
            var blob: String? = null
            override fun read() = blob
            override fun write(blob: String) { this.blob = blob }
        }
        val plain = object : dev.r1ptt.data.ConfigCipher {
            override fun seal(plain: String, allowKeyCreation: Boolean) = plain
            override fun open(blob: String) = blob
        }
        val codec = object : dev.r1ptt.data.ConfigCodec<Config> {
            override fun encode(value: Config) = dev.r1ptt.data.ConfigJson.toJson(value).toString()
            override fun decode(plain: String) = dev.r1ptt.data.ConfigJson.merge(Config(), JSONObject(plain))
        }
        app.store.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.store, dev.r1ptt.data.EncryptedConfigPersistence(app.store.value, memory, plain, codec))
    }

    @Test fun hermesNamesItselfOnceAndThenOnlyTheUserCanRenameIt() {
        memoryStore()
        assertTrue(get("/v1/status").second.getBoolean("name_settable"))
        assertEquals(400, post("/v1/name", JSONObject().put("name", "<b>Robot</b>")).first)
        assertEquals("", app.store.value.agent.name)
        val request = JSONObject().put("name", "  Robot ").put("request_id", "name-request-0001")
        assertEquals("Robot", post("/v1/name", request).second.getString("name"))
        assertEquals("Robot", app.store.value.agent.name)
        val status = get("/v1/status").second
        assertEquals("Robot", status.getString("name"))
        assertFalse(status.getBoolean("name_settable"))
        // The same request recovers its receipt; a new one cannot rename.
        assertEquals(200, post("/v1/name", request).first)
        assertEquals(403, post("/v1/name", JSONObject().put("name", "Jarvis")).first)
        assertEquals(401, post("/v1/name", JSONObject().put("name", "Jarvis"), auth = "e".repeat(40)).first)
        assertEquals("Robot", app.store.value.agent.name)
        // Clearing the name on the R1 lets Hermes name itself again.
        app.store.update { it.copy(agent = it.agent.copy(name = "")) }
        assertEquals(200, post("/v1/name", JSONObject().put("name", "Jarvis")).first)
        assertEquals("Jarvis", app.store.value.agent.displayName)
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

    @Test fun receiptReplayDoesNotSendAgainAndIdentityCannotChange() {
        val id = "send-request-id-1234"
        val request = JSONObject().put("to", "Sam").put("body", "Once").put("request_id", id)
        val first = post("/v1/sms", request)
        assertEquals(200, first.first)
        assertEquals(first.second.toString(), post("/v1/sms", request).second.toString())
        assertEquals(1, sent.size)
        assertEquals(first.second.toString(), get("/v1/requests/$id").second.toString())
        assertEquals(409, post("/v1/sms", request.put("body", "Changed")).first)
        assertEquals(1, sent.size)
        assertEquals(1, api.sentToday())
    }

    @Test fun controlsRequireTheirOwnPermissionAndShowPersists() {
        for ((path, body) in listOf("/v1/speak" to JSONObject().put("text", "Hi"),
            "/v1/show" to JSONObject().put("text", "Hi"), "/v1/volume" to JSONObject().put("level", 0.5),
            "/v1/recipients" to JSONObject().put("name", "Pat").put("number", "+15557654321")))
            assertEquals(path, 403, post(path, body).first)
        configure { it.copy(hermes = it.hermes.copy(allowShow = true, allowVolume = true, allowSaveRecipient = true)) }
        val shown = post("/v1/show", JSONObject().put("text", "Meeting at 3"))
        assertEquals(200, shown.first)
        assertEquals("Meeting at 3", DeviceNotices(app).state.value.single().text)
        app.notices.dismiss(shown.second.getString("id"))
        assertTrue(DeviceNotices(app).state.value.isEmpty())
        val volume = post("/v1/volume", JSONObject().put("level", 0.5))
        assertEquals(200, volume.first)
        assertTrue(volume.second.getDouble("level") in 0.0..1.0)
        assertEquals(400, post("/v1/volume", JSONObject().put("level", 2)).first)
        assertEquals(200, post("/v1/recipients", JSONObject().put("name", "Pat").put("number", "+15557654321")).first)
        assertEquals(2, get("/v1/recipients").second.getJSONArray("recipients").length())
    }

    @Test fun revokedPermissionBlocksAnActionAlreadyQueuedToTheMainThread() {
        configure { it.copy(hermes = it.hermes.copy(allowShow = true)) }
        val body = JSONObject().put("text", "Do not show this").put("request_id", "queued-request-id-1234")
        val request = DeviceApi.Request("POST", "/v1/show", emptyMap(), mapOf("authorization" to "Bearer $token"), body.toString())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Pair<Int, JSONObject>> { api.handle(request) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (app.getSharedPreferences("device_receipts", 0).all.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            configure { it.copy(hermes = it.hermes.copy(allowShow = false)) }
            while (!future.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertEquals(403, future.get(1, TimeUnit.SECONDS).first)
            assertTrue(app.notices.state.value.isEmpty())
        } finally { executor.shutdownNow() }
    }

    @Test fun speakingHonorsQuietAndPlaybackConflicts() {
        configure { it.copy(quiet = true, hermes = it.hermes.copy(allowSpeak = true)) }
        assertEquals(409, post("/v1/speak", JSONObject().put("text", "Hello")).first)
        configure { it.copy(quiet = false) }
        api = DeviceApi(app, speak = { false })
        assertEquals(409, post("/v1/speak", JSONObject().put("text", "Hello")).first)
        api = DeviceApi(app, speak = { true })
        assertEquals(200, post("/v1/speak", JSONObject().put("text", "Hello")).first)
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
