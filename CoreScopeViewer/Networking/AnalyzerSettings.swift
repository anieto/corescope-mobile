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

    var host: String {
        didSet {
            UserDefaults.standard.set(host, forKey: Self.hostDefaultsKey)
        }
    }

    init() {
        host = UserDefaults.standard.string(forKey: Self.hostDefaultsKey) ?? Self.defaultHost
    }

    var baseURL: URL {
        URL(string: "https://\(normalizedHost)")!
    }

    var webSocketURL: URL {
        URL(string: "wss://\(normalizedHost)")!
    }

    private var normalizedHost: String {
        host
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "wss://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .replacingOccurrences(of: "ws://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }
}
