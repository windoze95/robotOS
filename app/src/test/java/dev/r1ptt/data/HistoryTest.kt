package dev.r1ptt.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HistoryTest {
    private fun directory() = File(RuntimeEnvironment.getApplication().cacheDir, "history-${java.util.UUID.randomUUID()}").apply { mkdirs() }

    @Test fun migratesLegacyIdentityAndKeepsSourceWithoutRepeatingMigration() {
        val dir = directory()
        val legacy = File(dir, "history.json")
        val source = """{"convId":"old-session","messages":[{"role":"user","text":"Earlier question","at":1}]}"""
        legacy.writeText(source)
        History(dir).useHistory { history ->
            assertEquals("old-session", history.convId)
            assertEquals("Earlier question", history.messages.single().text)
            assertEquals(source, legacy.readText())
            history.saveDraft("Unsent words")
        }
        History(dir).useHistory {
            assertEquals("old-session", it.convId)
            assertEquals("Unsent words", it.draft)
            assertEquals(1, it.messages.size)
        }
    }

    @Test fun backgroundRepliesDraftsAndReadingPositionsStayWithTheirConversation() {
        val dir = directory()
        var first = ""
        var second = ""
        History(dir).useHistory { history ->
            first = history.convId
            history.add(Msg.USER, "First question")
            history.saveDraft("First draft")
            history.savePosition(123, false)
            second = history.newConversation()
            history.saveDraft("Second draft")
            history.add(Msg.ASSISTANT, "Late first reply", first, "run-1")
            history.add(Msg.ASSISTANT, "Late first reply", first, "run-1")
            history.confirmDraft("First draft", first)
            assertEquals("Second draft", history.draft)
            assertTrue(history.messages.isEmpty())
            history.select(first)
            assertEquals("", history.draft)
            assertEquals(2, history.messages.size)
            assertEquals(123, history.flow.value.scrollY)
            assertFalse(history.flow.value.followLatest)
        }
        History(dir).useHistory { history ->
            assertEquals(first, history.convId)
            history.select(second)
            assertEquals("Second draft", history.draft)
        }
    }

    @Test fun acceptingAnEarlierDraftDoesNotEraseNewEdits() {
        History(directory()).useHistory {
            it.saveDraft("Original")
            it.saveDraft("Edited while submitting")
            it.confirmDraft("Original")
            assertEquals("Edited while submitting", it.draft)
        }
    }

    @Test fun editsDuringAnInFlightWriteAreSavedWithoutAnotherEditOrFlush() {
        val firstWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val latestWrite = CountDownLatch(1)
        History(directory()).useHistory { history ->
            // Hold the real INSERT after its snapshot has been captured, as slow disk I/O can.
            val db = History::class.java.getDeclaredField("database").apply { isAccessible = true }
                .get(history) as SQLiteDatabase
            db.setCustomScalarFunction("pause_draft_write") { draft ->
                if (draft == "First draft") {
                    firstWrite.countDown()
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                } else if (draft == "Latest draft") latestWrite.countDown()
                draft
            }
            db.execSQL("CREATE TEMP TRIGGER pause_draft_write BEFORE INSERT ON conversations BEGIN SELECT pause_draft_write(NEW.draft); END")
            try {
                history.saveDraft("First draft")
                assertTrue("Initial autosave did not start", firstWrite.await(5, TimeUnit.SECONDS))
                history.saveDraft("Latest draft")
                history.savePosition(321, false)
                releaseWrite.countDown()
                assertTrue("An edit during the write was never autosaved", latestWrite.await(5, TimeUnit.SECONDS))

                // Acquiring the writer transaction waits for the follow-up INSERT to commit.
                // Do not use awaitWrites/close here: both explicitly persist the current state.
                db.beginTransaction()
                try {
                    db.rawQuery("SELECT draft,scroll_y,follow_latest FROM conversations WHERE id=?", arrayOf(history.convId)).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("Latest draft", it.getString(0))
                        assertEquals(321, it.getInt(1))
                        assertEquals(0, it.getInt(2))
                    }
                } finally { db.endTransaction() }
            } finally { releaseWrite.countDown() }
        }
    }

    @Test fun removingLastChatCreatesAnEmptyOneAndCannotResurrectDelayedDraftWrites() {
        val dir = directory()
        var removed = ""
        History(dir).useHistory {
            removed = it.convId
            it.saveDraft("Pending save")
            it.remove(removed)
        }
        History(dir).useHistory {
            assertNotEquals(removed, it.convId)
            assertEquals(1, it.flow.value.chats.size)
            assertEquals("", it.draft)
        }
    }

    @Test fun unreadableMigrationDoesNotReplaceSourceOrEnableWrites() {
        val dir = directory()
        File(dir, "history.json").writeText("broken")
        History(dir).useHistory {
            assertNotNull(it.errors.value)
            it.saveDraft("Do not overwrite evidence")
        }
        assertEquals("broken", File(dir, "history.json").readText())
    }

    private fun History.useHistory(block: (History) -> Unit) { try { block(this) } finally { close() } }
}
