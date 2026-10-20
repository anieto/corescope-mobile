import Foundation

struct BeaconServerInfo: Codable, Equatable, Sendable {
    let serverVersion: String

    var versionComponents: [Int]? {
        let sections = serverVersion.split(separator: "+", omittingEmptySubsequences: false)
        guard sections.count <= 2, let release = sections.first, !release.isEmpty else { return nil }
        if sections.count == 2 {
            let identifiers = sections[1].split(separator: ".", omittingEmptySubsequences: false)
            guard identifiers.allSatisfy({ identifier in
                !identifier.isEmpty && identifier.allSatisfy {
                    $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-")
                }
            }) else { return nil }
        }
        let parts = release.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        let numbers = parts.compactMap { part -> Int? in
            guard !part.isEmpty, !(part.count > 1 && part.first == "0"),
                  part.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
            return Int(part)
        }
        return numbers.count == 3 ? numbers : nil
    }

    func requireSupported() throws {
        guard let version = versionComponents else { throw BeaconAPIError.unrecognizedVersion }
        guard !version.lexicographicallyPrecedes([2, 0, 3]) else {
            throw BeaconAPIError.unsupportedVersion(serverVersion)
        }
    }
}

enum BeaconAPIError: Error, LocalizedError {
    case unrecognizedVersion
    case unsupportedVersion(String)
    case rateLimited(until: Date)
    case server(status: Int, code: String?)
    case malformedPage

    var errorDescription: String? {
        switch self {
        case .unrecognizedVersion:
            "The server did not report a recognized Beacon version."
        case .unsupportedVersion(let version):
            "Beacon \(version) is not supported. Version 2.0.3 or newer is required."
        case .rateLimited(let until):
            "This server is rate limiting requests. Try again after \(until.formatted(date: .omitted, time: .standard))."
        case .server(let status, _):
            "Beacon returned status \(status)."
        case .malformedPage:
            "Beacon returned an incomplete pagination response."
        }
    }
}

actor BeaconRateLimit {
    static let shared = BeaconRateLimit()
    private var deadlines: [String: Date] = [:]

    func check(origin: String, now: Date) throws {
        if let deadline = deadlines[origin], deadline > now {
            throw BeaconAPIError.rateLimited(until: deadline)
        }
        deadlines[origin] = nil
    }

    func record(origin: String, retryAfter: String?, now: Date) -> Date {
        let proposed = Self.retryDate(retryAfter, now: now)
        let deadline = max(deadlines[origin] ?? proposed, proposed)
        deadlines[origin] = deadline
        return deadline
    }

    static func retryDate(_ header: String?, now: Date) -> Date {
        if let header = header?.trimmingCharacters(in: .whitespacesAndNewlines) {
            if let seconds = TimeInterval(header), seconds.isFinite, seconds >= 0 {
                return now.addingTimeInterval(min(seconds, max(0, Date.distantFuture.timeIntervalSince(now))))
            }
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = TimeZone(secondsFromGMT: 0)
            formatter.dateFormat = "EEE',' dd MMM yyyy HH':'mm':'ss zzz"
            if let date = formatter.date(from: header) { return max(date, now) }
        }
        return now.addingTimeInterval(30)
    }
}

struct BeaconAPIClient: Sendable {
    let baseURL: URL
    let sourceSession: AnalyzerSourceSession
    private let session: URLSession
    private let rateLimit: BeaconRateLimit
    private let now: @Sendable () -> Date
    private var requestAllowance: MyNodesRequestAllowance?

    init(
        baseURL: URL,
        sourceSession: AnalyzerSourceSession,
        session: URLSession = .shared,
        rateLimit: BeaconRateLimit = .shared,
        now: @escaping @Sendable () -> Date = { .now }
    ) throws {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path.isEmpty || components.path == "/" else { throw APIError.invalidURL }
        components.scheme = "https"
        components.host = host.lowercased()
        components.path = ""
        if components.port == 443 { components.port = nil }
        guard let canonical = components.url else { throw APIError.invalidURL }
        self.baseURL = canonical
        self.sourceSession = sourceSession
        self.session = session
        self.rateLimit = rateLimit
        self.now = now
    }

    var cacheIdentifier: String { "beacon|\(baseURL.absoluteString)" }

    func replacingSourceSession(_ sourceSession: AnalyzerSourceSession) throws -> BeaconAPIClient {
        var copy = try BeaconAPIClient(baseURL: baseURL, sourceSession: sourceSession, session: session, rateLimit: rateLimit, now: now)
        copy.requestAllowance = requestAllowance
        return copy
    }

