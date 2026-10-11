package dev.r1ptt.messages

import org.junit.Assert.*
import org.junit.Test

class SmsModelsTest {
    private fun message(parts: Int = 2) = SmsRecord.outgoing("+15551234567", "Test only", 1, parts, 1000)

    @Test fun singleRecipientOnly() {
        assertEquals("+15551234567", SmsAddress.normalize(" +1 (555) 123-4567 "))
        assertEquals("12345", SmsAddress.normalize("12345"))
        listOf("", "12", "1".repeat(16), "555,666", "555;666", "*123#", "tel:555", "sms:555", "Yana", "a@b.com", "１２３４", "555\n666", "+1+555", "555/666").forEach { assertNull(it, SmsAddress.normalize(it)) }
    }

    @Test fun everyPartMustSendAndDeliver() {
        val original = message()
        assertEquals(SmsStatus.SENDING, original.status(1001))
        val one = original.sent(0, true, -1)
        assertEquals(SmsStatus.SENDING, one.status(1001))
        val all = one.sent(1, true, -1)
        assertEquals(SmsStatus.SENT, all.status(1001))
        val delivered = all.delivered(0, DeliveryPart.DELIVERED)
        assertEquals(SmsStatus.SENT, delivered.status(1001))
        assertEquals(SmsStatus.DELIVERED, delivered.delivered(1, DeliveryPart.DELIVERED).status(1001))
    }

    @Test fun missingCallbacksRemainUncertain() {
        val original = message().sent(0, true, -1)
        assertEquals(SmsStatus.UNKNOWN, original.status(1000 + SmsRecord.SEND_WAIT_MS))
        assertEquals(SmsStatus.UNKNOWN, original.status(0)) // clock correction must not imply an endless send
        assertEquals(SmsStatus.PARTLY_SENT, original.sent(1, false, 4).status(2000))
        assertEquals(SmsStatus.FAILED, message(1).sent(0, false, 4).status(2000))
    }

    @Test fun deliveryBeforeSentAndDuplicateCallbacksDoNotDowngrade() {
        val delivered = message(1).delivered(0, DeliveryPart.DELIVERED)
        assertEquals(delivered, delivered.sent(0, false, 4))
        assertEquals(delivered, delivered.delivered(0, DeliveryPart.PENDING))
        assertEquals(delivered, delivered.delivered(0, DeliveryPart.FAILED))
        assertEquals(delivered, delivered.sent(99, true, -1))
    }

    @Test fun carrierFailureIsDifferentFromFailureToSend() {
        val record = message(1).sent(0, true, -1).delivered(0, DeliveryPart.FAILED)
        assertEquals(SmsStatus.DELIVERY_FAILED, record.status(5000))
    }

    @Test fun callbackAloneIsNotProofOfDelivery() {
        assertEquals(DeliveryPart.DELIVERED, SmsDelivery.classify("3gpp", true, 0))
        assertEquals(DeliveryPart.DELIVERED, SmsDelivery.classify("3gpp2", true, 0))
        assertEquals(DeliveryPart.PENDING, SmsDelivery.classify("3gpp", true, 0x20))
        assertEquals(DeliveryPart.FAILED, SmsDelivery.classify("3gpp", true, 0x40))
        listOf(SmsDelivery.classify(null, true, 0), SmsDelivery.classify("3gpp", false, 0),
            SmsDelivery.classify("3gpp", true, 1), SmsDelivery.classify("3gpp", true, -1),
            SmsDelivery.classify("3gpp2", true, 0x20000)).forEach { assertEquals(DeliveryPart.UNKNOWN, it) }
    }

    @Test fun savedAttemptKeepsItsIdentityAndPartialResults() {
        val record = message().sent(0, true, -1).delivered(0, DeliveryPart.PENDING).sent(1, false, 7)
        assertEquals(record, SmsRecord.decode(record.encode()))
        assertEquals(SmsStatus.PARTLY_SENT, SmsRecord.decode(record.encode()).status(900_000))
    }

    @Test fun recordsSavedWithTheRetiredAssistantLinkStillLoad() {
        val record = message().sent(0, true, -1)
        val saved = org.json.JSONObject(record.encode()).put("assistantOutcomeId", "synthetic-outcome-id")
        assertEquals(record, SmsRecord.decode(saved.toString()))
    }

    @Test fun staleForgedAndMalformedCallbacksAreRejected() {
        val record = message()
        val valid = SmsCallback(record.id, record.token, 1, true)
        assertEquals(valid, SmsCallback.parse(valid.uri()))
        assertTrue(valid.accepts(record))
        assertFalse(valid.copy(token = message().token).accepts(record))
        assertFalse(valid.copy(id = message().id).accepts(record))
        assertFalse(valid.copy(part = 2).accepts(record))
        listOf(valid.uri()+"?body=test", valid.uri()+"/", valid.uri().replace("robotos-sms", "https"), valid.uri().dropLast(1)+"-1").forEach { assertNull(SmsCallback.parse(it)) }
    }

    @Test fun noZeroPartOrOversizeOutboxRecords() {
        assertThrows(IllegalArgumentException::class.java) { message(0) }
        assertThrows(IllegalArgumentException::class.java) { message(11) }
        assertThrows(IllegalArgumentException::class.java) { SmsRecord.outgoing("Yana", "test", 1, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SmsRecord.outgoing("123", "x".repeat(1601), 1, 1, 0) }
    }
}
