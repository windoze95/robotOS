package dev.r1ptt.messages

import java.util.Locale

data class SmsRecipient(val id: Long, val name: String, val number: String)
sealed interface SmsResolution {
    data class Number(val number: String) : SmsResolution
    data class Choose(val matches: List<SmsRecipient>) : SmsResolution
    data object Missing : SmsResolution
}

object SmsRecipientResolver {
    private fun key(name: String) = name.trim().trimEnd('.', ',', '!', '?').replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    /** [region] is the SMS SIM's country; it only decides whether a ten-digit number is a +1 number. */
    fun resolve(request: String, saved: List<SmsRecipient>, region: String? = null): SmsResolution {
        SmsRecipientText.number(request, region)?.let { return SmsResolution.Number(it) }
        fun named(name: String) = saved.filter { key(it.name) == key(name) && SmsAddress.normalize(it.number) == it.number }
            .distinctBy { it.number }
        // "Text my mom" names the saved "Mom"; an exact saved "My Mom" still wins.
        val matches = named(request).ifEmpty { Regex("^\\s*my\\s+(.+)$", RegexOption.IGNORE_CASE).find(request)?.let { named(it.groupValues[1]) }.orEmpty() }
        return when (matches.size) {
            0 -> SmsResolution.Missing
            1 -> SmsResolution.Number(matches.single().number)
            else -> SmsResolution.Choose(matches)
        }
    }
}
