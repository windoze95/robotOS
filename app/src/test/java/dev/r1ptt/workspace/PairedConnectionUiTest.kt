package dev.r1ptt.workspace

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.SettingsActivity
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.hermes.HermesActivity
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class PairedConnectionUiTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    private fun pair(base: String = "https://workspace.example", token: String? = null) {
        // No network credentials: these tests exercise only the native navigation surfaces.
        val identity = JSONObject().put("device_id", "paired-r1").put("base_url", base).apply { if (token != null) put("device_token", token) }
        val disk = object : ConfigBlobStorage { var value: String? = null; override fun read() = value; override fun write(blob: String) { value = blob } }
        val cipher = object : ConfigCipher { override fun seal(plain: String, allowKeyCreation: Boolean) = plain; override fun open(blob: String) = blob }
        val codec = object : ConfigCodec<JSONObject> { override fun encode(value: JSONObject) = value.toString(); override fun decode(plain: String) = JSONObject(plain) }
        app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.workspace.identity, EncryptedConfigPersistence(identity, disk, cipher, codec))
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
    }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun buttons(view: View) = all(view).filterIsInstance<Button>()
    private fun connections(activity: SettingsActivity) {
        all(activity.window.decorView).single { it.contentDescription?.toString()?.startsWith("Connections.") == true }.performClick()
    }

    @Test fun pairedHermesUsesTheHostAndChatsWhileKeepingLocalPermissions() {
        pair()
        val controller = Robolectric.buildActivity(HermesActivity::class.java).create()
        val activity = controller.get()
        val root = activity.window.decorView
        val text = all(root).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue("workspace.example" in text)
        assertTrue("Managed by your host" in text)
        assertFalse(buttons(root).any { it.text == "Check connection" || it.text == "Choose model" })
        val permissions = all(root).filterIsInstance<Switch>().map { it.text.toString() }
        assertTrue(permissions.containsAll(listOf("Let Hermes access this R1", "Relay my texts to Hermes", "Speak announcements", "Show messages on screen", "Change volume", "Save recipients")))
        buttons(root).single { it.text == "Host & devices" }.performClick()
        assertEquals(HubSettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        buttons(root).single { it.text == "Chats" }.performClick()
        assertEquals(dev.r1ptt.ChatsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        controller.destroy()
    }

    @Test fun pairedSettingsReplacesDirectCredentialsAndKeepsSpeechSettings() {
        pair()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        val activity = controller.get()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        connections(activity)
        assertTrue(all(activity.window.decorView).filterIsInstance<EditText>().isEmpty())
        buttons(activity.window.decorView).single { it.text == "Host & devices" }.performClick()
        assertEquals(HubSettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        buttons(activity.window.decorView).single { it.text == "Saved prompts" }.performClick()
        assertEquals(WorkspaceActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        @Suppress("DEPRECATION") activity.onBackPressed()
        all(activity.window.decorView).single { it.contentDescription?.toString()?.startsWith("Speech services.") == true }.performClick()
        assertTrue(all(activity.window.decorView).filterIsInstance<EditText>().any { it.contentDescription == "OpenAI speech API key" })
        controller.destroy()
    }

    @Test fun unpairedDevicesKeepDirectConnectionAndModelControls() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).create()
        connections(settings.get())
        assertTrue(all(settings.get().window.decorView).filterIsInstance<EditText>().any { it.contentDescription == "Hermes API key" })
        assertTrue(buttons(settings.get().window.decorView).any { it.text == "Save connection" })
        val hermes = Robolectric.buildActivity(HermesActivity::class.java).create()
        assertTrue(buttons(hermes.get().window.decorView).any { it.text == "Choose model" })
        assertTrue(buttons(hermes.get().window.decorView).any { it.text == "Check connection" })
        hermes.destroy(); settings.destroy()
    }

    @Test fun legacyRunsDestinationWaitsForRecoveryThenOpensSavedPrompts() {
        pair(); app.workspace.requireRecovery(checkNow = false)
        val route = Intent(app, WorkspaceActivity::class.java).putExtra("dev.r1ptt.workspace.RUNS", true)
        val controller = Robolectric.buildActivity(WorkspaceActivity::class.java, route).create()
        val activity = controller.get()
        assertEquals("restricted", currentScreen(activity))
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
        activity.javaClass.getDeclaredMethod("landingPage").apply { isAccessible = true }.invoke(activity)
        assertEquals("prompts", currentScreen(activity))
        controller.destroy()
    }

    @Test fun savedPromptOnlyOffersEditingAndNewChatWithNoExecutionState() {
        pair()
        val saved = seedPrompt("completed", "Reply stored by Hermes")
        app.workspace.store.put("prompts", saved.copyJson().put("instruction", "Newer saved instruction")
            .put("revision", 2).put("changed_since_run", true))
        val controller = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.promptsIntent(app)).create()
        val activity = controller.get(); openSavedPrompt(activity)
        assertEquals("prompt", currentScreen(activity))
        assertTrue(buttons(activity.window.decorView).map { it.text.toString() }.containsAll(listOf("Edit prompt", "Run in new chat")))
        assertTrue("Newer saved instruction" in texts(activity))
        assertFalse(texts(activity).any { it in setOf("Hermes reply", "Reply stored by Hermes", "Changed since last run", "Ran · reply saved") })
        assertTrue(all(activity.window.decorView).filterIsInstance<EditText>().isEmpty())
        assertNull(activity.javaClass.getDeclaredField("prompt").apply { isAccessible = true }.get(activity))
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        controller.destroy()
    }

    @Test fun runInNewChatAttachesWithoutSendingAndLeavesOriginalChatDraft() {
        pair(); seedPrompt("completed", "Earlier reply")
        val old = app.history.convId
        app.history.saveDraft("Unsent normal message")
        val controller = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.promptsIntent(app)).create()
        val activity = controller.get(); openSavedPrompt(activity)
        buttons(activity.window.decorView).single { it.text == "Run in new chat" }.performClick()
        val next = app.history.convId
        assertTrue(next.startsWith("shared-"))
        assertNotEquals("saved-prompt", next)
        assertTrue(PromptAttachment.attached(app))
        assertEquals("saved-prompt", PromptAttachment.source(app)!!.getString("id"))
        assertTrue(app.history.messages.isEmpty())
        assertTrue(app.workspace.store.items("shared_chat_links").isEmpty())
        assertEquals(1, app.workspace.store.items("runs").size)
        assertEquals(dev.r1ptt.HomeActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        app.history.select(old)
        assertEquals("Unsent normal message", app.history.draft)
        controller.destroy()
    }

    @Test fun sharedChatRecordsAreExcludedFromPromptBuilder() {
        pair(); val saved = seedPrompt("completed", "Earlier reply")
        app.workspace.store.put("prompts", saved.copyJson().put("id", "shared-chat").put("title", "Chat question"))
        app.workspace.store.put("drafts", saved.copyJson().put("id", "shared-pending").put("title", "Chat in flight"))
        val controller = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.promptsIntent(app)).create()
        val text = texts(controller.get()).joinToString("\n")
        assertTrue(text.contains("Travel notes"))
        assertFalse(text.contains("Chat question"))
        assertFalse(text.contains("Chat in flight"))
        controller.destroy()
    }

    @Test fun attachedComposerOpensBuilderInsteadOfKeyboardAndBlocksDictation() {
        pair(); val saved = seedPrompt("completed", "Earlier reply")
        val conversation = PromptAttachment.open(app, saved)
        val controller = Robolectric.buildActivity(dev.r1ptt.HomeActivity::class.java).create()
        val activity = controller.get()
        activity.javaClass.getDeclaredMethod("renderHistory", dev.r1ptt.data.History.Snapshot::class.java)
            .apply { isAccessible = true }.invoke(activity, app.history.flow.value)
        val attachment = activity.findViewById<TextView>(dev.r1ptt.R.id.prompt_attachment)
        assertEquals(View.VISIBLE, attachment.visibility)
        assertEquals(View.GONE, activity.findViewById<EditText>(dev.r1ptt.R.id.input).visibility)
        assertTrue(attachment.text.contains("PROMPT BUILDER"))
        assertTrue(attachment.text.contains("Travel notes"))
        assertFalse(activity.canDictate())
        // An attachment owns neither keyboard dictation nor the keyboard's tap-to-send gesture.
        activity.javaClass.getDeclaredField("resumed").apply { isAccessible = true }.setBoolean(activity, true)
        assertFalse(activity.isActive())
        app.turns.attachTarget(activity)
        app.turns.onPress(android.os.SystemClock.uptimeMillis()); app.turns.onHoldStart()
        assertFalse(app.turns.state.value.phase in setOf(dev.r1ptt.Phase.LISTENING, dev.r1ptt.Phase.DICTATING))
        app.turns.detachTarget(activity)
        activity.insert("Cannot replace the prompt")
        assertTrue(app.history.draft.isEmpty())
        attachment.performClick()
        val route = shadowOf(activity).nextStartedActivity
        assertEquals(WorkspaceActivity::class.java.name, route.component!!.className)
        assertEquals("saved-prompt", route.getStringExtra("dev.r1ptt.workspace.PROMPT_ID"))
        assertEquals(conversation, route.getStringExtra("dev.r1ptt.workspace.RETURN_CHAT"))
        assertTrue(app.workspace.store.items("shared_chat_links").isEmpty())
        controller.destroy()
    }

    @Test fun attachedEditorReopensLocalDraftAndBackPreservesItForTheSameChat() {
        pair(); val saved = seedPrompt("completed", "Earlier reply")
        val conversation = PromptAttachment.open(app, saved)
        val controller = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.promptsIntent(app)).create()
        val activity = controller.get()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val body = when {
                        request.path!!.endsWith("/lease") -> JSONObject().put("lease_token", "editor-lease").put("revision", 1)
                        request.path!!.endsWith("/release") -> JSONObject()
                        request.path!!.endsWith("/prompts/saved-prompt") -> saved
                        else -> JSONObject().put("items", JSONArray())
                    }
                    return okhttp3.mockwebserver.MockResponse().setBody(body.toString())
                }
            }
            server.start(); pair(server.url("/").toString(), "test-workspace")
            val draft = saved.copyJson().put("instruction", "Unsent local edits")
            app.workspace.store.put("drafts", draft)
            activity.javaClass.getDeclaredField("returnChat").apply { isAccessible = true }.set(activity, conversation)
            activity.javaClass.getDeclaredMethod("openPrompt", String::class.java, Boolean::class.javaPrimitiveType,
                List::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, "saved-prompt", false, emptyList<JSONObject>(), 0, false)
            val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (currentScreen(activity) != "builder" && System.nanoTime() < until) {
                shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertEquals("builder", currentScreen(activity))
            val field = all(activity.window.decorView).filterIsInstance<EditText>().single { it.text.toString() == "Unsent local edits" }
            field.setText("Local edit after returning")
            assertEquals("Local edit after returning", app.workspace.store.item("drafts", "saved-prompt")!!.getString("instruction"))
            assertTrue(buttons(activity.window.decorView).any { it.text == "Save and return to chat" })
            @Suppress("DEPRECATION") activity.onBackPressed()
            assertEquals(conversation, app.history.convId)
            assertEquals("Local edit after returning", app.workspace.store.item("drafts", "saved-prompt")!!.getString("instruction"))
            assertEquals("Original instruction", PromptAttachment.source(app)!!.getString("instruction"))
            controller.destroy()
        }
    }

    @Test fun workspaceHasNoSeparateRunsDestination() {
        pair()
        val controller = Robolectric.buildActivity(WorkspaceActivity::class.java).create()
        val text = texts(controller.get())
        assertTrue("Prompt Builder" in text)
        assertFalse(text.any { it == "Runs" || it == "Workspace runs" })
        controller.destroy()
    }

    private fun seedPrompt(status: String, output: String): JSONObject {
        val saved = JSONObject().put("id", "saved-prompt").put("title", "Travel notes").put("instruction", "Original instruction")
            .put("blocks", JSONArray()).put("revision", 1).put("last_run_id", "submission-1")
            .put("state", if (status == "completed") "ran" else "needs_attention")
        app.workspace.store.put("prompts", saved)
        app.workspace.store.put("runs", JSONObject().put("id", "submission-1").put("prompt_id", "saved-prompt")
            .put("prompt_revision", 1).put("status", status).put("output", output).put("snapshot", saved.copyJson())
            .put("approval", JSONObject().put("request_id", "approval-1").put("description", "Approve the requested action")))
        return saved
    }
    private fun openSavedPrompt(activity: WorkspaceActivity) {
        all(activity.window.decorView).single { it.contentDescription?.toString()?.startsWith("Travel notes.") == true }.performClick()
    }
    private fun texts(activity: WorkspaceActivity) = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }

    private fun currentScreen(activity: WorkspaceActivity) = activity.javaClass.getDeclaredField("screen").apply { isAccessible = true }.get(activity)
}
