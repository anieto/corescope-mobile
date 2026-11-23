import CoreLocation
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
        #expect(registry.sources.first?.id == "meshtexas")
        #expect(registry.sources.first?.type == .coreScope)
        let local = AnalyzerSourceChoices(sources: registry.sources, userCountry: "US")
        let visitor = AnalyzerSourceChoices(sources: registry.sources, userCountry: "MX")
        #expect(local.onboardingHost(savedHost: nil, isOnboarded: false) == "analyzer.meshtexas.org")
        #expect(visitor.onboardingHost(savedHost: nil, isOnboarded: false) == nil)
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
        #expect(registry.sources.first?.backendType == .beacon)
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

    @Test func countrySectionsPutTheDeviceCountryFirstAndKeepFileOrder() {
        let sources = [source("br1", "BR"), source("other"), source("us1", "US"),
                       source("ca", "CA"), source("br2", "BR"), source("us2", "US")]
        let locale = Locale(identifier: "en_US")
        let local = AnalyzerSourceChoices(sources: sources, userCountry: "US", displayLocale: locale)
        #expect(!local.customFirst)
        #expect(local.countrySections.map(\.country) == ["US", "BR", "CA", nil])
        #expect(local.countrySections.map { $0.sources.map(\.id) } ==
                [["us1", "us2"], ["br1", "br2"], ["ca"], ["other"]])
        let visitor = AnalyzerSourceChoices(sources: sources, userCountry: "MX", displayLocale: locale)
        #expect(visitor.customFirst)
        #expect(visitor.countrySections.map(\.country) == ["BR", "CA", "US", nil])
    }

    @Test func nearestSortKeepsTiesAndUnlocatedEntriesInFileOrder() {
        let sources = [source("missing"), source("far", location: [0, 40]),
                       source("invalid", location: [100, 0]), source("near1", location: [0, 1]),
                       source("near2", location: [0, -1]), source("incomplete", location: [1]),
                       source("nonfinite", location: [.infinity, 0])]
        let choices = AnalyzerSourceChoices(sources: sources)
        #expect(choices.nearest(to: .init(latitude: 0, longitude: 0)).map(\.id) ==
                ["near1", "near2", "far", "missing", "invalid", "incomplete", "nonfinite"])
    }

    @Test func nearestSortPrefersMeshTexasOnlyInsideTexas() {
        let sources = [source("meshtexas", "US", location: [30.6, -96.9]),
                       source("comchan", "US", location: [30.27, -97.74]),
                       source("nearby", "US", location: [30.28, -97.74]),
                       source("arkansas", "US", location: [33.45, -94.02])]
        let choices = AnalyzerSourceChoices(sources: sources)
        #expect(choices.nearest(to: .init(latitude: 30.27, longitude: -97.74)).map(\.id) ==
                ["meshtexas", "comchan", "nearby", "arkansas"])
        #expect(choices.nearest(to: .init(latitude: 33.45, longitude: -94.02)).first?.id == "arkansas")
        let withoutMeshTexas = AnalyzerSourceChoices(sources: Array(sources.dropFirst()))
        #expect(withoutMeshTexas.nearest(to: .init(latitude: 30.27, longitude: -97.74)).map(\.id) ==
                ["comchan", "nearby", "arkansas"])
    }

    @Test func onboardingRespectsCountryDefaultsAndSavedHosts() {
        let sources = [source("us", "US", isDefault: true), source("ca1", "CA"),
                       source("ca2", "CA", isDefault: true), source("br1", "BR"), source("br2", "BR")]
        let canada = AnalyzerSourceChoices(sources: sources, userCountry: "CA")
        let brazil = AnalyzerSourceChoices(sources: sources, userCountry: "BR")
        let mexico = AnalyzerSourceChoices(sources: sources, userCountry: "MX")
        #expect(canada.onboardingHost(savedHost: nil, isOnboarded: false) == "ca2.example")
        #expect(brazil.onboardingHost(savedHost: nil, isOnboarded: false) == "br1.example")
        #expect(mexico.onboardingHost(savedHost: nil, isOnboarded: false) == nil)
        #expect(canada.onboardingHost(savedHost: "saved.example", isOnboarded: false) == "saved.example")
        #expect(canada.onboardingHost(savedHost: "saved.example", isOnboarded: true) == "saved.example")
        #expect(canada.onboardingHost(savedHost: nil, isOnboarded: true) == nil)
    }

    @Test func newMetadataIsOptionalAndMalformedValuesDoNotDropEntries() throws {
        let json = """
        {"version":2,"sources":[
          {"id":"old","name":"Old","host":"old.example","subtitle":"","isDefault":false},
          {"id":"bad","name":"Bad","host":"bad.example","subtitle":"","isDefault":false,
           "country":42,"location":[10,"wrong"]},
          {"id":"new","name":"New","host":"new.example","subtitle":"","isDefault":true,
           "country":"us","location":[10,20],"mapCenter":[30,40],"mapRadiusKm":100}
        ]}
        """
        let document = try JSONDecoder().decode(AnalyzerSourceRegistryDocument.self, from: Data(json.utf8))
        #expect(document.sources.map(\.id) == ["old", "bad", "new"])
        for source in document.sources.prefix(2) {
            #expect(source.country == nil)
            #expect(source.locationCoordinate == nil)
        }
        let valid = try #require(document.sources.last)
        #expect(valid.country == "US")
        #expect(valid.locationCoordinate?.longitude == 20)
        #expect(valid.mapLongitude == 40)
        #expect(valid.mapRadiusKm == 100)
        let cached = try JSONDecoder().decode(AnalyzerSourceRegistryDocument.self,
                                              from: JSONEncoder().encode(document))
        #expect(cached.sources == document.sources)
    }

    private func source(_ id: String, _ country: String? = nil, location: [Double]? = nil,
                        isDefault: Bool = false) -> AnalyzerSource {
        AnalyzerSource(id: id, name: id, host: "\(id).example", subtitle: "", isDefault: isDefault,
                       mapCenter: nil, mapRadiusKm: nil, icon: nil, type: nil,
                       country: country, location: location)
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
