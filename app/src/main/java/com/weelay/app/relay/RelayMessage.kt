package com.weelay.app.relay

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
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

@Serializable
data class SyncEvent(
    val type: String = "",
    val body: JsonElement? = null
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
