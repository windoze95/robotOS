package dev.r1ptt.sys

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin hook whose only policy is force-lock: DevicePolicyManager.lockNow() turns the
 * screen off instantly when the button is tapped. Activated by tools/provision.sh.
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: android.content.Context, intent: android.content.Intent) = changed(context)
    override fun onDisabled(context: android.content.Context, intent: android.content.Intent) = changed(context)
    private fun changed(context: android.content.Context) {
        val app = context.applicationContext as dev.r1ptt.App
        app.workspace.management.refreshProtection()
        app.workspace.recoverNow()
    }
}
