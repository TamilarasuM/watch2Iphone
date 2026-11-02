package com.tamilarasu.watch2iphone.ancs

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/*
 * Pure (no Android classes) implementation of the Apple Notification Center Service wire format so it can be
 * unit tested on the JVM. Every constant below was checked against Apple's ANCS specification
 * (Specification + Appendix pages). All numeric values are little endian, all strings UTF-8.
 */

object AncsUuids {
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    /** Client Characteristic Configuration Descriptor (Bluetooth SIG 0x2902), used to enable GATT notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

object EventId {
    const val ADDED = 0
    const val MODIFIED = 1
    const val REMOVED = 2
}

object EventFlags {
    const val SILENT = 1 shl 0
    const val IMPORTANT = 1 shl 1
    const val PRE_EXISTING = 1 shl 2
    const val POSITIVE_ACTION = 1 shl 3
    const val NEGATIVE_ACTION = 1 shl 4
}

object CategoryId {
    const val OTHER = 0
    const val INCOMING_CALL = 1
    const val MISSED_CALL = 2
    const val VOICEMAIL = 3
    const val SOCIAL = 4
    const val SCHEDULE = 5
    const val EMAIL = 6
    const val NEWS = 7
    const val HEALTH_AND_FITNESS = 8
    const val BUSINESS_AND_FINANCE = 9
    const val LOCATION = 10
    const val ENTERTAINMENT = 11
}

object CommandId {
    const val GET_NOTIFICATION_ATTRIBUTES = 0
    const val GET_APP_ATTRIBUTES = 1
    const val PERFORM_NOTIFICATION_ACTION = 2
}

object NotificationAttributeId {
    const val APP_IDENTIFIER = 0
    const val TITLE = 1
    const val SUBTITLE = 2
    const val MESSAGE = 3
    const val MESSAGE_SIZE = 4
    const val DATE = 5
    const val POSITIVE_ACTION_LABEL = 6
    const val NEGATIVE_ACTION_LABEL = 7

    /** Only these three attributes are followed by a 2-byte maximum-length parameter in the request. */
    val TAKES_MAX_LENGTH: Set<Int> = setOf(TITLE, SUBTITLE, MESSAGE)
}

object ActionId {
    const val POSITIVE = 0
    const val NEGATIVE = 1
}

object AppAttributeId {
    const val DISPLAY_NAME = 0
}

/** ATT error codes the Control Point can return on a write (spec: "Control Point error codes"). */
object ControlPointError {
    fun describe(status: Int): String? = when (status) {
        0xA0 -> "unknown command"
        0xA1 -> "invalid command"
        0xA2 -> "invalid parameter (e.g. the notification no longer exists)"
        0xA3 -> "action failed"
        else -> null
    }
}

/** One GATT notification from the Notification Source characteristic. */
data class NotificationEvent(
    val eventId: Int,
    val eventFlags: Int,
    val categoryId: Int,
    val categoryCount: Int,
    val uid: Long,
) {
    val isPreExisting get() = eventFlags and EventFlags.PRE_EXISTING != 0
    val isSilent get() = eventFlags and EventFlags.SILENT != 0
    val hasPositiveAction get() = eventFlags and EventFlags.POSITIVE_ACTION != 0
    val hasNegativeAction get() = eventFlags and EventFlags.NEGATIVE_ACTION != 0

    companion object {
        /**
         * EventID(1) EventFlags(1) CategoryID(1) CategoryCount(1) NotificationUID(4). The spec gives the layout only
         * in a figure; the field order and the 32-bit UID come from the spec text, the 8-byte total is the sum.
         */
        const val SIZE = 8

        fun parse(bytes: ByteArray): NotificationEvent? {
            if (bytes.size < SIZE) return null
            return NotificationEvent(
                eventId = bytes.u8(0),
                eventFlags = bytes.u8(1),
                categoryId = bytes.u8(2),
                categoryCount = bytes.u8(3),
                uid = bytes.u32le(4),
            )
        }
    }
}

/** What a Control Point request expects back on the Data Source. */
data class ExpectedResponse(val commandId: Int, val attributeIds: List<Int>)

object AncsCommands {
    /** App id, title, subtitle, message and the two action labels. The response keeps this order (spec). */
    val NOTIFICATION_ATTRIBUTES: List<Int> = listOf(
        NotificationAttributeId.APP_IDENTIFIER,
        NotificationAttributeId.TITLE,
        NotificationAttributeId.SUBTITLE,
        NotificationAttributeId.MESSAGE,
        NotificationAttributeId.POSITIVE_ACTION_LABEL,
        NotificationAttributeId.NEGATIVE_ACTION_LABEL,
    )

    const val MAX_TITLE = 128
    const val MAX_SUBTITLE = 128
    const val MAX_MESSAGE = 512

