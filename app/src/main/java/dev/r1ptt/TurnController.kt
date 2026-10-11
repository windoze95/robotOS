package dev.r1ptt

import android.util.Log
import dev.r1ptt.audio.Clip
import dev.r1ptt.audio.Earcon
import dev.r1ptt.audio.Recorder
import dev.r1ptt.audio.SentenceSplitter
import dev.r1ptt.audio.Speaker
import dev.r1ptt.audio.SpeechText
import dev.r1ptt.data.Msg
import dev.r1ptt.data.Config
import dev.r1ptt.input.Gestures
import dev.r1ptt.input.CapturedInput
import dev.r1ptt.input.InputRoute
import dev.r1ptt.net.CallRegistry
import dev.r1ptt.net.ChatClient
import dev.r1ptt.net.ChatRequest
import dev.r1ptt.net.Http
import dev.r1ptt.net.SttClient
import dev.r1ptt.net.TtsClient
import dev.r1ptt.net.friendly
import dev.r1ptt.workspace.SharedChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Phase {
    IDLE, LISTENING, DICTATING, TRANSCRIBING, THINKING, ANSWERING, SPEAKING, ERROR;

    val active: Boolean get() = this != IDLE && this != ERROR
}

data class TurnState(
    val phase: Phase = Phase.IDLE,
    /** Microphone level 0..1 while listening. */
    val level: Float = 0f,
    /** What the user said this turn, as transcribed live (voice turns). */
    val heard: String = "",
    /** The reply streamed so far (until it lands in the history). */
    val reply: String = "",
    /** A short status line: errors, "New conversation", agent progress. */
    val note: String = "",
    /** The orange stage was tapped: this voice turn's words go to the text field, not to Hermes. */
    val editing: Boolean = false,
)

/** Foreground chat or private-draft context: where dictation and button gestures go. */
interface DictationTarget {
    fun isActive(): Boolean
    /** A draft screen can own the button while dictation is unavailable or awaiting consent. */
    fun canDictate(): Boolean = true
    /** Private drafts never opt in to the debug clip archive. */
    fun privateDictation(): Boolean = false
    fun dictationUnavailable() {}
    fun consumeDoubleTap(): Boolean = false

    /** Provisional words from live transcription, shown in place at the cursor until [insert]. */
    fun showPartial(text: String)

    /** Final text: replaces the provisional words, or goes in at the cursor. */
    fun insert(text: String)

    /** Drops the provisional words (nothing was said after all). */
    fun clearPartial()

    /** Keeps the provisional words as typed text (the dictation was interrupted or failed). */
    fun keepPartial()

    /** Handles a tap: chat sends; Messages opens review. False if there is nothing to do. */
    fun sendTyped(): Boolean
    fun hideKeyboard()

    /** A voice turn kept to edit has landed in this chat's draft: open the text field on it. */
    fun editDraft() {}
}

/**
 * Everything one press of the button sets in motion:
 *
 *   hold → record → release → transcribe → (keyboard up?  insert into the text field)
 *                                          (otherwise    → ask Hermes  → stream reply → speak)
 *
 * A new press always interrupts whatever is going on. Gesture callbacks arrive on the main thread;
 * network work runs on a small pool and is cancelled with its turn.
 */
