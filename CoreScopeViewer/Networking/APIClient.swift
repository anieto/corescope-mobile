import Foundation

/// Shared persistent cache for read-only API responses. Values are isolated by
/// analyzer and endpoint, kept in memory after first use, and concurrent refreshes
/// for the same key share one request.
actor APIResponseCache {
    static let shared = APIResponseCache()

    private struct Entry<Value: Codable>: Codable {
        let savedAt: Date
        let value: Value
    }

    private struct TimestampEntry: Decodable {
        let savedAt: Date
    }

    private var memoryData: [String: Data] = [:]
    private var inFlightRefreshes: [String: Task<Data, Error>] = [:]
    private var generation = UUID()

    func value<Value: Codable>(
        for key: String,
        maximumAge: TimeInterval
    ) -> Value? {
        let data = memoryData[key] ?? (try? Data(contentsOf: fileURL(for: key)))
        guard let data,
              let entry = try? JSONDecoder().decode(Entry<Value>.self, from: data),
              Date().timeIntervalSince(entry.savedAt) <= maximumAge else {
            return nil
        }
        memoryData[key] = data
        return entry.value
    }

    func clear() {
        generation = UUID()
        inFlightRefreshes.values.forEach { $0.cancel() }
        inFlightRefreshes.removeAll()
        memoryData.removeAll()
        try? FileManager.default.removeItem(at: cacheDirectory)
    }

    func savedAt(for key: String) -> Date? {
        let data = memoryData[key] ?? (try? Data(contentsOf: fileURL(for: key)))
        guard let data,
              let entry = try? JSONDecoder().decode(TimestampEntry.self, from: data) else {
            return nil
        }
        memoryData[key] = data
        return entry.savedAt
    }

    func store<Value: Codable & Sendable>(
        _ value: Value,
        for key: String,
        savedAt: Date = .now
    ) {
        guard let data = try? JSONEncoder().encode(Entry(savedAt: savedAt, value: value)) else { return }
        memoryData[key] = data
        try? data.write(to: fileURL(for: key), options: .atomic)
    }

    func refresh<Value: Codable & Sendable>(
        for key: String,
        session: AnalyzerSourceSession? = nil,
        loader: @escaping @Sendable () async throws -> Value
    ) async throws -> Value {
        try session?.check()
        let generation = self.generation
        let requestKey = "\(key)|\(session?.id.uuidString ?? "")"
        if let inFlight = inFlightRefreshes[requestKey] {
            let data = try await inFlight.value
            try session?.check()
            try Task.checkCancellation()
            guard generation == self.generation else { throw CancellationError() }
            return try JSONDecoder().decode(Entry<Value>.self, from: data).value
        }

        let refresh = Task<Data, Error> {
            let value = try await loader()
            try session?.check()
            try Task.checkCancellation()
            return try JSONEncoder().encode(Entry(savedAt: .now, value: value))
        }
        inFlightRefreshes[requestKey] = refresh
        do {
            let data = try await refresh.value
            try session?.check()
            guard generation == self.generation else { throw CancellationError() }
            memoryData[key] = data
            try? data.write(to: fileURL(for: key), options: .atomic)
            inFlightRefreshes[requestKey] = nil
            return try JSONDecoder().decode(Entry<Value>.self, from: data).value
        } catch {
            if generation == self.generation { inFlightRefreshes[requestKey] = nil }
            throw error
        }
    }

    private var cacheDirectory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("APIResponseCache", isDirectory: true)
    }

    private func fileURL(for key: String) -> URL {
        let fileName = key.data(using: .utf8)?
            .base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
            ?? UUID().uuidString
        try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
        return cacheDirectory.appendingPathComponent(fileName).appendingPathExtension("json")
    }
}

extension String {
    /// Percent-encodes this string for safe use as a single URL path
    /// segment. CoreScope uses a channel's display name as its `hash`
    /// identifier (e.g. "#bot"), and building a URL by simply interpolating
    /// that into a path string turns `#` into a fragment delimiter — the
    /// server then sees a truncated path with no hash and no `/messages`
    /// suffix at all, silently succeeding with the wrong (empty) response
    /// instead of erroring. Only unreserved characters pass through
    /// unescaped; everything else (including `#`, `?`, `/`, spaces) is
    /// percent-encoded, which is correct for a single segment even though
    /// `/` would be legal in a full multi-segment path.
    var urlPathComponentEncoded: String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return addingPercentEncoding(withAllowedCharacters: allowed) ?? self
    }
}

enum APIError: Error, LocalizedError {
    case invalidURL
    case invalidResponse
    case http(Int)
    case decoding(Error)

    var errorDescription: String? {
        switch self {
        case .invalidURL: "Invalid URL"
        case .invalidResponse: "Invalid response from server"
        case .http(let code): "Server returned status \(code)"
        case .decoding(let error): "Failed to decode response: \(error.localizedDescription)"
        }
    }
}

/// Thin REST client for CoreScope's /api/* endpoints (docs/api-spec.md).
/// No authentication is required for any read endpoint.
struct APIClient: Sendable {
    private let baseURL: URL
    private let session: URLSession
    private let decoder: JSONDecoder
    private var requestAllowance: MyNodesRequestAllowance?

    /// Captures `settings.baseURL` at construction time rather than holding
    /// a reference to `AnalyzerSettings` itself, since AnalyzerSettings is a
    /// UI-bound, main-actor-mutated class and this client must stay Sendable
    /// to call safely from background tasks. Reconstruct the client (view
    /// models do this in `configure`) after the host changes.
    init(settings: AnalyzerSettings, session: URLSession = .shared) {
        self.init(baseURL: settings.baseURL, session: session)
    }

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
        self.decoder = Self.makeDecoder()
    }

    func budgetingRequests(_ budget: MyNodesRequestBudget, node: String) -> APIClient {
        var copy = self
        copy.requestAllowance = MyNodesRequestAllowance(budget: budget, node: node, cooldownKey: cacheIdentifier)
        return copy
    }

    var cacheIdentifier: String {
        baseURL.absoluteString
    }

    static func makeDecoder() -> JSONDecoder {
        let fractional = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
        let whole = Date.ISO8601FormatStyle(includingFractionalSeconds: false)

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoderInner in
            let container = try decoderInner.singleValueContainer()
            let string = try container.decode(String.self)
            if let date = try? fractional.parse(string) {
                return date
            }
            if let date = try? whole.parse(string) {
                return date
            }
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Expected ISO 8601 date, got \(string)"
            )
        }
        return decoder
    }

    func get<T: Decodable>(_ path: String, query: [URLQueryItem] = []) async throws -> T {
        guard var components = URLComponents(string: baseURL.absoluteString + path) else {
            throw APIError.invalidURL
        }
        if !query.isEmpty {
            components.queryItems = query
        }
        guard let url = components.url else { throw APIError.invalidURL }

        let data: Data
        let response: URLResponse
        if let requestAllowance {
            (data, response) = try await requestAllowance.data(for: URLRequest(url: url), session: session)
        } else {
            (data, response) = try await session.data(from: url)
        }
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw APIError.http(http.statusCode) }

        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }
}
