import Foundation
import Observation

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

    var mapLatitude: Double? { mapCenter?.first }
    var mapLongitude: Double? {
        guard let mapCenter, mapCenter.count > 1 else { return nil }
        return mapCenter[1]
    }
}

private struct AnalyzerSourceRegistryDocument: Codable {
    let version: Int
    let sources: [AnalyzerSource]
}

/// Loads the community-maintained analyzer list from GitHub. The bundled
/// document keeps first launch and offline use functional, while an optional
/// `NodeScopeSourceRegistryURL` bundle value can override the default endpoint.
@Observable
@MainActor
final class AnalyzerSourceRegistry {
    private static let cachedDocumentDefaultsKey = "cachedAnalyzerSourceRegistry"
    private static let defaultRemoteRegistryURL = URL(
        string: "https://api.github.com/repos/anieto/corescope-mobile/contents/CommunitySources/us-sources.json?ref=main"
    )

    private(set) var sources: [AnalyzerSource]
    private(set) var isRefreshing = false
    private(set) var lastError: String?

    init() {
        sources = Self.cachedSources ?? Self.bundledSources
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
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let response = response as? HTTPURLResponse,
                  200..<300 ~= response.statusCode else {
                throw URLError(.badServerResponse)
            }

            let document = try JSONDecoder().decode(AnalyzerSourceRegistryDocument.self, from: data)
            let validSources = document.sources.filter { !$0.host.isEmpty && !$0.name.isEmpty }
            guard !validSources.isEmpty else {
                throw DecodingError.dataCorrupted(
                    .init(codingPath: [], debugDescription: "The source registry is empty.")
                )
            }

            sources = validSources
            lastError = nil
            if let encoded = try? JSONEncoder().encode(document) {
                UserDefaults.standard.set(encoded, forKey: Self.cachedDocumentDefaultsKey)
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

    private static var cachedSources: [AnalyzerSource]? {
        guard let data = UserDefaults.standard.data(forKey: cachedDocumentDefaultsKey),
              let document = try? JSONDecoder().decode(AnalyzerSourceRegistryDocument.self, from: data),
              !document.sources.isEmpty else {
            return nil
        }
        return document.sources
    }

    private static var bundledSources: [AnalyzerSource] {
        guard let url = Bundle.main.url(forResource: "us-sources", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let document = try? JSONDecoder().decode(AnalyzerSourceRegistryDocument.self, from: data) else {
            return []
        }
        return document.sources
    }

}