    func budgetingRequests(_ budget: MyNodesRequestBudget, node: String) -> BeaconAPIClient {
        var copy = self
        copy.requestAllowance = MyNodesRequestAllowance(budget: budget, node: node)
        return copy
    }

    func checkCooldown() async throws {
        try sourceSession.check()
        try await rateLimit.check(origin: cacheIdentifier, now: now())
        try sourceSession.check()
    }

    func recordCooldown(retryAfter: String?) async -> Date {
        await rateLimit.record(origin: cacheIdentifier, retryAfter: retryAfter, now: now())
    }

    func serverInfo() async throws -> BeaconServerInfo {
        let info: BeaconServerInfo = try await get(["info"])
        guard info.versionComponents != nil else { throw BeaconAPIError.unrecognizedVersion }
        return info
    }

    func get<Value: Decodable & Sendable>(
        _ path: [String], query: [URLQueryItem] = []
    ) async throws -> Value {
        try sourceSession.check()
        try await rateLimit.check(origin: cacheIdentifier, now: now())
        try sourceSession.check()
        guard !path.isEmpty, path.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }),
              var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw APIError.invalidURL
        }
        components.percentEncodedPath = "/api/v1/" + path.map(\.urlPathComponentEncoded).joined(separator: "/")
        if !query.isEmpty { components.queryItems = query }
        guard let url = components.url else { throw APIError.invalidURL }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let requestCopy = request
        let task = Task {
            if let requestAllowance {
                return try await requestAllowance.data(for: requestCopy, session: session)
            }
            return try await session.data(for: requestCopy)
        }
        let cancellationID = sourceSession.registerCancellation { task.cancel() }
        defer { sourceSession.removeCancellation(cancellationID) }
        do {
            let (data, response) = try await withTaskCancellationHandler {
                try await task.value
            } onCancel: {
                task.cancel()
            }
            try sourceSession.check()
            guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
            if http.statusCode == 429 {
                let deadline = await rateLimit.record(
                    origin: cacheIdentifier, retryAfter: http.value(forHTTPHeaderField: "Retry-After"), now: now()
                )
                try sourceSession.check()
                throw BeaconAPIError.rateLimited(until: deadline)
            }
            guard (200..<300).contains(http.statusCode) else {
                let body = try? JSONDecoder().decode(ErrorBody.self, from: data)
                throw BeaconAPIError.server(status: http.statusCode, code: body?.error.code)
            }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .millisecondsSince1970
            return try decoder.decode(Value.self, from: data)
        } catch {
            try sourceSession.check()
            throw error
        }
    }

    private struct ErrorBody: Decodable {
        struct Detail: Decodable { let code: String }
        let error: Detail
    }
}

actor BeaconServerCatalog {
    static let shared = BeaconServerCatalog()
    private let defaults: UserDefaults
    private let storageKey = "beacon-server-info-v1"
    private var pending: [String: (id: UUID, task: Task<BeaconServerInfo, Error>)] = [:]

    init(suiteName: String? = nil) {
        defaults = suiteName.flatMap { UserDefaults(suiteName: $0) } ?? .standard
    }

    func knownInfo(for client: BeaconAPIClient) -> BeaconServerInfo? {
        saved()[client.cacheIdentifier]
    }

    func resolve(using client: BeaconAPIClient, refreshKnown: Bool = false) async throws -> BeaconServerInfo {
        try client.sourceSession.check()
        if !refreshKnown, let known = saved()[client.cacheIdentifier] {
            try known.requireSupported()
            return known
        }
        let key = "\(client.cacheIdentifier)|\(client.sourceSession.id)"
        if let request = pending[key] {
            let info = try await request.task.value
            try client.sourceSession.check()
            try info.requireSupported()
            return info
        }
        let identifier = UUID()
        let task = Task { try await self.probe(using: client) }
        pending[key] = (identifier, task)
        defer {
            if pending[key]?.id == identifier { pending[key] = nil }
        }
        let info = try await task.value
        try client.sourceSession.check()
        try info.requireSupported()
        return info
    }

    func probe(using client: BeaconAPIClient) async throws -> BeaconServerInfo {
        let info = try await client.serverInfo()
        try client.sourceSession.check()
        var records = saved()
        records[client.cacheIdentifier] = info
        defaults.set(try JSONEncoder().encode(records), forKey: storageKey)
        return info
    }

    private func saved() -> [String: BeaconServerInfo] {
        guard let data = defaults.data(forKey: storageKey) else { return [:] }
        return (try? JSONDecoder().decode([String: BeaconServerInfo].self, from: data)) ?? [:]
    }
}
