package dev.r1ptt.workspace

import dev.r1ptt.App
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One serialized network writer per device, revision-fenced by the host across devices. */
class WorkspaceClient(private val app: App) {
    val identity = HubIdentity(app)
    val store = WorkspaceStore(File(app.filesDir, "workspace"))
    private val http = HubHttp()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow("Saved on this R1")
    val status: StateFlow<String> = mutable
    private var loop: Job? = null
    val management by lazy { HubManagement(app, this) }
    private val recoveryFence = RecoveryFence().apply { if (identity.paired) invalidate() }
    private var recoveryJob: Job? = null
    val quarantined: Boolean get() = identity.quarantined || identity.error != null || store.loadError != null
    val recoveryPending: Boolean get() = identity.paired && recoveryFence.pending()
    val blocked: Boolean get() = quarantined || recoveryPending
    val canReenroll: Boolean get() = identity.value.let { it.optBoolean("local_workspace_cleared") && !it.optBoolean("lost") && !it.optBoolean("revoked") } && identity.error == null && recoveryFence.verified()
    val networkAllowed: Boolean get() = !blocked && (!identity.paired || recoveryFence.verified())
    internal fun recoveryTicket(): Long = recoveryFence.ticket()
    internal fun recoveryConfirmed(ticket: Long) {
        if (recoveryFence.confirm(ticket)) mutable.value = if (quarantined) "Device access restricted" else "Device recovery verified"
    }
    /** Fence synchronously before any activity/button/API can observe the newly awake device. */
    fun requireRecovery(checkNow: Boolean = true) {
        if (!identity.paired) return
        recoveryFence.invalidate()
        mutable.value = "Checking device recovery…"
        dev.r1ptt.ui.DeviceActivity.refreshRecovery(app)
        if (checkNow) recoverNow()
    }
    @Synchronized fun recoverNow() {
        if (!identity.paired || recoveryJob?.isActive == true) return
        val ticket = recoveryTicket()
        recoveryJob = scope.launch {
            try {
                app.radio.hold("workspace-wake-recovery", true)
                // Recovery has its own short HTTP deadline; never queue behind a transcription.
                withTimeoutOrNull(15_000) { if (app.radio.awaitOnline(5_000)) recoveryOnly() }
            } catch (_: Exception) { /* Known lost state remains durable through every failure. */ }
            finally {
                recoveryFence.offline(ticket)
                app.radio.hold("workspace-wake-recovery", false)
                if (!recoveryFence.verified()) mutable.value = if (quarantined) "Device access restricted" else "Recovery not verified · connect to your host and retry"
                withContext(NonCancellable + Dispatchers.Main) { dev.r1ptt.ui.DeviceActivity.refreshRecovery(app) }
                synchronized(this@WorkspaceClient) { recoveryJob = null }
                // A new wake/network event during the request must receive its own fresh check.
                if (recoveryPending && recoveryTicket() != ticket) recoverNow()
            }
        }
    }
    suspend fun recoveryOnly() = withContext(Dispatchers.IO) { recoveryLock.withLock {
        val applied = identity.value.optLong("applied_policy_revision")
        management.checkIn()
        // Confirm the newly applied policy immediately, then receive only its permitted commands.
        if (identity.value.optLong("applied_policy_revision") != applied) management.checkIn()
    } }
    private val recoveryLock = Mutex()

