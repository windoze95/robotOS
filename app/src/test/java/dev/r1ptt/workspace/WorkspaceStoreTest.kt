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
        store.saveDraft(JSONObject().put("id", "prompt").put("revision", 7).put("lease_token", "fenced").put("instruction", "Unsynced edit"))
        store.reconcile("notes", emptyList()); store.reconcile("prompts", emptyList())
        assertEquals(listOf(local.getString("id")), store.items("notes").map { it.getString("id") })
        assertEquals(7, store.item("drafts", "prompt")!!.getInt("revision"))
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
