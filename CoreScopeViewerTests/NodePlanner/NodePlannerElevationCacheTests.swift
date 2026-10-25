import Foundation
import Testing
@testable import CoreScopeViewer

struct NodePlannerElevationCacheTests {
    @Test func persistsAndReloadsFreshElevation() async throws {
        let fileURL = temporaryCacheURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }
        let coordinate = try NodePlannerCoordinate(latitude: 30, longitude: -97)
        let fetchedAt = Date(timeIntervalSince1970: 1_000)
        let firstCache = NodePlannerElevationDiskCache(fileURL: fileURL)

        await firstCache.store([
            NodePlannerCachedElevation(
                coordinate: coordinate,
                elevationMeters: 202,
                fetchedAt: fetchedAt
            )
        ])
        let reloadedCache = NodePlannerElevationDiskCache(fileURL: fileURL)
        let record = await reloadedCache.elevation(
            for: coordinate,
            newerThan: Date(timeIntervalSince1970: 900)
        )

        #expect(record?.elevationMeters == 202)
        #expect(record?.fetchedAt == fetchedAt)
    }

    @Test func rejectsExpiredElevation() async throws {
        let fileURL = temporaryCacheURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }
        let coordinate = try NodePlannerCoordinate(latitude: 30, longitude: -97)
        let cache = NodePlannerElevationDiskCache(fileURL: fileURL)
        await cache.store([
            NodePlannerCachedElevation(
                coordinate: coordinate,
                elevationMeters: 202,
                fetchedAt: Date(timeIntervalSince1970: 1_000)
            )
        ])

        let record = await cache.elevation(
            for: coordinate,
            newerThan: Date(timeIntervalSince1970: 1_001)
        )

        #expect(record == nil)
    }

    @Test func corruptCacheFailsOpen() async throws {
        let fileURL = temporaryCacheURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try Data("not json".utf8).write(to: fileURL)
        let coordinate = try NodePlannerCoordinate(latitude: 30, longitude: -97)
        let cache = NodePlannerElevationDiskCache(fileURL: fileURL)

        let record = await cache.elevation(for: coordinate, newerThan: .distantPast)

        #expect(record == nil)
    }

    private func temporaryCacheURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
            .appendingPathComponent("elevation-cache-v1.json", isDirectory: false)
    }
}
