import Foundation
import Observation

/// Holds the single configurable CoreScope host the app talks to. Defaults
/// to MeshTexas's analyzer, but any CoreScope-hosting community's host can
/// be substituted here without any other code change, since every screen
/// derives its region/area/map config from that host's own /api/config/*
/// endpoints rather than assuming Texas-specific values.
@Observable
final class AnalyzerSettings {
    static let defaultHost = "analyzer.meshtexas.org"

    private static let hostDefaultsKey = "analyzerHost"
    private(set) var sourceSession = AnalyzerSourceSession()
    private(set) var beaconBackend: BeaconBackend?
    private(set) var backendSelectionError: String?
    private(set) var sourceIsReady = false
    private(set) var sourceResolutionID = UUID()
    private var resolvingID: UUID?

    var host: String {
        didSet {
            if Self.normalizedHost(oldValue).lowercased() != Self.normalizedHost(host).lowercased() {
                beaconBackend = nil
                backendSelectionError = nil
                renewSourceSession()
                requestSourceResolution()
            }
            UserDefaults.standard.set(host, forKey: Self.hostDefaultsKey)
        }
    }

    init() {
        host = UserDefaults.standard.string(forKey: Self.hostDefaultsKey) ?? Self.defaultHost
    }

    func requestSourceResolution() {
        sourceIsReady = false
        backendSelectionError = nil
        sourceResolutionID = UUID()
    }

    @MainActor
    func resolveSource(
        knownCoreScope: Bool, requiresBeacon: Bool = false,
        catalog: BeaconServerCatalog = .shared, session: URLSession = .shared
    ) async {
        let resolutionID = sourceResolutionID
        guard !sourceIsReady, resolvingID != resolutionID else { return }
        resolvingID = resolutionID
        defer { if resolvingID == resolutionID { resolvingID = nil } }
        let capturedSession = sourceSession
        do {
            let client = try BeaconAPIClient(baseURL: baseURL, sourceSession: capturedSession, session: session)
            let remembered = await catalog.knownInfo(for: client)
            try capturedSession.check()
            guard sourceResolutionID == resolutionID else { return }
            if let remembered {
                try selectBeacon(verifiedServerInfo: remembered, expectedSessionID: capturedSession.id, session: session)
            } else if beaconBackend != nil {
                try beaconBackend?.serverInfo.requireSupported()
            } else if requiresBeacon || !knownCoreScope {
                do {
                    let info = try await catalog.resolve(using: client)
                    try capturedSession.check()
                    guard sourceResolutionID == resolutionID else { return }
                    try selectBeacon(verifiedServerInfo: info, expectedSessionID: capturedSession.id, session: session)
                } catch BeaconAPIError.server(let status, _) where status == 404 && !requiresBeacon {
                    // An absent Beacon endpoint permits a positive CoreScope compatibility check.
                    try await Self.confirmCoreScope(baseURL: client.baseURL, session: session, sourceSession: capturedSession)
                } catch is DecodingError where !requiresBeacon {
                    // So does a successful answer that isn't Beacon JSON: CoreScope serves its web
                    // page (200, HTML) for every unknown path, /api/v1/info included. Errors,
                    // timeouts and throttling still never count as evidence of CoreScope.
                    try await Self.confirmCoreScope(baseURL: client.baseURL, session: session, sourceSession: capturedSession)
                }
            }
            guard sourceResolutionID == resolutionID, sourceSession.isCurrent, !Task.isCancelled else { return }
            backendSelectionError = nil
            sourceIsReady = true
        } catch {
            guard sourceResolutionID == resolutionID, sourceSession.id == capturedSession.id else { return }
            backendSelectionError = error is CancellationError
                ? String(localized: "Source detection was interrupted. Retry to continue.") : error.localizedDescription
        }
    }

    /// The positive CoreScope check required before a custom host is treated as CoreScope.
    private static func confirmCoreScope(baseURL: URL, session: URLSession, sourceSession: AnalyzerSourceSession) async throws {
        let backend = CoreScopeBackend(client: APIClient(baseURL: baseURL, session: session), sourceSession: sourceSession)
        _ = try await backend.mapDefaults()
        try sourceSession.check()
    }

    // Detection must also call this if the backend kind changes without a host change.
    func renewSourceSession() {
        sourceSession.invalidate()
        sourceSession = AnalyzerSourceSession()
        if let previous = beaconBackend {
            do {
                beaconBackend = try BeaconBackend(client: previous.client.replacingSourceSession(sourceSession),
                                                   verifiedServerInfo: previous.serverInfo)
                backendSelectionError = nil
            } catch {
                // Fail closed: an invalid Beacon selection must never route requests to CoreScope.
                sourceSession.invalidate()
                backendSelectionError = error.localizedDescription
            }
        }
    }

    func selectBeacon(
        verifiedServerInfo: BeaconServerInfo, expectedSessionID: UUID, session: URLSession = .shared
    ) throws {
        try sourceSession.check()
        guard sourceSession.id == expectedSessionID else { throw CancellationError() }
        try verifiedServerInfo.requireSupported()
        if beaconBackend?.serverInfo == verifiedServerInfo { return }
        let nextSession = AnalyzerSourceSession()
        let client = try BeaconAPIClient(baseURL: baseURL, sourceSession: nextSession, session: session)
        let backend = try BeaconBackend(client: client, verifiedServerInfo: verifiedServerInfo)
        sourceSession.invalidate()
        beaconBackend = backend
        backendSelectionError = nil
        sourceSession = nextSession
    }

    var baseURL: URL {
        URL(string: "https://\(normalizedHost)")!
    }

    var webSocketURL: URL {
        URL(string: "wss://\(normalizedHost)")!
    }

    static func normalizedHost(_ value: String) -> String {
        var candidate = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !candidate.isEmpty else { return "" }

        if !candidate.contains("://") {
            candidate = "https://\(candidate)"
        }

        guard let components = URLComponents(string: candidate),
              let host = components.host else {
            return value
                .replacingOccurrences(of: "https://", with: "")
                .replacingOccurrences(of: "wss://", with: "")
                .replacingOccurrences(of: "http://", with: "")
                .replacingOccurrences(of: "ws://", with: "")
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        }

        if let port = components.port {
            return "\(host):\(port)"
        }
        return host
    }

    private var normalizedHost: String {
        Self.normalizedHost(host)
    }
}
