import Foundation
import Testing
import CoreGraphics
import ImageIO
import MapKit
@testable import CoreScopeViewer

struct CoverageRasterTests {
    private func grid() throws -> CoverageGrid {
        try CoverageGrid(radiusMeters: 1000, spacingMeters: 500, minEastMeters: -1000,
            maxEastMeters: 1000, minNorthMeters: -1000, maxNorthMeters: 1000)
    }
    private func cells(_ grid: CoverageGrid) throws -> [CoverageAreaCell] {
        try (0..<grid.count).map { index in
            let point = try #require(grid.point(at: index))
            if point.state == .outsideRadius { return .init(state: .outsideRadius, lossDb: nil, marginDb: nil) }
            if point.state == .nearSite { return .init(state: .nearSite, lossDb: nil, marginDb: nil) }
            if point.northMeters > 0 { return .init(state: .missingTerrain, lossDb: nil, marginDb: nil) }
            return .init(state: .qualified, lossDb: 120, marginDb: point.eastMeters < 0 ? -1 : 1)
        }
    }
    private func raster(latitude: Double = 0, longitude: Double = 0) throws -> CoverageRaster {
        let grid = try grid()
        return try CoverageRaster(grid: grid, transmitter: .init(latitude: latitude, longitude: longitude),
            area: .init(cells: cells(grid), chargedSamples: 0, chargedGeometry: 0, workLimited: false))
    }

