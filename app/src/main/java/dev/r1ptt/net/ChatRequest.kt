package dev.r1ptt.net

import dev.r1ptt.data.Config
import dev.r1ptt.data.Msg
import dev.r1ptt.data.SessionMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the `/chat/completions` request for the active provider. The backends differ only
 * in how the conversation continues:
 * - HISTORY (OpenAI, custom): the last N messages ride along every time.
 * - HERMES_SESSION: just the new message; `X-Hermes-Session-Id` continues the Hermes transcript.
 */
object ChatRequest {
    class Built(val url: String, val headers: Map<String, String>, val body: JSONObject, val timeoutSec: Int)

    /** [session] overrides the Hermes transcript, e.g. the owner's SMS conversation. */
    fun build(cfg: Config, messages: List<Msg>, convId: String, session: String = "r1ptt-$convId"): Built {
        val p = cfg.provider

        val headers = linkedMapOf<String, String>()
        if (p.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${p.apiKey}"
        headers["Accept"] = "text/event-stream"
        headers.putAll(p.headers)
        if (p.session == SessionMode.HERMES_SESSION) headers["X-Hermes-Session-Id"] = session

        val context = when (p.session) {
            SessionMode.HISTORY -> messages.takeLast(cfg.historyMessages.coerceAtLeast(1))
            SessionMode.HERMES_SESSION -> listOfNotNull(messages.lastOrNull { it.role == Msg.USER })
        }
        val msgs = JSONArray()
        if (cfg.systemPrompt.isNotBlank()) msgs.put(message("system", cfg.systemPrompt))
        context.forEach { msgs.put(message(it.role, it.text)) }

        val body = JSONObject()
            .put("model", p.model)
            .put("stream", true)
            .put("messages", msgs)
        mergeExtra(body, p.extraBody)

        return Built(p.baseUrl.trimEnd('/') + "/chat/completions", headers, body, p.timeoutSec)
    }

    private fun message(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    private fun mergeExtra(body: JSONObject, extra: String) {
        val j = runCatching { JSONObject(extra.ifBlank { "{}" }) }.getOrNull() ?: return
        for (k in j.keys()) body.put(k, j.get(k))
    }
}
