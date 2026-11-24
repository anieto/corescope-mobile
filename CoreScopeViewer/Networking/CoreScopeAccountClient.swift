import Foundation
import Security

struct CoreScopeAccountUser: Codable, Sendable {
    let id: Int64
    let email: String
    let displayName: String
    let csrfToken: String
}

struct CoreScopeAccountCapabilities: Decodable, Sendable {
    let enabled: Bool
    let notifications: Bool?
    let repeaterTokens: Bool?
}

struct CoreScopeWatchedNode: Decodable, Identifiable, Sendable {
    let pubkey: String
    let name: String
    var id: String { pubkey }
}

struct CoreScopeAccountNotifications: Decodable, Sendable {
    let enabled: Bool
    let watches: [CoreScopeWatchedNode]
    let pushRelay: StagingPushCapability?

    private enum CodingKeys: String, CodingKey { case enabled, watches }

    init(from decoder: Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try fields.decode(Bool.self, forKey: .enabled)
        watches = try fields.decode([CoreScopeWatchedNode].self, forKey: .watches)
        pushRelay = try AccountPushRelay(from: decoder).pushRelay
    }
}

private struct AccountPushRelay: Decodable {
    let pushRelay: StagingPushCapability?

    private enum CodingKeys: String, CodingKey { case pushRelay, nodeScopePush }

    init(from decoder: Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        let key: CodingKeys = fields.contains(.pushRelay) ? .pushRelay : .nodeScopePush
        pushRelay = try fields.decodeIfPresent(StagingPushCapability.self, forKey: key)
    }
}

enum CoreScopeAccountError: Error, LocalizedError {
    case unsupported, signedOut, rejected, conflict, storage, response, reset, throttled, pushLinkGone, watchLimit, watchUnknown
    var errorDescription: String? {
        switch self {
        case .reset: String(localized: "Account settings were reset on the server. Choose how to continue before syncing.")
        case .throttled: String(localized: "Account writes are limited to once a minute. Your changes are saved; try Sync now later.")
        case .unsupported: String(localized: "This source does not support compatible CoreScope accounts.")
        case .signedOut: String(localized: "Sign in again to access this account.")
        case .rejected, .pushLinkGone: String(localized: "The request was rejected. Check your credentials or try again later.")
        case .watchLimit: String(localized: "This account reached its watch limit. Stop watching a node before adding another.")
        case .watchUnknown: String(localized: "This analyzer doesn’t know that node yet.")
        case .conflict: String(localized: "Account favorites changed during sync. Try Sync now again.")
        case .storage: String(localized: "The account session could not be saved securely.")
        case .response: String(localized: "The account response could not be read. No favorites were changed.")
        }
    }
}

/// Deliberately separate from public analyzer sessions: no shared cookie jar or redirects.
private final class AccountRedirectPolicy: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

enum AccountSessionKeychain {
    static func load(origin: String) throws -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "org.nodescope.corescope-session", kSecAttrAccount as String: origin,
            kSecReturnData as String: true]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data,
              let token = String(data: data, encoding: .utf8) else { throw CoreScopeAccountError.storage }
        return token
    }

    static func save(_ token: String?, origin: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "org.nodescope.corescope-session", kSecAttrAccount as String: origin]
        guard let token else {
            let result = SecItemDelete(query as CFDictionary)
            guard result == errSecSuccess || result == errSecItemNotFound else { throw CoreScopeAccountError.storage }
            return
        }
        let values: [String: Any] = [kSecValueData as String: Data(token.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var result = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if result == errSecItemNotFound {
            result = SecItemAdd(query.merging(values) { _, value in value } as CFDictionary, nil)
        }
        guard result == errSecSuccess else { throw CoreScopeAccountError.storage }
    }
}

struct AccountRepeater: Decodable, Identifiable, Sendable {
    let pubkey: String
    let name: String
    let known: Bool
    let inactive: Bool?
    let lastHeard: Date?
    let status: String?
    let comaintainers: [String]
    var id: String { pubkey }
    var title: String { name.isEmpty ? String(pubkey.prefix(12)) : name }
}

struct AccountRepeaterDetails: Decodable, Sendable {
    let pubkey: String
    let revision: Int64
    let heightM: Double?
    let gainDbi: Double
    let status: String?
    let power: String?
    let notes: String
    let claimedByMe: Bool
    let canEdit: Bool
    let claimers: [RepeaterMetadata.Claim]
}

struct AccountRepeaterEdit: Encodable, Sendable {
    let baseRevision: Int64
    let heightM: Double?
    let gainDbi: Double
    let status: String
    let power: String
    let notes: String
}

enum AccountRepeaterError: Error, LocalizedError {
    case staleRevision

