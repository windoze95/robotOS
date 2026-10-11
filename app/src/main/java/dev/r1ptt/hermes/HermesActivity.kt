package dev.r1ptt.hermes

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.r1ptt.App
import dev.r1ptt.R
import dev.r1ptt.data.ConfigPersistenceException
import dev.r1ptt.messages.SmsRecipientText

/**
 * Hermes as the agent: whether Hermes can act on this R1, and whether the owner's texts reach it.
 * The device token and Hermes's address are provisioned from a computer (docs/HERMES.md).
 */
class HermesActivity : Activity() {
    private val app get() = application as App
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var act: Switch
    private lateinit var passthrough: Switch
    private lateinit var owner: EditText
    private lateinit var limit: EditText
    private val tick = object : Runnable {
        override fun run() { status.text = describe(); main.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // shows the owner's number
        val cfg = app.store.value
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(24)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(getColor(R.color.bg)); addView(root) })
        header("Hermes")
        status = text("", 14f, R.color.fg)
        text(if (cfg.hermesAgent) "Every push-to-talk turn goes to Hermes as said. Hermes texts people through this R1 with its robotos tools."
            else "Choose Hermes Agent under Settings → Backend to make Hermes the agent.", 13f, R.color.dim)

        header("Hermes on this R1")
        act = switch("Let Hermes act on this R1 (send texts, read texts, status)", cfg.hermes.deviceApi)
        if (cfg.hermes.token.length < 32) {
            act.isEnabled = false
            text("Provision the device token from a computer first (docs/HERMES.md).", 12f, R.color.dim)
        }
        limit = field("Texts Hermes may send per day (0 = no limit)", cfg.hermes.dailySendLimit.toString(), number = true)

        header("Your texts to Hermes")
        passthrough = switch("Send texts from my number to Hermes and text back its reply", cfg.hermes.passthrough)
        owner = field("My phone number", cfg.hermes.owner, phone = true)
        text("Texts from everyone else stay in Messages. Plugged in, the radios stay up so texts arrive anytime; " +
            "on battery they arrive when the R1 is awake.", 12f, R.color.dim)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            listOf(button("Save") { save() }, button("Close") { finish() })
                .forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        })
    }

    override fun onResume() { super.onResume(); tick.run() }
    override fun onPause() { main.removeCallbacks(tick); super.onPause() }

    private fun describe(): String {
        val cfg = app.store.value
        val link = cfg.hermes
        val plugged = (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val last = app.deviceApi.lastCall
        return listOf(
            "Agent: ${if (cfg.hermesAgent) "Hermes · ${runCatching { java.net.URI(cfg.provider.baseUrl).host }.getOrNull() ?: cfg.provider.baseUrl}" else cfg.provider.label}",
            "Hermes access: " + when {
                !link.deviceApi -> "off"
                app.deviceApi.listening -> "ready on port ${link.port}"
                else -> "not running (check the token)"
            },
            "Last reached by Hermes: ${if (last == 0L) "not since robotOS started" else DateUtils.getRelativeTimeSpanString(last)}",
            "Texts Hermes sent today: ${app.deviceApi.sentToday()}" + if (link.dailySendLimit > 0) " of ${link.dailySendLimit}" else "",
            "Your texts to Hermes: ${if (link.passthrough && cfg.hermesAgent) "on · ${app.hermesTexts.last}" else "off"}",
            if (plugged) "On external power: radios stay up" else "On battery: radios sleep 3 minutes after the screen goes dark",
        ).joinToString("\n")
    }

    private fun save() {
        // Stored in international form on a US SIM, so it matches how Android reports the sender.
        val number = owner.text.toString().trim().let { if (it.isEmpty()) "" else SmsRecipientText.number(it, SmsRecipientText.region(this)) }
        if (number == null) { toast("Enter your phone number with its country code, e.g. +14055550123"); return }
        if (passthrough.isChecked && number.isEmpty()) { toast("Enter your phone number to send your texts to Hermes"); return }
        val perDay = limit.text.toString().trim().toIntOrNull()?.coerceIn(0, 10_000) ?: app.store.value.hermes.dailySendLimit
        try {
            app.store.update { it.copy(hermes = it.hermes.copy(deviceApi = act.isChecked && act.isEnabled, passthrough = passthrough.isChecked,
                owner = number, dailySendLimit = perDay).also { link -> require(link.valid()) }) }
        } catch (e: ConfigPersistenceException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show(); return
        } catch (_: IllegalArgumentException) {
            toast("Those settings aren't valid; check the phone number"); return
        }
        toast("Saved")
        finish()
    }

    // ---- tiny view helpers, as in SettingsActivity ----

    private fun header(value: String) = text(value, 15f, R.color.accent).apply { setPadding(0, dp(14), 0, dp(4)) }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(getColor(color)); setPadding(0, dp(4), 0, dp(4))
        root.addView(this)
    }

    private fun field(label: String, value: String, number: Boolean = false, phone: Boolean = false): EditText {
        text(label, 12f, R.color.dim)
        return EditText(this).apply {
            setText(value)
            textSize = 14f
            setTextColor(getColor(R.color.fg))
            setBackgroundResource(R.drawable.field)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER
                phone -> InputType.TYPE_CLASS_PHONE
                else -> InputType.TYPE_CLASS_TEXT
            }
            root.addView(this)
        }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
