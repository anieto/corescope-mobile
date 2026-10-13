import Foundation
import Testing
@testable import CoreScopeViewer

/// Mirrors Android's `SourceIconsTest`: only registry icon paths are accepted,
/// and every icon the registry names exists.
struct AnalyzerSourceLogoTests {
    @Test @MainActor func bundledV2SurvivesUnpublishedEndpointAndLegacyCache() async throws {
        let suite = "RegistryFallback-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let legacy = Data(#"{"version":1,"sources":[{"id":"old","name":"Old","host":"old.example","subtitle":"","isDefault":true}]}"#.utf8)
        defaults.set(legacy, forKey: "cachedAnalyzerSourceRegistryTypedV2")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RegistryFixtureProtocol.self]
        configuration.httpAdditionalHeaders = ["X-Registry-Status": "404"]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let registry = AnalyzerSourceRegistry(defaults: defaults, session: session)
        #expect(registry.defaultSource?.id == "meshtexas")
        #expect(registry.defaultSource?.type == .coreScope)
        let original = registry.sources
        await registry.refresh()
        #expect(registry.sources == original)
        #expect(registry.lastError != nil)
    }

    @Test @MainActor func registryRefreshPersistsAndFailedRefreshRetainsLastGoodList() async throws {
        let suite = "RegistryRefresh-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RegistryFixtureProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let registry = AnalyzerSourceRegistry(defaults: defaults, session: session)
        await registry.refresh()
        #expect(registry.lastError == nil)
        #expect(registry.defaultSource?.backendType == .beacon)
        #expect(registry.sources.count == 1)
        for status in ["404", "429", "503", "legacy", "malformed"] {
            let failingConfiguration = URLSessionConfiguration.ephemeral
            failingConfiguration.protocolClasses = [RegistryFixtureProtocol.self]
            failingConfiguration.httpAdditionalHeaders = ["X-Registry-Status": status]
            let failingSession = URLSession(configuration: failingConfiguration)
            defer { failingSession.invalidateAndCancel() }
            let restored = AnalyzerSourceRegistry(defaults: defaults, session: failingSession)
            #expect(restored.sources == registry.sources)
            await restored.refresh()
            #expect(restored.lastError != nil)
            #expect(restored.sources == registry.sources)
            #expect(AnalyzerSourceRegistry(defaults: defaults).sources == registry.sources)
        }
    }

    @Test func versionTwoFiltersUnknownAndMalformedEntriesAndDefaultsMissingType() throws {
        let data = Data(#"{"version":2,"sources":[{"id":"a","type":"beacon","name":"A","host":"a.example","subtitle":"","isDefault":false},{"type":"future"},{"id":"broken","type":"beacon","name":7},{"id":"missing","name":"Missing","host":"missing.example","subtitle":"","isDefault":false}]}"#.utf8)
        let document = try JSONDecoder().decode(AnalyzerSourceRegistryDocument.self, from: data)
        #expect(document.sources.count == 2)
        #expect(document.sources.first?.backendType == .beacon)
        #expect(document.sources.last?.id == "missing")
        #expect(document.sources.last?.backendType == .coreScope)
        let restored = try JSONDecoder().decode(AnalyzerSourceRegistryDocument.self,
                                               from: JSONEncoder().encode(document))
        #expect(restored.sources == document.sources)
    }

    @Test func legacyRegistryRemainsCoreScope() throws {
        let data = Data(#"{"version":1,"sources":[{"id":"a","name":"A","host":"a.example","subtitle":"","isDefault":true}]}"#.utf8)
        let document = try JSONDecoder().decode(AnalyzerSourceRegistryDocument.self, from: data)
        #expect(document.sources.first?.backendType == .coreScope)
    }

    @Test func unusableRegistriesAreRejected() {
        for json in [
            #"{"version":3,"sources":[]}"#,
            #"{"version":2,"sources":[{"type":"future"}]}"#,
            #"{"version":2,"sources":[{"id":"a","type":"beacon","name":"A","host":"  ","subtitle":"","isDefault":false}]}"#
        ] {
            #expect(throws: (any Error).self) {
                try JSONDecoder().decode(AnalyzerSourceRegistryDocument.self, from: Data(json.utf8))
            }
        }
    }

    @Test func onlyRegistryIconPathsAreAccepted() {
        #expect(AnalyzerSourceLogo.iconPath("icons/meshtexas.png") == "icons/meshtexas.png")
        #expect(AnalyzerSourceLogo.iconPath(" icons/gulf-coast-mesh.png ") == "icons/gulf-coast-mesh.png")
        for rejected in [nil, "", "https://example.org/logo.png", "//example.org/a.png", "icons/../secret.png",
                         "icons/a/b.png", "icons/Logo.png", "icons/logo.svg", "logo.png"] {
            #expect(AnalyzerSourceLogo.iconPath(rejected) == nil)
        }
    }

    @Test func iconIsOptionalInTheRegistry() throws {
        let withIcon = #"{"id":"a","name":"A","host":"a.example","subtitle":"","isDefault":false,"icon":"icons/a.png"}"#
        let without = #"{"id":"b","name":"B","host":"b.example","subtitle":"","isDefault":false}"#
        #expect(try JSONDecoder().decode(AnalyzerSource.self, from: Data(withIcon.utf8)).icon == "icons/a.png")
        #expect(try JSONDecoder().decode(AnalyzerSource.self, from: Data(without.utf8)).icon == nil)
    }

    @Test func coloradoBeaconPublishesStatewideInitialViewport() throws {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let data = try Data(contentsOf: repositoryRoot.appendingPathComponent("CommunitySources/sources-v2.json"))
        let document = try JSONDecoder().decode(AnalyzerSourceRegistryDocument.self, from: data)
        let colorado = try #require(document.sources.first { $0.id == "colorado-mesh" })

        #expect(colorado.backendType == .beacon)
        #expect(colorado.mapLatitude == 39.0)
        #expect(colorado.mapLongitude == -105.5)
        #expect(colorado.mapRadiusKm == 400)
    }
}

private final class RegistryFixtureProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let url = request.url, url.path.hasSuffix("/sources-v2.json") else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let mode = request.value(forHTTPHeaderField: "X-Registry-Status") ?? "200"
        let version = mode == "legacy" ? 1 : 2
        let body = mode == "malformed" ? "invalid JSON" :
            """
            {"version":\(version),"sources":[{"id":"fixture","type":"beacon","name":"Fixture","host":"fixture.example","subtitle":"","isDefault":true},{"type":"future"}]}
            """
        guard let response = HTTPURLResponse(url: url, statusCode: Int(mode) ?? 200,
                                             httpVersion: nil, headerFields: nil) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}
