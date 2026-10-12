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
    private var gate: FrameLayout? = null
    private var gateCover: View? = null
    private var gatePage: View? = null
    private var gatePageScroll: View? = null
    private var gatePageKey = ""
    private var gateCovering = false
    private val liftLater = Runnable { applyRecoveryGate() }
    private val deviceDialogs = mutableListOf<AlertDialog>()
    internal val hasOpenDialog: Boolean get() = deviceDialogs.any { it.isShowing }
    private val recoveryApp get() = application as? dev.r1ptt.App
    private fun recoveryBlocksScreen() = recoveryApp?.workspace?.let { hub ->
        hub.blocked && !(this is dev.r1ptt.workspace.HubSettingsActivity && hub.canReenroll)
    } == true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        DeviceScale.override(newBase.resources.configuration)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        activities.add(this)
        if (recoveryApp?.workspace?.identity?.paired == true) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener { applyRecoveryGate() }
    }

    override fun onStart() {
        super.onStart()
        if (startedActivities++ == 0) {
            // robotOS is coming into view (a wake or a return from another app). A recovery screen
            // shown now starts on the logo again, never on the page it was left on.
            restartCover()
            recoveryApp?.workspace?.wake()
        }
    }
    override fun onStop() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        resumedNow = true
        recoveryApp?.let { app ->
            if (app.workspace.identity.paired) {
                app.workspace.recoverNow()
                app.workspace.management.refreshProtection()
            }
        }
        applyRecoveryGate()
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

    override fun onDestroy() {
        activities.remove(this)
        phases.removeCallbacks(liftLater)
        deviceDialogs.toList().also { deviceDialogs.clear() }.forEach { it.dismiss() }
        super.onDestroy()
    }

    /**
     * Covers every native screen and hides its accessibility tree, including saved history. It
     * opens on the glowing logo; the recovery page fades in only if the check is still running
     * after [COVER_MS], so a normal wake goes logo, then the screen, with nothing in between.
     */
    internal fun applyRecoveryGate() {
        val app = recoveryApp ?: return
        val hub = app.workspace
        val content = findViewById<View>(android.R.id.content) ?: return
        val blocked = recoveryBlocksScreen()
        noteGate(blocked)
        if (!blocked) { liftGate(content); return }
        // This runs on every layout pass. Hiding content is idempotent; window flags and keyboard
        // requests are not (each one relayouts and calls back here), so they run only when the
        // gate first appears or changes. Otherwise the gate spins at frame rate.
        phases.removeCallbacks(liftLater)
        content.visibility = View.INVISIBLE
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val identity = hub.identity.value
        val label = if (hub.quarantined) "Device access restricted" else "Recovery verification required"
        val detail = if (hub.quarantined) identity.optString("lost_message").ifBlank {
            "This R1 is restricted. Restore access from your robotOS host console."
        } else if (!app.radio.isOnline()) "No internet connection. robotOS opens as soon as this R1 reaches your host, " +
            "and keeps trying while the screen is on. Saved notes and recordings remain on this R1."
        else "Waiting for your host to confirm this R1 hasn't been reported lost. robotOS keeps trying while the " +
            "screen is on. Saved notes and recordings remain on this R1."
        // A restricted R1 can be rung by its owner at any time; otherwise offer this only while
        // ringing. The side button also stops ringing.
        val ringing = hub.quarantined || hub.management.ringing
        val key = listOf(label, detail, identity.optString("return_message"), identity.optBoolean("secure_lock_applied"),
            hub.canReenroll, ringing).joinToString("\u0000")
        val covering = covering()
        val shell = gate
        if (shell != null && gatePageKey == key && gateCovering == covering) return
        if (shell == null || gatePageKey != key) {
            val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
            if (window.attributes.flags and secure == 0) window.addFlags(secure)
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(content.windowToken, 0)
            content.clearFocus()
            deviceDialogs.toList().also { deviceDialogs.clear() }.forEach { it.dismiss() }
        }
        // Fade only over a screen someone is looking at; on a wake the first frame is the cover.
        val looking = resumedNow && hasWindowFocus()
        val root = shell ?: FrameLayout(this).also { root ->
            root.setBackgroundColor(getColor(R.color.bg))
            gateCover = LogoGlow(this).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
            gate = root
            (window.decorView as ViewGroup).addView(root, ViewGroup.LayoutParams(-1, -1))
            if (looking) { root.alpha = 0f; root.animate().alpha(1f).setDuration(FADE_MS).start() }
        }
        root.bringToFront()
        if (gatePageKey != key) {
            gatePage?.let(root::removeView)
            val page = ui.page("Device recovery") {}
            page.root.setBackgroundColor(getColor(R.color.bg))
            page.body.addView(ui.title(label))
            page.body.addView(ui.note(detail))
            if (hub.quarantined) {
                identity.optString("return_message").takeIf(String::isNotBlank)?.let { page.body.addView(ui.text(it)) }
                page.body.addView(ui.note(if (identity.optBoolean("secure_lock_applied")) "Android secure lock was requested. robotOS remains restricted until restored by the owner."
                    else "robotOS is restricted. A secure Android screen lock is not active through robotOS; this does not lock other Android apps or protect against physical system access."))
            }
            page.body.addView(ui.button("Check recovery status", primary = true) { hub.recoverNow() })
            page.body.addView(ui.button("Connect to a network") {
                startActivity(android.content.Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            })
            if (hub.canReenroll) page.body.addView(ui.button("Pair this cleared R1") {
                startActivity(android.content.Intent(this, dev.r1ptt.workspace.HubSettingsActivity::class.java))
            })
            if (ringing) page.body.addView(ui.button("Stop ringing") { hub.management.stopRinging() })
            page.root.visibility = if (covering) View.GONE else View.VISIBLE
            root.addView(page.root, FrameLayout.LayoutParams(-1, -1))
            gatePage = page.root; gatePageScroll = page.scroll; gatePageKey = key
        }
        val smooth = looking && shell != null && gateCovering != covering
        gateCovering = covering
        gateCover?.fade(covering, smooth)
        gatePage?.fade(!covering, smooth)
    }

    private fun liftGate(content: View) {
        val root = gate
        if (root == null) {
            content.visibility = View.VISIBLE
            content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            return
        }
        // A check that passes at once still gets a clean beat of logo, never a one-frame flash.
        val hold = coverHold()
        if (gateCovering && hold > 0) {
            phases.removeCallbacks(liftLater)
            phases.postDelayed(liftLater, hold)
            return
        }
        content.visibility = View.VISIBLE
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        gate = null; gateCover = null; gatePage = null; gatePageScroll = null; gatePageKey = ""; gateCovering = false
        val parent = root.parent as? ViewGroup ?: return
        if (resumedNow && hasWindowFocus()) root.animate().alpha(0f).setDuration(FADE_MS).withEndAction { parent.removeView(root) }.start()
        else parent.removeView(root)
    }

    private fun View.fade(show: Boolean, smooth: Boolean) {
        animate().cancel()
        if (!smooth) { alpha = 1f; visibility = if (show) View.VISIBLE else View.GONE; return }
        if (show) {
            if (visibility != View.VISIBLE) { alpha = 0f; visibility = View.VISIBLE }
            animate().alpha(1f).setDuration(FADE_MS).start()
        } else if (visibility == View.VISIBLE) animate().alpha(0f).setDuration(FADE_MS).withEndAction { visibility = View.GONE }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (recoveryBlocksScreen()) {
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_1 && event.action == KeyEvent.ACTION_DOWN)
                recoveryApp?.workspace?.management?.stopRinging()
            handleWheel(event, gatePageScroll)
            return true
        }
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
        deviceDialogs.add(dialog)
        dialog.setOnDismissListener { deviceDialogs.remove(dialog) }
        dialog.setOnKeyListener { _, _, event -> handleWheel(event, scrollable(dialog.window?.decorView), inDialog = true) }
        if (recoveryApp?.workspace?.identity?.paired == true) dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        if (recoveryBlocksScreen()) dialog.dismiss()
    }

    /**
     * A short message in robotOS's own style. It is placed after the current event, so a message
     * followed by finish() or a page change still shows, on whichever screen is in front.
     */
    fun showToast(message: CharSequence) {
        if (recoveryBlocksScreen()) return
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

    companion object {
        /** The logo holds the screen this long before the recovery page fades in. */
        internal const val COVER_MS = 3_500L
        /** The shortest logo beat once shown, so a check that passes at once never flickers. */
        private const val MIN_COVER_MS = 900L
        private const val FADE_MS = 220L
        private var coverFrom = 0L
        private var gateUp = false
        private val phases = android.os.Handler(android.os.Looper.getMainLooper())
        private val showPage = Runnable { activities.toList().forEach { if (!it.isDestroyed) it.applyRecoveryGate() } }
        /** Starts the logo beat again: when the gate comes up and whenever robotOS comes into view. */
        private fun restartCover() {
            coverFrom = SystemClock.uptimeMillis()
            phases.removeCallbacks(showPage)
            phases.postDelayed(showPage, COVER_MS)
        }
        private fun noteGate(blocked: Boolean) {
            if (blocked && !gateUp) restartCover()
            gateUp = blocked
        }
        private fun covering() = SystemClock.uptimeMillis() - coverFrom < COVER_MS
        private fun coverHold() = coverFrom + MIN_COVER_MS - SystemClock.uptimeMillis()
        private var startedActivities = 0
        private val activities = java.util.Collections.newSetFromMap(java.util.WeakHashMap<DeviceActivity, Boolean>())
        /** Main-thread navigation may only originate from the currently resumed robotOS screen. */
        internal fun foregroundActivity(): DeviceActivity? = activities.firstOrNull { it.resumedNow && !it.isFinishing && !it.isDestroyed }
        fun refreshRecovery(app: dev.r1ptt.App) {
            val update = {
                if (app.workspace.blocked) {
                    PttService.instance?.cancelPressForRecovery()
                    app.deviceApi.stop(); app.turns.cancelTurn(); app.turns.stopSpeech(); app.updates.restrict()
                    dev.r1ptt.workspace.WorkspaceActivity.pauseForRecovery()
                }
                if (app.workspace.quarantined) {
                    app.getSystemService(android.app.NotificationManager::class.java).cancel(dev.r1ptt.messages.SmsController.NOTIFICATION)
                    dev.r1ptt.workspace.WorkspaceActivity.quarantineActive()
                }
                activities.toList().forEach { if (!it.isDestroyed) it.applyRecoveryGate() }
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) update()
            else android.os.Handler(android.os.Looper.getMainLooper()).post { update() }
        }
        private var pendingToast: Pair<CharSequence, Long>? = null
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
