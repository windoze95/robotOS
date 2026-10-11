package dev.r1ptt.messages

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.DictationTarget
import dev.r1ptt.Phase
import dev.r1ptt.PttService
import dev.r1ptt.R
import dev.r1ptt.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI
import java.text.DateFormat
import java.util.Date

/** One-to-one SMS UI. Manual drafts and external compose requests require review. */
class MessagesActivity : Activity(), DictationTarget {
    private val app get() = application as App
    private val sms get() = app.sms
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var list: LinearLayout? = null
    private var recipient: EditText? = null
    private var body: EditText? = null
    private var reviewButton: Button? = null
    private var screen = Screen.THREADS
    private var peer: String? = null
    private var draft = SmsDraft()
    private var loaded = false
    private var resumed = false
    private var building = false
    private var sending = false
    private var imeVisible = false
    private var generation = 0L
    private var draftRevision = 0L
    private var ui: CoroutineScope? = null
    private var dialog: AlertDialog? = null
    private var dictationConsent: String? = null
    private var rows = emptyList<SmsRecord>()
    private var threads = emptyList<SmsThread>()
    private var pendingCompose: SmsComposeAction? = null
    private var pendingExternal: SmsDraft? = null
    private var pendingNotice: String? = null
    private var mmsNotices = 0
    private var replyRequests = emptyList<Pair<String, SmsDraft>>()
    private val main = Handler(Looper.getMainLooper())
    private val save = Runnable { persistDraft() }
    private val provisional by lazy { ForegroundColorSpan(getColor(R.color.dim)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(SmsAssistant.RECIPIENT).orEmpty()
        val text = intent.getStringExtra(SmsAssistant.BODY).orEmpty()
        if (name.isNotBlank() && name.length <= 80 && text.isNotBlank() && text.length <= SmsRecord.MAX_DRAFT_CHARS) pendingCompose = SmsComposeAction(name, text)
        val externalPeer = intent.getStringExtra(SmsExternalDraft.PEER)
        val externalBody = intent.getStringExtra(SmsExternalDraft.BODY).orEmpty()
        if (externalPeer != null && SmsAddress.normalize(externalPeer) == externalPeer && externalBody.length <= SmsRecord.MAX_DRAFT_CHARS)
            pendingExternal = SmsDraft(externalPeer, externalBody)
        pendingNotice = intent.getStringExtra(SmsExternalDraft.NOTICE)?.take(300)
        intent.getStringExtra(SmsAssistant.THREAD)?.let { address ->
            if (SmsAddress.normalize(address) == address) { screen = Screen.CONVERSATION; peer = address }
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(6))
            isFocusableInTouchMode = true
        }
        setContentView(root)
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            imeVisible = insets.isVisible(WindowInsets.Type.ime())
            adaptToKeyboard()
            view.onApplyWindowInsets(insets)
        }
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        app.turns.attachTarget(this)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (hasMic()) PttService.start(this)
        ui = MainScope().also { scope ->
            scope.launch { sms.revision.collect { refresh() } }
            scope.launch { app.turns.state.collect { renderTurn() } }
            // Refresh uncertain status only while this screen is visible. This does not touch radios.
            scope.launch { while (isActive) { delay(2000); if (rows.any { it.status(System.currentTimeMillis()) == SmsStatus.SENDING || it.status(System.currentTimeMillis()) == SmsStatus.UNKNOWN }) renderRows() } }
        }
        refresh(reloadDraft = true)
    }

    override fun onPause() {
        app.turns.detachTarget(this)
        dialog?.dismiss(); dialog = null
        hideKeyboard()
        main.removeCallbacks(save)
        if (loaded && !sending) persistDraft()
        resumed = false
        generation++
        ui?.cancel(); ui = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        app.turns.detachTarget(this)
        main.removeCallbacks(save)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_1) {
            if (event.repeatCount == 0) PttService.instance?.frameworkButton(event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Platform Activity back handling")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        if (screen == Screen.THREADS) finish() else navigate(Screen.THREADS)
    }

    private fun navigate(next: Screen, address: String? = null) {
        app.turns.cancelDictation(this)
        hideKeyboard()
        persistDraft()
        screen = next; peer = address; rows = emptyList()
        renderScreen()
        refresh()
    }

    private fun renderScreen() {
        building = true
        root.removeAllViews(); list = null; recipient = null; body = null; reviewButton = null
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(header)
        header.addView(button("‹", ::goBack).apply { contentDescription = "Back" }, LinearLayout.LayoutParams(dp(48), dp(44)))
        header.addView(label("Messages", 19f), LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(button("Options", ::options), LinearLayout.LayoutParams(dp(88), dp(44)))
        status = label(if (loaded) idleHint() else "Opening Messages…", 12f).apply {
            setTextColor(getColor(R.color.dim)); setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(status)
        if (screen == Screen.COMPOSE) {
            recipient = field("One phone number", draft.peer, InputType.TYPE_CLASS_PHONE).also { field ->
                field.contentDescription = "Recipient phone number"
                field.filters = arrayOf(InputFilter.LengthFilter(40))
                root.addView(field)
                watch(field) { draft = draft.copy(peer = it); draftChanged() }
            }
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; scroll.addView(this) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (screen) {
            Screen.THREADS -> root.addView(button(if (draft.body.isNotEmpty() || draft.peer.isNotEmpty()) "Resume draft" else "New text") { navigate(Screen.COMPOSE) }.apply { isEnabled = loaded })
            Screen.CONVERSATION -> root.addView(button("Write a reply", ::reply))
            Screen.COMPOSE -> {
                body = field("Write a text…", draft.body, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE).also { field ->
                    field.contentDescription = "SMS draft"
                    field.minLines = 2; field.maxLines = 4
                    field.gravity = Gravity.TOP
                    field.filters = arrayOf(InputFilter.LengthFilter(SmsRecord.MAX_DRAFT_CHARS))
                    field.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    field.setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
                    }
                    root.addView(field)
                    watch(field) { draft = draft.copy(body = it); draftChanged() }
                }
                reviewButton = button("Review text", { sendTyped() }).also { root.addView(it) }
            }
        }
        building = false
        renderRows()
        renderTurn()
        adaptToKeyboard()
    }

    private fun refresh(reloadDraft: Boolean = false) {
        if (!resumed) return
        val ticket = ++generation
        val draftAtLoad = draftRevision
        sms.load(if (screen == Screen.CONVERSATION) peer else null) { snapshot, error ->
            if (!resumed || generation != ticket) return@load
            if (snapshot == null) { status.text = error; return@load }
            val rebuild = !loaded || reloadDraft
            if (rebuild && !sending && draftRevision == draftAtLoad) draft = snapshot.draft
            loaded = true; rows = snapshot.messages; threads = snapshot.threads
            mmsNotices = snapshot.mmsNotices; replyRequests = snapshot.replyRequests
            if (rebuild) renderScreen() else renderRows()
            pendingCompose?.let { action -> pendingCompose = null; resolveAction(action) }
            pendingExternal?.let { next -> pendingExternal = null; replaceDraft(next) }
            pendingNotice?.let { notice -> pendingNotice = null; showDialog(AlertDialog.Builder(this).setTitle("SMS only").setMessage(notice).setPositiveButton("OK", null)) }
            if (screen == Screen.CONVERSATION) getSystemService(NotificationManager::class.java).cancel(SmsController.NOTIFICATION)
        }
    }

    private fun renderRows() {
        val container = list ?: return
        container.removeAllViews()
        when (screen) {
            Screen.THREADS -> {
                if (mmsNotices > 0) container.addView(label("$mmsNotices MMS notice(s) saved on this device. robotOS cannot download pictures or group MMS. Choose an MMS-capable default app in Android settings and ask the sender to resend; the notice is not an imported MMS.", 14f))
                replyRequests.forEach { (id, requested) -> container.addView(button("Review saved draft · ${requested.peer}") {
                    replaceDraft(requested) { sms.removeReplyRequest(id) }
                }) }
                if (threads.isEmpty()) container.addView(label("Your texts will appear here.\n\nSMS only · one recipient at a time. Pictures, group MMS, RCS, and old inbox import are not included.", 15f))
                threads.forEach { thread ->
                    container.addView(button("${if (thread.unread) "● " else ""}${thread.peer}\n${thread.last.body.take(70)}") { navigate(Screen.CONVERSATION, thread.peer) }.apply { gravity = Gravity.START; maxLines = 3 })
                }
                if (threads.size == 100) container.addView(label("Showing the 100 most recent conversations.", 12f))
            }
            Screen.CONVERSATION -> {
                container.addView(label(peer.orEmpty(), 17f).apply { setTextColor(getColor(R.color.accent)) })
                if (rows.size == 100) container.addView(label("Showing the newest 100 texts. Older texts remain stored on this device.", 12f))
                rows.forEach { record ->
                    container.addView(label(record.body, 16f).apply {
                        setTextIsSelectable(true); setPadding(0, dp(12), 0, dp(3))
                        gravity = if (record.incoming) Gravity.START else Gravity.END
                    })
                    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.createdAt))
                    val count = if (record.parts.size > 1) " · ${record.parts.count { it.sent == SentPart.SENT }}/${record.parts.size} parts sent" else ""
                    container.addView(label("${record.status(System.currentTimeMillis()).label()}$count\n$time", 11f).apply { setTextColor(getColor(R.color.dim)) })
                    if (!record.incoming) container.addView(button("Use as a new draft") {
                        showDialog(AlertDialog.Builder(this).setTitle("Create another text?")
                            .setMessage("The earlier text may already have arrived, even if its status is unknown. This creates a draft; review it before sending again.")
                            .setPositiveButton("Create draft") { _, _ -> replaceDraft(SmsDraft(record.peer, record.body)) }
                            .setNegativeButton("Cancel", null))
                    })
                    if (!record.incoming) container.addView(button("Message details") { details(record) })
                }
            }
            Screen.COMPOSE -> container.addView(label(
                "Hold to dictate after enabling it in Options. Release inserts words into this draft. Tap opens review.\n\n${draft.body.length}/${SmsRecord.MAX_DRAFT_CHARS} characters · up to ${SmsRecord.MAX_PARTS} SMS parts",
                13f,
            ).apply { setTextColor(getColor(R.color.dim)); setPadding(0, dp(8), 0, dp(8)) })
        }
    }

    private fun reply() { peer?.let { replaceDraft(SmsDraft(it, "")) } }

    private fun resolveAction(action: SmsComposeAction) {
        sms.recipients { saved, error ->
            if (!resumed) { pendingCompose = action; return@recipients }
            if (saved == null) { pendingCompose = action; status.text = error; return@recipients }
            when (val result = SmsRecipientResolver.resolve(action.recipient, saved)) {
                is SmsResolution.Number -> replaceDraft(SmsDraft(result.number, action.body))
                is SmsResolution.Choose -> showDialog(AlertDialog.Builder(this).setTitle("Which ${action.recipient}?")
                    .setItems(result.matches.map { "${it.name} · ${it.number}" }.toTypedArray()) { _, which ->
                        replaceDraft(SmsDraft(result.matches[which].number, action.body))
                    }.setNegativeButton("Cancel", null))
                SmsResolution.Missing -> {
                    replaceDraft(SmsDraft("", action.body))
                    status.text = getString(R.string.sms_missing_recipient, action.recipient)
                }
            }
        }
    }

    private fun replaceDraft(next: SmsDraft, accepted: () -> Unit = {}) {
        if (draft.body.isNotBlank() && draft != next) {
            showDialog(AlertDialog.Builder(this).setTitle("Replace the current draft?")
                .setMessage("You have an unfinished text. Replacing it discards that draft.")
                .setPositiveButton("Replace draft") { _, _ -> draft = next; navigate(Screen.COMPOSE); accepted() }
                .setNegativeButton("Keep draft", null))
        } else { draft = next; navigate(Screen.COMPOSE); accepted() }
    }

    private fun draftChanged() {
        if (building) return
        draftRevision++
        main.removeCallbacks(save); main.postDelayed(save, 250)
        renderRows()
    }

    private fun persistDraft() {
        if (!loaded || sending) return
        main.removeCallbacks(save)
        sms.saveDraft(draft) { if (resumed) status.setText(R.string.sms_draft_save_failed) }
    }

    override fun isActive() = resumed
    override fun privateDictation() = true
    override fun canDictate() = resumed && screen == Screen.COMPOSE && dialog == null && !sending && hasMic() && dictationConsent == consentKey()
    override fun dictationUnavailable() {
        status.text = when {
            screen != Screen.COMPOSE -> "Open a draft to dictate a text."
            canDictate() -> "Hold again to dictate into this draft."
            else -> "Enable dictation in Options first. Typing stays available."
        }
    }
    override fun consumeDoubleTap() = true // double-tap does not clear an AI conversation from Messages

    override fun sendTyped(): Boolean {
        if (screen != Screen.COMPOSE || dialog != null || sending) return true
        if (app.turns.busy || app.updates.installing) { status.setText(R.string.sms_finish_before_review); return true }
        if (!sms.canSend) { status.setText(R.string.sms_enable_sending); return true }
        val review = try { sms.prepare(draft) } catch (e: IllegalArgumentException) { status.text = e.message; return true }
        catch (e: IllegalStateException) { status.text = e.message; return true }
        catch (_: Exception) { status.setText(R.string.sms_unavailable); return true }
        hideKeyboard()
        val text = "To: ${review.record.peer}\n${review.simLabel}\n${review.texts.size} SMS ${if (review.texts.size == 1) "part" else "parts"}\n\n${review.record.body}\n\nCarrier SMS charges may apply."
        val scroll = ScrollView(this).apply { addView(label(text, 16f).apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }) }
        showDialog(AlertDialog.Builder(this).setTitle("Review text").setView(scroll)
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Send SMS") { _, _ ->
                persistDraft()
                sending = true
                renderTurn()
                sms.send(review) { consumed, message ->
                    sending = false
                    if (consumed && draft == review.draft) draft = SmsDraft()
                    if (resumed) {
                        if (consumed) { screen = Screen.CONVERSATION; peer = review.record.peer; renderScreen(); refresh() }
                        else renderTurn()
                        status.text = message
                    }
                }
            })
        return true
    }

    override fun showPartial(text: String) {
        if (text.isBlank()) return
        val start = put(text) ?: return
        body?.let { it.text.setSpan(provisional, start, it.selectionEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
    override fun insert(text: String) { put(text); persistDraft() }
    override fun clearPartial() {
        val e = body?.text ?: return
        val start = e.getSpanStart(provisional); val end = e.getSpanEnd(provisional)
        if (start >= 0) { e.removeSpan(provisional); e.delete(start, end) }
    }
    override fun keepPartial() { body?.text?.removeSpan(provisional) }

    private fun put(text: String): Int? {
        val field = body ?: return null
        val e = field.text
        var start = e.getSpanStart(provisional); var end = e.getSpanEnd(provisional)
        e.removeSpan(provisional)
        if (start < 0) { start = field.selectionStart.coerceAtLeast(0); end = field.selectionEnd.coerceAtLeast(start) }
        val gap = if (start > 0 && !e[start - 1].isWhitespace()) " " else ""
        e.replace(start, end, gap + text)
        field.setSelection((start + gap.length + text.length).coerceAtMost(e.length))
        return start.coerceAtMost(e.length)
    }

    override fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        root.requestFocus()
    }

    private fun options() {
        app.turns.cancelDictation(this)
        val options = arrayOf(
            if (sms.canSend) "SMS sending is enabled" else "Enable SMS sending",
            if (SmsRole.held(this)) "Default SMS app · incoming enabled" else if (sms.receiveEnabled) "Turn off incoming texts" else "Enable incoming texts",
            if (dictationConsent == consentKey()) "Turn off dictation" else "Enable dictation for this visit",
            if (app.smsAssistant.enabled) "Turn off assistant SMS sending" else "Enable assistant SMS sending",
            "Saved recipients",
            "robotOS settings",
            if (SmsRole.held(this)) "robotOS is the default SMS app" else "Make robotOS the default SMS app",
            "SMS diagnostics",
            "Recent assistant outcomes",
            "Hermes",
        )
        showDialog(AlertDialog.Builder(this).setTitle("Messages options").setItems(options) { _, which ->
            when (which) {
                0 -> if (!sms.canSend) enableSend()
                1 -> if (SmsRole.held(this)) defaultSmsInfo() else if (sms.receiveEnabled) { sms.receiveEnabled = false; status.text = idleHint() } else enableReceive()
                2 -> if (dictationConsent == consentKey()) { dictationConsent = null; status.text = idleHint() } else enableDictation()
                3 -> if (app.smsAssistant.enabled) { app.smsAssistant.enabled = false; app.turns.refreshVoiceContext() } else enableAssistant()
                4 -> savedRecipients()
                5 -> startActivity(Intent(this, SettingsActivity::class.java))
                6 -> defaultSmsInfo()
                7 -> details(null)
                8 -> startActivity(Intent(this, dev.r1ptt.OutcomesActivity::class.java))
                9 -> startActivity(Intent(this, dev.r1ptt.hermes.HermesActivity::class.java))
            }
        })
    }

    private fun details(record: SmsRecord?) {
        val scroll = ScrollView(this).apply { addView(label(sms.diagnostics(record), 14f).apply { setPadding(dp(18), dp(8), dp(18), dp(8)); setTextIsSelectable(true) }) }
        showDialog(AlertDialog.Builder(this).setTitle(if (record == null) "SMS diagnostics" else "Message details").setView(scroll).setPositiveButton("Close", null))
    }

    private fun defaultSmsInfo() {
        if (SmsRole.held(this)) {
            showDialog(AlertDialog.Builder(this).setTitle("Default SMS app")
                .setMessage("robotOS is the default SMS app. Incoming SMS is saved even when the companion option was off. To stop handling incoming SMS, choose another default app in Android settings. MMS downloads and group MMS are not supported.")
                .setPositiveButton("Android settings") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                .setNegativeButton("Close", null))
        } else if (!SmsRole.available(this)) status.setText(R.string.sms_role_unavailable)
        else showDialog(AlertDialog.Builder(this).setTitle("Use robotOS as the default SMS app?")
            .setMessage("robotOS will handle new SMS and keep a copy in Android’s SMS storage. Existing inboxes are not imported. Android grants the SMS access required for this role.\n\nSMS only: pictures and group MMS cannot be downloaded. An incoming MMS notice is saved locally and shown as unsupported; switching apps may require asking the sender to resend. Call quick replies are drafts requiring review in Messages.\n\nThe modem keeps its existing sleep schedule.")
            .setPositiveButton("Choose default app") { _, _ -> startActivityForResult(SmsRole.request(this), SMS_ROLE) }
            .setNegativeButton("Cancel", null))
    }

    private fun enableSend() {
        if (!sms.supported) { status.setText(R.string.sms_unsupported); return }
        showDialog(AlertDialog.Builder(this).setTitle("Allow robotOS to send SMS?")
            .setMessage("Texts use your SIM. Every text requires recipient and message review, then Send SMS. Android will ask for SMS access. This does not change your default messaging app.")
            .setPositiveButton("Continue") { _, _ -> requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SEND_PERMISSION) }
            .setNegativeButton("Cancel", null))
    }

    private fun enableReceive() {
        if (!sms.supported) { status.setText(R.string.sms_unsupported); return }
        showDialog(AlertDialog.Builder(this).setTitle("Save new incoming SMS?")
            .setMessage("robotOS will save new SMS locally and show a private notification. Old inboxes, pictures and group MMS are not imported. No texts are sent to your AI provider.\n\nThe modem still sleeps. Your carrier may deliver queued texts after wake; timing and expiry are not guaranteed.")
            .setPositiveButton("Enable incoming SMS") { _, _ ->
                if (sms.permitted(Manifest.permission.RECEIVE_SMS)) { sms.receiveEnabled = true; status.text = idleHint() }
                else requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), RECEIVE_PERMISSION)
            }.setNegativeButton("Cancel", null))
    }

    private fun enableDictation() {
        val cfg = app.store.value
        fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: "the configured transcription endpoint"
        val destinations = buildSet { add(host(cfg.stt.baseUrl)); if (cfg.liveStt) add(host(cfg.sttLiveUrl)) }.joinToString(" and ")
        val key = consentKey()
        showDialog(AlertDialog.Builder(this).setTitle("Use online dictation for texts?")
            .setMessage("While you hold the button in a draft, microphone audio is sent to $destinations for transcription. Existing texts and recipients are not included. Release only inserts words; you still review and send.\n\nThis permission lasts for this visit to Messages. You can keep typing without it.")
            .setPositiveButton("Enable dictation") { _, _ ->
                if (key == consentKey()) dictationConsent = key
                if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
                status.text = idleHint()
            }.setNegativeButton("Keep typing", null))
    }

    private fun enableAssistant() {
        showDialog(AlertDialog.Builder(this).setTitle("Let the assistant send texts directly?")
            .setMessage("Ask naturally, for example ‘Tell Sam I’m on my way’ or ‘Send a text to NUMBER’. The assistant writes a natural message and sends without review to one clear recipient. With no topic, it writes a brief greeting. For verbatim wording, say ‘Text Sam exactly: MESSAGE’. Carrier SMS charges may apply. Missing or ambiguous recipients need clarification.\n\nWhile enabled, completed voice and typed requests use your selected chat provider to interpret the request and write the message. Saved recipients and existing texts stay local. Messages shows Android’s actual status; Recent assistant outcomes retains categories only. Contextual requests such as ‘send that to her’ still need a clear recipient and intent.")
            .setPositiveButton("Enable direct sending") { _, _ -> app.smsAssistant.enabled = true; app.turns.refreshVoiceContext(); status.setText(R.string.sms_assistant_enabled) }
            .setNegativeButton("Cancel", null))
    }

    private fun savedRecipients() {
        sms.recipients { saved, error ->
            if (!resumed) return@recipients
            if (saved == null) { status.text = error; return@recipients }
            showDialog(AlertDialog.Builder(this).setTitle("Saved recipients · this device only")
                .setItems(saved.map { "${it.name} · ${it.number}" }.toTypedArray()) { _, which ->
                    val selected = saved[which]
                    showDialog(AlertDialog.Builder(this).setTitle(selected.name).setMessage(selected.number)
                        .setPositiveButton("Write a text") { _, _ -> replaceDraft(SmsDraft(selected.number, "")) }
                        .setNeutralButton("Remove") { _, _ -> sms.removeRecipient(selected.id) { if (resumed) savedRecipients() } }
                        .setNegativeButton("Cancel", null))
                }.setPositiveButton("Add recipient") { _, _ -> addRecipient() }.setNegativeButton("Done", null))
        }
    }

    private fun addRecipient() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val name = field("Name you will say", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
            .apply { filters = arrayOf(InputFilter.LengthFilter(80)) }
        val number = field("Exact phone number", "", InputType.TYPE_CLASS_PHONE)
            .apply { filters = arrayOf(InputFilter.LengthFilter(40)) }
        fields.addView(name); fields.addView(number)
        showDialog(AlertDialog.Builder(this).setTitle("Save a recipient").setView(fields).setPositiveButton("Save", null).setNegativeButton("Cancel", null))
        val shown = dialog ?: return
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val label = name.text.toString().trim()
            val phone = SmsAddress.normalize(number.text.toString())
            if (label.isBlank()) { name.error = "Enter a name"; return@setOnClickListener }
            if (phone == null) { number.error = "Enter one exact phone number"; return@setOnClickListener }
            sms.saveRecipient(label, phone) { saved ->
                if (resumed) { if (saved) { shown.dismiss(); status.setText(R.string.sms_recipient_saved) } else number.error = "Could not save. Check storage." }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (requestCode == RECEIVE_PERMISSION) sms.receiveEnabled = granted && sms.permitted(Manifest.permission.RECEIVE_SMS)
        if (requestCode == MIC_PERMISSION && granted && hasMic() && resumed) PttService.start(this)
        status.text = if (granted) idleHint() else "Android did not grant access. Drafts and typing remain available."
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        val shown = builder.create()
        dialog = shown
        shown.setOnDismissListener { if (dialog === shown) dialog = null }
        shown.show()
    }

    private fun renderTurn() {
        if (!::status.isInitialized) return
        val turn = app.turns.state.value
        val busy = turn.phase.active
        recipient?.isEnabled = !busy && !sending
        body?.isEnabled = !busy && !sending
        reviewButton?.isEnabled = loaded && !busy && !sending
        if (busy) {
            status.text = when (turn.phase) {
                Phase.DICTATING -> "Dictating… release to insert"
                Phase.TRANSCRIBING -> "Transcribing into draft…"
                else -> "Voice activity in progress…"
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            status.text = if (turn.phase == Phase.ERROR) turn.note else idleHint()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun adaptToKeyboard() {
        if (!::status.isInitialized) return
        status.visibility = if (imeVisible && screen == Screen.COMPOSE) View.GONE else View.VISIBLE
        body?.maxLines = if (imeVisible) 2 else 4
    }

    private fun idleHint(): String {
        if (!sms.supported) return "SMS unavailable · drafts only"
        val incoming = if (sms.receiveEnabled && sms.permitted(Manifest.permission.RECEIVE_SMS)) "incoming on when modem is awake" else "incoming off"
        return "SMS · $incoming\n${if (dictationConsent == consentKey()) "Dictation on for this visit" else "Typing only · dictation off"}"
    }

    private fun consentKey(): String = app.store.value.let { c -> listOf(c.stt.baseUrl, c.stt.model, c.liveStt.toString(), c.sttLiveUrl, c.sttLiveModel).joinToString("\n") }
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setTextColor(getColor(R.color.fg)); gravity = Gravity.CENTER_VERTICAL }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; textSize = 14f; setOnClickListener { action() } }
    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type; textSize = 16f; setTextColor(getColor(R.color.fg)); setHintTextColor(getColor(R.color.dim))
        setText(value); setSelection(text.length); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    private fun watch(field: EditText, update: (String) -> Unit) { field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!building) update(s.toString()) }
        override fun afterTextChanged(s: Editable?) {}
    }) }

    private enum class Screen { THREADS, CONVERSATION, COMPOSE }
    companion object { private const val SEND_PERMISSION = 20; private const val RECEIVE_PERMISSION = 21; private const val MIC_PERMISSION = 22; private const val SMS_ROLE = 23 }
}
