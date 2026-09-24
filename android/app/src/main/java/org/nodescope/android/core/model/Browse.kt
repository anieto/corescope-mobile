package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** ISO-8601 instants; offsets such as `-05:00` are accepted on every Android version. */
fun parseInstant(value: String?): Instant? = value?.let {
    runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
}

/** GET /api/channels */
@Serializable
data class MeshChannel(
    val hash: String,
    val name: String,
    val lastMessage: String? = null,
    val lastSender: String? = null,
    val messageCount: Int = 0,
    val lastActivity: String? = null,
)
@Serializable data class ChannelsResponse(val channels: List<MeshChannel> = emptyList())

/** GET /api/channels/{hash}/messages */
@Serializable
data class ChannelMessage(
    val sender: String? = null,
    val text: String = "",
    val timestamp: String? = null,
    @SerialName("sender_timestamp") val senderTimestamp: Double? = null,
    val packetId: Long? = null,
    val packetHash: String? = null,
    val repeats: Int? = null,
    val observers: List<String> = emptyList(),
    val hops: Int? = null,
    val snr: Double? = null,
)
@Serializable data class ChannelMessagesResponse(val messages: List<ChannelMessage> = emptyList(), val total: Int? = null)

/** A channel whose key is held only on this device; the key never goes to an analyzer. */
@Serializable
data class MonitoredChannel(
    val channelName: String,
    val keyHex: String,
    val displayName: String? = null,
    val createdAt: Long = 0,
) {
    val title: String get() = displayName?.takeIf(String::isNotBlank) ?: channelName
}

/** GET /api/observers/{id}/analytics */
@Serializable
data class ObserverAnalytics(
    val timeline: List<LabeledCount> = emptyList(),
    val packetTypes: Map<String, Long> = emptyMap(),
    val nodesTimeline: List<LabeledCount> = emptyList(),
    val snrDistribution: List<SnrBucket> = emptyList(),
    val recentPackets: List<ObserverRecentPacket> = emptyList(),
)
@Serializable data class LabeledCount(val label: String, val count: Long)
@Serializable data class SnrBucket(val range: String, val count: Long)

/** The analytics endpoint returns a lightweight packet projection, not the /api/packets shape. */
@Serializable
data class ObserverRecentPacket(
    val id: Long,
    val hash: String = "",
    val timestamp: String? = null,
    @SerialName("payload_type") val payloadType: Int? = null,
    val snr: Double? = null,
    val rssi: Double? = null,
    val direction: String? = null,
)

/** Human-readable MeshCore payload type, matching the iOS analytics labels. */
fun payloadTypeLabel(code: Int?): String = when (code) {
    0 -> "Request"; 1 -> "Response"; 2 -> "Direct Msg"; 3 -> "ACK"; 4 -> "Advert"
    5 -> "Channel Msg"; 6 -> "Group Data"; 7 -> "Anon Req"; 8 -> "Path"; 9 -> "Trace"
    10 -> "Multipart"; 11 -> "Control"; 15 -> "Raw Custom"
    null -> "Unknown"
    else -> "Type $code"
}

/** GET /api/packets/{hash}: one transmission with every observation. */
@Serializable
data class PacketDetail(
    val packet: PacketRecord,
    val path: List<String> = emptyList(),
    @SerialName("observation_count") val observationCount: Int? = null,
    val observations: List<PacketObservation> = emptyList(),
)
@Serializable
data class PacketRecord(
    val id: Long,
    val hash: String = "",
    @SerialName("payload_type") val payloadType: Int? = null,
    @SerialName("payload_version") val payloadVersion: Int? = null,
    @SerialName("decoded_json") val decodedJson: String? = null,
    @SerialName("raw_hex") val rawHex: String? = null,
    val snr: Double? = null,
    val rssi: Double? = null,
    val timestamp: String? = null,
)
@Serializable
data class PacketObservation(
    val id: Long,
    @SerialName("observer_name") val observerName: String? = null,
    @SerialName("observer_iata") val observerIata: String? = null,
    val snr: Double? = null,
    val rssi: Double? = null,
    val timestamp: String? = null,
    /** CoreScope uses null for hops it couldn't resolve to a node. */
    @SerialName("resolved_path") val resolvedPath: List<String?>? = null,
)

/** MeshCore payload type as the wire name (ADVERT, GRP_TXT, …), shared by packet screens. */
fun payloadTypeName(code: Int?): String = when (code) {
    0 -> "REQ"; 1 -> "RESPONSE"; 2 -> "TXT_MSG"; 3 -> "ACK"; 4 -> "ADVERT"
    5 -> "GRP_TXT"; 6 -> "GRP_DATA"; 7 -> "ANON_REQ"; 8 -> "PATH"; 9 -> "TRACE"
    10 -> "MULTIPART"; 11 -> "CONTROL"; 15 -> "RAW_CUSTOM"
    else -> "UNKNOWN"
}
