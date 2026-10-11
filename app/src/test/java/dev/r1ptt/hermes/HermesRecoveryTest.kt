package dev.r1ptt.hermes

import dev.r1ptt.data.Config
import dev.r1ptt.data.History
import dev.r1ptt.data.Msg
import dev.r1ptt.net.CallRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import java.io.File

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [34], application = android.app.Application::class)
class HermesRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recoveryAfterHistoryCommitDoesNotDuplicateOrRedirectTheAnswer() {
        val history = History(temporary.root)
        val original = history.convId
        history.saveDraft("Question")
        history.add(Msg.ASSISTANT, "Answer", deliveryKey = "operation")
        history.awaitWrites() // Simulate a crash after the transcript commit, before the receipt marker.
        store(original, "Answer")
        val runs = HermesRuns(temporary.root) { Config() }
        history.newConversation()
        history.saveDraft("Different question")
        runs.reconcile(history)
        await { runs.state.value.run!!.delivered }
        assertEquals("Different question", history.draft)
        assertTrue(history.messages.isEmpty())
        assertTrue(history.select(original))
        assertEquals(listOf("Answer"), history.messages.map { it.text })
        assertEquals("", history.draft)
        assertTrue(HermesRuns(temporary.root) { Config() }.state.value.run!!.delivered)
    }

    @Test fun emptyCompletedOutputClearsItsDraftWithoutCreatingAnEmptyMessage() {
        val history = History(temporary.root)
        history.saveDraft("Question")
        store(history.convId, "")
        val runs = HermesRuns(temporary.root) { Config() }
        runs.reconcile(history)
        await { runs.state.value.run!!.delivered }
        assertEquals("", history.draft)
        assertTrue(history.messages.isEmpty())
    }

    @Test fun admissionOnlyClearsMatchingOriginalDraftAndDeletedChatsRemainDeleted() {
        val history = History(temporary.root)
        val original = history.convId
        history.saveDraft("Edited question")
        store(original, "Answer")
        val runs = HermesRuns(temporary.root) { Config() }
        runs.confirmAdmittedDraft(history)
        assertEquals("Edited question", history.draft)
        history.saveDraft("Question")
        runs.confirmAdmittedDraft(history)
        assertEquals("", history.draft)
        history.remove(original)
        runs.reconcile(history)
        await { runs.state.value.message.contains("original chat was removed") }
        assertFalse(runs.state.value.run!!.delivered)
        assertFalse(history.flow.value.chats.any { it.id == original })
    }

    @Test fun executionSettlementAllowsRecoveryAlongsideTheLiveTurnWithoutDuplicateDelivery() {
        val history = History(temporary.root)
        MockWebServer().use { server ->
            server.start()
            val cfg = Config().let { it.copy(agent = it.agent.copy(baseUrl = server.url("/v1").toString())) }
            val runs = HermesRuns(temporary.root) { cfg }
            server.enqueue(MockResponse().setBody(HermesClientTest.CAPABILITIES))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"run_id\":\"run_live\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"event\":\"run.completed\",\"run_id\":\"run_live\",\"seq\":1,\"output\":\"Answer\"}\n\n"))
            val answer = runs.execute(cfg, "Question", "r1ptt-${history.convId}", CallRegistry(), {}, {
                if (runs.state.value.run?.status == "completed") {
                    assertTrue(runs.state.value.executing)
                    runs.reconcile(history) // Home can observe the final event before execute returns.
                    assertFalse(runs.state.value.run!!.delivered)
                }
            })
            assertFalse(runs.state.value.executing)
            val operation = runs.state.value.run!!.operationId
            runs.reconcile(history) // Recovery retries on the execution-settled emission.
            history.add(Msg.ASSISTANT, answer!!, deliveryKey = operation) // The live turn is still finishing.
            history.awaitWrites()
            await { runs.state.value.run!!.delivered }
            assertEquals(listOf("Answer"), history.messages.map { it.text })
        }
    }

    private fun store(conversation: String, output: String) {
        HermesRunLedger(File(temporary.root, "hermes-run.json")).write(HermesRun(
            "operation", "connection", "r1ptt-$conversation", "{\"input\":\"Question\"}", 42,
            true, 86400, true, true, runId = "run_1", status = "completed", output = output))
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("Recovery did not finish", condition())
    }
}
