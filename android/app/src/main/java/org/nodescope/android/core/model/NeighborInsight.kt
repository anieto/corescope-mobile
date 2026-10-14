package org.nodescope.android.core.model

// Port of iOS NeighborInsight.swift: one neighbor model for CoreScope reach links and Beacon neighbors.

enum class NeighborDirection { INCOMING, OUTGOING, BIDIRECTIONAL, UNKNOWN }

data class NeighborIdentity(val publicKey: String?, val displayPrefix: String?, val sourceId: String?) {
    /** Only a full 64-hex key opens a node; a prefix is display-only. */
    val linkablePublicKey: String? get() = publicKey?.takeIf(::isFullPublicKey)?.lowercase()

    private companion object {
        fun isFullPublicKey(value: String) =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}

/** Null is unknown, never zero. */
data class NeighborSignal(val snr: Double?, val rssi: Double?)

data class NeighborInsight(
    val identity: NeighborIdentity,
    val name: String?,
    val role: String?,
    /** Epoch milliseconds. */
    val lastHeard: Long?,
    val observationCount: Int?,
    val signal: NeighborSignal,
    val direction: NeighborDirection,
    val latitude: Double?,
    val longitude: Double?,
) {
    val id: String get() = identity.linkablePublicKey ?: identity.sourceId ?: identity.displayPrefix ?: name ?: "unknown"

    val hasValidCoordinate: Boolean get() {
        val lat = latitude ?: return false
        val lon = longitude ?: return false
        return lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)
    }
}

data class NeighborCollection(
    val neighbors: List<NeighborInsight>,
    val completeness: AnalyticsCompleteness,
    val explanation: String?,
)

/** CoreScope reach links as neutral neighbors, without inventing signal or last-heard values. */
fun NodeReach.neighborCollection() = NeighborCollection(
    neighbors = links.map { link ->
        NeighborInsight(
            identity = NeighborIdentity(link.pubkey, link.pubkey.take(8).lowercase(), null),
            name = link.name, role = link.role, lastHeard = null, observationCount = null,
            signal = NeighborSignal(null, null),
            direction = if (link.bidir) NeighborDirection.BIDIRECTIONAL else NeighborDirection.UNKNOWN,
            latitude = link.lat, longitude = link.lon,
        )
    },
    completeness = AnalyticsCompleteness.COMPLETE,
    explanation = null,
)
