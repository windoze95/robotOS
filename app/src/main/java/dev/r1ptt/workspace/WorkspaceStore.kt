package dev.r1ptt.workspace

import dev.r1ptt.data.AtomicConfigFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.stringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
internal fun JSONObject.copyJson() = JSONObject(toString())
internal fun newId(): String = UUID.randomUUID().toString()

/** Atomic snapshots include both cache and pending writes. Failed loads never silently reset data. */
class WorkspaceStore(directory: File) {
    val mediaDir = File(directory, "media").apply { mkdirs() }
    private val disk = AtomicConfigFile(File(directory, "workspace.json"))
    var loadError: String? = null
        private set
    private var data = try { (disk.read()?.let(::JSONObject) ?: JSONObject()).also {
        if (compactCache(it)) disk.write(it.toString())
    } } catch (_: Exception) {
        loadError = "Saved workspace data could not be loaded. It has been preserved for recovery; writes are disabled."
        JSONObject()
    }
    /** Keep edit leases in the active editor; stored drafts need only their content and revision. */
    private fun compactCache(root: JSONObject): Boolean {
        var changed = false
        mapOf("notes" to listOf("capture_hash"), "note_drafts" to listOf("capture_hash"),
            "prompts" to listOf("lease_token", "lease_expires_at", "state", "last_run_revision", "changed_since_run"),
            "drafts" to listOf("lease_token", "lease_expires_at", "state", "last_run_revision", "changed_since_run"),
            "receipts" to listOf("type")).forEach { (key, fields) ->
            val items = root.optJSONArray(key)?.objects() ?: return@forEach
            if (items.any { item -> fields.any(item::has) }) {
                root.put(key, JSONArray(items.map { item -> item.copyJson().apply { fields.forEach(::remove) } }))
                changed = true
            }
        }
        root.optJSONArray("runs")?.objects()?.let { runs ->
            val fields = listOf("last_run_revision", "last_run_id", "changed_since_run", "state",
                "id", "revision", "created_at", "updated_at")
            if (runs.any { run -> run.optJSONObject("snapshot")?.let { fields.any(it::has) } == true }) {
                root.put("runs", JSONArray(runs.map { run -> run.copyJson().apply {
                    optJSONObject("snapshot")?.apply { fields.forEach(::remove) }
                } }))
                changed = true
            }
        }
        return changed
    }
    @Synchronized fun snapshot(): JSONObject = data.copyJson()
    @Synchronized fun mutate(change: (JSONObject) -> Unit) {
        check(loadError == null) { loadError.orEmpty() }
        val next = data.copyJson()
        change(next)
        compactCache(next)
        disk.write(next.toString())
        data = next
    }
    fun items(key: String): List<JSONObject> = snapshot().optJSONArray(key)?.objects().orEmpty()
    fun item(key: String, id: String): JSONObject? = items(key).find { it.optString("id") == id }
    @Synchronized fun version(): Long = data.optLong("cache_clock")
    private fun stamp(root: JSONObject, key: String, id: String) {
        val next = root.optLong("cache_clock") + 1
        root.put("cache_clock", next)
        root.put("cache_versions", (root.optJSONObject("cache_versions") ?: JSONObject()).put("$key/$id", next))
    }
    private fun newer(key: String, existing: JSONObject?, incoming: JSONObject): JSONObject {
        if (existing == null || key !in setOf("notes", "prompts", "runs") || incoming.optBoolean("pending")) return incoming
        if (key == "runs" && existing.optString("status") in setOf("completed", "failed", "cancelled", "interrupted") &&
            incoming.optString("status") !in setOf("completed", "failed", "cancelled", "interrupted")) return existing
        if (existing.optLong("revision") > incoming.optLong("revision") || existing.optLong("updated_at") > incoming.optLong("updated_at")) return existing
        return incoming
    }
    private val sharedKeys = setOf("notes", "prompts", "drafts", "note_drafts", "runs", "requests", "shared_chat_links", "chat_attachments")
    fun put(key: String, item: JSONObject) = mutate { root ->
        check(key !in sharedKeys || !root.optBoolean("frozen")) { "This local workspace was cleared; pair it again before saving shared data" }
        val existing = root.optJSONArray(key)?.objects().orEmpty().find { it.optString("id") == item.getString("id") }
        val chosen = newer(key, existing, item)
        val all = root.optJSONArray(key)?.objects().orEmpty().filterNot { it.optString("id") == item.getString("id") }
        root.put(key, JSONArray(all + chosen))
        stamp(root, key, item.getString("id"))
    }
    fun remove(key: String, id: String) = mutate { root ->
        root.put(key, JSONArray(root.optJSONArray(key)?.objects().orEmpty().filterNot { it.optString("id") == id }))
        stamp(root, key, id)
    }
    @Synchronized fun capture(title: String, text: String, audio: File? = null, mime: String = "audio/mp4"): JSONObject {
        check(!snapshot().optBoolean("frozen")) { "This local workspace was cleared" }
        val id = newId()
        val recording = audio?.let {
            val target = File(mediaDir, "$id.${if (mime == "audio/wav") "wav" else "m4a"}")
            FileOutputStream(target).use { output -> it.inputStream().use { input -> input.copyTo(output) }; output.fd.sync() }
            target.name
        }
        return JSONObject().put("id", id).put("title", title.take(200)).put("text", text)
            .put("kind", if (audio == null) "text" else "audio").put("local_media", recording)
            .put("mime", mime).put("pending", true).put("created_at", System.currentTimeMillis())
            .also { put("notes", it) }
    }
    /** Preserve local pending capture; remote deletions remove only clean cached items. */
    fun reconcile(key: String, remote: List<JSONObject>, requestedAtVersion: Long = Long.MAX_VALUE) = mutate { root ->
        check(!root.optBoolean("frozen")) { "This local workspace was cleared" }
        val previous = root.optJSONArray(key)?.objects().orEmpty()
        val versions = root.optJSONObject("cache_versions") ?: JSONObject()
        val old = previous.associateBy { it.getString("id") }
        val retained = previous.filter { it.optBoolean("pending") || versions.optLong("$key/${it.getString("id")}") > requestedAtVersion }
        val retainedIds = retained.map { it.getString("id") }.toSet()
        val merged = remote.filterNot { incoming ->
            incoming.getString("id") in retainedIds ||
                (incoming.getString("id") !in old && versions.optLong("$key/${incoming.getString("id")}") > requestedAtVersion)
        }.map { incoming ->
            val existing = old[incoming.getString("id")]
            newer(key, existing, incoming).copyJson().also { next ->
                existing?.stringOrEmpty("local_media")?.takeIf(String::isNotBlank)?.let { next.put("local_media", it) }
            }
        }
        root.put(key, JSONArray(merged + retained))
    }
    fun saveDraft(prompt: JSONObject) = put("drafts", prompt)
    /** A delayed sync cannot replace the durable identity of a newer question in this chat. */
    @Synchronized fun confirmChatAdmission(conversation: String, operation: String, requestId: String, runId: String): Boolean {
        val pending = item("shared_chat_links", conversation) ?: return false
        if (pending.optString("operation") != operation || pending.optString("request_id") != requestId ||
            pending.stringOrEmpty("run_id").let { it.isNotBlank() && it != runId }) return false
        mutate { root ->
            val links = root.optJSONArray("shared_chat_links")?.objects().orEmpty()
            root.put("shared_chat_links", JSONArray(links.map { if (it.optString("id") == conversation) it.put("run_id", runId) else it }))
            if (pending.optJSONObject("prompt") != null) root.put("chat_attachments", JSONArray(
                root.optJSONArray("chat_attachments")?.objects().orEmpty().filterNot { it.optString("id") == conversation }))
        }
        return true
    }
    /** Completion belongs to one receipt, even if another caller has already begun a follow-up. */
    @Synchronized fun finishChat(conversation: String, runId: String) {
        if (item("shared_chat_links", conversation)?.optString("run_id") == runId) remove("shared_chat_links", conversation)
    }
    fun resumeAfterEnrollment() = mutate { it.put("frozen", false) }
    @Synchronized fun clearShared() {
        mutate { root -> root.put("frozen", true); listOf("notes", "prompts", "drafts", "note_drafts", "runs", "resume", "requests", "shared_chat_links", "chat_attachments").forEach { root.remove(it) } }
        val recordings = mediaDir.listFiles() ?: error("Local recordings could not be enumerated for erasure")
        recordings.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
        check(mediaDir.listFiles()?.isEmpty() == true) { "Some local recordings could not be erased" }
    }
    /** A receipt transitions to executing before effects. Restarted effects are never replayed. */
    @Synchronized fun beginCommand(command: JSONObject, device: String, now: Long): Boolean {
        val id = command.getString("id")
        if (item("receipts", id) != null) return false
        val rejection = when {
            command.optString("device_id") != device -> "Command targets another device"
            command.optLong("expires_at") <= now -> "Command expired"
            else -> null
        }
        put("receipts", JSONObject().put("id", id).put("device_id", device)
            .put("status", if (rejection == null) "received" else "failed").put("result", JSONObject().put("message", rejection ?: "Received"))
            .put("ack_pending", true))
        return rejection == null
    }
    fun commandState(id: String, status: String, result: String) {
        val receipt = requireNotNull(item("receipts", id))
        put("receipts", receipt.put("status", status).put("result", JSONObject().put("message", result)).put("ack_pending", true))
    }
    fun recoverCommands() {
        items("receipts").filter { it.optString("status") in setOf("executing", "received") }.forEach {
            commandState(it.getString("id"), "unknown", "Device restarted before completion was recorded; action will not repeat")
        }
    }
}
