package dev.r1ptt.workspace

import dev.r1ptt.App
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.data.Msg
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
        // These tests start inside an already verified foreground recovery session.
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
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

    /** Several chats/turns, plus an admission reply that can be lost until explicitly restored. */
    private class AttachmentHost : Dispatcher() {
        private val prompts = linkedMapOf<String, JSONObject>()
        private val runs = linkedMapOf<String, JSONObject>()
        private val requests = linkedMapOf<String, Triple<String, Long, String>>()
        val submissionKeys = CopyOnWriteArrayList<String>()
        @Volatile var dropRunReplies = false
        @Volatile var executions = 0
        @Synchronized fun seed(prompt: JSONObject) { prompts[prompt.getString("id")] = prompt.copyJson() }
        @Synchronized fun receipt(id: String) = runs.getValue(id).copyJson()
        private fun response(value: JSONObject, code: Int = 200) = MockResponse().setResponseCode(code).setBody(value.toString())
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.removePrefix("/api/v1")
            val data = request.body.readUtf8().takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            val id = path.split('/').getOrNull(2).orEmpty()
            return when {
                path == "/devices/self/check-in" -> response(JSONObject().put("device_id", "r1-a").put("lost", false).put("revoked", false)
                    .put("settings_revision", 0).put("settings", JSONObject()).put("commands", JSONArray()))
                path == "/notes" && request.method == "GET" -> response(JSONObject().put("items", JSONArray()))
                path == "/prompts" && request.method == "GET" -> response(JSONObject().put("items", JSONArray(prompts.values.map { it.copyJson() })))
                path == "/runs" && request.method == "GET" -> response(JSONObject().put("items", JSONArray(runs.values.map { it.copyJson() })))
                path == "/prompts" && request.method == "POST" -> {
                    assertFalse(prompts.containsKey(data.getString("id")))
                    val created = data.copyJson().put("revision", 1).put("created_at", 1L).put("updated_at", 1L)
                    prompts[created.getString("id")] = created
                    response(created)
                }
                path.endsWith("/lease") -> response(JSONObject().put("lease_token", "lease-$id").put("expires_at", System.currentTimeMillis() + 60_000))
                path.endsWith("/release") -> response(JSONObject())
                path.startsWith("/prompts/") && path.endsWith("/run") -> {
                    val key = data.getString("request_id")
                    val revision = data.getLong("revision")
                    submissionKeys += key
                    val known = requests[key]
                    val run = if (known != null) {
                        assertEquals(id, known.first); assertEquals(revision, known.second)
                        runs.getValue(known.third)
                    } else {
                        val saved = prompts.getValue(id)
                        assertEquals(saved.getLong("revision"), revision)
                        executions++
                        val runId = "attached-run-$executions"
                        JSONObject().put("id", runId).put("prompt_id", id).put("prompt_revision", revision)
                            .put("status", "completed").put("output", "Reply $executions").put("snapshot", saved.copyJson())
                            .put("created_at", executions * 10L).put("updated_at", executions * 10L + 1).also { receipt ->
                                runs[runId] = receipt
                                requests[key] = Triple(id, revision, runId)
                                saved.put("last_run_id", runId)
                            }
                    }
                    if (dropRunReplies) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else response(run)
                }
                path.startsWith("/prompts/") && request.method == "GET" -> prompts[id]?.let { response(it) }
                    ?: response(JSONObject().put("code", "not_found"), 404)
                path.startsWith("/prompts/") && request.method == "PUT" -> {
                    val saved = prompts.getValue(id)
                    assertEquals(saved.getLong("revision"), data.getLong("base_revision"))
                    val next = data.copyJson().put("revision", saved.getLong("revision") + 1).put("updated_at", saved.optLong("updated_at") + 1)
                    prompts[id] = next
                    response(next)
                }
                path.startsWith("/runs/") -> response(runs.getValue(id))
                path == "/resume" -> response(JSONObject().put("revision", 0))
                else -> response(JSONObject().put("code", "unexpected_path"), 404)
            }
        }
    }

    private fun savedAttachment(revision: Long = 1, title: String = "Travel prompt", text: String = "  Keep these exact words.\nIncluding spacing.  ") = JSONObject()
        .put("id", "saved-template").put("title", title).put("revision", revision).put("updated_at", revision)
        .put("instruction", "Summarize the notes in order.")
        .put("blocks", JSONArray().put(JSONObject().put("id", "written-note").put("kind", "text").put("text", text))
            .put(JSONObject().put("id", "recorded-note").put("kind", "audio").put("text", "Transcript from a recording.").put("media_id", "recording-1")))

    /** Recovery check-in applies on Main; pump it while network work uses the background scope. */
    private fun <T> background(work: suspend () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<T> { runBlocking { work() } }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!future.isDone && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            future.get(1, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }

    @Test fun openingPromptAttachmentsWaitsForSendAndAlwaysCreatesANewChat() {
        MockWebServer().use { server ->
            server.start(); val app = configured(server.url("/").toString())
            val saved = savedAttachment(); app.workspace.store.put("prompts", saved)
            val old = app.history.convId
            app.history.saveDraft("An unrelated unsent message", old)
            val first = PromptAttachment.open(app, saved)
            val second = PromptAttachment.open(app, saved)
            assertNotEquals(first, second)
            assertTrue(first.startsWith("shared-")); assertTrue(second.startsWith("shared-"))
            assertTrue(PromptAttachment.attached(app, first)); assertTrue(PromptAttachment.attached(app, second))
            assertEquals(second, app.history.convId)
            assertTrue(app.history.messages.isEmpty())
            assertTrue(app.workspace.store.items("shared_chat_links").isEmpty())
            assertTrue(app.workspace.store.items("runs").isEmpty())
            assertEquals(0, server.requestCount)
            app.history.select(old)
            assertEquals("An unrelated unsent message", app.history.draft)
        }
    }

    @Test fun anUnsentAttachmentUsesNewlySavedEditsButRejectsAnUnsavedDraft() {
        val app = configured("https://workspace.example")
        val saved = savedAttachment(); app.workspace.store.put("prompts", saved)
        val chat = PromptAttachment.open(app, saved)
        val edited = savedAttachment(2, "Updated travel prompt", "Saved after opening the chat")
        app.workspace.store.put("prompts", edited)
        val delivery = PromptAttachment.delivery(app, chat)
        assertEquals(WorkspaceClient.preview(edited), delivery.text)
        assertEquals(2L, delivery.prompt.getLong("revision"))
        assertEquals("Updated travel prompt", delivery.prompt.getString("title"))
        app.workspace.store.saveDraft(savedAttachment(2, "Unsaved title", "Not saved to the host"))
        try { PromptAttachment.delivery(app, chat); fail("An unsaved edit must not silently send an older saved version") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Save your edits")) }
        assertTrue(PromptAttachment.attached(app, chat))
    }
    @Test fun aNormalVoiceOrTextTurnCannotBypassAnUnsentPromptAttachment() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val app = configured(server.url("/").toString())
            val saved = savedAttachment(); app.workspace.store.put("prompts", saved)
            val chat = PromptAttachment.open(app, saved)
            try { SharedChat.ask(app, chat, "Voice from another screen", "Device context"); fail("Attachment must stay in control") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("attached prompt first")) }
            assertEquals(0, server.requestCount)
            assertTrue(PromptAttachment.attached(app, chat))
            assertTrue(app.workspace.store.items("shared_chat_links").isEmpty())
        }
    }

    @Test fun sendingAnAttachmentPreservesExactInputAndProvenanceAfterTheTemplateChanges() {
        MockWebServer().use { server ->
            val host = AttachmentHost(); server.dispatcher = host; server.start()
            val app = configured(server.url("/").toString())
            val saved = savedAttachment(); app.workspace.store.put("prompts", saved); host.seed(saved)
            val chat = PromptAttachment.open(app, saved)
            val delivery = PromptAttachment.delivery(app, chat)
            val reply = background { SharedChat.ask(app, chat, delivery.text, "Device context", delivery.prompt, onAdmitted = {
                assertTrue(PromptAttachment.attached(app, chat))
                PromptAttachment.accepted(app, chat)
            }) }
            assertEquals("Reply 1", reply)
            assertEquals(1, host.executions)
            assertFalse(PromptAttachment.attached(app, chat))
            val snapshot = host.receipt("attached-run-1").getJSONObject("snapshot").getJSONArray("blocks").getJSONObject(0)
            assertEquals(WorkspaceClient.preview(saved), snapshot.getString("text"))
            assertEquals("saved-template", snapshot.getJSONObject("prompt").getString("id"))
            val user = app.history.messages.single { it.role == Msg.USER }
            assertEquals(delivery.text, user.text)
            assertEquals("saved-template", user.promptId); assertEquals("Travel prompt", user.promptTitle); assertEquals(1L, user.promptRevision)
            app.workspace.store.put("prompts", savedAttachment(2, "Edited after sending", "This must not replace the chat's message"))
            SharedChat.importHistory(app)
            assertEquals(user, app.history.messages.single { it.role == Msg.USER })
            assertEquals(delivery.text, host.receipt("attached-run-1").getJSONObject("snapshot").getJSONArray("blocks").getJSONObject(0).getString("text"))
        }
    }

    @Test fun anUncertainAttachmentRetriesItsFrozenTextAndReferenceAfterTemplateEdits() {
        MockWebServer().use { server ->
            val host = AttachmentHost().apply { dropRunReplies = true }; server.dispatcher = host; server.start()
            val app = configured(server.url("/").toString())
            val saved = savedAttachment(); app.workspace.store.put("prompts", saved); host.seed(saved)
            val chat = PromptAttachment.open(app, saved)
            val original = PromptAttachment.delivery(app, chat)
            val failure = background { runCatching { SharedChat.ask(app, chat, original.text, "Frozen device context", original.prompt) }.exceptionOrNull() }
            assertTrue(failure is IOException)
            val pending = app.workspace.store.item("shared_chat_links", chat)!!
            assertTrue(pending.stringOrEmpty("run_id").isBlank())
            assertTrue(PromptAttachment.attached(app, chat))
            val changed = savedAttachment(2, "New source title", "A newer template body")
            app.workspace.store.put("prompts", changed); host.seed(changed)
            val retry = PromptAttachment.delivery(app, chat)
            assertEquals(original.text, retry.text)
            assertEquals(original.prompt.toString(), retry.prompt.toString())
            host.dropRunReplies = false
            assertEquals("Reply 1", background { SharedChat.ask(app, chat, retry.text, pending.getString("instruction"), retry.prompt) })
            assertEquals(1, host.executions)
            assertEquals(listOf(pending.getString("request_id")), host.submissionKeys.distinct())
            assertEquals(1L, app.history.messages.single { it.role == Msg.USER }.promptRevision)
            assertEquals(original.text, app.history.messages.single { it.role == Msg.USER }.text)
            assertNull(app.workspace.store.item("shared_chat_links", chat))
            assertFalse(PromptAttachment.attached(app, chat))
        }
    }

    @Test fun syncingALostAdmissionReplyRepairsItsLinkBeforeAllowingAFollowUp() {
        MockWebServer().use { server ->
            val host = AttachmentHost().apply { dropRunReplies = true }; server.dispatcher = host; server.start()
            val app = configured(server.url("/").toString())
            val saved = savedAttachment(); app.workspace.store.put("prompts", saved); host.seed(saved)
            val chat = PromptAttachment.open(app, saved)
            val delivery = PromptAttachment.delivery(app, chat)
            assertTrue(background { runCatching { SharedChat.ask(app, chat, delivery.text, "Original context", delivery.prompt) }.exceptionOrNull() } is IOException)
            val original = app.workspace.store.item("shared_chat_links", chat)!!
            assertTrue(original.stringOrEmpty("run_id").isBlank())
            background { app.workspace.sync() }
            val reconciled = app.workspace.store.item("shared_chat_links", chat)!!
            assertEquals("attached-run-1", reconciled.getString("run_id"))
            assertEquals(original.getString("operation"), reconciled.getString("operation"))
            assertEquals(original.getString("request_id"), reconciled.getString("request_id"))
            assertFalse(PromptAttachment.attached(app, chat))
            assertEquals("Reply 1", app.history.messages.last().text)
            host.dropRunReplies = false
            assertEquals("Reply 2", background { SharedChat.ask(app, chat, "A distinct follow-up", "Current device context") })
            assertEquals(2, host.executions)
            assertEquals(2, host.submissionKeys.distinct().size)
            val users = app.history.messages.filter { it.role == Msg.USER }
            assertEquals(listOf(delivery.text, "A distinct follow-up"), users.map { it.text })
            assertEquals("saved-template", users[0].promptId)
            assertEquals("", users[1].promptId)
            assertNull(app.workspace.store.item("shared_chat_links", chat))
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
