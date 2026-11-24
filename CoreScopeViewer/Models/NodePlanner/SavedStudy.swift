import Foundation

/// Saved inputs with an optional versioned coverage calculation snapshot.
struct SavedStudy: Codable, Equatable, Identifiable, Sendable {
    enum Kind: String, Codable, Sendable { case linkProfile, coverage }
    var id: String
    var name: String
    var createdAtMilliseconds: Int64
    var updatedAtMilliseconds: Int64
    var kind: Kind
    var linkProfile: SavedLinkProfile?
    var coverage: SavedCoverage?
    // Version 3 only (docs/node-planner/saved-plans-v3-schema.md); the store assigns revisions and result references.
    var revision: Int64? = nil
    var planID: String? = nil
    var origin: StudyOrigin? = nil
    var importOrigin: ImportOrigin? = nil
    var resultRef: ResultRef? = nil
    /// Whether a referenced result file could be read on this device. Not stored.
    var resultStatus: SavedResultStatus = .none
    /// A Link Profile's result payload, in memory only: on disk it's a result file. Not stored.
    var linkProfileResult: Data? = nil

    private enum CodingKeys: String, CodingKey {
        case id, name, createdAtMilliseconds, updatedAtMilliseconds, kind, linkProfile, coverage
        case revision, planID, origin, importOrigin, resultRef
    }

    func validate() throws {
        guard PlanValidation.id(id), PlanValidation.name(name),
              createdAtMilliseconds >= 0, updatedAtMilliseconds >= createdAtMilliseconds,
              updatedAtMilliseconds <= 253_402_300_799_999 else { throw DeploymentPlanError.invalidData }
        switch kind {
        case .linkProfile:
            guard let linkProfile, coverage == nil else { throw DeploymentPlanError.invalidData }
            try linkProfile.validate()
        case .coverage:
            guard let coverage, linkProfile == nil else { throw DeploymentPlanError.invalidData }
            try coverage.validate()
        }
    }
}

struct ReportedAntennaSource: Codable, Equatable, Sendable {
    let analyzerHost: String
    let publicKey: String
    let updatedAtMilliseconds: Int64?
    let updatedBy: String?

    func isFromDifferentRepeater(than previous: Self?) -> Bool {
        previous?.analyzerHost != analyzerHost || previous?.publicKey != publicKey
    }

    var draftValue: String? {
        (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) }
    }

    static func fromDraft(_ text: String?) -> Self? {
        guard let text else { return nil }
        return try? JSONDecoder().decode(Self.self, from: Data(text.utf8))
    }
}

extension RepeaterMetadata {
    func antennaSource(host: String) -> ReportedAntennaSource {
        ReportedAntennaSource(
            analyzerHost: AnalyzerSettings.normalizedHost(host), publicKey: pubkey,
            updatedAtMilliseconds: updatedAt.flatMap { Int64(exactly: ($0.timeIntervalSince1970 * 1000).rounded()) },
            updatedBy: updatedBy
        )
    }
}

struct SavedAntenna: Codable, Equatable, Sendable {
    var heightMeters: Double
    var gainDbi: Double
    var lossDb: Double
    var reportedGainSource: ReportedAntennaSource? = nil
    var reportedHeightSource: ReportedAntennaSource? = nil

    func validate() throws {
        guard heightMeters.isFinite, (0...10_000).contains(heightMeters),
              gainDbi.isFinite, lossDb.isFinite, lossDb >= 0 else { throw DeploymentPlanError.invalidData }
    }
}

struct SavedEndpoint: Codable, Equatable, Sendable {
    var name: String?
    var latitude: Double
    var longitude: Double
    var antenna: SavedAntenna

    func validate() throws {
        guard latitude.isFinite, (-90...90).contains(latitude),
              longitude.isFinite, (-180...180).contains(longitude),
              name.map({ $0.utf8.count <= 1024 }) ?? true else { throw DeploymentPlanError.invalidData }
        try antenna.validate()
    }
}

struct SavedLinkProfile: Codable, Equatable, Sendable {
    var pointA: SavedEndpoint
    var pointB: SavedEndpoint
    var frequencyMHz: Double
    var transmitPowerDbm: Double
    var sensitivityDbm: Double
    var refractionK: Double
    /// nil means the tool chooses its normal adaptive sampling.
    var sampleCount: Int?

    func validate() throws {
        try pointA.validate()
        try pointB.validate()
        guard frequencyMHz.isFinite, frequencyMHz > 0, transmitPowerDbm.isFinite,
              sensitivityDbm.isFinite, refractionK.isFinite, refractionK > 0,
              sampleCount.map({ (2...100).contains($0) }) ?? true else { throw DeploymentPlanError.invalidData }
    }
}

struct SavedCoverageNode: Codable, Equatable, Sendable {
    var sourceID: String
    var nodeID: String
    var name: String?
    var role: String
    var latitude: Double
    var longitude: Double
    var lastSeenMilliseconds: Int64?

    func validate() throws {
        guard !sourceID.isEmpty, sourceID.utf8.count <= 1024,
              !nodeID.isEmpty, nodeID.utf8.count <= 1024, role.utf8.count <= 160,
              name.map({ $0.utf8.count <= 1024 }) ?? true,
              latitude.isFinite, (-90...90).contains(latitude),
              longitude.isFinite, (-180...180).contains(longitude),
              lastSeenMilliseconds.map({ (0...253_402_300_799_999).contains($0) }) ?? true else {
            throw DeploymentPlanError.invalidData
        }
    }
}

