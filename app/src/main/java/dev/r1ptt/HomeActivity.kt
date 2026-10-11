package dev.r1ptt

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.TextWatcher
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.r1ptt.data.Config
import dev.r1ptt.data.History
import dev.r1ptt.data.Msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dev.r1ptt.hermes.HermesRunState
import dev.r1ptt.hermes.DeviceNotice
import dev.r1ptt.audio.AudioPlayback
import kotlin.math.sqrt

/**
 * The launcher, and the only screen most people will see: status line, conversation, and a text
 * field. When the keyboard is up, the button dictates into the field instead of sending.
 */
class HomeActivity : dev.r1ptt.ui.DeviceActivity(), DictationTarget {
    private val app get() = application as App

    private lateinit var status: TextView
    private lateinit var meter: ProgressBar
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var live: TextView
    private lateinit var input: EditText

    private var observations: CoroutineScope? = null
    private var imeVisible = false
    private var resumed = false
    private var textSp = 17f
    private var renderedConv = ""
    private var lastRendered: Msg? = null
    private var followLatest = true
    private var changingDraft = false
    private var submittedDraft: String? = null
    private var batteryLevel: Int? = null
    private var networkAvailable = false
    private var connectionRegistered = false
    private val batteryEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON) {
                app.hermesRuns.setVisible(this@HomeActivity, intent.action == Intent.ACTION_SCREEN_ON)
                return
            }
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { it >= 0 }?.let { it * 100 / scale.coerceAtLeast(1) }
            renderConnectivity()
        }
    }
    private val connectionEvents = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networkAvailable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            runOnUiThread { renderConnectivity() }
        }
        override fun onLost(network: Network) { networkAvailable = false; runOnUiThread { renderConnectivity() } }
    }
    /** Marks live dictation's provisional words in the text field (dim until the final transcript). */
    private val provisional by lazy { ForegroundColorSpan(getColor(R.color.dim)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        status = findViewById(R.id.status)
        meter = findViewById(R.id.meter)
        scroll = findViewById(R.id.scroll)
        wheelScrollTarget = scroll
        list = findViewById(R.id.list)
        live = findViewById(R.id.live)
        input = findViewById(R.id.input)

        // Lets hideKeyboard() park focus somewhere other than the text field.
        findViewById<View>(R.id.root).isFocusableInTouchMode = true
        input.setHorizontallyScrolling(false)
        input.maxLines = 3
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else false
        }
        findViewById<View>(R.id.send).setOnClickListener { if (!sendTyped()) showKeyboard() }
        findViewById<View>(R.id.chat_title).setOnClickListener { startActivity(Intent(this, ChatsActivity::class.java)) }
        findViewById<View>(R.id.controls).setOnClickListener { startActivity(Intent(this, QuickControlsActivity::class.java)) }
        findViewById<View>(R.id.menu).setOnClickListener { openMenu() }
        findViewById<View>(R.id.stop).setOnClickListener { app.turns.cancelTurn() }
        findViewById<View>(R.id.hermes_task).setOnClickListener { startActivity(Intent(this, dev.r1ptt.hermes.HermesActivity::class.java)) }
        findViewById<View>(R.id.hermes_notice).setOnClickListener { app.notices.state.value.lastOrNull()?.let(::openNotice) }
        findViewById<View>(R.id.latest).setOnClickListener { followLatest = true; scrollToEnd() }
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val bottom = scroll.getChildAt(0)?.height ?: 0
            followLatest = y + scroll.height >= bottom - dp(48)
            findViewById<View>(R.id.latest).visibility = if (followLatest) View.GONE else View.VISIBLE
            if (renderedConv.isNotEmpty()) app.history.savePosition(y, followLatest, renderedConv)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!changingDraft) app.history.saveDraft(s?.toString().orEmpty())
            }
        })
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.isVisible(WindowInsets.Type.ime())
            if (ime != imeVisible) {
                imeVisible = ime
                render(app.turns.state.value) // the hint differs with the keyboard up
            }
            v.onApplyWindowInsets(insets)
        }
        // Contextual wheel routing is shared with every robotOS screen.
        volumeControlStream = AudioManager.STREAM_MUSIC

        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        visible = true
        app.turns.attachTarget(this)
        hideSystemBars()
        if (hasMic()) PttService.start(this) // the microphone service may only start from the foreground
        observations = MainScope().also { s ->
            s.launch { app.store.flow.collect(::applyConfig) }
            s.launch { app.history.flow.collect(::renderHistory) }
            s.launch { app.turns.state.collect { render(it); kotlinx.coroutines.delay(50) } }
            s.launch { AudioPlayback.flow.collect { render(app.turns.state.value) } }
            s.launch { app.history.errors.collect { if (it != null) status.text = it } }
            s.launch { app.hermesRuns.state.collect { renderTask(it) } }
            s.launch { app.notices.state.collect(::renderNotices) }
        }
        render(app.turns.state.value)
        registerReceiver(batteryEvents, IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }, RECEIVER_NOT_EXPORTED)
        app.hermesRuns.setVisible(this, getSystemService(android.os.PowerManager::class.java).isInteractive)
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(connectionEvents) }
            .onSuccess { connectionRegistered = true }
    }

    override fun onPause() {
        app.hermesRuns.setVisible(this, false)
        app.history.savePosition(scroll.scrollY, followLatest)
        app.history.flush()
        unregisterReceiver(batteryEvents)
        if (connectionRegistered) getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(connectionEvents)
        connectionRegistered = false
        app.turns.detachTarget(this)
        resumed = false
        visible = false
        observations?.cancel()
        observations = null
        super.onPause()
    }

    override fun onDestroy() {
        app.turns.detachTarget(this)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasMic()) PttService.start(this) else status.text = getString(R.string.need_mic)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // This is the home screen: Back only closes the keyboard.
        if (imeVisible) hideKeyboard()
    }

    // ---- DictationTarget ----

    override fun isActive(): Boolean = resumed && imeVisible && input.hasFocus()

    override fun showPartial(text: String) {
        if (text.isEmpty()) return
        val start = put(text)
        input.text.setSpan(provisional, start, input.selectionEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    override fun insert(text: String) {
        put(text)
    }

    override fun clearPartial() {
        val e = input.text
        val start = e.getSpanStart(provisional)
        if (start < 0) return
        val end = e.getSpanEnd(provisional)
        e.removeSpan(provisional)
        e.delete(start, end)
    }

    override fun keepPartial() {
        input.text.removeSpan(provisional)
    }

    /**
     * Writes [text] over the provisional words, or at the cursor if there are none, with a space
     * before it if needed; leaves the cursor after it. Returns where the text (and space) starts.
     */
    private fun put(text: String): Int {
        val e: Editable = input.text
        var start = e.getSpanStart(provisional)
        var end = e.getSpanEnd(provisional)
        e.removeSpan(provisional)
        if (start < 0) {
            start = input.selectionStart.coerceAtLeast(0)
            end = input.selectionEnd.coerceAtLeast(start)
        }
        val sep = if (start > 0 && !e[start - 1].isWhitespace()) " " else ""
        e.replace(start, end, sep + text)
        input.setSelection(start + sep.length + text.length)
        return start
    }

    override fun sendTyped(): Boolean {
        if (app.updates.installing) return false // retain unsent text during the installer handoff
        if (app.store.loadError != null || app.history.errors.value != null) { render(app.turns.state.value); return false }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return false
        if (submittedDraft == text && app.turns.busy) return true
        submittedDraft = text
        hideKeyboard()
        app.turns.sendText(text)
        return true
    }

    override fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun showKeyboard() {
        input.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(input, 0)
    }

    // ---- rendering ----

    private fun render(global: TurnState) {
        val active = app.turns.activeConversation
        val s = if (active != null && active != app.history.convId) TurnState() else global
        status.setTextColor(getColor(if (s.phase == Phase.ERROR) R.color.error else R.color.dim))
        val stop = findViewById<TextView>(R.id.stop)
        stop.visibility = if (s.phase.active) View.VISIBLE else View.GONE
        stop.text = stopLabel()
        stop.setOnClickListener { if (AudioPlayback.active) app.turns.stopSpeech() else app.turns.cancelTurn() }
        if (!s.phase.active) submittedDraft = null
        status.text = when (s.phase) {
            Phase.IDLE -> s.note.ifEmpty { idleHint() }
            Phase.LISTENING -> if (s.note.isNotEmpty()) "Listening: ${s.note.lowercase()}" else "Listening… release to send"
            Phase.DICTATING -> "Dictating… release to insert"
            Phase.TRANSCRIBING -> "Transcribing…"
            Phase.THINKING -> s.note.ifEmpty { "Thinking…" }
            Phase.ANSWERING -> s.note.ifEmpty { "Hermes" }
            Phase.SPEAKING -> "Speaking"
            Phase.ERROR -> s.note
        }
        val listening = s.phase == Phase.LISTENING || s.phase == Phase.DICTATING
        meter.visibility = if (listening) View.VISIBLE else View.INVISIBLE
        meter.progress = (sqrt(s.level.coerceIn(0f, 1f)) * 160).toInt().coerceAtMost(100)
        // The exchange in progress: what was heard (live transcription) and the reply so far.
        val showLive = s.phase == Phase.THINKING || s.phase == Phase.ANSWERING ||
            (s.phase.active && (s.heard.isNotEmpty() || s.reply.isNotEmpty()))
        if (showLive) {
            live.visibility = View.VISIBLE
            live.text = SpannableStringBuilder().apply {
                if (s.heard.isNotEmpty()) {
                    append(s.heard, ForegroundColorSpan(getColor(R.color.dim)), 0)
                    append("\n\n")
                }
                append(s.reply.ifEmpty { if (s.phase == Phase.LISTENING) "" else "…" })
            }
            if (followLatest) scrollToEnd()
        } else {
            live.visibility = View.GONE
        }
        // Remote work and spoken replies do not need the backlight held on.
        if (listening) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun idleHint(): String {
        app.store.loadError?.let { return it }
        app.history.errors.value?.let { return it }
        val cfg = app.store.value
        return when {
            cfg.agent.baseUrl.isBlank() || cfg.agent.apiKey.isBlank() -> getString(R.string.need_hermes)
            cfg.missingSpeechKey -> getString(R.string.need_key)
            imeVisible -> "Hold to dictate; tap to send"
            else -> if (networkAvailable) "Ready" else "Network unavailable"
        }
    }

    private fun renderHistory(snap: History.Snapshot) {
        val switched = snap.convId != renderedConv
        val msgs = snap.messages
        val from = msgs.indexOfLast { it === lastRendered }
        if (switched || from < 0 && lastRendered != null) {
            list.removeAllViews()
            renderedConv = snap.convId
            lastRendered = null
        }
        while (list.childCount > 0 && list.getChildAt(0).tag !== msgs.firstOrNull()) list.removeViewAt(0)
        val start = if (lastRendered == null) 0 else msgs.indexOfLast { it === lastRendered } + 1
        for (m in msgs.drop(start)) list.addView(bubble(m))
        lastRendered = msgs.lastOrNull()
        findViewById<TextView>(R.id.chat_title).text = snap.title
        findViewById<View>(R.id.empty).visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
        if (input.text.toString() != snap.draft) {
            changingDraft = true
            input.setText(snap.draft)
            input.setSelection(input.text.length)
            changingDraft = false
        }
        if (switched) {
            render(app.turns.state.value)
            followLatest = snap.followLatest
            scroll.post { if (renderedConv == snap.convId) { if (followLatest) scrollToEnd() else scroll.scrollTo(0, snap.scrollY) } }
        } else if (msgs.size > start && followLatest) scrollToEnd()
    }

    private fun bubble(m: Msg) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = m
        val user = m.role == Msg.USER
        setPadding(0, dp(if (user) 20 else 12), 0, dp(8))
        addView(ui.text(if (user) "You" else "Hermes", 13f, muted = true).apply {
            setPadding(0, 0, 0, dp(4))
        })
        addView(TextView(this@HomeActivity).apply {
            text = m.text
            textSize = textSp
            setTextColor(getColor(R.color.fg))
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            gravity = Gravity.START
        })
    }

    private fun openMenu() {
        val options = arrayOf("New chat", "Saved chats", "Read last reply", "Messages", "Hermes", "Settings")
        showDeviceDialog(AlertDialog.Builder(this).setTitle("robotOS").setItems(options) { _, index ->
            when (index) {
                0 -> app.turns.newConversation()
                1 -> startActivity(Intent(this, ChatsActivity::class.java))
                2 -> app.history.messages.lastOrNull { it.role == Msg.ASSISTANT }?.let {
                    if (!app.turns.speakText(it.text)) android.widget.Toast.makeText(this, "Speech is unavailable while busy or quiet", android.widget.Toast.LENGTH_SHORT).show()
                }
                3 -> startActivity(Intent(this, dev.r1ptt.messages.MessagesActivity::class.java))
                4 -> startActivity(Intent(this, dev.r1ptt.hermes.HermesActivity::class.java))
                5 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        })
    }

    private fun renderTask(state: HermesRunState) {
        val line = findViewById<TextView>(R.id.hermes_task)
        val run = state.run
        line.visibility = if (run != null && (run.active || !run.delivered)) View.VISIBLE else View.GONE
        line.text = state.message.ifBlank { run?.label.orEmpty() }
        findViewById<TextView>(R.id.stop).text = stopLabel()
        app.hermesRuns.confirmAdmittedDraft(app.history)
        app.hermesRuns.reconcile(app.history)
    }

    private fun stopLabel() = if (AudioPlayback.active) "Stop speech"
        else if (app.hermesRuns.state.value.run?.active == true) "Stop task" else "Disconnect"

    private fun renderNotices(notices: List<DeviceNotice>) {
        findViewById<TextView>(R.id.hermes_notice).apply {
            visibility = if (notices.isEmpty()) View.GONE else View.VISIBLE
            text = notices.lastOrNull()?.let { "Note from Hermes${if (notices.size > 1) " · ${notices.size}" else ""}\n${it.text}" }.orEmpty()
            contentDescription = "$text. Tap to read or dismiss"
        }
    }

    private fun openNotice(notice: DeviceNotice) {
        val message = ui.text(notice.text).apply {
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val body = ScrollView(this).apply { addView(message) }
        val dialog = AlertDialog.Builder(this).setTitle("Note from Hermes").setView(body)
            .setPositiveButton("Dismiss note") { _, _ ->
                runCatching { app.notices.dismiss(notice.id) }.onFailure {
                    android.widget.Toast.makeText(this, "The note could not be dismissed", android.widget.Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Close", null)
        if (app.notices.state.value.size > 1) dialog.setNeutralButton("All notes") { _, _ ->
            val notes = app.notices.state.value.asReversed()
            showDeviceDialog(AlertDialog.Builder(this).setTitle("Notes from Hermes")
                .setItems(notes.map { it.text.take(100) }.toTypedArray()) { _, index -> openNotice(notes[index]) }
                .setNegativeButton("Close", null))
        }
        showDeviceDialog(dialog)
    }

    private fun renderConnectivity() {
        if (!resumed) return
        findViewById<TextView>(R.id.controls).text = batteryLevel?.let { "$it%" } ?: "Controls"
        findViewById<View>(R.id.controls).contentDescription = "Quick controls. Battery ${batteryLevel ?: "unknown"} percent"
        if (!app.turns.state.value.phase.active) render(app.turns.state.value)
    }

    private fun applyConfig(c: Config) {
        textSp = c.textSizeSp.coerceIn(14, 24).toFloat()
        live.textSize = textSp
        input.textSize = textSp
        for (i in 0 until list.childCount) (list.getChildAt(i) as? LinearLayout)?.let { row ->
            (row.getChildAt(0) as? TextView)?.textSize = 13f * textSp / 17f
            (row.getChildAt(1) as? TextView)?.textSize = textSp
        }
    }

    private fun scrollToEnd() {
        scroll.post { if (followLatest) { scroll.scrollTo(0, scroll.getChildAt(0).height); findViewById<View>(R.id.latest).visibility = View.GONE } }
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var visible = false

        /** Brings the conversation up when a hold starts somewhere else (e.g. in system Settings). */
        fun bringToFront(ctx: Context) {
            if (visible) return
            runCatching {
                ctx.startActivity(
                    Intent(ctx, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }
    }
}
