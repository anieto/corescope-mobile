import CoreLocation
import Foundation
import Observation

enum AnalyzerSourceType: String, Codable, Sendable {
    case coreScope = "corescope"
    case beacon
}

struct AnalyzerSource: Codable, Hashable, Identifiable {
    let id: String
    let name: String
    let host: String
    let subtitle: String
    /// The preferred source for its country.
    let isDefault: Bool
    let mapCenter: [Double]?
    let mapRadiusKm: Double?
    /// Optional square logo, as a path inside the registry folder
    /// (`icons/<name>.png`), shared with Android.
    let icon: String?
    let type: AnalyzerSourceType?
    /// Optional translated subtitles by language tag, such as `{"pt-BR": "…"}`.
    var subtitles: [String: String]? = nil
    var country: String? = nil
    var location: [Double]? = nil

    var locationCoordinate: CLLocationCoordinate2D? {
        guard let location, location.count == 2 else { return nil }
        let coordinate = CLLocationCoordinate2D(latitude: location[0], longitude: location[1])
        return CLLocationCoordinate2DIsValid(coordinate) ? coordinate : nil
    }

    /// The subtitle in the app's language when the registry has one, else the English `subtitle`.
    var displaySubtitle: String { ContentTranslation.pick(subtitles) ?? subtitle }

    var backendType: AnalyzerSourceType { type ?? .coreScope }

    var mapLatitude: Double? { mapCenter?.first }
    var mapLongitude: Double? {
        guard let mapCenter, mapCenter.count > 1 else { return nil }
        return mapCenter[1]
    }
}

extension AnalyzerSource {
    init(from decoder: Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        id = try fields.decode(String.self, forKey: .id)
        name = try fields.decode(String.self, forKey: .name)
        host = try fields.decode(String.self, forKey: .host)
        subtitle = try fields.decode(String.self, forKey: .subtitle)
        isDefault = try fields.decode(Bool.self, forKey: .isDefault)
        mapCenter = try fields.decodeIfPresent([Double].self, forKey: .mapCenter)
        mapRadiusKm = try fields.decodeIfPresent(Double.self, forKey: .mapRadiusKm)
        icon = try fields.decodeIfPresent(String.self, forKey: .icon)
        type = try fields.decodeIfPresent(AnalyzerSourceType.self, forKey: .type)
        subtitles = try fields.decodeIfPresent([String: String].self, forKey: .subtitles)
        country = AnalyzerSourceChoices.countryCode(try? fields.decode(String.self, forKey: .country))
        location = try? fields.decode([Double].self, forKey: .location)
    }
}

struct AnalyzerSourceChoices {
    struct CountrySection: Identifiable {
        let country: String?
        let name: String?
        let sources: [AnalyzerSource]
        var id: String? { country }
    }

    let sources: [AnalyzerSource]
    let userCountry: String?
    let displayLocale: Locale

    init(sources: [AnalyzerSource], userCountry: String? = Locale.current.region?.identifier,
         displayLocale: Locale = .current) {
        self.sources = sources
        self.userCountry = Self.countryCode(userCountry)
        self.displayLocale = displayLocale
    }

    private static let countryCodes = Set(Locale.Region.isoRegions.map(\.identifier))

    static func countryCode(_ value: String?) -> String? {
        guard let code = value?.uppercased(), code.utf8.count == 2,
              countryCodes.contains(code) else { return nil }
        return code
    }

    var userCountryName: String? {
        userCountry.map { displayLocale.localizedString(forRegionCode: $0) ?? $0 }
    }

    private var localSources: [AnalyzerSource] {
        guard let userCountry else { return [] }
        return sources.filter { Self.countryCode($0.country) == userCountry }
    }

    var customFirst: Bool { localSources.isEmpty }

    var countrySections: [CountrySection] {
        let groups = Dictionary(grouping: sources) { Self.countryCode($0.country) }
        let countries = groups.compactMap { code, entries -> CountrySection? in
            guard let code else { return nil }
            return .init(country: code, name: displayLocale.localizedString(forRegionCode: code) ?? code,
                         sources: entries)
        }.sorted { first, second in
            if first.country == second.country { return false }
            if first.country == userCountry { return true }
            if second.country == userCountry { return false }
            let order = (first.name ?? "").compare(second.name ?? "", options: .caseInsensitive,
                                                    locale: displayLocale)
            return order == .orderedSame ? (first.id ?? "") < (second.id ?? "") : order == .orderedAscending
        }
        guard let other = groups[nil] else { return countries }
        return countries + [.init(country: nil, name: nil, sources: other)]
    }

    func onboardingHost(savedHost: String?, isOnboarded: Bool) -> String? {
        if let savedHost, !savedHost.isEmpty { return savedHost }
        guard !isOnboarded else { return nil }
        let localSources = localSources
        return (localSources.first(where: \.isDefault) ?? localSources.first)?.host
    }

    func nearest(to coordinate: CLLocationCoordinate2D) -> [AnalyzerSource] {
        let origin = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        let ranked: [(index: Int, source: AnalyzerSource, distance: Double)] = sources.enumerated().map { index, source in
            let distance = source.locationCoordinate.map {
                origin.distance(from: CLLocation(latitude: $0.latitude, longitude: $0.longitude))
            }
            return (index: index, source: source, distance: distance ?? .infinity)
        }
        var sorted = ranked.sorted { first, second in
            if first.distance == second.distance { return first.index < second.index }
            return first.distance < second.distance
        }.map(\.source)
        if let meshTexas = sorted.firstIndex(where: { $0.id == "meshtexas" }), meshTexas > 0,
           Self.isInTexas(coordinate) {
            let preferred = sorted.remove(at: meshTexas)
            sorted.insert(preferred, at: 0)
        }
        return sorted
    }

