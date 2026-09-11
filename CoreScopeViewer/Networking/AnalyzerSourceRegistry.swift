import Foundation
import Observation

struct AnalyzerSource: Codable, Hashable, Identifiable {
    let id: String
    let name: String
    let host: String
    let subtitle: String
    let isDefault: Bool
}

private struct AnalyzerSourceRegistryDocument: Codable {
    let version: Int
    let sources: [AnalyzerSource]
}

/// Loads the community-maintained analyzer list. The bundled document keeps
/// first launch and offline use functional; once the GitHub repository is
/// published, its raw JSON URL can be added as `NodeScopeSourceRegistryURL`.
@Observable
@MainActor
final class AnalyzerSourceRegistry {
    private static let cachedDocumentDefaultsKey = "cachedAnalyzerSourceRegistry"

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
            return nil
        }
        return URL(string: rawURL)
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
