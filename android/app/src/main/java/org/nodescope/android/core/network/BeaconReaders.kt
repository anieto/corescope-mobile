package org.nodescope.android.core.network

import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.nodescope.android.core.model.*

// Ports of iOS BeaconNodeReader / BeaconObserverReader / BeaconPacketReader / BeaconRegionReader.

/**
 * Beacon cursors are opaque to screens and bound to the endpoint, source, source session and
 * filters that produced them (iOS encodes the same fields as sorted-key JSON in base64).
 */
internal object BeaconCursor {
    fun encode(endpoint: String, client: BeaconApiClient, region: String?, payloadType: Int?, value: JsonPrimitive): AnalyzerCursor {
        val saved = buildJsonObject {
            put("endpoint", endpoint)
            payloadType?.let { put("payloadType", it) }
            region?.let { put("region", it) }
            put("sessionID", client.sourceSession.id.toString())
            put("source", client.cacheIdentifier)
            put("value", value)
        }
        return AnalyzerCursor(Base64.getEncoder().encodeToString(saved.toString().toByteArray()))
    }

    /** The server's continuation value, or [AnalyzerPagingException.InvalidCursor] when it belongs elsewhere. */
    fun decode(cursor: AnalyzerCursor, endpoint: String, client: BeaconApiClient, region: String?, payloadType: Int?): String {
        val saved = runCatching {
            protocolJson.parseToJsonElement(String(Base64.getDecoder().decode(cursor.value))).jsonObject
        }.getOrNull() ?: throw AnalyzerPagingException.InvalidCursor()
        val matches = saved["endpoint"]?.jsonPrimitive?.contentOrNull == endpoint &&
            saved["source"]?.jsonPrimitive?.contentOrNull == client.cacheIdentifier &&
            saved["sessionID"]?.jsonPrimitive?.contentOrNull == client.sourceSession.id.toString() &&
            saved["region"]?.jsonPrimitive?.contentOrNull == region &&
            saved["payloadType"]?.jsonPrimitive?.intOrNull == payloadType
        val value = (saved["value"] as? JsonPrimitive)?.takeIf { matches && it !is JsonNull }
            ?: throw AnalyzerPagingException.InvalidCursor()
        return value.content
    }

    /** A page's `nextCursor`: an integer or a non-empty string. */
    fun value(element: JsonElement?): JsonPrimitive? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) return primitive.longOrNull?.let(::JsonPrimitive) ?: throw BeaconApiException.MalformedPage()
        if (primitive.content.isEmpty()) throw BeaconApiException.MalformedPage()
        return primitive
    }
}

sealed class AnalyzerPagingException(message: String) : IllegalStateException(message) {
    class InvalidCursor : AnalyzerPagingException("This page belongs to a different request. Refresh to continue.")
    class InvalidPageSize : AnalyzerPagingException("The page size must be greater than zero.")
}

@Serializable
private data class BeaconRawPage(val items: JsonArray = JsonArray(emptyList()), val hasMore: Boolean, val nextCursor: JsonElement? = null)

private fun pageQuery(pageSize: Int, region: String?): MutableList<Pair<String, String>> {
    if (pageSize <= 0) throw AnalyzerPagingException.InvalidPageSize()
    return mutableListOf("limit" to minOf(pageSize, 200).toString()).apply { region?.let { add("iatas" to it) } }
}

private fun <T> continuation(page: BeaconRawPage, items: List<T>, encode: (JsonPrimitive) -> AnalyzerCursor): AnalyzerCursor? {
    if (!page.hasMore) return null
    val value = BeaconCursor.value(page.nextCursor)
    if (items.isEmpty() || value == null) throw BeaconApiException.MalformedPage()
    return encode(value)
}

private fun <T> decodeItems(page: BeaconRawPage, serializer: kotlinx.serialization.KSerializer<T>): List<T> = try {
    protocolJson.decodeFromJsonElement(ListSerializer(serializer), page.items)
} catch (e: kotlinx.serialization.SerializationException) {
    throw BeaconApiException.InvalidResponse()
} catch (e: IllegalArgumentException) {
    throw BeaconApiException.InvalidResponse()
}

@Serializable
data class BeaconNodeRegion(val iata: String, val lastHeard: Long? = null)

