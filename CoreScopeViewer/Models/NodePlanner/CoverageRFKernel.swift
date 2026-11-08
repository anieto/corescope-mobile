import Foundation
import NodeScopeRF

/// Calls the shared validated terrain-only kernel. The caller supplies the geodesic midpoint latitude.
enum CoverageRFKernel {
    static var sourceRevision: String { CoverageKernel.shared.sourceRevision }
    static var scenario: String { CoverageKernel.shared.scenario }

    static func predict(profile: CoverageTerrainProfile, inputs: CoverageStudyInputs,
                        pathCenterLatitude: Double) throws -> Double {
        guard (3...100_001).contains(profile.distancesMeters.count),
              profile.elevationsMeters.count == profile.distancesMeters.count else {
            throw CoverageRFConvergenceFailure.invalidInput
        }
        let count = Int32(profile.distancesMeters.count)
        let distances = KotlinDoubleArray(size: count)
        let elevations = KotlinDoubleArray(size: count)
        for index in 0..<count {
            distances.set(index: index, value: profile.distancesMeters[Int(index)])
            elevations.set(index: index, value: profile.elevationsMeters[Int(index)])
        }
        let result = CoverageKernel.shared.predict(distancesMeters: distances, elevationsMeters: elevations,
            frequencyMHz: inputs.frequencyMHz, transmitHeightMeters: inputs.transmitHeightMeters,
            receiveHeightMeters: inputs.receiveHeightMeters, pathCenterLatitude: pathCenterLatitude)
        if result.state == .invalidInput { throw CoverageRFConvergenceFailure.invalidInput }
        guard result.state == .predicted, let loss = result.lossDb?.doubleValue, loss.isFinite else {
            throw CoverageRFConvergenceFailure.invalidModel
        }
        return loss
    }
}
