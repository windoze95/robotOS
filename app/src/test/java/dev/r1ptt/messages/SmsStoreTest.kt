package dev.r1ptt.messages

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmsStoreTest {
    private lateinit var store: SmsStore
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("messages.db"); store = SmsStore(RuntimeEnvironment.getApplication()) }
    @After fun cleanup() { store.close() }
    private fun record() = SmsRecord.outgoing("+15551234567", "Synthetic test", 1, 2, 1000)

    @Test fun committedAttemptSurvivesReopeningAndCannotBeReservedTwice() {
        val record = record()
        val draft = SmsDraft(record.peer, record.body)
        store.saveDraft(draft); store.outgoing(record, draft); store.close()
        store = SmsStore(RuntimeEnvironment.getApplication())
        assertEquals(record, store.conversation(record.peer).single())
        assertEquals(SmsDraft(), store.draft())
        store.saveDraft(SmsDraft("12345", "New draft"))
        assertThrows(IllegalStateException::class.java) { store.outgoing(record, store.draft()) }
        assertEquals(SmsDraft("12345", "New draft"), store.draft())
        assertEquals(1, store.conversation(record.peer).size)
    }
    @Test fun callbackUpdatesAreDurableAndForgedCallbacksDoNothing() {
        val record = record(); store.outgoing(record, SmsDraft())
        val correct = SmsCallback(record.id, record.token, 0, false)
        assertFalse(store.update(correct.copy(token = "wrong")) { it.sent(0, true, -1) })
        assertTrue(store.update(correct) { it.sent(0, true, -1) })
        assertFalse(store.update(correct) { it.sent(0, false, 4) })
        store.close(); store = SmsStore(RuntimeEnvironment.getApplication())
        assertEquals(SentPart.SENT, store.conversation(record.peer).single().parts[0].sent)
        assertEquals(SmsStatus.UNKNOWN, store.conversation(record.peer).single().status(900_000))
    }
    @Test fun anEditedDraftIsNotClearedByAnOlderReview() {
        val record = record()
        val current = SmsDraft(record.peer, "Edited after review")
        store.saveDraft(current); store.outgoing(record, SmsDraft(record.peer, record.body))
        assertEquals(current, store.draft())
    }
    @Test fun incomingReplaysDeduplicateAndUnreadIsLocal() {
        val incoming = SmsRecord("pdu-hash", "12345", "Synthetic incoming", 2000, 1, true)
        assertTrue(store.insert(incoming)); assertFalse(store.insert(incoming))
        assertEquals(1, store.conversation("12345").size)
        assertTrue(store.threads().single().unread)
        store.markRead("12345")
        assertFalse(store.threads().single().unread)
    }
    @Test fun recipientsAreExplicitAndDuplicateNamesRemainDistinct() {
        assertTrue(store.recipients().isEmpty())
        store.addRecipient("Yana", "+15551234567"); store.addRecipient("Yana", "+15557654321")
        assertTrue(SmsRecipientResolver.resolve("Yana", store.recipients()) is SmsResolution.Choose)
        store.removeRecipient(store.recipients().last().id)
        assertEquals(SmsResolution.Number("+15551234567"), SmsRecipientResolver.resolve("Yana", store.recipients()))
    }
    @Test fun versionThreeUpgradeDropsOnlyTheRelayTablesAndKeepsOldRecords() {
        store.close(); val context = RuntimeEnvironment.getApplication(); context.deleteDatabase("messages.db")
        SmsStore(context).writableDatabase.apply {
            execSQL("CREATE TABLE bridge_jobs(id TEXT PRIMARY KEY)")
            execSQL("CREATE TABLE bridge_peers(number TEXT PRIMARY KEY)")
            version = 3
            close()
        }
        val old = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("messages.db").path, null, 0)
        val failed = record().sent(0, false, 32).sent(1, false, 32)
        // Records written before later fields existed still read back intact.
        val legacy = org.json.JSONObject(failed.encode()).apply { remove("systemOwned"); remove("sendEvidence") }
        val parts = legacy.getJSONArray("parts")
        for (i in 0 until parts.length()) { parts.getJSONObject(i).remove("radioError"); parts.getJSONObject(i).remove("failureSource") }
        old.execSQL("INSERT INTO messages VALUES (?,?,?,?,?)", arrayOf<Any>(failed.id, failed.peer, failed.createdAt, 0, legacy.toString()))
        old.execSQL("INSERT INTO recipients (name, number) VALUES ('Yana', '+15551234567')")
        old.close()
        store = SmsStore(context)
        val restored = store.conversation(failed.peer).single()
        assertEquals(32, restored.parts[0].error); assertNull(restored.parts[0].radioError)
        assertEquals(SmsStatus.FAILED, restored.status(2000)); assertEquals(failed.token, restored.token)
        assertFalse(restored.systemOwned); assertTrue(store.pendingSystemCopies().isEmpty())
        assertEquals("Yana", store.recipients().single().name)
        store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'bridge_%'", null).use { assertEquals(0, it.count) }
        assertEquals(4, store.readableDatabase.version)
    }

    @Test fun mmsNoticeIsDurableBoundedAndDeduplicatedAndCallRepliesDoNotOverwriteDrafts() {
        val draft = SmsDraft("12345", "Existing unfinished draft"); store.saveDraft(draft)
        assertTrue(store.saveMmsNotice("hash", 1, byteArrayOf(1), byteArrayOf(2, 3)))
        assertFalse(store.saveMmsNotice("hash", 1, byteArrayOf(1), byteArrayOf(2, 3)))
        assertThrows(IllegalArgumentException::class.java) { store.saveMmsNotice("large", 1, null, ByteArray(262145)) }
        store.addReplyRequest("request", SmsDraft("67890", "Call reply")); store.close()
        store = SmsStore(RuntimeEnvironment.getApplication())
        assertEquals(1, store.mmsNoticeCount()); assertEquals(draft, store.draft())
        assertEquals("Call reply", store.replyRequests().single().second.body)
        store.removeReplyRequest("request"); assertTrue(store.replyRequests().isEmpty())
    }
}
