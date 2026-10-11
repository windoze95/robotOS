package dev.r1ptt

import android.Manifest
import android.app.Activity
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
import kotlin.math.sqrt

/**
 * The launcher, and the only screen most people will see: status line, conversation, and a text
 * field. When the keyboard is up, the button dictates into the field instead of sending.
 */
class HomeActivity : Activity(), DictationTarget {
    private val app get() = application as App

    private lateinit var status: TextView
    private lateinit var meter: ProgressBar
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var live: TextView
    private lateinit var input: EditText

    private var ui: CoroutineScope? = null
    private var imeVisible = false
    private var resumed = false
    private var textSp = 17f
    private var renderedConv = ""
    private var lastRendered: Msg? = null
    /** Marks live dictation's provisional words in the text field (dim until the final transcript). */
    private val provisional by lazy { ForegroundColorSpan(getColor(R.color.dim)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        status = findViewById(R.id.status)
        meter = findViewById(R.id.meter)
        scroll = findViewById(R.id.scroll)
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
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.messages).setOnClickListener { startActivity(Intent(this, dev.r1ptt.messages.MessagesActivity::class.java)) }
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.isVisible(WindowInsets.Type.ime())
            if (ime != imeVisible) {
                imeVisible = ime
                render(app.turns.state.value) // the hint differs with the keyboard up
            }
            v.onApplyWindowInsets(insets)
        }
        // Volume keys (the wheel) adjust speech, not the ringer, even when nothing is playing.
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
        ui = MainScope().also { s ->
            s.launch { app.store.flow.collect(::applyConfig) }
            s.launch { app.history.flow.collect(::renderHistory) }
            s.launch { app.turns.state.collect(::render) }
        }
    }

    override fun onPause() {
        app.turns.detachTarget(this)
        resumed = false
        visible = false
        ui?.cancel()
        ui = null
        super.onPause()
    }

    override fun onDestroy() {
        app.turns.detachTarget(this)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasMic()) PttService.start(this) else status.text = getString(R.string.need_mic)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            // The side button, remapped by the Magisk keylayout. The root reader normally handles
            // it; this path only matters while that reader is down.
            KeyEvent.KEYCODE_BUTTON_1 -> {
                if (event.repeatCount == 0) PttService.instance?.frameworkButton(event.action == KeyEvent.ACTION_DOWN)
                return true
            }
            // The scroll wheel, remapped to volume by the Magisk keylayout: always the speech volume.
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    getSystemService(AudioManager::class.java).adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI,
                    )
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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
        if (app.store.loadError != null) { render(app.turns.state.value); return false }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return false
        input.text.clear()
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

    private fun render(s: TurnState) {
        status.setTextColor(getColor(if (s.phase == Phase.ERROR) R.color.error else R.color.accent))
        status.text = when (s.phase) {
            Phase.IDLE -> s.note.ifEmpty { idleHint() }
            Phase.LISTENING -> if (s.note.isNotEmpty()) "Listening · ${s.note.lowercase()}" else "Listening… release to send"
            Phase.DICTATING -> "Dictating… release to insert"
            Phase.TRANSCRIBING -> "Transcribing…"
            Phase.THINKING -> s.note.ifEmpty { "Thinking…" }
            Phase.ANSWERING -> s.note.ifEmpty { app.store.value.provider.label }
            Phase.SPEAKING -> "Speaking · tap to stop"
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
            scrollToEnd()
        } else {
            live.visibility = View.GONE
        }
        // The screen stays on for a turn; otherwise the short system timeout turns it off.
        if (s.phase.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun idleHint(): String {
        app.store.loadError?.let { return it }
        val cfg = app.store.value
        return when {
            cfg.provider.apiKey.isBlank() && cfg.provider.id == "openai" -> getString(R.string.need_key)
            imeVisible -> "Hold to dictate · tap to send"
            else -> "Hold the side button to talk"
        }
    }

    private fun renderHistory(snap: History.Snapshot) {
        val msgs = snap.messages
        val from = msgs.indexOfLast { it === lastRendered }
        if (snap.convId != renderedConv || from < 0 && lastRendered != null || list.childCount > 150) {
            list.removeAllViews()
            renderedConv = snap.convId
            lastRendered = null
        }
        val start = if (lastRendered == null) 0 else msgs.indexOfLast { it === lastRendered } + 1
        for (m in msgs.drop(start)) list.addView(bubble(m))
        lastRendered = msgs.lastOrNull()
        scrollToEnd()
    }

    private fun bubble(m: Msg) = TextView(this).apply {
        val user = m.role == Msg.USER
        text = m.text
        textSize = textSp
        setTextColor(getColor(if (user) R.color.dim else R.color.fg))
        gravity = if (user) Gravity.END else Gravity.START
        setPadding(if (user) dp(32) else 0, dp(7), if (user) 0 else dp(16), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun applyConfig(c: Config) {
        if (c.textSizeSp.toFloat() == textSp) return
        textSp = c.textSizeSp.toFloat()
        live.textSize = textSp
        for (i in 0 until list.childCount) (list.getChildAt(i) as? TextView)?.textSize = textSp
    }

    private fun scrollToEnd() {
        scroll.post { scroll.scrollTo(0, scroll.getChildAt(0).height) }
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
