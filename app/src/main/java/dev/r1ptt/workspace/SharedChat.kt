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

/** Native chat uses the same immutable prompts and host-owned runs as the builder and web. */
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

    suspend fun ask(app: App, conversation: String, text: String, instruction: String,
                    onAdmitted: (String) -> Unit = {}, onStatus: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        check(conversation.startsWith("shared-"))
        val hub = app.workspace
        check(!hub.blocked) { "Shared workspace access is restricted" }
        val first = admission.withLock {
            // Persist the identity before any remote write. A lost response cannot turn a retry
            // into another question or run, even after a process restart.
            var pending = hub.store.item("shared_chat_links", conversation)
            if (pending != null && pending.optString("run_id").isNotBlank()) {
                val previous = hub.api("GET", "/runs/${pending.getString("run_id")}")
                hub.store.put("runs", previous)
                val sameQuestion = pending.optString("text") == text && pending.optString("instruction") == instruction
                // A process can die after admission, before delivering the completed reply. A
                // retry still belongs to that run; only a distinct question begins another turn.
                if (previous.optString("status") in terminal && !sameQuestion) {
                    hub.store.remove("shared_chat_links", conversation); pending = null
                }
            }
            if (pending != null && (pending.optString("text") != text || pending.optString("instruction") != instruction))
                throw IOException("The previous question is still pending. Open its saved prompt in Workspace before sending another.")
            if (pending == null) {
                pending = JSONObject().put("id", conversation).put("operation", newId()).put("text", text)
                    .put("instruction", instruction).put("request_id", newId())
                hub.store.put("shared_chat_links", pending)
            }
            val savedPending = pending
            if (savedPending.optString("run_id").isNotBlank()) {
                hub.api("GET", "/runs/${savedPending.getString("run_id")}")
            } else {
                val prompt = try { hub.api("GET", "/prompts/$conversation") } catch (e: HubException) {
                    if (e.status != 404) throw e
                    hub.api("POST", "/prompts", JSONObject().put("id", conversation).put("title", text.replace('\n', ' ').take(80))
                        .put("instruction", "").put("blocks", JSONArray()))
                }
                val priorRun = prompt.optString("last_run_id").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { hub.api("GET", "/runs/$it") }
                if (priorRun != null && priorRun.optString("status") !in terminal &&
                    priorRun.optJSONObject("snapshot")?.optJSONArray("blocks")?.optJSONObject(0)?.optString("id") != savedPending.getString("operation"))
                    throw IOException("This chat has a task in progress. Review it in Workspace before sending another question.")
                var revision = savedPending.optLong("revision", 0)
                if (revision == 0L) {
                    val lease = hub.acquire(conversation)
                    try {
                        val blocks = lease.getJSONArray("blocks")
                        val ownSave = blocks.length() == 1 && blocks.getJSONObject(0).optString("id") == savedPending.getString("operation") &&
                            blocks.getJSONObject(0).optString("text") == text && lease.optString("instruction") == savedPending.getString("instruction")
                        val base = savedPending.optLong("base_revision", lease.getLong("revision"))
                        if (!ownSave && lease.getLong("revision") != base)
                            throw IOException("This chat changed on another device. Your question is saved locally; review it in Workspace.")
                        if (!savedPending.has("base_revision")) {
                            savedPending.put("base_revision", base); hub.store.put("shared_chat_links", savedPending)
                        }
                        val saved = if (ownSave) lease else hub.save(lease.put("instruction", savedPending.getString("instruction"))
                            .put("blocks", JSONArray().put(JSONObject().put("id", savedPending.getString("operation")).put("kind", "text").put("text", text))))
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
                    hub.store.remove("shared_chat_links", conversation)
                    return@withContext run.stringOrEmpty("output").takeUnless { it == "null" }.orEmpty()
                }
                "failed", "cancelled", "canceled", "interrupted" -> {
                    hub.store.remove("shared_chat_links", conversation)
                    throw IOException(run.stringOrEmpty("error").takeUnless { it.isBlank() || it == "null" } ?: "This task stopped. Review it in Workspace.")
                }
                "waiting_for_approval", "waiting_approval", "awaiting_approval", "submission_unknown", "unknown", "unsupported" ->
                    throw IOException("This task needs attention. Open Workspace to review its status; it has not been resubmitted.")
            }
            if (System.currentTimeMillis() >= deadline)
                throw IOException("The host is still working. The result will sync when ready; open Workspace for status.")
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
                    val question = run.optJSONObject("snapshot")?.optJSONArray("blocks")?.optJSONObject(0)?.optString("text").orEmpty()
                    val at = run.optLong("created_at")
                    val user = listOfNotNull(question.takeIf(String::isNotBlank)?.let { Msg(Msg.USER, it, at, "${run.getString("id")}:user") })
                    val answer = run.stringOrEmpty("output").takeUnless { it == "null" }.orEmpty()
                    user + if (run.optString("status") == "completed" && answer.isNotBlank())
                        listOf(Msg(Msg.ASSISTANT, answer, run.optLong("updated_at"), "${run.getString("id")}:assistant")) else emptyList()
                }
                app.history.importShared(id, prompts[id]?.optString("title") ?: "Shared chat", messages)
                // Importing display history does not acknowledge delivery to a waiting caller.
                // Only ask consumes its durable pending link when handing back a result/error.
            }
    }

    @Synchronized fun clearHistory(app: App) {
        app.history.flow.value.chats.filter { it.id.startsWith("shared-") }.forEach { app.history.remove(it.id) }
        app.history.awaitWrites()
    }
}
