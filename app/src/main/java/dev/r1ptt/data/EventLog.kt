package dev.r1ptt.data

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A small on-device record of what matters after an incident: recovery checks and the recovery
 * cover, radio cuts and restores, screen, power and network changes, and voice turns. Timestamps
 * and outcomes only: never content, addresses or credentials. Two files of at most 256 KB each in
 * app storage, so roughly the last week. Read it with
 * `adb shell dumpsys activity service dev.r1ptt/.PttService`.
 */
object EventLog {
    private const val TAG = "r1ptt"
    private const val FILE = "events.log"
    private const val OLDER = "events.1.log"
    internal var maxBytes = 256 * 1024L
    private val io = Executors.newSingleThreadExecutor { Thread(it, "event-log").apply { isDaemon = true } }
    /** Used on [io] only. The offset keeps lines unambiguous across time-zone changes. */
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    @Volatile private var dir: File? = null

    fun start(directory: File) { dir = directory }

    /** Also written to logcat with the same text, so a live `adb logcat -s r1ptt` shows it too. */
    fun record(message: String) {
        Log.i(TAG, message)
        val target = dir ?: return
        val at = System.currentTimeMillis()
        io.execute {
            runCatching {
                val file = File(target, FILE)
                if (file.length() > maxBytes) file.renameTo(File(target, OLDER))
                file.appendText("${time.format(Date(at))} $message\n")
            }
        }
    }

    /** Both files, oldest line first, after any pending writes. */
    fun lines(): List<String> {
        val target = dir ?: return emptyList()
        return runCatching {
            io.submit<List<String>> {
                listOf(OLDER, FILE).flatMap { name -> File(target, name).takeIf(File::exists)?.readLines().orEmpty() }
            }.get(5, TimeUnit.SECONDS)
        }.getOrDefault(emptyList())
    }
}
