package dev.r1ptt.hermes

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import dev.r1ptt.App
import dev.r1ptt.data.Msg
import dev.r1ptt.messages.SmsDraft
import dev.r1ptt.messages.SmsRecipientText
import dev.r1ptt.messages.SmsRecord
import dev.r1ptt.net.CallRegistry
import dev.r1ptt.net.ChatClient
import dev.r1ptt.net.ChatRequest
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The owner texting this R1's number is texting Hermes: each text goes to Hermes in its own SMS
 * session and the reply is texted back. Texts from anyone else stay in Messages. Hermes can act on
 * the R1 meanwhile (for example, text someone else) through its robotos tools.
 *
 * Pending texts survive a restart and are retried with backoff for [GIVE_UP_MS]; after that the owner
 * gets one short notice instead of silence. A reply is never sent twice for one text.
 */
class HermesTexts(
    private val app: App,
    private val sms: () -> dev.r1ptt.messages.SmsController = { app.sms },
    private val ask: (String) -> String = { text -> askHermes(app, text) },
    private val reply: (String, String) -> Boolean = { owner, body -> textOwner(app, owner, body) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = app.getSharedPreferences("hermes_texts", 0)
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hermes-texts") }
    @Volatile var last: String = "No texts yet"
        private set

    /** Called for each newly saved incoming text. True when it is the owner's and went to Hermes. */
    fun received(record: SmsRecord): Boolean {
        val cfg = app.store.value
        val link = cfg.hermes
        if (!record.incoming || !link.passthrough || !cfg.hermesAgent || record.body.isBlank()) return false
        if (!SmsRecipientText.sameNumber(record.peer, link.owner, SmsRecipientText.region(app))) return false
        synchronized(prefs) { prefs.edit().putLong("pending.${record.id}", clock()).commit() }
        Log.i(TAG, "hermes text: received from owner")
        worker.execute(::drain)
        return true
    }

    /** At service start: finish anything a restart interrupted. */
    fun resume() = worker.execute(::drain)

    private fun pending(): List<Pair<String, Long>> = synchronized(prefs) {
        prefs.all.mapNotNull { (k, v) -> if (k.startsWith("pending.") && v is Long) k.removePrefix("pending.") to v else null }
    }.sortedBy { it.second }

    private fun done(id: String) = synchronized(prefs) { prefs.edit().remove("pending.$id").remove("tries.$id").remove("answer.$id").commit() }

    private fun drain() {
        for ((id, since) in pending()) {
            val record = sms().record(id)
            if (record == null) { done(id); continue }
            if (!process(record, since)) {
                val tries = prefs.getInt("tries.$id", 0) + 1
                prefs.edit().putInt("tries.$id", tries).commit()
                // 15 s, 30 s, 1 min … 5 min: Wi-Fi reconnects and Hermes restarts are usually quick.
                worker.schedule(::drain, (15_000L shl (tries - 1).coerceAtMost(4)).coerceAtMost(300_000L), TimeUnit.MILLISECONDS)
                return // keep order: later texts wait for this one
            }
            done(id)
        }
    }

    /** True when this text is finished: answered, or given up on with a notice. */
    private fun process(record: SmsRecord, since: Long): Boolean {
        val link = app.store.value.hermes
        val lock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "robotOS:hermes-text")
        lock.acquire(4 * 60_000L)
        try {
            // Hermes answers (and acts) once per text; a failed SMS handoff retries only the reply.
            val answer = prefs.getString("answer.${record.id}", null) ?: try {
                app.radio.onActivity()
                if (!runBlocking { app.radio.awaitOnline(45_000) }) throw java.io.IOException("No network")
                ask(record.body).trim().also { prefs.edit().putString("answer.${record.id}", it).commit() }
            } catch (e: Exception) {
                Log.w(TAG, "hermes text: ${e.javaClass.simpleName}")
                last = "Couldn't reach Hermes: ${dev.r1ptt.net.friendly(e)}"
                if (clock() - since < GIVE_UP_MS) return false
                reply(link.owner, "robotOS couldn't reach Hermes (${dev.r1ptt.net.friendly(e)}). Your text is saved on the R1; send it again later.")
                return true
            }
            if (answer.isEmpty() || reply(link.owner, fit(answer))) {
                last = "Answered a text"
                Log.i(TAG, "hermes text: answered")
                return true
            }
            last = "Hermes answered, but the reply text couldn't be sent"
            Log.w(TAG, "hermes text: reply not handed to Android")
            return clock() - since >= GIVE_UP_MS
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    companion object {
        private const val TAG = "r1ptt"
        const val GIVE_UP_MS = 30 * 60_000L
        /** Long agent answers become several SMS parts; past this, the text says it was cut. */
        const val MAX_REPLY_CHARS = 1200

        /** How Hermes should answer a text: it goes back as an SMS. */
        const val STYLE = "Your user is texting you from their own phone; robotOS on their Rabbit R1 relays it. " +
            "Your reply is texted back to that phone as an SMS: plain text only, no markdown, lists or emoji, and brief " +
            "(a few sentences) unless they ask for more. You can act on the R1, including texting other people, with your robotos tools."

        fun fit(text: String): String = if (text.length <= MAX_REPLY_CHARS) text else text.take(MAX_REPLY_CHARS - 1).trimEnd() + "…"

        private fun askHermes(app: App, text: String): String {
            val cfg = app.store.value
            val req = ChatRequest.build(cfg.copy(systemPrompt = STYLE), listOf(Msg(Msg.USER, text)), "", session = cfg.hermes.smsSession)
            return ChatClient().stream(req, CallRegistry(), {}, {})
        }

        /** Hands one reply to Android on the main thread, as a Hermes (agent) send. */
        private fun textOwner(app: App, owner: String, body: String): Boolean {
            val latch = CountDownLatch(1)
            var consumed = false
            Handler(Looper.getMainLooper()).post {
                val review = runCatching { app.sms.prepare(SmsDraft(owner, body)) }.getOrNull()
                if (review == null) latch.countDown()
                else app.sms.send(review, clearDraft = false, agent = true) { ok, _ -> consumed = ok; latch.countDown() }
            }
            latch.await(30, TimeUnit.SECONDS)
            return consumed
        }
    }
}