    fun start() {
        if (loop?.isActive == true) return
        store.recoverCommands()
        management.scheduleRecovery()
        loop = scope.launch {
            while (isActive) {
                if (identity.paired && app.radio.isOnline()) runCatching { sync() }
                delay(30_000)
            }
        }
    }
    internal fun cancelTransfers() = http.cancelAll()
    fun refresh() { scope.launch { runCatching { sync() } } }
    suspend fun pair(url: String, code: String, name: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!identity.paired || canReenroll) { "This R1 is already paired. Revoke its enrollment in the host console before clearing app data to pair again." }
            identity.error?.let { error(it) }
            val base = HubHttp.normalize(url)
            val result = http.json(base, "", "POST", "/enrollments/claim", JSONObject().put("code", code.trim()).put("name", name.trim()).put("lost", false).put("revoked", false).put("local_workspace_cleared", false)
                    .put("settings_revision", 0).put("permissions", JSONObject()))
            check(!identity.paired || canReenroll) { "Recovery access changed while pairing. Restore this R1 in the owner console first." }
            identity.update { root ->
                root.put("base_url", base).put("device_id", result.getString("device_id"))
                    .put("device_token", result.getString("device_token")).put("recovery_token", result.getString("recovery_token"))
                    .put("name", name.trim()).put("lost", false).put("revoked", false).put("local_workspace_cleared", false)
                    .put("settings_revision", 0).put("permissions", JSONObject())
                    .put("policy_revision", 0).put("applied_policy_revision", 0).put("secure_lock_applied", false)
                root.remove("lost_message"); root.remove("return_message")
            }
        }
        store.resumeAfterEnrollment()
        app.store.update { it.copy(agent = it.agent.copy(apiKey = "", headers = emptyMap()),
            hermes = it.hermes.copy(token = newId().replace("-", "") + newId().replace("-", ""))) }
        requireRecovery(); start(); sync()
    }
    internal fun api(method: String, path: String, body: JSONObject? = null, recovery: Boolean = false): JSONObject {
        check(identity.paired) { "Pair this R1 with a host first" }
        if (!recovery) check(networkAllowed) { "Shared workspace access is restricted on this device" }
        val credentials = identity.value
        try {
            val result = http.json(credentials.getString("base_url"), credentials.getString(if (recovery) "recovery_token" else "device_token"), method, path, body)
            check(identity.value.optString("device_id") == credentials.getString("device_id")) { "Device enrollment changed while the request was running" }
            if (!recovery) check(networkAllowed) { "Shared workspace access changed while the request was running" }
            return result
        } catch (e: HubException) {
            if (!recovery && e.status in setOf(401, 403)) {
                identity.update { it.put("revoked", true) }
                withRecoveryRestriction()
            }
            throw e
        }
    }
    private fun withRecoveryRestriction() {
        mutable.value = "Device access restricted"
        dev.r1ptt.ui.DeviceActivity.refreshRecovery(app)
        requireRecovery()
    }
    /** Active host runs can request a native tool; drain its durable queue without a workspace upload. */
    suspend fun checkCommands() = withContext(Dispatchers.IO) {
        lock.withLock {
            check(identity.paired && !quarantined) { "Shared workspace access is restricted" }
            try {
                recoveryOnly()
                check(!blocked) { "Shared workspace access changed during check-in" }
            } finally {
                // Learning quarantine can cancel this active chat. Wake Home's content gate even
                // when cancellation prevents checkIn from returning normally.
                if (blocked) mutable.value = "Workspace access restricted"
            }
        }
    }
    suspend fun sync() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!identity.paired) return@withLock
            mutable.value = "Syncing…"
            try {
                recoveryOnly()
                if (blocked) { mutable.value = "Workspace access restricted"; return@withLock }
                syncNotes()
                listOf("notes", "prompts", "runs").forEach { key ->
                    val version = store.version()
                    store.reconcile(key, api("GET", "/$key").getJSONArray("items").objects(), version)
                }
                SharedChat.importHistory(app)
                val resume = api("GET", "/resume")
                store.mutate { it.put("resume", resume) }
                mutable.value = when {
                    store.items("notes").any { it.optBoolean("pending") } -> "Saved on this R1 · waiting for connection"
                    store.items("drafts").isNotEmpty() -> "Notes synced · prompt draft on this R1"
                    else -> "Synced"
                }
            } catch (e: Exception) {
                mutable.value = if (e is HubException) e.message.orEmpty() else "Saved on this R1 · waiting for connection"
                throw e
            }
        }
    }
    internal fun syncNotes() {
        check(networkAllowed) { "Check device recovery before syncing" }
        store.items("notes").filter { it.optBoolean("pending") }.forEach { note ->
            try {
                if (note.optString("kind") == "audio" && note.stringOrEmpty("media_id").isBlank()) {
                    val credentials = identity.value
                    val media = http.upload(credentials.getString("base_url"), credentials.getString("device_token"), note.getString("id"),
                        File(store.mediaDir, note.getString("local_media")), note.optString("mime", "audio/mp4"))
                    check(networkAllowed && identity.value.optString("device_id") == credentials.getString("device_id")) { "Workspace access changed during upload" }
                    note.put("media_id", media.getString("id")); store.put("notes", note)
                }
                // Journal the exact creation body before admission. Local edits never change a
                // retry of an uncertain POST, even when the host committed but its reply was lost.
                val creation = note.optJSONObject("creation_payload") ?: JSONObject()
                    .put("id", note.getString("id")).put("title", note.optString("title")).put("kind", note.getString("kind"))
                    .put("text", note.optString("text")).put("media_id", note.stringOrEmpty("media_id").takeIf(String::isNotBlank)).also {
                        note.put("creation_payload", it); store.put("notes", note)
                    }
                var saved = try { api("POST", "/notes", creation) } catch (conflict: HubException) {
                    if (conflict.status != 409) throw conflict
                    // Old uncertain captures can predate the immutable journal. Recover the
                    // canonical note and preserve an independently edited local copy explicitly.
                    api("GET", "/notes/${note.getString("id")}")
                }
                fun sameText(left: JSONObject, right: JSONObject) = listOf("title", "text").all { left.optString(it) == right.optString(it) }
                if (!sameText(saved, note)) {
                    if (sameText(saved, creation)) {
                        saved = api("PUT", "/notes/${note.getString("id")}", JSONObject().put("base_revision", saved.getLong("revision"))
                            .put("title", note.optString("title")).put("text", note.optString("text")))
                    } else store.put("note_drafts", note.copyJson().put("sync_error", "This note also changed on another device"))
                }
                note.stringOrEmpty("local_media").takeIf(String::isNotBlank)?.let { saved.put("local_media", it) }
                store.put("notes", saved.put("pending", false))
            } catch (error: HubException) {
                if (error.status !in setOf(400, 409, 413, 422)) throw error
                // One invalid/conflicting capture cannot indefinitely block other notes, prompts
                // or recovery. Keep the original and its error available for an explicit retry.
                if (error.status in setOf(400, 413, 422)) note.remove("creation_payload")
                store.put("notes", note.put("sync_error", error.message))
            }
        }
    }
    fun saveLocalDraft(prompt: JSONObject) {
        store.saveDraft(prompt); mutable.value = "Prompt draft saved on this R1 · save to sync"
    }
    fun capture(title: String, text: String, audio: File? = null, mime: String = "audio/mp4"): JSONObject {
        check(!blocked) { "Shared workspace access is restricted" }
        val note = store.capture(title, text, audio, mime)
        mutable.value = "Saved on this R1 · waiting for connection"; refresh()
        return note
    }
    suspend fun transcribe(id: String): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        syncNotes()
        val note = requireNotNull(store.item("notes", id))
        api("POST", "/notes/$id/transcribe", JSONObject().put("base_revision", note.getLong("revision"))).also { result ->
            note.stringOrEmpty("local_media").takeIf(String::isNotBlank)?.let { result.put("local_media", it) }; store.put("notes", result)
        }
    } }
    suspend fun editNote(note: JSONObject, title: String, text: String): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        check(!blocked)
        val id = note.getString("id")
        store.put("note_drafts", note.copyJson().put("title", title).put("text", text))
        val current = store.item("notes", id) ?: error("This note was removed. Your local edit is preserved.")
        val saved = if (current.optBoolean("pending")) current.copyJson().put("title", title).put("text", text).also { store.put("notes", it) }
        else {
            // A capture can finish uploading while its editor is open. Only adopt its first server
            // revision when the content still matches the editor's starting snapshot.
            val base = if (note.optBoolean("pending")) {
                check(current.optString("text") == note.optString("text") && current.optString("title") == note.optString("title")) {
                    "This note changed while you were editing. Your local edit is preserved."
                }
                current.getLong("revision")
            } else note.getLong("revision")
            api("PUT", "/notes/$id", JSONObject().put("base_revision", base).put("title", title).put("text", text)).also {
                current.stringOrEmpty("local_media").takeIf(String::isNotBlank)?.let { path -> it.put("local_media", path) }
                store.put("notes", it)
            }
        }
        store.remove("note_drafts", id); saved
    } }
    suspend fun recording(note: JSONObject): File = withContext(Dispatchers.IO) {
        check(!blocked)
        note.stringOrEmpty("local_media").takeIf(String::isNotBlank)?.let { name -> File(store.mediaDir, name).takeIf(File::exists)?.let { return@withContext it } }
        check(networkAllowed) { "Check device recovery before downloading" }
        val credentials = identity.value; val id = note.getString("media_id")
        val downloaded = http.media(credentials.getString("base_url"), credentials.getString("device_token"), id, File(store.mediaDir, "download-$id.audio"))
        if (!networkAllowed || identity.value.optString("device_id") != credentials.getString("device_id")) {
            downloaded.delete(); error("Workspace access changed during download")
        }
        downloaded
    }
    suspend fun createPrompt(title: String, notes: List<JSONObject>): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        syncNotes()
        val blocks = notes.map { selected -> block(store.item("notes", selected.getString("id")) ?: selected) }
        api("POST", "/prompts", JSONObject().put("id", newId()).put("title", title).put("instruction", "").put("blocks", JSONArray(blocks)))
            .also { store.put("prompts", it) }
    } }
    suspend fun acquire(id: String, takeover: Boolean = false): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        val lease = api("POST", "/prompts/$id/lease", JSONObject().put("takeover", takeover))
        api("GET", "/prompts/$id").put("lease_token", lease.getString("lease_token"))
    } }
    suspend fun renew(prompt: JSONObject): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        val lease = api("POST", "/prompts/${prompt.getString("id")}/lease", JSONObject().put("takeover", false))
        check(lease.getString("lease_token") == prompt.getString("lease_token")) { "Editing moved to another device. Your local draft is preserved." }
        lease
    } }
    suspend fun save(prompt: JSONObject, scroll: Int = 0): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        store.saveDraft(prompt)
        // Renew only the existing holder. A takeover elsewhere must fence this saved token.
        val lease = api("POST", "/prompts/${prompt.getString("id")}/lease", JSONObject().put("takeover", false))
        check(lease.getString("lease_token") == prompt.getString("lease_token")) { "Editing moved to another device. Your local draft is preserved." }
        val cached = store.item("prompts", prompt.getString("id"))
        val unchanged = cached != null && cached.optLong("revision") == prompt.getLong("revision") &&
            listOf("title", "instruction", "blocks").all { cached.opt(it)?.toString() == prompt.opt(it)?.toString() }
        val saved = if (unchanged) api("GET", "/prompts/${prompt.getString("id")}").also {
            check(it.getLong("revision") == prompt.getLong("revision")) { "This prompt changed on another device. Your local draft is preserved." }
        } else api("PUT", "/prompts/${prompt.getString("id")}", prompt.copyJson().put("base_revision", prompt.getLong("revision")))
        store.put("prompts", saved); store.remove("drafts", prompt.getString("id"))
        val resume = api("GET", "/resume")
        runCatching { api("PUT", "/resume", JSONObject().put("prompt_id", prompt.getString("id")).put("block_id", JSONObject.NULL)
            .put("scroll_y", scroll).put("base_revision", resume.optLong("revision"))) }
        mutable.value = "Synced"
        saved.put("lease_token", lease.getString("lease_token"))
    } }
    fun release(prompt: JSONObject) { scope.launch { lock.withLock { runCatching {
        api("POST", "/prompts/${prompt.getString("id")}/release", JSONObject().put("lease_token", prompt.optString("lease_token")))
    } } } }
    suspend fun run(prompt: JSONObject, again: Boolean = false): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        val key = "${prompt.getString("id")}:${prompt.getLong("revision")}:$again"
        val pending = store.items("requests").find { it.optString("key") == key }
        val request = pending ?: JSONObject().put("id", newId()).put("key", key).put("revision", prompt.getLong("revision"))
            .put("request_id", newId()).put("run_again", again).also { store.put("requests", it) }
        api("POST", "/prompts/${prompt.getString("id")}/run", request).also {
            store.put("runs", it); store.remove("requests", request.getString("id"))
        }
    } }
    suspend fun runAction(id: String, action: String, approvalId: String? = null, choice: String? = null): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        val key = "$id:$action:${approvalId.orEmpty()}:${choice.orEmpty()}"
        val request = store.items("requests").find { it.optString("key") == key } ?: JSONObject().put("id", newId()).put("key", key)
            .put("request_id", newId()).put("approval_id", approvalId).put("choice", choice).also { store.put("requests", it) }
        api("POST", "/runs/$id/$action", request).also { store.put("runs", it); store.remove("requests", request.getString("id")) }
    } }
    companion object {
        /** Resolve durable audio IDs into a copy; failed hydration never discards a local draft. */
        fun hydrateMedia(prompt: JSONObject, note: (String) -> JSONObject?): JSONObject = prompt.copyJson().also { hydrated ->
            hydrated.getJSONArray("blocks").objects().forEach { block ->
                if (block.optString("kind") == "audio" && block.stringOrEmpty("media_id").isBlank()) {
                    val source = note(block.optString("note_id")) ?: error("Original recording is missing; the local draft was preserved")
                    val media = source.stringOrEmpty("media_id")
                    check(!source.optBoolean("pending") && media.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
                        "Wait for recording upload before saving this prompt; the local draft was preserved"
                    }
                    block.put("media_id", media)
                }
            }
        }
        fun block(note: JSONObject) = JSONObject().put("id", newId()).put("note_id", note.getString("id"))
            .put("kind", note.getString("kind")).put("text", note.optString("text")).put("media_id", note.stringOrEmpty("media_id").takeIf(String::isNotBlank)?.also {
                require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "The recording identifier is invalid" }
            })
        fun preview(prompt: JSONObject) = (listOf(prompt.optString("instruction")) + prompt.getJSONArray("blocks").objects().map { it.optString("text") })
            .filter(String::isNotBlank).joinToString("\n\n")
    }
}
