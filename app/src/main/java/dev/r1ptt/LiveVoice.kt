package dev.r1ptt

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.r1ptt.audio.MicStream
import dev.r1ptt.audio.CaptureAdmission
import dev.r1ptt.audio.PcmPlayer
import dev.r1ptt.audio.Recorder
import dev.r1ptt.data.Config
import dev.r1ptt.data.Msg
import dev.r1ptt.net.LiveProtocol
import dev.r1ptt.net.LiveSession
import dev.r1ptt.net.SocketPump

/** App-owned voice exchanges. Interrupted/failed sockets are discarded; completed ones stay warm. */
class LiveVoice(
    private val app: App,
    private val publish: (TurnState) -> Unit,
    private val fail: (String) -> Unit,
    private val settled: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = LiveTracker(SystemClock::elapsedRealtime)
    private val scope = ExchangeScope()
    private val player = PcmPlayer(LiveProtocol.SAMPLE_RATE,
        onStarted = { id -> main.post { if (active && turnId == id) TurnMetrics.event("playback_started", id) } },
        onFailure = { id -> main.post { if (active && turnId == id) failExchange("Audio output unavailable") } },
    )
    @Volatile private var session: LiveSession? = null
    private var mic: MicStream? = null
    private val micLock = Any()
    private val capture = CaptureAdmission()
    private var streaming = false
    private val preRoll = ArrayList<ByteArray>()
    private val held = ArrayDeque<Pair<Long, ByteArray>>()
    private var heldBytes = 0
    private val heard = StringBuilder()
    private val said = StringBuilder()
    private var active = false
    @Volatile private var turnId = 0L
    @Volatile private var level = 0f
    private var waitingForNet = false
    private var firstReply = false
    private val ticker = object : Runnable {
        override fun run() { tick(); if (active) main.postDelayed(this, 200) }
    }
    private val closer = Runnable { if (busy) scheduleClose() else closeSession() }
    val busy get() = active || mic != null
    val capturing get() = mic != null

    /** GPT-Live supplies no exchange cancellation/ID here; replace the socket on barge-in. */
    fun interrupt(): Boolean {
        if (!active || tracker.holding) return false
        TurnMetrics.event("interrupt_start", turnId)
        stopExchange(save = true, close = true)
        publish(TurnState())
        settled()
        return true
    }

    fun startCapture(): Boolean {
        main.removeCallbacks(closer)
        stopCapture()
        turnId = TurnMetrics.next()
        val warm = session?.takeIf { !it.gone }
        TurnMetrics.event("turn_start", turnId, "mode" to 0L, "warm" to if (warm != null) 1L else 0L,
            "ready" to if (warm?.started == true) 1L else 0L, "connection" to (warm?.id ?: 0L))
        val generation = synchronized(micLock) { capture.begin() }
        val m = MicStream(LiveProtocol.SAMPLE_RATE,
            { pcm -> onMicChunk(generation, pcm) },
            { captureFailed(generation, "Microphone unavailable; input may be partial") },
        ) { if (synchronized(micLock) { capture.accepts(generation) }) level = it }
        mic = if (m.start()) m else null
        if (mic == null) { stopCapture(); TurnMetrics.event("session_failed", turnId); settled() }
        return mic != null
    }

    fun holdStart() {
        if (mic == null) return
        synchronized(micLock) { capture.failure() }?.let { failExchange(it); return }
        flushExchange()
        tracker.reset()
        tracker.hold()
        clearHeld()
        firstReply = false
        active = true
        val s = session?.takeIf { !it.gone } ?: openSession()
        scope.begin(s.id)
        TurnMetrics.event("hold_start", turnId, "connection" to s.id)
        startTicker()
        if (!s.send(LiveProtocol.UNMUTE)) return
        synchronized(micLock) {
            for (pcm in preRoll) if (!s.send(LiveProtocol.append(pcm))) break
            preRoll.clear()
            streaming = !s.gone
        }
        publishState(LiveTracker.Phase.LISTENING)
    }

    fun holdEnd() {
        if (!active) { stopCapture(); return }
        TurnMetrics.event("release", turnId)
        // Close admission before stopping AudioRecord, then append MUTE behind accepted PCM.
        stopCapture(release = true)?.let { failExchange(it); return }
        val s = session ?: return failExchange("Voice connection closed; input may be partial")
        if (!s.send(LiveProtocol.MUTE)) return
        tracker.release()
        val cutoff = SystemClock.elapsedRealtime() - KEEP_TAIL_MS
        val tail = held.filter { it.first >= cutoff }.map { it.second }.dropWhile { !isSpeech(it) }
        clearHeld()
        for (pcm in tail) if (!play(pcm)) return
    }

    fun shortRelease(stoppedReply: Boolean) {
        stopCapture()
        if (!active) scheduleClose()
        settled()
    }

    fun warm() {
        val cfg = app.store.value
        if (app.store.loadError != null || !cfg.liveVoice || !cfg.live.warm) return
        if (session?.takeIf { !it.gone } == null) openSession()
        if (!busy) scheduleClose()
    }
    fun screenOff() { if (!busy) closeSession() }
    fun reset() { stopExchange(save = true, close = true); settled() }

    private fun stopCapture(release: Boolean = false): String? {
        val m = mic
        val fault = synchronized(micLock) {
            val failure = capture.close()
            streaming = false
            preRoll.clear()
            failure
        }
        if (release) TurnMetrics.event("release_gate_closed", turnId)
        mic = null
        m?.stop()
        return fault
    }

    private fun captureFailed(generation: Long, message: String) {
        val failed = synchronized(micLock) { capture.fail(generation, message) }
        if (failed) main.post {
            if (synchronized(micLock) { capture.current(generation) }) failExchange(message)
        }
    }

    private fun onMicChunk(generation: Long, pcm: ByteArray) = synchronized(micLock) {
        if (!capture.accepts(generation)) return@synchronized
        if (streaming) session?.send(LiveProtocol.append(pcm))
        else if (preRoll.size < MAX_PREROLL_CHUNKS) preRoll += pcm
        else {
            captureFailed(generation, "Audio pre-roll full; input may be partial")
        }
    }

    private fun openSession(): LiveSession {
        val cfg = app.store.value
        val callbacks = CallbackBudget(LiveProtocol.SAMPLE_RATE * 2 * 8, 256)
        lateinit var s: LiveSession
        s = LiveSession(object : LiveSession.Listener {
            override fun onEvent(event: LiveProtocol.Event) {
                if (session !== s) return
                val size = when (event) {
                    is LiveProtocol.Event.Audio -> event.pcm.size
                    is LiveProtocol.Event.Heard -> event.delta.length * 2
                    is LiveProtocol.Event.Said -> event.delta.length * 2
                    else -> 0
                }
                if (!callbacks.reserve(size)) { s.reject("Voice callback queue full"); return }
                val token = scope.snapshot(s.id) // capture identity before posting to main
                main.postAtTime({
                    try {
                        if (session === s && !s.gone) {
                            if (event == LiveProtocol.Event.Started) tracker.restartWait()
                            else if (scope.accepts(token)) handle(event)
                        }
                    } finally { callbacks.release(size) }
                }, s, SystemClock.uptimeMillis())
            }
            override fun onFailure(message: String) {
                main.postAtFrontOfQueue {
                    if (session !== s) return@postAtFrontOfQueue
                    if (busy) failExchange(message) else closeSession()
                }
            }
            override fun onGone() { main.post { if (session === s && !busy) session = null } }
            override fun onMetric(name: String, stats: SocketPump.Stats) {
                TurnMetrics.event(name, turnId, "connection" to s.id,
                    "queued_bytes" to stats.queuedBytes, "max_queue_ms" to stats.maxQueueMs)
            }
        })
        session = s
        TurnMetrics.event("connect_start", turnId, "connection" to s.id)
        val start = LiveProtocol.start(cfg.live.model, instructions(cfg), cfg.live.voice, cfg.live.backendModel,
            cfg.live.reasoningEffort, cfg.live.webSearch)
        connectWhenOnline(s, cfg.live.url, cfg.keyFor(cfg.liveEndpoint), start, SystemClock.elapsedRealtime() + NET_WAIT_MS)
        return s
    }

    private fun connectWhenOnline(s: LiveSession, url: String, key: String, start: String, deadline: Long) {
        if (session !== s || s.gone) return
        when {
            app.radio.isOnline() -> {
                if (waitingForNet) TurnMetrics.event("network_available", turnId, "connection" to s.id)
                waitingForNet = false
                s.connect(url, key, start)
            }
            SystemClock.elapsedRealtime() >= deadline -> {
                if (busy) failExchange("No network; input may be partial") else closeSession()
            }
            else -> {
                if (!waitingForNet) TurnMetrics.event("network_wait", turnId, "connection" to s.id)
                waitingForNet = true
                main.postDelayed({ connectWhenOnline(s, url, key, start, deadline) }, 200)
            }
        }
    }

    private fun handle(event: LiveProtocol.Event) {
        when (event) {
            is LiveProtocol.Event.Audio -> {
                if (tracker.holding) {
                    if (event.pcm.size > MAX_HELD_BYTES) return failExchange("Reply audio too large")
                    val now = SystemClock.elapsedRealtime()
                    held.addLast(now to event.pcm)
                    heldBytes += event.pcm.size
                    while (held.isNotEmpty() && (held.first().first < now - KEEP_TAIL_MS || heldBytes > MAX_HELD_BYTES)) {
                        heldBytes -= held.removeFirst().second.size
                    }
                } else play(event.pcm)
            }
            is LiveProtocol.Event.Heard -> {
                if (event.delta.length > MAX_TEXT_CHARS - heard.length) return failExchange("Voice transcript too large")
                heard.append(event.delta)
            }
            is LiveProtocol.Event.Said -> {
                if (event.delta.length > MAX_TEXT_CHARS - said.length) return failExchange("Voice reply too large")
                said.append(event.delta)
            }
            LiveProtocol.Event.DelegationStarted -> tracker.delegationStarted()
            is LiveProtocol.Event.Backend -> tracker.backend(event.finished, event.searching)
            else -> {} // transport owns Started/Failed/Closed
        }
    }

    private fun play(pcm: ByteArray): Boolean {
        val speech = isSpeech(pcm)
        if (speech && !firstReply) { firstReply = true; TurnMetrics.event("first_reply", turnId) }
        if (!player.play(pcm, turnId, speech)) { failExchange("Reply audio queue full"); return false }
        tracker.audio(speech)
        return true
    }

    private fun tick() {
        if (!active) return
        val phase = tracker.phase(player.busy())
        when {
            tracker.expired() -> failExchange("Voice turn timed out; input may be partial")
            !waitingForNet && tracker.noReply() -> failExchange("No reply; please try again")
            phase == LiveTracker.Phase.IDLE -> finishExchange()
            else -> publishState(phase)
        }
    }

    private fun publishState(phase: LiveTracker.Phase) {
        val cfg = app.store.value
        publish(when (phase) {
            LiveTracker.Phase.LISTENING -> TurnState(Phase.LISTENING, level, heard.toString().trim(), note = if (waitingForNet) CONNECTING else "")
            LiveTracker.Phase.WAITING -> TurnState(Phase.THINKING, heard = heard.toString().trim(), reply = said.toString().trim(), note = if (waitingForNet) CONNECTING else "")
            LiveTracker.Phase.LOOKING_UP -> TurnState(Phase.THINKING, heard = heard.toString().trim(), reply = said.toString().trim(), note = if (tracker.lookingUpWeb) "Searching the web…" else "Thinking (${cfg.live.backendModel})…")
            LiveTracker.Phase.SPEAKING -> TurnState(Phase.SPEAKING, heard = heard.toString().trim(), reply = said.toString().trim())
            LiveTracker.Phase.IDLE -> TurnState()
        })
    }

    private fun finishExchange() {
        stopCapture()
        scope.end()
        flushExchange()
        tracker.reset()
        active = false
        main.removeCallbacks(ticker)
        TurnMetrics.event("exchange_complete", turnId)
        publish(TurnState())
        scheduleClose()
        settled()
    }

    private fun failExchange(message: String) {
        TurnMetrics.event("session_failed", turnId)
        stopExchange(save = false, close = true)
        fail(message)
        settled()
    }

    private fun stopExchange(save: Boolean, close: Boolean) {
        scope.end() // invalidate posted transcript/backend callbacks before changing state
        stopCapture()
        if (close) closeSession()
        player.flush()
        TurnMetrics.event("playback_flushed", turnId)
        if (save) flushExchange() else { heard.setLength(0); said.setLength(0) }
        tracker.reset()
        clearHeld()
        waitingForNet = false
        active = false
        main.removeCallbacks(ticker)
    }

    private fun clearHeld() { held.clear(); heldBytes = 0 }
    private fun flushExchange() {
        val q = heard.toString().trim(); val a = said.toString().trim()
        if (q.isNotEmpty()) app.history.add(Msg.USER, q)
        if (a.isNotEmpty()) app.history.add(Msg.ASSISTANT, a)
        heard.setLength(0); said.setLength(0)
    }
    private fun scheduleClose() {
        main.removeCallbacks(closer)
        main.postDelayed(closer, app.store.value.live.idleCloseSec.coerceIn(5, 600) * 1000L)
    }
    private fun closeSession() {
        main.removeCallbacks(closer)
        val s = session
        session = null // fence callbacks before cancel/close
        waitingForNet = false
        if (s != null) {
            s.close()
            main.removeCallbacksAndMessages(s) // socket termination fences the reader before removal
            TurnMetrics.event("session_closed", turnId, "connection" to s.id)
        }
    }
    private fun startTicker() { main.removeCallbacks(ticker); main.post(ticker) }
    private fun instructions(cfg: Config): String {
        val recent = app.history.messages.takeLast(8).joinToString("\n") {
            (if (it.role == Msg.USER) "User: " else "You: ") + it.text.take(300)
        }.takeLast(2000)
        return buildString {
            append(cfg.systemPrompt)
            append(TurnController.NO_DEVICE_ACTIONS)
            append(" The user holds a button while talking and lets go when finished.")
            if (recent.isNotBlank()) append("\n\nEarlier in this conversation:\n").append(recent)
        }
    }
    private fun isSpeech(pcm: ByteArray) = Recorder.rms(pcm, pcm.size) > 0.004f
    private companion object {
        const val KEEP_TAIL_MS = 1500L
        const val MAX_HELD_BYTES = LiveProtocol.SAMPLE_RATE * 2 * 3 / 2
        const val MAX_PREROLL_CHUNKS = 250
        const val MAX_TEXT_CHARS = 65_536
        const val NET_WAIT_MS = 30_000L
        const val CONNECTING = "Connecting to Wi-Fi…"
    }
}
