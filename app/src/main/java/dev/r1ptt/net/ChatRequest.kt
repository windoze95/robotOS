package dev.r1ptt.net

import dev.r1ptt.data.Config
import dev.r1ptt.data.Msg
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the `/chat/completions` request to Hermes. Only the new message is sent;
 * `X-Hermes-Session-Id` continues the conversation Hermes keeps on its side.
 */
object ChatRequest {
    class Built(val url: String, val headers: Map<String, String>, val body: JSONObject, val timeoutSec: Int)

    /** [session] overrides the Hermes transcript, e.g. the owner's SMS conversation. */
    fun build(cfg: Config, messages: List<Msg>, convId: String, session: String = "r1ptt-$convId"): Built {
        val a = cfg.agent
        val headers = linkedMapOf<String, String>()
        if (a.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${a.apiKey}"
        headers["Accept"] = "text/event-stream"
        headers.putAll(a.headers)
        headers["X-Hermes-Session-Id"] = session

        val msgs = JSONArray()
        if (cfg.systemPrompt.isNotBlank()) msgs.put(message("system", cfg.systemPrompt))
        messages.lastOrNull { it.role == Msg.USER }?.let { msgs.put(message(it.role, it.text)) }

        val body = JSONObject().put("model", a.model).put("stream", true).put("messages", msgs)
        return Built(a.baseUrl.trimEnd('/') + "/chat/completions", headers, body, a.timeoutSec)
    }

    private fun message(role: String, text: String) = JSONObject().put("role", role).put("content", text)
}