@Serializable
data class BeaconNode(
    val id: String,
    val publicKey: String,
    val nodeType: Int,
    val name: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val iatas: List<BeaconNodeRegion>? = null,
    val firstSeen: Long? = null,
    val lastSeen: Long? = null,
    val supportsMultibytePaths: Boolean? = null,
    val supportsMultibyteTraces: Boolean? = null,
) {
    /** Summaries have no top-level last-seen: the newest region hearing stands in for it. */
    val lastHeard: Long? get() = iatas?.mapNotNull { it.lastHeard }?.maxOrNull()

    val role: String get() = beaconRole(nodeType)

    /** Android's single `last_seen` carries the newest known activity (iOS `latestActivity`). */
    fun meshNode(): MeshNode {
        val position = Coordinate.gps(lat, lng)
        return MeshNode(
            publicKey = publicKey.lowercase(), name = name, role = role,
            lat = position?.latitude, lon = position?.longitude,
            lastSeen = isoMillis(listOfNotNull(lastSeen, lastHeard).maxOrNull()).orEmpty(),
            firstSeen = isoMillis(firstSeen), advertCount = null,
        )
    }

    fun validate() {
        if (!isUuid(id) || !isFullBeaconKey(publicKey)) throw BeaconApiException.InvalidResponse()
    }
}

internal fun beaconRole(nodeType: Int) = when (nodeType) {
    1 -> "companion"; 2 -> "repeater"; 3 -> "room"; 4 -> "sensor"; else -> "unknown"
}

class BeaconNodeReader(val client: BeaconApiClient, verifiedServerInfo: BeaconServerInfo) {
    init { verifiedServerInfo.requireSupported() }

    suspend fun nodePage(region: String?, pageSize: Int = 200, cursor: AnalyzerCursor? = null): AnalyzerPage<BeaconNode> {
        client.sourceSession.check()
        val query = pageQuery(pageSize, region)
        cursor?.let { query += "cursor" to BeaconCursor.decode(it, "nodes", client, region, null) }
        val page = client.get(BeaconRawPage.serializer(), listOf("nodes"), query)
        val items = decodeItems(page, BeaconNode.serializer()).onEach { it.validate() }
        val next = continuation(page, items) { BeaconCursor.encode("nodes", client, region, null, it) }
        client.sourceSession.check()
        return AnalyzerPage(items, next)
    }

    /** No item limit: reaching the request budget must fail, not imply a complete map. */
    suspend fun nodes(region: String?, maximumPages: Int = 20): List<BeaconNode> =
        collectAnalyzerPages(Int.MAX_VALUE, minOf(maximumPages, 20), identity = { it.publicKey.lowercase() }) { cursor, _ ->
            nodePage(region, cursor = cursor)
        }

    suspend fun nodeDetail(publicKey: String): BeaconNode? {
        val summary = nodeSummary(publicKey) ?: return null
        val key = publicKey.lowercase()
        val detail = client.get(BeaconNode.serializer(), listOf("nodes", summary.id))
        detail.validate()
        if (detail.id != summary.id || detail.publicKey.lowercase() != key) throw BeaconApiException.InvalidResponse()
        client.sourceSession.check()
        return detail
    }

    /** One full-key lookup; null when the server doesn't know the node. */
    suspend fun nodeSummary(publicKey: String): BeaconNode? {
        client.sourceSession.check()
        if (!isFullBeaconKey(publicKey)) throw BeaconApiException.InvalidResponse()
        val key = publicKey.lowercase()
        val lookup = client.get(BeaconRawPage.serializer(), listOf("nodes"), listOf("pubkey" to key))
        val items = decodeItems(lookup, BeaconNode.serializer())
        if (lookup.hasMore || items.size > 1) throw BeaconApiException.MalformedPage()
        val summary = items.firstOrNull() ?: return null
        summary.validate()
        if (summary.publicKey.lowercase() != key) throw BeaconApiException.InvalidResponse()
        client.sourceSession.check()
        return summary
    }

    suspend fun neighbors(publicKey: String): NeighborCollection? {
        val summary = nodeSummary(publicKey) ?: return null
        val records = client.get(ListSerializer(BeaconNeighbor.serializer()), listOf("nodes", summary.id, "neighbors"))
        records.forEach { it.validate() }
        client.sourceSession.check()
        return NeighborCollection(records.map { it.insight() }, AnalyticsCompleteness.COMPLETE, null)
    }

