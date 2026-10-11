package dev.r1ptt.messages

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One recipient only. Never interpret a URI, dial string, group, or alphabetic alias as a number. */
object SmsAddress {
    fun normalize(input: String): String? {
        val number = input.trim().replace(Regex("[ ()-]"), "")
        return number.takeIf { it.matches(Regex("\\+?[0-9]{3,15}")) }
    }
}

enum class SentPart { WAITING, SENT, FAILED, UNKNOWN }
enum class DeliveryPart { UNKNOWN, PENDING, DELIVERED, FAILED }
data class SmsPart(
    val sent: SentPart = SentPart.WAITING,
    val delivery: DeliveryPart = DeliveryPart.UNKNOWN,
    val error: Int? = null,
    val radioError: Int? = null,
    val failureSource: String? = null,
)

enum class SmsStatus { RECEIVED, SENDING, SENT, DELIVERED, FAILED, PARTLY_SENT, UNKNOWN, DELIVERY_FAILED }

data class SmsRecord(
    val id: String,
    val peer: String,
    val body: String,
    val createdAt: Long,
    val subscriptionId: Int,
    val incoming: Boolean,
    val token: String = "",
    val parts: List<SmsPart> = emptyList(),
    val systemOwned: Boolean = false,
    val sendEvidence: String? = null,
) {
    fun status(now: Long): SmsStatus {
        if (incoming) return SmsStatus.RECEIVED
        if (parts.all { it.delivery == DeliveryPart.DELIVERED }) return SmsStatus.DELIVERED
        // v0.3.0 read the callback result after goAsync(), persisting 0 instead of Android's result.
        // Keep that evidence intact and label it unknown, never as proof that the text failed.
        if (parts.any { it.sent == SentPart.UNKNOWN || (it.sent == SentPart.FAILED && it.error == 0 && it.failureSource == null) }) return SmsStatus.UNKNOWN
        val sent = parts.count { it.sent == SentPart.SENT }
        val failed = parts.count { it.sent == SentPart.FAILED }
        val waiting = parts.size - sent - failed
        // An absent callback is not evidence that a message failed. Never retry it automatically.
        if (waiting > 0) return if (now - createdAt in 0 until SEND_WAIT_MS) SmsStatus.SENDING else SmsStatus.UNKNOWN
        if (failed > 0) return if (sent > 0) SmsStatus.PARTLY_SENT else SmsStatus.FAILED
        if (parts.any { it.delivery == DeliveryPart.FAILED }) return SmsStatus.DELIVERY_FAILED
        return SmsStatus.SENT
    }

    fun sent(index: Int, success: Boolean, error: Int, radioError: Int? = null, source: String = "Android callback"): SmsRecord = change(index) { part ->
        // A positive delivery report can arrive first. Replayed or conflicting sent callbacks
        // cannot downgrade it, nor change a terminal sent result.
        if (part.sent != SentPart.WAITING) part
        else part.copy(sent = if (success) SentPart.SENT else if (error == 0) SentPart.UNKNOWN else SentPart.FAILED, error = if (success) null else error,
            radioError = if (success) null else radioError, failureSource = if (success) null else source)
    }

    fun delivered(index: Int, result: DeliveryPart): SmsRecord = change(index) { part ->
        when {
            part.delivery == DeliveryPart.DELIVERED || result == DeliveryPart.UNKNOWN -> part
            result == DeliveryPart.DELIVERED -> part.copy(sent = SentPart.SENT, delivery = result, error = null, radioError = null, failureSource = null)
            part.delivery == DeliveryPart.FAILED -> part
            else -> part.copy(delivery = result)
        }
    }

    private fun change(index: Int, block: (SmsPart) -> SmsPart): SmsRecord {
        if (incoming || index !in parts.indices) return this
        return copy(parts = parts.mapIndexed { i, part -> if (i == index) block(part) else part })
    }

    fun encode(): String = JSONObject().put("id", id).put("peer", peer).put("body", body)
        .put("createdAt", createdAt).put("subscriptionId", subscriptionId).put("incoming", incoming)
        .put("token", token).put("systemOwned", systemOwned).put("sendEvidence", sendEvidence).put("parts", JSONArray().also { array -> parts.forEach { p ->
            array.put(JSONObject().put("sent", p.sent.name).put("delivery", p.delivery.name).put("error", p.error)
                .put("radioError", p.radioError).put("failureSource", p.failureSource))
        } }).toString()

    companion object {
        const val SEND_WAIT_MS = 120_000L
        const val MAX_PARTS = 10
        const val MAX_DRAFT_CHARS = 1600

        fun outgoing(peer: String, body: String, subscription: Int, count: Int, now: Long): SmsRecord {
            require(SmsAddress.normalize(peer) == peer && body.isNotBlank())
            require(body.length <= MAX_DRAFT_CHARS && count in 1..MAX_PARTS && subscription >= 0)
            return SmsRecord(UUID.randomUUID().toString(), peer, body, now, subscription, false,
                UUID.randomUUID().toString(), List(count) { SmsPart() })
        }

        fun decode(text: String): SmsRecord {
            val j = JSONObject(text)
            val array = j.getJSONArray("parts")
            val record = SmsRecord(j.getString("id"), j.getString("peer"), j.getString("body"),
                j.getLong("createdAt"), j.getInt("subscriptionId"), j.getBoolean("incoming"),
                j.getString("token"), List(array.length()) { i -> array.getJSONObject(i).let { p ->
                    SmsPart(SentPart.valueOf(p.getString("sent")), DeliveryPart.valueOf(p.getString("delivery")),
                        if (p.has("error") && !p.isNull("error")) p.getInt("error") else null,
                        if (p.has("radioError") && !p.isNull("radioError")) p.getInt("radioError") else null,
                        if (p.has("failureSource") && !p.isNull("failureSource")) p.getString("failureSource") else null)
                } }, j.optBoolean("systemOwned", false),
                if (j.has("sendEvidence") && !j.isNull("sendEvidence")) j.getString("sendEvidence") else null)
            require(record.incoming || record.parts.size in 1..MAX_PARTS)
            return record
        }
    }
}

