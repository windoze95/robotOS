package dev.r1ptt

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.r1ptt.audio.CaptureBuffer
import dev.r1ptt.audio.Clip
import dev.r1ptt.audio.MicStream
import dev.r1ptt.audio.Wav
import dev.r1ptt.net.LiveTranscriber
import dev.r1ptt.net.SocketPump
import dev.r1ptt.net.TranscribeProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** One press owns its mic, callbacks and bounded recording; failures never replay partial input. */
class SttStream(
    private val app: App,
    private val config: dev.r1ptt.data.Config = app.store.value,
    private val onPartial: (String) -> Unit,
    private val onFailure: (String) -> Unit = {},
    private val onLevel: (Float) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    val turnId = TurnMetrics.next()
    private val buffer = CaptureBuffer(MAX_BYTES, TranscribeProtocol.SAMPLE_RATE * 2 * 10) { chunk ->
        transcriber?.send(TranscribeProtocol.append(chunk)) == true
    }
    private val partial = StringBuilder()
    private val callbacks = CallbackBudget(128 * 1024, 256)
    private val result = CompletableDeferred<String>()
    @Volatile private var transcriber: LiveTranscriber? = null
    @Volatile private var done = false
    @Volatile private var released = false
    @Volatile private var interruptedInput = false
    @Volatile private var peakLevel = 0f
    private var waitingForNet = false
    private val mic = MicStream(TranscribeProtocol.SAMPLE_RATE, ::onChunk,
        {
            if (buffer.fail()) {
                interruptedInput = true
                main.post { terminalFailure("Microphone unavailable; input may be partial") }
            }
        },
    ) { level ->
        if (!done && !released) {
            if (level > peakLevel) peakLevel = level
            onLevel(level)
        }
    }
    private val captureDeadline = Runnable { terminalFailure("Dictation limit reached; please retry with a shorter utterance") }
    val peak get() = peakLevel
    val durationMs get() = buffer.size() * 1000L / (TranscribeProtocol.SAMPLE_RATE * 2)
    val usableRecording get() = released && !interruptedInput && !buffer.failed()

    fun start(): Boolean {
        TurnMetrics.event("turn_start", turnId, "mode" to 1L, "warm" to 0L, "ready" to 0L, "connection" to 0L)
        if (!mic.start()) { terminalFailure("Microphone unavailable"); return false }
        val cfg = config
        lateinit var t: LiveTranscriber
        t = LiveTranscriber(object : LiveTranscriber.Listener {
            override fun onDelta(text: String) {
                if (done) return
                val size = text.length * 2
                if (!callbacks.reserve(size)) {
                    if (!released) interruptedInput = true
                    t.cancel()
                    main.postAtFrontOfQueue { terminalFailure("Dictation callback queue full; input may be partial") }
                    return
                }
                main.postAtTime(Runnable {
                    try {
                        if (done || transcriber !== t) return@Runnable
                        if (text.length > 65_536 - partial.length) {
                            terminalFailure("Dictation transcript too large; input may be partial")
                            return@Runnable
                        }
                        partial.append(text)
                        onPartial(partial.toString().trim())
                    } finally { callbacks.release(size) }
                }, t, SystemClock.uptimeMillis())
            }
            override fun onCompleted(text: String) {
                if (text.length > 65_536) {
                    main.postAtFrontOfQueue { terminalFailure("Dictation transcript too large") }
                    return
                }
                main.post {
                    if (done || transcriber !== t) return@post
                    if (buffer.failed() || interruptedInput) {
                        terminalFailure("Input may be partial; please try again")
                        return@post
                    }
                    if (!released) {
                        terminalFailure("Transcription ended before release; input may be partial")
                        return@post
                    }
                    done = true
                    TurnMetrics.event("first_reply", turnId)
                    TurnMetrics.event("exchange_complete", turnId)
                    result.complete(text.trim())
                    closeTransport()
                }
            }
            override fun onFailure(message: String) {
                if (buffer.fail()) interruptedInput = true
                main.post { terminalFailure(message) }
            }
            override fun onMetric(name: String, stats: SocketPump.Stats) {
                TurnMetrics.event(name, turnId, "connection" to t.id,
                    "queued_bytes" to stats.queuedBytes, "max_queue_ms" to stats.maxQueueMs)
            }
        })
        transcriber = t
        TurnMetrics.event("connect_start", turnId, "connection" to t.id)
        val update = TranscribeProtocol.sessionUpdate(cfg.sttLiveModel, cfg.sttDelay,
            listOfNotNull(cfg.sttLanguage.takeIf { it.isNotBlank() }))
        connectWhenOnline(t, cfg.sttLiveUrl, cfg.keyFor(cfg.stt), update, SystemClock.elapsedRealtime() + NET_WAIT_MS)
        main.postDelayed(captureDeadline, 120_000)
        return true
    }

    fun hold() {
        if (done) return
        TurnMetrics.event("hold_start", turnId, "connection" to (transcriber?.id ?: 0L))
        if (!buffer.hold()) terminalFailure("Dictation send failed; input may be partial")
    }

    suspend fun finish(): String? {
        if (!released) {
            TurnMetrics.event("release", turnId)
            main.removeCallbacks(captureDeadline)
            val accepted = buffer.release {
                released = true // capture is already closed under the same buffer monitor
                TurnMetrics.event("release_gate_closed", turnId)
                transcriber?.send(TranscribeProtocol.COMMIT) == true
            }
            released = true
            mic.stop()
            if (!accepted && !done) terminalFailure("Dictation send failed; input may be partial")
        }
        val text = try { withTimeoutOrNull(FINAL_WAIT_MS) { result.await() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        if (text == null) {
            if (!done) terminalFailure("Transcription timed out")
            closeTransport()
        }
        return text
    }

    fun recording(dir: File): Clip {
        check(usableRecording) { "Input may be partial; please try again" }
        dir.mkdirs()
        val f = File(dir, "clip-${System.currentTimeMillis()}.wav")
        val bytes = buffer.bytes()
        Wav.write(f, bytes, TranscribeProtocol.SAMPLE_RATE)
        return Clip(f, "audio/wav", bytes.size * 1000L / (TranscribeProtocol.SAMPLE_RATE * 2), peakLevel)
    }

    fun cancel() {
        done = true
        buffer.stop()
        main.removeCallbacks(captureDeadline)
        mic.stop()
        closeTransport()
        result.cancel()
    }

    private fun terminalFailure(message: String) {
        if (done) return
        done = true
        if (!released || buffer.failed()) interruptedInput = true
        buffer.stop() // fence the capture thread before stopping AudioRecord
        main.removeCallbacks(captureDeadline)
        mic.stop()
        closeTransport()
        TurnMetrics.event("session_failed", turnId)
        result.completeExceptionally(IllegalStateException(message))
        if (interruptedInput) onFailure(message)
    }
    private fun closeTransport() {
        val t = transcriber
        transcriber = null
        if (t != null) {
            t.cancel()
            main.removeCallbacksAndMessages(t)
            TurnMetrics.event("session_closed", turnId, "connection" to t.id)
        }
    }
    private fun onChunk(chunk: ByteArray) {
        when (buffer.append(chunk)) {
            CaptureBuffer.Result.FULL -> {
                interruptedInput = true
                main.post { terminalFailure("Dictation audio buffer full; input may be partial") }
            }
            CaptureBuffer.Result.SEND_FAILED -> {
                interruptedInput = true
                main.post { terminalFailure("Dictation send failed; input may be partial") }
            }
            else -> {}
        }
    }
    private fun connectWhenOnline(t: LiveTranscriber, url: String, key: String, update: String, deadline: Long) {
        if (done || transcriber !== t || t.done) return
        when {
            app.radio.isOnline() -> {
                if (waitingForNet) TurnMetrics.event("network_available", turnId, "connection" to t.id)
                waitingForNet = false
                t.connect(url, key, update)
            }
            SystemClock.elapsedRealtime() >= deadline -> terminalFailure("No network; input may be partial")
            else -> {
                if (!waitingForNet) TurnMetrics.event("network_wait", turnId, "connection" to t.id)
                waitingForNet = true
                main.postDelayed({ connectWhenOnline(t, url, key, update, deadline) }, 200)
            }
        }
    }
    private companion object {
        const val NET_WAIT_MS = 30_000L
        const val FINAL_WAIT_MS = 8_000L
        const val MAX_BYTES = TranscribeProtocol.SAMPLE_RATE * 2 * 120
    }
}
