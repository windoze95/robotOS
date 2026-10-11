package dev.r1ptt.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.AbsListView
import android.widget.TextView
import dev.r1ptt.PttService
import dev.r1ptt.R
import dev.r1ptt.audio.AudioPlayback

/** Common device chrome and contextual wheel handling, including framework dialogs. */
open class DeviceActivity : Activity() {
    protected val ui by lazy { DeviceUi(this) }
    var wheelScrollTarget: View? = null
    var wheelVolumeTarget: SeekBar? = null
    private var resumedNow = false
    private var toastView: TextView? = null
    private var volumeHud: VolumeHud? = null
    private val dismissToast = Runnable { hideToast() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        DeviceScale.override(newBase.resources.configuration)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.decorView.post {
            pendingToast?.takeIf { resumedNow }?.let { (message, at) ->
                pendingToast = null
                if (SystemClock.uptimeMillis() - at < 4000) showToast(message)
            }
        }
    }

    override fun onPause() {
        resumedNow = false
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_1) {
            if (event.repeatCount == 0) PttService.instance?.frameworkButton(event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return handleWheel(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) wheelVolumeTarget?.takeIf { it.hasFocus() }?.let { slider ->
            val bounds = android.graphics.Rect()
            slider.getGlobalVisibleRect(bounds)
            if (!bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                slider.clearFocus()
                findViewById<View>(android.R.id.content).requestFocus()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    fun handleWheel(event: KeyEvent, scrollTarget: View? = wheelScrollTarget, inDialog: Boolean = false): Boolean {
        if (!WheelPolicy.isWheel(event.keyCode, event.scanCode, event.device?.name)) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        val volume = wheelVolumeTarget?.takeIf { it.isShown && it.hasFocus() }
        if (AudioPlayback.active || volume != null) {
            val audio = getSystemService(AudioManager::class.java)
            // A dialog covers this window, so the system readout is the one that stays visible.
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                if (volume == null && inDialog) AudioManager.FLAG_SHOW_UI else 0)
            val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            volume?.progress = level
            if (volume == null && !inDialog) showVolume(level, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        } else {
            val distance = ui.dp(if (up) -48 else 48)
            if (scrollTarget is AbsListView) scrollTarget.scrollListBy(distance) else scrollTarget?.scrollBy(0, distance)
        }
        return true
    }

    fun showDeviceDialog(builder: AlertDialog.Builder): AlertDialog = builder.create().also { dialog ->
        dialog.setOnKeyListener { _, _, event -> handleWheel(event, scrollable(dialog.window?.decorView), inDialog = true) }
        dialog.show()
    }

    /**
     * A short message in robotOS's own style. It is placed after the current event, so a message
     * followed by finish() or a page change still shows, on whichever screen is in front.
     */
    fun showToast(message: CharSequence) {
        val at = SystemClock.uptimeMillis()
        window.decorView.post {
            if (isFinishing || isDestroyed || !resumedNow) pendingToast = message to at else displayToast(message)
        }
    }

    private fun displayToast(message: CharSequence) {
        val content = findViewById<FrameLayout>(android.R.id.content) ?: return
        val view = toastView ?: TextView(this).also { view ->
            toastView = view
            view.textSize = 16f
            view.typeface = Fonts.display(this, 500)
            view.setTextColor(getColor(R.color.fg))
            view.setLineSpacing(ui.dp(2).toFloat(), 1f)
            view.background = ui.background(R.color.raised, 22).apply { setStroke(ui.dp(1), getColor(R.color.outline)) }
            view.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(ui.dot(R.color.accent, 9), null, null, null)
            view.compoundDrawablePadding = ui.dp(12)
            view.elevation = ui.dp(12).toFloat()
            view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            view.setOnClickListener { hideToast() }
        }
        // setContentView() replaces the content, so the message rejoins the current page each time.
        if (view.parent !== content) {
            (view.parent as? ViewGroup)?.removeView(view)
            content.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                setMargins(ui.dp(12), ui.dp(10), ui.dp(12), 0)
            })
        }
        view.bringToFront()
        view.text = message
        view.removeCallbacks(dismissToast)
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = -ui.dp(14).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(180).start()
        view.postDelayed(dismissToast, (2400L + message.length * 35L).coerceAtMost(6000L))
    }

    private fun hideToast() {
        val view = toastView ?: return
        view.removeCallbacks(dismissToast)
        view.animate().alpha(0f).translationY(-ui.dp(10).toFloat()).setDuration(160)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    private fun showVolume(level: Int, max: Int) {
        val content = findViewById<FrameLayout>(android.R.id.content) ?: return
        val hud = volumeHud ?: VolumeHud(this).also { volumeHud = it }
        if (hud.parent !== content) {
            (hud.parent as? ViewGroup)?.removeView(hud)
            content.addView(hud, FrameLayout.LayoutParams(ui.dp(50), ui.dp(188), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = ui.dp(10)
            })
        }
        hud.bringToFront()
        hud.show(level, max)
    }

    private fun scrollable(view: View?): View? {
        if ((view is ScrollView || view is AbsListView) && view.isShown) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) scrollable(view.getChildAt(i))?.let { return it }
        return null
    }

    private companion object {
        var pendingToast: Pair<CharSequence, Long>? = null
    }
}

object WheelPolicy {
    fun isWheel(keyCode: Int, scanCode: Int, deviceName: String?): Boolean {
        if (keyCode !in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return false
        return deviceName?.contains("och1970_holl", ignoreCase = true) == true ||
            (deviceName == null && (scanCode == 103 || scanCode == 108))
    }
}

/**
 * The R1's 2.88-inch panel is about 278 ppi, but Android runs at 190 dpi so its own screens fit.
 * robotOS lays itself out on a [CANVAS_DP]-wide canvas instead, so its type and touch targets keep
 * their intended physical size. It never shrinks a screen and never scales by more than half again.
 */
object DeviceScale {
    const val CANVAS_DP = 300

    fun override(base: Configuration): Configuration? {
        val dpi = base.densityDpi
        val narrow = base.smallestScreenWidthDp
        if (dpi <= 0 || narrow <= 0 || narrow == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) return null
        val target = (narrow * dpi / CANVAS_DP).coerceAtMost(dpi * 3 / 2)
        if (target <= dpi) return null
        return Configuration().apply {
            densityDpi = target
            smallestScreenWidthDp = narrow * dpi / target
            if (base.screenWidthDp > 0) screenWidthDp = base.screenWidthDp * dpi / target
            if (base.screenHeightDp > 0) screenHeightDp = base.screenHeightDp * dpi / target
        }
    }
}
