package org.nodescope.android.feature.linkprofile

import kotlin.math.*

// Port of NodeScope's iOS NodePlannerRFEngine. Portions derived from MeshCore One
// by Avi0n, https://github.com/Avi0n/MeshCoreOne, commit
// 9fba8e45ca14c38f4ed20e5ede157944fa1c246d. Licensed under GNU GPL v3.0.
data class ElevationPoint(val distanceMeters: Double, val elevationMeters: Double)
data class LinkBudget(
    val transmitPowerDBm: Double, val gainADB: Double, val gainBDB: Double,
    val lossADB: Double, val lossBDB: Double, val receiverSensitivityDBm: Double,
)
data class PathRequest(
    val profile: List<ElevationPoint>, val heightAMeters: Double, val heightBMeters: Double,
    val frequencyMHz: Double, val refractionK: Double = 4.0 / 3.0, val linkBudget: LinkBudget? = null,
)
enum class ClearanceStatus { CLEAR, MARGINAL, PARTIAL_OBSTRUCTION, BLOCKED }
data class Obstruction(val distanceFromAMeters: Double, val obstructionHeightMeters: Double, val fresnelClearancePercent: Double)
data class PathResult(
    val distanceMeters: Double, val freeSpacePathLossDB: Double, val peakDiffractionLossDB: Double,
    val totalPathLossDB: Double, val worstClearancePercent: Double, val clearanceStatus: ClearanceStatus,
    val obstructions: List<Obstruction>, val frequencyMHz: Double, val refractionK: Double,
    val receivePowerDBm: Double?, val linkMarginDB: Double?,
)

/** Planning estimates using the same model and thresholds as iOS, not measured link quality. */
object LinkProfileEngine {
    const val ENGINE_ID = "meshcore-one-los-compatible"
    const val ENGINE_VERSION = 1
    const val EARTH_RADIUS_METERS = 6_371_000.0

    private fun positive(vararg values: Double) = values.all { it.isFinite() && it > 0 }
    fun wavelength(frequencyMHz: Double): Double =
        if (positive(frequencyMHz)) 299_792_458.0 / (frequencyMHz * 1_000_000) else 0.0

    fun fresnelRadius(frequencyMHz: Double, distanceA: Double, distanceB: Double): Double =
        if (positive(frequencyMHz, distanceA, distanceB))
            sqrt(wavelength(frequencyMHz) * distanceA * distanceB / (distanceA + distanceB)) else 0.0

    fun earthBulge(distanceA: Double, distanceB: Double, refractionK: Double): Double =
        if (positive(distanceA, distanceB, refractionK))
            distanceA * distanceB / (2 * refractionK * EARTH_RADIUS_METERS) else 0.0

    fun pathLoss(distanceMeters: Double, frequencyMHz: Double): Double =
        if (positive(distanceMeters, frequencyMHz)) 20 * log10(distanceMeters) + 20 * log10(frequencyMHz) - 27.55 else 0.0

    fun diffractionLoss(height: Double, distanceA: Double, distanceB: Double, frequencyMHz: Double): Double {
        if (!height.isFinite() || !positive(distanceA, distanceB, frequencyMHz)) return 0.0
        val parameter = height * sqrt(2 * (distanceA + distanceB) / (wavelength(frequencyMHz) * distanceA * distanceB))
        if (parameter <= -0.78) return 0.0
        val shifted = parameter - 0.1
        return 6.9 + 20 * log10(sqrt(shifted * shifted + 1) + shifted)
    }

    fun analyze(request: PathRequest): PathResult {
        require(request.profile.size >= 2) { "At least two terrain samples are required" }
        require(positive(request.frequencyMHz, request.refractionK)) { "Frequency and refraction factor must be positive and finite" }
        require(listOf(request.heightAMeters, request.heightBMeters).all { it.isFinite() && it >= 0 }) { "Antenna heights must be finite and nonnegative" }
        request.profile.forEachIndexed { index, point ->
            require(point.distanceMeters.isFinite() && point.elevationMeters.isFinite()) { "Terrain values must be finite" }
            require(index == 0 || point.distanceMeters > request.profile[index - 1].distanceMeters) { "Terrain distances must increase" }
        }
        request.linkBudget?.let {
            require(listOf(it.transmitPowerDBm, it.gainADB, it.gainBDB, it.lossADB, it.lossBDB, it.receiverSensitivityDBm).all(Double::isFinite))
            require(it.lossADB >= 0 && it.lossBDB >= 0) { "System losses must be nonnegative" }
        }
        val first = request.profile.first()
        val last = request.profile.last()
        val length = last.distanceMeters - first.distanceMeters
        val startHeight = first.elevationMeters + request.heightAMeters
        val endHeight = last.elevationMeters + request.heightBMeters
        var worst = Double.POSITIVE_INFINITY
        var peak = 0.0
        val obstructions = mutableListOf<Obstruction>()
        request.profile.forEach { point ->
            val a = point.distanceMeters - first.distanceMeters
            val b = length - a
            // Endpoint samples do not represent an intervening obstruction.
            if (a > 1 && b > 1) {
                val sight = startHeight + a / length * (endHeight - startHeight)
                val terrain = point.elevationMeters + earthBulge(a, b, request.refractionK)
                val radius = fresnelRadius(request.frequencyMHz, a, b)
                val clearance = sight - terrain
                val percent = if (radius > 0) clearance / radius * 100 else if (clearance > 0) 100.0 else 0.0
                worst = min(worst, percent)
                val height = terrain - sight
                if (height > -radius) peak = max(peak, diffractionLoss(height, a, b, request.frequencyMHz))
                if (percent < 60) obstructions += Obstruction(point.distanceMeters, height, percent)
            }
        }
        if (worst == Double.POSITIVE_INFINITY) worst = 100.0
        val freeSpace = pathLoss(length, request.frequencyMHz)
        val total = freeSpace + peak
        val receive = request.linkBudget?.let { it.transmitPowerDBm + it.gainADB + it.gainBDB - it.lossADB - it.lossBDB - total }
        val status = when {
            worst >= 80 -> ClearanceStatus.CLEAR
            worst >= 60 -> ClearanceStatus.MARGINAL
            worst >= 0 -> ClearanceStatus.PARTIAL_OBSTRUCTION
            else -> ClearanceStatus.BLOCKED
        }
        return PathResult(length, freeSpace, peak, total, worst, status, obstructions.toList(), request.frequencyMHz,
            request.refractionK, receive, receive?.let { it - requireNotNull(request.linkBudget).receiverSensitivityDBm })
    }
}
