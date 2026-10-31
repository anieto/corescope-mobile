import CryptoKit
import Foundation
import Testing
@testable import TerrainReader

private final class Fixture {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    var url: URL { folder.appendingPathComponent("terrain.hgt") }
    let values: [Int16] = [-32768, -32767, -23, -1, 0, 1, 256, 32767, 17]
    var releases = 0
    var digest: String = ""

    init() throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let bytes = Data(values.flatMap { value -> [UInt8] in
            let bits = UInt16(bitPattern: value)
            return [UInt8(bits >> 8), UInt8(bits & 255)]
        })
        try bytes.write(to: url)
        digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
    func open(side: Int = 3, digest: String? = nil, cancelled: () -> Bool = { false }) throws -> LeasedTerrainReader {
        try LeasedTerrainReader.open(url: url, side: side, decodedSHA256: digest ?? self.digest,
                                     cancelled: cancelled, release: { self.releases += 1 })
    }
    deinit { try? FileManager.default.removeItem(at: folder) }
}

@Test func signedSamplesAndRowCrossing() throws {
    let fixture = try Fixture()
    let reader = try fixture.open()
    defer { reader.close() }
    #expect(try reader.read(start: 0, count: 9) == fixture.values)
    #expect(try reader.read(start: 2, count: 4) == Array(fixture.values[2..<6]))
    #expect(try reader.read(start: 8, count: 1) == [17])
    #expect(fixture.releases == 0)
}

@Test func closeReleasesExactlyOnceAndPreventsReads() throws {
    let fixture = try Fixture()
    let reader = try fixture.open()
    reader.close(); reader.close()
    #expect(fixture.releases == 1)
    #expect(throws: TerrainReadFailure.closed) { try reader.read(start: 0, count: 1) }
}

@Test func deinitReleasesPin() throws {
    let fixture = try Fixture()
    do { let reader = try fixture.open(); #expect(reader.side == 3) }
    #expect(fixture.releases == 1)
}

@Test(arguments: [0, 1, 8, 3602, Int.max])
func invalidDimensionsReleasePin(side: Int) throws {
    let fixture = try Fixture()
    #expect(throws: TerrainReadFailure.invalidDescriptor) { try fixture.open(side: side) }
    #expect(fixture.releases == 1)
}

@Test(arguments: ["", "xyz", String(repeating: "A", count: 64)])
func malformedDigestReleasePin(digest: String) throws {
    let fixture = try Fixture()
    #expect(throws: TerrainReadFailure.invalidDescriptor) { try fixture.open(digest: digest) }
    #expect(fixture.releases == 1)
}

@Test func checksumMismatchReleasesPin() throws {
    let fixture = try Fixture()
    #expect(throws: TerrainReadFailure.corrupt) { try fixture.open(digest: String(repeating: "0", count: 64)) }
    #expect(fixture.releases == 1)
}

@Test(arguments: [0, 17, 19])
func wrongFileSizeReleasesPin(size: Int) throws {
    let fixture = try Fixture()
    try Data(repeating: 0, count: size).write(to: fixture.url)
    #expect(throws: TerrainReadFailure.corrupt) { try fixture.open() }
    #expect(fixture.releases == 1)
}

@Test func missingFileReleasesPin() throws {
    let fixture = try Fixture()
    try FileManager.default.removeItem(at: fixture.url)
    #expect(throws: TerrainReadFailure.io) { try fixture.open() }
    #expect(fixture.releases == 1)
}

@Test(arguments: [1, 2, 3])
func cancellationDuringOpenReleasesPin(check: Int) throws {
    let fixture = try Fixture()
    var calls = 0
    #expect(throws: TerrainReadFailure.cancelled) {
        try fixture.open(cancelled: { calls += 1; return calls == check })
    }
    #expect(fixture.releases == 1)
}

@Test func invalidRangesNeverOverflow() throws {
    let fixture = try Fixture()
    let reader = try fixture.open(); defer { reader.close() }
    for (start, count) in [(-1, 1), (0, 0), (9, 1), (8, 2), (0, 32769), (Int.max, 1), (0, Int.max)] {
        #expect(throws: TerrainReadFailure.invalidRange) { try reader.read(start: start, count: count) }
    }
}

@Test(arguments: [1, 2, 3])
func cancelledReadsKeepLeaseUntilClose(check: Int) throws {
    let fixture = try Fixture()
    let reader = try fixture.open(); defer { reader.close() }
    var calls = 0
    #expect(throws: TerrainReadFailure.cancelled) {
        try reader.read(start: 0, count: 1, cancelled: { calls += 1; return calls == check })
    }
    #expect(fixture.releases == 0)
    #expect(try reader.read(start: 0, count: 1) == [-32768])
}

@Test func truncationAfterOpenIsUnknownNotZero() throws {
    let fixture = try Fixture()
    let reader = try fixture.open(); defer { reader.close() }
    let writer = try FileHandle(forWritingTo: fixture.url)
    try writer.truncate(atOffset: 2); try writer.close()
    #expect(throws: TerrainReadFailure.corrupt) { try reader.read(start: 8, count: 1) }
}

@Test func pinnedRealTileReplay() throws {
    let path = try #require(ProcessInfo.processInfo.environment["TERRAIN_READER_FIXTURES"])
    let root = URL(fileURLWithPath: path)
    let tiles = try String(contentsOf: root.appendingPathComponent("tiles.tsv"), encoding: .utf8)
    let cases = try String(contentsOf: root.appendingPathComponent("samples.tsv"), encoding: .utf8)
    var checked = 0
    for line in tiles.split(separator: "\n") {
        let fields = line.split(separator: "\t").map(String.init)
        var releases = 0
        let reader = try LeasedTerrainReader.open(url: root.appendingPathComponent(fields[0]), side: 3601,
                                                  decodedSHA256: fields[1], release: { releases += 1 })
        do {
            defer { reader.close() }
            for sample in cases.split(separator: "\n") {
                let parts = sample.split(separator: "\t").map(String.init)
                guard parts[0] == fields[0] else { continue }
                let start = try #require(Int(parts[1]))
                let expected = try parts[2].split(separator: ",").map { try #require(Int16($0)) }
                #expect(try reader.read(start: start, count: expected.count) == expected)
                checked += 1
            }
        }
        #expect(releases == 1)
    }
    #expect(checked == 848)
}
