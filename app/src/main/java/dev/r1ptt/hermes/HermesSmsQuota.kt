package dev.r1ptt.hermes

import android.content.Context
import java.time.LocalDate

/** One durable quota shared by every agent SMS path, reserved before Android handoff. */
class HermesSmsQuota(context: Context, private val day: () -> Long = { LocalDate.now().toEpochDay() }) {
    // Preserve the counter used by the previous device API implementation.
    private val prefs = context.getSharedPreferences("device_api", 0)

    @Synchronized fun sentToday(): Int = if (prefs.getLong("day", -1) == day()) prefs.getInt("count", 0) else 0

    @Synchronized fun claim(id: String, limit: Int): Boolean {
        val current = day()
        val sameDay = prefs.getLong("day", -1) == current
        val attempts = if (sameDay) prefs.getStringSet("attempts", emptySet()).orEmpty() else emptySet()
        if (id in attempts) return true
        val count = if (sameDay) prefs.getInt("count", 0) else 0
        if (limit > 0 && count >= limit) return false
        check(prefs.edit().putLong("day", current).putInt("count", count + 1)
            .putStringSet("attempts", (attempts + id).toList().takeLast(10_000).toSet()).commit())
        return true
    }

    /** Only release when it is certain no attempt reached Android. Unknown outcomes count. */
    @Synchronized fun release(id: String) {
        if (prefs.getLong("day", -1) != day()) return
        val attempts = prefs.getStringSet("attempts", emptySet()).orEmpty()
        if (id !in attempts) return
        check(prefs.edit().putStringSet("attempts", attempts - id)
            .putInt("count", (sentToday() - 1).coerceAtLeast(0)).commit())
    }
}
