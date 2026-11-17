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
        guard let translation = ContentTranslation.load(GlossaryTranslation.self, named: "glossary") else {
            return document.sections
        }
        return translation.apply(to: document.sections)
    }()

    static func entry(_ term: GlossaryTerm) -> GlossaryEntry? {
        sections.lazy.flatMap(\.entries).first { $0.id == term.rawValue }
    }
}

/// A translation of glossary.json, in `glossary.<language>.json` beside it. Sections are keyed by
/// their English title and entries by id; anything missing stays in English.
struct GlossaryTranslation: Decodable {
    struct Entry: Decodable {
        let term: String?
        let summary: String?
        let detail: String?
    }

    let sections: [String: String]?
    let entries: [String: Entry]?

    func apply(to sections: [GlossarySection]) -> [GlossarySection] {
        sections.map { section in
            GlossarySection(
                title: self.sections?[section.title].nonEmpty ?? section.title,
                entries: section.entries.map { entry in
                    let translated = entries?[entry.id]
                    return GlossaryEntry(
                        id: entry.id,
                        term: translated?.term.nonEmpty ?? entry.term,
                        summary: translated?.summary.nonEmpty ?? entry.summary,
                        detail: translated?.detail.nonEmpty ?? entry.detail)
                })
        }
    }
}

/// Translations of the bundled JSON content (glossary.json, whats-new.json) and of community
/// source subtitles. The format is shared with Android; see docs/localization-content.md.
enum ContentTranslation {
    /// Language tags to look for, most specific first. Content follows the language the app's
    /// interface is running in, so translated text never appears beside English screens.
    /// English is the source language and has no translation files.
    static var languages: [String] { languages(for: Bundle.main.preferredLocalizations.first ?? "en") }

    static func languages(for tag: String) -> [String] {
        let normalized = tag.replacingOccurrences(of: "_", with: "-")
        guard let base = normalized.split(separator: "-").first.map(String.init),
              base.lowercased() != "en" else { return [] }
        return base == normalized ? [base] : [normalized, base]
    }

    /// The first translation `<name>.<language>.json` in the bundle, for the most specific
    /// matching language.
    static func load<T: Decodable>(_ type: T.Type, named name: String, bundle: Bundle = .main,
                                   languages: [String] = languages) -> T? {
        for language in languages {
            guard let url = bundle.url(forResource: "\(name).\(language)", withExtension: "json"),
                  let data = try? Data(contentsOf: url) else { continue }
            guard let translation = try? JSONDecoder().decode(T.self, from: data) else {
                assertionFailure("\(name).\(language).json is invalid")
                continue
            }
            return translation
        }
        return nil
    }

    /// The value for the most specific matching language in a `{"pt-BR": "…", "pt": "…"}` map.
    static func pick(_ values: [String: String]?, languages: [String] = languages) -> String? {
        guard let values else { return nil }
        for language in languages {
            if let match = values.first(where: { $0.key.caseInsensitiveCompare(language) == .orderedSame }),
               let value = match.value.nonEmpty {
                return value
            }
        }
        return nil
    }
}

private extension Optional where Wrapped == String {
    var nonEmpty: String? {
        guard let self, !self.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return self
    }
}

private extension String {
    var nonEmpty: String? { Optional(self).nonEmpty }
}
