package dev.r1ptt.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

class HubException(val status: Int, val code: String, message: String, val response: JSONObject? = null) : IllegalStateException(message)

/** Device credentials never follow redirects or travel over cleartext except explicit loopback. */
class HubHttp {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
    fun cancelAll() { active.forEach { it.cancel() } }
    private fun <T> call(request: Request, transport: OkHttpClient = client, action: (okhttp3.Response) -> T): T {
        val call = transport.newCall(request); active.add(call)
        try { return call.execute().use(action) } finally { active.remove(call) }
    }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    fun json(base: String, token: String, method: String, path: String, body: JSONObject? = null): JSONObject {
        val request = request(base, token, path).method(method,
            if (method in setOf("GET", "HEAD")) null else (body ?: JSONObject()).toString().toRequestBody(JSON)).build()
        return execute(request, longRunning = path.endsWith("/transcribe"), recovery = path.startsWith("/devices/self/"))
    }
    fun upload(base: String, token: String, id: String, file: File, mime: String): JSONObject {
        require(file.length() <= 32L * 1024 * 1024) { "Recording is too large to upload" }
        return execute(request(base, token, "/media").header("X-Upload-Id", id)
            .post(file.readBytes().toRequestBody(mime.toMediaType())).build())
    }
    fun media(base: String, token: String, id: String, target: File): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return call(request(base, token, "/media/$id").get().build()) { response ->
            if (!response.isSuccessful) fail(response.code, response.body?.string())
            val pending = File(target.parentFile, "${target.name}.pending")
            try {
                response.body!!.byteStream().use { input -> java.io.FileOutputStream(pending).use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                        check(total <= 32L * 1024 * 1024) { "Recording is too large" }; output.write(buffer, 0, n) }
                    output.fd.sync()
                } }
                java.nio.file.Files.move(pending.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                target
            } finally { pending.delete() }
        }
    }
    private fun request(base: String, token: String, path: String): Request.Builder = Request.Builder()
        .url(normalize(base) + "/api/v1" + path).header("Accept", "application/json")
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
    private fun execute(request: Request, longRunning: Boolean = false, recovery: Boolean = false): JSONObject =
        call(request, if (longRunning) client.newBuilder().readTimeout(150, TimeUnit.SECONDS).callTimeout(160, TimeUnit.SECONDS).build() else if (recovery) client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build() else client) { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) fail(response.code, raw)
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }
    private fun fail(status: Int, raw: String?): Nothing {
        val code = runCatching { JSONObject(raw.orEmpty()).optString("code") }.getOrDefault("")
        val message = when (status) {
            401 -> "Workspace credentials were rejected. Check this device in the host console."
            403 -> "Workspace access is restricted. Check this device in the host console."
            409, 412, 423 -> "Another save or editor changed this prompt. Your local draft is preserved."
            429 -> "Too many requests. Wait briefly and try again."
            503 -> "The host service is unavailable. Your local work is saved."
            else -> "The host could not complete this request ($status). Your local work is saved."
        }
        throw HubException(status, code, message, runCatching { JSONObject(raw.orEmpty()) }.getOrNull())
    }
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun normalize(raw: String): String {
            val uri = URI(raw.trim().trimEnd('/'))
            require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in setOf("", "/")) { "Enter the host's HTTPS address without a path" }
            require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]"))) { "Use HTTPS for the shared workspace" }
            return uri.toString().trimEnd('/')
        }
    }
}
