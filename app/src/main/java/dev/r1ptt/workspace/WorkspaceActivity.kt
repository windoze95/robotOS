package dev.r1ptt.workspace

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.audio.Recorder
import dev.r1ptt.audio.AudioPlayback
import dev.r1ptt.ui.DeviceActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Native, wheel-scrollable shared notes and Prompt Builder. Nothing submits while capturing. */
class WorkspaceActivity : DeviceActivity() {
    private val app get() = application as App
    private val hub get() = app.workspace
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var body: LinearLayout
    private var status: TextView? = null
    private var prompt: JSONObject? = null
    private var screen = "index"
    private var saving = false
    private var recorder: Recorder? = null
    private var recordingDestination: RecordingDestination<JSONObject>? = null
    private var playback: MediaPlayer? = null
    private var selected = linkedSetOf<String>()
    private var statusJob: Job? = null
    private var leaseJob: Job? = null
    private var audioButton: android.widget.Button? = null
    private var viewedPromptId: String? = null
    private var returnChat: String? = null
    private var draftFields: (() -> Unit)? = null
    private var draftContent: String? = null
    private var noteEdited: (() -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        landingPage(); hub.refresh()
    }
    override fun onResume() {
        super.onResume()
        active = java.lang.ref.WeakReference(this)
        var pressedRecorder: Recorder? = null
        button = { down ->
            if (down) {
                if (screen in setOf("index", "notes", "builder") && !hub.blocked) {
                    startRecording(); pressedRecorder = recorder
                }
            } else {
                // UP belongs to the recorder captured at DOWN, even after navigation or pause.
                if (pressedRecorder != null && recorder === pressedRecorder) finishRecording()
                pressedRecorder = null
            }
        }
        hub.start(); hub.refresh()
        statusJob = scope.launch { hub.status.collect { value ->
            status?.text = value
            if (hub.quarantined && screen != "restricted") restricted()
            else if (!hub.blocked && screen == "restricted") landingPage()
            else if (!hub.blocked && value != "Syncing…" && screen in setOf("index", "notes", "prompts")) {
                val y = wheelScrollTarget?.scrollY ?: 0
                when (screen) { "index" -> index(); "notes" -> notes(); "prompts" -> prompts() }
                wheelScrollTarget?.post { wheelScrollTarget?.scrollTo(0, y) }
            }
        } }
    }
    override fun onPause() {
        active = null; button = null; finishRecording(showResult = false); stashDraft(); stopPlayback(); statusJob?.cancel()
        super.onPause()
    }
    override fun onDestroy() { leaseJob?.cancel(); prompt?.let(hub::release); scope.cancel(); super.onDestroy() }
    @Deprecated("Platform Activity back handling") override fun onBackPressed() = back()
    private fun back() {
        if (saving) { showToast("Wait for this action to finish; your local draft is preserved"); return }
        if (recorder != null) { finishRecording(); return }
        stashDraft(); stopPlayback()
        when (screen) {
            "index", "restricted" -> finish()
            "prompt" -> prompts()
            "prompt_input" -> viewedPromptId?.let { promptDetail(it) } ?: prompts()
            "builder" -> returnChat?.let(::returnToChat) ?: (prompt?.getString("id")?.let { promptDetail(it) } ?: prompts())
            "preview" -> prompt?.let(::builder) ?: prompts()
            else -> { releasePrompt(); index() }
        }
    }
    private fun page(title: String, name: String, back: () -> Unit = ::back) {
        screen = name; draftFields = null; draftContent = null; audioButton = null; noteEdited = null
        val layout = ui.page(title, back); body = layout.body; wheelScrollTarget = layout.scroll
        setContentView(layout.root)
        status = ui.note(hub.status.value).also(body::addView)
    }
    private fun restricted() {
        stashDraft()
        recorder?.cancel(); recorder = null; recordingDestination = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopPlayback(); page("Device recovery", "restricted")
        body.addView(ui.title("Shared access is paused"))
        val identity = hub.identity.value
        body.addView(ui.note((hub.store.loadError ?: identity.optString("lost_message")).ifBlank { "This R1 was marked lost or its shared access was revoked. Use your host console to restore access." }))
        identity.optString("return_message").takeIf(String::isNotBlank)?.let { body.addView(ui.text(it)) }
        body.addView(ui.button("Stop ringing") { hub.management.stopRinging() })
        body.addView(ui.button("Check recovery status") { task { hub.sync(); if (!hub.blocked) landingPage() } })
    }
    private fun landingPage() {
        if (hub.blocked) { restricted(); return }
        // Recheck launches queued before the local permission or connection changed.
        val token = intent.getStringExtra(EXTRA_DEVICE_TOKEN)
        intent.removeExtra(EXTRA_DEVICE_TOKEN)
        if (token != null && (!app.store.value.hermes.deviceApi || !app.store.value.hermes.allowOpenApps ||
                token != app.store.value.hermes.token)) { finish(); return }
        intent.getStringExtra(EXTRA_SAVED_PROMPT_ID)?.let { id ->
            intent.removeExtra(EXTRA_SAVED_PROMPT_ID)
            promptDetail(id)
            return
        }
        if (intent.getBooleanExtra(EXTRA_NOTES, false)) {
            intent.removeExtra(EXTRA_NOTES)
            openNotes(intent.getStringExtra(EXTRA_NOTE_ID))
            intent.removeExtra(EXTRA_NOTE_ID)
            return
        }
        val promptId = intent.getStringExtra(EXTRA_PROMPT_ID)
        if (promptId != null) {
            returnChat = intent.getStringExtra(EXTRA_RETURN_CHAT)
            intent.removeExtra(EXTRA_PROMPT_ID)
            page("Prompt Builder", "opening")
            openPrompt(promptId)
            return
        }
        val openPrompts = intent.getBooleanExtra(EXTRA_PROMPTS, false) || intent.getBooleanExtra(EXTRA_RUNS, false)
        intent.removeExtra(EXTRA_PROMPTS)
        intent.removeExtra(EXTRA_RUNS)
        if (openPrompts) prompts() else index()
    }
    private fun index() {
        if (hub.blocked) { restricted(); return }
        returnChat = null
        page("Workspace", "index")
        if (!hub.identity.paired) {
            body.addView(ui.note("Pair with your robotOS host to share notes and prompts across your R1s. Notes captured here stay on this R1 until pairing."))
            body.addView(ui.button("Pair a host", primary = true) { startActivity(Intent(this, HubSettingsActivity::class.java)) })
        }
        body.addView(ui.button("Write note", primary = true) { writeNote() })
        recordingControl()
        body.addView(ui.actionRow("Notes", "${hub.store.items("notes").size} saved · select several for a prompt") { notes() })
        body.addView(ui.actionRow("Prompt Builder", "Saved prompts and drafts") { prompts() })
        val resume = hub.store.snapshot().optJSONObject("resume")
        resume?.optString("prompt_id")?.takeIf { it.isNotBlank() && it != "null" }?.let { id ->
            if (id.startsWith("shared-")) body.addView(ui.button("Continue shared chat", primary = true) {
                if (app.turns.busy) showToast("Finish the current conversation before switching chats")
                else if (app.history.select(id)) {
                    startActivity(Intent(this, dev.r1ptt.HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish()
                } else showToast("Sync the shared reply first")
            })
            else body.addView(ui.button("Continue last saved prompt") { openPrompt(id, scroll = resume.optInt("scroll_y")) })
        }
        body.addView(ui.button("Sync now") { task { hub.sync(); index() } })
        body.addView(ui.actionRow("Host & device access", hub.identity.value.optString("name", "Not paired")) { startActivity(Intent(this, HubSettingsActivity::class.java)) })
    }
    private fun notes() {
        if (hub.blocked) { restricted(); return }
        page("Notes", "notes")
        body.addView(ui.button("Write note", primary = true) { writeNote() }); recordingControl()
        if (selected.isNotEmpty()) body.addView(ui.button("Add ${selected.size} to prompt", primary = true) { choosePrompt(selected.mapNotNull { hub.store.item("notes", it) }) })
        hub.store.items("note_drafts").forEach { draft ->
            body.addView(ui.actionRow("Local edit: ${draft.optString("title")}", "Saved on this R1 · recover as a new note") {
                page("Recover note edit", "note_draft")
                body.addView(ui.text(draft.optString("text")))
                body.addView(ui.button("Save as a new text note", primary = true) {
                    try { hub.capture(draft.optString("title"), draft.optString("text")); hub.store.remove("note_drafts", draft.getString("id")); notes() }
                    catch (e: Exception) { showToast(safe(e)) }
                })
                body.addView(ui.note("Any original voice recording remains with its source note."))
            })
        }
        val items = hub.store.items("notes").sortedByDescending { it.optLong("created_at") }
        if (items.isEmpty()) body.addView(ui.note("Capture a thought with Write note or hold the side button to record. Release to save."))
        items.forEach { note ->
            val id = note.getString("id")
            val card = ui.card()
            card.addView(ui.toggle(note.optString("title").ifBlank { "Untitled note" }, id in selected) { checked ->
                if (checked) selected.add(id) else selected.remove(id); notes()
            })
            card.addView(ui.note(note.optString("sync_error").ifBlank { if (note.optBoolean("pending")) "Saved on this R1 · waiting for connection" else "Synced" }))
            card.addView(ui.text(note.optString("text").ifBlank { "Voice recording · transcript pending" }, 15f).apply { maxLines = 3 })
            card.addView(ui.button("Open note") { noteDetail(note) }); body.addView(card)
        }
    }
    internal fun canNavigateFromTools(): Boolean = !saving && recorder == null && playback == null &&
        screen in setOf("index", "notes", "note", "prompts", "prompt") && noteEdited?.invoke() != true

    internal fun openApp(destination: String, itemId: String?) {
        if (hub.blocked) { restricted(); return }
        when (destination) {
            "workspace" -> { releasePrompt(); index() }
            "notes" -> openNotes(itemId)
            "prompt_builder" -> if (itemId == null) { releasePrompt(); prompts() } else promptDetail(itemId)
            else -> error("Unsupported workspace destination")
        }
    }

    internal fun openNotes(noteId: String?) {
        if (hub.blocked) { restricted(); return }
        releasePrompt(); returnChat = null
        if (noteId == null) notes()
        else {
            val note = hub.store.item("notes", noteId)
            if (note != null) noteDetail(note)
            else { notes(); showToast("That note is no longer available on this R1") }
        }
    }

    private fun noteDetail(note: JSONObject) {
        page("Note", "note")
        val title = field("Title", note.optString("title")); val text = field("Note or transcript", note.optString("text"), multi = true)
        noteEdited = { title.text.toString() != note.optString("title") || text.text.toString() != note.optString("text") }
        body.addView(ui.button("Save note", primary = true) {
            if (saving) return@button
            val savedTitle = title.text.toString(); val savedText = text.text.toString()
            saving = true; enableInputs(body, false)
            task(onError = { error -> saving = false; enableInputs(body, true); showToast(safe(error)) }) {
                val saved = hub.editNote(note, savedTitle, savedText)
                saving = false
                if (hub.blocked) restricted() else noteDetail(saved)
            }
        })
        body.addView(ui.button("Add to prompt") { choosePrompt(listOf(note)) })
        if (note.optString("kind") == "audio") {
            body.addView(ui.button("Play original recording") { play(note) })
            body.addView(ui.button("Transcribe on host") {
                if (text.text.toString() != note.optString("text") || title.text.toString() != note.optString("title")) showToast("Save your note edits before transcribing")
                else {
                    saving = true; enableInputs(body, false)
                    task(onError = { error -> saving = false; enableInputs(body, true); showToast(safe(error)) }) {
                        val result = hub.transcribe(note.getString("id")); saving = false
                        if (hub.blocked) restricted() else noteDetail(result)
                    }
                }
            })
            body.addView(ui.note("A failed transcription keeps the original recording. You can enter or correct its transcript above."))
        }
    }
    private fun writeNote() {
        stashDraft()
        val returnTo = prompt
        page("Write note", "write")
        val title = field("Title"); val text = field("Your note", multi = true)
        body.addView(ui.button(if (returnTo == null) "Save note" else "Save and add to prompt", primary = true) {
            if (text.text.isBlank()) { text.error = "Enter a note"; return@button }
            try {
                val note = hub.capture(title.text.toString().ifBlank { "Note" }, text.text.toString())
                if (returnTo == null) notes() else { appendNotes(returnTo, listOf(note)); builder(returnTo) }
            } catch (e: Exception) { showToast(safe(e)) }
        })
    }
    private fun recordingControl() {
        audioButton = ui.button(if (recorder == null) "Record voice note" else "Stop and save recording") {
            if (recorder == null) startRecording() else finishRecording()
        }.also(body::addView)
        body.addView(ui.note("Hold the side button to record; release to save. Up to two minutes per clip. Recordings stay on this R1 until upload is confirmed."))
    }
    private fun startRecording() {
        if (recorder != null || hub.blocked || saving) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 91); return
        }
        if (app.turns.busy) { showToast("Finish your conversation before recording a note"); return }
        stopPlayback(); app.radio.onActivity()
        val next = Recorder(File(app.filesDir, "workspace-capture")) {}
        if (!next.start()) { showToast("The microphone is unavailable"); return }
        stashDraft()
        recordingDestination = RecordingDestination(screen, prompt)
        recorder = next; audioButton?.text = "Stop and save recording"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status?.text = "Recording · release to save"
    }
    private fun finishRecording(showResult: Boolean = true) {
        val active = recorder ?: return; recorder = null
        val destination = recordingDestination; recordingDestination = null
        audioButton?.text = "Record voice note"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val clip = active.stop() ?: return
        if (clip.durationMs < 150) { clip.file.delete(); return }
        try {
            val note = hub.capture("Voice note", "", clip.file, clip.mime)
            clip.file.delete()
            val target = destination?.promptIfCurrent(prompt)
            target?.let { appendNotes(it, listOf(note)) }
            if (showResult && destination?.canShowResult(screen, prompt) == true) {
                if (target != null) builder(target) else notes()
            }
            showToast(if (target == null) "Recording saved in Notes on this R1" else "Recording added to this prompt")
        } catch (e: Exception) { showToast(safe(e)) }
    }
    private fun play(note: JSONObject) { task {
        val file = hub.recording(note); stopPlayback()
        playback = MediaPlayer().apply { setDataSource(file.path); prepare(); setOnCompletionListener { stopPlayback() }; start(); AudioPlayback.started(this) }
        showToast("Playing original recording")
    } }
    private fun stopPlayback() { playback?.let { runCatching { it.stop() }; AudioPlayback.stopped(it); it.release() }; playback = null }
    private fun prompts() {
        if (hub.blocked) { restricted(); return }
        returnChat = null
        releasePrompt(); viewedPromptId = null
        page("Prompt Builder", "prompts")
        body.addView(ui.button("New prompt", primary = true) { choosePrompt(emptyList(), onlyNew = true) })
        hub.store.items("drafts").filterNot { it.getString("id").startsWith("shared-") }.forEach { draft ->
            body.addView(ui.actionRow("Local draft: ${draft.optString("title")}", "Saved on this R1 · review before replacing shared content") { localDraft(draft) })
        }
        val all = savedPrompts()
        if (all.isEmpty()) body.addView(ui.note("Create a prompt from notes, recordings or an instruction. Save it here and run it later."))
        all.sortedByDescending { it.optLong("updated_at") }.forEach { saved ->
            body.addView(ui.actionRow(saved.optString("title", "Untitled prompt"), "Saved prompt") { promptDetail(saved.getString("id")) })
        }
        body.addView(ui.button("Refresh") { task { hub.sync(); prompts() } })
    }
    private fun choosePrompt(notes: List<JSONObject>, onlyNew: Boolean = false) {
        if (prompt != null && !onlyNew) { appendNotes(prompt!!, notes); builder(prompt!!); return }
        val existing = if (onlyNew) emptyList() else savedPrompts()
        showDeviceDialog(AlertDialog.Builder(this).setTitle("Add to prompt").setItems((listOf("New prompt") + existing.map { it.optString("title") }).toTypedArray()) { _, selectedIndex ->
            if (selectedIndex == 0) {
                val title = ui.field("Prompt title", "New prompt")
                showDeviceDialog(AlertDialog.Builder(this).setTitle("New prompt").setView(title)
                    .setPositiveButton("Create") { _, _ -> task {
                        val created = hub.createPrompt(title.text.toString().ifBlank { "New prompt" }, notes)
                        selected.clear(); openPrompt(created.getString("id"))
                    } }.setNegativeButton("Cancel", null))
            } else openPrompt(existing[selectedIndex - 1].getString("id"), additions = notes)
        })
    }
    private fun openPrompt(id: String, takeover: Boolean = false, additions: List<JSONObject> = emptyList(), scroll: Int = 0, ignoreDraft: Boolean = false) {
        if (id.startsWith("shared-")) { prompts(); return }
        task(onError = { error ->
            if (error is HubException && error.status in setOf(409, 423)) showDeviceDialog(AlertDialog.Builder(this)
                .setTitle("Continue editing here?").setMessage("Another device holds this prompt. Taking over prevents its delayed saves from replacing your work. Its unsynced draft remains on that device.")
                .setPositiveButton("Continue here") { _, _ -> openPrompt(id, true, additions, scroll, ignoreDraft) }.setNegativeButton("Cancel", null))
            else showToast(safe(error))
        }) {
            val acquired = hub.acquire(id, takeover)
            val draft = if (ignoreDraft) null else hub.store.item("drafts", id)
            if (draft != null) {
                if (draft.optLong("revision") != acquired.getLong("revision")) {
                    hub.release(acquired)
                    localDraft(draft)
                    return@task
                }
                // Take the new lease, but preserve this R1's edits to the same saved revision.
                listOf("title", "instruction", "blocks").forEach { acquired.put(it, draft.get(it)) }
            }
            leaseJob?.cancel()
            leaseJob = scope.launch {
                while (isActive) {
                    delay(30_000)
                    if (prompt?.optString("id") != id) break
                    try { hub.renew(acquired) } catch (_: Exception) { status?.text = "Editing lease needs attention · local draft preserved"; break }
                }
            }
            prompt = acquired
            if (additions.isNotEmpty()) appendNotes(acquired, additions)
            builder(acquired)
            wheelScrollTarget?.post { wheelScrollTarget?.scrollTo(0, scroll) }
        }
    }
    private fun localDraft(draft: JSONObject) {
        page("Local draft", "local_draft")
        body.addView(ui.note("This copy is preserved on this R1. The shared prompt may have changed on another device."))
        body.addView(ui.text(WorkspaceClient.preview(draft)))
        body.addView(ui.button("Save as a new prompt", primary = true) { task {
            // Creation snapshots the complete local draft without overwriting any shared revision.
            hub.sync()
            val recovered = WorkspaceClient.hydrateMedia(draft) { hub.store.item("notes", it) }
            val created = withContext(Dispatchers.IO) { hub.api("POST", "/prompts", recovered.put("id", newId()).put("title", "${draft.optString("title")} (recovered)")) }
            hub.store.put("prompts", created); hub.store.remove("drafts", draft.getString("id"))
            returnChat?.let { hub.store.put("chat_attachments", JSONObject().put("id", it).put("prompt_id", created.getString("id"))) }
            openPrompt(created.getString("id"))
        } })
        body.addView(ui.button("Open shared version") { openPrompt(draft.getString("id"), ignoreDraft = true) })
    }
    private fun appendNotes(target: JSONObject, notes: List<JSONObject>) {
        val blocks = target.getJSONArray("blocks")
        notes.forEach { blocks.put(WorkspaceClient.block(hub.store.item("notes", it.getString("id")) ?: it)) }
        hub.saveLocalDraft(target); selected.clear()
    }
    private fun builder(value: JSONObject) {
        prompt = value; page("Prompt Builder", "builder")
        val title = field("Prompt title", value.optString("title"))
        val instruction = field("Overall instruction", value.optString("instruction"), multi = true)
        val fields = mutableListOf<Pair<JSONObject, EditText>>()
        val blocks = value.getJSONArray("blocks").objects()
        blocks.forEachIndexed { index, block ->
            val card = ui.card(); card.addView(ui.mono("${index + 1} · ${if (block.optString("kind") == "audio") "Voice note" else "Note"}"))
            val text = ui.field("Note or transcript", block.optString("text"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            text.minLines = 2; card.addView(text); fields.add(block to text)
            if (block.optString("kind") == "audio") {
                card.addView(ui.button("Play recording") { hub.store.item("notes", block.optString("note_id"))?.let(::play) ?: play(block) })
                card.addView(ui.button("Transcribe") {
                    stashDraft(); val before = block.optString("text")
                    task {
                        val note = hub.transcribe(block.getString("note_id"))
                        if (prompt !== value || hub.blocked) { showToast("Transcript saved to its source note"); return@task }
                        if (block.optString("text") == before) block.put("text", note.getString("text")).put("media_id", note.opt("media_id"))
                        else showToast("Transcript saved to its source note; your prompt edits were kept")
                        hub.saveLocalDraft(value)
                        if (screen == "builder" && prompt?.optString("id") == value.getString("id")) builder(value)
                    }
                })
            }
            card.addView(ui.row(ui.button("Up") { moveBlock(value, index, -1) }.apply { isEnabled = index > 0 },
                ui.button("Down") { moveBlock(value, index, 1) }.apply { isEnabled = index < blocks.lastIndex }))
            card.addView(ui.button("Remove from prompt") { stashDraft(); value.put("blocks", JSONArray(value.getJSONArray("blocks").objects().filterIndexed { position, _ -> position != index })); hub.saveLocalDraft(value); builder(value) })
            body.addView(card)
        }
        draftFields = {
            value.put("title", title.text.toString()).put("instruction", instruction.text.toString())
            fields.forEach { (block, text) -> block.put("text", text.text.toString()) }
        }
        draftContent = editorContent(value)
        (listOf(title, instruction) + fields.map { it.second }).forEach { field -> field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { stashDraft() }
            override fun afterTextChanged(s: Editable?) = Unit
        }) }
        body.addView(ui.button("Write a new note") { writeNote() }); recordingControl()
        body.addView(ui.button("Add existing notes") { stashDraft(); pickNotes(value) })
        body.addView(ui.button("Preview") { stashDraft(); preview(value) })
        body.addView(ui.button(if (returnChat != null) "Save and return to chat" else "Save prompt", primary = true) { savePrompt(value) })
        if (returnChat == null) body.addView(ui.button("Run in new chat") { savePrompt(value, openChat = true) })
    }
    private fun moveBlock(value: JSONObject, index: Int, direction: Int) {
        stashDraft(); val blocks = value.getJSONArray("blocks").objects().toMutableList()
        java.util.Collections.swap(blocks, index, index + direction); value.put("blocks", JSONArray(blocks)); hub.saveLocalDraft(value); builder(value)
    }
    private fun pickNotes(value: JSONObject) {
        val notes = hub.store.items("notes"); val picks = BooleanArray(notes.size)
        showDeviceDialog(AlertDialog.Builder(this).setTitle("Add existing notes")
            .setMultiChoiceItems(notes.map { it.optString("title", "Note") }.toTypedArray(), picks) { _, index, checked -> picks[index] = checked }
            .setPositiveButton("Add") { _, _ -> appendNotes(value, notes.filterIndexed { i, _ -> picks[i] }); builder(value) }.setNegativeButton("Cancel", null))
    }
    private fun preview(value: JSONObject) {
        page("Preview", "preview", back = { builder(value) })
        body.addView(ui.note("This text will be sent to Hermes. Original recordings remain attached to your notes."))
        body.addView(ui.text(WorkspaceClient.preview(value).ifBlank { "Add an instruction or note first." }))
        body.addView(ui.button("Back to editing") { builder(value) })
    }
    private fun stashDraft() {
        draftFields?.invoke()
        if (draftFields != null) prompt?.let { value ->
            val content = editorContent(value)
            if (content != draftContent) runCatching { hub.saveLocalDraft(value); draftContent = content }
                .onFailure { showToast("Could not save local draft. Keep this screen open and retry.") }
        }
    }
    private fun editorContent(value: JSONObject) = listOf("title", "instruction", "blocks").joinToString("\n") { value.opt(it)?.toString().orEmpty() }
    private fun savePrompt(value: JSONObject, openChat: Boolean = false) {
        if (saving) return
        if (openChat && app.turns.busy) { showToast("Finish the current conversation before opening another chat"); return }
        stashDraft()
        if (openChat) try { PromptAttachment.validate(value) } catch (e: IllegalStateException) { showToast(safe(e)); return }
        saving = true
        enableInputs(body, false)
        task(onError = { e -> saving = false; enableInputs(body, true); showToast(safe(e)) }) {
            // Audio captured inside the builder must upload before any prompt is called synced.
            hub.sync()
            val hydrated = WorkspaceClient.hydrateMedia(value) { hub.store.item("notes", it) }
            val saved = hub.save(hydrated, wheelScrollTarget?.scrollY ?: 0); prompt = saved
            saving = false
            builder(saved)
            when {
                returnChat != null -> returnToChat(returnChat!!)
                openChat -> openChat(saved)
                else -> showToast("Synced · available on your other R1")
            }
        }
    }
    private fun releasePrompt() {
        leaseJob?.cancel(); leaseJob = null
        prompt?.let(hub::release); prompt = null
    }
    private fun savedPrompts() = hub.store.items("prompts").filterNot { it.getString("id").startsWith("shared-") }

