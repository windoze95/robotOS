package dev.r1ptt.messages

import android.app.Activity
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import dev.r1ptt.App

object SmsRole {
    fun held(context: Context): Boolean = context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    fun available(context: Context): Boolean = context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_SMS) == true
    fun request(context: Context): Intent = context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
}

/** Exported entry points accept a bounded, single-number draft, never a send instruction. */
object SmsExternalDraft {
    const val PEER = "sms.external.peer"
    const val BODY = "sms.external.body"
    const val NOTICE = "sms.external.notice"
    fun parse(intent: Intent): SmsDraft? {
        if (intent.action !in setOf(Intent.ACTION_SENDTO, android.telephony.TelephonyManager.ACTION_RESPOND_VIA_MESSAGE)) return null
        if (intent.hasExtra(Intent.EXTRA_STREAM) || intent.clipData != null) return null
        val uri = intent.data ?: return null
        if (uri.scheme !in setOf("sms", "smsto")) return null
        // Reject groups, fragments, dial strings, and multiple query values. A URI is untrusted input.
        if (uri.fragment != null) return null
        val encoded = uri.encodedSchemeSpecificPart
        val raw = android.net.Uri.decode(encoded.substringBefore('?'))
        val peer = SmsAddress.normalize(raw) ?: return null
        val query = encoded.substringAfter('?', "")
        val bodyValues = if (query.isEmpty()) emptyList() else query.split('&')
        if (bodyValues.any { !it.startsWith("body=") }) return null
        if (bodyValues.size > 1) return null
        val body = intent.getStringExtra("sms_body") ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: bodyValues.singleOrNull()
                ?.substringAfter('=')?.let(android.net.Uri::decode).orEmpty()
        if (body.length > SmsRecord.MAX_DRAFT_CHARS) return null
        return SmsDraft(peer, body)
    }
    fun open(context: Context, draft: SmsDraft): Intent = Intent(context, MessagesActivity::class.java)
        .putExtra(PEER, draft.peer).putExtra(BODY, draft.body)
}

class SmsComposeActivity : Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        if ((application as App).workspace.blocked) { startActivity(Intent(this, dev.r1ptt.HomeActivity::class.java)); finish(); return }
        val draft = runCatching { SmsExternalDraft.parse(intent) }.getOrNull()
        startActivity(if (draft != null) SmsExternalDraft.open(this, draft) else Intent(this, MessagesActivity::class.java)
            .putExtra(SmsExternalDraft.NOTICE, "Only one-recipient SMS drafts are supported. MMS, attachments, and group messages are not supported; nothing was sent."))
        finish()
    }
}

/** Phone's protected quick-reply entry point queues a durable draft and private review notification. */
class SmsRespondService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if ((application as App).workspace.blocked) { stopSelf(startId); return START_NOT_STICKY }
        val draft = intent?.takeIf { it.action == android.telephony.TelephonyManager.ACTION_RESPOND_VIA_MESSAGE }
            ?.let { runCatching { SmsExternalDraft.parse(it) }.getOrNull() }
        (application as App).sms.queueReply(draft) { stopSelf(startId) }
        return START_NOT_STICKY // Restarting a service must never replay a send.
    }
}

/** MMS is explicitly unsupported: retain its bounded push locally and surface a durable warning. */
class MmsNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION || intent.type != "application/vnd.wap.mms-message" || !SmsRole.held(context)) return
        val pending = goAsync()
        (context.applicationContext as App).sms.receiveMmsNotice(intent) { pending.finish() }
    }
}