struct SavedCoverage: Codable, Equatable, Sendable {
    var transmitter: SavedEndpoint
    var receiver: SavedAntenna
    var frequencyMHz: Double
    var transmitPowerDbm: Double
    var sensitivityDbm: Double
    var reserveDb: Double
    var radiusMeters: Int
    var spacingMeters: Int
    var nodes: [SavedCoverageNode]
    var catalogComplete: Bool
    var calculatedResult: Data? = nil

    func validate() throws {
        guard calculatedResult.map({ $0.count <= 8 * 1024 * 1024 }) ?? true else { throw DeploymentPlanError.tooLarge }
        try transmitter.validate()
        try receiver.validate()
        guard (-85...85).contains(transmitter.latitude),
              (1...3000).contains(transmitter.antenna.heightMeters), (1...3000).contains(receiver.heightMeters),
              frequencyMHz.isFinite, (30...6000).contains(frequencyMHz),
              transmitPowerDbm.isFinite, sensitivityDbm.isFinite, reserveDb.isFinite, reserveDb >= 0,
              (1000...100_000).contains(radiusMeters),
              CoverageGrid.supportedSpacings.contains(spacingMeters),
              nodes.count <= 200, Set(nodes.map(\.nodeID)).count == nodes.count else { throw DeploymentPlanError.invalidData }
        var identities = Set<[String]>()
        for node in nodes {
            try node.validate()
            guard identities.insert([node.sourceID, node.nodeID]).inserted else { throw DeploymentPlanError.invalidData }
        }
    }
}

extension PortableInputFingerprint {
    static func linkProfile(_ inputs: SavedLinkProfile) throws -> String {
        func endpoint(_ point: SavedEndpoint) -> [Double] {
            [point.latitude, point.longitude, point.antenna.heightMeters, point.antenna.gainDbi, point.antenna.lossDb]
        }
        return try linkProfile(pointA: endpoint(inputs.pointA), pointB: endpoint(inputs.pointB),
                               frequencyMHz: inputs.frequencyMHz, transmitPowerDbm: inputs.transmitPowerDbm,
                               sensitivityDbm: inputs.sensitivityDbm, refractionK: inputs.refractionK,
                               sampleCount: inputs.sampleCount.map(Int64.init))
    }
}

/// A named collection of studies (version 3). Membership lives only in `SavedStudy.planID`.
struct StudyPlan: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var name: String
    var notes: String?
    var createdAtMilliseconds: Int64
    var updatedAtMilliseconds: Int64
    var revision: Int64?
}

/// Where a study's inputs were captured; nil fields are unknown. A nil origin is "Unknown source".
struct StudyOrigin: Codable, Equatable, Sendable {
    var host: String?
    var region: String?
    var capturedAtMilliseconds: Int64?

    func encode(to encoder: any Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(host, forKey: .host)
        try values.encode(region, forKey: .region)
        try values.encode(capturedAtMilliseconds, forKey: .capturedAtMilliseconds)
    }
}

/// The immediate source of an imported independent copy, not an ancestry chain.
struct ImportOrigin: Codable, Equatable, Sendable {
    var studyID: String
    var revision: Int64
}

/// An immutable result file, named by the SHA-256 of its bytes under CandidateSites/results/.
struct ResultRef: Codable, Equatable, Sendable {
    static let iosLegacy = "legacy.ios-plist-coverage"
    static let androidLegacy = "legacy.android-json-coverage"
    static let portableCoverage = "nodescope.coverage-result"
    static let portableLinkProfile = "nodescope.link-profile-result"

    var format: String
    var version: Int
    var byteLength: Int
    var sha256: String
    var inputFingerprint: String?

    func encode(to encoder: any Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(format, forKey: .format)
        try values.encode(version, forKey: .version)
        try values.encode(byteLength, forKey: .byteLength)
        try values.encode(sha256, forKey: .sha256)
        try values.encode(inputFingerprint, forKey: .inputFingerprint) // written as null, as the schema requires
    }
}

/// What happened when the store looked for a study's result file.
enum SavedResultStatus: Equatable, Sendable {
    /// No result was saved.
    case none
    /// Read and verified; for coverage, its bytes are in calculatedResult.
    case available
    /// Saved result not on this device (for example, after a cloud restore).
    case missing
    /// The file's length or hash doesn't match its reference.
    case unverified
    /// Verified, but in a format this version can't show: another platform's or a newer one.
    case unsupported
}


// Nullable fields must still be present (the schema's "required unless marked optional"); decoding
// with decode(_:forKey:) rather than decodeIfPresent refuses a missing key. In extensions to keep
// the memberwise initializers.
extension StudyOrigin {
    enum CodingKeys: String, CodingKey { case host, region, capturedAtMilliseconds }
    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        host = try values.decode(String?.self, forKey: .host)
        region = try values.decode(String?.self, forKey: .region)
        capturedAtMilliseconds = try values.decode(Int64?.self, forKey: .capturedAtMilliseconds)
    }
}

extension ResultRef {
    enum CodingKeys: String, CodingKey { case format, version, byteLength, sha256, inputFingerprint }
    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        format = try values.decode(String.self, forKey: .format)
        version = try values.decode(Int.self, forKey: .version)
        byteLength = try values.decode(Int.self, forKey: .byteLength)
        sha256 = try values.decode(String.self, forKey: .sha256)
        inputFingerprint = try values.decode(String?.self, forKey: .inputFingerprint)
    }
}
