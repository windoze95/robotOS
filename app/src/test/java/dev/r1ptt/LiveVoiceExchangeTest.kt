package dev.r1ptt

import dev.r1ptt.audio.PcmPlayer
import dev.r1ptt.data.Config as AppConfig
import dev.r1ptt.messages.SmsStore
import dev.r1ptt.net.LiveProtocol
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
/** Live speech-to-speech (OpenAI only) talks; it never acts on the device. */
class LiveVoiceExchangeTest {
    private lateinit var app: App
    private lateinit var voice: LiveVoice
    private lateinit var tracker: LiveTracker
    private val states = mutableListOf<TurnState>()
    private val failures = mutableListOf<String>()
    private val command = "Text +15551234567: Synthetic completed voice command"

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.deleteDatabase("messages.db")
        voice = LiveVoice(app, publish = states::add, fail = failures::add, settled = {})
        tracker = field("tracker") as LiveTracker
    }
    @After fun cleanup() { voice.reset(); (field("player") as PcmPlayer).release() }
    private fun field(name: String): Any? = LiveVoice::class.java.getDeclaredField(name).apply { isAccessible = true }.get(voice)
    private fun tick() { LiveVoice::class.java.getDeclaredMethod("tick").apply { isAccessible = true }.invoke(voice) }
    private fun event(event: LiveProtocol.Event) {
        LiveVoice::class.java.getDeclaredMethod("handle", LiveProtocol.Event::class.java).apply { isAccessible = true }.invoke(voice, event)
    }
    /** Synthetic accepted events; no microphone, network, player or native SMS transport. */
    private fun begin(heard: String, said: String = "Synthetic response") {
        LiveVoice::class.java.getDeclaredField("active").apply { isAccessible = true }.setBoolean(voice, true)
        tracker.hold(); event(LiveProtocol.Event.Heard(heard)); event(LiveProtocol.Event.Said(said))
        assertNull(field("session")); assertFalse(voice.capturing)
    }
    private fun reply() { tracker.release(); tracker.audio(speech = true) }
    private fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }
    private fun noSms() { SmsStore(app).use { assertTrue(it.threads().isEmpty()) } }

    @Test fun liveVoiceIsOnlyForTheOpenAiBackend() {
        for (provider in listOf("openai", "hermes", "custom")) {
            val c = AppConfig().let { it.copy(activeProvider = provider, providers = it.providers +
                (provider to it.providers.getValue(provider).copy(apiKey = "synthetic-key"))) }
            assertEquals(provider, provider == "openai", c.liveVoice)
        }
    }

    @Test fun aCompletedRequestToTextIsOnlyConversation() {
        begin(command); reply(); advance(1200); tick(); tick()
        noSms(); assertFalse(voice.busy); assertTrue(failures.isEmpty())
    }

    @Test fun pendingBackendWorkStillPreventsQuietAudioCompletion() {
        begin("Ordinary question"); reply(); event(LiveProtocol.Event.DelegationStarted)
        advance(2000); tick(); assertTrue(voice.busy)
        event(LiveProtocol.Event.Backend("response.completed")); tick()
        assertFalse(voice.busy); noSms()
    }

    @Test fun interruptionSettlesWithoutFailing() {
        begin(command); reply(); assertTrue(voice.interrupt()); advance(2000); tick()
        noSms(); assertFalse(voice.busy); assertTrue(failures.isEmpty())
    }

    @Test fun failedExchangeKeepsNothingAndSaysWhy() {
        begin(command); reply()
        LiveVoice::class.java.getDeclaredMethod("failExchange", String::class.java).apply { isAccessible = true }
            .invoke(voice, "Synthetic connection failure")
        noSms(); assertFalse(voice.busy); assertTrue(app.history.messages.isEmpty())
        assertEquals(listOf("Synthetic connection failure"), failures)
    }

    @Test fun noReplyTimeoutIsAFailure() {
        begin(command); tracker.release(); advance(20_000); tick()
        assertEquals(listOf("No reply; please try again"), failures)
        noSms()
    }

    @Test fun modelOutputCannotSendAnything() {
        begin("Explain how texting works", said = command); reply(); advance(1200); tick()
        noSms(); assertEquals(listOf("Explain how texting works", command), app.history.messages.map { it.text })
    }
}
