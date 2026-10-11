package dev.r1ptt

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import dev.r1ptt.data.Config as AppConfig
import dev.r1ptt.messages.SmsStore
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** The R1 is never the agent: a request to text someone reaches the backend exactly as said. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TurnHermesTest {
    private lateinit var app: App
    private val turns get() = app.turns
    private val request = "Text Sam: running late, start without me"

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.deleteDatabase("messages.db")
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
        configure { it.copy(tts = it.tts.copy(enabled = false)) }
    }
    @After fun cleanup() { turns.shutdown() }

    @Suppress("UNCHECKED_CAST") private fun configure(update: (AppConfig) -> AppConfig) {
        val state = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store) as MutableStateFlow<AppConfig>
        state.value = update(state.value)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("Turn condition did not arrive", condition())
    }
    /** Enter at the completed-transcription boundary; no microphone is used. */
    private fun completedVoice(input: String) {
        val handle = TurnController::class.java.declaredMethods.single { it.name == "handleTranscript" }.apply { isAccessible = true }
        val block: suspend (Any) -> Unit = { turn -> suspendCoroutine { continuation ->
            val result = handle.invoke(turns, turn, input, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        } }
        TurnController::class.java.declaredMethods.single { it.name == "launch" }.apply { isAccessible = true }
            .invoke(turns, 1L, false, block)
    }
    private fun hermes(server: MockWebServer) = configure {
        it.copy(agent = it.agent.copy(baseUrl = server.url("/v1").toString(), apiKey = "k".repeat(40)))
    }
    private fun reply(server: MockWebServer, text: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text))
            .put("finish_reason", "stop"))).toString()))

    @Test fun hermesGetsTheRequestUnchangedInItsSessionAndActsThroughItsTools() {
        MockWebServer().use { server ->
            server.start(); hermes(server)
            server.enqueue(MockResponse().setResponseCode(404))
            reply(server, "Texted Sam.")
            completedVoice(request)
            await { app.history.messages.lastOrNull()?.text == "Texted Sam." && !turns.busy }
            assertEquals("/v1/capabilities", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", sent.path)
            assertEquals("r1ptt-${app.history.convId}", sent.getHeader("X-Hermes-Session-Id"))
            val messages = JSONObject(sent.body.readUtf8()).getJSONArray("messages")
            assertEquals(request, messages.getJSONObject(messages.length() - 1).getString("content"))
            assertTrue(messages.getJSONObject(0).getString("content").contains("robotos tools"))
            assertEquals(2, server.requestCount)
            SmsStore(app).use { assertTrue(it.threads().isEmpty()) }
        }
    }

    @Test fun approvalPausesTheLocalTurnAndKeepsItsRemoteIdentity() {
        MockWebServer().use { server ->
            server.start(); hermes(server)
            server.enqueue(MockResponse().setBody(dev.r1ptt.hermes.HermesClientTest.CAPABILITIES))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"run_id\":\"run_approval\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"event\":\"approval.request\",\"run_id\":\"run_approval\",\"seq\":1,\"request_id\":\"decision\",\"choices\":[\"once\",\"deny\"]}\n\n"))
            completedVoice(request)
            await { app.hermesRuns.state.value.run?.status == "waiting_for_approval" && !turns.busy }
            assertEquals("decision", app.hermesRuns.state.value.run!!.approval!!.requestId)
            assertEquals("", app.history.draft)
            assertEquals(3, server.requestCount)
            val before = app.history.convId
            turns.newConversation()
            assertNotEquals(before, app.history.convId)
            assertEquals("waiting_for_approval", app.hermesRuns.state.value.run!!.status)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun anApprovalReviewPressCannotRecordSendOrStartAnotherConversation() {
        val before = app.history.convId
        var backs = 0
        turns.guardApprovalReview { backs++ }
        turns.onPress(0)
        turns.guardApprovalReview(null) // navigation during the press must retain its original context
        turns.onHoldStart(); turns.onHoldEnd(); turns.onShortRelease(); turns.onDoubleTap()
        assertFalse(turns.busy)
        assertEquals(before, app.history.convId)
        assertTrue(app.history.messages.isEmpty())
        assertEquals(0, backs)
    }

    @Test fun browsingAnotherConversationDoesNotRedirectTheReply() {
        MockWebServer().use { server ->
            server.start(); hermes(server)
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBodyDelay(200, TimeUnit.MILLISECONDS)
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Original reply\"},\"finish_reason\":\"stop\"}]}"))
            val original = app.history.convId
            completedVoice("Original question")
            await { server.requestCount == 2 }
            app.history.newConversation()
            app.history.saveDraft("Different draft")
            await { !turns.busy }
            assertTrue(app.history.messages.isEmpty())
            assertEquals("Different draft", app.history.draft)
            assertTrue(app.history.select(original))
            assertEquals("Original reply", app.history.messages.last().text)
        }
    }
}
