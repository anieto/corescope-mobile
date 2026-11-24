import Foundation
import Testing
@testable import CoreScopeViewer

@Suite(.serialized)
struct CoreScopeAccountSyncTests {
    private let a = String(repeating: "a", count: 64)
    private let b = String(repeating: "b", count: 64)
    private let c = String(repeating: "c", count: 64)
    private let d = String(repeating: "d", count: 64)

    @Test func notificationsDecodePushRelayWithLegacyFallback() throws {
        let current = #"{"relayOrigin":"https://push.nodescope.app","analyzerId":"current","contractVersion":1,"platforms":["ios"]}"#
        let legacy = #"{"relayOrigin":"https://push.nodescope.app","analyzerId":"legacy","contractVersion":1,"platforms":["ios"]}"#
        let cases: [(String, String?)] = [
            (#","pushRelay":"# + current, "current"),
            (#","nodeScopePush":"# + legacy, "legacy"),
            (#","pushRelay":"# + current + #","nodeScopePush":"# + legacy, "current"),
            ("", nil),
            (#","pushRelay":null,"nodeScopePush":"# + legacy, nil),
        ]
        for (fields, expected) in cases {
            let body = #"{"enabled":true,"watches":[{"pubkey":"abc","name":"Roof"}]"# + fields + "}"
            let notifications = try JSONDecoder().decode(CoreScopeAccountNotifications.self, from: Data(body.utf8))
            #expect(notifications.enabled)
            #expect(notifications.watches.map(\.name) == ["Roof"])
            #expect(notifications.pushRelay?.analyzerId == expected, "Fields: \(fields)")
        }
    }

    private func settings(_ keys: [String], claimed: [String] = []) throws -> CoreScopeAccountSettings {
        let own = claimed.map { ["pubkey": $0, "name": "Roof", "future": "preserve"] }
        return CoreScopeAccountSettings(revision: 1, generation: "g", doc: .init(v: 1, keys: [
            "meshcore-favorites": String(decoding: try JSONEncoder().encode(keys), as: UTF8.self),
            "meshcore-my-nodes": String(decoding: try JSONEncoder().encode(own), as: UTF8.self),
            "meshcore-theme": "dark"
        ]), allowlist: [.init(key: "meshcore-favorites", kind: "set", id: nil),
                        .init(key: "meshcore-my-nodes", kind: "set", id: "pubkey")])
    }

    @Test @MainActor func accountStorageSeparatesUsersAndSourcesAndPurgesOnlySignedOutAccount() throws {
        let suite = "account-isolation-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = CoreScopeAccountSelectionStore(defaults: defaults)
        let first = try CoreScopeAccountSelectionState(remote: settings([a]))
        let second = try CoreScopeAccountSelectionState(remote: settings([b]))
        try store.save(first, origin: "https://one/", userID: 1)
        try store.save(second, origin: "https://one/", userID: 2)
        #expect(try store.load(origin: "https://two/", userID: 1) == nil)
        #expect(try store.load(origin: "https://one/", userID: 1)?.favorites == [a])
        #expect(try store.load(origin: "https://one/", userID: 2)?.favorites == [b])
        store.remove(origin: "https://one/", userID: 1)
        #expect(try store.load(origin: "https://one/", userID: 1) == nil)
        #expect(try store.load(origin: "https://one/", userID: 2)?.favorites == [b])
    }

    @Test func resetRequiresExplicitResolutionAndKeepsPendingEdits() throws {
        var state = try CoreScopeAccountSelectionState(remote: settings([a]))
        state.favorites.insert(b)
        let original = try settings([c])
        let reset = CoreScopeAccountSettings(revision: 1, generation: "new-generation", doc: original.doc, allowlist: original.allowlist)
        #expect(throws: CoreScopeAccountError.self) { try state.refresh(reset) }
        #expect(state.favorites == [a, b])
        #expect(state.baseline.generation == "g")
        #expect(throws: CoreScopeAccountError.self) {
            try CoreScopeFavoriteMerge.document(base: state.baseline, local: state.favorites, remote: reset)
        }
        var restored = state
        try restored.restore(onto: reset)
        #expect(restored.favorites == [a, b, c])
        #expect(restored.baseline.generation == "new-generation")
        let accepted = try CoreScopeAccountSelectionState(remote: reset)
        #expect(accepted.favorites == [c])
    }

    @Test func refreshPreservesPendingEditsAndDoesNotResurrectRemoteDeletions() throws {
        var state = try CoreScopeAccountSelectionState(remote: settings([a, b]))
        state.favorites.remove(a)
        state.favorites.insert(d)
        try state.refresh(settings([a, c]))
        #expect(state.favorites == [c, d])
        try state.refresh(settings([a, c]))
        #expect(state.favorites == [c, d])
    }

    @Test func removingAFavoriteFiltersBothKeysAndPreservesOtherMyNodeFields() throws {
        let remote = try settings([a, c], claimed: [a, b])
        var state = try CoreScopeAccountSelectionState(remote: remote)
        #expect(state.favoriteKeys == [a, b, c])
        #expect(state.myNodeNames[b] == "Roof")
        state.removeFavorite(a.uppercased())
        #expect(state.favoriteKeys == [b, c])
        #expect(state.hasPendingEdits)
        try state.refresh(remote)
        let (document, selection) = try CoreScopeFavoriteMerge.document(base: state.baseline, local: state.favorites,
            remote: remote, removingMyNodes: state.removedMyNodes ?? [])
        #expect(selection.favorites == [c])
        #expect(selection.myNodes == [b: "Roof"])
        let kept = try #require(document.keys["meshcore-my-nodes"])
        let rows = try JSONDecoder().decode([[String: String]].self, from: Data(kept.utf8))
        #expect(rows == [["pubkey": b, "name": "Roof", "future": "preserve"]])
        #expect(document.keys["meshcore-theme"] == "dark")
        let accepted = CoreScopeAccountSettings(revision: 2, generation: remote.generation,
            doc: document, allowlist: remote.allowlist)
        try state.acknowledge(accepted, sentFavorites: state.favorites)
        #expect(!state.hasPendingEdits)
    }

    @Test func writesAreThrottledAcrossClientsIncludingFailedAttempts() async throws {
        let gate = CoreScopeAccountWriteGate()
        let now = Date(timeIntervalSince1970: 1_000)
        try await gate.reserve(origin: "https://one/", now: now)
        await #expect(throws: CoreScopeAccountError.self) {
            try await gate.reserve(origin: "https://one/", now: now.addingTimeInterval(59))
        }
        try await gate.reserve(origin: "https://two/", now: now)
        try await gate.reserve(origin: "https://one/", now: now.addingTimeInterval(60))
    }

    @Test @MainActor func controllerRejectsChangedAccountBeforeReadingOrUploadingSelections() async throws {
        let fixture = AccountFixture()
        AccountFixtureProtocol.fixture = fixture
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountFixtureProtocol.self]
        let origin = try #require(URL(string: "https://switched-account.fixture/"))
        let client = try CoreScopeAccountClient(origin: origin, configuration: configuration, cookie: "synthetic")
        let staleUser = CoreScopeAccountUser(id: 8, email: "other@example.test", displayName: "Other", csrfToken: "old")
        let model = CoreScopeAccountModel(client: client, origin: origin.absoluteString, user: staleUser)
        await model.sync(upload: true)
        #expect(model.user == nil)
        #expect(model.selection == nil)
        #expect(fixture.requests.map { $0.url?.path } == ["/api/auth/me"])
    }

    @Test @MainActor func controllerPausesResetAndRetainsPendingEditsAfterConflict() async throws {
        let suite = "account-controller-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = CoreScopeAccountSelectionStore(defaults: defaults)
        let fixture = AccountFixture()
        AccountFixtureProtocol.fixture = fixture
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountFixtureProtocol.self]
        let origin = try #require(URL(string: "https://controller-\(UUID().uuidString.lowercased()).fixture/"))
        let client = try CoreScopeAccountClient(origin: origin, configuration: configuration, cookie: "synthetic")
        let user = CoreScopeAccountUser(id: 7, email: "fixture@example.test", displayName: "Test", csrfToken: "csrf-fixture")
        var state = try CoreScopeAccountSelectionState(remote: settings([a]))
        state.favorites.insert(b)
        try store.save(state, origin: origin.absoluteString, userID: 7)
        let remote = try settings([c])
        fixture.response = try JSONEncoder().encode(CoreScopeAccountSettings(revision: 1, generation: "reset",
            doc: remote.doc, allowlist: remote.allowlist))
        let model = CoreScopeAccountModel(store: store, client: client, origin: origin.absoluteString, user: user)
        await model.sync(upload: true)
        #expect(model.resetDocument != nil)
        #expect(model.selection?.favorites == [a, b])
        #expect(!fixture.requests.contains { $0.httpMethod == "PUT" })
        await model.resolveReset(restore: true)
        #expect(model.selection?.favorites == [a, b, c])
        fixture.putCode = 409
        await model.sync(upload: true)
        #expect(fixture.requests.filter { $0.httpMethod == "PUT" }.count == 1)
        #expect(try store.load(origin: origin.absoluteString, userID: 7)?.favorites == [a, b, c])
        await model.sync(upload: true)
        #expect(fixture.requests.filter { $0.httpMethod == "PUT" }.count == 1)
        await model.logout()
        #expect(try store.load(origin: origin.absoluteString, userID: 7) == nil)
        #expect(model.user == nil)
    }

