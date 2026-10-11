package dev.r1ptt.hermes

import dev.r1ptt.data.Agent
import dev.r1ptt.data.Config
import dev.r1ptt.net.CallRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HermesRunsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun cfg(server: MockWebServer) = Config(agent = Agent(baseUrl = server.url("/v1").toString(), apiKey = "secret", provider = "chosen"))

    @Test fun ledgerSurvivesRestartWithoutStoringCredentialsAndMalformedStorageFailsClosed() {
        val file = temporary.newFile()
        val ledger = HermesRunLedger(file)
        val run = HermesRun("op", "hash", "r1ptt-session", "{\"input\":\"hello\"}", 42, true, 86400, true, true,
            runId = "run_1", status = "waiting_for_approval", approval = HermesApproval("approval", "Do this", "command", setOf("once", "deny")))
        ledger.write(run)
        assertEquals(run, HermesRunLedger(file).read())
        assertFalse(file.readText().contains("apiKey"))
        file.writeText("{")
        assertTrue(runCatching { ledger.read() }.isFailure)
    }

    @Test fun admissionPersistsIdentityAndReplayCannotDuplicateText() {
        MockWebServer().use { server ->
            server.start(); val cfg = cfg(server)
            val runs = HermesRuns(temporary.root) { cfg }
            server.enqueue(MockResponse().setBody(HermesClientTest.CAPABILITIES))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"run_id\":\"run_1\",\"status\":\"started\"}"))
            server.enqueue(events(
                """{"event":"message.delta","run_id":"run_1","seq":0,"delta":"Hello"}""",
                """{"event":"message.delta","run_id":"run_1","seq":0,"delta":"duplicate"}""",
                """{"event":"run.completed","run_id":"run_1","seq":1,"output":"Hello"}"""))
            val deltas = mutableListOf<String>(); var admitted = false
            val answer = runs.execute(cfg, "Question", "r1ptt-session", CallRegistry(), deltas::add, {}, {
                admitted = true
                assertEquals("run_1", HermesRunLedger(File(temporary.root, "hermes-run.json")).read()!!.runId)
            })
            assertEquals("Hello", answer); assertTrue(admitted); assertEquals(listOf("Hello"), deltas)
            server.takeRequest()
            val post = server.takeRequest(); val payload = JSONObject(post.body.readUtf8())
            assertEquals("chosen", payload.getString("provider")); assertEquals("r1ptt-session", payload.getString("session_id"))
            assertEquals(runs.state.value.run!!.operationId, post.getHeader("Idempotency-Key"))
            assertEquals("completed", HermesRuns(temporary.root) { cfg }.state.value.run!!.status)
        }
    }

    @Test fun expiredStreamRecoversAuthoritativeFinalOutputWithoutAnotherSubmission() {
        MockWebServer().use { server ->
            server.start(); val cfg = cfg(server); val runs = HermesRuns(temporary.root) { cfg }
            server.enqueue(MockResponse().setBody(HermesClientTest.CAPABILITIES))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"run_id\":\"run_1\"}"))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("{\"run_id\":\"run_1\",\"status\":\"completed\",\"output\":\"Recovered\"}"))
            assertEquals("Recovered", runs.execute(cfg, "Question", "session", CallRegistry(), {}, {}))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun ambiguousAdmissionRetainsExactRequestAndDoesNotAutomaticallyResubmit() {
        MockWebServer().use { server ->
            server.start(); val cfg = cfg(server); val runs = HermesRuns(temporary.root) { cfg }
            server.enqueue(MockResponse().setBody(HermesClientTest.CAPABILITIES))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertTrue(runCatching { runs.execute(cfg, "Question", "session", CallRegistry(), {}, {}) }.isFailure)
            val stored = HermesRunLedger(File(temporary.root, "hermes-run.json")).read()!!
            assertEquals("submission_unknown", stored.status); assertEquals("Question", JSONObject(stored.payload).getString("input"))
            assertEquals(2, server.requestCount)
            assertTrue(runCatching { runs.execute(cfg, "Another", "session", CallRegistry(), {}, {}) }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun processDeathDuringAdmissionBecomesExplicitlyRecoverable() {
        val run = stored()
        val recovered = HermesRuns(temporary.root) { Config() }
        assertEquals("submission_unknown", recovered.state.value.run!!.status)
        assertEquals(run.operationId, recovered.state.value.run!!.operationId)
    }

    @Test fun waitingForApprovalReleasesTheStreamAndDoesNotPoll() {
        MockWebServer().use { server ->
            server.start(); val cfg = cfg(server); val runs = HermesRuns(temporary.root) { cfg }
            server.enqueue(MockResponse().setBody(HermesClientTest.CAPABILITIES))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"run_id\":\"run_1\"}"))
            server.enqueue(events("""{"event":"approval.request","run_id":"run_1","seq":1,"request_id":"approval-1","description":"Do this","choices":["once","deny"]}"""))
            val error = runCatching { runs.execute(cfg, "Question", "session", CallRegistry(), {}, {}) }.exceptionOrNull()
            assertTrue(error is HermesTaskPaused)
            assertEquals("waiting_for_approval", runs.state.value.run!!.status)
            runs.setVisible(this, true)
            Thread.sleep(100)
            assertEquals(3, server.requestCount)
            runs.setVisible(this, false)
        }
    }

    @Test fun lateDeltasCannotUndoStopAndUnsequencedDeltasAreNotReplayed() {
        stored(status = "stopping", id = "run_1")
        val runs = HermesRuns(temporary.root) { Config() }
        val run = runs.state.value.run!!
        runs.acceptEvent(run.operationId, HermesEvent("message.delta", "run_1", 4,
            JSONObject().put("delta", "late")))
        assertEquals("stopping", runs.state.value.run!!.status)
        runs.acceptEvent(run.operationId, HermesEvent("message.delta", "run_1", null,
            JSONObject().put("delta", "cannot safely replay")))
        assertEquals("late", runs.state.value.run!!.output)
        assertTrue(runs.state.value.run!!.transcriptIncomplete)
    }

    @Test fun refreshAndDecisionSerializeAndDoNotApproveTheReplacementRequest() {
        MockWebServer().use { server ->
            server.start(); val cfg = cfg(server)
            stored(status = "waiting_for_approval", id = "run_1", connection = HermesClient.connectionId(cfg.agent),
                approval = HermesApproval("old", "Old action", "", setOf("once", "deny")))
            val runs = HermesRuns(temporary.root) { cfg }
            server.enqueue(MockResponse().setBody("""{"run_id":"run_1","status":"waiting_for_approval","approval":{"request_id":"new","choices":["once","deny"]}}"""))
            runs.refresh(); runs.resolveApproval("run_1", "old", true)
            val until = System.nanoTime() + 2_000_000_000
            while (runs.state.value.working && System.nanoTime() < until) Thread.sleep(5)
            assertFalse(runs.state.value.working)
            assertEquals("new", runs.state.value.run!!.approval!!.requestId)
            assertEquals(1, server.requestCount)
            assertTrue(runs.state.value.message.contains("no longer current"))
        }
    }

    @Test fun truncatedReplayWithoutFinalOutputReconcilesThroughStatus() {
        MockWebServer().use { server ->
            server.start(); val cfg = cfg(server); val runs = HermesRuns(temporary.root) { cfg }
            server.enqueue(MockResponse().setBody(HermesClientTest.CAPABILITIES))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"run_id\":\"run_1\"}"))
            server.enqueue(events("""{"event":"replay.truncated","run_id":"run_1"}""",
                """{"event":"message.delta","run_id":"run_1","seq":44,"delta":"tail"}""",
                """{"event":"run.completed","run_id":"run_1","seq":45}"""))
            server.enqueue(MockResponse().setBody("""{"run_id":"run_1","status":"completed","output":"Whole answer"}"""))
            val deltas = mutableListOf<String>()
            assertEquals("Whole answer", runs.execute(cfg, "Question", "session", CallRegistry(), deltas::add, {}))
            assertTrue(deltas.isEmpty()); assertEquals(4, server.requestCount)
        }
    }

    @Test fun explicitArchivePreservesUnknownReceiptAndOnlyThenAllowsANewRequest() {
        MockWebServer().use { server ->
            server.start(); val cfg = cfg(server)
            val original = stored(status = "submission_unknown", connection = HermesClient.connectionId(cfg.agent))
            val runs = HermesRuns(temporary.root) { cfg }
            server.enqueue(MockResponse().setBody(HermesClientTest.CAPABILITIES))
            assertTrue(runCatching { runs.execute(cfg, "New question", "new-session", CallRegistry(), {}, {}) }.isFailure)
            assertEquals(original, runs.state.value.run)
            assertEquals(1, server.requestCount)

            assertTrue(runs.canArchive)
            runs.archive()
            awaitControls(runs)
            assertNull(runs.state.value.run)
            assertNull(HermesRuns(temporary.root) { cfg }.state.value.run)
            assertEquals(1, server.requestCount) // Archiving is strictly local.
            val receipt = JSONObject(File(temporary.root, "hermes-run-archive.json").readText()).getJSONArray("receipts").getJSONObject(0)
            assertEquals(original.operationId, receipt.getString("operationId"))
            assertEquals(original.connectionId, receipt.getString("connectionId"))
            assertEquals(original.payload, receipt.getString("payload"))
            assertEquals(original.sessionId, receipt.getString("sessionId"))
            assertFalse(receipt.toString().contains(cfg.agent.apiKey))

            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"run_id\":\"run_new\"}"))
            server.enqueue(events("""{"event":"run.completed","run_id":"run_new","seq":1,"output":"New answer"}"""))
            assertEquals("New answer", runs.execute(cfg, "New question", "new-session", CallRegistry(), {}, {}))
            assertNotEquals(original.operationId, runs.state.value.run!!.operationId)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun archiveKeepsTheLatestTwentyOriginalReceipts() {
        val file = File(temporary.root, "hermes-run.json")
        val ledger = HermesRunLedger(file)
        repeat(21) { n ->
            val run = HermesRun("op-$n", "connection-$n", "session-$n", "{}", n.toLong(), false, 0, false, false,
                runId = "run-$n", status = "waiting_for_approval", approval = HermesApproval("approval-$n", "Review", "", setOf("deny")))
            ledger.write(run)
            ledger.archive(run)
            assertNull(HermesRunLedger(file).read())
        }
        val receipts = JSONObject(File(temporary.root, "hermes-run-archive.json").readText()).getJSONArray("receipts")
        assertEquals(20, receipts.length())
        assertEquals("op-1", receipts.getJSONObject(0).getString("operationId"))
        val last = receipts.getJSONObject(19)
        assertEquals("run-20", last.getString("runId"))
        assertEquals("connection-20", last.getString("connectionId"))
        assertEquals("approval-20", last.getJSONObject("approval").getString("request_id"))
    }

    @Test fun replacingAnActiveTaskCannotOverwriteAFinalAnswerRecoveredDuringAdmission() {
        MockWebServer().use { server ->
            server.start(); val cfg = cfg(server)
            val original = stored(status = "running", id = "run_original", connection = HermesClient.connectionId(cfg.agent))
            val runs = HermesRuns(temporary.root) { cfg }
            server.enqueue(MockResponse().setBody(HermesClientTest.CAPABILITIES))
            server.enqueue(MockResponse().setBody("""{"run_id":"run_original","status":"completed","output":"Recovered answer"}"""))
            assertTrue(runCatching { runs.execute(cfg, "Next question", "new-session", CallRegistry(), {}, {}) }.isFailure)
            val retained = HermesRunLedger(File(temporary.root, "hermes-run.json")).read()!!
            assertEquals(original.operationId, retained.operationId)
            assertEquals("Recovered answer", retained.output)
            assertFalse(retained.delivered)
            assertFalse(runs.state.value.executing)
            assertEquals(2, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)

            runs.markDelivered(original.operationId)
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"run_id\":\"run_next\"}"))
            server.enqueue(events("""{"event":"run.completed","run_id":"run_next","seq":1,"output":"Next answer"}"""))
            assertEquals("Next answer", runs.execute(cfg, "Next question", "new-session", CallRegistry(), {}, {}))
        }
    }

    private fun awaitControls(runs: HermesRuns) {
        val until = System.nanoTime() + 2_000_000_000
        while (runs.state.value.working && System.nanoTime() < until) Thread.sleep(5)
        assertFalse(runs.state.value.working)
    }

    private fun stored(status: String = "submitting", id: String = "", connection: String = "hash", approval: HermesApproval? = null): HermesRun {
        val run = HermesRun("op", connection, "session", "{\"input\":\"question\"}", System.currentTimeMillis(), true, 86400, true, true,
            runId = id, status = status, approval = approval)
        HermesRunLedger(File(temporary.root, "hermes-run.json")).write(run)
        return run
    }

    private fun events(vararg payloads: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(payloads.joinToString("") { "data: $it\n\n" })
}
