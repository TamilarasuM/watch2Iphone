package com.tamilarasu.watch2iphone.ancs

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AncsProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun tuple(id: Int, text: String): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        return bytes(id, data.size and 0xFF, (data.size shr 8) and 0xFF) + data
    }

    private fun notificationResponse(uid: Long, vararg attrs: Pair<Int, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CommandId.GET_NOTIFICATION_ATTRIBUTES)
        out.writeU32le(uid)
        attrs.forEach { out.write(tuple(it.first, it.second)) }
        return out.toByteArray()
    }

    private val fullResponse = notificationResponse(
        0x01020304L,
        NotificationAttributeId.APP_IDENTIFIER to "com.apple.MobileSMS",
        NotificationAttributeId.TITLE to "Alice",
        NotificationAttributeId.SUBTITLE to "",
        NotificationAttributeId.MESSAGE to "Héllo 👋 from iPhone",
        NotificationAttributeId.POSITIVE_ACTION_LABEL to "Reply",
        NotificationAttributeId.NEGATIVE_ACTION_LABEL to "Clear",
    )

    @Test
    fun notificationSource_parsesEightByteEvent() {
        // EventID=0 Added, flags=Important|NegativeAction, category=4 Social, count=2, uid=0x01020304
        val event = NotificationEvent.parse(bytes(0, 0x02 or 0x10, 4, 2, 0x04, 0x03, 0x02, 0x01))!!
        assertEquals(EventId.ADDED, event.eventId)
        assertEquals(CategoryId.SOCIAL, event.categoryId)
        assertEquals(2, event.categoryCount)
        assertEquals(0x01020304L, event.uid)
        assertTrue(event.hasNegativeAction)
        assertTrue(!event.hasPositiveAction)
        assertTrue(!event.isPreExisting)
    }

    @Test
    fun notificationSource_uidIsUnsigned32Bit() {
        val event = NotificationEvent.parse(bytes(0, 0, 0, 1, 0xFF, 0xFF, 0xFF, 0xFF))!!
        assertEquals(0xFFFFFFFFL, event.uid)
    }

    @Test
    fun notificationSource_tooShortIsRejected() {
        assertNull(NotificationEvent.parse(bytes(0, 0, 0, 0, 1, 2, 3)))
    }

    @Test
    fun getNotificationAttributes_layoutMatchesSpec() {
        val expected = bytes(
            0, // CommandIDGetNotificationAttributes
            0x04, 0x03, 0x02, 0x01, // NotificationUID little endian
            0, // AppIdentifier
            1, 0x80, 0x00, // Title, max length 128
            2, 0x80, 0x00, // Subtitle, max length 128
            3, 0x00, 0x02, // Message, max length 512
            6, // PositiveActionLabel
            7, // NegativeActionLabel
        )
        assertArrayEquals(expected, AncsCommands.getNotificationAttributes(0x01020304L))
    }

    @Test
    fun getAppAttributes_appIdentifierIsNulTerminated() {
        val expected = "com.x".toByteArray() + bytes(0, 0)
        assertArrayEquals(bytes(1) + expected, AncsCommands.getAppAttributes("com.x"))
    }

    @Test
    fun performNotificationAction_layoutMatchesSpec() {
        assertArrayEquals(
            bytes(2, 0x04, 0x03, 0x02, 0x01, 1),
            AncsCommands.performNotificationAction(0x01020304L, ActionId.NEGATIVE),
        )
    }

    @Test
    fun parse_completeResponseInOneChunk() {
        val outcome = DataSourceParser.parse(fullResponse, AncsCommands.EXPECTED_NOTIFICATION_ATTRIBUTES)
        val complete = outcome as ParseOutcome.Complete
        val message = complete.message as DataSourceMessage.NotificationAttributes
        assertEquals(0x01020304L, message.uid)
        assertEquals("com.apple.MobileSMS", message.attributes[NotificationAttributeId.APP_IDENTIFIER])
        assertEquals("Alice", message.attributes[NotificationAttributeId.TITLE])
        assertEquals("", message.attributes[NotificationAttributeId.SUBTITLE])
        assertEquals("Héllo 👋 from iPhone", message.attributes[NotificationAttributeId.MESSAGE])
        assertEquals("Reply", message.attributes[NotificationAttributeId.POSITIVE_ACTION_LABEL])
        assertEquals("Clear", message.attributes[NotificationAttributeId.NEGATIVE_ACTION_LABEL])
        assertEquals(fullResponse.size, complete.consumed)
    }

    @Test
    fun reassembler_everySplitPointProducesTheSameResult() {
        val expected = AncsCommands.EXPECTED_NOTIFICATION_ATTRIBUTES
        for (split in 1 until fullResponse.size) {
            val reassembler = DataSourceReassembler(expected)
            val first = reassembler.feed(fullResponse.copyOfRange(0, split))
            assertEquals("split=$split must be incomplete", ParseOutcome.Incomplete, first)
            val second = reassembler.feed(fullResponse.copyOfRange(split, fullResponse.size))
            val message = (second as ParseOutcome.Complete).message as DataSourceMessage.NotificationAttributes
            assertEquals("split=$split", "Alice", message.attributes[NotificationAttributeId.TITLE])
        }
    }

    @Test
    fun reassembler_oneByteAtATime() {
        val reassembler = DataSourceReassembler(AncsCommands.EXPECTED_NOTIFICATION_ATTRIBUTES)
        var outcome: ParseOutcome = ParseOutcome.Incomplete
        for (i in fullResponse.indices) {
            outcome = reassembler.feed(byteArrayOf(fullResponse[i]))
            if (i < fullResponse.size - 1) assertEquals(ParseOutcome.Incomplete, outcome)
        }
        assertTrue(outcome is ParseOutcome.Complete)
        assertEquals(0, reassembler.bufferedBytes)
    }

    @Test
    fun reassembler_keepsLeftoverBytesOfTheNextResponse() {
        val reassembler = DataSourceReassembler(AncsCommands.EXPECTED_NOTIFICATION_ATTRIBUTES)
        val outcome = reassembler.feed(fullResponse + bytes(0, 9, 9))
        assertTrue(outcome is ParseOutcome.Complete)
        assertEquals(3, reassembler.bufferedBytes)
    }

    @Test
    fun parse_wrongCommandIdIsMalformed() {
        val outcome = DataSourceParser.parse(bytes(1, 0, 0, 0, 0), AncsCommands.EXPECTED_NOTIFICATION_ATTRIBUTES)
        assertTrue(outcome is ParseOutcome.Malformed)
    }

    @Test
    fun parse_attributesOutOfRequestedOrderAreMalformed() {
        val swapped = notificationResponse(
            1L,
            NotificationAttributeId.TITLE to "x",
            NotificationAttributeId.APP_IDENTIFIER to "y",
        )
        val outcome = DataSourceParser.parse(swapped, AncsCommands.EXPECTED_NOTIFICATION_ATTRIBUTES)
        assertTrue(outcome is ParseOutcome.Malformed)
    }

    @Test
    fun parse_appAttributesResponse() {
        val response = bytes(1) + "com.apple.MobileSMS".toByteArray() + bytes(0) + tuple(AppAttributeId.DISPLAY_NAME, "Messages")
        val outcome = DataSourceParser.parse(response, AncsCommands.EXPECTED_APP_ATTRIBUTES) as ParseOutcome.Complete
        val message = outcome.message as DataSourceMessage.AppAttributes
        assertEquals("com.apple.MobileSMS", message.appId)
        assertEquals("Messages", message.attributes[AppAttributeId.DISPLAY_NAME])
    }

    @Test
    fun parse_appAttributesIncompleteUntilNulAndTuple() {
        val expected = AncsCommands.EXPECTED_APP_ATTRIBUTES
        assertEquals(ParseOutcome.Incomplete, DataSourceParser.parse(bytes(1, 0x63, 0x6F), expected))
        assertEquals(ParseOutcome.Incomplete, DataSourceParser.parse(bytes(1, 0x63, 0), expected))
    }

    @Test
    fun controlPointErrors_areNamed() {
        assertEquals("invalid parameter (e.g. the notification no longer exists)", ControlPointError.describe(0xA2))
        assertNull(ControlPointError.describe(0x05))
    }
}