    @Test @MainActor func accountFavoritesReplaceDeviceMembershipAndStayOnSignOut() async throws {
        let suite = "unified-favorites-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let accounts = CoreScopeAccountSelectionStore(defaults: defaults)
        let favorites = FavoritesStore(defaults: defaults, accountStore: accounts)
        let origin = try #require(URL(string: "https://unified-\(UUID().uuidString.lowercased()).fixture/"))
        let source = try #require(origin.host)
        favorites.applyAccountNodes([a], names: [a: "Device node"], source: source)
        favorites.applyAccountNodes([a], names: [:], source: "another.fixture")
        try accounts.save(CoreScopeAccountSelectionState(remote: settings([b], claimed: [b, c])),
            origin: origin.absoluteString, userID: 7)
        accounts.activate(origin: origin.absoluteString, userID: 7)
        #expect(Set(favorites.items.filter { $0.source == source }.map(\.entityID)) == [b, c])
        #expect(favorites.items.first { $0.entityID == c }?.title == "Roof")
        let shared = try #require(favorites.items.first { $0.entityID == b })
        favorites.remove(shared)
        #expect(!favorites.contains(kind: .node, entityID: b, source: source))
        let added = MeshNode(publicKey: d, name: "Added in app", role: "unknown", lat: nil, lon: nil,
            lastSeen: nil, firstSeen: nil, advertCount: nil, hashSize: nil, hashSizeInconsistent: nil,
            lastHeard: nil, defaultScope: nil)
        favorites.toggle(node: added, source: source)
        #expect(try accounts.load(origin: origin.absoluteString, userID: 7)?.favorites == [d])
        #expect(favorites.nodeKeys(source: source) == [a])

        let fixture = AccountFixture()
        AccountFixtureProtocol.fixture = fixture
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [AccountFixtureProtocol.self]
        let client = try CoreScopeAccountClient(origin: origin, configuration: config, cookie: "synthetic")
        let user = CoreScopeAccountUser(id: 7, email: "fixture@example.test", displayName: "Test", csrfToken: "csrf-fixture")
        let model = CoreScopeAccountModel(store: accounts, client: client, origin: origin.absoluteString,
            source: source, user: user, favorites: favorites)
        await model.logout()

        #expect(model.user == nil)
        #expect(favorites.nodeKeys(source: source) == [c, d])
        #expect(favorites.nodeKeys(source: "another.fixture") == [a])
        let restarted = FavoritesStore(defaults: defaults, accountStore: CoreScopeAccountSelectionStore(defaults: defaults))
        #expect(restarted.nodeKeys(source: source) == [c, d])
        #expect(restarted.items.first { $0.entityID == c }?.title == "Roof")
    }

