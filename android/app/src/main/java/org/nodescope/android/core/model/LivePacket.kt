package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.nodescope.android.core.network.protocolJson
import java.time.Instant

/** Shared by the live feed lookup and Observers; missing telemetry stays null, never zero. */
@Serializable
data class MeshObserver(val id: String, val name: String? = null, val iata: String? = null,
    val lat: Double? = null, val lon: Double? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("first_seen") val firstSeen: String? = null,
    @SerialName("packet_count") val packetCount: Long? = null,
    val packetsLastHour: Long? = null,
    val model: String? = null, val firmware: String? = null,
    @SerialName("client_version") val clientVersion: String? = null,
    val radio: String? = null,
    @SerialName("battery_mv") val batteryMv: Int? = null,
    @SerialName("uptime_secs") val uptimeSecs: Long? = null,
    @SerialName("noise_floor") val noiseFloor: Double? = null,
    val nodeRole: String? = null,
) {
    val coordinate: Coordinate? get() = Coordinate.gps(lat, lon)
    val displayName: String get() = name?.takeIf(String::isNotBlank) ?: id.take(12)
}
@Serializable data class ObserversResponse(val observers: List<MeshObserver> = emptyList())

data class LivePacket(
    val id: Long, val hash: String, val payloadType: Int?, val typeName: String,
    val observerId: String?, val observerName: String?, val region: String?,
    val snr: Double?, val rssi: Double?, val hops: List<String?>,
    val resolvedPath: List<String?>, val timestamp: String?,
    val receivedAt: Long, val isLive: Boolean, val observations: Int = 1,
    /** Decoded payload fields used for previews: message text, advert name, channel. */
    val payloadText: String? = null, val payloadName: String? = null, val payloadChannel: String? = null,
    val payloadVersion: Int? = null, val rawHex: String? = null,
) {
    // Same transmission can have different observers and routes; retain those observations.
    val key: String get() = "$id|$hash|$observerId|${hops.joinToString(",")}|${resolvedPath.joinToString(",")}"
    val title: String get() = observerName?.takeIf { it.isNotBlank() } ?: observerId?.take(12) ?: "Unknown observer"
}

/** Accept both current REST-shaped socket frames and the older decoded header/path schema. */
fun parseLivePacket(data: JsonObject, live: Boolean, now: Long = System.currentTimeMillis()): LivePacket? {
    val id = data["id"]?.jsonPrimitive?.longOrNull ?: return null
    val decoded = data["decoded"] as? JsonObject
        ?: data.text("decoded_json")?.let { runCatching { protocolJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    val header = decoded?.get("header") as? JsonObject
    val type = data.number("payload_type")?.toInt() ?: header?.number("payloadType")?.toInt()
    val name = header?.text("payloadTypeName") ?: payloadTypeName(type)
    // Socket frames nest the payload; REST-shaped `decoded_json` is the payload itself.
    val payload = decoded?.get("payload") as? JsonObject ?: decoded
    val decodedHops = (decoded?.get("path") as? JsonObject)?.get("hops") as? JsonArray
    val path = decodedHops ?: data.text("path_json")?.let { runCatching { protocolJson.parseToJsonElement(it) as? JsonArray }.getOrNull() }
        ?: data["path_json"] as? JsonArray
    val hops = path.orEmpty().map { value -> when (value) {
        is JsonPrimitive -> if (value is JsonNull) null else if (value.isString) value.content
            else value.intOrNull?.takeIf { it in 0..255 }?.let { "%02x".format(it) }
        is JsonObject -> value.text("pubkey") ?: value.text("hash")
        else -> null
    } }
    val resolved = (data["resolved_path"] as? JsonArray ?: decoded?.get("resolved_path") as? JsonArray)
        .orEmpty().map { (it as? JsonPrimitive)?.contentOrNull }
    return LivePacket(id, data.text("hash").orEmpty(), type, name, data.text("observer_id"), data.text("observer_name"),
        data.text("observer_iata"), data.number("snr"), data.number("rssi"), hops, resolved,
        data.text("timestamp") ?: data.text("first_seen"), now, live, data.number("observation_count")?.toInt() ?: 1,
        payload?.text("text")?.takeIf(String::isNotBlank), payload?.text("name")?.takeIf(String::isNotBlank),
        payload?.text("channel")?.takeIf(String::isNotBlank), header?.number("payloadVersion")?.toInt(),
        (data.text("raw_hex") ?: data.text("raw"))?.takeIf(String::isNotBlank))
}
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

fun packetEpoch(packet: LivePacket): Long = packet.timestamp?.let {
    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
} ?: packet.receivedAt