    @Test func sharedGeographicCheckpoints() throws {
        struct Point: Decodable { let latitude, longitude: Double; let state: String }
        struct Fixture: Decodable { let latitude, longitude: Double; let points: [Point] }
        let path = "shared/fixtures/coverage/raster-geography.json"
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent(path).path) { root.deleteLastPathComponent() }
        let fixtures = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: root.appendingPathComponent(path)))
        #expect(fixtures.count == 4)
        for fixture in fixtures {
            let image = try raster(latitude: fixture.latitude, longitude: fixture.longitude)
            for point in fixture.points {
                #expect(image.classification(latitude: point.latitude, longitude: point.longitude).rawValue == point.state)
            }
        }
    }

    @Test func projectedTilePreservesMaskAndTransparency() throws {
        let raster = try raster()
        let data = try raster.tile(zoom: 14, x: 8192, y: 8191)
        #expect(data.count == 256 * 256 * 4)
        func pixel(_ x: Int, _ y: Int) -> [UInt8] { Array(data[(y * 256 + x) * 4..<(y * 256 + x + 1) * 4]) }
        // Northeast of the transmitter is unresolved; the distant corner is outside the study.
        #expect(pixel(32, 224) == [0x66, 0x66, 0x66, 0x90])
        #expect(pixel(33, 224) == [0x66, 0x66, 0x66, 0x90])
        #expect(pixel(34, 224) == [0, 0, 0, 0])
        #expect(pixel(255, 0) == [0, 0, 0, 0])
        let southeast = try raster.tile(zoom: 14, x: 8192, y: 8192)
        #expect(Array(southeast[(32 * 256 + 32) * 4..<(32 * 256 + 33) * 4]) == [0x26, 0xB8, 0x5A, 0xA0])
        #expect(CoverageRaster.rgba(.belowThreshold, column: 0, row: 0) == 0)
        #expect(CoverageRaster.rgba(.unresolved, column: 256, row: 0) == CoverageRaster.rgba(.unresolved, column: 0, row: 0))
    }

    @Test func mapKitAdapterReturnsPNGAndRejectsInvalidatedRequests() async throws {
        let raster = try raster()
        let overlay = try CoverageMapKitOverlay(raster: raster, cacheBytes: 1024 * 1024)
        #expect(!overlay.canReplaceMapContent)
        #expect(!overlay.isGeometryFlipped)
        #expect(overlay.tileSize == CGSize(width: 256, height: 256))
        func load(_ path: MKTileOverlayPath) async -> Result<Data, Error> {
            await withCheckedContinuation { continuation in
                overlay.loadTile(at: path) { data, error in
                    if let error { continuation.resume(returning: .failure(error)) }
                    else if let data { continuation.resume(returning: .success(data)) }
                    else { continuation.resume(returning: .failure(CoverageTilePNGFailure.encoding)) }
                }
            }
        }
        let path = MKTileOverlayPath(x: 8192, y: 8192, z: 14, contentScaleFactor: 2)
        let actual = try await load(path).get()
        #expect(actual == (try raster.pngTile(zoom: 14, x: 8192, y: 8192)))
        let invalid = await load(MKTileOverlayPath(x: 0, y: 0, z: 23, contentScaleFactor: 1))
        switch invalid {
        case .success: Issue.record("Invalid map tile returned data")
        case .failure(let error): #expect(error as? CoverageRasterFailure == .invalidTile)
        }
        overlay.invalidate()
        switch await load(path) {
        case .success: Issue.record("Invalidated overlay returned data")
        case .failure(let error): #expect(error as? CoverageTileStoreFailure == .inactive)
        }
    }

    @Test func tinyTilesCannotGrowCacheEntryCountWithoutBound() throws {
        let store = try CoverageTileStore(raster: raster(), byteLimit: 8 * 1024 * 1024)
        let size = try store.tile(zoom: 8, x: 0, y: 0).count
        for x in 1..<64 { _ = try store.tile(zoom: 8, x: x, y: 0) }
        var hitChecks = 0
        _ = try store.tile(zoom: 8, x: 0, y: 0) {
            hitChecks += 1
            return hitChecks > 1
        }
        _ = try store.tile(zoom: 8, x: 64, y: 0)
        // All of these distant tiles are transparent and have the same encoded size.
        #expect(store.cachedBytes == 64 * size)
        var checks = 0
        #expect(throws: CoverageRasterFailure.cancelled) {
            try store.tile(zoom: 8, x: 1, y: 0) {
                checks += 1
                return checks > 1
            }
        }
        #expect(store.cachedBytes == 64 * size)
    }

    @Test func invalidationDoesNotWaitForRendering() async throws {
        let store = try CoverageTileStore(raster: raster(), byteLimit: 1024 * 1024)
        let entered = DispatchSemaphore(value: 0)
        let resume = DispatchSemaphore(value: 0)
        let worker = Task.detached {
            var checkpoints = 0
            return Result {
                try store.tile(zoom: 14, x: 8192, y: 8192) {
                    checkpoints += 1
                    if checkpoints == 3 {
                        entered.signal()
                        _ = resume.wait(timeout: .now() + 5)
                    }
                    return false
                }
            }
        }
        defer { resume.signal() }
        let didEnter = await withCheckedContinuation { continuation in
            DispatchQueue.global().async {
                continuation.resume(returning: entered.wait(timeout: .now() + 5) == .success)
            }
        }
        #expect(didEnter)
        store.invalidate()
        #expect(store.cachedBytes == 0)
        resume.signal()
        switch await worker.value {
        case .success: Issue.record("Invalidated rendering returned a tile")
        case .failure(let error): #expect(error as? CoverageRasterFailure == .cancelled)
        }
    }

    @Test func tileStoreBoundsRetentionAndRejectsObsoleteWork() throws {
        let raster = try raster()
        let reference = try raster.pngTile(zoom: 0, x: 0, y: 0)
        let store = try CoverageTileStore(raster: raster, byteLimit: reference.count)
        #expect(try store.tile(zoom: 0, x: 0, y: 0) == reference)
        #expect(store.cachedBytes == reference.count)
        #expect(try store.tile(zoom: 0, x: 0, y: 0) == reference)
        for x in 0..<4 {
            _ = try store.tile(zoom: 2, x: x, y: 0)
            #expect(store.cachedBytes <= reference.count)
        }
        #expect(throws: CoverageRasterFailure.cancelled) {
            try store.tile(zoom: 2, x: 3, y: 0, cancelled: { true })
        }
        store.invalidate()
        #expect(store.cachedBytes == 0)
        #expect(throws: CoverageTileStoreFailure.inactive) { try store.tile(zoom: 0, x: 0, y: 0) }
        let uncached = try CoverageTileStore(raster: raster, byteLimit: 0)
        #expect(try uncached.tile(zoom: 0, x: 0, y: 0) == reference)
        #expect(uncached.cachedBytes == 0)
        for capacity in [-1, 8 * 1024 * 1024 + 1] {
            #expect(throws: CoverageTileStoreFailure.invalidCapacity) {
                try CoverageTileStore(raster: raster, byteLimit: capacity)
            }
        }
        let interrupted = try CoverageTileStore(raster: raster, byteLimit: 1024 * 1024)
        var checkpoints = 0
        #expect(throws: CoverageRasterFailure.cancelled) {
            try interrupted.tile(zoom: 14, x: 8192, y: 8192) {
                checkpoints += 1
                if checkpoints == 3 { interrupted.invalidate() }
                return false
            }
        }
        #expect(interrupted.cachedBytes == 0)
        #expect(throws: CoverageTileStoreFailure.inactive) { try interrupted.tile(zoom: 0, x: 0, y: 0) }
    }

    @Test func pngTilePreservesGeographicRaster() throws {
        let raster = try raster()
        let rgba = try raster.tile(zoom: 14, x: 8192, y: 8192)
        let png = try raster.pngTile(zoom: 14, x: 8192, y: 8192)
        let source = try #require(CGImageSourceCreateWithData(png as CFData, nil))
        let image = try #require(CGImageSourceCreateImageAtIndex(source, 0, nil))
        #expect(image.width == 256)
        #expect(image.height == 256)
        #expect(image.alphaInfo == .last)
        let provider = try #require(image.dataProvider)
        let decoded = try #require(provider.data) as Data
        for row in 0..<256 {
            let start = row * image.bytesPerRow
            #expect(decoded[start..<start + 1024] == rgba[row * 1024..<(row + 1) * 1024])
        }
        #expect(throws: CoverageRasterFailure.invalidTile) { try raster.pngTile(zoom: 23, x: 0, y: 0) }
        #expect(throws: CoverageRasterFailure.cancelled) {
            try raster.pngTile(zoom: 14, x: 8192, y: 8192, cancelled: { true })
        }
    }

    @Test func rejectsInvalidAndCancelledRasters() throws {
        let grid = try grid(), raster = try raster()
        var invalid = try cells(grid)
        invalid[12] = .init(state: .qualified, lossDb: 1, marginDb: 1)
        #expect(throws: CoverageRasterFailure.invalidResult) {
            try CoverageRaster(grid: grid, transmitter: .init(latitude: 0, longitude: 0),
                area: .init(cells: invalid, chargedSamples: 0, chargedGeometry: 0, workLimited: false))
        }
        invalid = try cells(grid)
        invalid[17] = .init(state: .qualified, lossDb: .nan, marginDb: 1)
        #expect(throws: CoverageRasterFailure.invalidResult) {
            try CoverageRaster(grid: grid, transmitter: .init(latitude: 0, longitude: 0),
                area: .init(cells: invalid, chargedSamples: 0, chargedGeometry: 0, workLimited: false))
        }
        #expect(throws: CoverageRasterFailure.cancelled) { try raster.tile(zoom: 14, x: 8192, y: 8192, cancelled: { true }) }
        for (z, x, y) in [(-1, 0, 0), (23, 0, 0), (0, 1, 0), (0, 0, -1)] {
            #expect(throws: CoverageRasterFailure.invalidTile) { try raster.tile(zoom: z, x: x, y: y) }
        }
    }
}

