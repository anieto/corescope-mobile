import Observation
import SwiftUI

/// The app-wide light/dark/system preference. Nothing reads the system
/// color scheme directly to decide this — every screen that needs to know
/// "are we in dark mode" (e.g. the map's route colors) still reads
/// `\.colorScheme` from the environment, which SwiftUI resolves correctly
/// once `.preferredColorScheme(mode.colorScheme)` is applied at the root.
@Observable
final class AppearanceSettings {
    enum Mode: String, CaseIterable, Identifiable {
        case system
        case light
        case dark

        var id: String { rawValue }

        var label: String {
            switch self {
            case .system: "System"
            case .light: "Light"
            case .dark: "Dark"
            }
        }

        /// `nil` tells SwiftUI to defer to the system setting.
        var colorScheme: ColorScheme? {
            switch self {
            case .system: nil
            case .light: .light
            case .dark: .dark
            }
        }
    }

    private static let defaultsKey = "appearanceMode"

    var mode: Mode {
        didSet { UserDefaults.standard.set(mode.rawValue, forKey: Self.defaultsKey) }
    }

    init() {
        if let raw = UserDefaults.standard.string(forKey: Self.defaultsKey),
           let restored = Mode(rawValue: raw) {
            mode = restored
        } else {
            mode = .system
        }
    }
}