    // U.S. Census Bureau TIGERweb ACS 2024, States 500K (WGS 84).
    private static let texasBoundary: [[[[Double]]]] = {
        guard let url = Bundle.main.url(forResource: "texas-boundary", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([[[[Double]]]].self, from: data)) ?? []
    }()

    private static func isInTexas(_ coordinate: CLLocationCoordinate2D) -> Bool {
        guard CLLocationCoordinate2DIsValid(coordinate) else { return false }
        return texasBoundary.contains { polygon in
            var inside = false
            for ring in polygon {
                for (start, end) in zip(ring, ring.dropFirst()) {
                    guard (start[1] > coordinate.latitude) != (end[1] > coordinate.latitude) else { continue }
                    let crossing = start[0] + (end[0] - start[0]) *
                        (coordinate.latitude - start[1]) / (end[1] - start[1])
                    if coordinate.longitude < crossing { inside.toggle() }
                }
            }
            return inside
        }
    }
}

struct AnalyzerSourceRegistryDocument: Codable {
    let version: Int
    let sources: [AnalyzerSource]

    private enum CodingKeys: String, CodingKey { case version, sources }
    private enum SourceKeys: String, CodingKey { case type }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decode(Int.self, forKey: .version)
        guard version == 1 || version == 2 else {
            throw DecodingError.dataCorruptedError(forKey: .version, in: container,
                                                  debugDescription: "Unsupported source registry version.")
        }
        var entries = try container.nestedUnkeyedContainer(forKey: .sources)
        var accepted: [AnalyzerSource] = []
        var identifiers: Set<String> = []
        while !entries.isAtEnd {
            do {
                let entry = try entries.superDecoder()
                let fields = try entry.container(keyedBy: SourceKeys.self)
                if let rawType = try fields.decodeIfPresent(String.self, forKey: .type),
                   AnalyzerSourceType(rawValue: rawType) == nil {
                    continue
                }
                let source = try AnalyzerSource(from: entry)
                guard !source.host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      !source.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      !source.id.isEmpty,
                      identifiers.insert(source.id).inserted else { continue }
                accepted.append(source)
            } catch {
                // A bad community entry must not hide otherwise valid sources.
                continue
            }
        }
        guard !accepted.isEmpty else {
            throw DecodingError.dataCorruptedError(forKey: .sources, in: container,
                                                  debugDescription: "The source registry has no supported entries.")
        }
        sources = accepted
    }
}

/// Loads the community-maintained analyzer list from GitHub. The bundled
/// document keeps first launch and offline use functional, while an optional
/// `NodeScopeSourceRegistryURL` bundle value can override the default endpoint.
@Observable
@MainActor
final class AnalyzerSourceRegistry {
    private static let cachedDocumentDefaultsKey = "cachedAnalyzerSourceRegistryTypedV2"
    private static let defaultRemoteRegistryURL = URL(
        string: "https://api.github.com/repos/anieto/corescope-mobile/contents/CommunitySources/sources-v2.json?ref=main"
    )

    private(set) var sources: [AnalyzerSource]
    private(set) var isRefreshing = false
    private(set) var lastError: String?
    private let defaults: UserDefaults
    private let session: URLSession

    init(defaults: UserDefaults = .standard, session: URLSession = .shared) {
        self.defaults = defaults
        self.session = session
        sources = defaults.data(forKey: Self.cachedDocumentDefaultsKey)
            .flatMap { try? Self.decodeV2($0).sources } ?? Self.bundledSources
    }

    func refresh() async {
        guard let remoteRegistryURL, !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }

        do {
            let requestURL = cacheBustingURL(for: remoteRegistryURL)
            var request = URLRequest(
                url: requestURL,
                cachePolicy: .reloadIgnoringLocalAndRemoteCacheData
            )
            request.setValue("application/vnd.github.raw+json", forHTTPHeaderField: "Accept")
            request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            request.setValue("no-cache", forHTTPHeaderField: "Pragma")
            request.setValue("NodeScope", forHTTPHeaderField: "User-Agent")
            let (data, response) = try await session.data(for: request)
            guard let response = response as? HTTPURLResponse,
                  200..<300 ~= response.statusCode else {
                throw URLError(.badServerResponse)
            }

            let document = try Self.decodeV2(data)
            sources = document.sources
            lastError = nil
            if let encoded = try? JSONEncoder().encode(document) {
                defaults.set(encoded, forKey: Self.cachedDocumentDefaultsKey)
            }
        } catch {
            lastError = error.localizedDescription
        }
    }

    private var remoteRegistryURL: URL? {
        guard let rawURL = Bundle.main.object(
            forInfoDictionaryKey: "NodeScopeSourceRegistryURL"
        ) as? String,
        !rawURL.isEmpty else {
            return Self.defaultRemoteRegistryURL
        }
        return URL(string: rawURL) ?? Self.defaultRemoteRegistryURL
    }

    private func cacheBustingURL(for url: URL) -> URL {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return url
        }
        var queryItems = components.queryItems ?? []
        queryItems.removeAll { $0.name == "cache_bust" }
        queryItems.append(URLQueryItem(name: "cache_bust", value: UUID().uuidString))
        components.queryItems = queryItems
        return components.url ?? url
    }

    private static func decodeV2(_ data: Data) throws -> AnalyzerSourceRegistryDocument {
        let document = try JSONDecoder().decode(AnalyzerSourceRegistryDocument.self, from: data)
        guard document.version == 2 else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "A version 2 source registry is required."))
        }
        return document
    }

    private static var bundledSources: [AnalyzerSource] {
        guard let url = Bundle.main.url(forResource: "sources-v2", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let document = try? decodeV2(data) else {
            return []
        }
        return document.sources
    }

}
