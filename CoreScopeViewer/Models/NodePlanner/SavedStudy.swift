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

struct SavedAntenna: Codable, Equatable, Sendable {
    var heightMeters: Double
    var gainDbi: Double
    var lossDb: Double

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
