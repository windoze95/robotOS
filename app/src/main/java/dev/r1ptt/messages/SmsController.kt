package dev.r1ptt.messages

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dev.r1ptt.App
import dev.r1ptt.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.concurrent.Executors

class SmsReview internal constructor(
    val draft: SmsDraft,
    val record: SmsRecord,
    val texts: List<String>,
    val simLabel: String,
)
data class MessagesSnapshot(val draft: SmsDraft, val threads: List<SmsThread>, val messages: List<SmsRecord>,
    val mmsNotices: Int = 0, val replyRequests: List<Pair<String, SmsDraft>> = emptyList())

/** No network client, AI History, automatic retries, polling service, or radio-idle override. */
class SmsController(
    private val app: App,
    private val transport: SmsTransport = AndroidSmsTransport(app),
    private val cellularEnabled: () -> Boolean = { app.store.value.power.cellular },
) {
    private val db = SmsStore(app)
    private val systemStore = SmsSystemStore(app, db)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("messages", 0)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision
    @Volatile private var dispatching = false
    @Volatile private var pendingUntil = 0L
    private val pending = mutableMapOf<String, Long>() // owned by the worker
    val busy: Boolean get() = dispatching || SystemClock.elapsedRealtime() < pendingUntil

    val supported: Boolean get() = app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
    fun permitted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val canSend: Boolean get() = supported && permitted(Manifest.permission.SEND_SMS)
    var receiveEnabled: Boolean
        get() = SmsRole.held(app) || prefs.getBoolean("receive", false)
        set(value) { prefs.edit().putBoolean("receive", value).apply() }

    fun load(peer: String?, done: (MessagesSnapshot?, String?) -> Unit) = worker.execute {
        try {
            if (SmsRole.held(app)) db.pendingSystemCopies().forEach(::syncSystem)
            val snapshot = MessagesSnapshot(db.draft(), db.threads(), if (peer == null) emptyList() else db.conversation(peer), db.mmsNoticeCount(), db.replyRequests())
            if (peer != null) db.markRead(peer)
            main.post { done(snapshot, null) }
        } catch (_: Exception) { main.post { done(null, "Messages could not be opened. Your stored data has been kept.") } }
    }

    fun saveDraft(draft: SmsDraft, failed: () -> Unit = {}) = worker.execute {
        try { db.saveDraft(draft) } catch (_: Exception) { main.post { failed() } }
    }

    // Synchronous reads for the device API's own threads. SQLiteOpenHelper serializes access.
    fun record(id: String): SmsRecord? = db.record(id)
    fun conversation(peer: String): List<SmsRecord> = db.conversation(peer)
    fun threads(): List<SmsThread> = db.threads()
    fun savedRecipients(): List<SmsRecipient> = db.recipients()

    fun recipients(done: (List<SmsRecipient>?, String?) -> Unit) = worker.execute {
        try { val saved = db.recipients(); main.post { done(saved, null) } }
        catch (_: Exception) { main.post { done(null, "Saved recipients could not be opened.") } }
    }

    fun saveRecipient(name: String, number: String, done: (Boolean) -> Unit) = worker.execute {
        val saved = runCatching { db.addRecipient(name, number) }.isSuccess
        main.post { done(saved) }
    }

    fun removeRecipient(id: Long, done: () -> Unit) = worker.execute {
        runCatching { db.removeRecipient(id) }
        main.post { done() }
    }

    fun diagnostics(record: SmsRecord? = null): String = listOfNotNull(record?.let(SmsDiagnostics::attempt),
        "Current device state (may differ from the attempt):\n${SmsDiagnostics.current(app, cellularEnabled())}").joinToString("\n\n")

    fun queueReply(draft: SmsDraft?, done: () -> Unit) = worker.execute {
        try {
            if (draft == null || draft.body.isBlank()) notifyPrivate("Call reply was not sent. Open Messages to write a one-recipient SMS.")
            else {
                db.addReplyRequest(java.util.UUID.randomUUID().toString(), draft)
                changed(); notifyPrivate("A call reply needs review in Messages. Nothing has been sent.")
            }
        } catch (_: Exception) { notifyPrivate("The call reply could not be saved. Nothing was sent.") }
        finally { main.post(done) }
    }
    fun removeReplyRequest(id: String) = worker.execute { runCatching { db.removeReplyRequest(id); changed() } }

    fun receiveMmsNotice(intent: Intent, done: () -> Unit) = worker.execute {
        try {
            val data = intent.getByteArrayExtra("data") ?: throw IllegalArgumentException()
            val headers = intent.getByteArrayExtra("header")
            val sub = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))
            val hash = MessageDigest.getInstance("SHA-256").apply { update("$sub:".toByteArray()); headers?.let(::update) }.digest(data)
                .joinToString("") { "%02x".format(it) }
            if (db.saveMmsNotice(hash, sub, headers, data)) { changed(); notifyPrivate("An MMS arrived. robotOS cannot download MMS. Open Messages for details.") }
        } catch (_: Exception) { notifyPrivate("An unsupported MMS arrived and its notice could not be saved. Use an MMS-capable app and ask the sender to resend.") }
        finally { done() }
    }

    private fun syncSystem(record: SmsRecord) {
        runCatching { systemStore.sync(record) }.onFailure { notifyPrivate("A text is saved in robotOS, but its system SMS copy could not be updated.") }
    }

    /** Preparation is local. The only transport call is in send(). */
    fun prepare(draft: SmsDraft): SmsReview {
        check(canSend) { "Enable SMS sending first." }
        check(cellularEnabled()) { "Turn on Use cellular data (SIM) in robotOS settings first." }
        val peer = requireNotNull(SmsAddress.normalize(draft.peer)) { "Enter one phone number, including its country code when needed." }
        val body = draft.body.trim()
        require(body.isNotEmpty() && body.length <= SmsRecord.MAX_DRAFT_CHARS) { "Enter a message of up to ${SmsRecord.MAX_DRAFT_CHARS} characters." }
        val sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        check(SubscriptionManager.isValidSubscriptionId(sub)) { "Choose a default SMS SIM in Android settings first." }
        val texts = transport.divide(body, sub)
        require(texts.size in 1..SmsRecord.MAX_PARTS) { "This text needs more than ${SmsRecord.MAX_PARTS} SMS parts. Shorten it first." }
        val slot = SubscriptionManager.getSlotIndex(sub)
        val carrier = runCatching { app.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub).networkOperatorName }
            .getOrNull().orEmpty()
        val label = listOfNotNull("Default SMS SIM", if (slot >= 0) "slot ${slot + 1}" else null, carrier.takeIf { it.isNotBlank() }).joinToString(" · ")
        return SmsReview(draft, SmsRecord.outgoing(peer, body, sub, texts.size, System.currentTimeMillis()), texts, label)
    }

    /**
     * Consumes one explicit attempt (review button, assistant command, or Hermes through the device
     * API). Never automatically retries. An [agent] send runs while a voice turn waits on Hermes.
     */
    fun send(review: SmsReview, clearDraft: Boolean = true, agent: Boolean = false, done: (Boolean, String) -> Unit) {
        if (dispatching || app.updates.installing || (!agent && app.turns.busy)) {
            done(false, "Finish the current action before sending."); return
        }
        if (!canSend || !cellularEnabled() || SubscriptionManager.getDefaultSmsSubscriptionId() != review.record.subscriptionId) {
            done(false, "SMS access or the selected SIM changed. Nothing was sent. Try the command or review again."); return
        }
        dispatching = true
        // Restore only radios robotOS put to sleep. Android's SMS service owns the actual send;
        // SMS does not wait for internet, and the normal screen-off idle alarm still applies.
        app.radio.onActivity()
        worker.execute {
            var persisted = false
            try {
                check(canSend && !app.updates.installing && cellularEnabled())
                check(SubscriptionManager.getDefaultSmsSubscriptionId() == review.record.subscriptionId)
                val record = review.record.copy(createdAt = System.currentTimeMillis(), systemOwned = SmsRole.held(app),
                    sendEvidence = SmsDiagnostics.current(app, cellularEnabled()))
                db.outgoing(record, review.draft.takeIf { clearDraft })
                persisted = true
                syncSystem(record)
                pending.entries.removeAll { it.value <= SystemClock.elapsedRealtime() }
                pending[record.id] = SystemClock.elapsedRealtime() + SmsRecord.SEND_WAIT_MS
                pendingUntil = pending.values.maxOrNull() ?: 0L
                val sent = ArrayList(record.parts.indices.map { callback(record, it, false) })
                val delivered = ArrayList(record.parts.indices.map { callback(record, it, true) })
                transport.send(record, review.texts, sent, delivered)
                changed()
                main.post { done(true, "Sending…") }
            } catch (_: SecurityException) {
                if (persisted) runCatching { failBeforeSend(review.record, "SecurityException before handoff") }
                main.post { done(persisted, "Android did not allow this text. Check SMS access; it was not retried.") }
            } catch (_: IllegalArgumentException) {
                if (persisted) runCatching { failBeforeSend(review.record, "IllegalArgumentException before handoff") }
                main.post { done(persisted, "Android rejected this text. It was not retried.") }
            } catch (_: Exception) {
                // A binder/transport exception after persistence may have happened after handoff.
                // Leave that attempt uncertain; never report it as safely retryable.
                changed()
                main.post { done(persisted, if (persisted) "Send status is uncertain. Check with the recipient before trying again." else "The text could not be saved, so it was not sent.") }
            } finally { dispatching = false }
        }
    }

    private fun failBeforeSend(record: SmsRecord, source: String) {
        record.parts.indices.forEach { part -> db.update(SmsCallback(record.id, record.token, part, false)) { it.sent(part, false, SmsManager.RESULT_ERROR_GENERIC_FAILURE, source = source) } }
        db.record(record.id)?.let(::syncSystem)
        pending.remove(record.id)
        pendingUntil = pending.values.maxOrNull() ?: 0L
        changed()
    }

    private fun callback(record: SmsRecord, part: Int, delivery: Boolean): PendingIntent {
        val id = SmsCallback(record.id, record.token, part, delivery)
        val intent = Intent(app, SmsStatusReceiver::class.java).setData(Uri.parse(id.uri()))
        // Android fills sent errorCode and delivery PDU extras. Both need mutability; component,
        // URI capability, and receiver remain explicit and non-exported. Never accept fill-in identity.
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun result(intent: Intent, resultCode: Int, finished: () -> Unit) = worker.execute {
        try {
            val callback = SmsCallback.parse(intent.dataString) ?: return@execute
            var terminal = false
            val updated = db.update(callback) { record ->
                val radioError = if (intent.hasExtra("errorCode")) intent.getIntExtra("errorCode", 0) else null
                val next = if (!callback.delivery) record.sent(callback.part, resultCode == Activity.RESULT_OK, resultCode, radioError)
                else record.delivered(callback.part, delivery(intent, resultCode))
                terminal = next.parts.none { it.sent == SentPart.WAITING }
                next
            }
            if (terminal) {
                pending.remove(callback.id)
                pendingUntil = pending.values.maxOrNull() ?: 0L
            }
            if (updated) { db.record(callback.id)?.let(::syncSystem); changed() }
        } catch (_: Exception) { notifyPrivate("A text status could not be saved. Check Messages before retrying.") }
        finally { finished() }
    }

    private fun delivery(intent: Intent, resultCode: Int): DeliveryPart {
        if (resultCode != Activity.RESULT_OK) return DeliveryPart.UNKNOWN
        val pdu = intent.getByteArrayExtra("pdu") ?: return DeliveryPart.UNKNOWN
        val format = intent.getStringExtra("format")
        if (pdu.size > 4096 || format !in setOf("3gpp", "3gpp2")) return DeliveryPart.UNKNOWN
        val sms = runCatching { SmsMessage.createFromPdu(pdu, format) }.getOrNull() ?: return DeliveryPart.UNKNOWN
        return SmsDelivery.classify(format, sms.isStatusReportMessage, sms.status)
    }

    fun receive(intent: Intent, finished: () -> Unit) = worker.execute {
        try {
            if (!receiveEnabled || !permitted(Manifest.permission.RECEIVE_SMS)) return@execute
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.toList().orEmpty()
            if (messages.isEmpty()) return@execute
            val sender = messages.first().displayOriginatingAddress ?: return@execute
            if (messages.any { it.displayOriginatingAddress != sender || it.displayMessageBody == null }) return@execute
            val peer = SmsAddress.normalize(sender) ?: sender
            val body = messages.joinToString("") { it.displayMessageBody }
            val sub = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update("$sub:".toByteArray())
            messages.forEach { hash.update(it.pdu) }
            val id = hash.digest().joinToString("") { "%02x".format(it) }
            val record = SmsRecord(id, peer, body, System.currentTimeMillis(), sub, true,
                systemOwned = intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION && SmsRole.held(app))
            if (db.insert(record)) {
                syncSystem(record)
                changed()
                // The owner's own texts go to Hermes, which answers by text; everyone else stays local.
                if (!app.hermesTexts.received(record)) notifyPrivate("New text in Messages")
            }
        } catch (_: Exception) { notifyPrivate("An incoming text could not be saved. Check available storage.") }
        finally { finished() }
    }

    private fun changed() { _revision.value += 1 }

    private fun notifyPrivate(message: String) {
        if (!permitted(Manifest.permission.POST_NOTIFICATIONS)) return
        runCatching {
            val notifications = app.getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { lockscreenVisibility = Notification.VISIBILITY_SECRET })
            val open = PendingIntent.getActivity(app, 0, Intent(app, MessagesActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            notifications.notify(NOTIFICATION, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_messages)
                .setContentTitle("robotOS Messages").setContentText(message).setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_SECRET).setAutoCancel(true).build())
        }
    }

    companion object {
        private const val CHANNEL = "messages"
        const val NOTIFICATION = 204
    }
}
