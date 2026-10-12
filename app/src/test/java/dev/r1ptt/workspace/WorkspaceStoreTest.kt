package dev.r1ptt.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun command(id: String = "c1", target: String = "device-a", expiry: Long = 2000) = JSONObject()
        .put("id", id).put("device_id", target).put("type", "clear_workspace").put("expires_at", expiry)
    @Test fun offlineCaptureSurvivesRestartAndRemoteReconciliation() {
        val root = temporary.newFolder(); val store = WorkspaceStore(root)
        val local = store.capture("Offline thought", "Keep this on device")
        val remote = JSONObject().put("id", "remote-note").put("text", "From the second R1")
        store.reconcile("notes", listOf(remote))
        val restored = WorkspaceStore(root)
        assertEquals(2, restored.items("notes").size)
        assertTrue(restored.item("notes", local.getString("id"))!!.getBoolean("pending"))
        assertEquals("Keep this on device", restored.item("notes", local.getString("id"))!!.getString("text"))
    }
    @Test fun recordingHasDurablePrivateCopyBeforeNoteIsPublished() {
        val root = temporary.newFolder(); val original = temporary.newFile().apply { writeBytes(byteArrayOf(1,2,3,4)) }
        val store = WorkspaceStore(root); val note = store.capture("Voice", "", original)
        original.delete()
        val restored = WorkspaceStore(root); val saved = restored.item("notes", note.getString("id"))!!
        assertArrayEquals(byteArrayOf(1,2,3,4), File(restored.mediaDir, saved.getString("local_media")).readBytes())
        assertTrue(saved.getBoolean("pending"))
        assertFalse(saved.has("media_id"))
    }
    @Test fun cleanRemoteDeletionDoesNotDeletePendingNotesOrPromptDrafts() {
        val store = WorkspaceStore(temporary.newFolder())
        val local = store.capture("Local", "Pending")
        store.put("notes", JSONObject().put("id", "deleted-elsewhere"))
        val editor = JSONObject().put("id", "prompt").put("revision", 7).put("lease_token", "fenced").put("instruction", "Unsynced edit")
        store.saveDraft(editor)
        store.reconcile("notes", emptyList()); store.reconcile("prompts", emptyList())
        assertEquals(listOf(local.getString("id")), store.items("notes").map { it.getString("id") })
        assertEquals(7, store.item("drafts", "prompt")!!.getInt("revision"))
        assertFalse(store.item("drafts", "prompt")!!.has("lease_token"))
        assertEquals("fenced", editor.getString("lease_token")) // The active editor still fences its writes.
    }
    @Test fun obsoleteCacheFieldsAreCompactedWithoutLosingDraftsOrUncertainReceipts() {
        val root = temporary.newFolder()
        val original = JSONObject().put("cache_clock", 12).put("cache_versions", JSONObject().put("notes/note", 12))
            .put("notes", JSONArray().put(JSONObject().put("id", "note").put("pending", true)
                .put("local_media", "note.m4a").put("capture_hash", "obsolete")
                .put("creation_payload", JSONObject().put("id", "note").put("text", "Original capture"))))
            .put("drafts", JSONArray().put(JSONObject().put("id", "prompt").put("revision", 7)
                .put("instruction", "Unsynced edit").put("lease_token", "expired").put("lease_expires_at", 10)))
            .put("receipts", JSONArray().put(JSONObject().put("id", "c1").put("device_id", "device-a")
                .put("type", "clear_workspace").put("status", "executing").put("ack_pending", true)))
            .put("requests", JSONArray().put(JSONObject().put("id", "pending").put("request_id", "same-on-retry")
                .put("revision", 7).put("run_again", false)))
        File(root, "workspace.json").writeText(original.toString())
        val media = File(root, "media").apply { mkdirs() }
        File(media, "note.m4a").writeText("Original recording")

        val store = WorkspaceStore(root)
        assertNull(store.loadError)
        assertEquals(12L, store.version())
        assertEquals(12, store.snapshot().getJSONObject("cache_versions").getInt("notes/note"))
        assertFalse(store.item("notes", "note")!!.has("capture_hash"))
        assertEquals("Original capture", store.item("notes", "note")!!.getJSONObject("creation_payload").getString("text"))
        assertEquals("Original recording", File(media, store.item("notes", "note")!!.getString("local_media")).readText())
        assertEquals("Unsynced edit", store.item("drafts", "prompt")!!.getString("instruction"))
        assertEquals(7, store.item("drafts", "prompt")!!.getInt("revision"))
        assertFalse(store.item("drafts", "prompt")!!.has("lease_token"))
        assertFalse(store.item("drafts", "prompt")!!.has("lease_expires_at"))
        assertFalse(store.item("receipts", "c1")!!.has("type"))
        assertEquals("same-on-retry", store.item("requests", "pending")!!.getString("request_id"))
        assertFalse(store.item("requests", "pending")!!.getBoolean("run_again"))
        store.recoverCommands()
        assertEquals("unknown", store.item("receipts", "c1")!!.getString("status"))
        assertTrue(store.item("receipts", "c1")!!.getBoolean("ack_pending"))
        assertFalse(store.beginCommand(command(), "device-a", 1000))
        val disk = JSONObject(File(root, "workspace.json").readText())
        assertFalse(disk.getJSONArray("drafts").getJSONObject(0).has("lease_token"))
        assertEquals("same-on-retry", disk.getJSONArray("requests").getJSONObject(0).getString("request_id"))
    }
    @Test fun replayedOrUncertainCommandsNeverExecuteAgain() {
        val root = temporary.newFolder(); val store = WorkspaceStore(root)
        assertTrue(store.beginCommand(command(), "device-a", 1000))
        store.commandState("c1", "executing", "Started")
        val restored = WorkspaceStore(root); restored.recoverCommands()
        assertEquals("unknown", restored.item("receipts", "c1")!!.getString("status"))
        assertFalse(restored.beginCommand(command(), "device-a", 1001))
        assertTrue(restored.item("receipts", "c1")!!.getBoolean("ack_pending"))
    }
    @Test fun oldSubmissionSnapshotCompactsMetadataButPreservesExactInputAndReply() {
        val root = temporary.newFolder()
        val blocks = JSONArray().put(JSONObject().put("id", "recording-block").put("note_id", "source-note")
            .put("kind", "audio").put("media_id", "recording").put("text", "Original transcript"))
        val snapshot = JSONObject().put("title", "Original prompt").put("instruction", "Original instruction")
            .put("blocks", blocks).put("preview", "Original instruction\n\nOriginal transcript")
            .put("id", "prompt").put("revision", 7).put("created_at", 1).put("updated_at", 2)
            .put("last_run_id", "prior-run").put("last_run_revision", 6).put("changed_since_run", true).put("state", "ran")
        val run = JSONObject().put("id", "run").put("prompt_id", "prompt").put("prompt_revision", 7)
            .put("status", "completed").put("output", "Confirmed reply").put("created_at", 3).put("updated_at", 4)
            .put("hermes", JSONObject().put("run_id", "hermes-run").put("session_id", "hermes-session")).put("snapshot", snapshot)
        val current = JSONObject().put("id", "prompt").put("revision", 8).put("instruction", "Later edit")
            .put("last_run_id", "run").put("last_run_revision", 7).put("changed_since_run", true).put("state", "ran")
        File(root, "workspace.json").writeText(JSONObject().put("runs", JSONArray().put(run))
            .put("prompts", JSONArray().put(current)).toString())

        val store = WorkspaceStore(root)
        assertNull(store.loadError)
        val restored = store.item("runs", "run")!!
        val compacted = restored.getJSONObject("snapshot")
        assertEquals(setOf("title", "instruction", "blocks", "preview"), compacted.keys().asSequence().toSet())
        assertEquals("Original prompt", compacted.getString("title"))
        assertEquals("Original instruction", compacted.getString("instruction"))
        assertEquals(blocks.toString(), compacted.getJSONArray("blocks").toString())
        assertEquals("Original instruction\n\nOriginal transcript", compacted.getString("preview"))
        assertEquals("prompt", restored.getString("prompt_id"))
        assertEquals(7, restored.getInt("prompt_revision"))
        assertEquals(3L, restored.getLong("created_at"))
        assertEquals(4L, restored.getLong("updated_at"))
        assertEquals("completed", restored.getString("status"))
        assertEquals("Confirmed reply", restored.getString("output"))
        assertEquals("hermes-session", restored.getJSONObject("hermes").getString("session_id"))
        assertEquals(current.toString(), store.item("prompts", "prompt")!!.toString())
        val restarted = WorkspaceStore(root)
        assertEquals(restored.toString(), restarted.item("runs", "run")!!.toString())
    }
    @Test fun receiptCannotBeExecutedForAnotherDeviceOrAfterExpiry() {
        val store = WorkspaceStore(temporary.newFolder())
        assertFalse(store.beginCommand(command(target = "device-b"), "device-a", 1000))
        assertEquals("failed", store.item("receipts", "c1")!!.getString("status"))
        assertFalse(store.beginCommand(command("expired", expiry = 1000), "device-a", 1000))
    }
    @Test fun clearingSharedDataPreservesRecoveryReceiptsAndDeletesAudio() {
        val root = temporary.newFolder(); val store = WorkspaceStore(root)
        val note = store.capture("Voice", "", temporary.newFile().apply { writeText("audio") })
        store.beginCommand(command(), "device-a", 1000)
        store.saveDraft(JSONObject().put("id", "draft"))
        store.clearShared()
        assertTrue(store.items("notes").isEmpty()); assertTrue(store.items("drafts").isEmpty())
        assertNotNull(store.item("receipts", "c1"))
        assertFalse(File(store.mediaDir, note.getString("local_media")).exists())
    }
    @Test fun unreadableStoreIsPreservedAndCannotBeOverwritten() {
        val root = temporary.newFolder(); val file = File(root, "workspace.json").apply { writeText("corrupt data to preserve") }
        val store = WorkspaceStore(root)
        assertNotNull(store.loadError)
        assertThrows(IllegalStateException::class.java) { store.capture("New", "Must not overwrite") }
        assertEquals("corrupt data to preserve", file.readText())
    }
    @Test fun previewUsesSnapshotOrderingAndRemovingBlockLeavesSourceNote() {
        val store = WorkspaceStore(temporary.newFolder()); val source = store.capture("Original", "Source text")
        val first = WorkspaceClient.block(source).put("text", "Edited snapshot")
        val prompt = JSONObject().put("instruction", "Summarize these").put("blocks", JSONArray().put(first).put(JSONObject().put("text", "Second")))
        assertEquals("Summarize these\n\nEdited snapshot\n\nSecond", WorkspaceClient.preview(prompt))
        prompt.put("blocks", JSONArray())
        assertEquals("Source text", store.item("notes", source.getString("id"))!!.getString("text"))
    }
    @Test fun lateListSnapshotCannotDeleteOrRegressConcurrentRun() {
        val store = WorkspaceStore(temporary.newFolder())
        val before = store.version()
        val complete = JSONObject().put("id", "run-new").put("status", "completed").put("updated_at", 20).put("output", "Authoritative result")
        store.put("runs", complete)
        store.reconcile("runs", emptyList(), before)
        assertEquals("completed", store.item("runs", "run-new")!!.getString("status"))
        store.put("runs", JSONObject().put("id", "run-new").put("status", "running").put("updated_at", 10))
        assertEquals("Authoritative result", store.item("runs", "run-new")!!.getString("output"))
        store.reconcile("runs", listOf(JSONObject().put("id", "run-new").put("status", "running").put("updated_at", 10)))
        assertEquals("completed", store.item("runs", "run-new")!!.getString("status"))
    }
    @Test fun lateSnapshotCannotResurrectLocallyDeletedItem() {
        val store = WorkspaceStore(temporary.newFolder())
        val note = JSONObject().put("id", "removed").put("revision", 2)
        store.put("notes", note); val before = store.version(); store.remove("notes", "removed")
        store.reconcile("notes", listOf(note), before)
        assertNull(store.item("notes", "removed"))
    }
    @Test fun clearingFreezesLateNetworkAndDraftWritesUntilNewEnrollment() {
        val store = WorkspaceStore(temporary.newFolder()); store.clearShared()
        assertThrows(IllegalStateException::class.java) { store.put("runs", JSONObject().put("id", "late-run").put("output", "Must not reappear")) }
        assertThrows(IllegalStateException::class.java) { store.saveDraft(JSONObject().put("id", "late-draft")) }
        assertThrows(IllegalStateException::class.java) { store.capture("Late audio", "") }
        store.resumeAfterEnrollment(); store.capture("New enrollment", "Allowed")
        assertEquals(1, store.items("notes").size)
    }
    @Test fun recoveredDraftHydratesOnlyAcknowledgedAudioAndPreservesSource() {
        val store = WorkspaceStore(temporary.newFolder())
        val note = store.capture("Voice", "Transcript", temporary.newFile().apply { writeText("Original recording") })
        val draft = JSONObject().put("id", "draft").put("blocks", JSONArray().put(WorkspaceClient.block(note)))
        assertThrows(IllegalStateException::class.java) { WorkspaceClient.hydrateMedia(draft) { store.item("notes", it) } }
        store.put("notes", note.copyJson().put("media_id", "uploaded-media-1").put("pending", false))
        val recovered = WorkspaceClient.hydrateMedia(draft) { store.item("notes", it) }
        assertEquals("uploaded-media-1", recovered.getJSONArray("blocks").getJSONObject(0).getString("media_id"))
        assertTrue(draft.getJSONArray("blocks").getJSONObject(0).isNull("media_id"))
        assertEquals("Original recording", File(store.mediaDir, note.getString("local_media")).readText())
    }
    @Test fun workspaceCredentialsRequireHttpsAndNeverAcceptUserInfoOrQuery() {
        assertEquals("https://host.example", HubHttp.normalize("https://host.example/"))
        assertEquals("http://127.0.0.1:8787", HubHttp.normalize("http://127.0.0.1:8787"))
        listOf("http://192.168.1.2", "https://user:secret@host.example", "https://host.example/?token=secret", "https://host.example/api").forEach {
            assertThrows(IllegalArgumentException::class.java) { HubHttp.normalize(it) }
        }
    }
}
