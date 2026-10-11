package dev.r1ptt.hermes

import dev.r1ptt.data.AtomicConfigFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HermesRun(
    val operationId: String,
    val connectionId: String,
    val sessionId: String,
    val payload: String,
    val createdAt: Long,
    val durableIdempotency: Boolean,
    val retentionSeconds: Long,
    val canStop: Boolean,
    val canApprove: Boolean,
    val runId: String = "",
    val status: String = "submitting",
    val lastSequence: Long = -1,
    val output: String = "",
    val error: String = "",
    val activity: String = "",
    val approval: HermesApproval? = null,
    val transcriptIncomplete: Boolean = false,
    val delivered: Boolean = false,
    val provider: String = "",
    val model: String = "",
) {
    val terminal get() = status in HermesRunStatus.TERMINAL
    val active get() = !terminal
    val label get() = when (status) {
        "submitting" -> "Sending to Hermes"
        "submission_unknown" -> "Submission status unknown"
        "queued", "started" -> "Queued"
        "running" -> activity.ifBlank { "Hermes is working" }
        "waiting_for_approval" -> "Your decision is needed"
        "stopping" -> "Stopping Hermes"
        "completed" -> "Complete"
        "cancelled" -> "Stopped"
        "interrupted" -> "Hermes restarted"
        "failed" -> "Task failed"
        else -> "Status unavailable"
    }
}

/** One unresolved task plus its final receipt. Atomic writes happen before remote dispatch. */
class HermesRunLedger(file: File) {
    private val storage = AtomicConfigFile(file)
    private val archives = AtomicConfigFile(File(file.parentFile, "hermes-run-archive.json"))

    @Synchronized fun read(): HermesRun? = storage.read()?.let { raw ->
        val j = JSONObject(raw)
        require(j.getInt("schema") == 1) { "Unsupported Hermes task storage" }
        if (j.optBoolean("cleared")) return null
        val a = j.optJSONObject("approval")
        HermesRun(
            j.getString("operationId"), j.getString("connectionId"), j.getString("sessionId"),
            j.getString("payload"), j.getLong("createdAt"), j.getBoolean("durableIdempotency"),
            j.getLong("retentionSeconds"), j.getBoolean("canStop"), j.getBoolean("canApprove"),
            j.optString("runId"), j.getString("status"), j.optLong("lastSequence", -1), j.optString("output"),
            j.optString("error"), j.optString("activity"), HermesApproval.parse(a),
            j.optBoolean("transcriptIncomplete"), j.optBoolean("delivered"), j.optString("provider"), j.optString("model"))
    }

    @Synchronized fun write(run: HermesRun) {
        storage.write(encode(run).toString())
    }

    /** Archive first, clear second. A crash between writes retains an actionable active receipt. */
    @Synchronized fun archive(run: HermesRun) {
        require(read()?.operationId == run.operationId) { "The saved task changed" }
        val old = archives.read()?.let {
            val j = JSONObject(it)
            require(j.getInt("schema") == 1) { "Unsupported Hermes archive storage" }
            j.getJSONArray("receipts")
        } ?: JSONArray()
        val kept = (0 until old.length()).map { old.getJSONObject(it) }
            .filter { it.optString("operationId") != run.operationId }.takeLast(19)
        archives.write(JSONObject().put("schema", 1).put("receipts", JSONArray(kept + encode(run))).toString())
        storage.write(JSONObject().put("schema", 1).put("cleared", true).toString())
    }

    private fun encode(run: HermesRun): JSONObject {
        val j = JSONObject().put("schema", 1).put("operationId", run.operationId).put("connectionId", run.connectionId)
            .put("sessionId", run.sessionId).put("payload", run.payload).put("createdAt", run.createdAt)
            .put("durableIdempotency", run.durableIdempotency).put("retentionSeconds", run.retentionSeconds)
            .put("canStop", run.canStop).put("canApprove", run.canApprove).put("runId", run.runId)
            .put("status", run.status).put("lastSequence", run.lastSequence).put("output", run.output)
            .put("error", run.error).put("activity", run.activity).put("transcriptIncomplete", run.transcriptIncomplete)
            .put("delivered", run.delivered).put("provider", run.provider).put("model", run.model)
        run.approval?.let { a -> j.put("approval", JSONObject().put("request_id", a.requestId)
            .put("description", a.description).put("command", a.command).put("choices", JSONArray(a.choices.toList()))) }
        return j
    }
}
