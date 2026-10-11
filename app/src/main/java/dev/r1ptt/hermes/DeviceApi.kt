package dev.r1ptt.hermes

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import dev.r1ptt.App
import dev.r1ptt.data.HermesLink
import dev.r1ptt.messages.SmsDraft
import dev.r1ptt.messages.SmsRecipientResolver
import dev.r1ptt.messages.SmsRecipientText
import dev.r1ptt.messages.SmsRecord
import dev.r1ptt.messages.SmsResolution
import dev.r1ptt.messages.SmsStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The R1's side of Hermes's `robotos` MCP server: a small JSON API on the tailnet. Hermes is the
 * agent; this only carries out what it asks (send a text, report on the device). Only Tailscale and
 * loopback (adb) peers presenting the bearer token are served, one request per connection.
 */
class DeviceApi(
    private val app: App,
    private val sms: dev.r1ptt.messages.SmsController = app.sms,
    private val clock: () -> Long = System::currentTimeMillis,
    private val settleMs: Long = 12_000L,
) {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "device-api").apply { isDaemon = true } }
    private val prefs = app.getSharedPreferences("device_api", 0)
    private val sendLock = Any()
    private var server: ServerSocket? = null
    private var port = 0
    /** When Hermes last reached the R1 (for the settings screen). */
    @Volatile var lastCall = 0L
        private set
    val listening: Boolean get() = server?.isClosed == false

    /** Follows the config: serve while enabled and valid, otherwise close the port. */
    @Synchronized fun apply(link: HermesLink) {
        val want = link.deviceApi && link.valid()
        if (want && server?.isClosed == false && port == link.port) return
        stop()
        if (!want) return
        try {
            val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(link.port), 16) }
            server = socket
            port = link.port
            Thread({ accept(socket) }, "device-api-accept").apply { isDaemon = true }.start()
            Log.i(TAG, "device api: listening")
        } catch (e: Exception) {
            Log.w(TAG, "device api: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            pool.execute { serve(client) }
        }
    }

    private fun serve(socket: Socket) = socket.use {
        runCatching {
            socket.soTimeout = 15_000
            if (!allowed(socket.inetAddress)) return@use
            val request = Request.read(BufferedInputStream(socket.getInputStream()))
            val (code, body) = if (request == null) 400 to error("Bad request") else handle(request)
            respond(socket, code, body)
        }.onFailure { Log.w(TAG, "device api: ${it.javaClass.simpleName}") }
    }

    internal fun handle(request: Request): Pair<Int, JSONObject> {
        val link = app.store.value.hermes
        if (!link.deviceApi || !authorized(request.headers["authorization"], link.token)) return 401 to error("Unauthorized")
        lastCall = clock()
        return try {
            when {
                request.method == "GET" && request.path == "/v1/status" -> 200 to status()
                request.method == "POST" && request.path == "/v1/sms" -> send(JSONObject(request.body))
                request.method == "GET" && request.path.startsWith("/v1/sms/") -> sms(request.path.removePrefix("/v1/sms/"))
                request.method == "GET" && request.path == "/v1/texts" ->
                    texts(request.query["with"], (request.query["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100))
                request.method == "GET" && request.path == "/v1/recipients" -> 200 to JSONObject().put("recipients",
                    JSONArray(sms.savedRecipients().map { JSONObject().put("name", it.name).put("number", it.number) }))
                else -> 404 to error("Not found")
            }
        } catch (e: org.json.JSONException) {
            400 to error("Invalid JSON")
        } catch (e: IllegalArgumentException) {
            400 to error(e.message ?: "Invalid request")
        }
    }

    private fun status(): JSONObject {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        val validSim = SubscriptionManager.isValidSubscriptionId(sub)
        val carrier = runCatching {
            app.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub).networkOperatorName
        }.getOrNull().orEmpty()
        val link = app.store.value.hermes
        return JSONObject()
            .put("device", "Rabbit R1 running robotOS")
            .put("app_version", runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull())
            .put("battery_percent", if (scale > 0 && level >= 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            .put("online", app.radio.isOnline())
            .put("airplane_mode", Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0)
            .put("screen_on", app.screen.isScreenOn())
            .put("can_text", sms.canSend && app.store.value.power.cellular && validSim)
            .put("carrier", carrier)
            .put("texts_sent_today", sentToday())
            .put("daily_text_limit", link.dailySendLimit)
            .put("owner_texts_go_to_hermes", link.passthrough)
    }

    /** One text, sent now. Serialized so parallel tool calls cannot collide in the native executor. */
    private fun send(j: JSONObject): Pair<Int, JSONObject> {
        val to = j.optString("to").trim()
        val body = j.optString("body").trim()
        require(to.isNotEmpty() && to.length <= 80) { "`to` must be a phone number or a recipient name saved on the R1." }
        require(body.isNotEmpty() && body.length <= SmsRecord.MAX_DRAFT_CHARS) { "`body` must be 1–${SmsRecord.MAX_DRAFT_CHARS} characters." }
        synchronized(sendLock) {
            val limit = app.store.value.hermes.dailySendLimit
            if (limit > 0 && sentToday() >= limit) return 429 to error("The R1's daily limit of $limit texts from Hermes is reached. Nothing was sent.")
            val number = when (val r = SmsRecipientResolver.resolve(to, sms.savedRecipients(), SmsRecipientText.region(app))) {
                is SmsResolution.Number -> r.number
                is SmsResolution.Choose -> return 409 to error("More than one saved recipient is named \"$to\". Nothing was sent; use a phone number.")
                    .put("matches", JSONArray(r.matches.map { JSONObject().put("name", it.name).put("number", it.number) }))
                SmsResolution.Missing -> return 404 to error("No recipient named \"$to\" is saved on the R1. Nothing was sent; use a phone number.")
            }
            var attempt = dispatch(number, body)
            // Another send (e.g. the owner's own) may still be handing off; wait briefly, never re-send.
            repeat(8) { if (!attempt.consumed && attempt.busy) { Thread.sleep(750); attempt = dispatch(number, body) } }
            if (!attempt.consumed) return 409 to error("${attempt.message} Nothing was sent.")
            countSend()
            Log.i(TAG, "device api: text handed to Android")
            val id = requireNotNull(attempt.id)
            return 200 to (settle(id)?.let(::describe) ?: JSONObject().put("id", id).put("to", number).put("status", "sending"))
        }
    }

    private class Attempt(val consumed: Boolean, val message: String, val id: String?, val busy: Boolean)

    private fun dispatch(number: String, body: String): Attempt {
        val latch = CountDownLatch(1)
        var result = Attempt(false, "SMS is unavailable.", null, false)
        main.post {
            val review = try { sms.prepare(SmsDraft(number, body)) } catch (e: Exception) {
                result = Attempt(false, e.message ?: "SMS is unavailable.", null, false)
                latch.countDown()
                return@post
            }
            sms.send(review, clearDraft = false, agent = true) { consumed, message ->
                result = Attempt(consumed, message, review.record.id, !consumed && message.startsWith("Finish the current action"))
                latch.countDown()
            }
        }
        return if (latch.await(30, TimeUnit.SECONDS)) result else Attempt(false, "The R1 did not hand the text to Android in time.", null, false)
    }

    /** Android usually reports a send within a few seconds; report what is known by then. */
    private fun settle(id: String): SmsRecord? {
        val until = clock() + settleMs
        var record = sms.record(id)
        while (record != null && record.status(clock()) == SmsStatus.SENDING && clock() < until) {
            Thread.sleep(250)
            record = sms.record(id)
        }
        return record
    }

    private fun sms(id: String): Pair<Int, JSONObject> {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "Unknown text id." }
        val record = sms.record(id)?.takeIf { !it.incoming } ?: return 404 to error("No text with that id.")
        return 200 to describe(record)
    }

    private fun texts(with: String?, limit: Int): Pair<Int, JSONObject> {
        val saved = sms.savedRecipients()
        fun name(number: String) = saved.firstOrNull { it.number == number }?.name
        if (with.isNullOrBlank()) {
            return 200 to JSONObject().put("conversations", JSONArray(sms.threads().take(limit).map { t ->
                JSONObject().put("with", t.peer).put("name", name(t.peer) ?: JSONObject.NULL).put("unread", t.unread)
                    .put("last", message(t.last))
            }))
        }
        val number = (SmsRecipientResolver.resolve(with, saved, SmsRecipientText.region(app)) as? SmsResolution.Number)?.number
            ?: return 404 to error("No conversation found for \"$with\".")
        return 200 to JSONObject().put("with", number).put("name", name(number) ?: JSONObject.NULL)
            .put("messages", JSONArray(sms.conversation(number).takeLast(limit).map(::message)))
    }

    private fun message(r: SmsRecord) = JSONObject().put("id", r.id).put("direction", if (r.incoming) "in" else "out")
        .put("at", Instant.ofEpochMilli(r.createdAt).toString()).put("body", r.body)
        .put("status", if (r.incoming) "received" else r.status(clock()).name.lowercase())

    private fun describe(r: SmsRecord) = JSONObject().put("id", r.id).put("to", r.peer).put("parts", r.parts.size)
        .put("status", r.status(clock()).name.lowercase())
        .put("error_codes", JSONArray(r.parts.mapNotNull { it.error }))

    fun sentToday(): Int = if (prefs.getLong("day", -1) == LocalDate.now().toEpochDay()) prefs.getInt("count", 0) else 0
    private fun countSend() = prefs.edit().putLong("day", LocalDate.now().toEpochDay()).putInt("count", sentToday() + 1).apply()

    private fun respond(socket: Socket, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 409 to "Conflict", 429 to "Too Many Requests")[code] ?: "Error"
        socket.getOutputStream().apply {
            write("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    /** One parsed HTTP/1.1 request: bounded head and body, no chunked encoding. */
    class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String) {
        companion object {
            fun read(input: InputStream): Request? {
                val head = ByteArrayOutputStream()
                var state = 0 // progress through "\r\n\r\n"
                while (state < 4) {
                    val b = input.read()
                    if (b < 0 || head.size() >= MAX_HEAD) return null
                    head.write(b)
                    state = when (b) {
                        '\r'.code -> if (state == 2) 3 else 1
                        '\n'.code -> if (state == 1) 2 else if (state == 3) 4 else 0
                        else -> 0
                    }
                }
                val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
                val start = lines.first().split(" ")
                if (start.size != 3 || !start[2].startsWith("HTTP/1.")) return null
                val headers = lines.drop(1).filter { it.isNotEmpty() }.mapNotNull { line ->
                    line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
                }.toMap()
                if (headers.containsKey("transfer-encoding")) return null
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 0..MAX_BODY) return null
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return null
                    read += n
                }
                val target = start[1]
                val q = target.indexOf('?')
                val query = if (q < 0) emptyMap() else target.substring(q + 1).split('&').mapNotNull { pair ->
                    val kv = pair.split('=', limit = 2)
                    if (kv[0].isEmpty()) null else URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
                }.toMap()
                return Request(start[0], if (q < 0) target else target.substring(0, q), query, headers, String(body, Charsets.UTF_8))
            }
        }
    }

    companion object {
        private const val TAG = "r1ptt"
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 64 * 1024

        private fun error(message: String) = JSONObject().put("error", message)

        fun authorized(header: String?, token: String): Boolean {
            if (token.length < 32 || header == null || !header.startsWith("Bearer ")) return false
            return MessageDigest.isEqual(header.removePrefix("Bearer ").trim().toByteArray(), token.toByteArray())
        }

        /** Tailscale peers (100.64.0.0/10, fd7a:115c:a1e0::/48) and loopback only; never the Wi-Fi LAN. */
        fun allowed(address: InetAddress): Boolean {
            if (address.isLoopbackAddress) return true
            val b = address.address
            return when (address) {
                is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
                is Inet6Address -> listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0).withIndex().all { (i, v) -> (b[i].toInt() and 0xff) == v }
                else -> false
            }
        }
    }
}
