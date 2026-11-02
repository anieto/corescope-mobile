import CryptoKit
import Darwin
import Foundation

enum CoverageTerrainCacheFailure: Error, Equatable {
    case busy, closed, invalidCache, missing, quota, cancelled, io
}

/// One serialized background worker owns this cache and its reader callbacks. Never use on the UI
/// actor. A process lock prevents two owners from modifying the same private cache directory.
final class CoverageTerrainDiskCache {
    private struct Metadata: Codable {
        let schema: Int
        let identity: String
        let side: Int
        let decodedSHA256: String
        let sourceURL: String
        let originURL: String
        let fetchedAtMilliseconds: Int64
        var accessSequence: Int64
        var bytes: Int64 { Int64(side) * Int64(side) * 2 }
    }
    private let root: URL
    private let quota: Int64
    private var lockFD: Int32 = -1
    private var entries: [String: Metadata] = [:]
    private var pins: [String: Int] = [:]
    private var sequence: Int64 = 0
    private var closed = false
    private static let marker = Data("nodescope-coverage-cache-v1\n".utf8)

    init(root: URL, quota: Int64) throws {
        guard (8192...2_000_000_000).contains(quota) else { throw CoverageTerrainRepositoryFailure.invalidLimits }
        self.root = root
        self.quota = quota
        do {
            if FileManager.default.fileExists(atPath: root.path) {
                guard try root.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else {
                    throw CoverageTerrainCacheFailure.invalidCache
                }
            } else { try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true) }
            let children = try FileManager.default.contentsOfDirectory(atPath: root.path)
            let markerURL = root.appendingPathComponent("owner")
            if !children.isEmpty {
                guard (try? Self.regular(markerURL)) == true, try Self.smallData(markerURL) == Self.marker else {
                    throw CoverageTerrainCacheFailure.invalidCache
                }
            }
            let lockURL = root.appendingPathComponent("lock")
            lockFD = Darwin.open(lockURL.path, O_CREAT | O_RDWR | O_NOFOLLOW, S_IRUSR | S_IWUSR)
            guard lockFD >= 0 else { throw CoverageTerrainCacheFailure.io }
            guard flock(lockFD, LOCK_EX | LOCK_NB) == 0 else { throw CoverageTerrainCacheFailure.busy }
            if children.isEmpty { try Self.marker.write(to: markerURL, options: .atomic) }
            try recover()
        } catch {
            if lockFD >= 0 { Darwin.close(lockFD); lockFD = -1 }
            throw error
        }
    }

    var inventory: [CoverageTerrainInventoryEntry] {
        entries.map { CoverageTerrainInventoryEntry(key: $0.key, decodedBytes: $0.value.bytes, pinned: pins[$0.key, default: 0] > 0) }
    }

    func preflight(_ descriptors: [CoverageTerrainDescriptor], remainingDownloadBytes: Int64, attempts: Int = 2) throws -> CoverageTerrainPreflight {
        try requireOpen()
        return try CoverageTerrainPreflight.make(requested: descriptors, inventory: inventory, quota: quota,
                                                 remainingDownloadBytes: remainingDownloadBytes, attempts: attempts)
    }

    /// Accepts a private, already-decoded file from the future validated download/decode adapter.
    /// Hash/length are independently verified here; this does NOT validate gzip or compressed digest.
    func publish(_ descriptor: CoverageTerrainDescriptor, decodedFile: URL, decodedSHA256: String,
                 fetchedAtMilliseconds: Int64, cancelled: () -> Bool = { false }) throws {
        try requireOpen()
        try check(cancelled)
        guard fetchedAtMilliseconds >= 0, Self.digest(decodedSHA256),
              let identity = String(data: descriptor.canonicalIdentity, encoding: .utf8) else {
            throw CoverageTerrainCacheFailure.invalidCache
        }
        let key = descriptor.cacheKey
        guard pins[key, default: 0] == 0 else { throw CoverageTerrainCacheFailure.busy }
        let metadata = Metadata(schema: 1, identity: identity, side: descriptor.side, decodedSHA256: decodedSHA256,
            sourceURL: descriptor.url, originURL: descriptor.originURL, fetchedAtMilliseconds: fetchedAtMilliseconds,
            accessSequence: try nextSequence())
        let metadataData = try encode(metadata)
        // Keep an existing entry intact until a replacement is fully validated.
        try makeRoom(for: descriptor.decodedBytes + 8192, protecting: key)
        let stage = root.appendingPathComponent(".stage-" + UUID().uuidString.lowercased())
        try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: stage) }
        let destination = stage.appendingPathComponent("terrain.hgt")
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else { throw CoverageTerrainCacheFailure.io }
        let input = try FileHandle(forReadingFrom: decodedFile)
        defer { try? input.close() }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var written: Int64 = 0
        while true {
            try check(cancelled)
            let bytes = try input.read(upToCount: Int(min(65536, descriptor.decodedBytes - written + 1))) ?? Data()
            if bytes.isEmpty { break }
            guard Int64(bytes.count) <= descriptor.decodedBytes - written else { throw TerrainReadFailure.corrupt }
            try output.write(contentsOf: bytes)
            written += Int64(bytes.count)
        }
        guard written == descriptor.decodedBytes else { throw TerrainReadFailure.corrupt }
        try output.synchronize()
        try output.close()
        let verified = try LeasedTerrainReader.open(url: destination, side: descriptor.side,
            decodedSHA256: decodedSHA256, cancelled: cancelled, release: {})
        verified.close()
        try metadataData.write(to: stage.appendingPathComponent("metadata.json"))
        try check(cancelled)
        // A cache miss/refetch may replace an unpinned corrupt entry. Saved plans are separate.
        if entries[key] != nil { try remove(key) }
        guard rename(stage.path, root.appendingPathComponent(key).path) == 0 else { throw CoverageTerrainCacheFailure.io }
        entries[key] = metadata
    }

    func acquire(_ descriptor: CoverageTerrainDescriptor, cancelled: () -> Bool = { false }) throws -> LeasedTerrainReader {
        try requireOpen()
        try check(cancelled)
        let key = descriptor.cacheKey
        guard var metadata = entries[key] else { throw CoverageTerrainCacheFailure.missing }
        guard Data(metadata.identity.utf8) == descriptor.canonicalIdentity, metadata.side == descriptor.side else {
            throw CoverageTerrainCacheFailure.invalidCache
        }
        let terrain = root.appendingPathComponent(key).appendingPathComponent("terrain.hgt")
        guard try Self.regular(terrain) else { throw CoverageTerrainCacheFailure.invalidCache }
        pins[key, default: 0] += 1
        let reader = try LeasedTerrainReader.open(url: terrain, side: metadata.side,
            decodedSHA256: metadata.decodedSHA256, cancelled: cancelled, release: { [self] in pins[key, default: 0] -= 1 })
        do {
            metadata.accessSequence = try nextSequence()
            let directory = root.appendingPathComponent(key)
            let part = directory.appendingPathComponent("metadata.part")
            try encode(metadata).write(to: part)
            guard rename(part.path, directory.appendingPathComponent("metadata.json").path) == 0 else { throw CoverageTerrainCacheFailure.io }
            entries[key] = metadata
            return reader
        } catch {
            reader.close()
            throw error
        }
    }

    /// Either transfers a complete set to the caller, or closes every reader opened here.
    func acquireMany(_ descriptors: [CoverageTerrainDescriptor], cancelled: () -> Bool = { false }) throws -> [String: LeasedTerrainReader] {
        let plan = try preflight(descriptors, remainingDownloadBytes: 0)
        guard plan.canPreparePinnedSet else { throw CoverageTerrainCacheFailure.quota }
        var readers: [String: LeasedTerrainReader] = [:]
        do {
            for descriptor in descriptors where readers[descriptor.cacheKey] == nil {
                readers[descriptor.cacheKey] = try acquire(descriptor, cancelled: cancelled)
            }
            try check(cancelled)
            return readers
        } catch {
            readers.values.forEach { $0.close() }
            throw error
        }
    }

    func clearUnpinned() throws {
        try requireOpen()
        for key in Array(entries.keys) where pins[key, default: 0] == 0 { try remove(key) }
    }

    func close() throws {
        if closed { return }
        guard !pins.values.contains(where: { $0 > 0 }) else { throw CoverageTerrainCacheFailure.busy }
        closed = true
        if lockFD >= 0 { Darwin.close(lockFD); lockFD = -1 }
    }

    private func recover() throws {
        let children = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isSymbolicLinkKey, .isDirectoryKey])
        guard children.count <= 4098 else { throw CoverageTerrainCacheFailure.invalidCache }
        for child in children {
            let name = child.lastPathComponent
            if name == "owner" || name == "lock" { continue }
            let resource = try child.resourceValues(forKeys: [.isSymbolicLinkKey, .isDirectoryKey])
            guard resource.isSymbolicLink != true, resource.isDirectory == true else { throw CoverageTerrainCacheFailure.invalidCache }
            if name.hasPrefix(".stage-"), UUID(uuidString: String(name.dropFirst(7))) != nil {
                try FileManager.default.removeItem(at: child)
                continue
            }
            guard Self.digest(name) else { throw CoverageTerrainCacheFailure.invalidCache }
            let names = try FileManager.default.contentsOfDirectory(atPath: child.path)
            guard Set(names).isSubset(of: ["terrain.hgt", "metadata.json", "metadata.part"]),
                  try Self.regular(child.appendingPathComponent("metadata.json")),
                  try Self.regular(child.appendingPathComponent("terrain.hgt")) else { throw CoverageTerrainCacheFailure.invalidCache }
            let metadata = try JSONDecoder().decode(Metadata.self, from: Self.smallData(child.appendingPathComponent("metadata.json")))
            guard metadata.schema == 1, (2...3601).contains(metadata.side), 3600 % (metadata.side - 1) == 0,
                  Self.digest(metadata.decodedSHA256), metadata.fetchedAtMilliseconds >= 0,
                  metadata.accessSequence >= 0, metadata.accessSequence < Int64.max,
                  SHA256.hash(data: Data(metadata.identity.utf8)).map({ String(format: "%02x", $0) }).joined() == name
            else { throw CoverageTerrainCacheFailure.invalidCache }
            // File content/length is revalidated on every lease, not trusted from metadata.
            entries[name] = metadata
            sequence = max(sequence, metadata.accessSequence)
            let part = child.appendingPathComponent("metadata.part")
            if names.contains("metadata.part") {
                guard try Self.regular(part) else { throw CoverageTerrainCacheFailure.invalidCache }
                try FileManager.default.removeItem(at: part)
            }
        }
    }

    private var used: Int64 { 4096 + entries.values.reduce(0) { $0 + $1.bytes + 8192 } }
    private func makeRoom(for needed: Int64, protecting: String) throws {
        guard needed + 4096 <= quota else { throw CoverageTerrainCacheFailure.quota }
        while used + needed > quota {
            guard let victim = entries.keys.filter({ $0 != protecting && pins[$0, default: 0] == 0 }).min(by: {
                let a = entries[$0]?.accessSequence ?? 0, b = entries[$1]?.accessSequence ?? 0
                return a == b ? $0 < $1 : a < b
            }) else { throw CoverageTerrainCacheFailure.quota }
            try remove(victim)
        }
    }
    private func remove(_ key: String) throws {
        guard pins[key, default: 0] == 0 else { throw CoverageTerrainCacheFailure.busy }
        try FileManager.default.removeItem(at: root.appendingPathComponent(key))
        entries.removeValue(forKey: key)
        pins.removeValue(forKey: key)
    }
    private func nextSequence() throws -> Int64 {
        guard sequence < Int64.max - 1 else { throw CoverageTerrainCacheFailure.invalidCache }
        sequence += 1
        return sequence
    }
    private func requireOpen() throws { if closed { throw CoverageTerrainCacheFailure.closed } }
    private func check(_ cancelled: () -> Bool) throws { if cancelled() { throw CoverageTerrainCacheFailure.cancelled } }
    private func encode(_ value: Metadata) throws -> Data {
        let data = try JSONEncoder().encode(value)
        guard data.count <= 4096 else { throw CoverageTerrainCacheFailure.invalidCache }
        return data
    }
    private static func digest(_ text: String) -> Bool {
        text.utf8.count == 64 && text.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }
    private static func regular(_ url: URL) throws -> Bool {
        let resource = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        return resource.isRegularFile == true && resource.isSymbolicLink != true
    }
    private static func smallData(_ url: URL) throws -> Data {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let data = try file.read(upToCount: 4097) ?? Data()
        guard data.count <= 4096 else { throw CoverageTerrainCacheFailure.invalidCache }
        return data
    }
    deinit { if lockFD >= 0 { Darwin.close(lockFD) } }
}