    /**
     * Observations back to [since] (epoch ms), newest first, from at most [maximumPages] pages of 200
     * (capped at 19, so the screen stays within 20 requests with the key lookup). Cursors may not
     * repeat and duplicate observation ids are ignored. Beacon pages follow observation ids, not
     * heardAt, so delayed ingestion can invert timestamps; the worst measured on the live servers
     * (2026-10-14) was under a minute. A page wholly older than [since] by more than
     * [INGESTION_GRACE_MILLIS] ends the walk, so a 24-hour view doesn't download the whole history.
     */
    suspend fun observationHistory(publicKey: String, since: Long, maximumPages: Int = 19): BeaconObservationHistory? {
        if (maximumPages <= 0) throw AnalyzerPagingException.InvalidPageSize()
        val node = nodeSummary(publicKey) ?: return null
        val observations = mutableListOf<BeaconObservation>()
        var cursor: String? = null
        val seenCursors = HashSet<String>()
        val seenIds = HashSet<Long>()
        var complete = false
        var pages = 0
        while (pages < minOf(maximumPages, 19)) {
            val query = mutableListOf("limit" to "200").apply { cursor?.let { add("cursor" to it) } }
            val page = client.get(BeaconRawPage.serializer(), listOf("nodes", node.id, "observations"), query)
            val items = decodeItems(page, BeaconObservation.serializer()).onEach { it.validate() }
            for (observation in items) {
                if (!seenIds.add(observation.id)) continue
                if (observation.heardAt >= since) observations += observation
            }
            pages++
            if (!page.hasMore || items.maxOfOrNull { it.heardAt }?.let { it < since - INGESTION_GRACE_MILLIS } == true) {
                complete = true
                break
            }
            val next = BeaconCursor.value(page.nextCursor)?.content
            if (items.isEmpty() || next == null || !seenCursors.add(next)) throw BeaconApiException.MalformedPage()
            cursor = next
        }
        client.sourceSession.check()
        return BeaconObservationHistory(node, observations.sortedByDescending { it.heardAt }, complete, pages + 1)
    }
}

/** How far a later observation page's timestamps may exceed an earlier page's. */
const val INGESTION_GRACE_MILLIS = 60 * 60 * 1_000L

@Serializable
data class BeaconNeighbor(
    val id: String,
    val name: String? = null,
    val publicKey: String,
    val nodeType: Int? = null,
    val nodeTypeName: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val observationCount: Int? = null,
    val lastSeen: Long? = null,
    val snr: Double? = null,
) {
    /** Beacon reports no direction or RSSI for a neighbor: both stay unknown. */
    fun insight() = NeighborInsight(
        identity = NeighborIdentity(publicKey.lowercase(), publicKey.take(8).lowercase(), id.lowercase()),
        name = name, role = nodeTypeName, lastHeard = lastSeen, observationCount = observationCount,
        signal = NeighborSignal(snr, null), direction = NeighborDirection.UNKNOWN, latitude = lat, longitude = lng,
    )

    fun validate() {
        if (!isUuid(id) || !isFullBeaconKey(publicKey) || (observationCount ?: 0) < 0 ||
            snr?.isFinite() == false || lat?.isFinite() == false || lng?.isFinite() == false) {
            throw BeaconApiException.InvalidResponse()
        }
    }
}

/** One hearing of the node: there is no observer id, so per-observer analytics are unavailable. */
@Serializable
data class BeaconObservation(
    val id: Long,
    val packetHash: String,
    val payloadType: Int,
    val heardAt: Long,
    val rssi: Double? = null,
    val snr: Double? = null,
    val hopCount: Int? = null,
) {
    fun validate() {
        if (id < 0 || packetHash.isEmpty() || payloadType < 0 || rssi?.isFinite() == false ||
            snr?.isFinite() == false || (hopCount ?: 0) < 0) {
            throw BeaconApiException.InvalidResponse()
        }
    }
}

data class BeaconObservationHistory(
    val node: BeaconNode,
    val observations: List<BeaconObservation>,
    val isComplete: Boolean,
    val requestCount: Int,
)

