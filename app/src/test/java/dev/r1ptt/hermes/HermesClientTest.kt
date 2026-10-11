package dev.r1ptt.hermes

import dev.r1ptt.data.Agent
import dev.r1ptt.net.ApiError
import dev.r1ptt.net.CallRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HermesClientTest {
    @Test fun discoveryPreservesProxyPrefixAndDistinguishesUnauthorizedFromLegacy() {
        MockWebServer().use { server ->
            server.start()
            val client = HermesClient(Agent(baseUrl = server.url("/proxy/p/work/v1").toString(), apiKey = "private"))
            server.enqueue(MockResponse().setResponseCode(404))
            assertTrue(client.discover().legacy)
            assertEquals("/proxy/p/work/v1/capabilities", server.takeRequest().path)
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            try { client.discover(); fail("401 must not select legacy") } catch (e: ApiError) { assertEquals(401, e.code) }
            for (path in listOf("https://elsewhere/v1/runs", "//elsewhere", "/../v1", "/%2e%2e/v1", "/v1\\bad")) {
                assertTrue(path, runCatching { client.endpoint(path) }.isFailure)
            }
        }
    }

    @Test fun exactCurrentCapabilityShapesAndUntrustedEndpoints() {
        MockWebServer().use { server ->
            server.start(); val client = HermesClient(Agent(baseUrl = server.url("/v1").toString()))
            server.enqueue(MockResponse().setBody(CAPABILITIES))
            val caps = client.discover()
            assertTrue(caps.runs); assertTrue(caps.approvals); assertTrue(caps.durableIdempotency)
            assertEquals(86400, caps.retentionSeconds)
            server.enqueue(MockResponse().setBody(CAPABILITIES.dropLast(1) + ",\"endpoints\":{\"runs\":{\"method\":\"POST\",\"path\":\"https://evil/v1/runs\"}}}"))
            assertTrue(runCatching { client.discover() }.isFailure)
        }
    }

    @Test fun redirectDoesNotForwardCredentialsAndApprovalUsesExactIdentity() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val client = HermesClient(Agent(baseUrl = server.url("/v1").toString(), apiKey = "secret"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/stolen")))
            assertTrue(runCatching { client.discover() }.isFailure)
            assertEquals(0, other.requestCount)
            server.takeRequest()
            server.enqueue(MockResponse().setBody("{\"run_id\":\"run_1\",\"request_id\":\"approval-1\",\"resolved\":1}"))
            client.approve("run_1", "approval-1", true)
            val sent = server.takeRequest()
            assertEquals("/v1/runs/run_1/approval", sent.path)
            val body = org.json.JSONObject(sent.body.readUtf8())
            assertEquals("approval-1", body.getString("request_id")); assertEquals("once", body.getString("choice"))
            assertFalse(body.has("all")); assertFalse(body.has("resolve_all"))
        } }
    }

    @Test fun eventReplayCursorAndCommentFramesAreParsedWithoutInventingCompletion() {
        MockWebServer().use { server ->
            server.start(); val client = HermesClient(Agent(baseUrl = server.url("/v1").toString()))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                ": open\n\nid: 3\ndata: {\"event\":\"message.delta\",\"run_id\":\"run_1\",\"seq\":3,\"delta\":\"Hi\"}\n\n"))
            val events = mutableListOf<HermesEvent>()
            client.events("run_1", 2, CallRegistry()) { events += it; true }
            assertEquals("2", server.takeRequest().getHeader("Last-Event-ID"))
            assertEquals(1, events.size); assertEquals("message.delta", events.single().name)
            assertEquals(3L, events.single().sequence)
        }
    }

    @Test fun providerIdentitySurvivesModelDiscoveryAndUnavailableModelsAreOmitted() {
        MockWebServer().use { server ->
            server.start(); val client = HermesClient(Agent(baseUrl = server.url("/v1").toString()))
            server.enqueue(MockResponse().setBody("""{"providers":[{"slug":"a","name":"A","models":["same","blocked"],"unavailable_models":["blocked"]},{"slug":"b","name":"B","models":["same"]},{"slug":"c","authenticated":false,"models":["no"]}]}"""))
            assertEquals(listOf("a" to "same", "b" to "same"), client.models().map { it.provider to it.model })
            assertEquals("/api/model/options?include_unconfigured=false", server.takeRequest().path)
        }
    }

    companion object {
        const val CAPABILITIES = """{"object":"hermes.api_server.capabilities","features":{"run_submission":true,"run_status":true,"run_events_sse":true,"run_stop":true,"approval_events":true,"run_approval_response":true,"model_options":true,"runs_idempotency":{"supported":true,"durable":true,"retention_seconds":86400}}}"""
    }
}
