package dev.r1ptt

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.r1ptt.data.Config
import dev.r1ptt.data.ConfigPersistenceException
import dev.r1ptt.power.DeviceSettings
import dev.r1ptt.update.UpdateActivity
import org.json.JSONObject

/**
 * On-device settings. Everything here can also be pushed from a computer with tools/provision.sh,
 * which is the easier way to enter API keys.
 */
class SettingsActivity : Activity() {
    private val app get() = application as App
    private lateinit var cfg: Config
    private lateinit var root: LinearLayout
    private val providerByView = mutableMapOf<Int, String>()

    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var extra: EditText
    private lateinit var speak: Switch
    private lateinit var voice: EditText
    private lateinit var sttUrl: EditText
    private lateinit var sttModel: EditText
    private lateinit var sttLiveOn: Switch
    private lateinit var ttsUrl: EditText
    private lateinit var ttsModel: EditText
    private lateinit var liveOn: Switch
    private lateinit var liveBackend: EditText
    private lateinit var liveSearch: Switch
    private lateinit var idleMinutes: EditText
    private lateinit var screenTimeout: EditText
    private lateinit var brightness: EditText
    private lateinit var cellular: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = app.store.value
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(root)
        })

        app.store.loadError?.let { error ->
            header("Settings recovery")
            root.addView(TextView(this).apply {
                text = error
                setTextColor(getColor(R.color.fg))
            })
            root.addView(button("Retry loading settings") {
                if (app.store.retryReload()) recreate()
                else Toast.makeText(this, app.store.loadError, Toast.LENGTH_LONG).show()
            })
        }

        root.addView(button("Hermes") { startActivity(Intent(this, dev.r1ptt.hermes.HermesActivity::class.java)) })
        header("Backend")
        val group = RadioGroup(this)
        cfg.availableProviders.values.forEach { p ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = p.label
                setTextColor(getColor(R.color.fg))
            }
            providerByView[rb.id] = p.id
            group.addView(rb)
            if (p.id == cfg.provider.id) group.check(rb.id)
        }
        root.addView(group)
        baseUrl = field("Base URL")
        apiKey = field("API key / token", secret = true)
        model = field("Model")
        extra = field("Extra request JSON")
        loadProvider()
        group.setOnCheckedChangeListener { _, checked ->
            stashProvider()
            cfg = cfg.copy(activeProvider = providerByView.getValue(checked))
            loadProvider()
        }

        header("Speech")
        speak = switch("Speak replies", cfg.tts.enabled)
        voice = field("Voice", cfg.tts.voice)
        sttUrl = field("Speech-to-text URL", cfg.stt.baseUrl)
        sttModel = field("Speech-to-text model", cfg.stt.model)
        sttLiveOn = switch("Show words as you speak (${cfg.sttLiveModel}, OpenAI)", cfg.sttLive)
        ttsUrl = field("Text-to-speech URL", cfg.tts.endpoint.baseUrl)
        ttsModel = field("Text-to-speech model", cfg.tts.endpoint.model)

        header("Voice turns (keyboard closed)")
        liveOn = switch("Speech-to-speech with ${cfg.live.model} (OpenAI)", cfg.live.enabled)
        liveBackend = field("Thinking model behind the voice", cfg.live.backendModel)
        liveSearch = switch("Let it search the web", cfg.live.webSearch)

        header("Power")
        idleMinutes = field("Wi-Fi off after idle (minutes, 0 = never)", cfg.power.wifiIdleMinutes.toString(), number = true)
        screenTimeout = field("Screen timeout (seconds)", cfg.power.screenTimeoutSec.toString(), number = true)
        brightness = field("Brightness (1–255)", cfg.power.brightness.toString(), number = true)
        cellular = switch("Use cellular data (SIM)", cfg.power.cellular)

        row(button("Save") { save() }, button("Cancel") { finish() })
        row(
            button("Wi-Fi") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            button("New chat") {
                app.history.newConversation()
                toast("New conversation")
            },
        )
        row(
            button("Battery") {
                AlertDialog.Builder(this).setTitle("Battery").setMessage(app.battery.summary())
                    .setPositiveButton("OK", null).show()
            },
            button("Reboot") { confirm("Reboot now?") { DeviceSettings.reboot() } },
            button("Power off") { confirm("Power off now?") { DeviceSettings.powerOff() } },
        )
        header("robotOS")
        root.addView(button("App updates") { startActivity(Intent(this, UpdateActivity::class.java)) })
    }

    private fun loadProvider() {
        val p = cfg.provider
        baseUrl.setText(p.baseUrl)
        apiKey.setText(p.apiKey)
        model.setText(p.model)
        extra.setText(p.extraBody)
    }

    /** Keeps edits to the provider being switched away from. */
    private fun stashProvider() {
        val p = cfg.provider.copy(
            baseUrl = baseUrl.text.toString().trim(),
            apiKey = apiKey.text.toString().trim(),
            model = model.text.toString().trim(),
            extraBody = extra.text.toString().trim().ifBlank { "{}" },
        )
        cfg = cfg.copy(providers = cfg.providers + (p.id to p))
    }

    private fun save() {
        stashProvider()
        val bad = cfg.availableProviders.values.firstOrNull { runCatching { JSONObject(it.extraBody) }.isFailure }
        if (bad != null) {
            toast("${bad.label}: extra request JSON isn't valid JSON")
            return
        }
        cfg = cfg.copy(
            stt = cfg.stt.copy(baseUrl = sttUrl.text.toString().trim(), model = sttModel.text.toString().trim()),
            sttLive = sttLiveOn.isChecked,
            tts = cfg.tts.copy(
                enabled = speak.isChecked,
                voice = voice.text.toString().trim(),
                endpoint = cfg.tts.endpoint.copy(baseUrl = ttsUrl.text.toString().trim(), model = ttsModel.text.toString().trim()),
            ),
            live = cfg.live.copy(
                enabled = liveOn.isChecked,
                backendModel = liveBackend.text.toString().trim().ifBlank { cfg.live.backendModel },
                webSearch = liveSearch.isChecked,
            ),
            power = cfg.power.copy(
                wifiIdleMinutes = idleMinutes.int(cfg.power.wifiIdleMinutes).coerceAtLeast(0),
                screenTimeoutSec = screenTimeout.int(cfg.power.screenTimeoutSec).coerceIn(5, 600),
                brightness = brightness.int(cfg.power.brightness).coerceIn(1, 255),
                cellular = cellular.isChecked,
            ),
        )
        val saved = try {
            app.store.update { cfg }
        } catch (e: ConfigPersistenceException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }
        DeviceSettings.apply(saved)
        app.radio.applyBaseline()
        toast("Saved")
        finish()
    }

    // ---- tiny view helpers ----

    private fun header(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(getColor(R.color.accent))
            setPadding(0, dp(14), 0, dp(4))
        })
    }

    private fun field(label: String, value: String = "", secret: Boolean = false, number: Boolean = false): EditText {
        root.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(getColor(R.color.dim))
            setPadding(0, dp(6), 0, dp(2))
        })
        val e = EditText(this).apply {
            setText(value)
            textSize = 14f
            setTextColor(getColor(R.color.fg))
            setBackgroundResource(R.drawable.field)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        root.addView(e)
        return e
    }

    private fun switch(label: String, on: Boolean) = Switch(this).apply {
        text = label
        isChecked = on
        setTextColor(getColor(R.color.fg))
        setPadding(0, dp(8), 0, dp(8))
        root.addView(this)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg buttons: Button) {
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        })
    }

    private fun confirm(question: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(question)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun EditText.int(default: Int) = text.toString().trim().toIntOrNull() ?: default

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
