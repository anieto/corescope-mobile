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
        string: "https://raw.githubusercontent.com/anieto/corescope-mobile/main/CommunitySources/us-sources.json"
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
        guard let remoteRegistryURL else { return }
        isRefreshing = true
        defer { isRefreshing = false }

        do {
            let (data, response) = try await URLSession.shared.data(from: remoteRegistryURL)
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
