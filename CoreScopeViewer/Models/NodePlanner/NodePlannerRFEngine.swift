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

        let first = request.profile[0]
        let last = request.profile[request.profile.count - 1]
        let distanceOrigin = first.distanceMeters
        let lengthMeters = last.distanceMeters - distanceOrigin
        let antennaStartHeight = first.elevationMeters + request.heightAMeters
        let antennaEndHeight = last.elevationMeters + request.heightBMeters
        let freeSpacePathLoss = pathLoss(distanceMeters: lengthMeters, frequencyMHz: request.frequencyMHz)

        var worstClearancePercent = Double.infinity
        var peakDiffractionLoss = 0.0
        var obstructions: [NodePlannerObstruction] = []

        for point in request.profile {
            let distanceFromStart = point.distanceMeters - distanceOrigin
            let distanceToEnd = lengthMeters - distanceFromStart

            guard distanceFromStart > endpointSkipMarginMeters,
                  distanceToEnd > endpointSkipMarginMeters else { continue }

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
            let clearancePercent = radius > 0 ? clearance / radius * 100 : (clearance > 0 ? 100 : 0)

            worstClearancePercent = min(worstClearancePercent, clearancePercent)

            let obstructionHeight = effectiveTerrainHeight - lineOfSightHeight
            if obstructionHeight > -radius {
                peakDiffractionLoss = max(
                    peakDiffractionLoss,
                    diffractionLoss(
                        obstructionHeightMeters: obstructionHeight,
                        distanceToAMeters: distanceFromStart,
                        distanceToBMeters: distanceToEnd,
                        frequencyMHz: request.frequencyMHz
                    )
                )
            }

            if clearancePercent < marginalClearanceThreshold {
                obstructions.append(
                    NodePlannerObstruction(
                        distanceFromAMeters: point.distanceMeters,
                        obstructionHeightMeters: obstructionHeight,
                        fresnelClearancePercent: clearancePercent
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