class TurnController(private val app: App) : Gestures.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pool = Executors.newCachedThreadPool()
    private val stt = SttClient()
    private val chat = ChatClient()
    private val tts = TtsClient { app.store.value }

    private val _state = MutableStateFlow(TurnState())
    val state: StateFlow<TurnState> = _state

    @Volatile var target: DictationTarget? = null
        private set
    // Keep the private-screen boundary during sleep/pause, when no Activity is resumed yet.
    // Attaching Home explicitly restores ordinary voice behavior.
    private var privateInputScreen = false
    private var approvalReviewBack: (() -> Unit)? = null
    private var approvalPressBack: (() -> Unit)? = null
    private var approvalBlockedPress = false

    /** Approval is touch-only. Capture the review boundary before any microphone/stop work. */
    fun guardApprovalReview(back: (() -> Unit)?) { approvalReviewBack = back }

    fun attachTarget(value: DictationTarget) {
        target?.takeIf { it !== value }?.let(::detachTarget)
        target = value
        privateInputScreen = value.privateDictation()
    }

    fun detachTarget(value: DictationTarget) {
        if (target !== value) return
        cancelDictation(value)
        target = null
    }

    fun cancelDictation(value: DictationTarget) {
        if (destination.target !== value) return
        pressGeneration++
        stopActive()
        settle()
    }

    val busy: Boolean get() = _state.value.phase.active || recorder != null || stream != null

    @Volatile private var recorder: Recorder? = null
    /** Live transcription for this press (gpt-live-transcribe), when transcription is on OpenAI. */
    @Volatile private var stream: SttStream? = null
    @Volatile private var current: Turn? = null
    val activeConversation: String? get() = current?.conversation
    private var dictating = false
    /** This press's words were kept to edit (the orange stage was tapped before release). */
    private var editingPress = false
    private var streamTurn = false
    private var interrupted = false
    private var noteJob: Job? = null
    private var pressGeneration = 0L
    private var updateBlockedPress = false
    private var dictationBlockedPress = false
    private var destination = CapturedInput<DictationTarget>(null)
    private var transcriptionConfig = app.store.value

    private class Turn(val id: Long, var conversation: String, val input: CapturedInput<DictationTarget>, val sttConfig: Config) {
        var job: Job? = null
        var speaker: Speaker? = null
        val reply = StringBuffer()
        var replySaved = false
        /** Its words go to the chat's draft instead of Hermes. */
        var editing = false
        @Volatile var remoteOperation: String? = null
        @Volatile var sharedOperation: String? = null
        @Volatile var remoteStopRequested = false
    }

    init {
        clipDir().listFiles()?.forEach { it.delete() } // leftovers from a crash
    }

    // ---- gestures (main thread) ----

    override fun onPress(atMs: Long) {
        approvalPressBack = approvalReviewBack
        approvalBlockedPress = approvalPressBack != null
        if (approvalBlockedPress) { app.screen.notePress(); return }
        updateBlockedPress = app.workspace.blocked || !app.updates.allowVoice()
        if (updateBlockedPress) {
            if (app.workspace.blocked) { stopActive(); fail("Workspace access is restricted on this R1"); settle() }
            return
        }
        pressGeneration++
        app.store.loadError?.let {
            stopActive()
            fail(it)
            settle()
            return
        }
        app.screen.notePress()
        interrupted = stopActive()
        val cfg = app.store.value
        destination = CapturedInput(target?.takeIf { it.isActive() })
        dictating = destination.target != null
        editingPress = false
        dictationBlockedPress = (destination.target == null && privateInputScreen) || destination.target?.canDictate() == false
        if (dictationBlockedPress) { settle(); return }
        transcriptionConfig = cfg // a mid-press settings change cannot redirect private audio
        // Keyboard closed → a turn for Hermes; keyboard open → dictation. Both stream through live
        // transcription when it's on OpenAI.
        streamTurn = cfg.liveStt
        app.radio.onActivity()
        app.screen.holdAwake()
        if (streamTurn) {
            val generation = pressGeneration
            lateinit var s: SttStream
            s = SttStream(app, config = cfg, onPartial = { if (generation == pressGeneration) partial(it) }, onFailure = { message ->
                if (stream === s) {
                    stream = null
                    s.cancel()
                    destination.target?.takeIf { it === target }?.keepPartial()
                    fail(message)
                    settle()
                }
            }) { level ->
                if (generation == pressGeneration) _state.update { if (it.phase == Phase.LISTENING || it.phase == Phase.DICTATING) it.copy(level = level) else it }
            }
            stream = if (s.start()) s else null // connects now; nothing is sent unless it becomes a hold
            return
        }
        val r = Recorder(clipDir()) { level ->
            _state.update { if (it.phase == Phase.LISTENING || it.phase == Phase.DICTATING) it.copy(level = level) else it }
        }
        recorder = if (r.start()) r else null
    }

    override fun onHoldStart() {
        if (approvalBlockedPress) { note("Resolve the pending request first"); return }
        if (updateBlockedPress || app.updates.installing) return
        if (dictationBlockedPress) { target?.dictationUnavailable(); return }
        app.store.loadError?.let { fail(it); settle(); return }
        val cfg = app.store.value
        val micMissing = if (streamTurn) stream == null else recorder == null
        if (micMissing) {
            fail("Microphone unavailable")
            settle()
            return
        }
        noteJob?.cancel()
        if (cfg.earcons && !cfg.quiet) Earcon.listening()
        if (!dictating) HomeActivity.bringToFront(app)
        _state.value = TurnState(phase = if (dictating) Phase.DICTATING else Phase.LISTENING)
        if (streamTurn) stream?.hold() else Http.warm(transcriptionConfig.stt.baseUrl)
    }

    override fun onHoldEnd() {
        if (approvalBlockedPress) return
        if (updateBlockedPress || app.updates.installing) return
        if (dictationBlockedPress) return
        if (app.store.loadError != null) return settle()
        if (streamTurn) {
            val s = stream ?: return settle() // the mic never opened; onHoldStart already said so
            stream = null
            if (s.durationMs < MIN_CLIP_MS || s.peak < SILENCE) {
                s.cancel()
                destination.target?.takeIf { it === target }?.clearPartial()
                note("Didn't hear anything")
            } else {
                launch(id = s.turnId, emitStart = false) { turn -> turn.editing = editingPress; transcribeStream(turn, s) }
            }
            settle()
            return
        }
        val r = recorder ?: return settle() // the mic never opened; onHoldStart already said so
        recorder = null
        val clip = r.stop()
        when {
            clip == null -> fail("Recording failed")
            clip.durationMs < MIN_CLIP_MS || clip.peak < SILENCE -> {
                discard(clip, destination.target?.privateDictation() == true)
                note("Didn't hear anything")
            }
            else -> launch { turn -> turn.editing = editingPress; transcribe(turn, clip) }
        }
        settle()
    }

    override fun onShortRelease() {
        if (approvalBlockedPress) return
        if (updateBlockedPress || app.updates.installing) return
        if (dictationBlockedPress) return
        stream?.cancel()
        stream = null
        recorder?.cancel()
        recorder = null
        settle()
    }

    override fun onTap() {
        if (approvalBlockedPress) {
            if (!app.screen.tapWasWake()) approvalPressBack?.invoke()
            return
        }
        if (updateBlockedPress || app.updates.installing) return
        val t = target
        when {
            interrupted -> interrupted = false // that press already stopped the reply
            privateInputScreen && app.screen.tapWasWake() -> {} // waking a private draft cannot review/send it
            t != null && t.isActive() -> if (!t.sendTyped()) t.hideKeyboard()
            app.screen.tapWasWake() -> {} // the tap that turned the screen on
            else -> app.screen.sleepNow()
        }
    }

    override fun onDoubleTap() {
        if (approvalBlockedPress || approvalReviewBack != null) return
        if (updateBlockedPress || app.updates.installing) return
        if (privateInputScreen || target?.takeIf { it.isActive() }?.consumeDoubleTap() == true) {
            pressGeneration++
            stopActive()
            settle()
            return
        }
        newConversation()
    }

    /** Service termination is terminal for microphone, sockets, playback and the wake lock. */
    fun shutdown() {
        pressGeneration++
        stopActive(cancelRemote = false)
        noteJob?.cancel()
        settle()
    }

    /** Stop audio without claiming that remote agent work has ended. */
    fun stopSpeech() {
        current?.speaker?.silence()
    }

    /** Explicit local interruption. Runs-backed cancellation is layered onto this in Hermes controls. */
    fun cancelTurn() { pressGeneration++; stopActive(); settle() }

    /**
     * The orange stage was swiped away: the voice turn ends before its words reach Hermes, and
     * the microphone closes now rather than at release. False once the words have been handed on.
     */
    fun cancelVoiceTurn(): Boolean {
        if (!voicePending()) return false
        cancelTurn() // the release that follows finds no capture and sends nothing
        note("Cancelled")
        return true
    }

    /**
     * The orange stage was tapped: the words go to the chat's text field to edit instead of to
     * Hermes. There is no way back to sending; a tap of the button sends the edited text.
     */
    fun editVoiceTurn(): Boolean {
        if (!voicePending()) return false
        editingPress = true
        current?.editing = true
        _state.update { it.copy(editing = true) }
        return true
    }

    /** A voice turn for Hermes, held or just released, whose words have not been handed on yet. */
    private fun voicePending(): Boolean = !dictating && when (_state.value.phase) {
        Phase.LISTENING -> stream != null || recorder != null
        Phase.TRANSCRIBING -> current != null
        else -> false
    }

    fun newConversation() {
        pressGeneration++
        interrupted = false
        stopActive(cancelRemote = false)
        settle()
        app.history.newConversation()
        note("New conversation")
    }

    /** Deliberate read-aloud or a permitted Hermes announcement; shares the interruptible speaker. */
    fun speakText(text: String, permitted: () -> Boolean = { true }): Boolean {
        val cfg = app.store.value
        if (text.isBlank() || text.length > 16_384 || busy || dev.r1ptt.audio.AudioPlayback.active ||
            cfg.quiet || app.workspace.blocked || !permitted() || !app.updates.allowVoice() || app.store.loadError != null) return false
        destination = CapturedInput(null)
        dictating = false
        app.radio.onActivity()
        app.screen.holdAwake()
        _state.value = TurnState(phase = Phase.SPEAKING)
        launch { turn ->
            val latest = app.store.value
            if (latest.quiet || !permitted()) return@launch
            val speaker = Speaker(tts::call, latest.tts.sampleRate).also { turn.speaker = it; it.start() }
            speaker.say(SpeechText.clean(text))
            finishSpeech(turn, speaker)
        }
        return true
    }

    /** A message typed into the launcher's text field: Hermes + text-to-speech. */
    fun sendText(text: String) {
        if (app.workspace.blocked) { fail("Workspace access is restricted on this R1"); return }
        if (!app.updates.allowVoice()) return
        app.store.loadError?.let { fail(it); settle(); return }
        pressGeneration++
        stopActive()
        destination = CapturedInput(null)
        dictating = false
        dictationBlockedPress = false
        app.radio.onActivity()
        app.screen.holdAwake()
        launch { turn -> ask(turn, text) }
    }

    // ---- the turn ----

    /** Only the explicit installer uses this; an active exchange is never interrupted for an update. */
    fun prepareForUpdate(): Boolean {
        return !busy && current == null
    }

    private fun launch(id: Long = TurnMetrics.next(), emitStart: Boolean = true, block: suspend (Turn) -> Unit) {
        val turn = Turn(id, app.history.convId, destination, transcriptionConfig)
        if (emitStart) TurnMetrics.event("turn_start", id, "mode" to 2L, "warm" to 0L, "ready" to 0L, "connection" to 0L)
        current = turn
        turn.job = scope.launch {
            try {
                block(turn)
            } catch (e: CancellationException) {
                throw e
            } catch (_: dev.r1ptt.hermes.HermesTaskPaused) {
                if (current === turn) note("${app.store.value.agent.displayName} needs your decision")
            } catch (e: Throwable) {
                if (turn.input.target?.privateDictation() == true) Log.w(TAG, "Private dictation failed")
                else Log.w(TAG, "turn failed", e)
                TurnMetrics.event("session_failed", turn.id)
                if (current === turn) fail(friendly(e))
            } finally {
                turn.speaker?.stop()
                // Interrupted mid-reply: keep what arrived so the screen and the context match.
                if (turn.remoteOperation == null && turn.sharedOperation == null && !turn.replySaved && turn.reply.isNotBlank())
                    app.history.add(Msg.ASSISTANT, turn.reply.toString().trim() + " …", conversation = turn.conversation)
                if (current === turn) {
                    current = null
                    TurnMetrics.event("exchange_complete", turn.id)
                    settle()
                }
            }
        }
    }

    private suspend fun transcribe(turn: Turn, clip: Clip) {
        val cfg = turn.sttConfig
        _state.value = TurnState(phase = Phase.TRANSCRIBING, editing = turn.editing)
        val text = try {
            awaitNetwork()
            io { calls -> stt.transcribe(cfg, clip, calls) }
        } finally {
            discard(clip, turn.input.target?.privateDictation() == true)
        }
        handleTranscript(turn, text)
    }

    /** Live-transcribed press: the final text is usually ready ~0.5 s after release. */
    private suspend fun transcribeStream(turn: Turn, s: SttStream) {
        val cfg = turn.sttConfig
        _state.update { it.copy(phase = Phase.TRANSCRIBING, level = 0f) }
        try {
            val text = s.finish() ?: run {
                check(s.usableRecording) { "Input may be partial; please try again" }
                // The live session failed (network, server): transcribe the recording instead.
                Log.w(TAG, "live transcription unavailable; transcribing the recording")
                val clip = s.recording(clipDir())
                try {
                    awaitNetwork()
                    io { calls -> stt.transcribe(cfg, clip, calls) }
                } finally {
                    discard(clip, turn.input.target?.privateDictation() == true)
                }
            }
            handleTranscript(turn, text)
        } finally {
            s.cancel()
            if (current === turn) turn.input.target?.takeIf { it === target }?.keepPartial()
        }
    }

    /** Dictation goes into the text field; anything else is a question for Hermes unless kept to edit. */
    private suspend fun handleTranscript(turn: Turn, transcript: String) {
        val text = transcript.trim()
        val route = turn.input.route(target, target?.isActive() == true)
        if (text.isEmpty()) {
            if (route == InputRoute.DRAFT) turn.input.target?.clearPartial()
            note("Didn't catch that")
            return
        }
        if (turn.editing) return keepToEdit(turn, text)
        when (route) {
            InputRoute.CHAT -> ask(turn, text)
            InputRoute.DRAFT -> { turn.input.target?.insert(text); idle() }
            InputRoute.DISCARDED -> idle() // leaving a draft never sends its words to Hermes
        }
    }

    /**
     * Words kept to edit wait at the end of their chat's draft, and the text field opens on them.
     * They go to the draft directly, not through dictation, which needs the keyboard already up.
     */
    private fun keepToEdit(turn: Turn, text: String) {
        app.history.appendDraft(text, conversation = turn.conversation)
        idle()
        if (turn.conversation == app.history.convId) target?.editDraft()
    }

    /** Words so far from live transcription: in the text field when dictating, else on screen. */
    private fun partial(text: String) {
        if (dictating) {
            if (destination.route(target, target?.isActive() == true) == InputRoute.DRAFT) destination.target?.showPartial(text)
        } else {
            _state.update {
                if (it.phase == Phase.LISTENING || it.phase == Phase.TRANSCRIBING) it.copy(heard = text) else it
            }
        }
    }

    private suspend fun ask(turn: Turn, text: String) {
        check(!app.workspace.blocked) { "Workspace access is restricted on this R1" }
        if (app.workspace.identity.paired) { askShared(turn, text); return }
        val cfg = app.store.value
        app.history.errors.value?.let { throw IOException(it) }
        // The R1 is never the agent: every turn reaches Hermes as said, and Hermes texts people
        // through its robotos tools.
        app.history.add(Msg.USER, text, conversation = turn.conversation)
        app.history.saveDraft(text, conversation = turn.conversation)
        _state.value = TurnState(phase = Phase.THINKING)
        awaitNetwork()
        val req = ChatRequest.build(cfg.copy(systemPrompt = cfg.systemPrompt + deviceContext(cfg)),
            listOf(Msg(Msg.USER, text)), turn.conversation)
        val speechConfig = app.store.value
        val speaker = if (speechConfig.tts.enabled && !speechConfig.quiet) {
            Speaker(tts::call, speechConfig.tts.sampleRate).also { turn.speaker = it; it.start() }
        } else null
        val splitter = SentenceSplitter()

        val finalReply = io { calls ->
            val runsReply = app.hermesRuns.execute(cfg.copy(systemPrompt = cfg.systemPrompt + deviceContext(cfg)), text,
                "r1ptt-${turn.conversation}", calls,
                onDelta = { d ->
                    if (current === turn) {
                        if (turn.reply.isEmpty()) TurnMetrics.event("first_reply", turn.id)
                        turn.reply.append(d)
                        _state.update { it.copy(phase = Phase.ANSWERING, reply = it.reply + d, note = "") }
                    }
                },
                onStatus = { s -> if (current === turn) _state.update { it.copy(note = s) } },
                onAdmitted = {
                    turn.remoteOperation = app.hermesRuns.state.value.run?.operationId
                    app.history.confirmDraft(text, turn.conversation)
                    if (turn.remoteStopRequested) app.hermesRuns.stop()
                })
            if (runsReply != null) {
                // Hermes deltas may include commentary. Speak its authoritative final answer once.
                if (current === turn && !app.store.value.quiet && app.store.value.tts.enabled)
                    speaker?.let { s -> splitter.feed(runsReply).forEach { s.say(SpeechText.clean(it)) } }
                runsReply
            } else chat.stream(
                req, calls,
                onDelta = { d ->
                    if (current === turn) {
                        if (turn.reply.isEmpty()) TurnMetrics.event("first_reply", turn.id)
                        turn.reply.append(d)
                        _state.update { it.copy(phase = Phase.ANSWERING, reply = it.reply + d, note = "") }
                        if (!app.store.value.quiet && app.store.value.tts.enabled)
                            speaker?.let { s -> splitter.feed(d).forEach { s.say(SpeechText.clean(it)) } }
                    }
                },
                onStatus = { s -> if (current === turn) _state.update { it.copy(note = s) } },
                onAccepted = { app.history.confirmDraft(text, turn.conversation) },
            )
        }

        val reply = finalReply.trim()
        turn.reply.setLength(0); turn.reply.append(reply)
        if (reply.isEmpty()) {
            turn.remoteOperation?.let { operation ->
                io { app.history.awaitWrites(); app.hermesRuns.markDelivered(operation) }
            }
            note("No reply")
            return
        }
        app.history.add(Msg.ASSISTANT, reply, conversation = turn.conversation, deliveryKey = turn.remoteOperation.orEmpty())
        turn.remoteOperation?.let { operation ->
            io { app.history.awaitWrites(); app.hermesRuns.markDelivered(operation) }
        }
        turn.replySaved = true
        if (speaker == null) {
            idle()
            return
        }
        if (app.store.value.quiet || !app.store.value.tts.enabled) speaker.silence()
        else splitter.flush().forEach { speaker.say(SpeechText.clean(it)) }
        finishSpeech(turn, speaker)
    }

    private suspend fun askShared(turn: Turn, text: String) {
        app.history.errors.value?.let { throw IOException(it) }
        val oldConversation = turn.conversation
        turn.conversation = SharedChat.conversation(app, oldConversation)
        app.history.saveDraft(text, turn.conversation)
        if (oldConversation != turn.conversation) app.history.confirmDraft(text, oldConversation)
        _state.value = TurnState(phase = Phase.THINKING)
        awaitNetwork()
        val cfg = app.store.value
        val identity = app.workspace.identity.value
        val context = cfg.systemPrompt + deviceContext(cfg) +
            " This request came from robotOS device_id=${identity.getString("device_id")}. Use that exact device_id for device tools unless the user explicitly chooses another device."
        val reply = SharedChat.ask(app, turn.conversation, text, context,
            onAdmitted = { runId ->
                turn.sharedOperation = runId
                app.history.confirmDraft(text, turn.conversation)
                if (turn.remoteStopRequested) scope.launch { runCatching { app.workspace.runAction(runId, "stop") } }
            },
            onStatus = { status -> if (current === turn) _state.update { it.copy(note = status) } }).trim()
        turn.replySaved = true // SharedChat imported the authoritative host result.
        if (current !== turn || app.workspace.blocked) return
        if (reply.isEmpty()) { note("No reply"); return }
        val speech = app.store.value
        if (speech.quiet || !speech.tts.enabled) { idle(); return }
        val speaker = Speaker(tts::call, speech.tts.sampleRate).also { turn.speaker = it; it.start() }
        speaker.say(SpeechText.clean(reply))
        finishSpeech(turn, speaker)
    }

    private suspend fun finishSpeech(turn: Turn, speaker: Speaker) {
        _state.update { it.copy(phase = Phase.SPEAKING, reply = "") }
        val error = suspendCancellableCoroutine<String?> { cont -> speaker.finish { e -> cont.resume(e) } }
        turn.speaker = null
        if (error != null) note(error) else idle()
    }

    private suspend fun awaitNetwork() {
        if (app.radio.isOnline()) return
        // Wi-Fi is coming back from an idle cut; say so rather than look stuck.
        _state.update { it.copy(note = "Connecting to Wi-Fi…") }
        if (!app.radio.awaitOnline(NET_WAIT_MS)) throw IOException("No network")
        _state.update { it.copy(note = "") }
    }

    /** Runs blocking network code on the pool; cancelling the coroutine aborts its HTTP calls. */
    private suspend fun <T> io(block: (CallRegistry) -> T): T = suspendCancellableCoroutine { cont ->
        val calls = CallRegistry()
        cont.invokeOnCancellation { calls.cancelAll() }
        pool.execute {
            try {
                cont.resume(block(calls))
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }
    }

    /** Stops whatever is in progress (reply, speech, recording). True if something was. */
    private fun stopActive(cancelRemote: Boolean = true): Boolean {
        val t = current
        current = null
        if (cancelRemote) t?.remoteStopRequested = true
        val wasActive = t != null || _state.value.phase.active
        if (t != null) TurnMetrics.event("interrupt_start", t.id)
        t?.speaker?.stop()
        t?.remoteOperation?.takeIf { cancelRemote }?.let { operation ->
            app.hermesRuns.state.value.run?.takeIf { it.operationId == operation && it.active }?.let { app.hermesRuns.stop() }
        }
        t?.sharedOperation?.takeIf { cancelRemote }?.let { operation ->
            scope.launch { runCatching { app.workspace.runAction(operation, "stop") } }
        }
        if (t != null) TurnMetrics.event("playback_flushed", t.id)
        t?.job?.cancel()
        recorder?.cancel()
        recorder = null
        stream?.cancel()
        stream = null
        destination.target?.takeIf { it === target }?.keepPartial()
        if (wasActive) _state.value = TurnState()
        return wasActive
    }

    // ---- state helpers ----

    private fun idle() {
        _state.value = TurnState()
    }

    private fun note(msg: String) {
        _state.value = TurnState(note = msg)
        clearNoteLater(3000)
    }

    private fun fail(msg: String) {
        _state.value = TurnState(phase = Phase.ERROR, note = msg)
        if (app.store.value.earcons && !app.store.value.quiet) Earcon.error()
        clearNoteLater(6000)
    }

    private fun clearNoteLater(ms: Long) {
        noteJob?.cancel()
        noteJob = scope.launch {
            delay(ms)
            if (!_state.value.phase.active) _state.value = TurnState()
        }
    }

    /** Lets the CPU sleep again once nothing is recording or in flight. */
    private fun settle() {
        if (current == null && recorder == null && stream == null) app.screen.release()
    }

    private fun clipDir() = File(app.cacheDir, "clips")

    private fun discard(clip: Clip, privateDraft: Boolean = false) {
        if (app.store.value.saveClips && !privateDraft) {
            runCatching {
                val keep = File(app.getExternalFilesDir("clips"), clip.file.name)
                clip.file.copyTo(keep, overwrite = true)
            }
        }
        clip.file.delete()
    }

    companion object {
        private const val TAG = "r1ptt"
        private const val MIN_CLIP_MS = 400L
        /** Peak 100 ms RMS below this is silence (the button was held but nobody spoke). */
        private const val SILENCE = 0.002f
        private const val NET_WAIT_MS = 30_000L
        /** What Hermes is told about this device on every push-to-talk turn. */
        private const val HERMES_DEVICE = " You are the agent behind this Rabbit R1 (robotOS); it relays what the user says and speaks your reply. " +
            "Act on it with your robotos tools, for example to text someone: do what was asked, then say briefly what happened."

        /** The device context, plus the name the user gave the agent on this R1 when there is one. */
        internal fun deviceContext(cfg: Config) =
            HERMES_DEVICE + if (cfg.agent.name.isBlank()) "" else " The user calls you ${cfg.agent.name}."
    }
}
