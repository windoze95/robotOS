package dev.r1ptt.net

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ChatClient {
    /**
     * Streams a reply. [onDelta] gets text as it arrives (on the calling thread); [onStatus] gets
     * agent progress hints (Hermes tool events). Returns the whole reply.
     */
    fun stream(
        req: ChatRequest.Built,
        calls: CallRegistry,
        onDelta: (String) -> Unit,
        onStatus: (String) -> Unit,
        requireComplete: Boolean = false,
        maxChars: Int = Int.MAX_VALUE,
        onAccepted: () -> Unit = {},
    ): String {
        val http = Request.Builder().url(req.url)
            .apply { req.headers.forEach { (k, v) -> header(k, v) } }
            .post(req.body.toString().toRequestBody(Http.JSON))
            .build()
        val client = Http.withReadTimeout(req.timeoutSec)
        val call = (if (requireComplete) client.newBuilder().callTimeout(req.timeoutSec.toLong(), TimeUnit.SECONDS).build() else client).newCall(http)
        calls.add(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError(resp.code, errorMessage(resp.body?.string().orEmpty()))
            onAccepted()
            val body = resp.body ?: throw IOException("Empty response")
            val full = StringBuilder()

            if (resp.header("Content-Type").orEmpty().contains("application/json")) {
                // The server ignored stream=true.
                if (requireComplete) require(!body.source().request(65_537)) { "Action response too large" }
                val raw = body.string()
                if (requireComplete && JSONObject(raw).getJSONArray("choices").getJSONObject(0).optString("finish_reason") != "stop") throw IllegalArgumentException("Incomplete action response")
                val text = ChatChunk.fullMessage(raw)
                require(text.length <= maxChars) { "Action response too large" }
                full.append(text)
                onDelta(text)
                return full.toString()
            }

            var done = false
            var finishReason: String? = null
            val parser = SseParser { event, data ->
                if (event != null && event.startsWith("hermes.")) {
                    onStatus(progressHint(data))
                    return@SseParser
                }
                val previouslyFinished = finishReason != null
                if (requireComplete && data.trim() != "[DONE]") {
                    val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
                    if (choice?.has("finish_reason") == true && !choice.isNull("finish_reason")) {
                        require(finishReason == null) { "Repeated action completion" }
                        finishReason = choice.getString("finish_reason")
                    }
                }
                when (val c = ChatChunk.parse(data)) {
                    is ChatChunk.Text -> {
                        require(!requireComplete || !previouslyFinished) { "Action content after completion" }
                        require(c.delta.length <= maxChars - full.length) { "Action response too large" }
                        full.append(c.delta)
                        onDelta(c.delta)
                    }
                    is ChatChunk.Failed -> throw IOException(c.message)
                    ChatChunk.Done -> done = true
                    ChatChunk.Other -> {}
                }
            }
            val src = body.source()
            while (!done) {
                if (src.exhausted()) break
                val line = if (requireComplete) src.readUtf8LineStrict(65_536) else src.readUtf8Line() ?: break
                parser.line(line)
            }
            parser.finish()
            require(!requireComplete || (done && finishReason == "stop")) { "Incomplete action response" }
            return full.toString()
        }
    }

    private fun progressHint(data: String): String = runCatching {
        val j = JSONObject(data)
        val tool = j.optString("tool").ifBlank { j.optString("name") }
        if (tool.isNotBlank()) "Using $tool…" else "Working…"
    }.getOrDefault("Working…")
}
