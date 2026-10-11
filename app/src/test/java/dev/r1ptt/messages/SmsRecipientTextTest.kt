package dev.r1ptt.messages

import org.junit.Assert.*
import org.junit.Test

class SmsRecipientTextTest {
    @Test fun spokenAndWrittenNumbersBecomeDigits() {
        for ((spoken, digits) in listOf(
            "four zero five five five five zero one two three" to "4055550123",
            "four oh five, five five five, oh one twenty three" to "4055550123",
            "405-555-0123" to "4055550123",
            "(405) 555-0123" to "4055550123",
            "405.555.0123" to "4055550123",
            "plus one 405 555 0123" to "+14055550123",
            "+1 four zero five five five five zero one two three" to "+14055550123",
            "eight hundred five five five one two one two" to "8005551212",
            "five five five, twelve twelve" to "5551212",
            "nine one nine two twenty thirty-three forty-four" to "9192203344",
            "four oh five double five triple seven one" to "405557771",
            "Four Oh Five" to "405",
        )) assertEquals(spoken, digits, SmsRecipientText.digits(spoken))
    }

    @Test fun wordsAndAmbiguousNumbersAreNotNumbers() {
        for (text in listOf("Sam", "my mom", "Number One", "two hundred twelve", "five hundred and six", "double", "1-800-FLOWERS",
            "four oh five; five", "+1 + 2", "one two three plus four", "１２３４")) {
            assertNull(text, SmsRecipientText.digits(text))
        }
    }

    @Test fun nationalNumbersGetPlusOneOnlyOnNanpSims() {
        assertEquals("+14055550123", SmsRecipientText.number("four zero five five five five zero one two three", "us"))
        assertEquals("+14055550123", SmsRecipientText.number("1 405 555 0123", "US"))
        assertEquals("+14055550123", SmsRecipientText.number("+1 (405) 555-0123", "gb"))
        assertEquals("4055550123", SmsRecipientText.number("405-555-0123", null))
        assertEquals("4055550123", SmsRecipientText.number("405-555-0123", "gb"))
        assertEquals("911", SmsRecipientText.number("nine one one", "us"))
        assertEquals("1135550123", SmsRecipientText.number("113 555 0123", "us")) // not a NANP area code
        assertNull(SmsRecipientText.number("Sam", "us"))
        assertTrue(SmsRecipientText.sameNumber("(405) 555-0123", "+14055550123", "us"))
        assertFalse(SmsRecipientText.sameNumber("(405) 555-0123", "+14055550124", "us"))
        assertFalse(SmsRecipientText.sameNumber("Sam", "Sam", "us"))
    }

    @Test fun resolverAcceptsSpokenNumbersAndMyPrefixedNames() {
        val saved = listOf(SmsRecipient(1, "Mom", "+14055550188"), SmsRecipient(2, "My Dad", "+14055550189"))
        assertEquals(SmsResolution.Number("+14055550123"), SmsRecipientResolver.resolve("four zero five five five five zero one two three", saved, "us"))
        assertEquals(SmsResolution.Number("+14055550188"), SmsRecipientResolver.resolve("my mom", saved, "us"))
        assertEquals(SmsResolution.Number("+14055550188"), SmsRecipientResolver.resolve("Mom.", saved, "us"))
        assertEquals(SmsResolution.Number("+14055550189"), SmsRecipientResolver.resolve("my dad", saved, "us"))
        assertEquals(SmsResolution.Missing, SmsRecipientResolver.resolve("my sister", saved, "us"))
    }
}
