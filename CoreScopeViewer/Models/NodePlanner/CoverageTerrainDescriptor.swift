import CryptoKit
import Foundation

enum CoverageTerrainRepositoryFailure: Error, Equatable {
    case invalidDescriptor, invalidInventory, invalidLimits, tooManyTiles
}

/// Validated immutable source identity. URL is transport provenance, not cache identity:
/// a mirror of the same pinned payload must reuse the same cache entry.
struct CoverageTerrainDescriptor: Sendable {
    let dataset: String
    let revision: String
    let tile: String
    let compressedSHA256: String
    let packedBytes: Int64
    let side: Int
    let url: String
    let verticalDatum: String
    let attribution: String
    let sourceType: String
    let originURL: String
    let coordinate: CoverageTerrainTile
    let canonicalIdentity: Data
    let cacheKey: String
    var decodedBytes: Int64 { Int64(side) * Int64(side) * 2 }

    init(dataset: String, revision: String, tile: String, compressedSHA256: String,
         packedBytes: Int64, side: Int, url: String, verticalDatum: String,
         attribution: String, sourceType: String = "not independently resolved", originURL: String = "") throws {
        func matches(_ value: String, _ pattern: String) -> Bool {
            value.range(of: pattern, options: .regularExpression) == value.startIndex..<value.endIndex
        }
        guard matches(dataset, #"^[A-Za-z0-9_.-]{1,48}$"#),
              matches(revision, #"^[A-Za-z0-9_.-]{1,48}$"#),
              matches(tile, #"^[NS][0-9]{2}[EW][0-9]{3}$"#),
              matches(compressedSHA256, #"^[a-f0-9]{64}$"#),
              (1...40_000_000).contains(packedBytes), (2...3601).contains(side),
              3600 % (side - 1) == 0,
              [url, verticalDatum, attribution, sourceType].allSatisfy({
                  !$0.isEmpty && $0.unicodeScalars.count <= 1024 && !$0.contains("\n")
              }),
              Self.validURL(url, allowLoopback: true),
              originURL.isEmpty || (originURL.unicodeScalars.count <= 1024 && Self.validURL(originURL, allowLoopback: false))
        else { throw CoverageTerrainRepositoryFailure.invalidDescriptor }
        let characters = Array(tile)
        guard let latitude = Int(String(characters[1...2])),
              let longitude = Int(String(characters[4...6])),
              let coordinate = try? CoverageTerrainTile(south: characters[0] == "S" ? -latitude : latitude,
                                                        west: characters[3] == "W" ? -longitude : longitude)
        else { throw CoverageTerrainRepositoryFailure.invalidDescriptor }
        let identity: [String: Any] = [
            "dataset": dataset, "revision": revision, "tile": tile,
            "compressed_sha256": compressedSHA256, "packed_bytes": packedBytes, "side": side,
            "vertical_datum": verticalDatum, "attribution": attribution, "source_type": sourceType,
            "decoder": "signed-be-hgt-v1", "resolution_arcseconds": 3600 / (side - 1), "nodata": -32768,
            "footprint": ["south": coordinate.south, "north": coordinate.south + 1,
                          "west": coordinate.west, "east": coordinate.west + 1]
        ]
        let canonical = try JSONSerialization.data(withJSONObject: identity, options: [.sortedKeys, .withoutEscapingSlashes])
        self.dataset = dataset
        self.revision = revision
        self.tile = tile
        self.compressedSHA256 = compressedSHA256
        self.packedBytes = packedBytes
        self.side = side
        self.url = url
        self.verticalDatum = verticalDatum
        self.attribution = attribution
        self.sourceType = sourceType
        self.originURL = originURL
        self.coordinate = coordinate
        canonicalIdentity = canonical
        cacheKey = SHA256.hash(data: canonical).map { String(format: "%02x", $0) }.joined()
    }

    private static func validURL(_ text: String, allowLoopback: Bool) -> Bool {
        guard !text.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0) }),
              let components = URLComponents(string: text), let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil, components.fragment == nil else { return false }
        return components.scheme == "https" ||
            (allowLoopback && components.scheme == "http" && ["localhost", "127.0.0.1"].contains(host))
    }
}

/// Snapshot supplied by the serialized cache owner. It is an estimate, not a verified lease.
struct CoverageTerrainInventoryEntry: Sendable {
    let key: String
    let decodedBytes: Int64
    let pinned: Bool
}

struct CoverageTerrainPreflight: Equatable, Sendable {
    let tiles: Int
    let uncachedTiles: Int
    let expectedDownloadBytes: Int64
    let maximumAttemptBytes: Int64
    let decodedPayloadBytes: Int64
    let largestStagingReservation: Int64
    let pinnedSetReservationBytes: Int64
    let worstPinnedStagingBytes: Int64
    let canPreparePinnedSet: Bool
    let fitsDownloadBudget: Bool
    let hasAllCachedTiles: Bool
    /// Every cached payload still requires checksum validation before opening a lease.
    let cacheValidationPending = true

