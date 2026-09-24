package org.nodescope.android.core.network

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.nodescope.android.core.model.*

/** A GRP_TXT/CHAN packet from /api/packets, reduced to what channel monitoring needs. */
data class ChannelPacket(
    val id: Long, val hash: String, val firstSeen: String?, val observationCount: Int,
    val observerName: String?, val snr: Double?, val payload: GroupPayload,
)
/** Server-decoded group payload: `CHAN` when the analyzer decrypted it, `GRP_TXT` when it could not. */
data class GroupPayload(
    val type: String, val channel: String? = null, val channelHash: Int? = null,
    val mac: String? = null, val encryptedData: String? = null,
    val sender: String? = null, val text: String? = null, val pathLength: Int? = null,
)

interface BrowseRepository {
    suspend fun channels(selection: AnalyzerSelection): List<MeshChannel>
    suspend fun channelMessages(host: String, hash: String): List<ChannelMessage>
    suspend fun channelPackets(selection: AnalyzerSelection): List<ChannelPacket>
    suspend fun observers(host: String): List<MeshObserver>
    suspend fun observerAnalytics(host: String, id: String): ObserverAnalytics
    suspend fun nodeHealth(host: String, publicKey: String): NodeHealth
    suspend fun nodePaths(host: String, publicKey: String): NodePaths
    suspend fun nodeReach(host: String, publicKey: String): NodeReach
    suspend fun nodeAnalytics(host: String, publicKey: String, days: Int): NodeAnalytics
    suspend fun packetDetail(host: String, hash: String): PacketDetail
    /** Every node on the analyzer (no region), for Explore's search and dashboard. */
    suspend fun allNodes(host: String): List<MeshNode>
    /** The analyzer's latest packets (no region), for Explore's search and dashboard. */
    suspend fun recentPackets(host: String, limit: Int = 1_000): List<LivePacket>
    /** Runs [block] against saved responses only (null when not saved or older than [maxAgeMillis]). */
    suspend fun <T> saved(maxAgeMillis: Long, block: suspend BrowseRepository.() -> T): Cached<T>? = null
}

fun channelsUrl(selection: AnalyzerSelection): HttpUrl = endpoint(selection.host, "api", "channels").newBuilder()
    .apply { selection.region?.let { addQueryParameter("region", it) } }.build()

/** Channel identifiers such as `#test` are encoded as one path segment. */
fun channelMessagesUrl(host: String, hash: String): HttpUrl = endpoint(host, "api", "channels", hash, "messages")

/** CoreScope filters payload type with `type`; it ignores the `payloadType` spelling. */
fun channelPacketsUrl(selection: AnalyzerSelection): HttpUrl = endpoint(selection.host, "api", "packets").newBuilder()
    .addQueryParameter("limit", "1000").addQueryParameter("type", "5")
    .apply { selection.region?.let { addQueryParameter("region", it) } }.build()

fun observerAnalyticsUrl(host: String, id: String): HttpUrl = endpoint(host, "api", "observers", id, "analytics")

/** `section` is `health`, `paths` or `reach`; each is independently optional on older analyzers. */
fun nodeSectionUrl(host: String, publicKey: String, section: String): HttpUrl = endpoint(host, "api", "nodes", publicKey, section)

fun packetDetailUrl(host: String, hash: String): HttpUrl = endpoint(host, "api", "packets", hash)

fun nodeAnalyticsUrl(host: String, publicKey: String, days: Int): HttpUrl =
    nodeSectionUrl(host, publicKey, "analytics").newBuilder().addQueryParameter("days", days.toString()).build()

/**
 * Channel reads are never saved to disk: as on iOS they change quickly and are only kept
 * briefly in memory. [ephemeral] is their source; in a restore it is the saved-only source,
 * so channels simply come back empty.
 */
