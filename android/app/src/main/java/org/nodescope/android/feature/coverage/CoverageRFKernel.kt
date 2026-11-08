package org.nodescope.android.feature.coverage

import org.nodescope.rf.CoverageKernel
import org.nodescope.rf.CoverageKernelState

/** The caller supplies the geodesic midpoint latitude and checks cancellation around the call. */
object CoverageRFKernel {
    val sourceRevision: String get() = CoverageKernel.sourceRevision
    val scenario: String get() = CoverageKernel.scenario

    fun predict(profile: CoverageTerrainProfile, inputs: CoverageStudyInputs, pathCenterLatitude: Double): Double {
        if (profile.distancesMeters.size !in 3..100_001 || profile.elevationsMeters.size != profile.distancesMeters.size)
            throw CoverageRFConvergenceFailure(CoverageRFConvergenceFailureState.INVALID_INPUT)
        val result = CoverageKernel.predict(profile.distancesMeters.toDoubleArray(), profile.elevationsMeters.toDoubleArray(),
            inputs.frequencyMHz, inputs.transmitHeightMeters, inputs.receiveHeightMeters, pathCenterLatitude)
        if (result.state == CoverageKernelState.INVALID_INPUT)
            throw CoverageRFConvergenceFailure(CoverageRFConvergenceFailureState.INVALID_INPUT)
        return result.lossDb?.takeIf { result.state == CoverageKernelState.PREDICTED && it.isFinite() }
            ?: throw CoverageRFConvergenceFailure(CoverageRFConvergenceFailureState.INVALID_MODEL)
    }
}