    static func make(requested: [CoverageTerrainDescriptor], inventory: [CoverageTerrainInventoryEntry],
                     quota: Int64, remainingDownloadBytes: Int64, attempts: Int = 2) throws -> Self {
        guard (8192...2_000_000_000).contains(quota), (0...512_000_000).contains(remainingDownloadBytes),
              (1...3).contains(attempts) else { throw CoverageTerrainRepositoryFailure.invalidLimits }
        guard requested.count <= 256 else { throw CoverageTerrainRepositoryFailure.tooManyTiles }
        guard inventory.count <= 4096, Set(inventory.map(\.key)).count == inventory.count,
              inventory.allSatisfy({
                  $0.key.utf8.count == 64 && $0.key.range(of: #"^[a-f0-9]{64}$"#, options: .regularExpression) != nil &&
                  (8...25_934_402).contains($0.decodedBytes) && $0.decodedBytes % 2 == 0
              }) else { throw CoverageTerrainRepositoryFailure.invalidInventory }
        let unique = Dictionary(requested.map { ($0.cacheKey, $0) }, uniquingKeysWith: { first, _ in first })
        let existing = Dictionary(uniqueKeysWithValues: inventory.map { ($0.key, $0) })
        guard !unique.values.contains(where: { descriptor in
            if let entry = existing[descriptor.cacheKey] { return entry.decodedBytes != descriptor.decodedBytes }
            return false
        }) else { throw CoverageTerrainRepositoryFailure.invalidInventory }
        let missing = unique.values.filter { existing[$0.cacheKey] == nil }
        let outsidePins = inventory.filter { $0.pinned && unique[$0.key] == nil }
            .reduce(Int64(0)) { $0 + $1.decodedBytes + 8192 }
        let decoded = unique.values.reduce(Int64(0)) { $0 + $1.decodedBytes }
        let pinned = 4096 + outsidePins + decoded + Int64(unique.count) * 8192
        let staging = pinned + (missing.map(\.packedBytes).max() ?? 0)
        let expected = missing.reduce(Int64(0)) { $0 + $1.packedBytes }
        return Self(tiles: unique.count, uncachedTiles: missing.count, expectedDownloadBytes: expected,
                    maximumAttemptBytes: missing.reduce(0) { $0 + ($1.packedBytes + 1) * Int64(attempts) },
                    decodedPayloadBytes: decoded,
                    largestStagingReservation: missing.map { $0.packedBytes + $0.decodedBytes + 8192 }.max() ?? 0,
                    pinnedSetReservationBytes: pinned, worstPinnedStagingBytes: staging,
                    canPreparePinnedSet: staging <= quota, fitsDownloadBudget: expected <= remainingDownloadBytes,
                    hasAllCachedTiles: missing.isEmpty)
    }
}

/// Conservative logical-byte estimate for one serialized download/decode/publish pipeline.
/// Requires eviction of unrelated unpinned entries and cleanup between tiles and retries.
/// This is not a filesystem free-space check or an acquired reservation.
struct CoverageTerrainPipelinePreflight: Sendable {
    let cache: CoverageTerrainPreflight
    let temporaryWorkspaceBytes: Int64
    let peakPipelineBytes: Int64
    let fitsPipelineQuota: Bool

    static func make(requested: [CoverageTerrainDescriptor], inventory: [CoverageTerrainInventoryEntry],
                     quota: Int64, remainingDownloadBytes: Int64, attempts: Int = 2) throws -> Self {
        let cache = try CoverageTerrainPreflight.make(requested: requested, inventory: inventory,
            quota: quota, remainingDownloadBytes: remainingDownloadBytes, attempts: attempts)
        let existing = Set(inventory.map(\.key))
        // The pinned-set estimate already includes the final/cache-stage decoded copy.
        // Retain the compressed download plus the decoder's private output until publish succeeds.
        let workspace = requested.filter { !existing.contains($0.cacheKey) }
            .map { $0.packedBytes + $0.decodedBytes + 8192 }.max() ?? 0
        let peak = cache.pinnedSetReservationBytes + workspace
        return Self(cache: cache, temporaryWorkspaceBytes: workspace,
                    peakPipelineBytes: peak, fitsPipelineQuota: peak <= quota)
    }
}