    @Test @MainActor func signInOfferIsRememberedPerAccountAndSource() throws {
        let suite = "favorites-offer-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let accounts = CoreScopeAccountSelectionStore(defaults: defaults)
        let origin = "https://offer.fixture/"
        var state = try CoreScopeAccountSelectionState(remote: settings([a], claimed: [b]))
        let device: Set<String> = [a, b, c]
        let offer = accounts.offeredDeviceFavorites(device, state: state, origin: origin, userID: 7)
        #expect(offer == [c])
        state.favorites.formUnion(offer)
        #expect(state.favorites == [a, c])
        #expect(state.myNodeNames == [b: "Roof"])
        accounts.finishDeviceFavoritesOffer(origin: origin, userID: 7)
        accounts.finishDeviceFavoritesOffer(origin: origin, userID: 8)
        let restarted = CoreScopeAccountSelectionStore(defaults: defaults)
        #expect(restarted.offeredDeviceFavorites([d], state: state, origin: origin, userID: 7).isEmpty)
        #expect(restarted.offeredDeviceFavorites([d], state: state, origin: origin, userID: 8).isEmpty)
        #expect(restarted.offeredDeviceFavorites([d], state: state, origin: origin, userID: 9) == [d])
        #expect(restarted.offeredDeviceFavorites([d], state: state, origin: "https://other.fixture/", userID: 7) == [d])
    }

