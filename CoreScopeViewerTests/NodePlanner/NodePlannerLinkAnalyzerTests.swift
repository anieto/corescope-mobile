import Foundation
import Testing
@testable import CoreScopeViewer

struct NodePlannerLinkAnalyzerTests {
    @Test func choosesMeshCoreCompatibleSampleCounts() {
        #expect(NodePlannerLinkAnalyzer.recommendedSampleCount(distanceMeters: 999) == 20)
        #expect(NodePlannerLinkAnalyzer.recommendedSampleCount(distanceMeters: 1_000) == 50)
        #expect(NodePlannerLinkAnalyzer.recommendedSampleCount(distanceMeters: 4_999) == 50)
        #expect(NodePlannerLinkAnalyzer.recommendedSampleCount(distanceMeters: 5_000) == 80)
        #expect(NodePlannerLinkAnalyzer.recommendedSampleCount(distanceMeters: 19_999) == 80)
        #expect(NodePlannerLinkAnalyzer.recommendedSampleCount(distanceMeters: 20_000) == 100)
    }

    @Test func analyzesProviderTerrainAndPreservesProvenance() async throws {
        let pointA = try NodePlannerCoordinate(latitude: 30, longitude: -97)
        let pointB = try NodePlannerCoordinate(latitude: 30.05, longitude: -97)
        let fetchedAt = Date(timeIntervalSince1970: 100)
        let calculatedAt = Date(timeIntervalSince1970: 200)
        let provider = LinkTerrainFixtureProvider(fetchedAt: fetchedAt)
        let analyzer = NodePlannerLinkAnalyzer(elevationProvider: provider, now: { calculatedAt })

        let analysis = try await analyzer.analyze(
            NodePlannerLinkAnalysisRequest(
                pointA: pointA,
                pointB: pointB,
                heightAMeters: 20,
                heightBMeters: 20,
                frequencyMHz: 915,
                refractionK: 4.0 / 3.0,
                linkBudget: nil,
                sampleCount: 3
            )
        )

        #expect(analysis.terrain.provenance.fetchedAt == fetchedAt)
        #expect(analysis.terrain.provenance.origin == .network)
        #expect(analysis.calculatedAt == calculatedAt)
        #expect(analysis.engineID == "meshcore-one-los-compatible")
        #expect(analysis.engineVersion == 1)
        #expect(analysis.result.distanceMeters > 5_000)
        #expect(analysis.result.frequencyMHz == 915)
    }

    @Test func rejectsIdenticalEndpointsBeforeFetchingTerrain() async throws {
        let point = try NodePlannerCoordinate(latitude: 30, longitude: -97)
        let analyzer = NodePlannerLinkAnalyzer(elevationProvider: LinkTerrainFixtureProvider())

        await #expect(throws: NodePlannerLinkAnalysisError.identicalEndpoints) {
            try await analyzer.analyze(
                NodePlannerLinkAnalysisRequest(
                    pointA: point,
                    pointB: point,
                    heightAMeters: 1,
                    heightBMeters: 1,
                    frequencyMHz: 915,
                    refractionK: 4.0 / 3.0,
                    linkBudget: nil,
                    sampleCount: 3
                )
            )
        }
    }

    @Test func rejectsProviderCoordinateMismatch() async throws {
        let pointA = try NodePlannerCoordinate(latitude: 30, longitude: -97)
        let pointB = try NodePlannerCoordinate(latitude: 31, longitude: -97)
        let provider = LinkTerrainFixtureProvider(reversesCoordinates: true)
        let analyzer = NodePlannerLinkAnalyzer(elevationProvider: provider)

        await #expect(throws: NodePlannerLinkAnalysisError.terrainCoordinateMismatch) {
            try await analyzer.analyze(
                NodePlannerLinkAnalysisRequest(
                    pointA: pointA,
                    pointB: pointB,
                    heightAMeters: 1,
                    heightBMeters: 1,
                    frequencyMHz: 915,
                    refractionK: 4.0 / 3.0,
                    linkBudget: nil,
                    sampleCount: 3
                )
            )
        }
    }
}

private struct LinkTerrainFixtureProvider: NodePlannerElevationProviding {
    let fetchedAt: Date
    let reversesCoordinates: Bool

    init(fetchedAt: Date = .distantPast, reversesCoordinates: Bool = false) {
        self.fetchedAt = fetchedAt
        self.reversesCoordinates = reversesCoordinates
    }

    func elevations(for coordinates: [NodePlannerCoordinate]) async throws -> NodePlannerTerrainProfile {
        let outputCoordinates = reversesCoordinates ? Array(coordinates.reversed()) : coordinates
        let samples = outputCoordinates.map {
            NodePlannerTerrainSample(coordinate: $0, elevationMeters: 100)
        }
        return NodePlannerTerrainProfile(
            samples: samples,
            provenance: NodePlannerElevationProvenance(fetchedAt: fetchedAt, origin: .network)
        )
    }
}
