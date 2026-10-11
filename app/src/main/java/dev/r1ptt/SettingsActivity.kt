package dev.r1ptt

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import dev.r1ptt.data.Config
import dev.r1ptt.data.ConfigPersistenceException
import dev.r1ptt.power.DeviceSettings
import dev.r1ptt.ui.DeviceActivity
import dev.r1ptt.update.UpdateActivity
import java.net.URI

/** Everyday controls are separate from credentials; each save merges only the fields edited. */
class SettingsActivity : DeviceActivity() {
    private val app get() = application as App
    private lateinit var body: LinearLayout
    private var current = Page.INDEX
    private var dirty: () -> Boolean = { false }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); show(Page.INDEX) }

    @Deprecated("Platform Activity back handling")
    override fun onBackPressed() = back()

    private fun back() {
        if (dirty()) showDeviceDialog(AlertDialog.Builder(this).setTitle("Discard unsaved changes?")
            .setPositiveButton("Discard") { _, _ -> show(Page.INDEX) }.setNegativeButton("Keep editing", null))
        else if (current == Page.INDEX) finish() else show(Page.INDEX)
    }

    private fun show(next: Page) {
        current = next
        dirty = { false }
        wheelVolumeTarget = null
        if (next == Page.CONNECTION || next == Page.SPEECH) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val page = ui.page(next.title, ::back)
        body = page.body
        setContentView(page.root)
        wheelScrollTarget = page.scroll
        when (next) {
            Page.INDEX -> index()
            Page.DISPLAY -> display()
            Page.SOUND -> sound()
            Page.POWER -> power()
            Page.CONNECTION -> connection()
            Page.SPEECH -> speech()
            Page.SYSTEM -> system()
        }
    }

    private fun index() {
        app.store.loadError?.let {
            body.addView(ui.text(it, 15f).apply { setTextColor(getColor(R.color.error)); setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(10)) })
            body.addView(ui.button("Retry loading settings", primary = true) {
                if (app.store.retryReload()) show(Page.INDEX) else showToast(app.store.loadError.orEmpty())
            })
        }
        body.addView(ui.grid(listOf(
            ui.navTile("Quick controls", R.drawable.ic_sliders, "Brightness, volume and quiet mode") { startActivity(Intent(this, QuickControlsActivity::class.java)) },
            ui.navTile("Display & controls", R.drawable.ic_display, "Text size, screen timeout and button guide") { show(Page.DISPLAY) },
            ui.navTile("Sound", R.drawable.ic_speaker, "Spoken replies and listening sounds") { show(Page.SOUND) },
            ui.navTile("Connectivity & power", R.drawable.ic_wifi, "Wi-Fi, cellular and reachability") { show(Page.POWER) },
            ui.navTile("Hermes", R.drawable.ic_hermes, "Connection, tasks and device access") { startActivity(Intent(this, dev.r1ptt.hermes.HermesActivity::class.java)) },
            ui.navTile("Connections", R.drawable.ic_server, "Hermes address and credentials") { show(Page.CONNECTION) },
            ui.navTile("Speech services", R.drawable.ic_voice, "Transcription, voice and credentials") { show(Page.SPEECH) },
            ui.navTile("Host & devices", R.drawable.ic_server, "Shared workspace and recovery permissions") { startActivity(Intent(this, dev.r1ptt.workspace.HubSettingsActivity::class.java)) },
            ui.navTile("System", R.drawable.ic_chip, "Battery, app updates and power") { show(Page.SYSTEM) },
        ), height = 100))
    }

    private fun display() {
        val cfg = app.store.value
        body.addView(ui.actionRow("Brightness", "${brightnessPercent(cfg.power.brightness)}%", R.drawable.ic_sun) { startActivity(Intent(this, QuickControlsActivity::class.java)) })
        body.addView(ui.actionRow("Text size", "${cfg.textSizeSp} sp", R.drawable.ic_text_size) {
            val sizes = intArrayOf(15, 17, 20, 23)
            choose("Text size", sizes.map { "$it sp" }.toTypedArray()) { index ->
                if (save { it.copy(textSizeSp = sizes[index]) }) show(Page.DISPLAY)
            }
        })
        body.addView(ui.actionRow("Screen timeout", "${cfg.power.screenTimeoutSec} seconds", R.drawable.ic_moon) {
            val seconds = intArrayOf(10, 15, 30, 60, 120)
            choose("Screen timeout", seconds.map { "$it seconds" }.toTypedArray()) { index ->
                if (save(device = true) { it.copy(power = it.power.copy(screenTimeoutSec = seconds[index])) }) show(Page.DISPLAY)
            }
        })
        body.addView(ui.section("Side button"))
        body.addView(guide("Hold" to "Talk. Release to send.", "Hold + keyboard" to "Dictate into your draft.", "Tap" to "Stop an answer, or sleep. A tap that wakes the screen only wakes it.", "Double-tap" to "Start a new chat."))
        body.addView(ui.section("Scroll wheel"))
        body.addView(guide("Turn" to "Scroll the page.", "While speaking" to "Change speech volume.", "Slider selected" to "Adjust it in Quick controls."))
        body.addView(ui.actionRow("Android accessibility", "System font, magnification and accessibility services", R.drawable.ic_settings) { openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
    }

    private fun sound() {
        val cfg = app.store.value
        toggle("Quiet mode", cfg.quiet) { on -> save { it.copy(quiet = on) }.also { if (it && on) app.turns.stopSpeech() } }
        body.addView(ui.note("Quiet mode silences spoken replies and listening sounds without changing your sound preferences."))
        toggle("Speak replies", cfg.tts.enabled) { on -> save { it.copy(tts = it.tts.copy(enabled = on)) }.also { if (it && !on) app.turns.stopSpeech() } }
        toggle("Listening sounds", cfg.earcons) { on -> save { it.copy(earcons = on) } }
        body.addView(ui.actionRow("Speech volume", "Adjust with the slider or scroll wheel", R.drawable.ic_speaker) { startActivity(Intent(this, QuickControlsActivity::class.java)) })
    }

    private fun power() {
        val cfg = app.store.value
        body.addView(ui.actionRow("Wi-Fi", if (app.radio.isOnline()) "A network is available" else "No network connection",
            if (app.radio.isOnline()) R.drawable.ic_wifi else R.drawable.ic_wifi_off) { openSystem(Settings.ACTION_WIFI_SETTINGS) })
        toggle("Stay reachable on battery", cfg.power.reachableOnBattery) { on -> save(radio = true) { it.copy(power = it.power.withReachability(on)) } }
        body.addView(ui.note("Keeps the radios available for ${cfg.agent.displayName} and incoming texts. Uses more battery. On external power, radios stay available automatically."))
        body.addView(ui.actionRow("Radio sleep delay", "${cfg.power.pocketIdleMinutes} minutes in pocket mode", R.drawable.ic_reach) {
            val minutes = intArrayOf(1, 3, 5, 10)
            choose("Radio sleep delay", arrayOf("1 minute", "3 minutes", "5 minutes", "10 minutes")) { index ->
                if (save(radio = true) { it.copy(power = it.power.normalized().copy(wifiIdleMinutes = minutes[index])) }) show(Page.POWER)
            }
        })
        toggle("Use cellular data", cfg.power.cellular) { on -> save(radio = true) { it.copy(power = it.power.copy(cellular = on)) } }
        body.addView(ui.note("Requires a SIM. In pocket mode the modem sleeps with Wi-Fi; texts may wait until the device wakes."))
    }

    private fun connection() {
        val before = app.store.value.agent
        body.addView(ui.note("Your R1 sends conversations to this Hermes server."))
        val url = field("Hermes server URL", before.baseUrl, uri = true)
        val key = field("Hermes API key", before.apiKey, secret = true)
        val model = field("Model", before.model)
        dirty = { url.value() != before.baseUrl || key.value() != before.apiKey || model.value() != before.model }
        body.addView(ui.button("Save connection", primary = true) {
            if (!url.validUrl()) return@button
            if (model.value().isBlank()) { model.error = "Enter a model"; return@button }
            if (save { c -> c.copy(agent = c.agent.copy(
                baseUrl = changed(url, before.baseUrl, c.agent.baseUrl), apiKey = changed(key, before.apiKey, c.agent.apiKey),
                model = changed(model, before.model, c.agent.model),
            )) }) { showToast("Connection saved"); show(Page.INDEX) }
        })
    }

    private fun speech() {
        val before = app.store.value
        body.addView(ui.note("Credentials stay on this device. Online dictation sends audio to the configured transcription service."))
        val key = field("OpenAI speech API key", before.openaiKey, secret = true)
        body.addView(ui.section("Transcription"))
        val sttUrl = field("Transcription URL", before.stt.baseUrl, uri = true)
        val sttModel = field("Transcription model", before.stt.model)
        val live = ui.toggle("Show words as you speak", before.sttLive).also(body::addView)
        body.addView(ui.section("Spoken replies"))
        val ttsUrl = field("Speech URL", before.tts.endpoint.baseUrl, uri = true)
        val ttsModel = field("Speech model", before.tts.endpoint.model)
        val voice = field("Voice", before.tts.voice)
        dirty = { key.value() != before.openaiKey || sttUrl.value() != before.stt.baseUrl || sttModel.value() != before.stt.model ||
            live.isChecked != before.sttLive || ttsUrl.value() != before.tts.endpoint.baseUrl || ttsModel.value() != before.tts.endpoint.model || voice.value() != before.tts.voice }
        body.addView(ui.button("Save speech services", primary = true) {
            if (!sttUrl.validUrl() || !ttsUrl.validUrl()) return@button
            if (save { c -> c.copy(
                openaiKey = changed(key, before.openaiKey, c.openaiKey),
                stt = c.stt.copy(baseUrl = changed(sttUrl, before.stt.baseUrl, c.stt.baseUrl), model = changed(sttModel, before.stt.model, c.stt.model)),
                sttLive = if (live.isChecked != before.sttLive) live.isChecked else c.sttLive,
                tts = c.tts.copy(voice = changed(voice, before.tts.voice, c.tts.voice), endpoint = c.tts.endpoint.copy(
                    baseUrl = changed(ttsUrl, before.tts.endpoint.baseUrl, c.tts.endpoint.baseUrl), model = changed(ttsModel, before.tts.endpoint.model, c.tts.endpoint.model))),
            ) }) { showToast("Speech services saved"); show(Page.INDEX) }
        })
    }

    private fun system() {
        body.addView(ui.actionRow("App updates", "robotOS ${app.updates.installedVersion()}", R.drawable.ic_download) { startActivity(Intent(this, UpdateActivity::class.java)) })
        body.addView(ui.actionRow("Battery record", "Measured screen-off intervals", R.drawable.ic_battery) {
            showDeviceDialog(AlertDialog.Builder(this).setTitle("Battery record").setMessage(app.battery.summary()).setPositiveButton("Close", null))
        })
        body.addView(ui.actionRow("Android settings", "Advanced system controls", R.drawable.ic_settings) { openSystem(Settings.ACTION_SETTINGS) })
        body.addView(ui.section("Power"))
        body.addView(ui.button("Sleep now") { app.screen.sleepNow() })
        body.addView(ui.row(ui.button("Restart", danger = true) { confirm("Restart device?", "Restart", DeviceSettings::reboot) },
            ui.button("Power off", danger = true) { confirm("Power off device?", "Power off", DeviceSettings::powerOff) }))
    }

    private fun field(label: String, value: String, secret: Boolean = false, uri: Boolean = false): EditText {
        body.addView(ui.mono(label).apply { setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(8)) })
        return ui.field(label, value, InputType.TYPE_CLASS_TEXT or when {
            secret -> InputType.TYPE_TEXT_VARIATION_PASSWORD
            uri -> InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }).also(body::addView)
    }

    private fun toggle(label: String, checked: Boolean, save: (Boolean) -> Boolean) {
        var changing = false
        val control = ui.toggle(label, checked)
        control.setOnCheckedChangeListener { _, on -> if (!changing && !save(on)) { changing = true; control.isChecked = !on; changing = false } }
        body.addView(control)
    }

    private fun save(device: Boolean = false, radio: Boolean = false, update: (Config) -> Config): Boolean = try {
        val saved = app.store.update(update)
        if (device) DeviceSettings.apply(saved)
        if (radio) app.radio.applyBaseline()
        true
    } catch (e: ConfigPersistenceException) { showToast(e.message ?: "Settings could not be saved"); false }

    private fun choose(title: String, labels: Array<String>, chosen: (Int) -> Unit) {
        showDeviceDialog(AlertDialog.Builder(this).setTitle(title).setItems(labels) { _, index -> chosen(index) }.setNegativeButton("Cancel", null))
    }

    private fun confirm(title: String, action: String, perform: () -> Unit) {
        showDeviceDialog(AlertDialog.Builder(this).setTitle(title).setPositiveButton(action) { _, _ -> perform() }.setNegativeButton("Cancel", null))
    }

    private fun openSystem(action: String) { runCatching { startActivity(Intent(action)) }.onFailure { showToast("This Android screen is unavailable") } }

    /** Gesture and meaning pairs in a card: the gesture in orange mono, its meaning beneath. */
    private fun guide(vararg rows: Pair<String, String>) = ui.card().apply {
        rows.forEachIndexed { index, (gesture, meaning) ->
            addView(ui.mono(gesture, R.color.accent, 12f, 800).apply { setPadding(0, if (index == 0) 0 else ui.dp(12), 0, ui.dp(3)) })
            addView(ui.text(meaning, 16f))
        }
    }

    private fun EditText.value() = text.toString().trim()
    private fun changed(field: EditText, before: String, latest: String) = field.value().let { if (it != before) it else latest }
    private fun EditText.validUrl(): Boolean {
        val valid = runCatching { URI(value()).let { it.scheme in listOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
        if (!valid) error = "Enter an http or https server URL"
        return valid
    }

    private enum class Page(val title: String) {
        INDEX("Settings"), DISPLAY("Display & controls"), SOUND("Sound"), POWER("Connectivity & power"),
        CONNECTION("Connections"), SPEECH("Speech services"), SYSTEM("System"),
    }

    companion object { fun brightnessPercent(level: Int) = ((level.coerceIn(1, 255) - 1) * 99 / 254) + 1 }
}
