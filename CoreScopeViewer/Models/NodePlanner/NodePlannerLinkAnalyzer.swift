import Foundation

struct NodePlannerLinkAnalysisRequest: Equatable, Sendable {
    let pointA: NodePlannerCoordinate
    let pointB: NodePlannerCoordinate
    let heightAMeters: Double
    let heightBMeters: Double
    let frequencyMHz: Double
    let refractionK: Double
    let linkBudget: NodePlannerLinkBudget?
    let sampleCount: Int?
}

struct NodePlannerLinkAnalysis: Equatable, Sendable {
    let request: NodePlannerLinkAnalysisRequest
    let terrain: NodePlannerTerrainProfile
    let result: NodePlannerPathResult
    let calculatedAt: Date
    let engineID: String
    let engineVersion: Int
}

extension NodePlannerLinkAnalysis {
    /// The analyzed path in stretches of one clearance, indexed into `terrain.samples`.
    var clearanceStretches: [NodePlannerClearanceStretch] {
        let samples = terrain.samples
        var distance = 0.0
        let profile = samples.indices.map { index in
            if index > 0 {
                distance += NodePlannerLinkAnalyzer.distance(from: samples[index - 1].coordinate, to: samples[index].coordinate)
            }
            return NodePlannerElevationPoint(distanceMeters: distance, elevationMeters: samples[index].elevationMeters)
        }
        return NodePlannerRFEngine.clearanceStretches(
            NodePlannerPathRequest(
                profile: profile,
                heightAMeters: request.heightAMeters,
                heightBMeters: request.heightBMeters,
                frequencyMHz: request.frequencyMHz,
                refractionK: request.refractionK,
                linkBudget: request.linkBudget
            )
        )
    }
}

enum NodePlannerLinkAnalysisError: Error, Equatable, Sendable {
    case identicalEndpoints
    case invalidSampleCount
    case terrainCountMismatch
    case terrainCoordinateMismatch
}

struct NodePlannerLinkAnalyzer: Sendable {
    static let minimumSampleCount = 2
    static let maximumSampleCount = 100

    private let elevationProvider: any NodePlannerElevationProviding
    private let now: @Sendable () -> Date

    init(
        elevationProvider: any NodePlannerElevationProviding,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.elevationProvider = elevationProvider
        self.now = now
    }

    func analyze(_ request: NodePlannerLinkAnalysisRequest) async throws -> NodePlannerLinkAnalysis {
        try Task.checkCancellation()

        let distanceMeters = Self.distance(from: request.pointA, to: request.pointB)
        guard distanceMeters > 0 else { throw NodePlannerLinkAnalysisError.identicalEndpoints }

        let sampleCount = request.sampleCount ?? Self.recommendedSampleCount(distanceMeters: distanceMeters)
        guard (Self.minimumSampleCount...Self.maximumSampleCount).contains(sampleCount) else {
            throw NodePlannerLinkAnalysisError.invalidSampleCount
        }

        let coordinates = NodePlannerPathSampler.coordinates(
            from: request.pointA,
            to: request.pointB,
            count: sampleCount
        )
        let terrain = try await elevationProvider.elevations(for: coordinates)
        try Task.checkCancellation()

        guard terrain.samples.count == coordinates.count else {
            throw NodePlannerLinkAnalysisError.terrainCountMismatch
        }

        var cumulativeDistance = 0.0
        var elevationProfile: [NodePlannerElevationPoint] = []
        elevationProfile.reserveCapacity(terrain.samples.count)

        for index in terrain.samples.indices {
            let sample = terrain.samples[index]
            guard sample.coordinate == coordinates[index] else {
                throw NodePlannerLinkAnalysisError.terrainCoordinateMismatch
            }
            if index > 0 {
                cumulativeDistance += Self.distance(
                    from: terrain.samples[index - 1].coordinate,
                    to: sample.coordinate
                )
            }
            elevationProfile.append(
                NodePlannerElevationPoint(
                    distanceMeters: cumulativeDistance,
                    elevationMeters: sample.elevationMeters
                )
            )
        }

        let result = try NodePlannerRFEngine.analyze(
            NodePlannerPathRequest(
                profile: elevationProfile,
                heightAMeters: request.heightAMeters,
                heightBMeters: request.heightBMeters,
                frequencyMHz: request.frequencyMHz,
                refractionK: request.refractionK,
                linkBudget: request.linkBudget
            )
        )

        return NodePlannerLinkAnalysis(
            request: request,
            terrain: terrain,
            result: result,
            calculatedAt: now(),
            engineID: NodePlannerPathResult.engineID,
            engineVersion: NodePlannerPathResult.engineVersion
        )
    }

    static func recommendedSampleCount(distanceMeters: Double) -> Int {
        switch distanceMeters {
        case ..<1_000:
            20
        case ..<5_000:
            50
        case ..<20_000:
            80
        default:
            100
        }
    }

    static func distance(from start: NodePlannerCoordinate, to end: NodePlannerCoordinate) -> Double {
        let latitudeA = start.latitude * .pi / 180
        let latitudeB = end.latitude * .pi / 180
        let latitudeDelta = (end.latitude - start.latitude) * .pi / 180
        let longitudeDelta = (end.longitude - start.longitude) * .pi / 180
        let haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2)
            + cos(latitudeA) * cos(latitudeB)
            * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        let angularDistance = 2 * atan2(sqrt(haversine), sqrt(max(0, 1 - haversine)))
        return NodePlannerRFEngine.earthRadiusMeters * angularDistance
    }

    static func initialBearing(from start: NodePlannerCoordinate, to end: NodePlannerCoordinate) -> Double {
        let latitudeA = start.latitude * .pi / 180
        let latitudeB = end.latitude * .pi / 180
        let longitudeDelta = (end.longitude - start.longitude) * .pi / 180
        let y = sin(longitudeDelta) * cos(latitudeB)
        let x = cos(latitudeA) * sin(latitudeB)
            - sin(latitudeA) * cos(latitudeB) * cos(longitudeDelta)
        let degrees = atan2(y, x) * 180 / .pi
        return degrees.truncatingRemainder(dividingBy: 360) + (degrees < 0 ? 360 : 0)
    }
}