@Serializable
data class BeaconObserver(
    val id: String,
    val displayName: String? = null,
    val observerType: String? = null,
    val iata: String? = null,
    val status: String? = null,
    val publicKey: String? = null,
    val radio: String? = null,
    val firstSeen: Long? = null,
    val lastSeen: Long? = null,
    val hardwareModel: String? = null,
    val firmwareVersion: String? = null,
    val softwareVersion: String? = null,
    val radioFreqMhz: Double? = null,
    val radioSf: Int? = null,
    val radioBwKhz: Double? = null,
    val radioCr: Int? = null,
    val batteryLevel: Double? = null,
    val uptimeSeconds: Long? = null,
) {
    /**
     * Beacon's observationCount includes non-packet events, so no packet total is shown, and
     * batteryLevel is a percentage, never converted to millivolts.
     */
    fun meshObserver() = MeshObserver(
        id = id.lowercase(), name = displayName, iata = iata,
        lastSeen = isoMillis(lastSeen), firstSeen = isoMillis(firstSeen), packetCount = null,
        model = hardwareModel, firmware = firmwareVersion, clientVersion = softwareVersion,
        radio = radio, batteryMv = null, uptimeSecs = uptimeSeconds, noiseFloor = null,
    )

    fun validate() {
        if (!isUuid(id)) throw BeaconApiException.InvalidResponse()
        if (publicKey != null && !isFullBeaconKey(publicKey)) throw BeaconApiException.InvalidResponse()
    }
}

/**
 * Beacon has no observer lookup by public key: the reader learns `uuid ↔ key` from observer
 * detail and live events, and a key it hasn't learned returns null, never a scan of every observer.
 */
class BeaconObserverReader(val client: BeaconApiClient, verifiedServerInfo: BeaconServerInfo) {
    private val mutex = Mutex()
    private val idsByKey = HashMap<String, String>()
    private val keysById = HashMap<String, String>()

    init { verifiedServerInfo.requireSupported() }

    suspend fun observerPage(region: String?, pageSize: Int = 200, cursor: AnalyzerCursor? = null): AnalyzerPage<BeaconObserver> {
        client.sourceSession.check()
        val query = pageQuery(pageSize, region)
        cursor?.let { query += "cursor" to BeaconCursor.decode(it, "observers", client, region, null) }
        val page = client.get(BeaconRawPage.serializer(), listOf("observers"), query)
        val items = decodeItems(page, BeaconObserver.serializer()).onEach { it.validate() }
        val next = continuation(page, items) { BeaconCursor.encode("observers", client, region, null, it) }
        client.sourceSession.check()
        return AnalyzerPage(items, next)
    }

    suspend fun observers(region: String?, maximumPages: Int = 20): List<BeaconObserver> =
        collectAnalyzerPages(Int.MAX_VALUE, minOf(maximumPages, 20), identity = { it.id.lowercase() }) { cursor, _ ->
            observerPage(region, cursor = cursor)
        }

    suspend fun observerDetail(id: String): BeaconObserver {
        client.sourceSession.check()
        if (!isUuid(id)) throw BeaconApiException.InvalidResponse()
        val identifier = id.lowercase()
        val detail = try {
            client.get(BeaconObserver.serializer(), listOf("observers", identifier))
        } catch (e: BeaconApiException.Server) {
            if (e.status == 404) forget(identifier)
            throw e
        }
        client.sourceSession.check()
        detail.validate()
        if (detail.id.lowercase() != identifier) {
            forget(identifier)
            throw BeaconApiException.InvalidResponse()
        }
        val key = detail.publicKey
        if (key != null) learn(identifier, key, client.sourceSession.id) else forget(identifier)
        return detail
    }

    /** Uses only a learned mapping; an unknown key is null, never a scan (iOS `observerDetail(publicKey:)`). */
    suspend fun observerDetailByKey(publicKey: String): BeaconObserver? {
        client.sourceSession.check()
        if (!isFullBeaconKey(publicKey)) throw BeaconApiException.InvalidResponse()
        val key = publicKey.lowercase()
        val identifier = mutex.withLock { idsByKey[key] } ?: return null
        return try {
            observerDetail(identifier).takeIf { it.publicKey?.lowercase() == key }
        } catch (e: BeaconApiException.Server) {
            if (e.status == 404) null else throw e
        }
    }

