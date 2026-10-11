package dev.r1ptt.hermes

import dev.r1ptt.data.Config
import dev.r1ptt.net.ApiError
import dev.r1ptt.net.CallRegistry
import dev.r1ptt.net.friendly
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class HermesRunState(val run: HermesRun? = null, val working: Boolean = false, val message: String = "", val executing: Boolean = false)
class HermesTaskPaused : IOException("Hermes needs your decision. Open Hermes to review the request.")

/** Durable control of one R1 task. Nothing polls while idle; foreground recovery is opt-in. */
class HermesRuns(
    directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val clientFactory: (dev.r1ptt.data.Agent) -> HermesClient = { HermesClient(it) },
    private val config: () -> Config,
) {
    private val ledger = HermesRunLedger(File(directory, "hermes-run.json"))
    private var storageError: String? = null
    private val initial = try {
        ledger.read()?.let { run ->
            if (run.runId.isBlank() && run.status == "submitting") run.copy(status = "submission_unknown").also(ledger::write) else run
        }
    } catch (_: Exception) { storageError = "Saved Hermes task could not be read. Keep this file for recovery."; null }
    private val mutable = MutableStateFlow(HermesRunState(initial, message = storageError.orEmpty()))
    val state: StateFlow<HermesRunState> = mutable
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "hermes-control").apply { isDaemon = true } }
    private val executing = AtomicBoolean(false)
    private val delivering = AtomicBoolean(false)
    private val controlExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "hermes-decisions").apply { isDaemon = true } }
    private val controlling = AtomicInteger(0)
    private val lock = Any()
    private var discovery: Triple<String, Long, HermesCapabilities>? = null
    private val visible = mutableSetOf<Any>()
    private var watching: CallRegistry? = null
    val canArchive: Boolean get() = state.value.run != null && !executing.get() && !state.value.working && state.value.run?.status != "submitting"

    fun capabilities(cfg: Config = config(), calls: CallRegistry = CallRegistry(), force: Boolean = false): HermesCapabilities {
        val id = HermesClient.connectionId(cfg.agent)
        synchronized(lock) { discovery?.takeIf { !force && it.first == id && clock() - it.second < 300_000 }?.let { return it.third } }
        return clientFactory(cfg.agent).discover(calls).also { synchronized(lock) { discovery = Triple(id, clock(), it) } }
    }

    /** Null means legacy chat was selected BEFORE any submission. Never fall back after dispatch. */
    fun execute(cfg: Config, text: String, sessionId: String, calls: CallRegistry,
        onDelta: (String) -> Unit, onStatus: (String) -> Unit, onAdmitted: () -> Unit = {}): String? {
        storageError?.let { throw IOException(it) }
        val caps = capabilities(cfg, calls)
        if (!caps.runs) {
            check(state.value.run?.active != true) { "Resolve the saved Hermes task before using legacy chat" }
            return null
        }
        synchronized(lock) {
            check(controlling.get() == 0) { "Finish the current Hermes control before sending another request" }
            check(!delivering.get()) { "The previous Hermes result is being saved" }
            check(executing.compareAndSet(false, true)) { "A Hermes task is already active" }
            mutable.value = mutable.value.copy(executing = true)
            watching?.cancelAll(); watching = null
        }
        try {
            val client = clientFactory(cfg.agent)
            var previous = state.value.run
            requireDelivered(previous)
            if (previous?.active == true) {
                checkConnection(previous, cfg)
                if (previous.runId.isBlank()) throw IOException("Check the previous submission in Hermes before sending another request")
                // A press can request stop before the replacement voice input finishes transcribing.
                val until = System.nanoTime() + 15_000_000_000L
                do {
                    applyStatus(previous!!.operationId, client.status(previous.runId, calls))
                    previous = state.value.run
                    if (previous?.terminal == true) break
                    if (previous?.status != "stopping" || System.nanoTime() >= until)
                        throw IOException("The previous Hermes task is still active. Stop or finish it first; your new request was not sent.")
                    Thread.sleep(500)
                } while (true)
            }
            // A task that was last seen running may have completed while this R1 was offline.
            // Preserve that newly recovered result until its original chat commits it.
            requireDelivered(previous)
            val payload = JSONObject().put("input", text).put("session_id", sessionId).put("instructions", cfg.systemPrompt)
                .apply {
                    // An omitted model selects the gateway default, including named profiles whose
                    // virtual model is not called "hermes-agent".
                    if (cfg.agent.provider.isNotBlank() || cfg.agent.model != "hermes-agent") put("model", cfg.agent.model)
                    if (cfg.agent.provider.isNotBlank()) put("provider", cfg.agent.provider)
                }.toString()
            val run = HermesRun(UUID.randomUUID().toString(), HermesClient.connectionId(cfg.agent), sessionId, payload, clock(),
                caps.durableIdempotency, caps.retentionSeconds, caps.stop, caps.approvals)
            save(run) // durable identity first; a failed write means no request is sent
            val admission = try { client.submit(payload, run.operationId, calls) } catch (e: Exception) {
                val rejected = e is ApiError && e.code in setOf(400, 401, 403, 404, 405, 413, 422, 429)
                change(run.operationId) { it.copy(status = if (rejected) "failed" else "submission_unknown", error = friendly(e)) }
                if (rejected) throw e
                throw IOException("Hermes submission could not be confirmed. Open Hermes to check it before retrying.", e)
            }
            change(run.operationId) { it.copy(runId = admission.runId, status = "queued", error = "") }
            onAdmitted()
            return follow(run.operationId, client, calls, onDelta, onStatus)
        } finally {
            synchronized(lock) {
                executing.set(false)
                mutable.value = mutable.value.copy(executing = false)
            }
            startVisibleWatch()
        }
    }

    private fun follow(operation: String, client: HermesClient, calls: CallRegistry,
        onDelta: (String) -> Unit = {}, onStatus: (String) -> Unit = {}): String {
        var reconnects = 0
        while (true) {
            val current = state.value.run?.takeIf { it.operationId == operation } ?: throw IOException("Task changed")
            if (current.terminal) return result(current)
            if (current.status == "waiting_for_approval") throw HermesTaskPaused()
            try {
                client.events(current.runId, current.lastSequence, calls) { event ->
                    val accepted = acceptEvent(operation, event)
                    val run = state.value.run ?: return@events false
                    if (accepted) {
                        if (event.name == "message.delta" && !run.transcriptIncomplete) onDelta(event.data.optString("delta"))
                        onStatus(run.label(config().agent.displayName))
                    }
                    !run.terminal && run.status != "waiting_for_approval"
                }
            } catch (e: Exception) {
                if (e is ApiError && e.code in listOf(401, 403)) throw e
                // Status is authoritative even when the stream was truncated or expired.
            }
            val after = state.value.run?.takeIf { it.operationId == operation } ?: throw IOException("Task changed")
            if (after.terminal) return result(after)
            if (after.status == "waiting_for_approval") throw HermesTaskPaused()
            try { applyStatus(operation, client.status(after.runId, calls)) }
            catch (e: Exception) {
                message("Connection interrupted. The task remains saved; open Hermes to check its status.")
                throw e
            }
            val refreshed = requireNotNull(state.value.run)
            if (refreshed.terminal) return result(refreshed)
            if (++reconnects > 3) throw IOException("Hermes is still working. Open Hermes to check progress.")
            Thread.sleep(500L * reconnects)
        }
    }

    private fun result(run: HermesRun): String {
        if (run.status == "completed") return run.output
        throw IOException(run.error.ifBlank { run.label(config().agent.displayName) })
    }

    private fun requireDelivered(run: HermesRun?) {
        if (run?.status == "completed" && !run.delivered)
            throw IOException("Save the previous Hermes result to Chats before starting another task")
    }

    /** An explicit screen check. Unknown admission is never silently resubmitted. */
    fun refresh() = control {
        val run = state.value.run ?: return@control
        if (run.runId.isBlank()) { message("Hermes may have received this request. Use Check submission to look up the same request safely."); return@control }
        val cfg = config(); checkConnection(run, cfg)
        applyStatus(run.operationId, clientFactory(cfg.agent).status(run.runId))
    }

    fun retrySubmission() = control {
        val run = state.value.run ?: return@control
        require(run.runId.isBlank() && run.status == "submission_unknown") { "This request already has a task" }
        require(run.durableIdempotency && run.retentionSeconds > 0 && clock() - run.createdAt in 0 until run.retentionSeconds * 1000) {
            "Hermes cannot safely retry this submission. Check the host before starting a new request."
        }
        val cfg = config(); checkConnection(run, cfg)
        val currentCapabilities = capabilities(cfg, force = true)
        require(currentCapabilities.durableIdempotency && clock() - run.createdAt < currentCapabilities.retentionSeconds * 1000) {
            "The current Hermes server cannot safely look up this submission"
        }
        val client = clientFactory(cfg.agent)
        val admission = client.submit(run.payload, run.operationId, CallRegistry())
        change(run.operationId) { it.copy(runId = admission.runId, status = "queued", error = "") }
        applyStatus(run.operationId, client.status(admission.runId))
    }

    fun stop() {
      val operation = state.value.run?.operationId ?: return
      control {
        val run = state.value.run?.takeIf { it.operationId == operation } ?: return@control
        if (run.terminal) return@control
        require(run.canStop && run.runId.isNotBlank()) { "Remote stop is unavailable for this task" }
        val cfg = config(); checkConnection(run, cfg)
        val client = clientFactory(cfg.agent)
        val status = client.stop(run.runId)
        if (status == "stopping") change(run.operationId) { it.copy(status = "stopping", approval = null, error = "") }
        applyStatus(run.operationId, client.status(run.runId))
      }
    }

    fun resolveApproval(runId: String, requestId: String, allow: Boolean) = control {
        val run = state.value.run ?: return@control
        val approval = run.approval
        require(run.runId == runId && run.status == "waiting_for_approval" && approval?.requestId == requestId &&
            run.canApprove && (if (allow) "once" else "deny") in approval.choices) { "This approval is no longer current" }
        val cfg = config(); checkConnection(run, cfg)
        val client = clientFactory(cfg.agent)
        try { client.approve(runId, requestId, allow) }
        catch (e: ApiError) {
            if (e.code == 409) {
                applyStatus(run.operationId, client.status(runId))
                throw IOException("That approval has expired or was answered elsewhere")
            }
            throw e
        }
        change(run.operationId) { it.copy(status = "running", approval = null, error = "") }
        applyStatus(run.operationId, client.status(runId))
    }

    fun markDelivered(operationId: String) = change(operationId) { it.copy(delivered = true) }

    /** Admission is enough to clear only the unchanged draft in its original chat. */
    fun confirmAdmittedDraft(history: dev.r1ptt.data.History) {
        val run = state.value.run?.takeIf { it.runId.isNotBlank() } ?: return
        if (history.errors.value != null) return
        runCatching { JSONObject(run.payload).getString("input") }.getOrNull()?.let {
            history.confirmDraft(it, run.sessionId.removePrefix("r1ptt-"))
        }
    }

    /** Local reconciliation only. Delivery keys make a crash before the receipt marker harmless. */
    fun reconcile(history: dev.r1ptt.data.History) {
        val run = synchronized(lock) {
            if (executing.get() || controlling.get() > 0) return
            val completed = state.value.run?.takeIf { it.status == "completed" && !it.delivered } ?: return
            if (!delivering.compareAndSet(false, true)) return
            completed
        }
        executor.execute {
            try {
                history.errors.value?.let { throw IOException(it) }
                val conversation = run.sessionId.removePrefix("r1ptt-")
                require(history.flow.value.chats.any { it.id == conversation }) { "The original chat was removed. Read this result in Hermes before archiving it." }
                if (run.output.isNotBlank()) history.add(dev.r1ptt.data.Msg.ASSISTANT, run.output,
                    conversation = conversation, deliveryKey = run.operationId)
                history.confirmDraft(JSONObject(run.payload).optString("input"), conversation)
                history.awaitWrites()
                markDelivered(run.operationId)
            } catch (e: Exception) { message(friendly(e)) }
            finally { delivering.set(false) }
        }
    }

    /** Explicit local archive after user confirmation; never stop, approve, or retry remote work. */
    fun archive() {
        val operation = state.value.run?.operationId ?: return
        control {
            synchronized(lock) {
                val run = state.value.run?.takeIf { it.operationId == operation } ?: return@synchronized
                require(!executing.get() && !delivering.get() && run.status != "submitting") { "Wait for local task work to settle before archiving" }
                watching?.cancelAll(); watching = null
                ledger.archive(run)
                mutable.value = mutable.value.copy(run = null, message = "Task record archived. Hermes was not stopped.")
            }
        }
    }

    /** UI ownership is separate from an active voice turn; leaving the last screen closes its SSE. */
    fun setVisible(owner: Any, shown: Boolean) {
        synchronized(lock) {
            if (shown) visible.add(owner) else visible.remove(owner)
            if (visible.isEmpty()) { watching?.cancelAll(); watching = null }
        }
        if (shown) startVisibleWatch()
    }

    private fun startVisibleWatch() {
        val run: HermesRun
        val calls: CallRegistry
        synchronized(lock) {
            if (visible.isEmpty() || watching != null || executing.get() || controlling.get() > 0) return
            run = state.value.run?.takeIf { it.active && it.runId.isNotBlank() && it.status != "waiting_for_approval" } ?: return
            calls = CallRegistry(); watching = calls
        }
        executor.execute {
            try {
                val cfg = config(); checkConnection(run, cfg)
                val client = clientFactory(cfg.agent)
                applyStatus(run.operationId, client.status(run.runId, calls))
                follow(run.operationId, client, calls)
            } catch (_: Exception) {
                // A screen leaving closes its own connection. Retry is explicit after bounded recovery.
            } finally { synchronized(lock) { if (watching === calls) watching = null } }
        }
    }

    private fun control(action: () -> Unit) {
        controlling.incrementAndGet()
        synchronized(lock) { mutable.value = mutable.value.copy(working = true, message = "") }
        controlExecutor.execute {
            try { storageError?.let { throw IOException(it) }; action() }
            catch (e: Exception) { message(friendly(e)) }
            finally {
                synchronized(lock) { mutable.value = mutable.value.copy(working = controlling.decrementAndGet() > 0) }
                startVisibleWatch()
            }
        }
    }

    internal fun acceptEvent(operation: String, event: HermesEvent): Boolean = synchronized(lock) {
        val old = mutable.value.run?.takeIf { it.operationId == operation && it.runId == event.runId } ?: return false
        if (old.terminal || event.sequence?.let { it <= old.lastSequence } == true) return false
        var run = old.copy(lastSequence = event.sequence ?: old.lastSequence)
        val j = event.data
        run = when (event.name) {
            "message.delta" -> if (event.sequence == null || event.sequence < 0) run.copy(transcriptIncomplete = true)
                else run.copy(status = if (old.status == "stopping") "stopping" else "running", output = (run.output + j.optString("delta")).take(MAX_OUTPUT))
            "message.interim" -> run.copy(activity = j.optString("text").take(180))
            "tool.started" -> run.copy(activity = "Using " + j.optString("tool").substringAfterLast("__").replace('_', ' ').take(70))
            "approval.request" -> if (old.status == "stopping") run else run.copy(status = "waiting_for_approval", approval = HermesApproval.parse(j))
            "approval.responded" -> run.copy(status = if (old.status == "stopping") "stopping" else "running", approval = null)
            "replay.truncated" -> run.copy(transcriptIncomplete = true, activity = "Reconnecting; some live text is unavailable")
            "run.completed" -> if (!j.has("output") || j.isNull("output")) run.copy(transcriptIncomplete = true, activity = "Checking final result")
                else run.copy(status = "completed", output = j.getString("output").take(MAX_OUTPUT), error = "", approval = null,
                    provider = j.optJSONObject("runtime")?.optString("provider").orEmpty(), model = j.optJSONObject("runtime")?.optString("model").orEmpty())
            "run.failed", "run.cancelled", "run.interrupted" -> run.copy(
                status = event.name.removePrefix("run."), output = j.optString("output", run.output).take(MAX_OUTPUT),
                error = j.optString("error"), approval = null,
                provider = j.optJSONObject("runtime")?.optString("provider").orEmpty(), model = j.optJSONObject("runtime")?.optString("model").orEmpty())
            else -> run
        }
        // Deltas are checkpointed only at semantic boundaries; terminal output is authoritative.
        // The sequence and partial text stay together, so a crash never skips unpersisted text.
        save(run, persist = run.transcriptIncomplete != old.transcriptIncomplete || (event.name != "message.delta" && event.name != "message.interim"))
        true
    }

    private fun applyStatus(operation: String, status: HermesRunStatus) = change(operation) { old ->
        require(old.runId == status.runId) { "Hermes returned a different task" }
        if (old.terminal) old else old.copy(status = if (old.status == "stopping" && !status.terminal) "stopping" else status.status,
            output = if (status.terminal) status.output.take(MAX_OUTPUT) else old.output,
            error = status.error, approval = status.approval, provider = status.provider, model = status.model)
    }

    private fun checkConnection(run: HermesRun, cfg: Config) {
        require(run.connectionId == HermesClient.connectionId(cfg.agent)) {
            "This task belongs to the previous Hermes connection. Restore that connection to control it."
        }
    }

    private fun message(value: String) = synchronized(lock) { mutable.value = mutable.value.copy(message = value) }
    private fun change(operation: String, update: (HermesRun) -> HermesRun) = synchronized(lock) {
        mutable.value.run?.takeIf { it.operationId == operation }?.let { save(update(it)) }
    }
    private fun save(run: HermesRun, persist: Boolean = true) = synchronized(lock) {
        if (persist) ledger.write(run)
        mutable.value = mutable.value.copy(run = run, message = "")
    }

    companion object { private const val MAX_OUTPUT = 200_000 }
}
