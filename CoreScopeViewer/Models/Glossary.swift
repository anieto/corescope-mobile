import Foundation

/// A term NodeScope explains, named by its entry's id in glossary.json.
enum GlossaryTerm: String, CaseIterable, Sendable {
    case node, companion, repeater, sensor, observer, advert, analyzer
    case roomServer = "room-server"
    case hop, route, flood, observation
    case unresolvedHop = "unresolved-hop"
    case heardBy = "heard-by"
    case alongTheWay = "along-the-way"
    case snr, rssi
    case channel, region
    case lastHeard = "last-heard"
    case clearance
    case lineOfSight = "line-of-sight"
    case fresnelZone = "fresnel-zone"
    case linkMargin = "link-margin"
    case deploymentPlan = "deployment-plan"
    case candidateSite = "candidate-site"
    case planningCoordinates = "planning-coordinates"
    case antennaHeight = "antenna-height"
    case radioFrequency = "radio-frequency"
    case transmitPower = "transmit-power"
    case antennaGain = "antenna-gain"
    case cableLoss = "cable-loss"
    case additionalLoss = "additional-loss"
    case receiverSensitivity = "receiver-sensitivity"
    case planningReserve = "planning-reserve"
    case coverageDetail = "coverage-detail"
    case studyRadius = "study-radius"
}

extension GlossaryTerm {
    /// The entry for a node's role as the analyzer reports it ("repeater", "room", …).
    init?(role: String) {
        switch role.lowercased() {
        case "companion": self = .companion
        case "repeater": self = .repeater
        case "room", "room server", "room_server", "roomserver": self = .roomServer
        case "sensor": self = .sensor
        default: return nil
        }
    }
}

struct GlossaryEntry: Decodable, Identifiable, Hashable, Sendable {
    let id: String
    let term: String
    let summary: String
    /// More for the Glossary screen; the popover shows only the summary.
    let detail: String?
}

struct GlossarySection: Decodable, Identifiable, Hashable, Sendable {
    let title: String
    let entries: [GlossaryEntry]

    var id: String { title }
}

/// The plain-language explanations behind the app's info buttons and Glossary screen. The text is
/// shared with Android (assets/glossary.json); an Android test keeps the two copies identical.
enum Glossary {
    /// Whether ⓘ buttons show beside terms. Settings → Glossary stays available either way.
    static let showsExplanationsDefaultsKey = "showsTermExplanations"

    static let sections: [GlossarySection] = {
        struct Document: Decodable {
            let sections: [GlossarySection]
        }
        guard let url = Bundle.main.url(forResource: "glossary", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let document = try? JSONDecoder().decode(Document.self, from: data) else {
            assertionFailure("glossary.json is missing or invalid")
            return []
        }
        return document.sections
    }()

    static func entry(_ term: GlossaryTerm) -> GlossaryEntry? {
        sections.lazy.flatMap(\.entries).first { $0.id == term.rawValue }
    }
}
