package dev.r1ptt.hermes

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import dev.r1ptt.App
import dev.r1ptt.data.Msg
import dev.r1ptt.data.Config
import dev.r1ptt.messages.SmsDraft
import dev.r1ptt.messages.SmsRecipientText
import dev.r1ptt.messages.SmsRecord
import dev.r1ptt.net.CallRegistry
import dev.r1ptt.net.ChatClient
import dev.r1ptt.net.ChatRequest
import dev.r1ptt.workspace.SharedChat
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest

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
    private val ask: ((String) -> String)? = null,
    private val reply: ((String, String) -> Boolean)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = app.getSharedPreferences("hermes_texts", 0)
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hermes-texts") }
    @Volatile var last: String = "No texts yet"
        private set

    /** Called for each newly saved incoming text. True when it is the owner's and went to Hermes. */
    fun received(record: SmsRecord): Boolean {
        if (app.workspace.quarantined) return false
        val cfg = app.store.value
        val link = cfg.hermes
        if (!record.incoming || !link.passthrough || record.body.isBlank()) return false
        if (!SmsRecipientText.sameNumber(record.peer, link.owner, SmsRecipientText.region(app))) return false
        synchronized(prefs) {
            if (prefs.contains("pending.${record.id}") || prefs.getBoolean("done.${record.id}", false)) return true
            prefs.edit().putLong("pending.${record.id}", clock()).putString("owner.${record.id}", link.owner)
                .putString("connection.${record.id}", connection(app)).commit()
        }
        Log.i(TAG, "hermes text: received from owner")
        worker.execute(::drain)
        return true
    }

    /** At service start: finish anything a restart interrupted. */
    fun resume() = worker.execute(::drain)

    private fun pending(): List<Pair<String, Long>> = synchronized(prefs) {
        prefs.all.mapNotNull { (k, v) -> if (k.startsWith("pending.") && v is Long) k.removePrefix("pending.") to v else null }
    }.sortedBy { it.second }

    private fun done(id: String) = synchronized(prefs) {
        val edit = prefs.edit().remove("pending.$id").remove("tries.$id").remove("answer.$id")
            .remove("owner.$id").remove("connection.$id").remove("asking.$id").remove("replying.$id").remove("recovery_wait.$id")
            .putBoolean("done.$id", true)
        // Incoming SMS records are the durable source; bound this additional deduplication cache.
        prefs.all.keys.filter { it.startsWith("done.") }.dropLast(511).forEach { edit.remove(it) }
        edit.commit()
    }

    private fun authorityAllowed(record: SmsRecord, owner: String, connection: String): Boolean {
        val link = app.store.value.hermes
        return !app.workspace.quarantined && link.passthrough && link.owner == owner && connection(app) == connection &&
            SmsRecipientText.sameNumber(record.peer, owner, SmsRecipientText.region(app))
    }

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
        val owner = prefs.getString("owner.${record.id}", null)
        val connection = prefs.getString("connection.${record.id}", null)
        // Old unbound queued work cannot safely acquire a different owner's authority on upgrade.
        if (owner == null || connection == null || !authorityAllowed(record, owner, connection)) {
            last = "A queued text was kept in Messages because relay access changed"
            return true
        }
        if (prefs.getBoolean("recovery_wait.${record.id}", false) && clock() - since >= GIVE_UP_MS) {
            last = if (prefs.getBoolean("asking.${record.id}", false)) "Recovery verification expired. Check Hermes before trying this request again."
                else "Recovery verification expired. The text remains in Messages; no request was submitted."
            return true
        }
        if (!app.workspace.networkAllowed) {
            check(prefs.edit().putBoolean("recovery_wait.${record.id}", true).commit())
            last = "Waiting for device recovery verification; the text remains in Messages"
            app.workspace.recoverNow()
            return false
        }
        if (prefs.getBoolean("replying.${record.id}", false)) {
            last = "Reply status is unknown; check Messages before sending again"
            return true
        }
        val lock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "robotOS:hermes-text")
        lock.acquire(4 * 60_000L)
        try {
            // Hermes answers (and acts) once per text; a failed SMS handoff retries only the reply.
            val answer = prefs.getString("answer.${record.id}", null) ?: run {
                val answer = if (prefs.getBoolean("asking.${record.id}", false)) UNKNOWN else {
                    app.radio.onActivity()
                    if (!runBlocking { app.radio.awaitOnline(45_000) }) {
                        last = "Waiting for a network to reach Hermes"
                        if (clock() - since < GIVE_UP_MS) return false
                        "robotOS couldn't reach Hermes. Your text is saved in Messages; no request was submitted."
                    } else {
                        val config = app.store.value
                        if (!authorityAllowed(record, owner, connection)) return true
                        if (!app.workspace.networkAllowed) { app.workspace.recoverNow(); return false }
                        check(prefs.edit().putBoolean("asking.${record.id}", true).commit())
                        try { (ask?.invoke(record.body) ?: if (app.workspace.identity.paired) runBlocking {
                            val deviceId = app.workspace.identity.value.getString("device_id")
                            SharedChat.ask(app, "shared-sms-$deviceId", record.body,
                                STYLE + " This request came through robotOS device_id=$deviceId. Target that exact device_id for device tools unless the user explicitly chooses another device.")
                        } else askHermes(config, record.body)).trim() }
                        catch (e: Exception) {
                            Log.w(TAG, "hermes text: ${e.javaClass.simpleName}")
                            // Legacy chat has no durable idempotency contract. Never replay possible actions.
                            UNKNOWN
                        }
                    }
                }
                check(prefs.edit().putString("answer.${record.id}", answer).commit())
                answer
            }
            if (!authorityAllowed(record, owner, connection)) {
                last = "Reply cancelled because relay access changed"
                return true
            }
            if (!app.workspace.networkAllowed) {
                check(prefs.edit().putBoolean("recovery_wait.${record.id}", true).commit())
                last = "Reply saved; waiting for device recovery verification"
                app.workspace.recoverNow()
                return false
            }
            if (answer.isEmpty()) return true
            check(prefs.edit().putBoolean("replying.${record.id}", true).commit())
            val handedOff = try { reply?.invoke(owner, fit(answer)) ?: textOwner(app, owner, fit(answer), connection) } catch (_: Exception) {
                last = "Reply status is unknown; check Messages before sending again"
                return true
            }
            if (handedOff) {
                last = "Reply attempt finished; check Messages for delivery"
                Log.i(TAG, "hermes text: answered")
                return true
            }
            // False is an explicit rejection before handoff, which may safely retry only delivery.
            prefs.edit().remove("replying.${record.id}").commit()
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
        private const val UNKNOWN = "Hermes's response was interrupted and the outcome is unknown. Your request may have run. Check Hermes before asking it to perform the action again."

        private fun connection(app: App): String = if (app.workspace.identity.paired)
            app.workspace.identity.value.let { "hub:${it.optString("base_url")}:${it.optString("device_id")}:${app.store.value.hermes.smsSession}" }
            else connectionId(app.store.value)
        internal fun connectionId(cfg: Config): String {
            val value = listOf(cfg.agent.baseUrl, cfg.agent.apiKey,
                cfg.agent.headers.toSortedMap().toString(), cfg.hermes.smsSession).joinToString("\n")
            return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        /** How Hermes should answer a text: it goes back as an SMS. */
        const val STYLE = "Your user is texting you from their own phone; robotOS on their Rabbit R1 relays it. " +
            "Your reply is texted back to that phone as an SMS: plain text only, no markdown, lists or emoji, and brief " +
            "(a few sentences) unless they ask for more. You can act on the R1, including texting other people, with your robotos tools."

        fun fit(text: String): String = if (text.length <= MAX_REPLY_CHARS) text else text.take(MAX_REPLY_CHARS - 1).trimEnd() + "…"

        private fun askHermes(cfg: Config, text: String): String {
            val req = ChatRequest.build(cfg.copy(systemPrompt = STYLE), listOf(Msg(Msg.USER, text)), "", session = cfg.hermes.smsSession)
            return ChatClient().stream(req, CallRegistry(), {}, {})
        }

        /** Hands one reply to Android on the main thread, as a Hermes (agent) send. */
        internal fun replyAllowed(app: App, owner: String, originalConnection: String) =
            app.store.value.let { cfg -> app.workspace.networkAllowed && cfg.hermes.passthrough && cfg.hermes.owner == owner && connection(app) == originalConnection }

        private fun textOwner(app: App, owner: String, body: String, originalConnection: String): Boolean {
            val latch = CountDownLatch(1)
            var consumed = false
            val admitted = AtomicBoolean(false)
            fun allowed() = replyAllowed(app, owner, originalConnection)
            Handler(Looper.getMainLooper()).post {
                if (!admitted.compareAndSet(false, true)) return@post
                if (!allowed()) { latch.countDown(); return@post }
                val review = runCatching { app.sms.prepare(SmsDraft(owner, body)) }.getOrNull()
                if (review == null) latch.countDown()
                else app.sms.send(review, clearDraft = false, agent = true, authorize = ::allowed) { ok, _ -> consumed = ok; latch.countDown() }
            }
            if (latch.await(30, TimeUnit.SECONDS)) return consumed
            // After admission the handoff is uncertain; report consumed to prevent another send.
            return !admitted.compareAndSet(false, true)
        }
    }
}
