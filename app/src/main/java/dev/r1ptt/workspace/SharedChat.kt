package dev.r1ptt.workspace

import dev.r1ptt.App
import dev.r1ptt.data.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Native chat owns execution; Prompt Builder supplies reusable text attachments. */
object SharedChat {
    private val admission = Mutex()
    private val terminal = setOf("completed", "failed", "cancelled", "canceled", "interrupted")

    fun conversation(app: App, current: String): String {
        if (current.startsWith("shared-")) return current
        val id = "shared-${newId()}"
        app.history.importShared(id, "New chat", emptyList())
        app.history.select(id)
        return id
    }

    suspend fun ask(app: App, conversation: String, text: String, instruction: String, attachment: JSONObject? = null,
                    onAdmitted: (String) -> Unit = {}, onStatus: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        check(conversation.startsWith("shared-"))
        val hub = app.workspace
        check(!hub.blocked) { "Shared workspace access is restricted" }
        check(attachment != null || !PromptAttachment.attached(app, conversation)) { "Open this chat and send or edit its attached prompt first" }
        val first = admission.withLock {
            // Persist the identity before any remote write. A lost response cannot turn a retry
            // into another question or run, even after a process restart.
            var pending = hub.store.item("shared_chat_links", conversation)
            if (pending != null && pending.optString("run_id").isNotBlank()) {
                val previous = hub.api("GET", "/runs/${pending.getString("run_id")}")
                hub.store.put("runs", previous)
                val sameQuestion = pending.optString("text") == text && pending.optString("instruction") == instruction &&
                    pending.optJSONObject("prompt")?.toString() == attachment?.toString()
                // A process can die after admission, before delivering the completed reply. A
                // retry still belongs to that run; only a distinct question begins another turn.
                if (previous.optString("status") in terminal && !sameQuestion) {
                    hub.store.finishChat(conversation, pending.getString("run_id")); pending = null
                }
            }
            if (pending != null && (pending.optString("text") != text || pending.optString("instruction") != instruction ||
                    pending.optJSONObject("prompt")?.toString() != attachment?.toString()))
                throw IOException("The previous question is still pending. Check this chat before sending another.")
            if (pending == null) {
                pending = JSONObject().put("id", conversation).put("operation", newId()).put("text", text)
                    .put("instruction", instruction).put("request_id", newId()).put("prompt", attachment)
                hub.store.put("shared_chat_links", pending)
            }
            val savedPending = pending
            if (savedPending.optString("run_id").isNotBlank()) {
                hub.api("GET", "/runs/${savedPending.getString("run_id")}")
            } else {
                val prompt = try { hub.api("GET", "/prompts/$conversation") } catch (e: HubException) {
                    if (e.status != 404) throw e
                    hub.api("POST", "/prompts", JSONObject().put("id", conversation).put("title", attachment?.optString("title")?.takeIf(String::isNotBlank) ?: text.replace('\n', ' ').take(80))
                        .put("instruction", "").put("blocks", JSONArray()))
                }
                val priorRun = prompt.optString("last_run_id").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { hub.api("GET", "/runs/$it") }
                if (priorRun != null && priorRun.optString("status") !in terminal &&
                    priorRun.optJSONObject("snapshot")?.optJSONArray("blocks")?.optJSONObject(0)?.optString("id") != savedPending.getString("operation"))
                    throw IOException("This chat has a task in progress. Check its activity before sending another question.")
                var revision = savedPending.optLong("revision", 0)
                if (revision == 0L) {
                    val lease = hub.acquire(conversation)
                    try {
                        val blocks = lease.getJSONArray("blocks")
                        val ownSave = blocks.length() == 1 && blocks.getJSONObject(0).optString("id") == savedPending.getString("operation") &&
                            blocks.getJSONObject(0).optString("text") == text && lease.optString("instruction") == savedPending.getString("instruction") &&
                            blocks.getJSONObject(0).optJSONObject("prompt")?.toString() == savedPending.optJSONObject("prompt")?.toString()
                        val base = savedPending.optLong("base_revision", lease.getLong("revision"))
                        if (!ownSave && lease.getLong("revision") != base)
                            throw IOException("This chat changed on another device. Your question is saved locally; refresh this chat.")
                        if (!savedPending.has("base_revision")) {
                            savedPending.put("base_revision", base); hub.store.put("shared_chat_links", savedPending)
                        }
                        val saved = if (ownSave) lease else hub.save(lease.put("instruction", savedPending.getString("instruction"))
                            .put("blocks", JSONArray().put(JSONObject().put("id", savedPending.getString("operation")).put("kind", "text")
                                .put("text", text).put("prompt", savedPending.optJSONObject("prompt")))))
                        revision = saved.getLong("revision")
                        savedPending.put("revision", revision); hub.store.put("shared_chat_links", savedPending)
                        hub.store.put("prompts", saved)
                    } finally { hub.release(lease) }
                }
                hub.api("POST", "/prompts/$conversation/run", JSONObject().put("revision", revision)
                    .put("request_id", savedPending.getString("request_id")).put("run_again", false)).also { run ->
                    savedPending.put("run_id", run.getString("id")); hub.store.put("shared_chat_links", savedPending)
                }
            }
        }
        hub.store.put("runs", first)
        onAdmitted(first.getString("id"))
        importHistory(app)
        var run = first
        val deadline = System.currentTimeMillis() + 180_000
        while (true) {
            check(!hub.blocked) { "Shared workspace access is restricted" }
            when (run.getString("status")) {
                "completed" -> {
                    importHistory(app)
                    hub.store.finishChat(conversation, run.getString("id"))
                    return@withContext run.stringOrEmpty("output").takeUnless { it == "null" }.orEmpty()
                }
                "failed", "cancelled", "canceled", "interrupted" -> {
                    hub.store.finishChat(conversation, run.getString("id"))
                    throw IOException(run.stringOrEmpty("error").takeUnless { it.isBlank() || it == "null" } ?: "This chat stopped. Check its activity.")
                }
                "waiting_for_approval", "waiting_approval", "awaiting_approval", "submission_unknown", "unknown", "unsupported" ->
                    throw IOException("This chat needs attention. Tap its activity to review it.")
            }
            if (System.currentTimeMillis() >= deadline)
                throw IOException("Hermes is still working. The reply will appear in this chat when ready.")
            onStatus("Host is ${run.getString("status").replace('_', ' ')}…")
            delay(1_000)
            hub.checkCommands()
            run = hub.api("GET", "/runs/${run.getString("id")}")
            hub.store.put("runs", run)
            importHistory(app)
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    @Synchronized fun importHistory(app: App) {
        val hub = app.workspace
        if (hub.blocked || !hub.identity.paired) return
        val prompts = hub.store.items("prompts").associateBy { it.getString("id") }
        hub.store.items("runs").filter { it.optString("prompt_id").startsWith("shared-") }
            .groupBy { it.getString("prompt_id") }.forEach { (id, runs) ->
                val messages = runs.sortedBy { it.optLong("created_at") }.flatMap { run ->
                    val block = run.optJSONObject("snapshot")?.optJSONArray("blocks")?.optJSONObject(0)
                    val question = block?.optString("text").orEmpty()
                    val source = block?.optJSONObject("prompt")
                    val at = run.optLong("created_at")
                    val user = listOfNotNull(question.takeIf(String::isNotBlank)?.let { Msg(Msg.USER, it, at, "${run.getString("id")}:user",
                        source?.optString("id").orEmpty(), source?.optString("title").orEmpty(), source?.optLong("revision") ?: 0) })
                    val answer = run.stringOrEmpty("output").takeUnless { it == "null" }.orEmpty()
                    user + if (run.optString("status") == "completed" && answer.isNotBlank())
                        listOf(Msg(Msg.ASSISTANT, answer, run.optLong("updated_at"), "${run.getString("id")}:assistant")) else emptyList()
                }
                app.history.importShared(id, prompts[id]?.optString("title") ?: "Shared chat", messages)
                // A receipt found after a lost response also confirms admission. Clear only the
                // attachment whose original operation is in that immutable receipt.
                val pending = hub.store.item("shared_chat_links", id)
                if (pending != null) runs.firstOrNull { run ->
                    run.optJSONObject("snapshot")?.optJSONArray("blocks")?.optJSONObject(0)?.optString("id") == pending.optString("operation")
                }?.let { admitted ->
                    if (pending.optString("run_id").isBlank() || PromptAttachment.attached(app, id))
                        hub.store.confirmChatAdmission(id, pending.getString("operation"), pending.getString("request_id"), admitted.getString("id"))
                }
                // Importing display history does not acknowledge delivery to a waiting caller.
                // Only ask consumes its durable pending link when handing back a result/error.
            }
    }

    @Synchronized fun clearHistory(app: App) {
        app.history.flow.value.chats.filter { it.id.startsWith("shared-") }.forEach { app.history.remove(it.id) }
        app.history.awaitWrites()
    }
}
