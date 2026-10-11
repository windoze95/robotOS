package dev.r1ptt.hermes

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.r1ptt.App
import dev.r1ptt.data.ConfigPersistenceException
import dev.r1ptt.data.HermesLink
import dev.r1ptt.messages.SmsRecipientText
import dev.r1ptt.net.friendly
import dev.r1ptt.ui.DeviceActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local device access and explicit, capability-aware Hermes task controls. */
class HermesActivity : DeviceActivity() {
    private val app get() = application as App
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observe: Job? = null
    private lateinit var status: TextView
    private lateinit var connection: TextView
    private lateinit var modelSummary: TextView
    private lateinit var tasks: LinearLayout
    private lateinit var act: Switch
    private lateinit var passthrough: Switch
    private lateinit var owner: EditText
    private lateinit var limit: EditText
    private lateinit var speak: Switch
    private lateinit var show: Switch
    private lateinit var volume: Switch
    private lateinit var recipients: Switch
    private lateinit var initialLink: HermesLink
    private var approvalDialog: AlertDialog? = null
    private var approvalIdentity: Pair<String, String>? = null
    private var lastTask: HermesRunState? = null
    private var registered = false
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON)
                app.hermesRuns.setVisible(this@HermesActivity, intent.action == Intent.ACTION_SCREEN_ON)
            if (::status.isInitialized) status.text = describe()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val cfg = app.store.value
        initialLink = cfg.hermes
        val page = ui.page("Hermes") { finish() }
        setContentView(page.root); wheelScrollTarget = page.scroll
        val body = page.body
        connection = ui.text(host(), 15f, muted = true); body.addView(connection)
        body.addView(ui.button("Check connection") { checkConnection() })
        tasks = ui.column(); body.addView(tasks)
        body.addView(ui.section("Model"))
        modelSummary = ui.text(modelLabel(), 15f, muted = true); body.addView(modelSummary)
        body.addView(ui.button("Choose model") { chooseModel() })
        body.addView(ui.text("A Hermes session may have its own model setting. Completed tasks show the model actually used.", 13f, muted = true))
        body.addView(ui.section("This R1"))
        status = ui.text(describe(), 14f, muted = true); body.addView(status)
        act = ui.toggle("Let Hermes access this R1", cfg.hermes.deviceApi); body.addView(act)
        if (cfg.hermes.token.length < 32) {
            act.isEnabled = false
            body.addView(ui.text("Set up the device connection from your computer first.", 14f, muted = true))
        }
        body.addView(ui.text("Access includes text messages, saved recipients and device status.", 14f, muted = true))
        body.addView(ui.section("Your texts to Hermes"))
        passthrough = ui.toggle("Relay my texts to Hermes", cfg.hermes.passthrough); body.addView(passthrough)
        body.addView(ui.text("Only texts from your number are relayed. Hermes replies by SMS.", 14f, muted = true))
        body.addView(ui.section("Text limit"))
        limit = ui.field("Texts per day · 0 for unlimited", cfg.hermes.dailySendLimit.toString(), InputType.TYPE_CLASS_NUMBER)
        body.addView(limit)
        body.addView(ui.section("Your number"))
        owner = ui.field("Phone number", cfg.hermes.owner, InputType.TYPE_CLASS_PHONE); body.addView(owner)
        body.addView(ui.section("Additional device controls"))
        speak = ui.toggle("Speak announcements", cfg.hermes.allowSpeak); body.addView(speak)
        show = ui.toggle("Show messages on screen", cfg.hermes.allowShow); body.addView(show)
        volume = ui.toggle("Change volume", cfg.hermes.allowVolume); body.addView(volume)
        recipients = ui.toggle("Save recipients", cfg.hermes.allowSaveRecipient); body.addView(recipients)
        body.addView(ui.text("These controls are available only while device access is on. Quiet mode still applies.", 14f, muted = true))
        body.addView(ui.section(""))
        body.addView(ui.row(ui.button("Save", true) { save() }, ui.button("Close") { finish() }))
        renderTask(app.hermesRuns.state.value)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }); registered = true
        app.hermesRuns.setVisible(this, getSystemService(android.os.PowerManager::class.java).isInteractive)
        observe = work.launch { app.hermesRuns.state.collect { renderTask(it) } }
    }

    override fun onPause() {
        observe?.cancel(); observe = null
        app.hermesRuns.setVisible(this, false)
        approvalDialog?.dismiss()
        app.turns.guardApprovalReview(null)
        if (registered) { unregisterReceiver(battery); registered = false }
        super.onPause()
    }

    override fun onDestroy() { work.cancel(); super.onDestroy() }

    private fun renderTask(state: HermesRunState) {
        // Do not rebuild the form or steal wheel position for every streamed token.
        val important = state.copy(run = state.run?.copy(output = "", lastSequence = -1))
        if (important == lastTask) return
        lastTask = important
        tasks.removeAllViews()
        val run = state.run
        if (run == null) {
            if (state.message.isNotBlank()) tasks.addView(ui.text(state.message, 14f, muted = true))
            return
        }
        tasks.addView(ui.section("Current task"))
        tasks.addView(ui.text(run.label, 18f))
        if (run.error.isNotBlank()) tasks.addView(ui.text(run.error, 14f, muted = true))
        if (state.message.isNotBlank()) tasks.addView(ui.text(state.message, 14f, muted = true))
        if (run.provider.isNotBlank() || run.model.isNotBlank())
            tasks.addView(ui.text(listOf(run.provider, run.model).filter { it.isNotBlank() }.joinToString(" · "), 13f, muted = true))
        if (run.status == "submission_unknown") tasks.addView(ui.button("Check submission") { app.hermesRuns.retrySubmission() }
            .apply { isEnabled = !state.working && run.durableIdempotency })
        if (run.runId.isNotBlank()) tasks.addView(ui.button("Refresh status") { app.hermesRuns.refresh() }.apply { isEnabled = !state.working })
        if (run.status == "waiting_for_approval") {
            tasks.addView(ui.button("Review request", true) { reviewApproval(run) }.apply { isEnabled = !state.working })
        } else if (approvalDialog != null) approvalDialog?.dismiss()
        if (run.active && run.runId.isNotBlank()) tasks.addView(ui.button("Stop task") { app.hermesRuns.stop() }
            .apply { isEnabled = !state.working && run.canStop && run.status != "stopping" })
        if (run.terminal && run.output.isNotBlank()) tasks.addView(ui.actionRow("Read result", run.output.take(100)) {
            showText("Hermes", run.output)
        })
        if (app.hermesRuns.canArchive) tasks.addView(ui.button("Archive task record") {
            showDeviceDialog(AlertDialog.Builder(this).setTitle("Archive this record?")
                .setMessage("Check Hermes on your computer first. Archiving only removes this task from the R1's active view. It does not stop Hermes, approve an action, or retry your request. A local receipt is kept.")
                .setPositiveButton("I've checked · Archive") { _, _ -> app.hermesRuns.archive() }
                .setNegativeButton("Keep task", null))
        })
        approvalIdentity?.let { identity ->
            if (identity != (run.runId to run.approval?.requestId.orEmpty())) approvalDialog?.dismiss()
        }
    }

    private fun reviewApproval(run: HermesRun) {
        val a = run.approval ?: return
        val content = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(16))
            addView(ui.text(a.description.ifBlank { "Hermes needs your decision to continue." }, 17f))
            if (a.command.isNotBlank()) addView(ui.text(a.command, 14f, muted = true))
            addView(ui.text("The side button cannot approve this request.", 13f, muted = true))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = showDeviceDialog(AlertDialog.Builder(this).setTitle("Review request").setView(scroll)
            .setPositiveButton("Allow once", null).setNegativeButton("Deny", null).setNeutralButton("Later", null))
        approvalDialog = dialog; approvalIdentity = run.runId to a.requestId
        dialog.setOnKeyListener { _, _, event ->
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_1) {
                if (event.repeatCount == 0) dev.r1ptt.PttService.instance?.frameworkButton(event.action == KeyEvent.ACTION_DOWN)
                true
            } else handleWheel(event, scroll)
        }
        app.turns.guardApprovalReview { dialog.dismiss() }
        dialog.setOnDismissListener {
            if (approvalDialog === dialog) { approvalDialog = null; approvalIdentity = null; app.turns.guardApprovalReview(null) }
        }
        fun resolve(allow: Boolean) {
            val current = app.hermesRuns.state.value
            if (current.working || current.run?.runId != run.runId || current.run.approval?.requestId != a.requestId) {
                toast("This approval is no longer current"); dialog.dismiss(); return
            }
            app.hermesRuns.resolveApproval(run.runId, a.requestId, allow)
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = run.canApprove && a.requestId.isNotBlank() && "once" in a.choices
            setOnClickListener { resolve(true) }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            isEnabled = run.canApprove && a.requestId.isNotBlank() && "deny" in a.choices
            setOnClickListener { resolve(false) }
        }
        if (!run.canApprove || a.requestId.isBlank()) content.addView(ui.text("Update Hermes on your computer to answer this request safely.", 14f, muted = true))
    }

    private fun checkConnection() {
        connection.text = "Checking ${host()}…"
        work.launch {
            try {
                app.radio.onActivity()
                val caps = withContext(Dispatchers.IO) { app.hermesRuns.capabilities(force = true) }
                connection.text = when {
                    caps.legacy -> "${host()} · legacy chat; task controls unavailable"
                    caps.runs -> "${host()} · connected; task controls available"
                    else -> "${host()} · connected; chat available"
                }
                status.text = describe()
            } catch (e: Exception) { connection.text = "${host()} · ${friendly(e)}" }
        }
    }

    private fun chooseModel() {
        work.launch {
            try {
                app.radio.onActivity()
                val models = withContext(Dispatchers.IO) {
                    check(app.hermesRuns.capabilities().models) { "Model discovery needs a newer Hermes API server" }
                    HermesClient(app.store.value.agent).models()
                }
                val providers = models.groupBy { it.provider }
                val keys = providers.keys.toList()
                val names = listOf("Use Hermes default") + keys.map { providers.getValue(it).first().providerName }
                showDeviceDialog(AlertDialog.Builder(this@HermesActivity).setTitle("Provider").setItems(names.toTypedArray()) { _, which ->
                    if (which == 0) selectModel(null) else {
                        val choices = providers.getValue(keys[which - 1])
                        showDeviceDialog(AlertDialog.Builder(this@HermesActivity).setTitle(names[which])
                            .setItems(choices.map { it.model }.toTypedArray()) { _, n -> selectModel(choices[n]) }
                            .setNegativeButton("Back", null))
                    }
                }.setNegativeButton("Cancel", null))
            } catch (e: Exception) { toast(friendly(e)) }
        }
    }

    private fun selectModel(model: HermesModel?) {
        try {
            app.store.update { it.copy(agent = it.agent.copy(provider = model?.provider.orEmpty(), model = model?.model ?: "hermes-agent")) }
            modelSummary.text = modelLabel()
            toast("Saved for future requests")
        } catch (e: ConfigPersistenceException) { toast(e.message.orEmpty()) }
    }

    private fun modelLabel(): String = app.store.value.agent.let { a ->
        if (a.provider.isBlank() && a.model == "hermes-agent") "Use Hermes default" else listOf(a.provider, a.model).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun host() = app.store.value.agent.baseUrl.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty().ifBlank { "Not connected" }
    private fun describe(): String {
        val cfg = app.store.value
        val plugged = (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val last = app.deviceApi.lastCall
        return listOf(
            if (!cfg.hermes.deviceApi) "Device access is off" else if (app.deviceApi.listening) "Device API is listening" else "Device API is unavailable",
            "Last contacted: ${if (last == 0L) "not this session" else DateUtils.getRelativeTimeSpanString(last)}",
            "Texts today: ${app.deviceApi.sentToday()}" + if (cfg.hermes.dailySendLimit > 0) " / ${cfg.hermes.dailySendLimit}" else " · unlimited",
            when { plugged -> "Plugged in · radios stay available"; cfg.power.stayReachable || cfg.power.wifiIdleMinutes == 0 -> "Battery · radios stay available"
                else -> "Battery · radios sleep after ${cfg.power.wifiIdleMinutes} min idle" },
            if (cfg.hermes.passthrough) app.hermesTexts.last else "Text relay is off",
        ).joinToString("\n")
    }

    private fun save() {
        val number = owner.text.toString().trim().let { if (it.isEmpty()) "" else SmsRecipientText.number(it, SmsRecipientText.region(this)) }
        if (number == null || (passthrough.isChecked && number.isEmpty())) { toast("Enter your phone number with its country code"); return }
        val perDay = limit.text.toString().trim().toIntOrNull()
        if (perDay == null || perDay !in 0..10_000) { toast("Enter a daily limit from 0 to 10000"); return }
        try {
            app.store.update { cfg ->
                val current = cfg.hermes
                cfg.copy(hermes = current.copy(
                    deviceApi = if (act.isChecked != initialLink.deviceApi) act.isChecked && act.isEnabled else current.deviceApi,
                    passthrough = if (passthrough.isChecked != initialLink.passthrough) passthrough.isChecked else current.passthrough,
                    owner = if (owner.text.toString() != initialLink.owner) number else current.owner,
                    dailySendLimit = if (perDay != initialLink.dailySendLimit) perDay else current.dailySendLimit,
                    allowSpeak = if (speak.isChecked != initialLink.allowSpeak) speak.isChecked else current.allowSpeak,
                    allowShow = if (show.isChecked != initialLink.allowShow) show.isChecked else current.allowShow,
                    allowVolume = if (volume.isChecked != initialLink.allowVolume) volume.isChecked else current.allowVolume,
                    allowSaveRecipient = if (recipients.isChecked != initialLink.allowSaveRecipient) recipients.isChecked else current.allowSaveRecipient
                ).also { require(it.valid()) })
            }
            toast("Saved"); finish()
        } catch (e: ConfigPersistenceException) { toast(e.message.orEmpty()) }
        catch (_: IllegalArgumentException) { toast("Check your number and device connection") }
    }

    private fun showText(title: String, value: String) {
        val text = ui.text(value, 17f).apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); setTextIsSelectable(true) }
        showDeviceDialog(AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Close", null))
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
