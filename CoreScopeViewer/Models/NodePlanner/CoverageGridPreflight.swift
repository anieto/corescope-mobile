import Foundation

enum CoverageGridPreflightFailure: Error, Equatable {
    case invalidBudget, pathSampleBudget, pathGeometryBudget, sampleBudget, geometryBudget, cancelled
}
struct CoverageGridWorkEstimate: Sendable {
    let eligiblePoints: Int
    let minimumSamples: Int64
    let minimumGeometry: Int64
    let maximumSamples: Int64
    let maximumGeometry: Int64
}

/// Necessary lower bounds only: acceptance does not guarantee convergence or completion.
enum CoverageGridPreflight {
    static func estimate(grid: CoverageGrid, sampleBudget: Int64, geometryBudget: Int64,
                         pathSampleBudget: Int = 250_000, pathGeometryBudget: Int = 200_000,
                         cancelled: () -> Bool = { false }) throws -> CoverageGridWorkEstimate {
        guard (0...150_000_000).contains(sampleBudget), (0...150_000_000).contains(geometryBudget),
              (1...250_000).contains(pathSampleBudget), (1...200_000).contains(pathGeometryBudget) else {
            throw CoverageGridPreflightFailure.invalidBudget
        }
        var eligible = 0
        var minimumSamples: Int64 = 0
        for index in 0..<grid.count {
            if cancelled() { throw CoverageGridPreflightFailure.cancelled }
            guard let point = grid.point(at: index), point.state == .eligible else { continue }
            // At least three passes are required; feature retention adds work beyond these uniform points.
            let minimum = [30.0, 10, 5].reduce(0) { $0 + Int(ceil(point.distanceMeters / $1)) + 1 }
            guard minimum <= pathSampleBudget else { throw CoverageGridPreflightFailure.pathSampleBudget }
            // Current native adapter rediscovers both axis endpoints on every refinement pass.
            guard pathGeometryBudget >= 12 else { throw CoverageGridPreflightFailure.pathGeometryBudget }
            eligible += 1
            minimumSamples += Int64(minimum)
        }
        if cancelled() { throw CoverageGridPreflightFailure.cancelled }
        let minimumGeometry = Int64(eligible) * 12
        guard minimumSamples <= sampleBudget else { throw CoverageGridPreflightFailure.sampleBudget }
        guard minimumGeometry <= geometryBudget else { throw CoverageGridPreflightFailure.geometryBudget }
        return CoverageGridWorkEstimate(eligiblePoints: eligible, minimumSamples: minimumSamples,
            minimumGeometry: minimumGeometry, maximumSamples: Int64(eligible) * Int64(pathSampleBudget),
            maximumGeometry: Int64(eligible) * Int64(pathGeometryBudget))
    }
}
