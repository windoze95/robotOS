package dev.r1ptt.sys

import android.app.KeyguardManager
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Locks Android at once, for lost mode. Device admin's lockNow() where Android supports device
 * admin; otherwise Android's lockdown through root, the power menu's "Lockdown", which locks at
 * once and requires the screen lock. Many R1 builds have no device-admin support at all. Either
 * way it only means something with an Android screen lock.
 */
class DeviceLock(
    ctx: Context,
    private val rootAvailable: () -> Boolean = { Root.verified },
    private val root: (String, Long) -> Int = Root::run,
) {
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val keyguard = ctx.getSystemService(KeyguardManager::class.java)
    private val admin = ComponentName(ctx, AdminReceiver::class.java)

    private fun viaAdmin() = dpm.isAdminActive(admin) && dpm.hasGrantedPolicy(admin, DeviceAdminInfo.USES_POLICY_FORCE_LOCK)

    /** A lock request would actually lock: an Android screen lock, plus device admin or root. */
    fun available(): Boolean = keyguard.isDeviceSecure && (viaAdmin() || rootAvailable())

    /** Locking goes through root, which can block: call [lockNow] off the main thread then. */
    fun usesRoot(): Boolean = !viaAdmin()

    /** Blocks on root, so call it off the main thread. */
    fun lockNow(): Boolean =
        if (viaAdmin()) runCatching { dpm.lockNow() }.isSuccess
        else root("cmd lock_settings require-strong-auth STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN && input keyevent 223", 8_000) == 0
}