    var errorDescription: String? {
        String(localized: "The repeater details changed. Reload the latest report before saving again.")
    }
}

actor CoreScopeAccountClient {
    let origin: URL
    private let session: URLSession
    private var cookie: String?
    private let lifetime: AnalyzerSourceSession?
    private let save: @Sendable (String?) throws -> Void

    init(origin: URL, configuration: URLSessionConfiguration = .ephemeral,
         cookie: String? = nil, lifetime: AnalyzerSourceSession? = nil, save: @escaping @Sendable (String?) throws -> Void = { _ in }) throws {
        guard origin.scheme == "https", origin.host != nil, origin.user == nil, origin.password == nil,
              origin.query == nil, origin.fragment == nil else { throw CoreScopeAccountError.unsupported }
        self.origin = origin
        self.cookie = cookie
        self.lifetime = lifetime
        self.save = save
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 30
        session = URLSession(configuration: configuration, delegate: AccountRedirectPolicy(), delegateQueue: nil)
    }

    @MainActor
    static func restoredRepeaterClient(settings: AnalyzerSettings) throws -> CoreScopeAccountClient? {
        guard settings.sourceIsReady, settings.beaconBackend == nil else { return nil }
        let url = settings.baseURL
        let origin = url.absoluteString
        guard let cookie = try AccountSessionKeychain.load(origin: origin) else { return nil }
        return try CoreScopeAccountClient(origin: url, cookie: cookie, lifetime: settings.sourceSession,
            save: { try AccountSessionKeychain.save($0, origin: origin) })
    }

    func supportsRepeaterManagement() async throws -> Bool {
        let capabilities = try await accountCapabilities()
        return capabilities?.enabled == true && capabilities?.repeaterTokens == true
    }

    func repeaters() async throws -> [AccountRepeater] {
        struct Response: Decodable { let repeaters: [AccountRepeater] }
        return try APIClient.makeDecoder().decode(Response.self, from: await request("api/account/repeaters")).repeaters
    }

    func repeaterDetails(_ key: String) async throws -> AccountRepeaterDetails {
        let path = try repeaterPath(key)
        return try decodeRepeater(await request(path + "/info"), key: key)
    }

    func setRepeaterClaim(_ key: String, claimed: Bool) async throws {
        let path = try repeaterPath(key)
        let user = try await me()
        _ = try await request(path + "/claim", method: claimed ? "POST" : "DELETE", csrf: user.csrfToken)
    }

    func saveRepeater(_ key: String, edit: AccountRepeaterEdit) async throws -> AccountRepeaterDetails {
        let path = try repeaterPath(key)
        let user = try await me()
        do {
            let data = try await request(path + "/info", method: "PUT",
                body: JSONEncoder().encode(edit), csrf: user.csrfToken)
            return try decodeRepeater(data, key: key)
        } catch CoreScopeAccountError.conflict {
            throw AccountRepeaterError.staleRevision
        }
    }

    private func repeaterPath(_ key: String) throws -> String {
        let normalized = key.lowercased()
        guard normalized.utf8.count == 64,
              normalized.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
            throw CoreScopeAccountError.rejected
        }
        return "api/repeaters/\(normalized)"
    }

    private func decodeRepeater(_ data: Data, key: String) throws -> AccountRepeaterDetails {
        let details = try JSONDecoder().decode(AccountRepeaterDetails.self, from: data)
        guard details.pubkey.caseInsensitiveCompare(key) == .orderedSame, details.revision >= 0 else {
            throw CoreScopeAccountError.response
        }
        return details
    }

    func supportsAccounts() async throws -> Bool { try await accountCapabilities()?.enabled == true }

    func accountCapabilities() async throws -> CoreScopeAccountCapabilities? {
        struct Config: Decodable { let userManagement: CoreScopeAccountCapabilities? }
        let data = try await request("api/config/client", authenticated: false)
        return try JSONDecoder().decode(Config.self, from: data).userManagement
    }

    func notifications() async throws -> CoreScopeAccountNotifications {
        let data = try await request("api/account/notifications")
        return try JSONDecoder().decode(CoreScopeAccountNotifications.self, from: data)
    }

    func setWatch(_ pubkey: String, watched: Bool, csrf: String) async throws -> CoreScopeAccountNotifications {
        let key = pubkey.lowercased()
        guard key.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
            throw CoreScopeAccountError.rejected
        }
        let data = try await request("api/account/notifications/watches/\(key)",
                                     method: watched ? "PUT" : "DELETE", csrf: csrf)
        return try JSONDecoder().decode(CoreScopeAccountNotifications.self, from: data)
    }

    func login(email: String, password: String) async throws -> CoreScopeAccountUser {
        struct Login: Encodable { let email: String; let password: String }
        let data = try await request("api/auth/login", method: "POST",
            body: JSONEncoder().encode(Login(email: email, password: password)), authenticated: false)
        let user = try JSONDecoder().decode(CoreScopeAccountUser.self, from: data)
        guard cookie != nil, !user.csrfToken.isEmpty else { throw CoreScopeAccountError.response }
        return user
    }

    func me() async throws -> CoreScopeAccountUser {
        try JSONDecoder().decode(CoreScopeAccountUser.self, from: await request("api/auth/me"))
    }

    func settings() async throws -> CoreScopeAccountSettings {
        let result = try JSONDecoder().decode(CoreScopeAccountSettings.self, from: await request("api/account/settings"))
        guard result.supportsNodeFavorites, result.supportsOwnNodes else { throw CoreScopeAccountError.unsupported }
        _ = try CoreScopeFavoriteMerge.selection(result)
        return result
    }

    func put(_ document: CoreScopeAccountSettings.Document, base: CoreScopeAccountSettings,
             csrf: String) async throws -> CoreScopeAccountSettings {
        struct Write: Encodable {
            let baseRevision: Int64
            let baseGeneration: String
            let doc: CoreScopeAccountSettings.Document
        }
        try await CoreScopeAccountWriteGate.shared.reserve(origin: origin.absoluteString)
        let data = try await request("api/account/settings", method: "PUT",
            body: JSONEncoder().encode(Write(baseRevision: base.revision, baseGeneration: base.generation, doc: document)), csrf: csrf)
        struct Ack: Decodable { let revision: Int64; let generation: String }
        let ack = try JSONDecoder().decode(Ack.self, from: data)
        return CoreScopeAccountSettings(revision: ack.revision, generation: ack.generation, doc: document, allowlist: base.allowlist)
    }

    #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
    func pushCapability() async throws -> StagingPushCapability? {
        let data = try await request("api/account/notifications")
        return try JSONDecoder().decode(AccountPushRelay.self, from: data).pushRelay
    }

    func linkPushDevice(_ installationID: String, proof: String, csrf: String) async throws -> StagingPushLink {
        let data = try await request("api/account/notifications/devices/\(installationID)", method: "PUT",
                                     body: JSONEncoder().encode(["proof": proof]), csrf: csrf)
        return try JSONDecoder().decode(StagingPushLink.self, from: data)
    }
    func unlinkPushDevice(_ installationID: String, csrf: String) async throws {
        _ = try await request("api/account/notifications/devices/\(installationID)", method: "DELETE", csrf: csrf)
    }
    #endif

    func logout() async throws {
        // Always forget the local token, including when server revocation is unavailable.
        do { _ = try await request("api/auth/logout", method: "POST") }
        catch { cookie = nil; try save(nil); throw error }
        cookie = nil
        try save(nil)
    }

    private func request(_ path: String, method: String = "GET", body: Data? = nil,
                         csrf: String? = nil, authenticated: Bool = true) async throws -> Data {
        try lifetime?.check()
        if authenticated && cookie == nil { throw CoreScopeAccountError.signedOut }
        try await MyNodesRequestAllowance.coreScopeCooldown.check(origin: APIClient(baseURL: origin).cacheIdentifier, now: .now)
        var request = URLRequest(url: origin.appendingPathComponent(path))
        request.httpMethod = method
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(origin.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")), forHTTPHeaderField: "Origin")
        if authenticated, let cookie { request.setValue("cs_session=\(cookie)", forHTTPHeaderField: "Cookie") }
        if let csrf { request.setValue(csrf, forHTTPHeaderField: "X-CS-CSRF") }
        try Task.checkCancellation()
        try lifetime?.check()
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        try lifetime?.check()
        guard let http = response as? HTTPURLResponse, http.url?.host == origin.host,
              data.count <= 512 * 1024 else { throw CoreScopeAccountError.response }
        let headers = http.allHeaderFields.reduce(into: [String: String]()) { result, pair in
            if let key = pair.key as? String, let value = pair.value as? String { result[key] = value }
        }
        if let renewed = HTTPCookie.cookies(withResponseHeaderFields: headers, for: origin).first(where: { $0.name == "cs_session" }) {
            let token = renewed.value.isEmpty || renewed.expiresDate.map({ $0 <= .now }) == true ? nil : renewed.value
            try save(token)
            cookie = token
        }
        if http.statusCode == 401 {
            if authenticated { cookie = nil; try save(nil) }
            throw authenticated ? CoreScopeAccountError.signedOut : CoreScopeAccountError.rejected
        }
        if http.statusCode == 429 {
            let until = await MyNodesRequestAllowance.coreScopeCooldown.record(
                origin: APIClient(baseURL: origin).cacheIdentifier,
                retryAfter: http.value(forHTTPHeaderField: "Retry-After"), now: .now)
            throw BeaconAPIError.rateLimited(until: until)
        }
        if http.statusCode == 410 && method == "PUT" && path.hasPrefix("api/account/notifications/devices/") {
            throw CoreScopeAccountError.pushLinkGone
        }
        if method == "PUT", path.hasPrefix("api/account/notifications/watches/") {
            if http.statusCode == 409 { throw CoreScopeAccountError.watchLimit }
            if http.statusCode == 404 { throw CoreScopeAccountError.watchUnknown }
        }
        if http.statusCode == 409 { throw CoreScopeAccountError.conflict }
        guard (200..<300).contains(http.statusCode) else { throw CoreScopeAccountError.rejected }
        return data
    }
}

