package dev.r1ptt.power

import dev.r1ptt.data.Power
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class DeviceSettingsTest {
    @Test fun pendingSettingsCoalesceBeforeTheWorkerStarts() {
        val jobs = ArrayDeque<Runnable>()
        val writes = mutableListOf<Power>()
        val writer = LatestDeviceSettings(Executor { jobs.addLast(it) }) { writes.add(it) }
        writer.submit(Power(brightness = 20))
        writer.submit(Power(brightness = 40, screenTimeoutSec = 30))
        assertEquals(1, jobs.size)
        jobs.removeFirst().run()
        assertEquals(listOf(Power(brightness = 40, screenTimeoutSec = 30)), writes)
        assertTrue(jobs.isEmpty())
    }

    @Test fun anEditDuringASlowOrFailedWriteRunsAfterItAndTheNewestCommitWins() {
        val jobs = ArrayDeque<Runnable>()
        val writes = mutableListOf<Int>()
        lateinit var writer: LatestDeviceSettings
        writer = LatestDeviceSettings(Executor { jobs.addLast(it) }) { power ->
            writes.add(power.brightness)
            if (power.brightness == 20) {
                // These arrive while the old command still owns the worker.
                writer.submit(Power(brightness = 30))
                writer.submit(Power(brightness = 40))
                throw IllegalStateException("Root command failed")
            }
        }
        writer.submit(Power(brightness = 20))
        jobs.removeFirst().run()
        assertEquals(listOf(20, 40), writes)
        assertTrue(jobs.isEmpty())
        writer.submit(Power(brightness = 50))
        jobs.removeFirst().run()
        assertEquals(listOf(20, 40, 50), writes)
    }
}
