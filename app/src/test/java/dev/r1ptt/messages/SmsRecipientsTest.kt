package dev.r1ptt.messages

import org.junit.Assert.*
import org.junit.Test

class SmsRecipientsTest {
    @Test fun localExactNamesOnlyAndNoGuessedPhoneNumbers() {
        val saved = listOf(SmsRecipient(1, "Yana", "+15551234567"))
        assertEquals(SmsResolution.Number("+15551234567"), SmsRecipientResolver.resolve(" yana ", saved))
        assertEquals(SmsResolution.Missing, SmsRecipientResolver.resolve("Yanna", saved))
        assertEquals(SmsResolution.Missing, SmsRecipientResolver.resolve("my friend", saved))
        assertEquals(SmsResolution.Missing, SmsRecipientResolver.resolve("Yana", emptyList()))
        assertEquals(SmsResolution.Number("+15557654321"), SmsRecipientResolver.resolve("+1 555-765-4321", saved))
    }
    @Test fun duplicateNamesRequireAnExplicitLocalChoice() {
        val saved = listOf(SmsRecipient(1, "Yana", "+15551234567"), SmsRecipient(2, "Yana", "+15557654321"))
        assertEquals(SmsResolution.Choose(saved), SmsRecipientResolver.resolve("Yana", saved))
        assertEquals(SmsResolution.Number("+15551234567"), SmsRecipientResolver.resolve("Yana", listOf(saved[0], saved[0].copy(id=3))))
    }
}
