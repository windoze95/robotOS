package dev.r1ptt.hermes

import android.content.Context
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class DeviceReceiptReadException(cause: Throwable) : IOException(
    "Saved device request receipts could not be read. No new action was started; preserve the receipt file for recovery.", cause)

/** Persist intent before mutation, then persist its result. A crash never makes retry look safe. */
class DeviceReceipts(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("device_receipts", 0)
    private val backingFile = File(context.dataDir, "shared_prefs/device_receipts.xml")

    @Synchronized fun get(id: String, owner: String): Pair<Int, JSONObject>? {
        verifyEmptyStore()
        val entry = read(id)?.takeIf { it.getString("owner") == digest(owner) } ?: return null
        return entry.getInt("code") to entry.getJSONObject("result")
    }

    @Synchronized fun perform(id: String, owner: String, fingerprint: String, action: () -> Pair<Int, JSONObject>): Pair<Int, JSONObject> {
        require(id.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "request_id must contain 16–128 letters, digits, hyphens or underscores." }
        verifyEmptyStore()
        val hash = digest(fingerprint)
        val previous = read(id)
        if (previous != null) {
            if (previous.getString("owner") != digest(owner) || previous.getString("fingerprint") != hash)
                return 409 to JSONObject().put("error", "request_id was already used for a different request.").put("request_id", id)
            return previous.getInt("code") to previous.getJSONObject("result")
        }
        val expired = prefs.all.keys.filter { key -> read(key)?.optLong("at", 0)?.let { clock() - it > RETENTION_MS } == true }
        if (prefs.all.size - expired.size >= MAX_RECEIPTS)
            return 429 to JSONObject().put("error", "Request receipt storage is full. No action was started.").put("request_id", id)
        val entry = JSONObject().put("owner", digest(owner)).put("fingerprint", hash).put("at", clock())
            .put("code", 202).put("result", JSONObject().put("request_id", id).put("status", "unknown")
                .put("error", "The action may be in progress or its outcome is unknown. Do not repeat it with a new request_id."))
        val editor = prefs.edit()
        expired.forEach { editor.remove(it) }
        check(editor.putString(id, entry.toString()).commit()) { "The request receipt could not be saved." }
        val (code, result) = try { action() }
        catch (e: org.json.JSONException) { 400 to JSONObject().put("error", "Invalid JSON fields.") }
        catch (e: IllegalArgumentException) { 400 to JSONObject().put("error", e.message ?: "Invalid request.") }
        result.put("request_id", id)
        check(prefs.edit().putString(id, entry.put("code", code).put("result", result).toString()).commit())
        return code to result
    }

    private fun read(id: String): JSONObject? {
        if (!prefs.contains(id)) return null
        return try {
            JSONObject(requireNotNull(prefs.getString(id, null))).also {
                require(it.getString("owner").matches(Regex("[0-9a-f]{64}")))
                require(it.getString("fingerprint").matches(Regex("[0-9a-f]{64}")))
                require(it.getLong("at") >= 0 && it.getInt("code") in 100..599)
                it.getJSONObject("result")
            }
        } catch (e: Exception) { throw DeviceReceiptReadException(e) }
    }

    /** Android may turn an unreadable preferences XML file into an empty map. Do not reset it. */
    private fun verifyEmptyStore() {
        if (prefs.all.isNotEmpty()) return
        val file = File(backingFile.path + ".bak").takeIf { it.exists() } ?: backingFile
        if (!file.exists()) return // First use; no prior request receipts.
        try {
            file.inputStream().use { input ->
                val xml = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                var root = false
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG) {
                        // A valid empty preferences file has exactly one <map> element. If disk
                        // contains entries but SharedPreferences loaded none, preserve it for recovery.
                        require(!root && xml.name == "map")
                        root = true
                    }
                    if (xml.eventType == XmlPullParser.TEXT) require(xml.text.isBlank())
                    xml.next()
                }
                require(root)
            }
        } catch (e: Exception) { throw DeviceReceiptReadException(e) }
    }

    companion object {
        const val RETENTION_MS = 7 * 24 * 60 * 60_000L
        private const val MAX_RECEIPTS = 512
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
