package org.nodescope.android.feature.coverage

import kotlin.math.abs

enum class CoverageRFConvergenceFailureState { INVALID_INPUT, INVALID_MODEL }
class CoverageRFConvergenceFailure(val state: CoverageRFConvergenceFailureState) : Exception(state.name)
enum class CoverageRFConvergenceState { QUALIFIED, ACCURACY_UNRESOLVED, THRESHOLD_UNRESOLVED }
data class CoverageRFConvergenceResult(
    val state: CoverageRFConvergenceState, val lossDb: Double?, val marginDb: Double?,
    val passes: Int, val spacingMeters: Double, val terrainEvaluations: Int, val geometryEvaluations: Int,
)

/** Study convergence policy, not a propagation model. Caller fixes radio/model inputs for all passes. */
object CoverageRFConvergence {
    fun evaluate(distanceMeters: Double, maximumLossDb: Double, sampleLimit: Int = 250_000,
                 geometryLimit: Int = 200_000, cancelled: () -> Boolean = { false },
                 profile: (Double, Int, Int) -> CoverageTerrainSampledPath,
                 predictLoss: (CoverageTerrainProfile) -> Double): CoverageRFConvergenceResult {
        fun fail(state: CoverageRFConvergenceFailureState): Nothing = throw CoverageRFConvergenceFailure(state)
        if (!distanceMeters.isFinite() || distanceMeters !in 250.0..100_000.0 || !maximumLossDb.isFinite() ||
            sampleLimit !in 1..250_000 || geometryLimit !in 1..200_000) fail(CoverageRFConvergenceFailureState.INVALID_INPUT)
        fun check() { if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED) }
        var samples = 0; var geometry = 0; var passes = 0; var stable = 0
        var previous: Double? = null
        var lastMargin = 0.0
        for (spacing in listOf(30.0, 10.0, 5.0, 2.0, 1.0)) {
            check()
            if (samples >= sampleLimit || geometry >= geometryLimit) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.WORK_LIMIT)
            val sampled = profile(spacing, sampleLimit - samples, geometryLimit - geometry)
            check()
            val input = sampled.profile
            if (input.distancesMeters.size < 2 || input.distancesMeters.size != input.elevationsMeters.size ||
                input.terrainEvaluations < input.distancesMeters.size || input.terrainEvaluations > sampleLimit - samples ||
                sampled.geometryEvaluations < 0 || sampled.geometryEvaluations > geometryLimit - geometry ||
                input.distancesMeters.first() != 0.0 || input.distancesMeters.last() != distanceMeters ||
                input.elevationsMeters.any { !it.isFinite() }) fail(CoverageRFConvergenceFailureState.INVALID_INPUT)
            var priorDistance = Double.NEGATIVE_INFINITY
            for (distance in input.distancesMeters) {
                if (!distance.isFinite() || distance <= priorDistance || distance !in 0.0..distanceMeters) {
                    fail(CoverageRFConvergenceFailureState.INVALID_INPUT)
                }
                priorDistance = distance
            }
            samples += input.terrainEvaluations
            geometry += sampled.geometryEvaluations
            check()
            val loss = predictLoss(input)
            // Discard output if cancellation arrived during a synchronous, non-interruptible RF call.
            check()
            val margin = maximumLossDb - loss
            if (!loss.isFinite() || !margin.isFinite()) fail(CoverageRFConvergenceFailureState.INVALID_MODEL)
            passes++
            previous?.let { stable = if (abs(loss - it) <= 0.1) stable + 1 else 0 }
            previous = loss
            lastMargin = margin
            if (spacing <= 5 && stable >= 2 && abs(margin) > 0.25) {
                return CoverageRFConvergenceResult(CoverageRFConvergenceState.QUALIFIED, loss, margin,
                    passes, spacing, samples, geometry)
            }
        }
        return CoverageRFConvergenceResult(
            if (abs(lastMargin) <= 0.25) CoverageRFConvergenceState.THRESHOLD_UNRESOLVED else CoverageRFConvergenceState.ACCURACY_UNRESOLVED,
            null, null, passes, 1.0, samples, geometry)
    }
}
