package dev.r1ptt.workspace

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import dev.r1ptt.App
import dev.r1ptt.R
import dev.r1ptt.ui.DeviceActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Controls for the current Hermes reply. Prompt Builder only supplies the text sent to a chat. */
class SharedChatActivity : DeviceActivity() {
    private val app get() = application as App
    private val hub get() = app.workspace
    private lateinit var body: LinearLayout
    private var conversation = ""
    private var selectedRun = ""
    private var observations: CoroutineScope? = null
    private var working = false
    private var message = ""
    private var showInput = false
    private var signature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        conversation = intent.getStringExtra(EXTRA_CONVERSATION).orEmpty().ifBlank { app.history.convId }
        selectedRun = intent.getStringExtra(EXTRA_RUN).orEmpty()
        val page = ui.page("Chat activity") { back() }
        body = page.body
        wheelScrollTarget = page.scroll
        setContentView(page.root)
        render()
    }

    override fun onResume() {
        super.onResume()
        observations = MainScope().also { scope ->
            scope.launch { hub.status.collect { render() } }
            scope.launch {
                refresh()
                while (isActive) {
                    delay(5_000)
                    val run = current()
                    if (active(run) || (run == null && pending() != null)) refresh()
                }
            }
        }
        render()
    }

    override fun onPause() {
        observations?.cancel(); observations = null
        app.turns.guardApprovalReview(null)
        super.onPause()
    }

    override fun onDestroy() { observations?.cancel(); observations = null; super.onDestroy() }

    @Deprecated("Platform Activity back handling") override fun onBackPressed() = back()
    private fun back() {
        if (showInput) { showInput = false; render(); wheelScrollTarget?.scrollTo(0, 0) }
        else finish()
    }

    private fun current(): JSONObject? = if (selectedRun.isNotBlank()) hub.store.item("runs", selectedRun) else latest(app, conversation)
    private fun pending() = hub.store.item("shared_chat_links", conversation)

    private fun render() {
        val run = current()
        val next = listOf(hub.blocked, run?.toString(), pending()?.toString(), working, message, showInput).joinToString("|")
        if (next == signature) { guardApproval(run); return }
        signature = next
        val y = wheelScrollTarget?.scrollY ?: 0
        body.removeAllViews()
        if (hub.blocked) {
            body.addView(ui.note("Chat access is restricted on this R1. Verify its recovery status to continue."))
            guardApproval(null)
            return
        }
        if (message.isNotBlank()) body.addView(ui.note(message))
        if (run == null) {
            body.addView(ui.note(if (pending() != null)
                "The original request is saved on this R1. Refresh to look for its reply, or return to the chat and tap Send to check the same request."
                else if (selectedRun.isNotBlank()) "This reply has not synced to the R1 yet. Refresh to check the host."
                else "No request has been sent in this chat."))
        } else if (showInput) {
            body.addView(ui.section("Sent to Hermes"))
            body.addView(ui.text(input(run)).apply { setTextIsSelectable(true) })
            body.addView(ui.button("Back to activity") { showInput = false; render(); wheelScrollTarget?.scrollTo(0, 0) })
        } else {
            val status = run.optString("status")
            body.addView(ui.mono(label(run), if (active(run)) R.color.accent else R.color.dim))
            val approval = run.optJSONObject("approval")
            if (status == "waiting_for_approval" && approval != null) {
                body.addView(ui.title("Review request", 20f))
                body.addView(ui.text(approvalDescription(approval)))
                approval.stringOrEmpty("command").takeIf(String::isNotBlank)?.let { command ->
                    body.addView(ui.text(command, 14f).apply { setTextIsSelectable(true) })
                }
                body.addView(ui.note("The side button cannot approve this request."))
                val id = approvalId(approval)
                if (id.isBlank()) body.addView(ui.note("Refresh to retrieve the current approval."))
                body.addView(ui.button("Allow once", primary = true) { control(run, "approval", id, "once") }.apply { isEnabled = !working && id.isNotBlank() })
                body.addView(ui.button("Deny") { control(run, "approval", id, "deny") }.apply { isEnabled = !working && id.isNotBlank() })
            }
            if (status == "submission_unknown") {
                body.addView(ui.note("Hermes has not confirmed whether it accepted this request. Checking keeps the original request identity."))
                body.addView(ui.button("Check submission", primary = true) { control(run, "reconcile") }.apply { isEnabled = !working })
            }
            val output = run.stringOrEmpty("output")
            val error = run.stringOrEmpty("error")
            if (output.isNotBlank()) body.addView(ui.text(output).apply { setTextIsSelectable(true) })
            if (error.isNotBlank()) body.addView(ui.note(error))
            if (active(run)) body.addView(ui.button("Stop task") { control(run, "stop") }.apply { isEnabled = !working && status != "stopping" })
            if (input(run).isNotBlank()) body.addView(ui.button("View text sent to Hermes") {
                showInput = true; render(); wheelScrollTarget?.scrollTo(0, 0)
            })
        }
        if (!showInput) body.addView(ui.button(if (working) "Checking…" else "Refresh") {
            observations?.launch { refresh() }
        }.apply { isEnabled = !working })
        guardApproval(run)
        wheelScrollTarget?.post { wheelScrollTarget?.scrollTo(0, y) }
    }

    private fun guardApproval(run: JSONObject?) {
        // A cancelled network call can finish after another approval screen has opened.
        if (observations == null) return
        app.turns.guardApprovalReview(if (!hub.blocked && run?.optString("status") == "waiting_for_approval") ({ finish() }) else null)
    }

    private suspend fun refresh() {
        if (working || hub.blocked || !hub.identity.paired) return
        working = true; render()
        try {
            hub.sync()
            message = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = safe(e) }
        finally { working = false; render() }
    }

    private fun control(run: JSONObject, action: String, approval: String? = null, choice: String? = null) {
        if (working || hub.blocked || observations == null) return
        val current = current() ?: return
        if (run.optString("id") != current.optString("id") || !active(current) ||
            (action == "approval" && (current.optString("status") != "waiting_for_approval" || approval != current.optJSONObject("approval")?.let(::approvalId)))) {
            message = "This request has changed. Review its current status."; render(); return
        }
        working = true; message = ""; render()
        observations?.launch {
            try {
                // runAction persists the control ID before dispatch and reuses it on an uncertain retry.
                hub.runAction(current.getString("id"), action, approval, choice)
                SharedChat.importHistory(app)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = safe(e) }
            finally { working = false; render() }
        }
    }

    private fun safe(error: Exception) = if (error is HubException) error.message.orEmpty()
        else "The host could not be reached. The original request is preserved; refresh to check its status."

    companion object {
        private const val EXTRA_CONVERSATION = "dev.r1ptt.workspace.CHAT"
        private const val EXTRA_RUN = "dev.r1ptt.workspace.CHAT_REQUEST"
        private val terminal = setOf("completed", "failed", "cancelled", "canceled", "interrupted")
        fun intent(context: Context, conversation: String, runId: String? = null) = Intent(context, SharedChatActivity::class.java)
            .putExtra(EXTRA_CONVERSATION, conversation).putExtra(EXTRA_RUN, runId)
        fun latest(app: App, conversation: String): JSONObject? {
            val pending = app.workspace.store.item("shared_chat_links", conversation)
            val pendingId = pending?.stringOrEmpty("run_id")
            pendingId?.takeIf(String::isNotBlank)?.let { return app.workspace.store.item("runs", it) }
            val replies = app.workspace.store.items("runs").filter { it.optString("prompt_id") == conversation }
            if (pending != null && pendingId.isNullOrBlank()) {
                // A lost admission response belongs to this operation, not an earlier reply in the chat.
                val operation = pending.stringOrEmpty("operation")
                return replies.firstOrNull { operation.isNotBlank() &&
                    it.optJSONObject("snapshot")?.optJSONArray("blocks")?.optJSONObject(0)?.optString("id") == operation }
            }
            return replies
                .maxWithOrNull(compareBy<JSONObject> { it.optLong("created_at") }.thenBy { it.optLong("updated_at") })
        }
        fun active(run: JSONObject?) = run != null && run.optString("status") !in terminal
        fun approvalDescription(approval: JSONObject): String = approval.stringOrEmpty("description")
            .ifBlank { approval.stringOrEmpty("message") }.ifBlank { approval.stringOrEmpty("summary") }
            .ifBlank { approval.toString(2) }
        fun label(run: JSONObject) = when (run.optString("status")) {
            "waiting_for_approval" -> "Waiting for you"
            "submission_unknown" -> "Submission unconfirmed"
            "queued", "submitting", "submitted" -> "Sending to Hermes"
            "running" -> "Hermes is working"
            "completed" -> "Reply received"
            "stopping" -> "Stopping"
            "cancelled", "canceled" -> "Stopped"
            "failed" -> "Request failed"
            "interrupted" -> "Interrupted"
            else -> "Check chat status"
        }
        private fun approvalId(approval: JSONObject) = approval.stringOrEmpty("request_id")
            .ifBlank { approval.stringOrEmpty("id") }.ifBlank { approval.stringOrEmpty("approval_id") }
        private fun input(run: JSONObject) = run.optJSONObject("snapshot")?.let { snapshot ->
            snapshot.stringOrEmpty("preview").ifBlank { runCatching { WorkspaceClient.preview(snapshot) }.getOrDefault("") }
        }.orEmpty()
    }
}
