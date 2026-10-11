package dev.r1ptt.messages

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Recipients as people say them and transcribers write them: "405-555-0123", "four oh five,
 * five five five, oh one twenty three", "plus one …", or a saved name in any capitalization.
 * Local and deterministic, so the AI only copies the user's words and never normalizes a number.
 */
object SmsRecipientText {
    private const val BREAK = "\u0000"
    private val units = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        .withIndex().associate { it.value to it.index } + mapOf("oh" to 0, "o" to 0)
    private val teens = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        .withIndex().associate { it.value to it.index + 10 }
    private val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        .withIndex().associate { it.value to (it.index + 2) * 10 }
    private val repeats = mapOf("double" to 2, "triple" to 3)
    private val numberWords = units.keys + teens.keys + tens.keys + repeats.keys + setOf("hundred", "plus")
    private val piece = Regex("\\+|[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}+]+")
    /** Spacing inside one spoken or written number. Anything else ends it. */
    private val soft = Regex("[\\s,()\\-–—'’]+|\\.")
    // North American Numbering Plan regions: ten-digit national numbers are +1 numbers.
    private val nanp = setOf("us", "ca", "pr", "vi", "gu", "as", "mp", "ag", "ai", "bb", "bm", "bs", "dm", "do",
        "gd", "jm", "kn", "ky", "lc", "ms", "sx", "tc", "tt", "vc", "vg")

    private fun tokens(text: String): List<String> = piece.findAll(text.lowercase(Locale.ROOT)).mapNotNull { m ->
        val value = m.value
        when {
            value == "+" || value[0].isLetterOrDigit() -> value
            soft.matches(value) -> null
            else -> BREAK
        }
    }.toList()

    private fun numeric(token: String) = token == "+" || token in numberWords || (token.isNotEmpty() && token.all { it in '0'..'9' })

    /** Digits for a run of number tokens, or null when the run is not unambiguously one number. */
    private fun parse(run: List<String>): String? {
        val out = StringBuilder()
        var plus = false
        var unit = false // the previous token was one spoken digit 1..9, which "hundred" may scale
        var i = 0
        while (i < run.size) {
            val word = run[i]
            val next = run.getOrNull(i + 1)
            when {
                word == "+" || word == "plus" -> { if (plus || out.isNotEmpty()) return null; plus = true; unit = false }
                word.all { it in '0'..'9' } -> { out.append(word); unit = false }
                word in units -> { out.append(units.getValue(word)); unit = units.getValue(word) > 0 }
                word in teens -> { out.append(teens.getValue(word)); unit = false }
                word in tens -> {
                    // "twenty one" is 21; "twenty oh five" stays 2005.
                    val digit = next?.takeIf { it != "oh" && it != "o" }?.let { units[it] }?.takeIf { it > 0 }
                    out.append(tens.getValue(word) + (digit ?: 0))
                    if (digit != null) i++
                    unit = false
                }
                word == "hundred" -> {
                    // "eight hundred five five five" is 800-555; "two hundred twelve" could be 212 or 20012.
                    if (!unit || next == "and" || next in teens || next in tens) return null
                    out.append("00"); unit = false
                }
                word in repeats -> {
                    val digit = next?.let { units[it]?.toString() ?: it.takeIf { d -> d.length == 1 && d[0] in '0'..'9' } } ?: return null
                    out.append(digit.repeat(repeats.getValue(word))); i++; unit = false
                }
                else -> return null
            }
            i++
        }
        return if (out.isEmpty()) null else (if (plus) "+" else "") + out
    }

    /** The digits of a phrase that is only a number, e.g. "four oh five, five five five…"; else null. */
    fun digits(phrase: String): String? {
        val words = tokens(phrase)
        if (words.isEmpty() || !words.all(::numeric)) return null
        return parse(words)
    }

    /** A spoken or written number as Android can dial it; ten-digit numbers get +1 on a NANP SIM. */
    fun number(phrase: String, region: String?): String? {
        val number = SmsAddress.normalize(phrase) ?: digits(phrase)?.let(SmsAddress::normalize) ?: return null
        if (number.startsWith("+") || region?.lowercase(Locale.ROOT) !in nanp) return number
        fun national(n: String) = n.length == 10 && n[0] in '2'..'9' && n[3] in '2'..'9'
        return when {
            national(number) -> "+1$number"
            number.length == 11 && number[0] == '1' && national(number.substring(1)) -> "+$number"
            else -> number
        }
    }

    /** Whether two written numbers reach the same phone, e.g. "(405) 555-0123" and "+14055550123". */
    fun sameNumber(a: String, b: String, region: String?): Boolean {
        val x = number(a, region) ?: return false
        return x == number(b, region)
    }

    /** The SMS SIM's country, which decides how a national number is dialed. */
    fun region(context: Context): String? = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(SubscriptionManager.getDefaultSmsSubscriptionId())
        tm.simCountryIso.ifBlank { tm.networkCountryIso }.takeIf { it.isNotBlank() }
    }.getOrNull()
}
