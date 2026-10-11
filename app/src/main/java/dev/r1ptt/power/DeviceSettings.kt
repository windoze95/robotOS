package dev.r1ptt.power

import dev.r1ptt.data.Config
import dev.r1ptt.data.Power
import dev.r1ptt.sys.Root
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Pushes the screen part of [Config.power] into Android's own settings (needs root). */
object DeviceSettings {
    private val writer = LatestDeviceSettings(
        Executors.newSingleThreadExecutor { task -> Thread(task, "device-settings").apply { isDaemon = true } },
    ) { p ->
        Root.run(
            listOf(
                "settings put system screen_off_timeout ${p.screenTimeoutSec.coerceIn(5, 600) * 1000}",
                "settings put system screen_brightness_mode 0",
                "settings put system screen_brightness ${p.brightness.coerceIn(1, 255)}",
            ).joinToString("; "), 8000,
        )
    }

    fun apply(cfg: Config) = writer.submit(cfg.power)

    fun reboot() = Root.async("svc power reboot || reboot")

    fun powerOff() = Root.async("svc power shutdown || reboot -p")
}

/** Only one command runs at a time. Pending edits replace older edits instead of building a queue. */
internal class LatestDeviceSettings(private val executor: Executor, private val write: (Power) -> Unit) {
    private val lock = Any()
    private var pending: Power? = null
    private var scheduled = false

    fun submit(power: Power) {
        val start = synchronized(lock) {
            pending = power
            if (scheduled) false else { scheduled = true; true }
        }
        if (start) executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                pending.also { pending = null; if (it == null) scheduled = false }
            } ?: return
            // Root.run already reports failures. A failed write must not strand a newer edit.
            runCatching { write(next) }
        }
    }
}
