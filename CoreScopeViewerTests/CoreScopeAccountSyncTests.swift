import Foundation
import Testing
@testable import CoreScopeViewer

@Suite(.serialized)
struct CoreScopeAccountSyncTests {
    private let a = String(repeating: "a", count: 64)
    private let b = String(repeating: "b", count: 64)
    private let c = String(repeating: "c", count: 64)
    private let d = String(repeating: "d", count: 64)

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

    @Test func claimedMembershipDoesNotForceFavoriteMembership() throws {
        let remote = try settings([a], claimed: [a, b])
        let (document, selection) = try CoreScopeFavoriteMerge.document(base: remote, local: [], remote: remote)
        #expect(selection.favorites.isEmpty)
        #expect(selection.keys == [a, b])
        #expect(document.keys["meshcore-my-nodes"] == remote.doc?.keys["meshcore-my-nodes"])
        let (_, imported) = try CoreScopeFavoriteMerge.document(base: remote, local: [b], remote: remote)
        #expect(imported.favorites == [b])
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

    @Test @MainActor func combinedFavoritesPreserveMembershipAndNeverPersistAccountNodesAsDeviceFavorites() throws {
        let suite = "combined-favorites-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let accounts = CoreScopeAccountSelectionStore(defaults: defaults)
        let favorites = FavoritesStore(defaults: defaults, accountStore: accounts)
        let origin = "https://one.fixture/"
        let source = "one.fixture"
        let local = MeshNode(publicKey: a.uppercased(), name: "Device node", role: "repeater", lat: nil, lon: nil,
            lastSeen: nil, firstSeen: nil, advertCount: nil, hashSize: nil, hashSizeInconsistent: nil, lastHeard: nil, defaultScope: nil)
        favorites.toggle(node: local, source: source)
        try accounts.save(CoreScopeAccountSelectionState(remote: settings([a, b], claimed: [b, c])), origin: origin, userID: 7)
        accounts.activate(origin: origin, userID: 7)
        #expect(favorites.items.count == 3)
        #expect(favorites.items.first?.node == local)
        #expect(favorites.nodeKeys(source: source) == [a])
        let localRow = try #require(favorites.items.first)
        #expect(favorites.membershipLabel(localRow) == "Device · Account")
        favorites.remove(localRow)
        #expect(favorites.items.count == 3)
        #expect(favorites.nodeKeys(source: source).isEmpty)
        let shared = try #require(favorites.items.first { $0.entityID.lowercased() == b })
        favorites.removeAccountFavorite(shared)
        #expect(favorites.items.contains { $0.entityID == b })
        #expect(favorites.membershipLabel(shared) == "Claimed")
        let restartedAccounts = CoreScopeAccountSelectionStore(defaults: defaults)
        let restarted = FavoritesStore(defaults: defaults, accountStore: restartedAccounts)
        #expect(restarted.items.count == 3)
        #expect(restarted.deviceItems.isEmpty)
        accounts.remove(origin: origin, userID: 7)
        #expect(favorites.items.isEmpty)
    }

    @Test @MainActor func switchingAccountAndRemoteRemovalNeverDeleteDeviceMembership() throws {
        let suite = "switch-projection-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let accounts = CoreScopeAccountSelectionStore(defaults: defaults)
        let favorites = FavoritesStore(defaults: defaults, accountStore: accounts)
        favorites.applyAccountNodes([a], names: [a: "Device"], source: "one.fixture")
        try accounts.save(CoreScopeAccountSelectionState(remote: settings([a, b])), origin: "https://one.fixture/", userID: 1)
        accounts.activate(origin: "https://one.fixture/", userID: 1)
        #expect(favorites.items.count == 2)
        try accounts.save(CoreScopeAccountSelectionState(remote: settings([])), origin: "https://one.fixture/", userID: 1)
        #expect(favorites.items.map(\.entityID) == [a])
        try accounts.save(CoreScopeAccountSelectionState(remote: settings([c])), origin: "https://one.fixture/", userID: 2)
        accounts.activate(origin: "https://one.fixture/", userID: 2)
        #expect(Set(favorites.items.map(\.entityID)) == [a, c])
        accounts.remove(origin: "https://one.fixture/", userID: 1)
        #expect(Set(favorites.items.map(\.entityID)) == [a, c])
        accounts.deactivate(origin: "https://one.fixture/")
        #expect(favorites.items.map(\.entityID) == [a])
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
}

private final class AccountFixture: @unchecked Sendable {
    private let lock = NSLock()
    private var saved: String?
    var token: String? { lock.withLock { saved } }
    var requests: [URLRequest] = []
    var code = 200
    var putCode: Int?
    var meCode: Int?
    var putResponse: Data?
    var beforeResponse: (@Sendable (URLRequest) async -> Void)?
    var response = Data()
    func save(_ token: String?) { lock.withLock { saved = token } }
    func answer(_ request: URLRequest) -> (Int, [String: String], Data) {
        lock.withLock {
            requests.append(request)
            if request.url?.path == "/api/config/client" { return (200, [:], Data(#"{"userManagement":{"enabled":true}}"#.utf8)) }
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
