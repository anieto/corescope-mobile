package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DEFAULT_HOST = "analyzer.meshtexas.org"

@Serializable
data class AnalyzerSource(
    val id: String,
    val name: String,
    val host: String,
    val subtitle: String,
    val isDefault: Boolean = false,
    /** Optional community viewport for "All regions", shared with iOS (`mapCenter` is [lat, lon]). */
    val mapCenter: List<Double>? = null,
    val mapRadiusKm: Double? = null,
) {
    val viewport: RegionCoordinate? get() = Coordinate.valid(mapCenter?.getOrNull(0), mapCenter?.getOrNull(1))
        ?.let { RegionCoordinate(it.latitude, it.longitude, mapRadiusKm ?: 250.0) }
}
@Serializable
data class SourceDocument(val version: Int, val sources: List<AnalyzerSource>)

@Serializable
data class MeshNode(
    @SerialName("public_key") val publicKey: String,
    val name: String? = null,
    val role: String,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("last_seen") val lastSeen: String,
    @SerialName("first_seen") val firstSeen: String? = null,
    @SerialName("advert_count") val advertCount: Int? = null,
) {
    val displayName: String get() = name?.takeIf(String::isNotBlank) ?: publicKey.take(12)
    val coordinate: Coordinate? get() = Coordinate.gps(lat, lon)
}
@Serializable
data class NodesResponse(val nodes: List<MeshNode>, val total: Int)

@Serializable
data class MapDefaults(val center: List<Double>, val zoom: Double) {
    val coordinate: Coordinate? get() = Coordinate.valid(center.getOrNull(0), center.getOrNull(1))
}

data class Coordinate(val latitude: Double, val longitude: Double) {
    companion object {
        /** Analyzer GPS uses the exact (0, 0) pair for an unset position. */
        fun gps(latitude: Double?, longitude: Double?): Coordinate? =
            valid(latitude, longitude)?.takeUnless { it.latitude == 0.0 && it.longitude == 0.0 }

        fun valid(latitude: Double?, longitude: Double?): Coordinate? =
            if (latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0) Coordinate(latitude, longitude) else null
    }
}

data class AnalyzerSelection(val host: String, val region: String?)
data class AnalyzerSnapshot(
    val nodes: List<MeshNode>,
    val total: Int,
    val regions: Map<String, String>,
    val mapDefaults: MapDefaults?,
    val configurationIncomplete: Boolean = false,
    val regionCoordinates: Map<String, RegionCoordinate> = emptyMap(),
)

@Serializable
data class RegionCoordinate(val lat: Double, val lon: Double, val radiusKm: Double? = null) {
    val coordinate: Coordinate? get() = Coordinate.valid(lat, lon)
}
@Serializable
data class RegionCoordinatesResponse(val coords: Map<String, RegionCoordinate> = emptyMap())
