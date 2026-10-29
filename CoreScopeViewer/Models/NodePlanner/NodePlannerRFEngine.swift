import Foundation

// Portions of this RF model are derived from MeshCore One by Avi0n:
// https://github.com/Avi0n/MeshCoreOne
// Upstream commit: 9fba8e45ca14c38f4ed20e5ede157944fa1c246d
// Licensed under GNU GPL v3.0. Modified for NodeScope with validated inputs,
// explicit provenance, link-budget output, and cross-platform fixture parity.

enum NodePlannerClearanceStatus: String, Codable, Sendable {
    case clear
    case marginal
    case partialObstruction
    case blocked
}

/// One sample's clearance of the first Fresnel zone, in `analyze`'s bands. Ordered best to worst,
/// so the worse of two is their maximum.
enum NodePlannerPathClearance: Int, Comparable, CaseIterable, Sendable {
    case clear
    case marginal
    case obstructed

    static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// A run of the path, from one profile sample to another, with one clearance throughout.
struct NodePlannerClearanceStretch: Equatable, Sendable {
    let startIndex: Int
    let endIndex: Int
    let clearance: NodePlannerPathClearance
}

struct NodePlannerElevationPoint: Codable, Equatable, Sendable {
    let distanceMeters: Double
    let elevationMeters: Double
}

struct NodePlannerLinkBudget: Codable, Equatable, Sendable {
    let transmitPowerDBm: Double
    let gainADB: Double
    let gainBDB: Double
    let lossADB: Double
    let lossBDB: Double
    let receiverSensitivityDBm: Double
}

struct NodePlannerPathRequest: Equatable, Sendable {
    let profile: [NodePlannerElevationPoint]
    let heightAMeters: Double
    let heightBMeters: Double
    let frequencyMHz: Double
    let refractionK: Double
    let linkBudget: NodePlannerLinkBudget?
}

struct NodePlannerObstruction: Codable, Equatable, Sendable {
    let distanceFromAMeters: Double
    let obstructionHeightMeters: Double
    let fresnelClearancePercent: Double
}

struct NodePlannerPathResult: Codable, Equatable, Sendable {
    static let engineID = "meshcore-one-los-compatible"
    static let engineVersion = 1

    let distanceMeters: Double
    let freeSpacePathLossDB: Double
    let peakDiffractionLossDB: Double
    let totalPathLossDB: Double
    let worstClearancePercent: Double
    let clearanceStatus: NodePlannerClearanceStatus
    let obstructions: [NodePlannerObstruction]
    let frequencyMHz: Double
    let refractionK: Double
    let receivePowerDBm: Double?
    let linkMarginDB: Double?
}

enum NodePlannerRFError: Error, Equatable, Sendable {
    case insufficientElevationPoints
    case nonFiniteValue
    case nonIncreasingDistance
    case invalidFrequency
    case invalidRefractionFactor
    case invalidAntennaHeight
    case invalidSystemLoss
}

enum NodePlannerRFEngine {
    static let speedOfLightMetersPerSecond = 299_792_458.0
    static let earthRadiusMeters = 6_371_000.0
    static let clearClearanceThreshold = 80.0
    static let marginalClearanceThreshold = 60.0

    private static let diffractionClearThreshold = -0.78
    private static let endpointSkipMarginMeters = 1.0

    static func wavelength(frequencyMHz: Double) -> Double {
        guard frequencyMHz > 0, frequencyMHz.isFinite else { return 0 }
        return speedOfLightMetersPerSecond / (frequencyMHz * 1_000_000)
    }

    static func fresnelRadius(
        frequencyMHz: Double,
        distanceToAMeters: Double,
        distanceToBMeters: Double
    ) -> Double {
        guard frequencyMHz > 0,
              distanceToAMeters > 0,
              distanceToBMeters > 0,
              frequencyMHz.isFinite,
              distanceToAMeters.isFinite,
              distanceToBMeters.isFinite else { return 0 }

        let totalDistance = distanceToAMeters + distanceToBMeters
        return sqrt(wavelength(frequencyMHz: frequencyMHz) * distanceToAMeters * distanceToBMeters / totalDistance)
    }

    static func earthBulge(
        distanceToAMeters: Double,
        distanceToBMeters: Double,
        refractionK: Double
    ) -> Double {
        guard distanceToAMeters > 0,
              distanceToBMeters > 0,
              refractionK > 0,
              distanceToAMeters.isFinite,
              distanceToBMeters.isFinite,
              refractionK.isFinite else { return 0 }

        return distanceToAMeters * distanceToBMeters / (2 * refractionK * earthRadiusMeters)
    }

