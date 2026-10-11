package dev.r1ptt.hermes

import dev.r1ptt.data.Agent
import dev.r1ptt.net.ApiError
import dev.r1ptt.net.CallRegistry
import dev.r1ptt.net.Http
import dev.r1ptt.net.errorMessage
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class HermesCapabilities(
    val legacy: Boolean = false,
    val runs: Boolean = false,
    val stop: Boolean = false,
    val approvals: Boolean = false,
    val models: Boolean = false,
    val durableIdempotency: Boolean = false,
    val retentionSeconds: Long = 0,
)

data class HermesApproval(val requestId: String, val description: String, val command: String, val choices: Set<String>) {
    companion object {
        fun parse(j: JSONObject?): HermesApproval? = j?.let {
            val choices = it.optJSONArray("choices")
            HermesApproval(it.optString("request_id"), it.optString("description"), it.optString("command"),
                (0 until (choices?.length() ?: 0)).map { n -> choices!!.optString(n) }.toSet())
        }
    }
}

data class HermesRunStatus(val runId: String, val status: String, val output: String, val error: String,
    val approval: HermesApproval?, val provider: String = "", val model: String = "") {
    val terminal get() = status in TERMINAL
    companion object {
        val TERMINAL = setOf("completed", "failed", "cancelled", "interrupted")
        fun parse(j: JSONObject): HermesRunStatus {
            val state = j.getString("status")
            require(state in TERMINAL || state in setOf("started", "queued", "running", "waiting_for_approval", "stopping")) {
                "Unknown Hermes task state"
            }
            return HermesRunStatus(j.getString("run_id"), state, j.optString("output"), j.optString("error"),
                HermesApproval.parse(j.optJSONObject("approval")), j.optJSONObject("runtime")?.optString("provider").orEmpty(),
                j.optJSONObject("runtime")?.optString("model").orEmpty())
        }
    }
}

data class HermesEvent(val name: String, val runId: String, val sequence: Long?, val data: JSONObject)
data class HermesAdmission(val runId: String, val replayed: Boolean)
data class HermesModel(val provider: String, val providerName: String, val model: String)

/** Authenticated control API. Redirects never carry the configured bearer/proxy credentials away. */
class HermesClient(private val agent: Agent, http: OkHttpClient = Http.client) {
    private val base = agent.baseUrl.trim().trimEnd('/').toHttpUrl()
    // Settings stores the OpenAI-compatible /v1 URL, possibly behind a profile or proxy prefix.
    private val prefix = base.encodedPath.trimEnd('/').removeSuffix("/v1")
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .readTimeout(30, TimeUnit.SECONDS).build()

