package dev.r1ptt.workspace

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.app.admin.DeviceAdminInfo
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import dev.r1ptt.App
import dev.r1ptt.hermes.DeviceApi
import dev.r1ptt.power.DeviceSettings
import dev.r1ptt.sys.AdminReceiver
import dev.r1ptt.update.UpdateActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume

/** Recovery identity is deliberately unable to read workspace content or invoke an agent. */
class HubManagement(private val app: App, private val hub: WorkspaceClient) {
    private val main = Handler(Looper.getMainLooper())
    private val policies = app.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(app, AdminReceiver::class.java)
    private var player: MediaPlayer? = null
    val ringing: Boolean get() = player != null
    private val stopRing = Runnable { stopRinging() }
    private val permissions get() = hub.identity
    private fun management() = permissions.allowed("remote_management")
    private fun locationAllowed() = permissions.allowed("location_reporting") && app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        app.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun secureLock() = app.getSystemService(KeyguardManager::class.java).isDeviceSecure && policies.isAdminActive(admin) && policies.hasGrantedPolicy(admin, DeviceAdminInfo.USES_POLICY_FORCE_LOCK)
    fun capabilities(): JSONObject = JSONObject().apply {
        put("ring", management()); put("stop_ring", management()); put("show_message", management())
        put("lock", management() && permissions.allowed("secure_lock") && secureLock())
        put("locate", management() && locationAllowed())
        put("clear_workspace", management() && permissions.allowed("allow_clear_workspace"))
        put("factory_reset", management() && permissions.allowed("allow_factory_reset") && policies.isDeviceOwnerApp(app.packageName) && policies.hasGrantedPolicy(admin, DeviceAdminInfo.USES_POLICY_WIPE_DATA))
        put("update_app", !hub.quarantined && management() && permissions.allowed("allow_updates"))
        put("reboot", !hub.quarantined && management() && permissions.allowed("allow_reboot") && policies.isDeviceOwnerApp(app.packageName))
        put("device_tool", app.store.value.hermes.deviceApi && !hub.quarantined)
    }
    fun security(): JSONObject = JSONObject()
        .put("app_restriction_available", true)
        .put("secure_lock_configured", app.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        .put("device_admin_active", policies.isAdminActive(admin))
        .put("device_owner", policies.isDeviceOwnerApp(app.packageName))
        .put("secure_lock_available", management() && permissions.allowed("secure_lock") && secureLock())
        .put("secure_lock_applied", hub.identity.value.optBoolean("secure_lock_applied"))
        .put("workspace_restricted", hub.quarantined)

    /** Existing consent automatically becomes effective when Android's security setup improves. */
    fun refreshProtection() {
        val state = hub.identity.value
        val restricted = state.optBoolean("lost") || state.optBoolean("revoked")
        val available = management() && permissions.allowed("secure_lock") && secureLock()
        if (!restricted || !available) {
            if (state.optBoolean("secure_lock_applied")) hub.identity.update { it.put("secure_lock_applied", false) }
        } else if (!state.optBoolean("secure_lock_applied")) {
            // Persist evidence only after Android accepts the request. Never invent a credential.
            if (runCatching { policies.lockNow() }.isSuccess)
                hub.identity.update { it.put("secure_lock_applied", true) }
        }
        dev.r1ptt.ui.DeviceActivity.refreshRecovery(app)
    }

    /** Applied durably before command delivery, including a newer policy returned with a receipt. */
    private suspend fun applyPolicy(response: JSONObject, ticket: Long) {
        require(response.opt("lost") is Boolean && response.opt("revoked") is Boolean) { "Invalid recovery policy" }
        val revision = response.optLong("policy_revision", 0)
        require(revision >= 0 && revision >= hub.identity.value.optLong("policy_revision")) { "Stale recovery policy" }
        // A healthy response admitted before a wake, reconnect or authorization denial cannot
        // restore access. A newly observed restrictive response is always applied immediately.
        if (ticket != hub.recoveryTicket() && !response.getBoolean("lost") && !response.getBoolean("revoked")) return
        hub.identity.update {
            it.put("lost", response.getBoolean("lost")).put("revoked", response.getBoolean("revoked"))
                .put("lost_message", response.optString("lost_message")).put("policy_revision", revision)
            if (response.has("name")) it.put("name", response.getString("name"))
        }
        // Cancellation of an active chat must not interrupt application of its lost-device policy.
        withContext(NonCancellable + Dispatchers.Main) { refreshProtection() }
        hub.identity.update { it.put("applied_policy_revision", revision) }
        if (hub.quarantined) hub.recoverNow()
    }

    suspend fun checkIn() {
        val ticket = hub.recoveryTicket()
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val state = hub.identity.value
        val packet = JSONObject().put("name", state.optString("name"))
            .put("app_version", app.updates.installedVersion()).put("battery", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            .put("capabilities", capabilities()).put("settings_revision", state.optLong("settings_revision"))
            .put("settings", appliedSettings())
            .put("applied_policy_revision", state.optLong("applied_policy_revision")).put("security", security())
        if (locationAllowed()) lastLocation()?.let { packet.put("location", it) }
        val response = hub.api("POST", "/devices/self/check-in", packet, recovery = true)
        check(response.getString("device_id") == state.getString("device_id")) { "Recovery response belongs to another device" }
        applyPolicy(response, ticket)
        hub.recoveryConfirmed(ticket)
        withContext(NonCancellable + Dispatchers.Main) {
            dev.r1ptt.ui.DeviceActivity.refreshRecovery(app)
            if (hub.networkAllowed) { app.deviceApi.apply(app.store.value.hermes); app.resumeHermesTexts() }
        }
        val revision = response.optLong("settings_revision")
        if (revision > state.optLong("settings_revision") && management() && hub.networkAllowed) {
            applySettings(response.optJSONObject("settings") ?: JSONObject())
            if (settingsMatch(response.optJSONObject("settings") ?: JSONObject()))
                hub.identity.update { it.put("settings_revision", revision) }
        }
        // Installation is confirmed only by the installed package version, even across a restart.
        val installed = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
        hub.store.items("receipts").filter { it.optLong("expected_version") > 0 && installed >= it.optLong("expected_version") }.forEach {
            hub.store.commandState(it.getString("id"), "applied", "Signed robotOS update is installed")
        }
        ackPending()
        response.optJSONArray("commands")?.objects().orEmpty().forEach { command ->
            currentCoroutineContext().ensureActive()
            if (hub.store.beginCommand(command, state.getString("device_id"), System.currentTimeMillis())) {
                val id = command.getString("id")
                try {
                    val accepted = ackPending() // report this receipt before attempting any device effect
                    if (id !in accepted) {
                        hub.store.commandState(id, "unknown", "The host rejected the receipt; no device action was attempted")
                        return@forEach
                    }
                    hub.store.commandState(id, "executing", "Execution started")
                    execute(command)
                } catch (_: Exception) {
                    hub.store.commandState(id, "unknown", "Completion could not be confirmed; this command will not repeat")
                }
            }
        }
        ackPending()
    }
    private suspend fun ackPending(): Set<String> {
        val accepted = mutableSetOf<String>()
        hub.store.items("receipts").filter { it.optBoolean("ack_pending") && it.optString("status") != "executing" && it.optString("device_id") == hub.identity.value.optString("device_id") }.forEach { receipt ->
            val id = receipt.getString("id")
            val ticket = hub.recoveryTicket()
            try {
                val response = hub.api("POST", "/devices/self/commands/$id/ack", JSONObject()
                    .put("status", receipt.getString("status")).put("result", receipt.optJSONObject("result") ?: JSONObject().put("message", receipt.optString("result"))), recovery = true)
                if (response.has("policy_revision")) applyPolicy(response, ticket)
                hub.store.put("receipts", receipt.put("ack_pending", false)); accepted.add(id)
            } catch (error: HubException) {
                error.response?.takeIf { it.has("policy_revision") }?.let { applyPolicy(it, ticket) }
                if (error.status !in setOf(400, 403, 404, 409)) throw error
                // Preserve the disagreement for review without poisoning every later check-in.
                // A new command whose received receipt was rejected must never execute.
                hub.store.put("receipts", receipt.put("ack_pending", false).put("ack_error", error.code.ifBlank { "Host rejected receipt (${error.status})" }))
            }
        }
        return accepted
    }
    private suspend fun execute(command: JSONObject) {
        val id = command.getString("id"); val type = command.getString("type")
        fun finish(status: String, text: String) = hub.store.commandState(id, status, text)
        if (command.optLong("expires_at") <= System.currentTimeMillis()) { finish("failed", "Command expired before execution"); return }
        if (hub.quarantined && type !in RECOVERY_COMMANDS) { finish("failed", "Device access is restricted; this action is disabled"); return }
        if (hub.recoveryPending && type !in RECOVERY_COMMANDS) { finish("failed", "Recovery must be verified after waking or reconnecting"); return }
        if (!capabilities().optBoolean(type)) { finish("failed", "This capability is disabled or unsupported on this R1"); return }
        val payload = command.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "ring" -> withContext(Dispatchers.Main) {
                stopRinging()
                val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val next = MediaPlayer.create(app, tone) ?: error("Alarm sound unavailable")
                next.isLooping = true; player = next; next.start()
                main.postDelayed(stopRing, payload.optLong("duration_seconds", 30).coerceIn(1, 120) * 1000)
                finish("applied", "Ringing with an automatic timeout")
            }
            "stop_ring" -> withContext(Dispatchers.Main) { stopRinging(); finish("applied", "Ringing stopped") }
            "show_message" -> {
                val text = payload.optString("message", payload.optString("text")).trim().take(1000); require(text.isNotBlank())
                hub.identity.update { it.put("return_message", text) }; app.notices.add(text)
                finish("applied", "Return information saved for display")
            }
            "lock" -> withContext(Dispatchers.Main) {
                check(secureLock()); policies.lockNow(); hub.identity.update { it.put("secure_lock_applied", true) }
                finish("applied", "Android secure lock requested")
            }
            "locate" -> {
                val location = currentLocation()
                if (location == null) finish("failed", "No location fix is available; no location was invented")
                else hub.store.put("receipts", requireNotNull(hub.store.item("receipts", id)).put("status", "applied").put("result", location).put("ack_pending", true))
            }
            "clear_workspace" -> {
                hub.identity.update { it.put("local_workspace_cleared", true); it.remove("device_token") }
                hub.cancelTransfers()
                SharedChat.clearHistory(app)
                hub.store.clearShared()
                val captures = java.io.File(app.filesDir, "workspace-capture")
                check(!captures.exists() || captures.deleteRecursively()) { "Some local captures could not be erased" }
                finish("applied", "Local workspace cache and recordings cleared; shared host content and recovery identity preserved")
            }
            "factory_reset" -> {
                finish("unknown", "Factory reset requested; completion cannot be acknowledged after erasure"); ackPending()
                @Suppress("DEPRECATION") policies.wipeData(0)
            }
            "reboot" -> {
                finish("unknown", "Reboot requested; completion will require the device to reconnect"); ackPending(); policies.reboot(admin)
            }
            "update_app" -> {
                val expected = payload.optLong("version_code")
                require(expected > app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode) { "An explicit newer signed release version is required" }
                hub.store.put("receipts", requireNotNull(hub.store.item("receipts", id)).put("expected_version", expected))
                withContext(Dispatchers.Main) {
                    app.notices.add("A robotOS update was requested. Open App updates to verify and approve the signed release.")
                    app.startActivity(Intent(app, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                finish("unknown", "Waiting for local approval in the signed updater; installation is not confirmed")
            }
            "device_tool" -> deviceTool(command)
            else -> finish("failed", "Unsupported command")
        }
    }
    private fun deviceTool(command: JSONObject) {
        check(hub.networkAllowed)
        val payload = command.getJSONObject("payload"); val name = payload.getString("tool")
        val args = payload.optJSONObject("arguments")?.copyJson() ?: JSONObject()
        val route = when (name.removePrefix("robotos_")) {
            "r1_status" -> "GET" to "/v1/status"
            "send_sms" -> "POST" to "/v1/sms"
            "sms_status" -> "GET" to "/v1/sms/${safePath(args.getString("id"))}"
            "request_status" -> "GET" to "/v1/requests/${safePath(args.getString("request_id"))}"
            "recent_texts" -> "GET" to "/v1/texts"
            "list_recipients" -> "GET" to "/v1/recipients"
            "save_recipient" -> "POST" to "/v1/recipients"
            "set_name" -> "POST" to "/v1/name"
            "speak" -> "POST" to "/v1/speak"
            "show" -> "POST" to "/v1/show"
            "set_volume" -> "POST" to "/v1/volume"
            else -> error("Unsupported device tool")
        }
        val query = mutableMapOf<String, String>()
        if (route.second == "/v1/texts") { args.optString("with").takeIf(String::isNotBlank)?.let { query["with"] = it }; query["limit"] = args.optInt("limit", 20).toString() }
        args.put("request_id", command.getString("id"))
        val (status, body) = app.deviceApi.handle(DeviceApi.Request(route.first, route.second, query,
            mapOf("authorization" to "Bearer ${app.store.value.hermes.token}"), args.toString()))
        val receipt = requireNotNull(hub.store.item("receipts", command.getString("id")))
        hub.store.put("receipts", receipt.put("status", if (body.optString("status") == "unknown") "unknown" else if (status in 200..299) "applied" else "failed")
            .put("result", JSONObject().put("http_status", status).put("body", body)).put("ack_pending", true))
    }
    private fun safePath(value: String) = value.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
    fun stopRinging() { main.removeCallbacks(stopRing); player?.let { runCatching { it.stop() }; it.release() }; player = null }
    private fun appliedSettings(): JSONObject = (hub.identity.value.optJSONObject("permissions") ?: JSONObject()).copyJson().apply {
        val cfg = app.store.value
        put("quiet", cfg.quiet); put("text_size_sp", cfg.textSizeSp)
        val brightness = android.provider.Settings.System.getInt(app.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, -1)
        val timeout = android.provider.Settings.System.getInt(app.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1)
        put("brightness", if (brightness >= 0) brightness else JSONObject.NULL)
        put("screen_timeout_sec", if (timeout >= 0) timeout / 1000 else JSONObject.NULL)
    }
    private fun settingsMatch(desired: JSONObject): Boolean {
        val actual = appliedSettings()
        return listOf("quiet", "text_size_sp", "brightness", "screen_timeout_sec").all { key ->
            !desired.has(key) || (!actual.isNull(key) && actual.opt(key)?.toString() == desired.opt(key)?.toString())
        } && (!desired.has("brightness") || android.provider.Settings.System.getInt(app.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, -1) == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    }
    private fun applySettings(desired: JSONObject) {
        app.store.update { before -> before.copy(
            quiet = if (desired.has("quiet")) desired.getBoolean("quiet") else before.quiet,
            textSizeSp = if (desired.has("text_size_sp")) desired.getInt("text_size_sp").also { require(it in 14..26) } else before.textSizeSp,
            power = before.power.copy(
                brightness = if (desired.has("brightness")) desired.getInt("brightness").also { require(it in 1..255) } else before.power.brightness,
                screenTimeoutSec = if (desired.has("screen_timeout_sec")) desired.getInt("screen_timeout_sec").also { require(it in setOf(10,15,30,60,120)) } else before.power.screenTimeoutSec)) }
        DeviceSettings.apply(app.store.value)
    }
    @Suppress("MissingPermission")
    private fun lastLocation(): JSONObject? {
        if (!locationAllowed()) return null
        val manager = app.getSystemService(LocationManager::class.java)
        return manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let(::locationJson)
    }
    private fun locationJson(fix: android.location.Location) = JSONObject().put("latitude", fix.latitude).put("longitude", fix.longitude)
        .put("accuracy", if (fix.hasAccuracy()) fix.accuracy else JSONObject.NULL).put("observed_at", fix.time)
    @Suppress("MissingPermission")
    private suspend fun currentLocation(): JSONObject? {
        if (!locationAllowed()) return null
        val manager = app.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).find { manager.isProviderEnabled(it) } ?: return lastLocation()
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { continuation ->
                val cancel = android.os.CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider, cancel, app.mainExecutor) { fix ->
                    if (continuation.isActive) continuation.resume(fix?.let(::locationJson))
                }
            }
        } ?: lastLocation()
    }
    fun scheduleImmediateRecovery() {
        if (!hub.identity.paired) return
        val job = android.app.job.JobInfo.Builder(78, ComponentName(app, HubRecoveryService::class.java))
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).build()
        app.getSystemService(android.app.job.JobScheduler::class.java).schedule(job)
    }
    companion object {
        internal val RECOVERY_COMMANDS = setOf("ring", "stop_ring", "show_message", "lock", "locate", "clear_workspace", "factory_reset")
    }
    fun scheduleRecovery() {
        val scheduler = app.getSystemService(android.app.job.JobScheduler::class.java)
        if (hub.identity.paired && permissions.allowed("recovery_checkins")) {
            if (scheduler.getPendingJob(77) == null) {
                val job = android.app.job.JobInfo.Builder(77, ComponentName(app, HubRecoveryService::class.java))
                    .setPeriodic(15 * 60_000L, 5 * 60_000L).setPersisted(true).build()
                check(scheduler.schedule(job) == android.app.job.JobScheduler.RESULT_SUCCESS) { "Recovery check-ins could not be scheduled" }
            }
        } else scheduler.cancel(77)
    }
}

/** A scheduled job joins RadioPolicy for a bounded recovery window; Android can defer it. */
class HubRecoveryService : android.app.job.JobService() {
    private var work: Job? = null
    override fun onStartJob(parameters: android.app.job.JobParameters): Boolean {
        val app = application as App
        if (!app.workspace.identity.paired || (parameters.jobId == 77 && !app.workspace.identity.allowed("recovery_checkins"))) return false
        work = CoroutineScope(Dispatchers.IO).launch {
            try {
                app.radio.hold("workspace-recovery", true)
                withTimeoutOrNull(90_000) { if (app.radio.awaitOnline(10_000)) app.workspace.recoveryOnly() }
            } catch (_: Exception) { /* Journaled outcomes remain recoverable; no credentials are logged. */ }
            finally { app.radio.hold("workspace-recovery", false); jobFinished(parameters, false) }
        }
        return true
    }
    override fun onStopJob(parameters: android.app.job.JobParameters): Boolean {
        work?.cancel(); (application as App).radio.hold("workspace-recovery", false)
        return parameters.jobId == 78 || (application as App).workspace.identity.allowed("recovery_checkins")
    }
}
