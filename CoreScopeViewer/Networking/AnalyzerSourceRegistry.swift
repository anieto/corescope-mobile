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
    let isDefault: Bool
    let mapCenter: [Double]?
    let mapRadiusKm: Double?
    /// Optional square logo, as a path inside the registry folder
    /// (`icons/<name>.png`), shared with Android.
    let icon: String?
    let type: AnalyzerSourceType?

    var backendType: AnalyzerSourceType { type ?? .coreScope }

    var mapLatitude: Double? { mapCenter?.first }
    var mapLongitude: Double? {
        guard let mapCenter, mapCenter.count > 1 else { return nil }
        return mapCenter[1]
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

    var defaultSource: AnalyzerSource? {
        sources.first(where: \.isDefault) ?? sources.first
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