    static func pathLoss(distanceMeters: Double, frequencyMHz: Double) -> Double {
        guard distanceMeters > 0,
              frequencyMHz > 0,
              distanceMeters.isFinite,
              frequencyMHz.isFinite else { return 0 }

        return 20 * log10(distanceMeters) + 20 * log10(frequencyMHz) - 27.55
    }

    static func diffractionLoss(
        obstructionHeightMeters: Double,
        distanceToAMeters: Double,
        distanceToBMeters: Double,
        frequencyMHz: Double
    ) -> Double {
        guard obstructionHeightMeters.isFinite,
              distanceToAMeters > 0,
              distanceToBMeters > 0,
              frequencyMHz > 0,
              distanceToAMeters.isFinite,
              distanceToBMeters.isFinite,
              frequencyMHz.isFinite else { return 0 }

        let totalDistance = distanceToAMeters + distanceToBMeters
        let parameter = obstructionHeightMeters * sqrt(
            2 * totalDistance / (wavelength(frequencyMHz: frequencyMHz) * distanceToAMeters * distanceToBMeters)
        )
        guard parameter > diffractionClearThreshold else { return 0 }

        let shifted = parameter - 0.1
        return 6.9 + 20 * log10(sqrt(shifted * shifted + 1) + shifted)
    }

    static func analyze(_ request: NodePlannerPathRequest) throws -> NodePlannerPathResult {
        try validate(request)

        let lengthMeters = request.profile[request.profile.count - 1].distanceMeters - request.profile[0].distanceMeters
        let freeSpacePathLoss = pathLoss(distanceMeters: lengthMeters, frequencyMHz: request.frequencyMHz)

        var worstClearancePercent = Double.infinity
        var peakDiffractionLoss = 0.0
        var obstructions: [NodePlannerObstruction] = []

        for sample in sampleClearances(request).compactMap({ $0 }) {
            worstClearancePercent = min(worstClearancePercent, sample.clearancePercent)

            if sample.obstructionHeight > -sample.radius {
                peakDiffractionLoss = max(
                    peakDiffractionLoss,
                    diffractionLoss(
                        obstructionHeightMeters: sample.obstructionHeight,
                        distanceToAMeters: sample.distanceFromStart,
                        distanceToBMeters: sample.distanceToEnd,
                        frequencyMHz: request.frequencyMHz
                    )
                )
            }

            if sample.clearancePercent < marginalClearanceThreshold {
                obstructions.append(
                    NodePlannerObstruction(
                        distanceFromAMeters: sample.point.distanceMeters,
                        obstructionHeightMeters: sample.obstructionHeight,
                        fresnelClearancePercent: sample.clearancePercent
                    )
                )
            }
        }

        if worstClearancePercent == .infinity {
            worstClearancePercent = 100
        }

        let totalPathLoss = freeSpacePathLoss + peakDiffractionLoss
        let receivePower = request.linkBudget.map {
            $0.transmitPowerDBm + $0.gainADB + $0.gainBDB - $0.lossADB - $0.lossBDB - totalPathLoss
        }
        let linkMargin: Double? = if let receivePower,
                                     let sensitivity = request.linkBudget?.receiverSensitivityDBm {
            receivePower - sensitivity
        } else {
            nil
        }

        return NodePlannerPathResult(
            distanceMeters: lengthMeters,
            freeSpacePathLossDB: freeSpacePathLoss,
            peakDiffractionLossDB: peakDiffractionLoss,
            totalPathLossDB: totalPathLoss,
            worstClearancePercent: worstClearancePercent,
            clearanceStatus: clearanceStatus(for: worstClearancePercent),
            obstructions: obstructions,
            frequencyMHz: request.frequencyMHz,
            refractionK: request.refractionK,
            receivePowerDBm: receivePower,
            linkMarginDB: linkMargin
        )
    }

    static func clearance(forPercent percent: Double) -> NodePlannerPathClearance {
        if percent >= clearClearanceThreshold {
            .clear
        } else if percent >= marginalClearanceThreshold {
            .marginal
        } else {
            .obstructed
        }
    }

    /// The path split into stretches of one clearance, for colouring the map line and the chart's
    /// line of sight. Each gap between samples takes the worse of its two ends; the endpoints carry
    /// no clearance of their own, as in `analyze`.
    static func clearanceStretches(_ request: NodePlannerPathRequest) -> [NodePlannerClearanceStretch] {
        guard request.profile.count >= 2 else { return [] }
        let bands = sampleClearances(request).map { $0.map { clearance(forPercent: $0.clearancePercent) } }
        var stretches: [NodePlannerClearanceStretch] = []
        for index in 0..<(request.profile.count - 1) {
            let band = [bands[index], bands[index + 1]].compactMap { $0 }.max() ?? .clear
            if let previous = stretches.last, previous.clearance == band {
                stretches[stretches.count - 1] = NodePlannerClearanceStretch(
                    startIndex: previous.startIndex,
                    endIndex: index + 1,
                    clearance: band
                )
            } else {
                stretches.append(NodePlannerClearanceStretch(startIndex: index, endIndex: index + 1, clearance: band))
            }
        }
        return stretches
    }

