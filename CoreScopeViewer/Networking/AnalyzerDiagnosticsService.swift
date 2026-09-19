import Foundation

enum AnalyzerCapability: String, CaseIterable, Identifiable, Sendable {
    case mapConfiguration
    case nodes
    case channels
    case observers
    case regions

    var id: String { rawValue }

    var title: String {
        switch self {
        case .mapConfiguration: "Map configuration"
        case .nodes: "Nodes and routes"
        case .channels: "Channels"
        case .observers: "Observers"
        case .regions: "Region filtering"
        }
    }

    var systemImage: String {
        switch self {
        case .mapConfiguration: "map"
        case .nodes: "point.3.connected.trianglepath.dotted"
        case .channels: "bubble.left.and.bubble.right"
        case .observers: "antenna.radiowaves.left.and.right"
        case .regions: "globe.americas"
        }
    }

    fileprivate var path: String {
        switch self {
        case .mapConfiguration: "/api/config/map"
        case .nodes: "/api/nodes?limit=1"
        case .channels: "/api/channels?limit=1"
        case .observers: "/api/observers?limit=1"
        case .regions: "/api/config/regions"
        }
    }

    fileprivate var isRequired: Bool {
        self == .mapConfiguration || self == .nodes
    }
}

enum AnalyzerDiagnosticLevel: Sendable {
    case success
    case warning
    case failure
}

struct AnalyzerCapabilityCheck: Identifiable, Sendable {
    let capability: AnalyzerCapability
    let level: AnalyzerDiagnosticLevel
    let detail: String

    var id: AnalyzerCapability.ID { capability.id }
}

struct AnalyzerDiagnosticReport: Sendable {
    let host: String
    let checkedAt: Date
    let connectionLevel: AnalyzerDiagnosticLevel
    let connectionDetail: String
    let compatibilityLevel: AnalyzerDiagnosticLevel
    let compatibilityDetail: String
    let capabilities: [AnalyzerCapabilityCheck]
}

struct AnalyzerDiagnosticsService: Sendable {
    private struct ProbeResult: Sendable {
        let capability: AnalyzerCapability
        let outcome: Outcome

        enum Outcome: Sendable {
            case available(milliseconds: Int)
            case authenticationRequired(statusCode: Int)
            case unavailable(statusCode: Int)
            case invalidJSON
            case networkFailure(String)
        }

        var receivedHTTPResponse: Bool {
            switch outcome {
            case .available, .authenticationRequired, .unavailable, .invalidJSON:
                true
            case .networkFailure:
                false
            }
        }
    }

    private let baseURL: URL
    private let session: URLSession