    @Test @MainActor func editsDuringSettingsReadAndWriteRemainPending() async throws {
        let suite = "inflight-edits-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = CoreScopeAccountSelectionStore(defaults: defaults)
        let origin = try #require(URL(string: "https://inflight-\(UUID().uuidString.lowercased()).fixture/"))
        let source = AnalyzerSettings.normalizedHost(origin.absoluteString)
        let fixture = AccountFixture()
        AccountFixtureProtocol.fixture = fixture
        fixture.response = try JSONEncoder().encode(settings([a, b]))
        fixture.putResponse = Data(#"{"revision":2,"generation":"g"}"#.utf8)
        var initial = try CoreScopeAccountSelectionState(remote: settings([a, b]))
        initial.favorites.insert(c)
        try store.save(initial, origin: origin.absoluteString, userID: 7)
        fixture.beforeResponse = { request in
            guard request.url?.path == "/api/account/settings" else { return }
            await MainActor.run {
                try? store.removeFavorite(request.httpMethod == "PUT" ? b : a, source: source)
            }
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountFixtureProtocol.self]
        let client = try CoreScopeAccountClient(origin: origin, configuration: configuration, cookie: "synthetic")
        let user = CoreScopeAccountUser(id: 7, email: "fixture@example.test", displayName: "Test", csrfToken: "csrf-fixture")
        let model = CoreScopeAccountModel(store: store, client: client, origin: origin.absoluteString, user: user)
        await model.sync(upload: true)
        let saved = try #require(try store.load(origin: origin.absoluteString, userID: 7))
        #expect(saved.favorites == [c])
        #expect(Set(try CoreScopeFavoriteMerge.selection(saved.baseline).favorites) == [b, c])
        #expect(saved.hasPendingEdits)
        #expect(model.message?.contains("Newer edits") == true)
        #expect(fixture.requests.filter { $0.httpMethod == "PUT" }.count == 1)
    }

    @Test @MainActor func expiryHidesAccountButPreservesPendingEditsAndOfflineLogoutPurgesThem() async throws {
        let suite = "expired-account-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = CoreScopeAccountSelectionStore(defaults: defaults)
        let origin = try #require(URL(string: "https://expiry.fixture/"))
        var state = try CoreScopeAccountSelectionState(remote: settings([a]))
        state.favorites.insert(b)
        try store.save(state, origin: origin.absoluteString, userID: 7)
        store.activate(origin: origin.absoluteString, userID: 7)
        let fixture = AccountFixture()
        fixture.meCode = 401
        AccountFixtureProtocol.fixture = fixture
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountFixtureProtocol.self]
        let client = try CoreScopeAccountClient(origin: origin, configuration: configuration, cookie: "synthetic", save: { fixture.save($0) })
        let user = CoreScopeAccountUser(id: 7, email: "fixture@example.test", displayName: "Test", csrfToken: "csrf-fixture")
        let model = CoreScopeAccountModel(store: store, client: client, origin: origin.absoluteString, user: user)
        await model.sync(upload: true)
        #expect(model.user == nil)
        #expect(store.visibleState(source: "expiry.fixture") == nil)
        #expect(try store.load(origin: origin.absoluteString, userID: 7)?.favorites == [a, b])
        #expect(fixture.token == nil)
        fixture.meCode = nil
        fixture.code = 503
        let restoredClient = try CoreScopeAccountClient(origin: origin, configuration: configuration, cookie: "restored", save: { fixture.save($0) })
        let restored = CoreScopeAccountModel(store: store, client: restoredClient, origin: origin.absoluteString, user: user)
        await restored.logout()
        #expect(restored.user == nil)
        #expect(try store.load(origin: origin.absoluteString, userID: 7) == nil)
        #expect(fixture.token == nil)
        #expect(restored.message?.contains("revocation was not confirmed") == true)
    }

