package dev.r1ptt.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import dev.r1ptt.net.ApiError
import dev.r1ptt.net.errorMessage
import dev.r1ptt.net.friendly
import okhttp3.Call
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Speaks a reply sentence by sentence while it is still streaming in. A fetch thread requests
 * speech for each sentence (16-bit PCM, raw or WAV) and hands chunks over as they arrive; a play
 * thread writes them to an AudioTrack. The next sentence downloads while the current one plays,
 * so speech starts after the first sentence rather than after the whole reply.
 */
class Speaker(
    /** Builds the text-to-speech call for one sentence. */
    private val newCall: (String) -> Call,
    /** Sample rate of raw PCM replies; a WAV reply's own header overrides it. */
    sampleRate: Int,
) {
    private val sentences = SpeechQueue(32, 16_384)
    private val chunks = LinkedBlockingQueue<ByteArray>(32) // <= ~256 KiB plus one writer chunk

    @Volatile private var rate = sampleRate
    @Volatile private var stopped = false
    @Volatile private var silenced = false
    @Volatile private var fetching = true
    @Volatile private var call: Call? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var error: String? = null
    @Volatile private var onDone: ((String?) -> Unit)? = null
    private val completion = Any()
    private var completed = false
    private val playback = Any()

    fun start() {
        thread(name = "tts-fetch") { fetchLoop() }
        thread(name = "tts-play") { playLoop() }
    }

    fun say(text: String) {
        if (text.isNotBlank() && !stopped && fetching && sentences.offer(text) == SpeechQueue.Admission.FULL) {
            throw IOException("Speech queue full")
        }
    }

    /** No more sentences. [done] runs on the play thread once everything has been heard. */
    fun finish(done: (error: String?) -> Unit) {
        val alreadyDone = synchronized(completion) {
            if (completed) true else { onDone = done; false }
        }
        if (alreadyDone) { if (!stopped || silenced) done(error); return }
        sentences.finish()
    }

    /** Silences immediately; [finish]'s callback won't run. */
    fun stop() {
        if (stopped) return
        stopped = true
        fetching = false
        sentences.close()
        chunks.clear()
        chunks.offer(END_PCM)
        call?.cancel()
        synchronized(playback) { track?.let { runCatching { it.pause(); it.flush() } } }
        AudioPlayback.stopped(this)
    }

    /** Stop only speech, allowing the owning turn's completion callback to settle normally. */
    fun silence() { silenced = true; stop() }

    private fun fetchLoop() {
        var first = true
        try {
            while (!stopped && fetching) {
                val text = sentences.take() ?: break
                try {
                    fetch(text, first)
                    first = false
                } catch (e: IOException) {
                    if (!stopped && error == null) error = "Speech failed: ${friendly(e)}"
                    break // a failing voice service fails every sentence; the text is on screen anyway
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            if (!queueChunk(END_PCM)) chunks.offer(END_PCM)
        }
    }

    private fun fetch(text: String, firstSentence: Boolean) {
        if (stopped || !fetching) return
        val c = newCall(text)
        call = c
        if (stopped || !fetching) { c.cancel(); return }
        c.execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError(resp.code, errorMessage(resp.body?.string().orEmpty()))
            val ins = resp.body?.byteStream() ?: return
            val buf = ByteArray(8192)
            var head = true
            var carry = -1 // an odd trailing byte, completed by the next read
            while (!stopped) {
                val n = ins.read(buf)
                if (n < 0) break
                var data = buf.copyOf(n)
                if (head) {
                    head = false
                    if (firstSentence) Wav.sampleRate(data)?.let { rate = it } // before the first chunk is queued
                    data = Wav.stripHeader(data)
                }
                if (carry >= 0) {
                    data = byteArrayOf(carry.toByte()) + data
                    carry = -1
                }
                if (data.size % 2 == 1) {
                    carry = data.last().toInt() and 0xff
                    data = data.copyOf(data.size - 1)
                }
                if (data.isNotEmpty() && !queueChunk(data)) break
            }
        }
    }

    /** Backpressure is cancellable even when playback failed and the bounded queue is full. */
    private fun queueChunk(chunk: ByteArray): Boolean {
        while (!stopped && fetching) if (chunks.offer(chunk, 100, TimeUnit.MILLISECONDS)) return true
        return false
    }

    private fun playLoop() {
        var t: AudioTrack? = null
        var frames = 0L
        var r = rate
        try {
            while (!stopped) {
                val chunk = chunks.take()
                if (chunk === END_PCM) break
                if (t == null) {
                    r = rate
                    t = synchronized(playback) {
                        if (stopped) null else newTrack(r).also {
                            track = it
                            it.play()
                            AudioPlayback.started(this)
                        }
                    }
                    if (t == null) break
                    // 100 ms of silence first, so the amplifier waking up doesn't clip the first syllable.
                    val pad = ByteArray(r / 10 * 2)
                    synchronized(playback) { if (!stopped) t.write(pad, 0, pad.size) }
                    frames += pad.size / 2
                }
                var off = 0
                var progressAt = SystemClock.elapsedRealtime()
                while (off < chunk.size && !stopped) {
                    val w = synchronized(playback) {
                        if (stopped) 0 else t.write(chunk, off, minOf(chunk.size - off, r / 25 * 2), AudioTrack.WRITE_NON_BLOCKING)
                    }
                    if (w < 0) throw IOException("AudioTrack write failed: $w")
                    if (SystemClock.elapsedRealtime() - progressAt >= 3000) throw IOException("Audio output stalled")
                    off += w
                    if (w > 0) progressAt = SystemClock.elapsedRealtime() else Thread.sleep(5)
                }
                frames += chunk.size / 2
            }
            if (t != null && !stopped) {
                // Let the buffered tail play out before stopping.
                val deadline = SystemClock.elapsedRealtime() + t.bufferSizeInFrames * 1000L / r + 1000
                while (!stopped && (t.playbackHeadPosition.toLong() and 0xffffffffL) < frames &&
                    SystemClock.elapsedRealtime() < deadline
                ) Thread.sleep(25)
                t.stop()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (error == null) error = "Playback failed"
            fetching = false
            call?.cancel()
        } finally {
            // Playback can fail before finish() arrives, while fetch is blocked in take().
            fetching = false
            sentences.close()
            call?.cancel()
            track = null
            t?.let { runCatching { it.release() } }
            AudioPlayback.stopped(this)
            val done = synchronized(completion) { completed = true; onDone.also { onDone = null } }
            if (!stopped || silenced) done?.invoke(error)
        }
    }

    private fun newTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(min, rate / 2)) // >= 250 ms
            .build()
    }

    private companion object {
        val END_PCM = ByteArray(0)
    }
}
