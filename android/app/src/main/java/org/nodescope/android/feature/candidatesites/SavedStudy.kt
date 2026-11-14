package org.nodescope.android.feature.candidatesites

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.nodescope.android.feature.coverage.CoverageGrid
import java.util.Base64

/** Saved inputs with an optional versioned coverage calculation snapshot. Mirrors iOS `SavedStudy`. */
@Serializable
data class SavedStudy(
    val id: String, val name: String, val createdAtMilliseconds: Long, val updatedAtMilliseconds: Long,
    val kind: Kind, val linkProfile: SavedLinkProfile? = null, val coverage: SavedCoverage? = null,
) {
    @Serializable
    enum class Kind { @SerialName("linkProfile") LINK_PROFILE, @SerialName("coverage") COVERAGE }

    fun validate() {
        planValid(validPlanID(id) && validPlanName(name) && createdAtMilliseconds >= 0 &&
            updatedAtMilliseconds >= createdAtMilliseconds && updatedAtMilliseconds <= LATEST_MILLISECONDS)
        when (kind) {
            Kind.LINK_PROFILE -> {
                planValid(linkProfile != null && coverage == null)
                requireNotNull(linkProfile).validate()
            }
            Kind.COVERAGE -> {
                planValid(coverage != null && linkProfile == null)
                requireNotNull(coverage).validate()
            }
        }
    }
}

@Serializable
data class SavedAntenna(val heightMeters: Double, val gainDbi: Double, val lossDb: Double) {
    fun validate() = planValid(heightMeters.isFinite() && heightMeters in 0.0..10_000.0 &&
        gainDbi.isFinite() && lossDb.isFinite() && lossDb >= 0)
}

@Serializable
data class SavedEndpoint(val name: String? = null, val latitude: Double, val longitude: Double, val antenna: SavedAntenna) {
    fun validate() {
        planValid(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0 &&
            (name == null || name.utf8Size() <= 1024))
        antenna.validate()
    }
}

@Serializable
data class SavedLinkProfile(
    val pointA: SavedEndpoint, val pointB: SavedEndpoint, val frequencyMHz: Double, val transmitPowerDbm: Double,
    val sensitivityDbm: Double, val refractionK: Double,
    /** Null means the tool chooses its normal adaptive sampling. */
    val sampleCount: Int? = null,
) {
    fun validate() {
        pointA.validate()
        pointB.validate()
        planValid(frequencyMHz.isFinite() && frequencyMHz > 0 && transmitPowerDbm.isFinite() &&
            sensitivityDbm.isFinite() && refractionK.isFinite() && refractionK > 0 &&
            (sampleCount == null || sampleCount in 2..100))
    }
}

@Serializable
data class SavedCoverageNode(
    val sourceID: String, val nodeID: String, val name: String? = null, val role: String,
    val latitude: Double, val longitude: Double, val lastSeenMilliseconds: Long? = null,
) {
    fun validate() = planValid(sourceID.isNotEmpty() && sourceID.utf8Size() <= 1024 &&
        nodeID.isNotEmpty() && nodeID.utf8Size() <= 1024 && role.utf8Size() <= 160 &&
        (name == null || name.utf8Size() <= 1024) &&
        latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0 &&
        (lastSeenMilliseconds == null || lastSeenMilliseconds in 0..LATEST_MILLISECONDS))
}

@Serializable
data class SavedCoverage(
    val transmitter: SavedEndpoint, val receiver: SavedAntenna, val frequencyMHz: Double, val transmitPowerDbm: Double,
    val sensitivityDbm: Double, val reserveDb: Double, val radiusMeters: Int, val spacingMeters: Int,
    val nodes: List<SavedCoverageNode>, val catalogComplete: Boolean,
    /** Base64 of the calculation snapshot, as iOS encodes its `Data`; at most 8 MiB decoded. */
    val calculatedResult: String? = null,
) {
    fun validate() {
        if (calculatedResult != null) {
            val bytes = try { Base64.getDecoder().decode(calculatedResult) } catch (_: IllegalArgumentException) {
                throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
            }
            if (bytes.size > MAXIMUM_RESULT_BYTES) throw DeploymentPlanException(DeploymentPlanFailure.TOO_LARGE)
        }
        transmitter.validate()
        receiver.validate()
        planValid(transmitter.latitude in -85.0..85.0 &&
            transmitter.antenna.heightMeters in 1.0..3000.0 && receiver.heightMeters in 1.0..3000.0 &&
            frequencyMHz.isFinite() && frequencyMHz in 30.0..6000.0 &&
            transmitPowerDbm.isFinite() && sensitivityDbm.isFinite() && reserveDb.isFinite() && reserveDb >= 0 &&
            radiusMeters in 1000..100_000 && spacingMeters in CoverageGrid.SUPPORTED_SPACINGS &&
            nodes.size <= 200 && nodes.map { it.nodeID }.toSet().size == nodes.size)
        val identities = mutableSetOf<Pair<String, String>>()
        for (node in nodes) {
            node.validate()
            planValid(identities.add(node.sourceID to node.nodeID))
        }
    }

    companion object {
        const val MAXIMUM_RESULT_BYTES = 8 * 1024 * 1024
    }
}

private const val LATEST_MILLISECONDS = 253_402_300_799_999L
private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