enum CoreScopeFavoriteMerge {
    struct Selection {
        let favorites: [String]
        let myNodes: [String: String]
        var keys: Set<String> { Set(favorites.map { $0.lowercased() }).union(myNodes.keys) }
    }

    static func selection(_ settings: CoreScopeAccountSettings) throws -> Selection {
        guard settings.supportsNodeFavorites, settings.supportsOwnNodes else { throw CoreScopeAccountError.unsupported }
        let values = settings.doc?.keys ?? [:]
        let favorites = try JSONDecoder().decode([String].self, from: Data((values["meshcore-favorites"] ?? "[]").utf8))
        struct MyNode: Decodable { let pubkey: String; let name: String? }
        let owned = try JSONDecoder().decode([MyNode].self, from: Data((values["meshcore-my-nodes"] ?? "[]").utf8))
        guard (favorites + owned.map(\.pubkey)).allSatisfy(validKey) else { throw CoreScopeAccountError.response }
        return Selection(favorites: favorites, myNodes: Dictionary(owned.map {
            ($0.pubkey.lowercased(), $0.name ?? "")
        }, uniquingKeysWith: { first, _ in first }))
    }

    static func validKey(_ key: String) -> Bool {
        key.utf8.count == 64 && key.utf8.allSatisfy { (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }
    }

    static func merge(base: Set<String>?, local: Set<String>, remote: Set<String>) -> Set<String> {
        guard let base else { return local.union(remote) }
        return local.subtracting(base).union(remote.subtracting(base)).union(base.intersection(local).intersection(remote))
    }

    static func document(base: CoreScopeAccountSettings?, local: Set<String>,
                         remote: CoreScopeAccountSettings, removingMyNodes: Set<String> = []) throws -> (CoreScopeAccountSettings.Document, Selection) {
        if let base, base.generation != remote.generation { throw CoreScopeAccountError.reset }
        let current = try selection(remote)
        let previous = try base.map(selection)
        let merged = merge(base: previous.map { Set($0.favorites.map { $0.lowercased() }) },
            local: Set(local.map { $0.lowercased() }), remote: Set(current.favorites.map { $0.lowercased() }))
        var values = remote.doc?.keys ?? [:]
        // Keep exact remote key spellings. Claimed-only nodes are not rewritten into favorites.
        let raw = current.favorites.filter { merged.contains($0.lowercased()) }
        let present = Set(raw.map { $0.lowercased() })
        let additions = merged.subtracting(present).sorted()
        if raw + additions != current.favorites {
            values["meshcore-favorites"] = String(decoding: try JSONEncoder().encode(raw + additions), as: UTF8.self)
        }
        let myNodes = current.myNodes.filter { !removingMyNodes.contains($0.key) }
        if myNodes.count != current.myNodes.count, let stored = values["meshcore-my-nodes"] {
            guard let entries = try JSONSerialization.jsonObject(with: Data(stored.utf8)) as? [[String: Any]] else {
                throw CoreScopeAccountError.response
            }
            let kept = entries.filter { entry in
                guard let key = entry["pubkey"] as? String else { return true }
                return !removingMyNodes.contains(key.lowercased())
            }
            values["meshcore-my-nodes"] = String(decoding: try JSONSerialization.data(withJSONObject: kept), as: UTF8.self)
        }
        return (.init(v: 1, keys: values), Selection(favorites: raw + additions, myNodes: myNodes))
    }
}

/// Shared across windows and clients. Failed writes also consume the interval.
actor CoreScopeAccountWriteGate {
    static let shared = CoreScopeAccountWriteGate()
    private var deadlines: [String: Date] = [:]
    func reserve(origin: String, now: Date = .now) throws {
        guard deadlines[origin].map({ $0 > now }) != true else { throw CoreScopeAccountError.throttled }
        deadlines[origin] = now.addingTimeInterval(60)
    }
}
