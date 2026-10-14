import Foundation

enum AnalyzerCapability: String, CaseIterable, Identifiable, Sendable {
    case mapConfiguration
    case nodes
    case channels
    case observers
    case regions
    case neighbors
    case nodeAnalytics
    case observerActivity
    case observerTelemetry
    case channelMessages

    var id: String { rawValue }

    var title: String {
        switch self {
        case .mapConfiguration: "Map configuration"
        case .nodes: "Nodes and routes"
        case .channels: "Channels"
        case .observers: "Observers"
        case .regions: "Region filtering"
        case .neighbors: "Node neighbors"
        case .nodeAnalytics: "Node analytics"
        case .observerActivity: "Observer activity"
        case .observerTelemetry: "Observer telemetry"
        case .channelMessages: "Channel messages"
        }
    }

    var systemImage: String {
        switch self {
        case .mapConfiguration: "map"
        case .nodes: "point.3.connected.trianglepath.dotted"
        case .channels: "bubble.left.and.bubble.right"
        case .observers: "antenna.radiowaves.left.and.right"
        case .regions: "globe.americas"
        case .neighbors: "point.3.connected.trianglepath.dotted"
        case .nodeAnalytics: "chart.xyaxis.line"
        case .observerActivity: "waveform.path.ecg"
        case .observerTelemetry: "gauge.with.dots.needle.33percent"
        case .channelMessages: "text.bubble"
        }
    }

