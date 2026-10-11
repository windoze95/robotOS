package dev.r1ptt

import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast
import dev.r1ptt.data.Config
import dev.r1ptt.data.ConfigPersistenceException
import dev.r1ptt.power.DeviceSettings
import dev.r1ptt.ui.DeviceActivity

/** Foreground-only controls. Brightness commits once after a gesture; no polling or radio checks. */
class QuickControlsActivity : DeviceActivity() {
    private val app get() = application as App
    private val main = Handler(Looper.getMainLooper())
    private var pendingBrightness: Int? = null
    private val commitBrightness = Runnable { flushBrightness() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = app.store.value
        val page = ui.page("Quick controls", ::finish)
        setContentView(page.root)
        wheelScrollTarget = page.scroll
        val body = page.body
        val level = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val network = if (app.radio.isOnline()) "Network available" else "Offline"
        body.addView(ui.text(if (level in 0..100) "$level% battery   $network" else network, 14f, true))
        body.addView(ui.section("Display"))
        val brightness = ui.slider("Brightness", SettingsActivity.brightnessPercent(cfg.power.brightness) - 1, 99,
            format = { "${it + 1}%" }, onPreview = { percent ->
                window.attributes = window.attributes.apply { screenBrightness = ((percent * 254 / 99) + 1) / 255f }
            }, onCommit = { percent ->
                pendingBrightness = (percent * 254 / 99) + 1
                main.removeCallbacks(commitBrightness)
                main.postDelayed(commitBrightness, 180)
            })
        body.addView(brightness.root)
        val audio = getSystemService(AudioManager::class.java)
        val volume = ui.slider("Speech volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            onPreview = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }, onCommit = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) })
        body.addView(volume.root)
        wheelVolumeTarget = volume.seek
        toggle(body, "Quiet mode", cfg.quiet) { on -> save { it.copy(quiet = on) }.also { if (it && on) app.turns.stopSpeech() } }
        toggle(body, "Speak replies", cfg.tts.enabled) { on -> save { it.copy(tts = it.tts.copy(enabled = on)) }.also { if (it && !on) app.turns.stopSpeech() } }
        toggle(body, "Stay reachable", cfg.power.stayReachable) { on -> save(radio = true) { it.copy(power = it.power.copy(stayReachable = on)) } }
        body.addView(ui.text("Stay reachable keeps radios on while on battery. Pocket mode lets them sleep.", 14f, true))
        body.addView(ui.row(ui.button("Wi-Fi") {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }.onFailure { toast("Wi-Fi settings unavailable") }
        }, ui.button("Sleep") { flushBrightness(); app.screen.sleepNow() }))
    }

    override fun onPause() {
        main.removeCallbacks(commitBrightness)
        flushBrightness()
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        super.onPause()
    }

    private fun flushBrightness() {
        val value = pendingBrightness ?: return
        pendingBrightness = null
        if (!save(device = true) { it.copy(power = it.power.copy(brightness = value)) }) window.attributes = window.attributes.apply { screenBrightness = -1f }
    }

    private fun toggle(body: LinearLayout, label: String, checked: Boolean, save: (Boolean) -> Boolean) {
        var changing = false
        val control = ui.toggle(label, checked)
        control.setOnCheckedChangeListener { _, on -> if (!changing && !save(on)) { changing = true; control.isChecked = !on; changing = false } }
        body.addView(control)
    }

    private fun save(device: Boolean = false, radio: Boolean = false, update: (Config) -> Config): Boolean = try {
        val next = app.store.update(update)
        if (device) DeviceSettings.apply(next)
        if (radio) app.radio.applyBaseline()
        true
    } catch (e: ConfigPersistenceException) { toast(e.message ?: "Settings could not be saved"); false }

    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