    @Test @MainActor func unchangedFavoritesDoNotWriteToNormalizeServerJSON() async throws {
        let suite = "no-op-sync-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let fixture = AccountFixture()
        AccountFixtureProtocol.fixture = fixture
        let original = try settings([a])
        var keys = try #require(original.doc).keys
        keys["meshcore-favorites"] = #"[ "\#(a)" ]"#
        let remote = CoreScopeAccountSettings(revision: original.revision, generation: original.generation,
            doc: .init(v: 1, keys: keys), allowlist: original.allowlist)
        fixture.response = try JSONEncoder().encode(remote)
        let origin = try #require(URL(string: "https://no-op.fixture/"))
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountFixtureProtocol.self]
        let client = try CoreScopeAccountClient(origin: origin, configuration: configuration, cookie: "synthetic")
        let user = CoreScopeAccountUser(id: 7, email: "fixture@example.test", displayName: "Test", csrfToken: "csrf-fixture")
        let model = CoreScopeAccountModel(store: CoreScopeAccountSelectionStore(defaults: defaults), client: client,
            origin: origin.absoluteString, user: user)
        await model.sync(upload: true)
        #expect(model.selection?.favorites == [a])
        #expect(model.selection?.hasPendingEdits == false)
        #expect(!fixture.requests.contains { $0.httpMethod == "PUT" })
    }

    @Test func independentAdditionsAndDeletionsPreserveClaimsAndOtherSettings() throws {
        let base = try settings([a, b], claimed: [d])
        let remote = try settings([a, c], claimed: [d])
        let (document, selection) = try CoreScopeFavoriteMerge.document(base: base, local: [b], remote: remote)
        #expect(selection.keys == [c, d])
        #expect(document.keys["meshcore-theme"] == "dark")
        #expect(document.keys["meshcore-my-nodes"] == remote.doc?.keys["meshcore-my-nodes"])
        #expect(!selection.favorites.contains(d))
    }

    @Test func firstSyncUnionsAndDeduplicatesCaseWithoutRewritingRemoteSpelling() throws {
        let remote = try settings([a.uppercased()], claimed: [b])
        let (_, selection) = try CoreScopeFavoriteMerge.document(base: nil, local: [a, c], remote: remote)
        #expect(selection.keys == [a, b, c])
        #expect(selection.favorites == [a.uppercased(), c])
        #expect(throws: (any Error).self) { try CoreScopeFavoriteMerge.selection(settings(["invalid"])) }
    }

    @Test func sessionRenewalOriginCSRFAndConflictAreHandledWithoutLeakingPublicCookies() async throws {
        let fixture = AccountFixture()
        AccountFixtureProtocol.fixture = fixture
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountFixtureProtocol.self]
        let origin = try #require(URL(string: "https://account.fixture"))
        let client = try CoreScopeAccountClient(origin: origin, configuration: configuration,
            save: { fixture.save($0) })
        #expect(try await client.supportsAccounts())
        let user = try await client.login(email: "fixture@example.test", password: "synthetic-only")
        #expect(user.id == 7)
        _ = try await client.me()
        #expect(fixture.token == "renewed")
        let remote = try settings([a])
        fixture.response = try JSONEncoder().encode(remote)
        _ = try await client.settings()
        fixture.code = 409
        await #expect(throws: CoreScopeAccountError.self) {
            try await client.put(try #require(remote.doc), base: remote, csrf: user.csrfToken)
        }
        let write = try #require(fixture.requests.last)
        #expect(write.value(forHTTPHeaderField: "Cookie") == "cs_session=renewed")
        #expect(write.value(forHTTPHeaderField: "X-CS-CSRF") == "csrf-fixture")
        #expect(write.value(forHTTPHeaderField: "Origin") == "https://account.fixture")
        #expect(fixture.requests.first?.value(forHTTPHeaderField: "Cookie") == nil)
        fixture.code = 200
        try await client.logout()
        #expect(fixture.token == nil)
    }

    @Test func invalidatedSourceCannotMakeAuthenticatedRequests() async throws {
        let lifetime = AnalyzerSourceSession()
        let client = try CoreScopeAccountClient(origin: #require(URL(string: "https://account.fixture")),
            cookie: "synthetic", lifetime: lifetime)
        lifetime.invalidate()
        await #expect(throws: CancellationError.self) { try await client.me() }
    }
    @Test func repeaterManagementUsesCurrentAccountAndRejectsStaleEdits() async throws {
        let fixture = AccountFixture()
        AccountFixtureProtocol.fixture = fixture
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountFixtureProtocol.self]
        let client = try CoreScopeAccountClient(origin: #require(URL(string: "https://repeater.fixture")),
            configuration: configuration, cookie: "session")
        #expect(try await client.supportsRepeaterManagement() == false)
        fixture.repeaterTokens = true
        #expect(try await client.supportsRepeaterManagement())
        fixture.response = Data("""
            {"repeaters":[{"pubkey":"\(a)","name":"Roof","known":true,"inactive":true,
            "lastHeard":"2026-11-20T05:00:00Z","comaintainers":["Operator"]}]}
            """.utf8)
        let repeater = try #require(try await client.repeaters().first)
        #expect(repeater.inactive == true)
        #expect(repeater.lastHeard != nil)
        try await client.setRepeaterClaim(a, claimed: false)
        let removal = try #require(fixture.requests.last)
        #expect(removal.httpMethod == "DELETE")
        #expect(removal.url?.path == "/api/repeaters/\(a)/claim")
        #expect(removal.value(forHTTPHeaderField: "X-CS-CSRF") == "csrf-fixture")
        fixture.putCode = 409
        await #expect(throws: AccountRepeaterError.staleRevision) {
            try await client.saveRepeater(a, edit: .init(baseRevision: 3, heightM: nil, gainDbi: 4, status: "", power: "", notes: ""))
        }
        #expect(fixture.requests.last?.httpMethod == "PUT")
    }

}