    fileprivate var path: String? {
        switch self {
        case .mapConfiguration: "/api/config/map"
        case .nodes: "/api/nodes?limit=1"
        case .channels: "/api/channels?limit=1"
        case .observers: "/api/observers?limit=1"
        case .regions: "/api/config/regions"
        case .neighbors, .nodeAnalytics, .observerActivity, .observerTelemetry, .channelMessages: nil
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
    var backendType = "CoreScope"
    var serverVersion: String? = nil
}

struct AnalyzerDiagnosticsService: Sendable {
    private enum BeaconProbeFamily: Hashable, Sendable {
        case nodes, observers, channels
    }

    private struct BeaconProbePage: Decodable, Sendable {
        struct Item: Decodable, Sendable {}
        let items: [Item]
    }

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
    private let beaconBackend: BeaconBackend?
    private static let coreScopeProbedCapabilities: [AnalyzerCapability] = [
        .mapConfiguration, .nodes, .channels, .observers, .regions
    ]

    init(settings: AnalyzerSettings) {
        baseURL = settings.baseURL
        beaconBackend = settings.beaconBackend

        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 15
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration)
    }

    func run() async -> AnalyzerDiagnosticReport {
        if let beaconBackend { return await runBeacon(beaconBackend) }
        let results = await withTaskGroup(of: ProbeResult.self, returning: [ProbeResult].self) { group in
            for capability in Self.coreScopeProbedCapabilities {
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

        let orderedResults = Self.coreScopeProbedCapabilities.compactMap { capability in
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

    private func runBeacon(_ backend: BeaconBackend) async -> AnalyzerDiagnosticReport {
        var report = AnalyzerDiagnosticReport(
            host: baseURL.host(percentEncoded: false) ?? baseURL.absoluteString, checkedAt: .now,
            connectionLevel: .warning, connectionDetail: "Connection has not been checked.",
            compatibilityLevel: .warning, compatibilityDetail: "Compatibility has not been checked.",
            capabilities: [], backendType: "Beacon", serverVersion: backend.serverInfo.serverVersion
        )
        let checks = AnalyzerCapability.allCases.map { capability in
            if capability == .mapConfiguration {
                return AnalyzerCapabilityCheck(
                    capability: capability, level: .warning,
                    detail: "Beacon does not provide map defaults; NodeScope uses region coordinates."
                )
            }
            let backendCapability: BackendCapability? = switch capability {
            case .neighbors: .neighbors
            case .nodeAnalytics: .nodeAnalytics
            case .observerActivity, .observerTelemetry: .observerAnalytics
            case .channels, .channelMessages: .serverChannels
            case .mapConfiguration, .nodes, .observers, .regions: nil
            }
            if let backendCapability, !backend.capabilities.contains(backendCapability) {
                return AnalyzerCapabilityCheck(
                    capability: capability, level: .warning,
                    detail: backendCapability.beaconExplanation
                )
            }
            let detail: String = switch capability {
            case .nodeAnalytics:
                "Supported with Beacon’s seven-day observation-history limit; endpoint not probed."
            case .observerActivity:
                "Supported with Beacon observation counts; endpoint not probed."
            case .observerTelemetry:
                "Battery, airtime, and radio telemetry are supported; endpoint not probed."
            case .channels, .channelMessages:
                "Server-decrypted keyed channels are supported; endpoint not probed."
            default:
                "Supported by the Beacon adapter; endpoint not probed."
            }
            return AnalyzerCapabilityCheck(
                capability: capability, level: .success,
                detail: detail
            )
        }
        do {
            let info = try await backend.client.serverInfo()
            let compatibility: AnalyzerDiagnosticLevel
            let detail: String
            var reportedChecks = checks
            do {
                try info.requireSupported()
                compatibility = .success
                reportedChecks = await beaconCapabilityChecks(backend: backend, defaults: checks)
                detail = "Beacon version and bounded browse-family checks completed."
            } catch {
                compatibility = .failure
                detail = error.localizedDescription
            }
            report = AnalyzerDiagnosticReport(
                host: report.host, checkedAt: .now, connectionLevel: .success,
                connectionDetail: "Beacon responded over HTTPS.", compatibilityLevel: compatibility,
                compatibilityDetail: detail, capabilities: reportedChecks, backendType: "Beacon",
                serverVersion: info.serverVersion
            )
        } catch {
            report = AnalyzerDiagnosticReport(
                host: report.host, checkedAt: .now, connectionLevel: .warning,
                connectionDetail: error.localizedDescription, compatibilityLevel: .warning,
                compatibilityDetail: "Beacon could not be rechecked. The selected server type is unchanged.",
                capabilities: checks, backendType: "Beacon", serverVersion: backend.serverInfo.serverVersion
            )
        }
        return report
    }

    private func beaconCapabilityChecks(
        backend: BeaconBackend,
        defaults: [AnalyzerCapabilityCheck]
    ) async -> [AnalyzerCapabilityCheck] {
        let results = await withTaskGroup(of: (BeaconProbeFamily, Bool).self) { group in
            group.addTask {
                let page: BeaconProbePage? = try? await backend.client.get(["nodes"], query: [.init(name: "limit", value: "1")])
                return (.nodes, page != nil)
            }
            group.addTask {
                let page: BeaconProbePage? = try? await backend.client.get(["observers"], query: [.init(name: "limit", value: "1")])
                return (.observers, page != nil)
            }
            group.addTask {
                let page: BeaconProbePage? = try? await backend.client.get(
                    ["channels"], query: [.init(name: "limit", value: "1"), .init(name: "keyKnown", value: "true")]
                )
                return (.channels, page != nil)
            }
            var values: [BeaconProbeFamily: Bool] = [:]
            for await (family, succeeded) in group { values[family] = succeeded }
            return values
        }

        return defaults.map { check in
            let family: BeaconProbeFamily? = switch check.capability {
            case .nodes, .neighbors, .nodeAnalytics: .nodes
            case .observers, .observerActivity, .observerTelemetry: .observers
            case .channels, .channelMessages: .channels
            case .mapConfiguration, .regions: nil
            }
            guard let family else { return check }
            guard results[family] == true else {
                return AnalyzerCapabilityCheck(
                    capability: check.capability,
                    level: .warning,
                    detail: "The bounded Beacon family check did not succeed."
                )
            }
            return AnalyzerCapabilityCheck(
                capability: check.capability,
                level: check.level,
                detail: check.detail.replacingOccurrences(of: "; endpoint not probed", with: "; family check passed")
                    .replacingOccurrences(of: "; endpoint not probed.", with: "; family check passed.")
                    .replacingOccurrences(of: "Supported by the Beacon adapter; endpoint not probed.", with: "Supported by the Beacon adapter; family check passed.")
            )
        }
    }

    private func probe(_ capability: AnalyzerCapability) async -> ProbeResult {
        guard let path = capability.path,
              let url = URL(string: path, relativeTo: baseURL) else {
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
