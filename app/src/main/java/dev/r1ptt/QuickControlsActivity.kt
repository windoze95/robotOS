package dev.r1ptt

import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.CompoundButton
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
        body.addView(ui.mono("", R.color.fg, 13f, 700).apply {
            text = readout()
            setPadding(ui.dp(4), 0, 0, ui.dp(12))
        })
        body.addView(ui.grid(listOf(
            tile("Quiet mode", R.drawable.ic_quiet, cfg.quiet) { on -> save { it.copy(quiet = on) }.also { if (it && on) app.turns.stopSpeech() } },
            tile("Speak replies", R.drawable.ic_wave, cfg.tts.enabled) { on -> save { it.copy(tts = it.tts.copy(enabled = on)) }.also { if (it && !on) app.turns.stopSpeech() } },
            tile("Stay reachable", R.drawable.ic_reach, cfg.power.reachableOnBattery) { on -> save(radio = true) { it.copy(power = it.power.withReachability(on)) } },
            ui.navTile("Wi-Fi", R.drawable.ic_wifi, "Android Wi-Fi settings") {
                runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }.onFailure { showToast("Wi-Fi settings unavailable") }
            },
        ), height = 96))
        body.addView(ui.note("Stay reachable keeps radios on while on battery. Pocket mode lets them sleep."))
        val brightness = ui.slider("Brightness", SettingsActivity.brightnessPercent(cfg.power.brightness) - 1, 99,
            format = { "${it + 1}%" }, icon = R.drawable.ic_sun, onPreview = { percent ->
                window.attributes = window.attributes.apply { screenBrightness = ((percent * 254 / 99) + 1) / 255f }
            }, onCommit = { percent ->
                pendingBrightness = (percent * 254 / 99) + 1
                main.removeCallbacks(commitBrightness)
                main.postDelayed(commitBrightness, 180)
            })
        body.addView(brightness.root)
        val audio = getSystemService(AudioManager::class.java)
        val volume = ui.slider("Speech volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            icon = R.drawable.ic_speaker,
            onPreview = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }, onCommit = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) })
        body.addView(volume.root)
        wheelVolumeTarget = volume.seek
        body.addView(ui.note("Select a slider to adjust it with the wheel."))
        body.addView(ui.button("Sleep", primary = true) { flushBrightness(); app.screen.sleepNow() })
    }

    override fun onPause() {
        main.removeCallbacks(commitBrightness)
        flushBrightness()
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        super.onPause()
    }

    /** Battery, charge and network at a glance, read once when the screen opens. */
    private fun readout(): CharSequence {
        val battery = getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val online = app.radio.isOnline()
        return SpannableStringBuilder().apply {
            if (level in 0..100) append("$level% battery", ForegroundColorSpan(getColor(if (level <= 15) R.color.error else R.color.fg)), 0)
            if (battery.isCharging) append("  ·  charging", ForegroundColorSpan(getColor(R.color.accent)), 0)
            if (isNotEmpty()) append("  ·  ")
            append(if (online) "online" else "offline", ForegroundColorSpan(getColor(if (online) R.color.ok else R.color.dim)), 0)
        }
    }

    private fun flushBrightness() {
        val value = pendingBrightness ?: return
        pendingBrightness = null
        if (!save(device = true) { it.copy(power = it.power.copy(brightness = value)) }) window.attributes = window.attributes.apply { screenBrightness = -1f }
    }

    /** A tile that reverts itself when its setting could not be saved. */
    private fun tile(label: String, glyph: Int, checked: Boolean, save: (Boolean) -> Boolean): CompoundButton {
        var changing = false
        return ui.tile(label, glyph, checked).apply {
            setOnCheckedChangeListener { control, on -> if (!changing && !save(on)) { changing = true; control.isChecked = !on; changing = false } }
        }
    }

    private fun save(device: Boolean = false, radio: Boolean = false, update: (Config) -> Config): Boolean = try {
        val next = app.store.update(update)
        if (device) DeviceSettings.apply(next)
        if (radio) app.radio.applyBaseline()
        true
    } catch (e: ConfigPersistenceException) { showToast(e.message ?: "Settings could not be saved"); false }
}