    fun getNotificationAttributes(uid: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CommandId.GET_NOTIFICATION_ATTRIBUTES)
        out.writeU32le(uid)
        for (id in NOTIFICATION_ATTRIBUTES) {
            out.write(id)
            if (id in NotificationAttributeId.TAKES_MAX_LENGTH) {
                out.writeU16le(
                    when (id) {
                        NotificationAttributeId.TITLE -> MAX_TITLE
                        NotificationAttributeId.SUBTITLE -> MAX_SUBTITLE
                        else -> MAX_MESSAGE
                    },
                )
            }
        }
        return out.toByteArray()
    }

    val EXPECTED_NOTIFICATION_ATTRIBUTES = ExpectedResponse(CommandId.GET_NOTIFICATION_ATTRIBUTES, NOTIFICATION_ATTRIBUTES)

    /** The AppIdentifier must be NUL-terminated (spec). */
    fun getAppAttributes(appId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CommandId.GET_APP_ATTRIBUTES)
        out.write(appId.toByteArray(StandardCharsets.UTF_8))
        out.write(0)
        out.write(AppAttributeId.DISPLAY_NAME)
        return out.toByteArray()
    }

    val EXPECTED_APP_ATTRIBUTES = ExpectedResponse(CommandId.GET_APP_ATTRIBUTES, listOf(AppAttributeId.DISPLAY_NAME))

    /** Byte 1 CommandID, bytes 2-5 NotificationUID, byte 6 ActionID (spec). The NP sends nothing on the Data Source. */
    fun performNotificationAction(uid: Long, actionId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CommandId.PERFORM_NOTIFICATION_ACTION)
        out.writeU32le(uid)
        out.write(actionId)
        return out.toByteArray()
    }
}

sealed interface DataSourceMessage {
    data class NotificationAttributes(val uid: Long, val attributes: Map<Int, String>) : DataSourceMessage
    data class AppAttributes(val appId: String, val attributes: Map<Int, String>) : DataSourceMessage
}

sealed interface ParseOutcome {
    /** More fragments are needed. */
    data object Incomplete : ParseOutcome

    data class Complete(val message: DataSourceMessage, val consumed: Int) : ParseOutcome

    data class Malformed(val reason: String) : ParseOutcome
}

object DataSourceParser {
    /**
     * Parses a (possibly partial) Data Source response. Layout from the spec:
     *  - Get Notification Attributes: CommandID(1)=0, NotificationUID(4), then (AttributeID(1), Length(2), Data)* in
     *    the order requested.
     *  - Get App Attributes: CommandID(1)=1, AppIdentifier (NUL terminated), then the same tuples.
     * An empty or missing attribute is sent with length 0, so every requested attribute always has a tuple.
     * Attribute data is not NUL terminated.
     */
    fun parse(buffer: ByteArray, expected: ExpectedResponse): ParseOutcome {
        if (buffer.isEmpty()) return ParseOutcome.Incomplete
        val commandId = buffer.u8(0)
        if (commandId != expected.commandId) {
            return ParseOutcome.Malformed("command id $commandId, expected ${expected.commandId}")
        }

        var pos = 1
        var uid = 0L
        var appId = ""
        when (commandId) {
            CommandId.GET_NOTIFICATION_ATTRIBUTES -> {
                if (buffer.size < 5) return ParseOutcome.Incomplete
                uid = buffer.u32le(1)
                pos = 5
            }
            CommandId.GET_APP_ATTRIBUTES -> {
                var end = pos
                while (end < buffer.size && buffer[end] != 0.toByte()) end++
                if (end >= buffer.size) return ParseOutcome.Incomplete
                appId = String(buffer, pos, end - pos, StandardCharsets.UTF_8)
                pos = end + 1
            }
            else -> return ParseOutcome.Malformed("unsupported command id $commandId")
        }

        val attributes = LinkedHashMap<Int, String>()
        for (expectedId in expected.attributeIds) {
            if (buffer.size < pos + 3) return ParseOutcome.Incomplete
            val id = buffer.u8(pos)
            if (id != expectedId) return ParseOutcome.Malformed("attribute id $id at offset $pos, expected $expectedId")
            val length = buffer.u16le(pos + 1)
            if (buffer.size < pos + 3 + length) return ParseOutcome.Incomplete
            attributes[id] = String(buffer, pos + 3, length, StandardCharsets.UTF_8)
            pos += 3 + length
        }

        val message = if (commandId == CommandId.GET_NOTIFICATION_ATTRIBUTES) {
            DataSourceMessage.NotificationAttributes(uid, attributes)
        } else {
            DataSourceMessage.AppAttributes(appId, attributes)
        }
        return ParseOutcome.Complete(message, pos)
    }
}

/** Splices Data Source fragments (the NP splits responses larger than the negotiated MTU) until a response is complete. */
class DataSourceReassembler(private val expected: ExpectedResponse) {
    private var buffer = ByteArray(0)

    val bufferedBytes: Int get() = buffer.size

    fun feed(fragment: ByteArray): ParseOutcome {
        buffer += fragment
        if (buffer.size > MAX_BUFFER) return ParseOutcome.Malformed("response larger than $MAX_BUFFER bytes")
        val outcome = DataSourceParser.parse(buffer, expected)
        if (outcome is ParseOutcome.Complete) buffer = buffer.copyOfRange(outcome.consumed, buffer.size)
        return outcome
    }

    private companion object {
        const val MAX_BUFFER = 64 * 1024
    }
}

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

internal fun ByteArray.u32le(index: Int): Long =
    u8(index).toLong() or (u8(index + 1).toLong() shl 8) or (u8(index + 2).toLong() shl 16) or (u8(index + 3).toLong() shl 24)

internal fun ByteArrayOutputStream.writeU16le(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}

internal fun ByteArrayOutputStream.writeU32le(value: Long) {
    write((value and 0xFF).toInt())
    write(((value shr 8) and 0xFF).toInt())
    write(((value shr 16) and 0xFF).toInt())
    write(((value shr 24) and 0xFF).toInt())
}
