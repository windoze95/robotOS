package dev.r1ptt.sys

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one place the app shells out to `su`, always with a timeout.
 *
 * Adapted from ClawPTT (MIT, github.com/mthelpme/clawptt): an unanswered Magisk grant prompt (and
 * some builds after a deny) parks `su` indefinitely, so a plain waitFor() would hang its caller.
 * Output is drained on a side thread so a chatty command can't fill the pipe and deadlock.
 * Nothing here throws: "no root" is a normal state, reported as -1 or null.
 */
object Root {
    private const val TAG = "r1ptt"
    private val pool = Executors.newCachedThreadPool()

    /** A root command has succeeded in this process, so root is granted. */
    @Volatile var verified = false
        private set

    /** Runs [cmd] under `su -c`; returns its exit code, or -1 on timeout or failure. */
    fun run(cmd: String, timeoutMs: Long = 4000): Int = exec(cmd, timeoutMs, collect = false).first

    /** Runs [cmd] and returns stdout+stderr, or null if it failed, timed out or exited non-zero. */
    fun read(cmd: String, timeoutMs: Long = 4000): String? =
        exec(cmd, timeoutMs, collect = true).let { (code, out) -> if (code == 0) out else null }

    /** Fire-and-forget [run] off the calling thread. */
    fun async(cmd: String, timeoutMs: Long = 8000, done: ((Int) -> Unit)? = null) {
        pool.execute { val code = run(cmd, timeoutMs); done?.invoke(code) }
    }

    /**
     * Starts a long-lived `su -c cmd` whose stdout is read as a raw byte stream (stderr is
     * discarded so it can't corrupt binary output). The caller owns the process and must
     * destroy() it, which also unblocks a pending read.
     */
    fun stream(cmd: String): Process? = runCatching {
        ProcessBuilder("su", "-c", cmd).redirectError(File("/dev/null")).start()
    }.onFailure { Log.w(TAG, "su stream failed to start", it) }.getOrNull()

    private fun exec(cmd: String, timeoutMs: Long, collect: Boolean): Pair<Int, String?> {
        var proc: Process? = null
        try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start().also { proc = it }
            val out = if (collect) ByteArrayOutputStream() else null
            val drainer = Thread {
                runCatching {
                    p.inputStream.use { ins ->
                        if (out != null) ins.copyTo(out) else {
                            val b = ByteArray(4096)
                            while (ins.read(b) >= 0) { /* discard */ }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "su timed out after ${timeoutMs}ms (grant prompt unanswered?): $cmd")
                p.destroyForcibly()
                return -1 to null
            }
            drainer.join(500)
            if (p.exitValue() == 0) verified = true
            return p.exitValue() to out?.toString(Charsets.UTF_8.name())
        } catch (e: Throwable) {
            Log.w(TAG, "su failed: $cmd", e)
            return -1 to null
        } finally {
            runCatching { proc?.destroy() }
        }
    }
}
