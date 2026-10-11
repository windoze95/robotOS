package dev.r1ptt.hermes

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import dev.r1ptt.App
import dev.r1ptt.data.Config
import dev.r1ptt.data.HermesLink
import dev.r1ptt.messages.SmsController
import dev.r1ptt.messages.SmsRecord
import dev.r1ptt.messages.SmsStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [34], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HermesTextsTest {
    private lateinit var app: App
    private lateinit var sms: SmsController
    private val asked = CopyOnWriteArrayList<String>()
    private val replies = CopyOnWriteArrayList<Pair<String, String>>()
    private val now = AtomicLong(1_000_000L)
    private var answer: () -> String = { "Sure." }
    private var replyWorks = true
    private val owner = "+14055550123"

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.deleteDatabase("messages.db")
        app.getSharedPreferences("hermes_texts", 0).edit().clear().commit()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
        sms = SmsController(app) { true }
        configure { it.copy(activeProvider = "hermes", hermes = HermesLink(passthrough = true, owner = owner)) }
    }

    @Suppress("UNCHECKED_CAST") private fun configure(update: (Config) -> Config) {
        val state = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store) as MutableStateFlow<Config>
        state.value = update(state.value)
    }

    private fun texts() = HermesTexts(app, { sms }, ask = { asked.add(it); answer() },
        reply = { to, body -> if (replyWorks) replies.add(to to body); replyWorks }, clock = now::get)

    private fun incoming(from: String, body: String, id: String = java.util.UUID.randomUUID().toString()): SmsRecord {
        val record = SmsRecord(id, from, body, now.get(), 1, true)
        SmsStore(app).use { it.insert(record) }
        return record
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("Condition did not arrive", condition())
    }

    @Test fun ownerTextsGoToHermesAndTheAnswerIsTextedBackOnce() {
        val texts = texts()
        assertTrue(texts.received(incoming(owner, "What's on my calendar?")))
        await { replies.isNotEmpty() }
        assertEquals(listOf("What's on my calendar?"), asked)
        assertEquals(listOf(owner to "Sure."), replies)
        texts.resume()
        Thread.sleep(200)
        assertEquals(1, asked.size)
        assertEquals(1, replies.size)
    }

    @Test fun everyoneElseAndDisabledPassthroughStayLocal() {
        val texts = texts()
        assertFalse(texts.received(incoming("+14055550199", "Hi")))
        configure { it.copy(hermes = it.hermes.copy(passthrough = false)) }
        assertFalse(texts.received(incoming(owner, "Hi")))
        configure { it.copy(activeProvider = "openai", hermes = it.hermes.copy(passthrough = true)) }
        assertFalse(texts.received(incoming(owner, "Hi")))
        Thread.sleep(200)
        assertTrue(asked.isEmpty())
    }

    @Test fun aFailedReplyRetriesOnlyTheTextNeverHermes() {
        replyWorks = false
        val texts = texts()
        val record = incoming(owner, "Turn on the porch light")
        assertTrue(texts.received(record))
        await { asked.size == 1 }
        Thread.sleep(200)
        assertTrue(replies.isEmpty())
        replyWorks = true
        texts.resume()
        await { replies.size == 1 }
        assertEquals(1, asked.size)
    }

    @Test fun hermesOutageEndsInOneNoticeAfterTheGiveUpWindow() {
        answer = { throw java.io.IOException("Connection refused") }
        val texts = texts()
        assertTrue(texts.received(incoming(owner, "Hello?")))
        await { asked.size == 1 }
        Thread.sleep(200)
        assertTrue(replies.isEmpty())
        now.addAndGet(HermesTexts.GIVE_UP_MS + 1)
        texts.resume()
        await { replies.size == 1 }
        assertTrue(replies.single().second.contains("couldn't reach Hermes"))
        texts.resume()
        Thread.sleep(200)
        assertEquals(1, replies.size)
    }

    @Test fun longAnswersAreCutToAFewTexts() {
        assertEquals("ok", HermesTexts.fit("ok"))
        val cut = HermesTexts.fit("x".repeat(5000))
        assertEquals(HermesTexts.MAX_REPLY_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
    }
}
