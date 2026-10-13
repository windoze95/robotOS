package dev.r1ptt.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import dev.r1ptt.data.EventLog

/**
 * Android's lock screen wears robotOS's recovery cover: the robot mark glowing on black, so swiping
 * up to unlock looks like swiping the cover away. Applied once per design version, so a wallpaper
 * the owner picks later is never replaced.
 */
object LockScreenLook {
    private const val VERSION = 1

    fun applyOnce(
        context: Context,
        // Home too, so any glimpse of the wallpaper while unlocking matches.
        wear: (Bitmap) -> Unit = { WallpaperManager.getInstance(context).setBitmap(it, null, true, WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM) },
    ) {
        val prefs = context.getSharedPreferences("look", Context.MODE_PRIVATE)
        if (prefs.getInt("lock_wallpaper", 0) >= VERSION) return
        val screen = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val picture = LogoGlow.still(context, screen.width(), screen.height())
        wear(picture)
        prefs.edit().putInt("lock_wallpaper", VERSION).apply()
        EventLog.record("lock screen: robotOS cover applied")
    }
}