    init(settings: AnalyzerSettings) {
        baseURL = settings.baseURL

        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 15
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration)
    }

    func run() async -> AnalyzerDiagnosticReport {
        let results = await withTaskGroup(of: ProbeResult.self, returning: [ProbeResult].self) { group in
            for capability in AnalyzerCapability.allCases {
                group.addTask {
                    await probe(capability)
                }
            }

            var values: [ProbeResult] = []
            for await result in group {
                values.append(result)
            }
            return values
        }

        let orderedResults = AnalyzerCapability.allCases.compactMap { capability in
            results.first { $0.capability == capability }
        }
        let receivedHTTPResponse = orderedResults.contains(where: \.receivedHTTPResponse)
        let authenticationFailures = orderedResults.filter {
            if case .authenticationRequired = $0.outcome { return true }
            return false
        }
        let requiredResults = orderedResults.filter(\.capability.isRequired)
        let requiredAvailable = requiredResults.allSatisfy {
            if case .available = $0.outcome { return true }
            return false
        }

        let connectionLevel: AnalyzerDiagnosticLevel
        let connectionDetail: String
        if receivedHTTPResponse {
            connectionLevel = .success
            connectionDetail = "Analyzer responded over HTTPS."
        } else {
            connectionLevel = .failure
            connectionDetail = orderedResults.compactMap {
                if case .networkFailure(let message) = $0.outcome { return message }
                return nil
            }.first ?? "The analyzer could not be reached."
        }

        let compatibilityLevel: AnalyzerDiagnosticLevel
        let compatibilityDetail: String
        if !authenticationFailures.isEmpty {
            compatibilityLevel = .failure
            compatibilityDetail = "The analyzer or an upstream proxy requires authentication."
        } else if requiredAvailable {
            compatibilityLevel = .success
            compatibilityDetail = "Core NodeScope features are supported."
        } else if receivedHTTPResponse {
            compatibilityLevel = .failure
            compatibilityDetail = "The server responded, but its API is not CoreScope-compatible."
        } else {
            compatibilityLevel = .failure
            compatibilityDetail = "Compatibility could not be checked while the analyzer is unreachable."
        }

        return AnalyzerDiagnosticReport(
            host: baseURL.host(percentEncoded: false) ?? baseURL.absoluteString,
            checkedAt: .now,
            connectionLevel: connectionLevel,
            connectionDetail: connectionDetail,
            compatibilityLevel: compatibilityLevel,
            compatibilityDetail: compatibilityDetail,
            capabilities: orderedResults.map(makeCapabilityCheck)
        )
    }

    private func probe(_ capability: AnalyzerCapability) async -> ProbeResult {
        guard let url = URL(string: capability.path, relativeTo: baseURL) else {
            return ProbeResult(
                capability: capability,
                outcome: .networkFailure("The analyzer address is invalid.")
            )
        }

        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let startedAt = Date()

        do {
            let (data, response) = try await session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return ProbeResult(capability: capability, outcome: .networkFailure("The server returned an invalid response."))
            }

            switch httpResponse.statusCode {
            case 200..<300:
                guard (try? JSONSerialization.jsonObject(with: data)) != nil else {
                    return ProbeResult(capability: capability, outcome: .invalidJSON)
                }
                let milliseconds = max(1, Int(Date().timeIntervalSince(startedAt) * 1_000))
                return ProbeResult(capability: capability, outcome: .available(milliseconds: milliseconds))
            case 401, 403:
                return ProbeResult(
                    capability: capability,
                    outcome: .authenticationRequired(statusCode: httpResponse.statusCode)
                )
            default:
                return ProbeResult(
                    capability: capability,
                    outcome: .unavailable(statusCode: httpResponse.statusCode)
                )
            }
        } catch {
            return ProbeResult(
                capability: capability,
                outcome: .networkFailure(Self.networkMessage(for: error))
            )
        }
    }

    private func makeCapabilityCheck(from result: ProbeResult) -> AnalyzerCapabilityCheck {
        switch result.outcome {
        case .available(let milliseconds):
            AnalyzerCapabilityCheck(
                capability: result.capability,
                level: .success,
                detail: "Available · \(milliseconds) ms"
            )
        case .authenticationRequired(let statusCode):
            AnalyzerCapabilityCheck(
                capability: result.capability,
                level: .failure,
                detail: "Authentication required · HTTP \(statusCode)"
            )
        case .unavailable(let statusCode):
            AnalyzerCapabilityCheck(
                capability: result.capability,
                level: result.capability.isRequired ? .failure : .warning,
                detail: statusCode == 404 ? "Not supported" : "Unavailable · HTTP \(statusCode)"
            )
        case .invalidJSON:
            AnalyzerCapabilityCheck(
                capability: result.capability,
                level: result.capability.isRequired ? .failure : .warning,
                detail: "Response format not recognized"
            )
        case .networkFailure:
            AnalyzerCapabilityCheck(
                capability: result.capability,
                level: .failure,
                detail: "Could not be checked"
            )
        }
    }

    private static func networkMessage(for error: Error) -> String {
        guard let urlError = error as? URLError else {
            return error.localizedDescription
        }

        switch urlError.code {
        case .notConnectedToInternet:
            return "This device is not connected to the internet."
        case .cannotFindHost, .dnsLookupFailed:
            return "The analyzer host could not be found."
        case .cannotConnectToHost, .networkConnectionLost:
            return "A connection to the analyzer could not be established."
        case .timedOut:
            return "The analyzer did not respond before the request timed out."
        case .serverCertificateUntrusted, .serverCertificateHasBadDate,
             .serverCertificateHasUnknownRoot, .secureConnectionFailed:
            return "A secure connection to the analyzer could not be established."
        default:
            return urlError.localizedDescription
        }
    }
}