struct CoverageTilePNGTests {
    @Test func decoderPreservesPixelOrientationAndAlpha() throws {
        var rgba = Data(count: 256 * 256 * 4)
        for y in 0..<256 {
            for x in 0..<256 {
                let offset = (y * 256 + x) * 4
                rgba[offset] = UInt8(x)
                rgba[offset + 1] = UInt8(y)
                rgba[offset + 2] = 90
                rgba[offset + 3] = UInt8((x + y) % 256)
            }
        }
        let encoded = try CoverageTilePNG.encode(rgba)
        let source = try #require(CGImageSourceCreateWithData(encoded as CFData, nil))
        let image = try #require(CGImageSourceCreateImageAtIndex(source, 0, nil))
        #expect(image.width == 256)
        #expect(image.height == 256)
        #expect(image.bitsPerComponent == 8)
        #expect(image.bitsPerPixel == 32)
        #expect(image.alphaInfo == .last)
        let provider = try #require(image.dataProvider)
        let decoded = try #require(provider.data) as Data
        // Compare rows directly to avoid CGContext premultiplication and color conversion.
        for row in 0..<256 {
            let start = row * image.bytesPerRow
            #expect(decoded[start..<start + 1024] == rgba[row * 1024..<(row + 1) * 1024])
        }
    }

    @Test func rejectsWrongDimensionsAndCancellation() throws {
        for size in [0, 256 * 256 * 4 - 1, 256 * 256 * 4 + 1] {
            #expect(throws: CoverageTilePNGFailure.invalidPixels) {
                try CoverageTilePNG.encode(Data(count: size))
            }
        }
        let rgba = Data(count: 256 * 256 * 4)
        #expect(throws: CoverageTilePNGFailure.cancelled) {
            try CoverageTilePNG.encode(rgba, cancelled: { true })
        }
        var checks = 0
        #expect(throws: CoverageTilePNGFailure.cancelled) {
            try CoverageTilePNG.encode(rgba) {
                checks += 1
                return checks == 2
            }
        }
        #expect(checks == 2)
    }
}
