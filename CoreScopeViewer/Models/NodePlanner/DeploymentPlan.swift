import Foundation

/// Version 1 holds candidate sites; version 2 adds typed studies with optional coverage result snapshots.
struct DeploymentPlanDocument: Codable, Equatable, Sendable {
    var schemaVersion: Int = 1
    var plans: [DeploymentPlan]
    var studies: [SavedStudy] = []
    /// Version 3 only (docs/node-planner/saved-plans-v3-schema.md).
    var documentRevision: Int64?
    var studyPlans: [StudyPlan] = []

    init(schemaVersion: Int = 1, plans: [DeploymentPlan], studies: [SavedStudy] = [],
         documentRevision: Int64? = nil, studyPlans: [StudyPlan] = []) {
        self.schemaVersion = schemaVersion
        self.plans = plans
        self.studies = studies
        self.documentRevision = documentRevision
        self.studyPlans = studyPlans
    }

    private enum CodingKeys: String, CodingKey { case schemaVersion, plans, studies, documentRevision, studyPlans }

    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try values.decode(Int.self, forKey: .schemaVersion)
        guard (1...3).contains(schemaVersion) else { throw DeploymentPlanError.unsupportedVersion }
        plans = try values.decode([DeploymentPlan].self, forKey: .plans)
        studies = schemaVersion >= 2
            ? try values.decode([SavedStudy].self, forKey: .studies)
            : try values.decodeIfPresent([SavedStudy].self, forKey: .studies) ?? []
        if schemaVersion == 3 {
            documentRevision = try values.decode(Int64.self, forKey: .documentRevision)
            studyPlans = try values.decode([StudyPlan].self, forKey: .studyPlans)
        }
    }

    func encode(to encoder: any Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(schemaVersion, forKey: .schemaVersion)
        try values.encode(plans, forKey: .plans)
        if schemaVersion >= 2 { try values.encode(studies, forKey: .studies) }
        if schemaVersion == 3 {
            try values.encode(documentRevision, forKey: .documentRevision)
            try values.encode(studyPlans, forKey: .studyPlans)
        }
    }

    func validate() throws {
        guard (1...3).contains(schemaVersion) else { throw DeploymentPlanError.unsupportedVersion }
        if schemaVersion == 3 { return try validateVersion3() }
        guard documentRevision == nil, studyPlans.isEmpty, plans.allSatisfy({ $0.revision == nil }),
              studies.allSatisfy({ $0.revision == nil && $0.planID == nil && $0.origin == nil
                  && $0.importOrigin == nil && $0.resultRef == nil }) else { throw DeploymentPlanError.invalidData }
        guard (schemaVersion == 2 || studies.isEmpty), studies.count <= 100,
              Set(studies.map(\.id)).count == studies.count else { throw DeploymentPlanError.invalidData }
        try studies.forEach { try $0.validate() }
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
    /// Version 3 only; the store assigns it.
    var revision: Int64? = nil

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

enum PlanValidation {
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
    static let maximumBytes = 32 * 1024 * 1024
    /// Version 3 keeps results in their own files, so its manifest has a smaller cap.
    static let maximumVersion3Bytes = 16 * 1024 * 1024

    static func decode(_ data: Data) throws -> DeploymentPlanDocument {
        guard data.count <= maximumBytes else { throw DeploymentPlanError.tooLarge }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = object["schemaVersion"] as? Int else { throw DeploymentPlanError.invalidData }
        guard (1...3).contains(version) else { throw DeploymentPlanError.unsupportedVersion }
        if version == 3 {
            guard data.count <= maximumVersion3Bytes else { throw DeploymentPlanError.tooLarge }
            // Results live in result files from version 3 on; an inline one means the file is malformed.
            let studies = object["studies"] as? [[String: Any]] ?? []
            guard !studies.contains(where: { ($0["coverage"] as? [String: Any])?["calculatedResult"] != nil }) else {
                throw DeploymentPlanError.invalidData
            }
        }
        let document: DeploymentPlanDocument
        do { document = try JSONDecoder().decode(DeploymentPlanDocument.self, from: data) }
        catch let error as DeploymentPlanError { throw error }
        catch { throw DeploymentPlanError.invalidData }
        try document.validate()
        if version == 3 {
            // Synthesized Decodable ignores unknown keys; version 3 refuses them at every level.
            let known = try JSONSerialization.jsonObject(with: JSONEncoder().encode(document))
            guard onlyKnownKeys(object, known) else { throw DeploymentPlanError.invalidData }
        }
        return document
    }

    static func encode(_ document: DeploymentPlanDocument) throws -> Data {
        try document.validate()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(document)
        guard data.count <= (document.schemaVersion == 3 ? maximumVersion3Bytes : maximumBytes) else {
            throw DeploymentPlanError.tooLarge
        }
        return data
    }

    /// Every key in `stored` appears in `known`, the same document as this build writes it. A null
    /// stored value may be absent from `known`, since optional values are omitted when encoding.
    private static func onlyKnownKeys(_ stored: Any, _ known: Any) -> Bool {
        if let stored = stored as? [String: Any] {
            guard let known = known as? [String: Any] else { return false }
            return stored.allSatisfy { key, value in
                guard let match = known[key] else { return value is NSNull }
                return onlyKnownKeys(value, match)
            }
        }
        if let stored = stored as? [Any] {
            guard let known = known as? [Any], known.count == stored.count else { return false }
            return zip(stored, known).allSatisfy(onlyKnownKeys)
        }
        return true
    }
}

extension DeploymentPlanDocument {
    static let maximumRevision = Int64.max

    func validateVersion3() throws {
        func check(_ condition: Bool) throws { if !condition { throw DeploymentPlanError.invalidData } }
        func validTime(_ created: Int64, _ updated: Int64) -> Bool {
            created >= 0 && updated >= created && updated <= 253_402_300_799_999
        }
        let sha = #"^[0-9a-f]{64}$"#
        func isSHA(_ value: String) -> Bool { value.range(of: sha, options: .regularExpression) != nil }
        try check((documentRevision ?? 0) >= 1)
        try check(plans.count <= 100 && studyPlans.count <= 100 && studies.count <= 100)
        try check(Set(plans.map(\.id)).count == plans.count && Set(studyPlans.map(\.id)).count == studyPlans.count
            && Set(studies.map(\.id)).count == studies.count)
        for plan in plans {
            try check((plan.revision ?? 0) >= 1)
            try plan.validate()
        }
        for plan in studyPlans {
            try check(PlanValidation.id(plan.id) && PlanValidation.name(plan.name) && (plan.revision ?? 0) >= 1
                && (plan.notes.map { $0.utf8.count <= 4096 } ?? true)
                && validTime(plan.createdAtMilliseconds, plan.updatedAtMilliseconds))
        }
        let planIDs = Set(studyPlans.map(\.id))
        for study in studies {
            try study.validate()
            try check((study.revision ?? 0) >= 1 && (study.planID.map(planIDs.contains) ?? true))
            if let origin = study.origin {
                try check((origin.host.map { $0.utf8.count <= 253 } ?? true) && (origin.region.map { $0.utf8.count <= 64 } ?? true)
                    && (origin.capturedAtMilliseconds.map { (0...253_402_300_799_999).contains($0) } ?? true))
            }
            if let imported = study.importOrigin { try check(PlanValidation.id(imported.studyID) && imported.revision >= 1) }
            if let ref = study.resultRef {
                let formats: Set<String> = study.kind == .coverage
                    ? [ResultRef.androidLegacy, ResultRef.iosLegacy, ResultRef.portableCoverage] : [ResultRef.portableLinkProfile]
                let portable = ref.format.hasPrefix("nodescope.")
                try check(formats.contains(ref.format) && ref.version == 1 && (1...8 * 1024 * 1024).contains(ref.byteLength)
                    && isSHA(ref.sha256) && portable == (ref.inputFingerprint != nil) && (ref.inputFingerprint.map(isSHA) ?? true))
            }
        }
    }
}
