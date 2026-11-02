import Foundation

/// Version 1 stores proposed sites and explicit assumptions only, never predicted results.
struct DeploymentPlanDocument: Codable, Equatable, Sendable {
    var schemaVersion: Int = 1
    var plans: [DeploymentPlan]

    func validate() throws {
        guard schemaVersion == 1 else { throw DeploymentPlanError.unsupportedVersion }
        guard plans.count <= 100, Set(plans.map(\.id)).count == plans.count else {
            throw DeploymentPlanError.invalidData
        }
        try plans.forEach { try $0.validate() }
    }
}

struct DeploymentPlan: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var name: String
    var createdAtMilliseconds: Int64
    var updatedAtMilliseconds: Int64
    var selectedSiteID: String?
    var sites: [CandidateSite]

    func validate() throws {
        guard PlanValidation.id(id), PlanValidation.name(name),
              createdAtMilliseconds >= 0, updatedAtMilliseconds >= createdAtMilliseconds,
              updatedAtMilliseconds <= 253_402_300_799_999,
              sites.count <= 100, Set(sites.map(\.id)).count == sites.count,
              selectedSiteID == nil || sites.contains(where: { $0.id == selectedSiteID }) else {
            throw DeploymentPlanError.invalidData
        }
        try sites.forEach { try $0.validate() }
    }
}

struct CandidateSite: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var name: String
    var latitude: Double
    var longitude: Double
    var assumptions: CandidateSiteAssumptions

    func validate() throws {
        guard PlanValidation.id(id), PlanValidation.name(name),
              latitude.isFinite, (-90...90).contains(latitude),
              longitude.isFinite, (-180..<180).contains(longitude) else {
            throw DeploymentPlanError.invalidData
        }
        try assumptions.validate()
    }
}

/// Nil means "not specified", not zero or a guessed radio preset.
/// Bounds protect stored input; they do not promise engine support or regulatory compliance.
struct CandidateSiteAssumptions: Codable, Equatable, Sendable {
    var antennaHeightMeters: Double?
    var frequencyMHz: Double?
    var transmitPowerDBm: Double?
    var antennaGainDBi: Double?
    var cableLossDB: Double?
    var additionalLossDB: Double?

    func validate() throws {
        let fields: [(Double?, ClosedRange<Double>)] = [
            (antennaHeightMeters, 0...10_000), (frequencyMHz, 0.001...100_000),
            (transmitPowerDBm, -200...100), (antennaGainDBi, -100...100),
            (cableLossDB, 0...200), (additionalLossDB, 0...200)
        ]
        guard fields.allSatisfy({ value, range in
            value.map { $0.isFinite && range.contains($0) } ?? true
        }) else { throw DeploymentPlanError.invalidData }
    }
}

enum DeploymentPlanError: Error, Equatable {
    case unsupportedVersion
    case invalidData
    case tooLarge
}

private enum PlanValidation {
    static func id(_ value: String) -> Bool {
        guard let uuid = UUID(uuidString: value) else { return false }
        return uuid.uuidString.lowercased() == value
    }

    static func name(_ value: String) -> Bool {
        !value.trimmingCharacters(in: CharacterSet(charactersIn: " \t\r\n")).isEmpty
            && value.utf8.count <= 160 && !value.unicodeScalars.contains { $0.value < 32 || $0.value == 127 }
    }
}

/// Strict decimal parsing shared by the manual editor and its contract tests.
enum CandidateSiteInput {
    static func make(id: String, name: String, values: [String], heightInFeet: Bool = false) throws -> CandidateSite {
        guard values.count == 8 else { throw DeploymentPlanError.invalidData }
        let parsed = try values.map { text -> Double? in
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { return nil }
            let decimal = trimmed.replacingOccurrences(of: ",", with: ".")
            guard decimal.range(of: #"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$"#,
                                options: .regularExpression) != nil,
                  let value = Double(decimal), value.isFinite else { throw DeploymentPlanError.invalidData }
            return value
        }
        guard let latitude = parsed[0], let longitude = parsed[1] else { throw DeploymentPlanError.invalidData }
        let site = CandidateSite(id: id, name: name, latitude: latitude,
            longitude: longitude == 180 ? -180 : longitude,
            assumptions: CandidateSiteAssumptions(antennaHeightMeters: parsed[2].map { heightInFeet ? $0 * 0.3048 : $0 }, frequencyMHz: parsed[3],
                transmitPowerDBm: parsed[4], antennaGainDBi: parsed[5], cableLossDB: parsed[6], additionalLossDB: parsed[7]))
        try site.validate()
        return site
    }
}

/// All disk boundaries must use this codec; synthesized Codable alone does not validate input.
enum DeploymentPlanCodec {
    static let maximumBytes = 4 * 1024 * 1024

    static func decode(_ data: Data) throws -> DeploymentPlanDocument {
        guard data.count <= maximumBytes else { throw DeploymentPlanError.tooLarge }
        let document = try JSONDecoder().decode(DeploymentPlanDocument.self, from: data)
        try document.validate()
        return document
    }

    static func encode(_ document: DeploymentPlanDocument) throws -> Data {
        try document.validate()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(document)
        guard data.count <= maximumBytes else { throw DeploymentPlanError.tooLarge }
        return data
    }
}