    fun endpoint(path: String): HttpUrl {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\') &&
            !path.contains('?') && !path.contains('#')) { "Invalid Hermes endpoint" }
        val decoded = java.net.URLDecoder.decode(path, "UTF-8")
        require(!decoded.contains('\\') && decoded.split('/').none { it == ".." || it == "." } &&
            decoded.none { it.code < 32 } && !decoded.startsWith("//")) { "Invalid Hermes endpoint" }
        return base.newBuilder().encodedPath(prefix + path).query(null).fragment(null).build()
    }

    fun discover(calls: CallRegistry = CallRegistry()): HermesCapabilities {
        val j = try { json("GET", "/v1/capabilities", calls = calls) }
        catch (e: ApiError) { if (e.code == 404) return HermesCapabilities(legacy = true) else throw e }
        require(j.optString("object") == "hermes.api_server.capabilities") { "Unrecognized Hermes capabilities" }
        val f = j.optJSONObject("features") ?: throw IOException("Missing Hermes capabilities")
        val endpoints = j.optJSONObject("endpoints")
        fun supports(flag: String, name: String, method: String, path: String): Boolean {
            if (!f.optBoolean(flag)) return false
            val entry = endpoints?.optJSONObject(name) ?: return true // Older servers omit the table.
            // These are canonical API paths; never trust a remote absolute URL with our credentials.
            require(entry.optString("method") == method && entry.optString("path") == path) { "Unsupported Hermes endpoint" }
            return true
        }
        val runs = supports("run_submission", "runs", "POST", "/v1/runs") &&
            supports("run_status", "run_status", "GET", "/v1/runs/{run_id}") &&
            supports("run_events_sse", "run_events", "GET", "/v1/runs/{run_id}/events")
        val idempotency = f.optJSONObject("runs_idempotency")
        val approvalFlag = if (f.has("run_approval_response")) "run_approval_response" else "run_approval"
        return HermesCapabilities(runs = runs,
            stop = supports("run_stop", "run_stop", "POST", "/v1/runs/{run_id}/stop"),
            approvals = f.optBoolean("approval_events") && supports(approvalFlag, "run_approval", "POST", "/v1/runs/{run_id}/approval"),
            models = supports("model_options", "model_options", "GET", "/api/model/options"),
            durableIdempotency = idempotency?.optBoolean("supported") == true && idempotency.optBoolean("durable"),
            retentionSeconds = (idempotency?.optLong("retention_seconds") ?: 0).coerceIn(0, 7 * 86400))
    }

    fun submit(payload: String, key: String, calls: CallRegistry): HermesAdmission {
        val j = json("POST", "/v1/runs", payload, calls, mapOf("Idempotency-Key" to key))
        return HermesAdmission(identifier(j.getString("run_id")), j.optBoolean("replayed"))
    }

    fun status(id: String, calls: CallRegistry = CallRegistry()) =
        HermesRunStatus.parse(json("GET", "/v1/runs/${identifier(id)}", calls = calls)).also {
            require(it.runId == id) { "Hermes returned a different task" }
        }

    fun stop(id: String): String = json("POST", "/v1/runs/${identifier(id)}/stop", "{}").getString("status")

    fun approve(id: String, approval: String, allow: Boolean) {
        require(approval.isNotBlank() && approval.length <= 256) { "This Hermes version cannot identify this approval safely" }
        val j = json("POST", "/v1/runs/${identifier(id)}/approval", JSONObject()
            .put("request_id", approval).put("choice", if (allow) "once" else "deny").toString())
        require(j.optString("run_id") == id && j.optString("request_id") == approval && j.optInt("resolved") == 1) {
            "Hermes did not confirm this approval"
        }
    }

    fun models(refresh: Boolean = false): List<HermesModel> {
        val url = endpoint("/api/model/options").newBuilder().addQueryParameter("include_unconfigured", "false")
            .apply { if (refresh) addQueryParameter("refresh", "1") }.build()
        val j = requestJson(request("GET", url), CallRegistry())
        val providers = j.optJSONArray("providers") ?: throw IOException("Missing Hermes model list")
        return buildList {
            for (i in 0 until providers.length()) {
                val p = providers.optJSONObject(i) ?: continue
                if (p.has("authenticated") && !p.optBoolean("authenticated")) continue
                val slug = p.optString("slug").takeIf { it.isNotBlank() } ?: continue
                val values = p.optJSONArray("models") ?: continue
                val unavailable = p.optJSONArray("unavailable_models")
                val blocked = (0 until (unavailable?.length() ?: 0)).map { unavailable!!.optString(it) }.toSet()
                if (p.optBoolean("free_tier_pending")) continue
                for (n in 0 until values.length()) {
                    val model = values.opt(n) as? String ?: continue
                    if (model.isNotBlank() && model !in blocked) add(HermesModel(slug, p.optString("name", slug), model))
                }
            }
        }.distinctBy { it.provider to it.model }
    }

    /** Returns when the server detaches, or the consumer has received a terminal event. */
    fun events(id: String, after: Long, calls: CallRegistry, receive: (HermesEvent) -> Boolean) {
        val req = request("GET", endpoint("/v1/runs/${identifier(id)}/events"), headers =
            if (after >= 0) mapOf("Last-Event-ID" to after.toString(), "Accept" to "text/event-stream") else mapOf("Accept" to "text/event-stream"))
        val call = http.newCall(req)
        calls.add(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw ApiError(response.code, "Hermes events: HTTP ${response.code}")
                require(response.header("Content-Type").orEmpty().contains("text/event-stream")) { "Hermes did not return an event stream" }
                val source = response.body?.source() ?: throw IOException("Empty Hermes stream")
                var eventName = ""
                var sequence: Long? = null
                val data = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict(65_536)
                    if (line.isEmpty()) {
                        if (data.isNotEmpty()) {
                            val j = JSONObject(data.toString())
                            val event = HermesEvent(j.optString("event", eventName), j.optString("run_id"),
                                if (j.has("seq")) j.getLong("seq") else sequence, j)
                            require(event.runId == id) { "Hermes returned a different task" }
                            if (!receive(event)) return
                        }
                        data.setLength(0); eventName = ""; sequence = null
                    } else if (!line.startsWith(':')) {
                        val field = line.substringBefore(':')
                        val value = line.substringAfter(':', "").removePrefix(" ")
                        when (field) {
                            "event" -> eventName = value
                            "id" -> sequence = value.toLongOrNull()
                            "data" -> {
                                require(data.length + value.length < MAX_JSON) { "Hermes event too large" }
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(value)
                            }
                        }
                    }
                }
                // An unterminated event is discarded; recovery polls the authoritative task state.
            }
        } finally { calls.remove(call) }
    }

    private fun json(method: String, path: String, body: String? = null, calls: CallRegistry = CallRegistry(),
        headers: Map<String, String> = emptyMap()) = requestJson(request(method, endpoint(path), body, headers), calls)

    private fun request(method: String, url: HttpUrl, body: String? = null, headers: Map<String, String> = emptyMap()) =
        Request.Builder().url(url).apply {
            if (agent.apiKey.isNotBlank()) header("Authorization", "Bearer ${agent.apiKey}")
            agent.headers.forEach { (k, v) -> header(k, v) }
            header("Accept", "application/json")
            headers.forEach { (k, v) -> header(k, v) }
            method(method, body?.toRequestBody(Http.JSON))
        }.build()

    private fun requestJson(request: Request, calls: CallRegistry): JSONObject {
        val call = http.newCall(request)
        calls.add(call)
        try {
            call.execute().use { response ->
                val body = response.body ?: throw IOException("Empty Hermes response")
                val source = body.source()
                require(!source.request(MAX_JSON + 1L)) { "Hermes response too large" }
                val raw = source.readUtf8()
                if (!response.isSuccessful) throw ApiError(response.code, errorMessage(raw).take(240))
                return JSONObject(raw)
            }
        } finally { calls.remove(call) }
    }

    companion object {
        private const val MAX_JSON = 1_048_576
        private fun identifier(value: String): String = value.also {
            require(it.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "Invalid Hermes task identifier" }
        }
        /** A one-way connection fingerprint; credentials and proxy headers never enter the ledger. */
        fun connectionId(agent: Agent): String {
            val bytes = listOf(agent.baseUrl.trimEnd('/'), agent.apiKey, agent.headers.toSortedMap().toString()).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
