package com.weelay.app.relay

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

// Handles JSON numbers that may be in scientific notation (e.g. 1.77e+15)
object FlexLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("FlexLong", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        if (decoder is JsonDecoder) {
            val str = decoder.decodeJsonElement().jsonPrimitive.content
            return str.toBigDecimal().toLong()
        }
        return decoder.decodeLong()
    }
}

@Serializable
data class RelayBuffer(
    @Serializable(with = FlexLongSerializer::class)
    val id: Long = 0,
    val name: String = "",
    @SerialName("short_name") val shortName: String? = null,
    val title: String = "",
    val type: String = "",
    val number: Int = 0,
    @SerialName("local_variables") val localVariables: Map<String, String> = emptyMap(),
    val hidden: Boolean = false,
    val lines: List<RelayLine> = emptyList()
) {
    // "name" in the API is the full name (e.g. "irc.libera.#archlinux")
    val fullName: String get() = name
}

@Serializable
data class RelayLine(
    @Serializable(with = FlexLongSerializer::class)
    val id: Long = 0,
    @Serializable(with = FlexLongSerializer::class)
    @SerialName("buffer_id") val bufferId: Long = 0,
    val date: String = "",
    @SerialName("date_printed") val datePrinted: String = "",
    val prefix: String = "",
    val message: String = "",
    val tags: List<String> = emptyList(),
    val highlight: Boolean = false,
    val displayed: Boolean = true,
    @SerialName("notify_level") val notifyLevel: Int = 0
)

/**
 * One entry of WeeChat's own hotlist — the source of truth for what is unread.
 *
 * [count] is indexed by notify level: 0 low (joins/parts and similar), 1 message, 2 private,
 * 3 highlight. Using this instead of counting lines client-side means badges are correct as soon
 * as the app connects, rather than only reflecting what arrived while it happened to be running.
 */
@Serializable
data class RelayHotlistEntry(
    val priority: Int = 0,
    val date: String = "",
    @Serializable(with = FlexLongSerializer::class)
    @SerialName("buffer_id") val bufferId: Long = 0,
    val count: List<Int> = emptyList()
) {
    private fun countAt(level: Int): Int = count.getOrElse(level) { 0 }

    /** Messages worth badging: regular chat plus private messages. Low-level noise is ignored. */
    val messageCount: Int get() = countAt(LEVEL_MESSAGE) + countAt(LEVEL_PRIVATE)

    val highlightCount: Int get() = countAt(LEVEL_HIGHLIGHT)

    /** Everything worth badging, highlights included. */
    val totalCount: Int get() = messageCount + highlightCount

    companion object {
        const val LEVEL_MESSAGE = 1
        const val LEVEL_PRIVATE = 2
        const val LEVEL_HIGHLIGHT = 3
    }
}

@Serializable
data class RelayNicklistGroup(
    @Serializable(with = FlexLongSerializer::class)
    val id: Long = 0,
    val name: String = "",
    val visible: Boolean = true,
    val groups: List<RelayNicklistGroup> = emptyList(),
    val nicks: List<RelayNickEntry> = emptyList()
)

@Serializable
data class RelayNickEntry(
    @Serializable(with = FlexLongSerializer::class)
    val id: Long = 0,
    val prefix: String = "",
    val name: String = "",
    val color: String = "",
    val visible: Boolean = true
)

// Flat entry used by the UI
data class RelayNicklistEntry(
    val prefix: String = "",
    val name: String = "",
    val visible: Boolean = true
)

/**
 * An emission of lines for a buffer. [isHistory] is true for fetched backlog (which must never
 * raise notifications) and false for live sync lines.
 *
 * Field order (bufferName, lines) is kept so existing `(bufferName, lines)` destructuring keeps
 * working for consumers that don't care about [isHistory].
 */
data class LinesUpdate(
    val bufferName: String,
    val lines: List<RelayLine>,
    val isHistory: Boolean = false
)
