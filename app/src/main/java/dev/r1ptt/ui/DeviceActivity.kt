package dev.r1ptt.ui

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.AbsListView
import dev.r1ptt.PttService
import dev.r1ptt.audio.AudioPlayback

/** Common device chrome and contextual wheel handling, including framework dialogs. */
open class DeviceActivity : Activity() {
    protected val ui by lazy { DeviceUi(this) }
    var wheelScrollTarget: View? = null
    var wheelVolumeTarget: SeekBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onResume() {
        super.onResume()
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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

    fun handleWheel(event: KeyEvent, scrollTarget: View? = wheelScrollTarget): Boolean {
        if (!WheelPolicy.isWheel(event.keyCode, event.scanCode, event.device?.name)) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        val volume = wheelVolumeTarget?.takeIf { it.isShown && it.hasFocus() }
        if (AudioPlayback.active || volume != null) {
            val audio = getSystemService(AudioManager::class.java)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                if (volume == null) AudioManager.FLAG_SHOW_UI else 0)
            volume?.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        } else {
            val distance = ui.dp(if (up) -48 else 48)
            if (scrollTarget is AbsListView) scrollTarget.scrollListBy(distance) else scrollTarget?.scrollBy(0, distance)
        }
        return true
    }

    fun showDeviceDialog(builder: AlertDialog.Builder): AlertDialog = builder.create().also { dialog ->
        dialog.setOnKeyListener { _, _, event -> handleWheel(event, scrollable(dialog.window?.decorView)) }
        dialog.show()
    }

    private fun scrollable(view: View?): View? {
        if ((view is ScrollView || view is AbsListView) && view.isShown) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) scrollable(view.getChildAt(i))?.let { return it }
        return null
    }
}

object WheelPolicy {
    fun isWheel(keyCode: Int, scanCode: Int, deviceName: String?): Boolean {
        if (keyCode !in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return false
        return deviceName?.contains("och1970_holl", ignoreCase = true) == true ||
            (deviceName == null && (scanCode == 103 || scanCode == 108))
    }
}