    /** Live-feed callers must supply the session that produced the event. */
    suspend fun learn(observerId: String, publicKey: String, sessionId: java.util.UUID) {
        client.sourceSession.check()
        if (sessionId != client.sourceSession.id) throw kotlinx.coroutines.CancellationException("The analyzer source changed.")
        if (!isUuid(observerId) || !isFullBeaconKey(publicKey)) throw BeaconApiException.InvalidResponse()
        val identifier = observerId.lowercase()
        val key = publicKey.lowercase()
        mutex.withLock {
            forgetLocked(identifier)
            idsByKey[key]?.let(::forgetLocked)
            idsByKey[key] = identifier
            keysById[identifier] = key
        }
    }

    suspend fun learnedKey(observerId: String): String? = mutex.withLock { keysById[observerId.lowercase()] }

    private suspend fun forget(id: String) = mutex.withLock { forgetLocked(id) }

    private fun forgetLocked(id: String) {
        keysById.remove(id)?.let { idsByKey.remove(it) }
    }
}

/** A Beacon resolved hop: confirmed only when one high-confidence node has a full key. */
@Serializable
data class BeaconResolvedHop(val confidence: String, val nodes: List<Node> = emptyList()) {
    @Serializable data class Node(val publicKey: String? = null)

    val confirmedKey: String? get() {
        val key = nodes.singleOrNull()?.publicKey
        return if (confidence == "high" && nodes.size == 1 && key != null && isFullBeaconKey(key)) key.lowercase() else null
    }
}

@Serializable
data class BeaconPacketSummary(
    val packetHash: String,
    val payloadType: Int,
    val routeType: Int,
    val firstHeardAt: Long,
    val lastHeardAt: Long,
    val observationCount: Int,
    val latestObserver: Observer? = null,
) {
    @Serializable data class Observer(val id: String, val displayName: String? = null, val iata: String? = null)
}

class BeaconPacketReader(val client: BeaconApiClient, verifiedServerInfo: BeaconServerInfo) {
    init { verifiedServerInfo.requireSupported() }


    @Serializable
    private data class Detail(
        val packetHash: String,
        val header: Header,
        val firstHeardAt: Long,
        val lastHeardAt: Long,
        val observationCount: Int,
        val observations: List<Observation>,
    ) {
        @Serializable data class Header(val routeType: Int, val payloadType: Int, val payloadVersion: Int? = null)
        @Serializable data class Observation(
            val id: Long,
            val observerName: String? = null,
            val iata: String? = null,
            val heardAt: Long,
            val rssi: Double? = null,
            val snr: Double? = null,
            val resolvedPath: List<BeaconResolvedHop>? = null,
        )
    }

    suspend fun packetPage(region: String?, pageSize: Int, payloadType: Int?, cursor: AnalyzerCursor?): AnalyzerPage<LivePacket> {
        val page = summaryPage(region, pageSize, payloadType, cursor)
        val now = client.currentTime()
        return AnalyzerPage(page.items.map { it.livePacket(now) }, page.nextCursor)
    }

    suspend fun summaryPage(region: String?, pageSize: Int, payloadType: Int?, cursor: AnalyzerCursor?): AnalyzerPage<BeaconPacketSummary> {
        client.sourceSession.check()
        val query = pageQuery(pageSize, region)
        payloadType?.let { query += "payloadType" to it.toString() }
        cursor?.let { query += "cursor" to BeaconCursor.decode(it, "packets", client, region, payloadType) }
        val page = client.get(BeaconRawPage.serializer(), listOf("packets"), query)
        val items = decodeItems(page, BeaconPacketSummary.serializer())
        if (!items.all { it.packetHash.isNotEmpty() && it.observationCount >= 0 }) throw BeaconApiException.InvalidResponse()
        val next = continuation(page, items) { BeaconCursor.encode("packets", client, region, payloadType, it) }
        client.sourceSession.check()
        return AnalyzerPage(items, next)
    }

