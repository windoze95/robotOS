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
        configure { it.copy(hermes = HermesLink(passthrough = true, owner = owner)) }
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

    @Test fun anAmbiguousHermesFailureIsNeverSubmittedAgain() {
        answer = { throw java.io.IOException("Connection refused") }
        val texts = texts()
        assertTrue(texts.received(incoming(owner, "Hello?")))
        await { asked.size == 1 }
        await { replies.size == 1 }
        assertTrue(replies.single().second.contains("outcome is unknown"))
        now.addAndGet(HermesTexts.GIVE_UP_MS + 1)
        texts.resume()
        await { replies.size == 1 }
        assertEquals(1, asked.size)
        texts.resume()
        Thread.sleep(200)
        assertEquals(1, replies.size)
    }

    @Test fun ownerChangesWhileHermesAnswersRevokeDelivery() {
        val began = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        answer = { began.countDown(); finish.await(5, TimeUnit.SECONDS); "Private reply" }
        val texts = texts()
        assertTrue(texts.received(incoming(owner, "My private question")))
        assertTrue(began.await(5, TimeUnit.SECONDS))
        configure { it.copy(hermes = it.hermes.copy(owner = "+14055550999")) }
        finish.countDown()
        Thread.sleep(200)
        texts.resume()
        Thread.sleep(200)
        assertTrue(replies.isEmpty())
        assertEquals(1, asked.size)
    }

    @Test fun disabledRelayRevokesQueuedDelivery() {
        replyWorks = false
        val texts = texts()
        texts.received(incoming(owner, "Turn on the light"))
        await { asked.size == 1 }
        Thread.sleep(200)
        configure { it.copy(hermes = it.hermes.copy(passthrough = false)) }
        replyWorks = true
        texts.resume()
        Thread.sleep(200)
        assertEquals(1, asked.size)
        assertTrue(replies.isEmpty())
    }

    @Test fun queuedReplyCannotAcquireANewConnectionsAuthority() {
        val original = HermesTexts.connectionId(app.store.value)
        assertTrue(HermesTexts.replyAllowed(app, owner, original))
        configure { it.copy(agent = it.agent.copy(baseUrl = "https://changed.example/v1")) }
        assertFalse(HermesTexts.replyAllowed(app, owner, original))
        assertTrue(HermesTexts.replyAllowed(app, owner, HermesTexts.connectionId(app.store.value)))
        configure { it.copy(hermes = it.hermes.copy(passthrough = false)) }
        assertFalse(HermesTexts.replyAllowed(app, owner, HermesTexts.connectionId(app.store.value)))
    }

    @Test fun aPassphraseKeepsTextsWithoutItInMessagesAndHermesNeverSeesIt() {
        configure { it.copy(hermes = it.hermes.copy(passphrase = "otter-42")) }
        val texts = texts()
        assertFalse(texts.received(incoming(owner, "What's on today?")))
        assertFalse(texts.received(incoming(owner, "otter-420 What's on today?")))
        assertTrue(texts.last.contains("didn't start with the passphrase"))
        assertFalse(texts.received(incoming(owner, "otter-42")))
        assertTrue(texts.received(incoming(owner, "Otter-42: What's on today?")))
        await { replies.isNotEmpty() }
        assertEquals(listOf("What's on today?"), asked)
        assertEquals(listOf(owner to "Sure."), replies)
    }

    @Test fun textsWithoutThePassphrasePauseTheRelayForAnHour() {
        configure { it.copy(hermes = it.hermes.copy(passphrase = "otter-42")) }
        val texts = texts()
        repeat(HermesTexts.MISS_LIMIT - 1) { assertFalse(texts.received(incoming(owner, "guess $it"))) }
        assertFalse(texts.received(incoming(owner, "otter-42")))
        assertTrue(texts.received(incoming(owner, "otter-42 Not paused yet?")))
        await { replies.isNotEmpty() }
        asked.clear(); replies.clear()
        now.addAndGet(HermesTexts.MISS_WINDOW_MS)
        repeat(HermesTexts.MISS_LIMIT) { assertFalse(texts.received(incoming(owner, "guess $it"))) }
        assertFalse(texts.received(incoming(owner, "otter-42 Am I locked out?")))
        assertTrue(texts.last.startsWith("Relay paused"))
        Thread.sleep(200)
        assertTrue(asked.isEmpty())
        now.addAndGet(HermesTexts.MISS_WINDOW_MS)
        assertTrue(texts.received(incoming(owner, "otter-42 And now?")))
        await { replies.isNotEmpty() }
        assertEquals(listOf("And now?"), asked)
    }

    @Test fun changingThePassphraseRevokesQueuedReplies() {
        for ((before, after) in listOf("otter-42" to "heron-77", "otter-42" to "", "" to "otter-42")) {
            asked.clear(); replies.clear(); replyWorks = false
            configure { it.copy(hermes = it.hermes.copy(passphrase = before)) }
            val texts = texts()
            assertTrue(texts.received(incoming(owner, "otter-42 Turn on the light")))
            await { asked.size == 1 }
            Thread.sleep(200)
            configure { it.copy(hermes = it.hermes.copy(passphrase = after)) }
            replyWorks = true
            texts.resume()
            Thread.sleep(200)
            assertTrue("$before -> $after", replies.isEmpty())
            assertEquals(1, asked.size)
        }
    }

    @Test fun theRequestFollowsThePassphraseAndRedactionRemovesIt() {
        assertEquals("hi", HermesTexts.request("otter-42 hi", "otter-42"))
        assertEquals("hi there", HermesTexts.request("  OTTER-42,hi there", "otter-42"))
        assertEquals("hi", HermesTexts.request("Otter-42: hi", "otter-42"))
        listOf("otter-42hi", "otter-420 hi", "hi otter-42", "otter-42", "otter-42 : ", "otter").forEach {
            assertNull(it, HermesTexts.request(it, "otter-42"))
        }
        assertEquals(" hi ", HermesTexts.request(" hi ", ""))
        assertNull(HermesTexts.request("  ", ""))
        assertEquals("hi", HermesTexts.redact("Otter-42: hi", "otter-42"))
        assertEquals("", HermesTexts.redact("otter-42", "otter-42"))
        assertEquals("hello", HermesTexts.redact("hello", "otter-42"))
        assertEquals("otter-42 hi", HermesTexts.redact("otter-42 hi", ""))
    }

    @Test fun longAnswersAreCutToAFewTexts() {
        assertEquals("ok", HermesTexts.fit("ok"))
        val cut = HermesTexts.fit("x".repeat(5000))
        assertEquals(HermesTexts.MAX_REPLY_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
    }
}
