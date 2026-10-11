package dev.r1ptt.workspace

import dev.r1ptt.App
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class SharedChatTest {
    @After fun closeHistory() { (RuntimeEnvironment.getApplication() as App).let { it.deviceApi.stop(); it.history.close() } }
    private fun configured(base: String): App {
        val app = RuntimeEnvironment.getApplication() as App
        val identity = JSONObject().put("base_url", base).put("device_id", "r1-a")
            .put("device_token", "workspace-only").put("recovery_token", "recovery-only")
        val disk = object : ConfigBlobStorage {
            var value: String? = null
            override fun read() = value
            override fun write(blob: String) { value = blob }
        }
        val cipher = object : ConfigCipher {
            override fun seal(plain: String, allowKeyCreation: Boolean) = plain
            override fun open(blob: String) = blob
        }
        val codec = object : ConfigCodec<JSONObject> {
            override fun encode(value: JSONObject) = value.toString()
            override fun decode(plain: String) = JSONObject(plain)
        }
        app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.workspace.identity, EncryptedConfigPersistence(identity, disk, cipher, codec))
        return app
    }
    private class Host(private val dropFirstRunReply: Boolean = false, private val waitForCommand: Boolean = false) : Dispatcher() {
        val receipts = CopyOnWriteArrayList<String>()
        val paths = CopyOnWriteArrayList<String>()
        val submissionKeys = CopyOnWriteArrayList<String>()
        var executions = 0
        private var dropped = false
        private var prompt: JSONObject? = null
        private var run: JSONObject? = null
        private var leaseHeld = false
        private fun response(value: JSONObject, code: Int = 200) = MockResponse().setResponseCode(code).setBody(value.toString())
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.removePrefix("/api/v1")
            paths += path
            assertEquals(if (path.startsWith("/devices/self/")) "Bearer recovery-only" else "Bearer workspace-only", request.getHeader("Authorization"))
            val data = request.body.readUtf8().takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            return when {
                path == "/devices/self/check-in" -> response(JSONObject().put("device_id", "r1-a").put("lost", false).put("revoked", false)
                    .put("settings_revision", 0).put("settings", JSONObject()).put("commands", JSONArray().put(JSONObject()
                        .put("id", "native-command-1").put("device_id", "r1-a").put("type", "device_tool")
                        .put("expires_at", System.currentTimeMillis() + 60_000).put("payload", JSONObject().put("tool", "show")
                            .put("arguments", JSONObject().put("text", "Host tool delivered during the voice request"))))))
                path == "/devices/self/commands/native-command-1/ack" -> {
                    receipts += data.getString("status")
                    if (data.getString("status") == "applied") {
                        assertEquals(200, data.getJSONObject("result").getInt("http_status"))
                        assertEquals("shown", data.getJSONObject("result").getJSONObject("body").getString("status"))
                        run!!.put("status", "completed").put("updated_at", 30L)
                    }
                    response(JSONObject())
                }
                path == "/prompts" && request.method == "POST" -> {
                    prompt = data.put("revision", 1).put("state", "draft").put("created_at", 1L).put("updated_at", 1L)
                    response(prompt!!)
                }
                path.endsWith("/lease") -> {
                    leaseHeld = true
                    response(JSONObject().put("lease_token", "stable-lease").put("revision", prompt!!.getLong("revision")).put("expires_at", System.currentTimeMillis() + 60_000))
                }
                path.endsWith("/release") -> { leaseHeld = false; response(JSONObject()) }
                path.startsWith("/prompts/") && path.endsWith("/run") -> {
                    val key = data.getString("request_id"); submissionKeys += key
                    if (run == null) {
                        executions++
                        run = JSONObject().put("id", "run-1").put("prompt_id", prompt!!.getString("id")).put("prompt_revision", prompt!!.getLong("revision"))
                            .put("status", if (waitForCommand) "running" else "completed").put("output", "Saved reply from Hermes").put("snapshot", prompt!!.copyJson()).put("created_at", 10L).put("updated_at", 20L)
                        prompt!!.put("last_run_id", "run-1")
                    }
                    if (dropFirstRunReply && !dropped) { dropped = true; MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                    else response(run!!)
                }
                path.startsWith("/prompts/") && request.method == "GET" -> prompt?.let { response(it) } ?: response(JSONObject().put("code", "not_found"), 404)
                path.startsWith("/prompts/") && request.method == "PUT" -> {
                    assertTrue(leaseHeld); assertEquals(prompt!!.getLong("revision"), data.getLong("base_revision"))
                    prompt = data.put("revision", data.getLong("base_revision") + 1).put("created_at", 1L).put("updated_at", 2L)
                    response(prompt!!)
                }
                path == "/runs/run-1" -> response(run!!)
                path == "/resume" -> response(JSONObject().put("revision", 0))
                else -> response(JSONObject().put("code", "unexpected"), 404)
            }
        }
    }
    @Test fun completedChatImportsSharedHistoryWhileKeepingLegacyConversation() = runBlocking {
        MockWebServer().use { server ->
            val host = Host(); server.dispatcher = host; server.start()
            val app = configured(server.url("/").toString())
            val legacyId = app.history.convId
            app.history.add(dev.r1ptt.data.Msg.USER, "Existing local chat", conversation = legacyId)
            val shared = SharedChat.conversation(app, legacyId)
            val result = SharedChat.ask(app, shared, "Remember this question", "Be brief")
            assertEquals("Saved reply from Hermes", result)
            assertEquals(1, host.executions)
            assertTrue(app.history.flow.value.chats.any { it.id == legacyId })
            assertTrue(app.history.messages.any { it.text == "Remember this question" })
            assertTrue(app.history.messages.any { it.text == result })
            assertNull(app.workspace.store.item("shared_chat_links", shared))
            SharedChat.clearHistory(app)
            assertFalse(app.history.flow.value.chats.any { it.id.startsWith("shared-") })
            assertTrue(app.history.flow.value.chats.any { it.id == legacyId })
        }
    }
    @Test fun lostSubmissionReplyReusesOneDurableRequestInsteadOfRunningAgain() = runBlocking {
        MockWebServer().use { server ->
            val host = Host(dropFirstRunReply = true); server.dispatcher = host; server.start()
            val app = configured(server.url("/").toString())
            val conversation = SharedChat.conversation(app, app.history.convId)
            val first = try { SharedChat.ask(app, conversation, "Perform once", "Be brief") } catch (_: IOException) { null }
            val result = first ?: SharedChat.ask(app, conversation, "Perform once", "Be brief")
            assertEquals("Saved reply from Hermes", result)
            assertEquals(1, host.executions)
            assertEquals(1, host.submissionKeys.distinct().size)
        }
    }
    @Test fun retryAfterAdmissionReturnsTheCompletedOriginalWithoutAnotherSubmission() = runBlocking {
        MockWebServer().use { server ->
            val host = Host(); server.dispatcher = host; server.start()
            val app = configured(server.url("/").toString())
            val conversation = SharedChat.conversation(app, app.history.convId)
            try {
                SharedChat.ask(app, conversation, "Perform exactly once", "Be brief", onAdmitted = { throw IOException("Process stopped before delivering the reply") })
                fail("Expected interruption after admission")
            } catch (_: IOException) { }
            assertEquals("run-1", app.workspace.store.item("shared_chat_links", conversation)!!.getString("run_id"))
            SharedChat.importHistory(app) // A background sync may import the completed run before retry.
            assertEquals("run-1", app.workspace.store.item("shared_chat_links", conversation)!!.getString("run_id"))
            val result = SharedChat.ask(app, conversation, "Perform exactly once", "Be brief")
            assertEquals("Saved reply from Hermes", result)
            assertEquals(1, host.submissionKeys.size)
            assertEquals(1, host.executions)
            assertNull(app.workspace.store.item("shared_chat_links", conversation))
        }
    }
    @Test fun activeRunDrainsNativeCommandsWithoutUploadingPendingNotes() {
        MockWebServer().use { server ->
            val host = Host(waitForCommand = true); server.dispatcher = host; server.start()
            val app = configured(server.url("/").toString())
            @Suppress("UNCHECKED_CAST")
            val config = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store) as MutableStateFlow<dev.r1ptt.data.Config>
            config.value = config.value.copy(hermes = config.value.hermes.copy(deviceApi = true, token = "x".repeat(32), allowShow = true))
            val pendingNote = app.workspace.store.capture("Offline note", "Keep this out of the tool check-in")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val conversation = SharedChat.conversation(app, app.history.convId)
                val future = executor.submit<String> { runBlocking { SharedChat.ask(app, conversation, "Show a message on this R1", "") } }
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!future.isDone && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
                assertEquals("Saved reply from Hermes", future.get(1, TimeUnit.SECONDS))
                assertEquals(listOf("received", "applied"), host.receipts.toList())
                assertEquals(1, app.notices.state.value.count { it.text == "Host tool delivered during the voice request" })
                assertTrue(app.workspace.store.item("notes", pendingNote.getString("id"))!!.getBoolean("pending"))
                assertFalse(host.paths.any { it == "/notes" || it == "/runs" || it.startsWith("/media") })
                assertEquals(1, host.executions)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun noteCreationReplyLossThenOfflineEditKeepsCreationIdentityAndSyncsLatestText() = runBlocking {
        MockWebServer().use { server ->
            var failReplies = true
            var canonical: JSONObject? = null
            val creationBodies = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JSONObject(request.body.readUtf8())
                    if (request.method == "POST") {
                        creationBodies += body.toString()
                        if (canonical == null) canonical = body.copyJson().put("revision", 1).put("updated_at", 10)
                        if (failReplies) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    } else if (request.method == "PUT") {
                        assertEquals(1L, body.getLong("base_revision"))
                        canonical!!.put("title", body.getString("title")).put("text", body.getString("text")).put("revision", 2).put("updated_at", 20)
                    }
                    return MockResponse().setBody(canonical!!.toString())
                }
            }
            server.start(); val app = configured(server.url("/").toString())
            val note = app.workspace.store.capture("Thought", "Original recording note")
            try { app.workspace.syncNotes(); fail("Expected lost response") } catch (_: IOException) { }
            assertTrue(app.workspace.store.item("notes", note.getString("id"))!!.getBoolean("pending"))
            app.workspace.editNote(note, "Thought edited", "Changed while offline")
            failReplies = false
            app.workspace.syncNotes()
            val synced = app.workspace.store.item("notes", note.getString("id"))!!
            assertEquals(1, creationBodies.distinct().size)
            assertEquals("Changed while offline", synced.getString("text"))
            assertFalse(synced.getBoolean("pending"))
            assertEquals(2, synced.getInt("revision"))
        }
    }
    @Test fun androidJsonNullMediaNeverBecomesLiteralNullAttachment() {
        val note = JSONObject("{\"id\":\"note-1\",\"kind\":\"text\",\"text\":\"A synced text note\",\"media_id\":null}")
        val block = WorkspaceClient.block(note)
        assertTrue(block.isNull("media_id"))
        assertFalse(block.toString().contains("\"media_id\":\"null\""))
        assertEquals("", note.stringOrEmpty("media_id"))
    }
    @Test fun commandCheckInPublishesNewQuarantineImmediately() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("device_id", "r1-a").put("lost", true).put("revoked", false)
                .put("settings_revision", 0).put("settings", JSONObject()).put("commands", JSONArray()).toString()))
            server.start(); val app = configured(server.url("/").toString())
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Boolean> { runBlocking {
                    try { app.workspace.checkCommands(); false } catch (_: IllegalStateException) { true }
                } }
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!future.isDone && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
                assertTrue(future.get(1, TimeUnit.SECONDS))
                assertTrue(app.workspace.blocked)
                assertEquals("Workspace access restricted", app.workspace.status.value)
                assertEquals(1, server.requestCount)
                assertEquals("Bearer recovery-only", server.takeRequest().getHeader("Authorization"))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun quarantineRejectsAgentSubmissionWithoutNetworkRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val app = configured(server.url("/").toString())
            app.workspace.identity.update { it.put("lost", true) }
            try { SharedChat.ask(app, "shared-test", "Do something", ""); fail("Expected quarantine") }
            catch (_: IllegalStateException) { }
            try { app.workspace.checkCommands(); fail("Expected quarantine") }
            catch (_: IllegalStateException) { }
            assertEquals(0, server.requestCount)
        }
    }
}