    private fun promptDetail(promptId: String) {
        if (hub.blocked) { restricted(); return }
        releasePrompt(); viewedPromptId = promptId
        val saved = hub.store.item("prompts", promptId) ?: run { prompts(); return }
        page("Saved prompt", "prompt")
        body.addView(ui.title(saved.optString("title").ifBlank { "Untitled prompt" }, 20f))
        body.addView(ui.button("Edit prompt", primary = true) { openPrompt(promptId) })
        body.addView(ui.button("Run in new chat") { openChat(saved) })
        body.addView(ui.text(WorkspaceClient.preview(saved)))
    }

    private fun openChat(saved: JSONObject) {
        try {
            if (hub.store.item("drafts", saved.getString("id")) != null) {
                showToast("Save your prompt edits before opening a chat")
                openPrompt(saved.getString("id")); return
            }
            returnToChat(PromptAttachment.open(app, saved))
        } catch (e: Exception) { showToast(safe(e)) }
    }

    private fun returnToChat(conversation: String) {
        if (app.turns.busy) { showToast("Finish the current conversation before returning to this chat"); return }
        if (!app.history.select(conversation)) { showToast("This chat is no longer available"); return }
        PromptAttachment.source(app, conversation)?.let { app.history.rename(conversation, it.optString("title")) }
        draftFields = null
        releasePrompt()
        startActivity(Intent(this, dev.r1ptt.HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
    private fun field(label: String, value: String = "", multi: Boolean = false) = ui.field(label, value,
        InputType.TYPE_CLASS_TEXT or if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0).apply {
        if (multi) minLines = 3
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
    }.also(body::addView)
    private fun enableInputs(view: android.view.View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) enableInputs(view.getChildAt(index), enabled)
    }
    private fun task(onError: (Exception) -> Unit = { showToast(safe(it)) }, work: suspend () -> Unit) {
        scope.launch { try { work() } catch (_: CancellationException) { } catch (e: Exception) { onError(e) } }
    }
    private fun safe(e: Exception) = when (e) {
        is HubException -> e.message.orEmpty()
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "Could not complete this action. Your local work is preserved."
        else -> "The host could not be reached. Your local work is preserved."
    }
    companion object {
        private const val EXTRA_NOTES = "dev.r1ptt.workspace.NOTES"
        private const val EXTRA_NOTE_ID = "dev.r1ptt.workspace.NOTE_ID"
        private const val EXTRA_DEVICE_TOKEN = "dev.r1ptt.workspace.DEVICE_TOKEN"
        private const val EXTRA_SAVED_PROMPT_ID = "dev.r1ptt.workspace.SAVED_PROMPT_ID"
        private const val EXTRA_RUNS = "dev.r1ptt.workspace.RUNS"
        private const val EXTRA_PROMPTS = "dev.r1ptt.workspace.PROMPTS"
        private const val EXTRA_PROMPT_ID = "dev.r1ptt.workspace.PROMPT_ID"
        private const val EXTRA_RETURN_CHAT = "dev.r1ptt.workspace.RETURN_CHAT"
        fun promptIntent(context: android.content.Context, promptId: String, returnChat: String? = null) =
            Intent(context, WorkspaceActivity::class.java).putExtra(EXTRA_PROMPT_ID, promptId).putExtra(EXTRA_RETURN_CHAT, returnChat)
        fun promptsIntent(context: android.content.Context) = Intent(context, WorkspaceActivity::class.java).putExtra(EXTRA_PROMPTS, true)
        internal fun savedPromptIntent(context: android.content.Context, id: String) =
            Intent(context, WorkspaceActivity::class.java).putExtra(EXTRA_SAVED_PROMPT_ID, id)
        internal fun notesIntent(context: android.content.Context, noteId: String? = null) =
            Intent(context, WorkspaceActivity::class.java).putExtra(EXTRA_NOTES, true)
                .putExtra(EXTRA_NOTE_ID, noteId)
        internal fun authorizeNavigation(intent: Intent, token: String) { intent.putExtra(EXTRA_DEVICE_TOKEN, token) }
        var button: ((Boolean) -> Unit)? = null
        private var active: java.lang.ref.WeakReference<WorkspaceActivity>? = null
        fun pauseForRecovery() { active?.get()?.let {
            it.stashDraft(); it.recorder?.cancel(); it.recorder = null; it.recordingDestination = null
            it.stopPlayback(); it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } }
        fun quarantineActive() { active?.get()?.restricted() }
    }
}
