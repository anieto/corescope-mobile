import Foundation

/// How distances are shown, chosen in Settings → Units. Imperial is the
/// default because the app's communities are in the US.
enum DistanceUnit: String, CaseIterable, Identifiable {
    case imperial
    case metric

    static let defaultsKey = "distanceUnit"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .imperial: "Imperial (mi)"
        case .metric: "Metric (km)"
        }
    }

    /// Same rule as Android `formatDistance`: miles or kilometers to one
    /// decimal place, and short distances in feet (under 0.1 mi) or meters
    /// (under 1 km), rounded to 10.
    func format(kilometers km: Double) -> String {
        switch self {
        case .imperial:
            let miles = km * 0.621371
            if miles < 0.1 {
                return "\(Int((km * 3280.84 / 10).rounded()) * 10) ft"
            }
            return String(format: "%.1f mi", locale: Locale(identifier: "en_US_POSIX"), miles)
        case .metric:
            if km < 1 {
                return "\(Int((km * 100).rounded()) * 10) m"
            }
            return String(format: "%.1f km", locale: Locale(identifier: "en_US_POSIX"), km)
        }
    }
}
