package dev.r1ptt

import android.os.SystemClock
import dev.r1ptt.data.EventLog
import java.util.concurrent.atomic.AtomicLong

/** Fixed event names and integer counters only. No content, URLs, credentials or periodic idle sampling. */
object TurnMetrics {
    private val sequence = AtomicLong()
    fun next(): Long = sequence.incrementAndGet()
    @Synchronized fun event(event: String, turn: Long = 0, vararg counters: Pair<String, Long>) {
        require(event.matches(Regex("[a-z_]+")) && counters.all { it.first.matches(Regex("[a-z_]+")) })
        EventLog.record("metric event=$event t_ms=${SystemClock.elapsedRealtime()} turn=$turn" +
            counters.joinToString("") { " ${it.first}=${it.second}" })
    }
}
