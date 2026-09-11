import Foundation

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

    /// Captures `settings.baseURL` at construction time rather than holding
    /// a reference to `AnalyzerSettings` itself, since AnalyzerSettings is a
    /// UI-bound, main-actor-mutated class and this client must stay Sendable
    /// to call safely from background tasks. Reconstruct the client (view
    /// models do this in `configure`) after the host changes.
    init(settings: AnalyzerSettings, session: URLSession = .shared) {
        self.baseURL = settings.baseURL
        self.session = session
        self.decoder = Self.makeDecoder()
    }

    var cacheIdentifier: String {
        baseURL.absoluteString
    }

    private static func makeDecoder() -> JSONDecoder {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        let whole = ISO8601DateFormatter()
        whole.formatOptions = [.withInternetDateTime]

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoderInner in
            let container = try decoderInner.singleValueContainer()
            let string = try container.decode(String.self)
            if let date = fractional.date(from: string) ?? whole.date(from: string) {
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

        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw APIError.http(http.statusCode) }

        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }
}
