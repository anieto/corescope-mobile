import Foundation
import Security

struct CoreScopeAccountUser: Codable, Sendable {
    let id: Int64
    let email: String
    let displayName: String
    let csrfToken: String
}

enum CoreScopeAccountError: Error, LocalizedError {
    case unsupported, signedOut, rejected, conflict, storage, response, reset, throttled
    var errorDescription: String? {
        switch self {
        case .reset: "Account settings were reset on the server. Choose how to continue before syncing."
        case .throttled: "Account writes are limited to once a minute. Your changes are saved; try Sync now later."
        case .unsupported: "This source does not support compatible CoreScope accounts."
        case .signedOut: "Sign in again to access this account."
        case .rejected: "The request was rejected. Check your credentials or try again later."
        case .conflict: "Account favorites changed during sync. Try Sync now again."
        case .storage: "The account session could not be saved securely."
        case .response: "The account response could not be read. No favorites were changed."
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

    func supportsAccounts() async throws -> Bool {
        struct Config: Decodable { struct Management: Decodable { let enabled: Bool }; let userManagement: Management? }
        let data = try await request("api/config/client", authenticated: false)
        return try JSONDecoder().decode(Config.self, from: data).userManagement?.enabled == true
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
        if http.statusCode == 409 { throw CoreScopeAccountError.conflict }
        guard (200..<300).contains(http.statusCode) else { throw CoreScopeAccountError.rejected }
        return data
    }
}

enum CoreScopeFavoriteMerge {
    struct Selection {
        let favorites: [String]
        let claimed: [String: String]
        var keys: Set<String> { Set(favorites.map { $0.lowercased() }).union(claimed.keys) }
    }

    static func selection(_ settings: CoreScopeAccountSettings) throws -> Selection {
        guard settings.supportsNodeFavorites, settings.supportsOwnNodes else { throw CoreScopeAccountError.unsupported }
        let values = settings.doc?.keys ?? [:]
        let favorites = try JSONDecoder().decode([String].self, from: Data((values["meshcore-favorites"] ?? "[]").utf8))
        struct Claimed: Decodable { let pubkey: String; let name: String? }
        let owned = try JSONDecoder().decode([Claimed].self, from: Data((values["meshcore-my-nodes"] ?? "[]").utf8))
        guard (favorites + owned.map(\.pubkey)).allSatisfy(validKey) else { throw CoreScopeAccountError.response }
        return Selection(favorites: favorites, claimed: Dictionary(owned.map {
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
                         remote: CoreScopeAccountSettings) throws -> (CoreScopeAccountSettings.Document, Selection) {
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
        values["meshcore-favorites"] = String(decoding: try JSONEncoder().encode(raw + additions), as: UTF8.self)
        return (.init(v: 1, keys: values), Selection(favorites: raw + additions, claimed: current.claimed))
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
