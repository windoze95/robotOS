package dev.r1ptt

import android.content.Context
import dev.r1ptt.net.ApiError
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.UUID

enum class OutcomeSource { TYPED, VOICE, LIVE_VOICE, SMS }
enum class OutcomeStatus { RESOLVING, CLARIFY, DISABLED, CANCELLED, FAILED, COMPLETED, HANDOFF, SMS_UNKNOWN, SMS_FAILED, SMS_PARTLY_SENT, SMS_SENT, SMS_DELIVERY_FAILED, SMS_DELIVERED }
enum class OutcomeReason { NONE, AUTHORIZATION, NETWORK, TIMEOUT, PROVIDER, INVALID_ACTION, REQUEST, RECIPIENT, UNAVAILABLE }
data class Outcome(val id: String, val at: Long, val source: OutcomeSource, val status: OutcomeStatus, val reason: OutcomeReason, val code: Int? = null)

/** At most 50 local outcomes for 3 days. Enum categories and numeric codes only; no content fields. */
class OutcomeStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("action_outcomes", 0)
    @Synchronized fun begin(source: OutcomeSource): String = UUID.randomUUID().toString().also {
        save((list() + Outcome(it, now(), source, OutcomeStatus.RESOLVING, OutcomeReason.NONE)).takeLast(50))
    }
    @Synchronized fun update(id: String, status: OutcomeStatus, reason: OutcomeReason = OutcomeReason.NONE, code: Int? = null) {
        val rows = list()
        save(rows.map { old ->
            if (old.id != id || (old.status.name.startsWith("SMS_") && !status.name.startsWith("SMS_")) ||
                (old.status in setOf(OutcomeStatus.SMS_SENT, OutcomeStatus.SMS_DELIVERED, OutcomeStatus.SMS_DELIVERY_FAILED) && status == OutcomeStatus.SMS_UNKNOWN)) old
            else old.copy(at = now(), status = status, reason = reason, code = code?.takeIf { it in -1..9999 })
        })
    }
    @Synchronized fun list(): List<Outcome> = runCatching {
        val j = JSONArray(prefs.getString("rows", "[]"))
        val rows = (0 until j.length()).mapNotNull { n -> runCatching {
            val r = j.getJSONObject(n)
            Outcome(r.getString("id"), r.getLong("at"), OutcomeSource.valueOf(r.getString("source")), OutcomeStatus.valueOf(r.getString("status")), OutcomeReason.valueOf(r.getString("reason")), if (r.has("code")) r.getInt("code") else null)
        }.getOrNull() }.filter { now() - it.at in 0..3 * 24 * 60 * 60_000L }.takeLast(50)
        if (rows.size != j.length()) save(rows)
        rows
    }.getOrDefault(emptyList())
    @Synchronized fun finishIfOpen(id: String, status: OutcomeStatus) {
        if (list().any { it.id == id && it.status == OutcomeStatus.RESOLVING }) update(id, status)
    }
    private fun save(rows: List<Outcome>) {
        val j = JSONArray()
        rows.forEach { j.put(JSONObject().put("id", it.id).put("at", it.at).put("source", it.source.name).put("status", it.status.name).put("reason", it.reason.name).put("code", it.code)) }
        prefs.edit().putString("rows", j.toString()).apply()
    }
    companion object {
        fun reason(error: Throwable): OutcomeReason = when (error) {
            is ApiError -> if (error.code in listOf(401, 403)) OutcomeReason.AUTHORIZATION else OutcomeReason.PROVIDER
            is SocketTimeoutException -> OutcomeReason.TIMEOUT
            is java.io.IOException -> OutcomeReason.NETWORK
            else -> OutcomeReason.INVALID_ACTION
        }
        fun reason(message: String): OutcomeReason = when {
            message.contains("API key", true) -> OutcomeReason.AUTHORIZATION
            message.contains("timed out", true) -> OutcomeReason.TIMEOUT
            message.contains("connection", true) || message.contains("network", true) -> OutcomeReason.NETWORK
            message.contains("Microphone", true) || message.contains("Audio", true) -> OutcomeReason.UNAVAILABLE
            else -> OutcomeReason.PROVIDER
        }
    }
}
