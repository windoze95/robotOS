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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import dev.r1ptt.ui.BatteryGlyph
import dev.r1ptt.ui.Fonts
import dev.r1ptt.ui.LevelBars
import dev.r1ptt.ui.PulseView
import dev.r1ptt.ui.VoiceStage

/**
 * The launcher, and the only screen most people will see: the conversation, one line about what
 * the R1 is doing, and a text field. Holding the button floods the screen orange; with the
 * keyboard up the button dictates into the field instead of sending.
 */
class HomeActivity : dev.r1ptt.ui.DeviceActivity(), DictationTarget {
    private val app get() = application as App
    /** What the user calls the agent (Settings → Hermes → Name), shown wherever it is spoken to. */
    private val agent get() = app.store.value.agent.displayName

    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var live: TextView
    private lateinit var input: EditText
    private lateinit var send: TextView
    private lateinit var stage: VoiceStage
    private lateinit var menuSheet: FrameLayout

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
    private var charging = false
    private var networkAvailable = false
    private var connectionRegistered = false
    private var listeningSince = 0L
    private var listenedMs = 0L
    private var statusAction: (() -> Unit)? = null
    private var shownStrip: List<Any?>? = null
    private var shownAction: List<Any>? = null
    private val filledAction by lazy { ui.pressable(ui.background(R.color.accent, 26), 26, onAccent = true) }
    private val plainAction by lazy { ui.pressable(ui.background(R.color.raised, 26), 26) }
    private val batteryEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON) {
                app.hermesRuns.setVisible(this@HomeActivity, intent.action == Intent.ACTION_SCREEN_ON)
                return
            }
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { it >= 0 }?.let { it * 100 / scale.coerceAtLeast(1) }
            charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
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
        scroll = findViewById(R.id.scroll)
        wheelScrollTarget = scroll
        list = findViewById(R.id.list)
        live = findViewById(R.id.live)
        input = findViewById(R.id.input)
        send = findViewById(R.id.send)
        stage = findViewById(R.id.stage)
        menuSheet = findViewById(R.id.menu_sheet)

        // Lets hideKeyboard() park focus somewhere other than the text field.
        findViewById<View>(R.id.root).isFocusableInTouchMode = true
        input.setHorizontallyScrolling(false)
        input.maxLines = 3
        input.textCursorDrawable = GradientDrawable().apply { setColor(getColor(R.color.accent)); setSize(ui.dp(2), ui.dp(20)) }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else false
        }
        send.setOnClickListener {
            if (stoppable(shown())) { if (AudioPlayback.active) app.turns.stopSpeech() else app.turns.cancelTurn() }
            else if (!sendTyped()) showKeyboard()
        }
        findViewById<View>(R.id.type).setOnClickListener { openComposer() }
        stage.onCancel = { app.turns.cancelVoiceTurn() }
        stage.onEdit = { app.turns.editVoiceTurn() }
        findViewById<View>(R.id.chat_title).setOnClickListener { startActivity(Intent(this, ChatsActivity::class.java)) }
        findViewById<View>(R.id.controls).setOnClickListener { startActivity(Intent(this, QuickControlsActivity::class.java)) }
        findViewById<View>(R.id.menu).setOnClickListener { openMenu() }
        findViewById<View>(R.id.hermes_task).setOnClickListener { startActivity(Intent(this, dev.r1ptt.hermes.HermesActivity::class.java)) }
        findViewById<View>(R.id.hermes_notice).setOnClickListener { app.notices.state.value.lastOrNull()?.let(::openNotice) }
        findViewById<View>(R.id.latest).setOnClickListener { followLatest = true; scrollToEnd() }
        findViewById<View>(R.id.turn_bar).setOnClickListener { statusAction?.invoke() }
        menuSheet.setOnClickListener { closeMenu() }
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
                renderAction(shown())
            }
        })
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.isVisible(WindowInsets.Type.ime())
            if (ime != imeVisible) {
                imeVisible = ime
                render(app.turns.state.value) // the hint differs with the keyboard up
                renderEmpty()
                if (followLatest) scrollToEnd() // keep the newest words above the keyboard
            }
            v.onApplyWindowInsets(insets)
        }
        findViewById<LevelBars>(R.id.dictation_meter).apply { bars = 9; color = getColor(R.color.accent) }
        buildEmpty()
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
            s.launch { app.history.errors.collect { render(app.turns.state.value) } }
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
        if (hasMic()) PttService.start(this)
        render(app.turns.state.value)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // This is the home screen: Back only closes the menu or the keyboard.
        if (menuSheet.visibility == View.VISIBLE) closeMenu() else if (imeVisible) hideKeyboard()
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

    /** Words kept from the orange stage are in the draft; the button's tap sends them once edited. */
    override fun editDraft() {
        if (resumed) openComposer()
    }

    /** The text field, even where a wide Stop button had taken its place, focused with the keyboard up. */
    private fun openComposer() {
        input.visibility = View.VISIBLE
        input.requestFocus()
        renderAction(shown())
        input.post { showKeyboard() }
    }

    private fun showKeyboard() {
        input.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(input, 0)
    }

    // ---- rendering ----

    /** The turn as this chat should show it: work in another chat does not appear here. */
    private fun shown(global: TurnState = app.turns.state.value): TurnState {
        val active = app.turns.activeConversation
        return if (active != null && active != app.history.convId) TurnState() else global
    }

    /** Remote work and playback can be stopped from the screen; a held button stops itself. */
    private fun stoppable(s: TurnState) = s.phase.active && s.phase != Phase.LISTENING && s.phase != Phase.DICTATING

    private fun render(global: TurnState) {
        val s = shown(global)
        if (!s.phase.active) submittedDraft = null
        renderStage(s)
        renderLive(s)
        renderStrip(s)
        renderAction(s)
        // Remote work and spoken replies do not need the backlight held on.
        if (s.phase == Phase.LISTENING || s.phase == Phase.DICTATING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Holding the button for Hermes: the orange stage, kept through transcription of that hold. */
    private fun renderStage(s: TurnState) {
        val voice = s.phase == Phase.LISTENING || (s.phase == Phase.TRANSCRIBING && stage.showing)
        if (!voice) { stage.hide(); return }
        if (!stage.showing) {
            listeningSince = SystemClock.elapsedRealtime()
            closeMenu()
            stage.show()
        }
        if (s.phase == Phase.LISTENING) listenedMs = SystemClock.elapsedRealtime() - listeningSince
        stage.update(s.heard, s.level, sending = s.phase == Phase.TRANSCRIBING, editing = s.editing, elapsedMs = listenedMs)
    }

    /** The exchange in progress: Hermes working, then the reply as it streams in. */
    private fun renderLive(s: TurnState) {
        val working = s.phase == Phase.THINKING || s.phase == Phase.ANSWERING
        val box = findViewById<View>(R.id.live_box)
        if (!working && !(stoppable(s) && s.reply.isNotEmpty())) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        findViewById<View>(R.id.live_pulse).visibility = if (working) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.live_note).text = when {
            s.note.isNotEmpty() -> s.note
            s.phase == Phase.THINKING -> "Thinking"
            else -> ""
        }
        live.visibility = if (s.reply.isEmpty()) View.GONE else View.VISIBLE
        live.text = s.reply
        if (followLatest) scrollToEnd()
    }

    private enum class Glyph { NONE, DOTS, BARS, METER, ICON }
    private class Strip(val text: String, val tone: Int, val glyph: Glyph = Glyph.NONE, val icon: Int = 0, val action: (() -> Unit)? = null)

    private fun strip(s: TurnState): Strip? = when (s.phase) {
        Phase.LISTENING, Phase.THINKING, Phase.ANSWERING -> null
        Phase.DICTATING -> Strip("Release to insert", R.color.accent, Glyph.METER)
        Phase.TRANSCRIBING -> if (stage.showing) null else Strip("Transcribing", R.color.accent, Glyph.DOTS)
        Phase.SPEAKING -> Strip("Speaking · wheel sets volume", R.color.accent, Glyph.BARS)
        Phase.ERROR -> Strip(s.note, R.color.error, Glyph.ICON, R.drawable.ic_alert)
        Phase.IDLE -> if (s.note.isNotEmpty()) Strip(s.note, R.color.dim, Glyph.ICON, R.drawable.ic_info) else idleStrip()
    }

    private fun idleStrip(): Strip? {
        val settings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        app.store.loadError?.let { return Strip(it, R.color.error, Glyph.ICON, R.drawable.ic_alert, settings) }
        app.history.errors.value?.let { return Strip(it, R.color.error, Glyph.ICON, R.drawable.ic_alert) }
        val cfg = app.store.value
        val heroAsks = findViewById<View>(R.id.empty).visibility == View.VISIBLE // the empty chat shows its own setup button
        return when {
            !hasMic() -> Strip(getString(R.string.need_mic), R.color.error, Glyph.ICON, R.drawable.ic_voice) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
            heroAsks && setupNeeded() != null -> null
            cfg.agent.baseUrl.isBlank() || cfg.agent.apiKey.isBlank() -> Strip(getString(R.string.need_hermes), R.color.accent, Glyph.ICON, R.drawable.ic_hermes, settings)
            cfg.missingSpeechKey -> Strip(getString(R.string.need_key), R.color.accent, Glyph.ICON, R.drawable.ic_voice, settings)
            imeVisible -> Strip("Hold to dictate · tap to send", R.color.dim, Glyph.ICON, R.drawable.ic_voice)
            !networkAvailable -> Strip("No network connection", R.color.dim, Glyph.ICON, R.drawable.ic_wifi_off) {
                startActivity(Intent(this, QuickControlsActivity::class.java))
            }
            else -> null
        }
    }

    private fun renderStrip(s: TurnState) {
        val strip = strip(s)
        if (strip?.glyph == Glyph.METER) findViewById<LevelBars>(R.id.dictation_meter).push(s.level)
        val key = strip?.let { listOf(it.text, it.tone, it.glyph, it.icon, it.action != null) }
        statusAction = strip?.action
        if (key == shownStrip) return // the meter above is the only part that moves at the level rate
        shownStrip = key
        val bar = findViewById<View>(R.id.turn_bar)
        bar.isClickable = statusAction != null
        if (strip == null) {
            bar.visibility = View.GONE
            status.text = ""
            return
        }
        bar.visibility = View.VISIBLE
        val state = strip.tone == R.color.accent && strip.glyph != Glyph.ICON
        status.text = strip.text
        status.setTextColor(getColor(strip.tone))
        status.typeface = if (state) Fonts.mono(this, 700) else Typeface.DEFAULT
        status.isAllCaps = state
        status.letterSpacing = if (state) .08f else 0f
        status.textSize = if (state) 12f else 14f
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
            if (strip.action != null) ui.icon(R.drawable.ic_chevron_right, strip.tone) else null, null)
        findViewById<PulseView>(R.id.pulse).apply {
            visibility = if (strip.glyph == Glyph.DOTS || strip.glyph == Glyph.BARS) View.VISIBLE else View.GONE
            mode = if (strip.glyph == Glyph.BARS) PulseView.Mode.BARS else PulseView.Mode.DOTS
        }
        findViewById<LevelBars>(R.id.dictation_meter).apply {
            visibility = if (strip.glyph == Glyph.METER) View.VISIBLE else View.GONE
            if (strip.glyph != Glyph.METER) reset()
        }
        findViewById<ImageView>(R.id.status_icon).apply {
            visibility = if (strip.glyph == Glyph.ICON) View.VISIBLE else View.GONE
            if (strip.glyph == Glyph.ICON) setImageDrawable(ui.icon(strip.icon, strip.tone))
        }
    }

    /**
     * Send when there's text, stop while Hermes or playback is running, otherwise open the keyboard.
     * With nothing typed, Stop takes the whole row; a keyboard button beside it still lets you type.
     */
    private fun renderAction(s: TurnState) {
        val stop = stoppable(s)
        val wide = stop && !imeVisible && input.text.isEmpty() && !input.hasFocus()
        val label = if (stop) stopLabel() else ""
        val glyph = when {
            stop -> R.drawable.ic_stop
            input.text.isNotBlank() -> R.drawable.ic_send
            else -> R.drawable.ic_keyboard
        }
        val key = listOf(glyph, label, wide)
        if (key == shownAction) return
        shownAction = key
        val filled = glyph != R.drawable.ic_keyboard
        input.visibility = if (wide) View.GONE else View.VISIBLE
        findViewById<View>(R.id.type).visibility = if (wide) View.VISIBLE else View.GONE
        send.layoutParams = (send.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (wide) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            weight = if (wide) 1f else 0f
            marginStart = if (wide) 0 else dp(8)
        }
        // The wide pill centers its icon with the label, so the icon rides in the text.
        send.text = if (!wide) "" else SpannableStringBuilder().apply {
            append(" ", ImageSpan(ui.icon(glyph, R.color.on_accent).apply { setBounds(0, 0, dp(20), dp(20)) }, DynamicDrawableSpan.ALIGN_CENTER),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  $label")
        }
        send.contentDescription = when (glyph) { R.drawable.ic_stop -> label; R.drawable.ic_send -> getString(R.string.send); else -> "Type a message" }
        send.setCompoundDrawablesRelativeWithIntrinsicBounds(if (wide) null else ui.icon(glyph, if (filled) R.color.on_accent else R.color.fg), null, null, null)
        send.background = if (filled) filledAction else plainAction
    }

    private fun stopLabel() = if (AudioPlayback.active) "Stop speech"
        else if (app.hermesRuns.state.value.run?.active == true) "Stop task" else "Disconnect"

    /** The first-run and empty-chat card: what the button does, with an arrow toward it. */
    private fun buildEmpty() {
        findViewById<TextView>(R.id.empty_kicker).setCompoundDrawablesRelative(
            ui.icon(R.drawable.ic_hermes, R.color.accent).apply { setBounds(0, 0, dp(14), dp(14)) }, null, null, null)
        val legend = findViewById<LinearLayout>(R.id.empty_legend)
        listOf("Tap" to "Stop · sleep", "2× tap" to "New chat", "Wheel" to "Scroll · volume").forEach { (key, meaning) ->
            legend.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
                addView(ui.mono(key, R.color.accent, 12f, 800), LinearLayout.LayoutParams(dp(84), -2))
                addView(ui.mono(meaning, R.color.dim, 12f, 500), LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
    }

    private fun setupNeeded(): String? {
        val cfg = app.store.value
        return when {
            cfg.agent.baseUrl.isBlank() || cfg.agent.apiKey.isBlank() -> "Connect Hermes to start."
            cfg.missingSpeechKey -> "Add a speech key to start."
            else -> null
        }
    }

    private fun renderEmpty() {
        val empty = findViewById<LinearLayout>(R.id.empty)
        val showing = list.childCount == 0 && !imeVisible
        empty.visibility = if (showing) View.VISIBLE else View.GONE
        (scroll.getChildAt(0) as LinearLayout).gravity = if (showing) Gravity.CENTER_VERTICAL else Gravity.TOP
        val setup = setupNeeded()
        findViewById<TextView>(R.id.empty_body).text = setup ?: "Release to send it to $agent."
        findViewById<View>(R.id.empty_legend).visibility = if (setup == null) View.VISIBLE else View.GONE
        val existing = empty.findViewWithTag<Button>("setup")
        if (setup == null) { existing?.let(empty::removeView); return }
        if (existing == null) empty.addView(ui.button("Open settings", primary = true) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }.apply {
            tag = "setup"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })
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
        renderEmpty()
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

    /** Your words in an orange bubble; Hermes's replies full width under a small label. */
    private fun bubble(m: Msg) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = m
        if (m.role == Msg.USER) {
            gravity = Gravity.END
            setPadding(dp(40), dp(14), 0, dp(2))
            addView(TextView(this@HomeActivity).apply {
                text = m.text
                textSize = textSp
                setTextColor(getColor(R.color.on_accent))
                setTextIsSelectable(true)
                highlightColor = 0x40000000
                setLineSpacing(dp(2).toFloat(), 1f)
                val r = dp(22).toFloat()
                val tail = dp(6).toFloat()
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.accent))
                    cornerRadii = floatArrayOf(r, r, r, r, tail, tail, r, r)
                }
                setPadding(dp(16), dp(10), dp(16), dp(11))
            }, LinearLayout.LayoutParams(-2, -2))
        } else {
            setPadding(0, dp(16), 0, dp(4))
            addView(ui.mono("", R.color.accent, 11f, 800).apply {
                textSize = labelSp() // follows the transcript's size, not the settings scale
                isAllCaps = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = replyLabel(m)
                setCompoundDrawablesRelative(ui.icon(R.drawable.ic_hermes, R.color.accent).apply { setBounds(0, 0, dp(12), dp(12)) }, null, null, null)
                compoundDrawablePadding = dp(6)
            })
            addView(TextView(this@HomeActivity).apply {
                text = m.text
                textSize = textSp
                setTextColor(getColor(R.color.fg))
                setTextIsSelectable(true)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    /** The agent's name and when it replied: today's time, or the date for older replies. */
    private fun replyLabel(m: Msg) = SpannableStringBuilder(agent.uppercase()).apply {
        val time = DateUtils.formatDateTime(this@HomeActivity, m.at, DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(m.at)) 0
            else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
        append("  ·  ${time.uppercase()}", ForegroundColorSpan(getColor(R.color.faint)), 0)
    }

    // ---- menu ----

    private fun openMenu() {
        if (stage.showing) return
        menuSheet.removeAllViews()
        val menuScroll = android.widget.ScrollView(this).apply { addView(menuContent()) }
        menuSheet.addView(menuScroll, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        wheelScrollTarget = menuScroll
        menuSheet.visibility = View.VISIBLE
        menuSheet.alpha = 0f
        menuSheet.animate().alpha(1f).setDuration(140).start()
        menuSheet.getChildAt(0).apply { translationY = -dp(16).toFloat(); animate().translationY(0f).setDuration(200).start() }
    }

    private fun closeMenu() {
        if (menuSheet.visibility != View.VISIBLE) return
        wheelScrollTarget = scroll
        menuSheet.animate().alpha(0f).setDuration(120).withEndAction { menuSheet.visibility = View.GONE; menuSheet.removeAllViews() }.start()
    }

    private fun menuContent(): View = ui.column().apply {
        isClickable = true // taps between tiles don't close the menu
        setPadding(dp(14), dp(10), dp(14), dp(16))
        addView(LinearLayout(this@HomeActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(10))
            addView(ui.title("robotOS", 22f), LinearLayout.LayoutParams(-2, -2))
            addView(ui.mono(app.updates.installedVersion(), R.color.faint, 11f, 500).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            addView(ui.iconButton(R.drawable.ic_close, "Close menu") { closeMenu() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        val lastReply = app.history.messages.lastOrNull { it.role == Msg.ASSISTANT }
        fun open(type: Class<*>) = { startActivity(Intent(this@HomeActivity, type)) }
        fun tile(label: String, icon: Int, primary: Boolean = false, action: () -> Unit) =
            ui.navTile(label, icon, primary = primary) { closeMenu(); action() }
        addView(ui.grid(listOf(
            tile("New chat", R.drawable.ic_plus, primary = true) { app.turns.newConversation() },
            tile("Chats", R.drawable.ic_chats, action = open(ChatsActivity::class.java)),
            tile("Read reply", R.drawable.ic_speaker) {
                if (lastReply != null && !app.turns.speakText(lastReply.text)) showToast("Speech is unavailable while busy or quiet")
            }.apply { isEnabled = lastReply != null; alpha = if (lastReply != null) 1f else .4f },
            tile("Messages", R.drawable.ic_sms, action = open(dev.r1ptt.messages.MessagesActivity::class.java)),
            tile("Hermes", R.drawable.ic_hermes, action = open(dev.r1ptt.hermes.HermesActivity::class.java)),
            tile("Workspace", R.drawable.ic_chats, action = open(dev.r1ptt.workspace.WorkspaceActivity::class.java)),
            tile("Settings", R.drawable.ic_settings, action = open(SettingsActivity::class.java)),
        )))
    }

    // ---- Hermes task and notes ----

    private fun cardLabel(text: String, tint: Int) = SpannableStringBuilder().apply {
        append(text.uppercase(), TypefaceSpan(Fonts.mono(this@HomeActivity, 800)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(.7f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(getColor(tint)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append("\n")
    }

    private fun renderTask(state: HermesRunState) {
        drawTask(state)
        renderAction(shown())
        app.hermesRuns.confirmAdmittedDraft(app.history)
        app.hermesRuns.reconcile(app.history)
    }

    private fun drawTask(state: HermesRunState) {
        val card = findViewById<TextView>(R.id.hermes_task)
        val run = state.run
        card.visibility = if (run != null && (run.active || !run.delivered)) View.VISIBLE else View.GONE
        val urgent = run?.status == "waiting_for_approval"
        val trouble = run?.status in setOf("failed", "submission_unknown", "interrupted")
        val tint = when { urgent -> R.color.on_accent; trouble -> R.color.error; else -> R.color.accent }
        // An approval names what Hermes wants to do, so the decision is visible before it is opened.
        card.text = cardLabel(if (urgent) "Approval needed" else "$agent task", tint)
            .append(run?.approval?.description?.takeIf { urgent && it.isNotBlank() } ?: state.message.ifBlank { run?.label(agent).orEmpty() })
        card.setTextColor(getColor(if (urgent) R.color.on_accent else R.color.fg))
        card.background = ui.pressable(ui.background(if (urgent) R.color.accent else R.color.field, 18).apply {
            if (!urgent) setStroke(dp(1), getColor(if (trouble) R.color.error else R.color.outline))
        }, 18, onAccent = urgent)
        card.setCompoundDrawablesRelativeWithIntrinsicBounds(ui.icon(R.drawable.ic_hermes, tint), null,
            ui.icon(R.drawable.ic_chevron_right, if (urgent) R.color.on_accent else R.color.faint), null)
    }

    private fun renderNotices(notices: List<DeviceNotice>) {
        findViewById<TextView>(R.id.hermes_notice).apply {
            visibility = if (notices.isEmpty()) View.GONE else View.VISIBLE
            text = notices.lastOrNull()?.let {
                cardLabel("Note from $agent${if (notices.size > 1) " · ${notices.size}" else ""}", R.color.accent).append(it.text)
            } ?: ""
            contentDescription = "$text. Tap to read or dismiss"
            background = ui.pressable(ui.background(R.color.field, 18).apply { setStroke(dp(1), getColor(R.color.accent)) }, 18)
            setCompoundDrawablesRelativeWithIntrinsicBounds(ui.icon(R.drawable.ic_info, R.color.accent), null,
                ui.icon(R.drawable.ic_chevron_right, R.color.faint), null)
        }
    }

    private fun openNotice(notice: DeviceNotice) {
        val message = ui.text(notice.text).apply {
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val body = ScrollView(this).apply { addView(message) }
        val dialog = AlertDialog.Builder(this).setTitle("Note from $agent").setView(body)
            .setPositiveButton("Dismiss note") { _, _ ->
                runCatching { app.notices.dismiss(notice.id) }.onFailure { showToast("The note could not be dismissed") }
            }.setNegativeButton("Close", null)
        if (app.notices.state.value.size > 1) dialog.setNeutralButton("All notes") { _, _ ->
            val notes = app.notices.state.value.asReversed()
            showDeviceDialog(AlertDialog.Builder(this).setTitle("Notes from $agent")
                .setItems(notes.map { it.text.take(100) }.toTypedArray()) { _, index -> openNotice(notes[index]) }
                .setNegativeButton("Close", null))
        }
        showDeviceDialog(dialog)
    }

    /** Battery level and charge in the header pill; a Wi-Fi mark appears only while offline. */
    private fun renderConnectivity() {
        if (!resumed) return
        findViewById<TextView>(R.id.controls).apply {
            val battery = BatteryGlyph(this@HomeActivity).apply {
                percent = batteryLevel ?: -1
                charging = this@HomeActivity.charging
                setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            }
            val offline = if (networkAvailable) null else ui.icon(R.drawable.ic_wifi_off, R.color.dim).apply { setBounds(0, 0, dp(18), dp(18)) }
            setCompoundDrawablesRelative(offline, null, battery, null)
            contentDescription = "Quick controls. Battery ${batteryLevel ?: "unknown"} percent" +
                (if (charging) ", charging" else "") + if (networkAvailable) "" else ". No network"
        }
        if (!app.turns.state.value.phase.active) render(app.turns.state.value)
    }

    private fun applyConfig(c: Config) {
        textSp = c.textSizeSp.coerceIn(14, 24).toFloat()
        live.textSize = textSp
        input.textSize = textSp
        val name = c.agent.displayName
        input.hint = "Message $name"
        input.contentDescription = "Message to $name"
        findViewById<TextView>(R.id.empty_kicker).text = name
        findViewById<TextView>(R.id.live_label).text = name
        for (i in 0 until list.childCount) (list.getChildAt(i) as? LinearLayout)?.let { row ->
            val m = row.tag as? Msg
            if (m?.role == Msg.USER) (row.getChildAt(0) as? TextView)?.textSize = textSp
            else {
                (row.getChildAt(0) as? TextView)?.apply { textSize = labelSp(); if (m != null) text = replyLabel(m) }
                (row.getChildAt(1) as? TextView)?.textSize = textSp
            }
        }
        renderEmpty()
        renderNotices(app.notices.state.value)
        drawTask(app.hermesRuns.state.value)
    }

    /** Transcript labels follow the text size, but stay on one line at the largest sizes. */
    private fun labelSp() = 11f * (textSp / 17f).coerceAtMost(1.1f)

    private fun scrollToEnd() {
        scroll.post {
            if (!followLatest) return@post
            scroll.scrollTo(0, if (list.childCount == 0 && findViewById<View>(R.id.live_box).visibility != View.VISIBLE) 0 else scroll.getChildAt(0).height)
            findViewById<View>(R.id.latest).visibility = View.GONE
        }
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