private final class AccountFixture: @unchecked Sendable {
    private let lock = NSLock()
    private var saved: String?
    var token: String? { lock.withLock { saved } }
    var requests: [URLRequest] = []
    var code = 200
    var putCode: Int?
    var repeaterTokens = false
    var meCode: Int?
    var putResponse: Data?
    var beforeResponse: (@Sendable (URLRequest) async -> Void)?
    var response = Data()
    func save(_ token: String?) { lock.withLock { saved = token } }
    func answer(_ request: URLRequest) -> (Int, [String: String], Data) {
        lock.withLock {
            requests.append(request)
            if request.url?.path == "/api/config/client" {
                return (200, [:], Data(
                    "{\"userManagement\":{\"enabled\":true,\"repeaterTokens\":\(repeaterTokens)}}".utf8
                ))
            }
            if request.url?.path == "/api/auth/me", let meCode { return (meCode, [:], Data()) }
            if request.url?.path == "/api/auth/login" || request.url?.path == "/api/auth/me" {
                let token = request.url?.path == "/api/auth/login" ? "initial" : "renewed"
                return (200, ["Set-Cookie": "cs_session=\(token); Path=/; Secure; HttpOnly"],
                    Data(#"{"id":7,"email":"fixture@example.test","displayName":"Test","csrfToken":"csrf-fixture"}"#.utf8))
            }
            return (request.httpMethod == "PUT" ? (putCode ?? code) : code, [:], request.httpMethod == "PUT" ? (putResponse ?? response) : response)
        }
    }
}

private final class AccountFixtureProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var fixture = AccountFixture()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    private var responseTask: Task<Void, Never>?
    override func startLoading() {
        let fixture = Self.fixture
        responseTask = Task {
        await fixture.beforeResponse?(request)
        guard !Task.isCancelled else { return }
        let (code, headers, data) = fixture.answer(request)
        guard let url = request.url, let response = HTTPURLResponse(url: url, statusCode: code,
            httpVersion: nil, headerFields: headers) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() { responseTask?.cancel() }
}
