package dev.r1ptt.workspace

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import kotlinx.coroutines.MainScope
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class SharedChatActivityTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    @After fun close() { app.deviceApi.stop(); app.history.close() }

    private fun pair(base: String = "https://workspace.example") {
        val identity = JSONObject().put("device_id", "paired-r1").put("base_url", base)
            .put("device_token", "content-token").put("recovery_token", "recovery-token")
        val disk = object : ConfigBlobStorage { var value: String? = null; override fun read() = value; override fun write(blob: String) { value = blob } }
        val cipher = object : ConfigCipher { override fun seal(plain: String, allowKeyCreation: Boolean) = plain; override fun open(blob: String) = blob }
        val codec = object : ConfigCodec<JSONObject> { override fun encode(value: JSONObject) = value.toString(); override fun decode(plain: String) = JSONObject(plain) }
        app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.workspace.identity, EncryptedConfigPersistence(identity, disk, cipher, codec))
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
    }

    private fun seed(status: String, id: String = "request-1", operation: String = "message-1", created: Long = 1): JSONObject = JSONObject()
        .put("id", id).put("prompt_id", "shared-chat").put("status", status).put("created_at", created)
        .put("snapshot", JSONObject().put("title", "Trip plan").put("instruction", "Context")
            .put("blocks", JSONArray().put(JSONObject().put("id", operation).put("kind", "text").put("text", "Original message"))))
        .put("output", if (status == "completed") "Original reply" else "")
        .put("approval", JSONObject().put("request_id", "approval-1").put("description", "Write the requested file").put("command", "touch approved-file"))
        .also { app.workspace.store.put("runs", it) }

    private fun screen(run: String? = null) = Robolectric.buildActivity(SharedChatActivity::class.java,
        SharedChatActivity.intent(app, "shared-chat", run)).create()
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun texts(activity: SharedChatActivity) = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun buttons(activity: SharedChatActivity) = all(activity.window.decorView).filterIsInstance<Button>()
    private fun enableActions(activity: SharedChatActivity) {
        // Exercise button dispatch without onResume's unrelated recovery/sync loop.
        activity.javaClass.getDeclaredField("observations").apply { isAccessible = true }.set(activity, MainScope())
    }
    private fun settle(until: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!until() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Expected operation to settle", until())
    }

    @Test fun openingAChatShowsApprovalsWithoutSubmittingOrOfferingPromptExecution() {
        pair(); seed("waiting_for_approval")
        val controller = screen(); val activity = controller.get()
        assertTrue(texts(activity).containsAll(listOf("Waiting for you", "Write the requested file", "touch approved-file")))
        val labels = buttons(activity).map { it.text.toString() }
        assertTrue(labels.containsAll(listOf("Allow once", "Deny", "Stop task", "View text sent to Hermes")))
        assertFalse(labels.any { it in setOf("Run again", "Edit prompt", "Send to Hermes") })
        assertTrue(app.workspace.store.items("requests").isEmpty())
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        controller.destroy()
    }

    @Test fun uncertaintyRetainsTheOriginalReceiptAndExactInput() {
        pair(); seed("submission_unknown")
        app.workspace.store.put("prompts", JSONObject().put("id", "shared-chat").put("instruction", "Newer draft").put("blocks", JSONArray()))
        val controller = screen(); val activity = controller.get()
        assertTrue(buttons(activity).any { it.text == "Check submission" })
        buttons(activity).single { it.text == "View text sent to Hermes" }.performClick()
        assertTrue(texts(activity).any { it == "Context\n\nOriginal message" })
        assertFalse(texts(activity).any { it.contains("Newer draft") })
        assertTrue(app.workspace.store.items("requests").isEmpty())
        controller.destroy()
    }
    @Test fun approvalVariantsKeepTheirDecisionDetailsVisible() {
        pair()
        listOf(
            JSONObject().put("request_id", "approval-1").put("message", "Create the requested calendar entry"),
            JSONObject().put("request_id", "approval-1").put("summary", "Read the selected document"),
            JSONObject().put("request_id", "approval-1").put("tool", "calendar_create").put("arguments", JSONObject().put("title", "Team meeting"))
        ).forEach { approval ->
            app.workspace.store.put("runs", seed("waiting_for_approval").put("approval", approval))
            val controller = screen()
            val visible = texts(controller.get()).joinToString("\n")
            val expected = approval.optString("message").ifBlank { approval.optString("summary") }.ifBlank { "Team meeting" }
            assertTrue(visible.contains(expected))
            assertTrue(buttons(controller.get()).any { it.text == "Allow once" && it.isEnabled })
            controller.destroy()
        }
    }

    @Test fun lostAdmissionResponseNeverDisplaysAnEarlierReplyAsItsResult() {
        pair(); seed("completed")
        app.workspace.store.put("shared_chat_links", JSONObject().put("id", "shared-chat").put("operation", "pending-message"))
        assertNull(SharedChatActivity.latest(app, "shared-chat"))
        val controller = screen()
        assertTrue(texts(controller.get()).any { it.contains("tap Send to check the same request") })
        assertFalse("Original reply" in texts(controller.get()))
        seed("running", "request-2", "pending-message", created = 2)
        assertEquals("request-2", SharedChatActivity.latest(app, "shared-chat")!!.getString("id"))
        assertTrue(app.workspace.store.items("requests").isEmpty())
        controller.destroy()
    }

    @Test fun legacyReplyCanBeOpenedByItsReceiptWithoutAHistoryMigration() {
        pair()
        app.workspace.store.put("runs", seed("completed").put("prompt_id", "old-saved-prompt"))
        val controller = screen("request-1")
        assertTrue("Original reply" in texts(controller.get()))
        assertFalse(buttons(controller.get()).any { it.text == "Run again" || it.text == "Stop task" })
        controller.destroy()
    }

    @Test fun approvalDispatchUsesItsExactIdentityAndIgnoresDuplicateTaps() {
        MockWebServer().use { server ->
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    release.await(5, TimeUnit.SECONDS)
                    return MockResponse().setBody(seed("completed").toString())
                }
            }
            server.start(); pair(server.url("/").toString()); seed("waiting_for_approval")
            val controller = screen(); val activity = controller.get(); enableActions(activity)
            val allow = buttons(activity).single { it.text == "Allow once" }
            try {
                allow.performClick(); allow.performClick()
                settle { server.requestCount == 1 }
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/api/v1/runs/request-1/approval", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals("approval-1", body.getString("approval_id")); assertEquals("once", body.getString("choice"))
                assertTrue(body.getString("request_id").isNotBlank())
                assertEquals(1, app.workspace.store.items("requests").size)
                release.countDown()
                settle { app.workspace.store.items("requests").isEmpty() }
                assertEquals(1, server.requestCount)
            } finally { release.countDown(); controller.destroy() }
        }
    }

    @Test fun aRecoveryFenceStopsAlreadyRenderedControlsBeforeTheyReachTheHost() {
        pair(); seed("waiting_for_approval")
        val controller = screen(); val activity = controller.get(); enableActions(activity)
        val allow = buttons(activity).single { it.text == "Allow once" }
        app.workspace.requireRecovery(checkNow = false)
        allow.performClick()
        assertTrue(app.workspace.store.items("requests").isEmpty())
        controller.destroy()
    }
}
