package dev.r1ptt.workspace

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import dev.r1ptt.App
import dev.r1ptt.ui.DeviceActivity
import kotlinx.coroutines.*

/** Enrollment is a one-time code from the owner console; owner credentials never reach an R1. */
class HubSettingsActivity : DeviceActivity() {
    private val app get() = application as App
    private val hub get() = app.workspace
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); render() }
    override fun onResume() { super.onResume(); render(); if (hub.identity.paired) hub.refresh() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun render() {
        val page = ui.page("Host & devices", ::finish); setContentView(page.root); wheelScrollTarget = page.scroll
        val body = page.body
        hub.identity.error?.let { body.addView(ui.note(it)); return }
        if (!hub.identity.paired || hub.identity.value.optBoolean("local_workspace_cleared")) {
            body.addView(ui.note("Create a pairing code in your robotOS web console, then enter it here. Each R1 receives separate credentials. Pairing sends future requests through the host and removes the saved direct Hermes API key from this device."))
            val url = ui.field("Host HTTPS address", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).also(body::addView)
            val code = ui.field("One-time pairing code", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).also(body::addView)
            val name = ui.field("Device name", "My R1").also(body::addView)
            val pair = ui.button("Pair this R1", primary = true) {}
            pair.setOnClickListener {
                pair.isEnabled = false
                scope.launch { try { hub.pair(url.text.toString(), code.text.toString(), name.text.toString()); showToast("Paired with the shared workspace"); render() }
                    catch (e: Exception) { showToast(if (e is IllegalArgumentException || e is IllegalStateException) e.message.orEmpty() else "Could not pair. Check the host address and pairing code."); pair.isEnabled = true } }
            }
            body.addView(pair); return
        }
        val identity = hub.identity.value
        body.addView(ui.title(identity.optString("name", "R1")))
        body.addView(ui.note(identity.optString("base_url")))
        body.addView(ui.note("Device ID: ${identity.optString("device_id")}"))
        body.addView(ui.button("Open Workspace", primary = true) { startActivity(Intent(this, WorkspaceActivity::class.java)) })
        body.addView(ui.button("Sync and check in") { scope.launch { try { hub.sync(); showToast(hub.status.value); render() } catch (_: Exception) { showToast(hub.status.value) } } })
        if (hub.blocked) {
            body.addView(ui.note(identity.optString("lost_message").ifBlank { "Access is restricted. Restore this device from the host console." }))
            body.addView(ui.button("Stop ringing") { hub.management.stopRinging() }); return
        }
        body.addView(ui.section("Local permissions"))
        fun permission(key: String, label: String, detail: String) {
            body.addView(ui.toggle(label, hub.identity.allowed(key)) { enabled ->
                try { hub.identity.permission(key, enabled); if (key == "recovery_checkins") hub.management.scheduleRecovery(); hub.refresh() }
                catch (_: Exception) { showToast("Could not save permission"); render() }
            }); body.addView(ui.note(detail))
        }
        permission("remote_management", "Allow remote management", "Lets your authenticated owner console ring this R1, display return information and change quiet mode, brightness, text size and screen timeout. Additional controls below are separate permissions.")
        permission("recovery_checkins", "Periodic recovery check-ins", "Wake, reconnect and boot always check recovery first. This optional setting also checks about every 15 minutes and briefly asks the existing radio policy for connectivity. Off by default; increases battery use. Android may defer check-ins. A powered-off device remains unreachable.")
        body.addView(ui.toggle("Share location for recovery", hub.identity.allowed("location_reporting")) { enabled ->
            hub.identity.permission("location_reporting", enabled)
            if (enabled && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 92)
            hub.refresh()
        })
        body.addView(ui.note("Reports location only with your permission and Android location access. Every fix includes its observation time and accuracy. Turn on Android Location if you want recovery fixes."))
        if (hub.identity.allowed("location_reporting") && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            body.addView(ui.note("Background recovery location is not enabled yet. In Android app permissions, open Location and choose Allow all the time. Precise location and background access are required before this R1 advertises remote location support."))
            body.addView(ui.button("Grant background location") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            })
        }
        body.addView(ui.button("Android location settings") { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        permission("secure_lock", "Allow secure remote lock", "With your permission, lost or revoked status automatically requests Android secure lock whenever an Android screen lock and active device admin are available. Enabling Android security later automatically strengthens recovery protection. Without them robotOS restricts its own screens and actions; other Android apps remain accessible.")
        val security = hub.management.security()
        body.addView(ui.note(if (security.optBoolean("secure_lock_available")) "Protection: robotOS restriction and Android secure lock."
            else "Protection: robotOS restriction. Android secure lock requires the permission above, remote management, an Android screen lock (PIN, pattern or password) and device admin."))
        body.addView(ui.button("Enable Android device admin") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                android.content.ComponentName(this, dev.r1ptt.sys.AdminReceiver::class.java)))
        })
        body.addView(ui.button("Configure Android screen lock") { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) })
        permission("allow_clear_workspace", "Allow clearing local workspace", "Owner can erase shared notes, recordings, drafts and cached replies on this R1. Host copies and the other R1 remain intact; recovery identity survives.")
        permission("allow_updates", "Allow update requests", "Owner can request a signed robotOS update. The existing updater verifies the release and requires local installer approval. The console shows pending until the installed version is confirmed.")
        val owner = getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName)
        if (owner) {
            permission("allow_reboot", "Allow remote reboot", "Available only for device-owner enrollment. Completion remains unknown until the device reconnects.")
            permission("allow_factory_reset", "Allow factory reset", "Destructive: erases the entire device, including local recordings, credentials and recovery access. Requires device-owner enrollment and explicit owner confirmation in the console.")
        } else body.addView(ui.note("Remote reboot and factory reset are unavailable: this installation is not enrolled as Android device owner."))
        body.addView(ui.button("Stop ringing") { hub.management.stopRinging() })
        body.addView(ui.note("Revoke this individual device in the host console if it is lost. Recovery communication remains separate from workspace access."))
    }
}