/** PendingIntent identity includes an unguessable capability and a part index, never message text. */
data class SmsCallback(val id: String, val token: String, val part: Int, val delivery: Boolean) {
    fun uri() = "robotos-sms://${if (delivery) "delivery" else "sent"}/$id/$token/$part"
    fun accepts(record: SmsRecord) = !record.incoming && record.id == id && record.token == token && part in record.parts.indices

    companion object {
        private val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val pattern = Regex("robotos-sms://(sent|delivery)/($UUID_PATTERN)/($UUID_PATTERN)/([0-9])")
        fun parse(uri: String?): SmsCallback? = uri?.let { pattern.matchEntire(it) }?.let { match ->
            SmsCallback(match.groupValues[2], match.groupValues[3], match.groupValues[4].toInt(), match.groupValues[1] == "delivery")
        }
    }
}

object SmsDelivery {
    /** Only a successful status report confirms delivery. Being called back alone proves nothing. */
    fun classify(format: String?, isReport: Boolean, status: Int): DeliveryPart {
        if (!isReport || format !in setOf("3gpp", "3gpp2")) return DeliveryPart.UNKNOWN
        if (status == 0) return DeliveryPart.DELIVERED
        if (format == "3gpp") return when (status) {
            in 0x20..0x3f -> DeliveryPart.PENDING
            in 0x40..0x7f -> DeliveryPart.FAILED
            else -> DeliveryPart.UNKNOWN
        }
        // CDMA status is encoded differently; retain uncertainty rather than guessing at failure.
        return DeliveryPart.UNKNOWN
    }
}

data class SmsDraft(val peer: String = "", val body: String = "")
data class SmsThread(val peer: String, val last: SmsRecord, val unread: Boolean)

fun SmsStatus.label(): String = when (this) {
    SmsStatus.RECEIVED -> "Received"
    SmsStatus.SENDING -> "Sending…"
    SmsStatus.SENT -> "Sent · delivery unconfirmed"
    SmsStatus.DELIVERED -> "Delivered"
    SmsStatus.FAILED -> "Not sent"
    SmsStatus.PARTLY_SENT -> "Partly sent · some parts failed"
    SmsStatus.UNKNOWN -> "Send status unknown · do not assume it failed"
    SmsStatus.DELIVERY_FAILED -> "Sent · carrier reported delivery failure"
}
