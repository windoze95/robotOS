package dev.r1ptt

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import dev.r1ptt.data.Config as AppConfig
import dev.r1ptt.ui.VoiceStage
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioRecord

/**
 * Until a held voice turn's words are handed on, the orange stage can throw it away or keep the
 * words to edit. Either way nothing reaches Hermes. Presses here record a real (synthetic) tone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HeldVoiceTurnTest {
    private lateinit var app: App
    private val turns get() = app.turns
    private val said = "Text Sam I'm running late"
    private val speech = MockWebServer()
    private val hermes = MockWebServer()
    /** Counts down when the transcription request arrives. */
    private val transcribing = CountDownLatch(1)
    /** The transcription answers only once this is open. */
    @Volatile private var transcriptionGate = CountDownLatch(0)
    /** Hermes answers only once this is open. */
    @Volatile private var hermesGate = CountDownLatch(0)

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
        speech.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.endsWith("/audio/transcriptions") != true) return MockResponse() // the hold's connection warm-up
                transcribing.countDown()
                transcriptionGate.await(8, TimeUnit.SECONDS)
                return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("text", said).toString())
            }
        }
        hermes.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/v1/chat/completions") return MockResponse().setResponseCode(404)
                hermesGate.await(8, TimeUnit.SECONDS)
                return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("choices", JSONArray()
                    .put(JSONObject().put("message", JSONObject().put("content", "Texted Sam.")).put("finish_reason", "stop"))).toString())
            }
        }
        speech.start()
        hermes.start()
        // Transcription off OpenAI records the whole hold and uploads it on release.
        configure {
            it.copy(stt = it.stt.copy(baseUrl = speech.url("/v1").toString(), apiKey = "k"), earcons = false,
                agent = it.agent.copy(baseUrl = hermes.url("/v1").toString(), apiKey = "k".repeat(40)),
                tts = it.tts.copy(enabled = false))
        }
        var sample = 0L
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                Thread.sleep(sizeInBytes * 1000L / (16_000 * 2)) // as fast as a real microphone
                for (i in 0 until sizeInBytes / 2) {
                    val s = (sin(2 * PI * 440 * sample++ / 16_000) * 12_000).toInt()
                    audioData[offsetInBytes + 2 * i] = s.toByte()
                    audioData[offsetInBytes + 2 * i + 1] = (s shr 8).toByte()
                }
                return sizeInBytes
            }
        })
    }

    @After fun cleanup() {
        transcriptionGate.countDown()
        hermesGate.countDown()
        turns.shutdown()
        ShadowAudioRecord.clearSource()
        speech.shutdown()
        hermes.shutdown()
    }

    @Suppress("UNCHECKED_CAST") private fun configure(update: (AppConfig) -> AppConfig) {
        val state = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store) as MutableStateFlow<AppConfig>
        state.value = update(state.value)
    }

    private fun idle(ms: Long = 100) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) { idle(20); Thread.sleep(5) }
        assertTrue("Turn condition did not arrive", condition())
    }

    /** Holds the button and talks for a little longer than the shortest clip that counts. */
    private fun holdAndTalk(phase: Phase = Phase.LISTENING) {
        turns.onPress(0)
        turns.onHoldStart()
        assertEquals(phase, turns.state.value.phase)
        Thread.sleep(600)
    }

    /** Lets any late work run, then checks that the press left nothing behind. */
    private fun assertNothingWasSent() {
        idle(500)
        Thread.sleep(200)
        idle(500)
        assertFalse(turns.busy)
        assertEquals(0, hermes.requestCount)
        assertTrue(app.history.messages.isEmpty())
    }

    @Test fun swipingTheStageAwayWhileHoldingClosesTheMicrophoneAndTheReleaseSendsNothing() {
        holdAndTalk()
        assertTrue(turns.cancelVoiceTurn())
        assertFalse("The microphone closes at the swipe, not at release", turns.busy)
        assertEquals("Cancelled", turns.state.value.note)
        turns.onHoldEnd()
        assertNothingWasSent()
        assertEquals("Nothing was uploaded for transcription", 1, transcribing.count)
        assertEquals("", app.history.draft)
    }

    @Test fun tappingTheStageWhileHoldingKeepsTheWordsToEditAfterTheDraft() {
        app.history.saveDraft("Remind me:")
        holdAndTalk()
        assertTrue(turns.editVoiceTurn())
        assertTrue(turns.state.value.editing)
        assertEquals(Phase.LISTENING, turns.state.value.phase) // still listening until release
        turns.onHoldEnd()
        await { !turns.busy && app.history.draft != "Remind me:" }
        assertEquals("Remind me: $said", app.history.draft)
        assertNothingWasSent()
    }

    @Test fun aTapJustAfterReleaseStillKeepsTheWords() {
        transcriptionGate = CountDownLatch(1)
        holdAndTalk()
        turns.onHoldEnd()
        assertTrue(transcribing.await(5, TimeUnit.SECONDS))
        assertEquals(Phase.TRANSCRIBING, turns.state.value.phase)
        assertTrue(turns.editVoiceTurn())
        assertTrue(turns.state.value.editing)
        transcriptionGate.countDown()
        await { !turns.busy }
        assertEquals(said, app.history.draft)
        assertNothingWasSent()
    }

    @Test fun aSwipeJustAfterReleaseStillCancels() {
        transcriptionGate = CountDownLatch(1)
        holdAndTalk()
        turns.onHoldEnd()
        assertTrue(transcribing.await(5, TimeUnit.SECONDS))
        assertTrue(turns.cancelVoiceTurn())
        transcriptionGate.countDown()
        assertNothingWasSent()
        assertEquals("", app.history.draft)
    }

    @Test fun onceTheWordsAreSentNeitherGestureCanTakeThemBack() {
        hermesGate = CountDownLatch(1)
        holdAndTalk()
        turns.onHoldEnd()
        await { turns.state.value.phase == Phase.THINKING }
        assertEquals(said, app.history.messages.single().text)
        assertFalse(turns.cancelVoiceTurn())
        assertFalse(turns.editVoiceTurn())
        assertEquals(Phase.THINKING, turns.state.value.phase)
        hermesGate.countDown()
        await { app.history.messages.lastOrNull()?.text == "Texted Sam." && !turns.busy }
    }

    @Test fun dictationWithTheKeyboardUpIsNotAVoiceTurnForTheStage() {
        val field = object : DictationTarget {
            val inserted = mutableListOf<String>()
            override fun isActive() = true
            override fun showPartial(text: String) {}
            override fun insert(text: String) { inserted += text }
            override fun clearPartial() {}
            override fun keepPartial() {}
            override fun sendTyped() = false
            override fun hideKeyboard() {}
        }
        turns.attachTarget(field)
        holdAndTalk(Phase.DICTATING)
        assertFalse(turns.cancelVoiceTurn())
        assertFalse(turns.editVoiceTurn())
        turns.onHoldEnd()
        await { !turns.busy }
        assertEquals(listOf(said), field.inserted)
        turns.detachTarget(field)
    }

    @Test fun onHomeATapOnTheStageLandsTheWordsInTheTextFieldWithTheKeyboardUp() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        try {
            holdAndTalk()
            idle()
            val stage = home.get().findViewById<VoiceStage>(R.id.stage)
            assertTrue(stage.showing)
            assertTrue(texts(stage).contains("Tap to edit · swipe to cancel"))
            val down = SystemClock.uptimeMillis()
            touch(stage, down, MotionEvent.ACTION_DOWN, 100f, 200f, 0)
            touch(stage, down, MotionEvent.ACTION_UP, 100f, 200f, 80)
            idle()
            assertTrue(turns.state.value.editing)
            assertTrue(texts(stage).containsAll(listOf("Dictating", "Release to edit", "Swipe to cancel")))
            turns.onHoldEnd()
            val input = home.get().findViewById<EditText>(R.id.input)
            await { input.text.toString() == said }
            idle()
            assertFalse(stage.showing)
            assertTrue(input.hasFocus())
            assertTrue(shadowOf(home.get().getSystemService(InputMethodManager::class.java)).isSoftInputVisible)
            assertNothingWasSent()
            // With the keyboard up, the button's tap sends the edited words; that path is unchanged.
            assertEquals(said, app.history.draft)
        } finally { home.pause().stop().destroy() }
    }

    @Test fun onHomeASwipeThrowsTheStageAwayAndCancels() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        try {
            holdAndTalk()
            idle()
            val stage = home.get().findViewById<VoiceStage>(R.id.stage)
            assertTrue(stage.showing && stage.width > 0)
            val down = SystemClock.uptimeMillis()
            touch(stage, down, MotionEvent.ACTION_DOWN, 40f, 200f, 0)
            touch(stage, down, MotionEvent.ACTION_MOVE, 40f + stage.width / 4f, 210f, 60)
            assertTrue("The stage follows the finger", stage.translationX > 0)
            touch(stage, down, MotionEvent.ACTION_UP, 40f + stage.width * .6f, 220f, 120)
            assertFalse(stage.showing)
            assertFalse(turns.busy)
            turns.onHoldEnd()
            idle(400)
            assertEquals(View.GONE, stage.visibility)
            assertEquals("Cancelled", home.get().findViewById<TextView>(R.id.status).text.toString())
            assertNothingWasSent()
            assertEquals(1, transcribing.count)
        } finally { home.pause().stop().destroy() }
    }

    private fun touch(view: View, down: Long, action: Int, x: Float, y: Float, afterMs: Long) {
        MotionEvent.obtain(down, down + afterMs, action, x, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