    private struct SampleClearance {
        let point: NodePlannerElevationPoint
        let distanceFromStart: Double
        let distanceToEnd: Double
        let obstructionHeight: Double
        let radius: Double
        let clearancePercent: Double
    }

    /// Each terrain sample against the line of sight; nil at the endpoints, which are not obstructions.
    private static func sampleClearances(_ request: NodePlannerPathRequest) -> [SampleClearance?] {
        let first = request.profile[0]
        let last = request.profile[request.profile.count - 1]
        let distanceOrigin = first.distanceMeters
        let lengthMeters = last.distanceMeters - distanceOrigin
        let antennaStartHeight = first.elevationMeters + request.heightAMeters
        let antennaEndHeight = last.elevationMeters + request.heightBMeters

        return request.profile.map { point in
            let distanceFromStart = point.distanceMeters - distanceOrigin
            let distanceToEnd = lengthMeters - distanceFromStart

            guard distanceFromStart > endpointSkipMarginMeters,
                  distanceToEnd > endpointSkipMarginMeters else { return nil }

            let fraction = distanceFromStart / lengthMeters
            let lineOfSightHeight = antennaStartHeight
                + fraction * (antennaEndHeight - antennaStartHeight)
            let effectiveTerrainHeight = point.elevationMeters + earthBulge(
                distanceToAMeters: distanceFromStart,
                distanceToBMeters: distanceToEnd,
                refractionK: request.refractionK
            )
            let radius = fresnelRadius(
                frequencyMHz: request.frequencyMHz,
                distanceToAMeters: distanceFromStart,
                distanceToBMeters: distanceToEnd
            )
            let clearance = lineOfSightHeight - effectiveTerrainHeight
            return SampleClearance(
                point: point,
                distanceFromStart: distanceFromStart,
                distanceToEnd: distanceToEnd,
                obstructionHeight: effectiveTerrainHeight - lineOfSightHeight,
                radius: radius,
                clearancePercent: radius > 0 ? clearance / radius * 100 : (clearance > 0 ? 100 : 0)
            )
        }
    }

    private static func validate(_ request: NodePlannerPathRequest) throws {
        guard request.profile.count >= 2 else { throw NodePlannerRFError.insufficientElevationPoints }
        guard request.frequencyMHz.isFinite else { throw NodePlannerRFError.nonFiniteValue }
        guard request.frequencyMHz > 0 else { throw NodePlannerRFError.invalidFrequency }
        guard request.refractionK.isFinite else { throw NodePlannerRFError.nonFiniteValue }
        guard request.refractionK > 0 else { throw NodePlannerRFError.invalidRefractionFactor }
        guard request.heightAMeters.isFinite, request.heightBMeters.isFinite else {
            throw NodePlannerRFError.nonFiniteValue
        }
        guard request.heightAMeters >= 0, request.heightBMeters >= 0 else {
            throw NodePlannerRFError.invalidAntennaHeight
        }

        for (index, point) in request.profile.enumerated() {
            guard point.distanceMeters.isFinite, point.elevationMeters.isFinite else {
                throw NodePlannerRFError.nonFiniteValue
            }
            if index > 0, point.distanceMeters <= request.profile[index - 1].distanceMeters {
                throw NodePlannerRFError.nonIncreasingDistance
            }
        }

        if let budget = request.linkBudget {
            let values = [
                budget.transmitPowerDBm,
                budget.gainADB,
                budget.gainBDB,
                budget.lossADB,
                budget.lossBDB,
                budget.receiverSensitivityDBm
            ]
            guard values.allSatisfy(\.isFinite) else { throw NodePlannerRFError.nonFiniteValue }
            guard budget.lossADB >= 0, budget.lossBDB >= 0 else {
                throw NodePlannerRFError.invalidSystemLoss
            }
        }
    }

    private static func clearanceStatus(for percent: Double) -> NodePlannerClearanceStatus {
        if percent >= clearClearanceThreshold {
            .clear
        } else if percent >= marginalClearanceThreshold {
            .marginal
        } else if percent >= 0 {
            .partialObstruction
        } else {
            .blocked
        }
    }
}
