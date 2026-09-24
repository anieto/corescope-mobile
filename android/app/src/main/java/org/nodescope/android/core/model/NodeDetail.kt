package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /api/nodes/{publicKey}/health */
@Serializable
data class NodeHealth(
    val node: MeshNode? = null,
    val observers: List<NodeObserverStat> = emptyList(),
    val stats: NodeHealthStats? = null,
)
@Serializable
data class NodeObserverStat(
    @SerialName("observer_id") val observerId: String,
    @SerialName("observer_name") val observerName: String? = null,
    val packetCount: Long = 0,
    val avgSnr: Double? = null,
    val avgRssi: Double? = null,
    val iata: String? = null,
)
@Serializable
data class NodeHealthStats(
    val totalTransmissions: Long? = null,
    val totalObservations: Long? = null,
    val totalPackets: Long? = null,
    val packetsToday: Long? = null,
    val avgSnr: Double? = null,
    val avgHops: Double? = null,
    val lastHeard: String? = null,
)

/** GET /api/nodes/{publicKey}/paths */
@Serializable
data class NodePaths(val paths: List<NodePath> = emptyList(), val totalPaths: Int = 0, val totalTransmissions: Int = 0)
@Serializable
data class NodePath(val hops: List<PathHop> = emptyList(), val count: Int = 0, val lastSeen: String? = null, val sampleHash: String = "")
@Serializable
data class PathHop(val prefix: String = "", val name: String? = null, val pubkey: String? = null, val lat: Double? = null, val lon: Double? = null) {
    val label: String get() = name?.takeIf(String::isNotBlank) ?: prefix
}

/** GET /api/nodes/{publicKey}/reach */
@Serializable
data class NodeReach(
    val window: ReachWindow? = null,
    val importance: ReachImportance? = null,
    @SerialName("direct_observers") val directObservers: List<ReachObserver> = emptyList(),
    val links: List<ReachLink> = emptyList(),
)
@Serializable data class ReachWindow(val days: Int, val since: String? = null)
@Serializable
data class ReachImportance(
    @SerialName("neighbor_degree") val neighborDegree: Int = 0,
    @SerialName("degree_rank") val degreeRank: Int? = null,
    @SerialName("nodes_with_edges") val nodesWithEdges: Int? = null,
    @SerialName("relay_observations") val relayObservations: Int? = null,
    @SerialName("bidirectional_links") val bidirectionalLinks: Int = 0,
    @SerialName("direct_observers") val directObservers: Int = 0,
)
@Serializable
data class ReachObserver(val pubkey: String, val name: String? = null, val count: Int = 0,
    @SerialName("avg_snr") val avgSnr: Double? = null, @SerialName("distance_km") val distanceKm: Double? = null)
@Serializable
data class ReachLink(
    val pubkey: String,
    val name: String? = null,
    val role: String? = null,
    @SerialName("we_hear") val weHear: Int = 0,
    @SerialName("they_hear") val theyHear: Int = 0,
    val bidir: Boolean = false,
    @SerialName("distance_km") val distanceKm: Double? = null,
) {
    val label: String get() = name?.takeIf(String::isNotBlank) ?: pubkey.take(12)
}

/** GET /api/nodes/{publicKey}/analytics?days=1|7|30|365. Missing sections decode as empty. */
@Serializable
data class NodeAnalytics(
    val node: MeshNode? = null,
    val timeRange: AnalyticsTimeRange? = null,
    val activityTimeline: List<ActivityPoint> = emptyList(),
    val snrTrend: List<SignalPoint> = emptyList(),
    val packetTypeBreakdown: List<PacketTypeCount> = emptyList(),
    val observerCoverage: List<ObserverCoverage> = emptyList(),
    val hopDistribution: List<HopCount> = emptyList(),
    val peerInteractions: List<PeerInteraction> = emptyList(),
    val uptimeHeatmap: List<UptimeCell> = emptyList(),
    val computedStats: ComputedStats? = null,
)
@Serializable data class AnalyticsTimeRange(val from: String, val to: String, val days: Int)
@Serializable data class ActivityPoint(val bucket: String, val count: Long)
@Serializable
data class SignalPoint(val timestamp: String, val snr: Double? = null, val rssi: Double? = null,
    @SerialName("observer_id") val observerId: String? = null, @SerialName("observer_name") val observerName: String? = null)
@Serializable data class PacketTypeCount(@SerialName("payload_type") val payloadType: Int, val count: Long)
@Serializable
data class ObserverCoverage(@SerialName("observer_id") val observerId: String, @SerialName("observer_name") val observerName: String? = null,
    val packetCount: Long = 0, val avgSnr: Double? = null, val avgRssi: Double? = null, val firstSeen: String? = null, val lastSeen: String? = null)
@Serializable data class HopCount(val hops: String, val count: Long)
@Serializable
data class PeerInteraction(@SerialName("peer_key") val peerKey: String, @SerialName("peer_name") val peerName: String? = null,
    val messageCount: Long = 0, val lastContact: String? = null)
/** `dayOfWeek` is 0 = Sunday; day and hour are UTC. */
@Serializable data class UptimeCell(val dayOfWeek: Int, val hour: Int, val count: Long)
@Serializable
data class ComputedStats(
    val availabilityPct: Double? = null,
    val longestSilenceMs: Double? = null,
    val longestSilenceStart: String? = null,
    val signalGrade: String? = null,
    val snrMean: Double? = null,
    val snrStdDev: Double? = null,
    val relayPct: Double? = null,
    val totalPackets: Long? = null,
    val uniqueObservers: Int? = null,
    val uniquePeers: Int? = null,
    val avgPacketsPerDay: Double? = null,
)
