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
import dev.r1ptt.ui.DeviceActivity
import dev.r1ptt.workspace.WorkspaceActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
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
    private var screen: ActivityController<out DeviceActivity>? = null

    @After fun closeScreen() { screen?.pause()?.stop()?.destroy(); app.deviceApi.stop(); app.history.close() }

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
            "/v1/recipients" to JSONObject().put("name", "Pat").put("number", "+15557654321"),
            "/v1/apps/open" to JSONObject()))
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

    private fun notesScreen(): WorkspaceActivity {
        val controller = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.notesIntent(app)).setup()
        screen = controller
        return controller.get()
    }

    private fun views(view: android.view.View): List<android.view.View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun seedNote(id: String = "note-1") = app.workspace.store.put("notes", JSONObject()
        .put("id", id).put("kind", "text").put("title", "Packing list").put("text", "Charger and headphones"))

    @Test fun openAppNavigatesToListOrExactNoteAndReplaysWithoutNavigatingAgain() {
        seedNote()
        configure { it.copy(hermes = it.hermes.copy(allowOpenApps = true)) }
        val activity = notesScreen()
        val listRequest = JSONObject().put("app", "notes").put("request_id", "open-notes-list-0001")
        assertEquals("notes", post("/v1/apps/open", listRequest).second.getString("app"))
        val detail = post("/v1/apps/open", JSONObject().put("app", "notes").put("item_id", "note-1").put("request_id", "open-notes-note-0001"))
        assertEquals(detail.second.toString(), 200, detail.first)
        assertEquals("opened", detail.second.getString("status"))
        assertEquals("note-1", detail.second.getString("item_id"))
        fun edits() = views(activity.window.decorView).filterIsInstance<android.widget.EditText>()
        assertEquals(listOf("Packing list", "Charger and headphones"), edits().map { it.text.toString() })
        // Replaying an earlier list request must not replace the note now on screen.
        assertEquals(200, post("/v1/apps/open", listRequest).first)
        assertEquals(2, edits().size)
        edits()[1].setText("Unsaved local edit")
        assertEquals(409, post("/v1/apps/open", JSONObject().put("app", "notes")).first)
        assertEquals("Unsaved local edit", edits()[1].text.toString())
        assertTrue(get("/v1/status").second.getJSONObject("permissions").getBoolean("open_app"))
    }

    @Test fun openAppRejectsMissingMalformedAndUnavailableTargets() {
        configure { it.copy(hermes = it.hermes.copy(allowOpenApps = true)) }
        assertEquals(409, post("/v1/apps/open", JSONObject().put("app", "notes")).first)
        notesScreen()
        assertEquals(404, post("/v1/apps/open", JSONObject().put("app", "notes").put("item_id", "missing")).first)
        for (value in listOf("", "../notes", "x".repeat(201), 7, JSONObject.NULL))
            assertEquals(400, post("/v1/apps/open", JSONObject().put("app", "notes").put("item_id", value)).first)
        for (target in listOf("", "com.android.settings", "android.intent.action.VIEW", 7, JSONObject.NULL))
            assertEquals(400, post("/v1/apps/open", JSONObject().put("app", target)).first)
        assertEquals(400, post("/v1/apps/open", JSONObject().put("app", "messages").put("item_id", "note-1")).first)
        app.turns.guardApprovalReview { }
        assertEquals(409, post("/v1/apps/open", JSONObject().put("app", "notes")).first)
        app.turns.guardApprovalReview(null)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(false)
        assertEquals(409, post("/v1/apps/open", JSONObject().put("app", "notes")).first)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        assertEquals(409, post("/v1/apps/open", JSONObject().put("app", "notes")).first)
    }

    @Test fun openAppLaunchesTheRequestedScreenAndRechecksPermissionAtLaunch() {
        seedNote()
        configure { it.copy(hermes = it.hermes.copy(allowOpenApps = true)) }
        val foreground = Robolectric.buildActivity(DeviceActivity::class.java).setup()
        screen = foreground
        val result = post("/v1/apps/open", JSONObject().put("app", "notes").put("item_id", "note-1"))
        assertEquals(result.second.toString(), 200, result.first)
        assertEquals("opening", result.second.getString("status"))
        val intent = shadowOf(foreground.get()).nextStartedActivity
        assertEquals(WorkspaceActivity::class.java.name, intent.component!!.className)
        val opened = Robolectric.buildActivity(WorkspaceActivity::class.java, android.content.Intent(intent)).create()
        assertTrue(views(opened.get().window.decorView).filterIsInstance<android.widget.EditText>().any { it.text.toString() == "Packing list" })
        opened.destroy()
        configure { it.copy(hermes = it.hermes.copy(allowOpenApps = false)) }
        val revoked = Robolectric.buildActivity(WorkspaceActivity::class.java, android.content.Intent(intent)).create()
        assertTrue(revoked.get().isFinishing)
        revoked.destroy()
    }

    @Test fun openAppFetchesAnUnsyncedSharedNoteBeforeNavigation() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val note = JSONObject().put("id", "remote-note").put("title", "From the host").put("text", "Freshly saved").put("kind", "text")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(note.toString()))
            val activity = notesScreen()
            val identity = JSONObject().put("device_id", "paired-r1").put("base_url", server.url("/").toString())
                .put("device_token", "content-token").put("recovery_token", "recovery-token")
            val disk = object : dev.r1ptt.data.ConfigBlobStorage { var value: String? = null; override fun read() = value; override fun write(blob: String) { value = blob } }
            val cipher = object : dev.r1ptt.data.ConfigCipher { override fun seal(plain: String, allowKeyCreation: Boolean) = plain; override fun open(blob: String) = blob }
            val codec = object : dev.r1ptt.data.ConfigCodec<JSONObject> { override fun encode(value: JSONObject) = value.toString(); override fun decode(plain: String) = JSONObject(plain) }
            app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
                .set(app.workspace.identity, dev.r1ptt.data.EncryptedConfigPersistence(identity, disk, cipher, codec))
            app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
            configure { it.copy(hermes = it.hermes.copy(allowOpenApps = true)) }
            val result = post("/v1/apps/open", JSONObject().put("app", "notes").put("item_id", "remote-note"))
            assertEquals(result.second.toString(), 200, result.first)
            assertEquals("/api/v1/notes/remote-note", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            assertEquals("From the host", app.workspace.store.item("notes", "remote-note")!!.getString("title"))
            assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.EditText>().any { it.text.toString() == "Freshly saved" })
        }
    }

    @Test fun openAppRoutesEveryBuiltInScreenWithoutExternalIntents() {
        configure { it.copy(hermes = it.hermes.copy(allowOpenApps = true)) }
        val foreground = Robolectric.buildActivity(DeviceActivity::class.java).setup()
        screen = foreground
        val targets = mapOf(
            "home" to dev.r1ptt.HomeActivity::class.java, "workspace" to WorkspaceActivity::class.java,
            "notes" to WorkspaceActivity::class.java, "prompt_builder" to WorkspaceActivity::class.java,
            "chats" to dev.r1ptt.ChatsActivity::class.java, "messages" to dev.r1ptt.messages.MessagesActivity::class.java,
            "hermes" to HermesActivity::class.java, "settings" to dev.r1ptt.SettingsActivity::class.java,
            "quick_controls" to dev.r1ptt.QuickControlsActivity::class.java, "updates" to dev.r1ptt.update.UpdateActivity::class.java,
            "host_devices" to dev.r1ptt.workspace.HubSettingsActivity::class.java)
        for ((name, type) in targets) {
            val result = post("/v1/apps/open", JSONObject().put("app", name))
            assertEquals(result.second.toString(), 200, result.first)
            assertEquals("opening", result.second.getString("status"))
            val intent = shadowOf(foreground.get()).nextStartedActivity
            assertEquals(type.name, intent.component!!.className)
            assertEquals(app.packageName, intent.component!!.packageName)
            assertNull(intent.action)
            assertNull(intent.data)
        }
    }

    @Test fun openAppShowsPromptBuilderOrSavedPromptWithoutRunningOrEditingIt() {
        val prompt = JSONObject().put("id", "saved-prompt").put("title", "Plan the trip").put("instruction", "Use these notes")
            .put("revision", 1).put("blocks", org.json.JSONArray())
        app.workspace.store.put("prompts", prompt)
        configure { it.copy(hermes = it.hermes.copy(allowOpenApps = true)) }
        val activity = notesScreen()
        assertEquals(200, post("/v1/apps/open", JSONObject().put("app", "prompt_builder")).first)
        assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Prompt Builder" })
        assertEquals(200, post("/v1/apps/open", JSONObject().put("app", "prompt_builder").put("item_id", "saved-prompt")).first)
        assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Plan the trip" })
        assertEquals(prompt.toString(), app.workspace.store.item("prompts", "saved-prompt").toString())
        assertTrue(app.workspace.store.items("runs").isEmpty())
        assertTrue(app.workspace.store.items("requests").isEmpty())
    }

    @Test fun hermesReadsTheOwnersTextsWithoutThePassphrase() {
        val owner = "+14055550123"
        configure { it.copy(hermes = it.hermes.copy(passthrough = true, owner = owner, passphrase = "otter-42")) }
        SmsStore(app).use {
            it.insert(SmsRecord("owner-text", owner, "Otter-42: what's on today?", 1_000L, 1, true))
            it.insert(SmsRecord("sam-text", "+15551234567", "otter-42 is not a secret to Sam", 2_000L, 1, true))
        }
        assertEquals("what's on today?", get("/v1/texts?with=%2B14055550123").second.getJSONArray("messages").getJSONObject(0).getString("body"))
        val threads = get("/v1/texts").second.getJSONArray("conversations")
        val mine = (0 until threads.length()).map(threads::getJSONObject).single { it.getString("with") == owner }
        assertEquals("what's on today?", mine.getJSONObject("last").getString("body"))
        assertEquals("otter-42 is not a secret to Sam", get("/v1/texts?with=sam").second.getJSONArray("messages").getJSONObject(0).getString("body"))
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