class HttpBrowseRepository(private val client: BodySource, private val ephemeral: BodySource = client,
    private val cache: ResponseCache? = null) : BrowseRepository {
    constructor(client: OkHttpClient, cache: ResponseCache? = null) : this(client.saving(cache), client.saving(null), cache)

    override suspend fun <T> saved(maxAgeMillis: Long, block: suspend BrowseRepository.() -> T): Cached<T>? =
        cache?.restore(maxAgeMillis) { HttpBrowseRepository(it).block() }

    override suspend fun channels(selection: AnalyzerSelection) =
        protocolJson.decodeFromString<ChannelsResponse>(ephemeral.read(channelsUrl(selection))).channels

    override suspend fun channelMessages(host: String, hash: String) =
        protocolJson.decodeFromString<ChannelMessagesResponse>(ephemeral.read(channelMessagesUrl(host, hash))).messages

    override suspend fun channelPackets(selection: AnalyzerSelection) = parseChannelPackets(ephemeral.read(channelPacketsUrl(selection)))

    override suspend fun observers(host: String) =
        protocolJson.decodeFromString<ObserversResponse>(client.read(endpoint(host, "api", "observers"))).observers

    override suspend fun observerAnalytics(host: String, id: String) =
        protocolJson.decodeFromString<ObserverAnalytics>(client.read(observerAnalyticsUrl(host, id)))

    override suspend fun nodeHealth(host: String, publicKey: String) =
        protocolJson.decodeFromString<NodeHealth>(client.read(nodeSectionUrl(host, publicKey, "health")))
    override suspend fun nodePaths(host: String, publicKey: String) =
        protocolJson.decodeFromString<NodePaths>(client.read(nodeSectionUrl(host, publicKey, "paths")))
    override suspend fun nodeReach(host: String, publicKey: String) =
        protocolJson.decodeFromString<NodeReach>(client.read(nodeSectionUrl(host, publicKey, "reach")))
    override suspend fun allNodes(host: String) = protocolJson.decodeFromString<NodesResponse>(
        client.read(endpoint(host, "api", "nodes").newBuilder().addQueryParameter("limit", "5000").build())).nodes.distinctBy { it.publicKey }
    override suspend fun recentPackets(host: String, limit: Int): List<LivePacket> {
        val root = protocolJson.parseToJsonElement(client.read(endpoint(host, "api", "packets").newBuilder()
            .addQueryParameter("limit", limit.toString()).build())).jsonObject
        return (root["packets"] as? JsonArray).orEmpty().mapNotNull { runCatching { parseLivePacket(it.jsonObject, live = false) }.getOrNull() }
    }
    override suspend fun packetDetail(host: String, hash: String) =
        protocolJson.decodeFromString<PacketDetail>(client.read(packetDetailUrl(host, hash)))
    override suspend fun nodeAnalytics(host: String, publicKey: String, days: Int) =
        protocolJson.decodeFromString<NodeAnalytics>(client.read(nodeAnalyticsUrl(host, publicKey, days)))
}

/** Malformed or non-channel packets are skipped rather than failing the whole response. */
fun parseChannelPackets(body: String): List<ChannelPacket> {
    val root = protocolJson.parseToJsonElement(body).jsonObject
    return (root["packets"] as? JsonArray).orEmpty().mapNotNull { element ->
        runCatching {
            val packet = element.jsonObject
            val decoded = when (val value = packet["decoded_json"]) {
                is JsonObject -> value
                is JsonPrimitive -> value.contentOrNull?.let { protocolJson.parseToJsonElement(it) as? JsonObject }
                else -> null
            } ?: return@runCatching null
            val payload = parseGroupPayload(decoded) ?: return@runCatching null
            ChannelPacket(
                id = packet["id"]?.jsonPrimitive?.longOrNull ?: return@runCatching null,
                hash = packet.text("hash").orEmpty(),
                firstSeen = packet.text("first_seen") ?: packet.text("timestamp"),
                observationCount = packet["observation_count"]?.jsonPrimitive?.intOrNull ?: 1,
                observerName = packet.text("observer_name"),
                snr = packet["snr"]?.jsonPrimitive?.doubleOrNull,
                payload = payload,
            )
        }.getOrNull()
    }
}

fun parseGroupPayload(decoded: JsonObject): GroupPayload? {
    val type = decoded.text("type")?.takeIf { it == "CHAN" || it == "GRP_TXT" } ?: return null
    val hash = (decoded["channelHash"] as? JsonPrimitive)?.let { value ->
        value.intOrNull ?: value.contentOrNull?.let { it.toIntOrNull() ?: it.toIntOrNull(16) }
    }
    return GroupPayload(type, decoded.text("channel"), hash, decoded.text("mac"), decoded.text("encryptedData"),
        decoded.text("sender"), decoded.text("text"), (decoded["path_len"] as? JsonPrimitive)?.intOrNull)
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