    suspend fun packetDetail(hash: String): PacketDetail {
        val response = client.get(Detail.serializer(), listOf("packets", hash))
        if (response.packetHash != hash) throw BeaconApiException.InvalidResponse()
        client.sourceSession.check()
        // rawPayload is not a complete packet; never expose it as CoreScope raw_hex.
        return PacketDetail(
            packet = PacketRecord(
                id = null, hash = response.packetHash, payloadType = response.header.payloadType,
                payloadVersion = response.header.payloadVersion, timestamp = isoMillis(response.lastHeardAt),
            ),
            path = emptyList(), observationCount = response.observationCount,
            observations = response.observations.map { observation ->
                PacketObservation(
                    id = observation.id, observerName = observation.observerName, observerIata = observation.iata,
                    snr = observation.snr, rssi = observation.rssi, timestamp = isoMillis(observation.heardAt),
                    resolvedPath = observation.resolvedPath?.map { it.confirmedKey },
                )
            },
        )
    }

    private fun BeaconPacketSummary.livePacket(now: Long) = LivePacket(
        id = null, hash = packetHash, payloadType = payloadType, typeName = payloadTypeName(payloadType),
        observerId = latestObserver?.id?.lowercase(), observerName = latestObserver?.displayName,
        region = latestObserver?.iata, snr = null, rssi = null, hops = emptyList(), resolvedPath = emptyList(),
        timestamp = isoMillis(lastHeardAt), receivedAt = now, isLive = false, observations = observationCount,
    )
}

class BeaconRegionReader(
    private val client: BeaconApiClient,
    verifiedServerInfo: BeaconServerInfo,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Iata(val iata: String, val displayName: String? = null, val lat: Double? = null, val lon: Double? = null) {
        val coordinate: RegionCoordinate? get() = Coordinate.valid(lat, lon)?.let { RegionCoordinate(it.latitude, it.longitude) }
    }

    @Serializable
    data class Region(
        val id: Int, val slug: String, val name: String, val iatas: List<String>,
        val centerLat: Double? = null, val centerLng: Double? = null, val zoomLevel: Int? = null,
    )

    @Serializable
    private data class Summary(val id: Int, val slug: String, val name: String)

    data class Catalog(val iatas: List<Iata>, val regions: List<Region>) {
        val names: Map<String, String> get() = iatas.associate { it.iata.uppercase() to (it.displayName ?: it.iata) }
        val coordinates: Map<String, RegionCoordinate> get() =
            iatas.mapNotNull { entry -> entry.coordinate?.let { entry.iata.uppercase() to it } }.toMap()
    }

    private val mutex = Mutex()
    private var cached: Pair<Catalog, Long>? = null
    private var pending: CompletableDeferred<Catalog>? = null

    init { verifiedServerInfo.requireSupported() }

    suspend fun catalog(forceRefresh: Boolean = false): Catalog {
        client.sourceSession.check()
        val (deferred, owner) = mutex.withLock {
            val hit = cached
            if (!forceRefresh && hit != null && hit.second > now()) return hit.first
            pending?.let { it to false } ?: CompletableDeferred<Catalog>().also { pending = it }.let { it to true }
        }
        if (owner) {
            try {
                val result = fetch()
                client.sourceSession.check()
                mutex.withLock { cached = result to now() + 300_000 }
                deferred.complete(result)
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            } finally {
                mutex.withLock { if (pending === deferred) pending = null }
            }
        }
        val result = deferred.await()
        client.sourceSession.check()
        return result
    }

    private suspend fun fetch(): Catalog {
        val iatas = client.get(ListSerializer(Iata.serializer()), listOf("iatas"))
        val summaries = client.get(ListSerializer(Summary.serializer()), listOf("regions"))
        if (summaries.size > 18) throw IllegalStateException("The requested history exceeds the page budget.")
        val valid = iatas.map { it.iata.uppercase() }.toSet().size == iatas.size &&
            iatas.all { validCode(it.iata) } &&
            summaries.map { it.id }.toSet().size == summaries.size &&
            summaries.map { it.slug }.toSet().size == summaries.size &&
            summaries.all { it.id > 0 && it.slug.isNotEmpty() }
        if (!valid) throw BeaconApiException.InvalidResponse()
        val regions = summaries.map { summary ->
            val detail = client.get(Region.serializer(), listOf("regions", summary.id.toString()))
            if (detail.id != summary.id || detail.slug != summary.slug || !detail.iatas.all(::validCode)) {
                throw BeaconApiException.InvalidResponse()
            }
            detail
        }
        client.sourceSession.check()
        return Catalog(iatas, regions)
    }

    private fun validCode(value: String) = value.length == 3 && value.all { it in 'A'..'Z' || it in 'a'..'z' }
}
