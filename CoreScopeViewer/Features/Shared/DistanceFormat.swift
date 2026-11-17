import Foundation

/// How distances are shown, chosen in Settings → Units. Until the user picks one, the default
/// follows the device region: US customary in the US, miles with metric heights in the UK,
/// and metric everywhere else.
enum DistanceUnit: String, CaseIterable, Identifiable {
    case imperial
    case uk
    case metric

    static let defaultsKey = "distanceUnit"

    var id: String { rawValue }

    /// The default for a region's measurement system, used until the user chooses a unit.
    static func defaultUnit(for locale: Locale) -> DistanceUnit {
        switch locale.measurementSystem {
        case .us: .imperial
        case .uk: .uk
        default: .metric
        }
    }

    static var localeDefault: DistanceUnit { defaultUnit(for: .current) }

    /// The user's choice, or the region default when they haven't chosen. For code outside a view;
    /// views read the same value with `@AppStorage(DistanceUnit.defaultsKey) … = .localeDefault`.
    static var current: DistanceUnit {
        UserDefaults.standard.string(forKey: defaultsKey).flatMap(DistanceUnit.init(rawValue:)) ?? localeDefault
    }

    var label: String {
        switch self {
        case .imperial: String(localized: "US (mi, ft)")
        case .uk: String(localized: "UK (mi, m)")
        case .metric: String(localized: "Metric")
        }
    }

    /// Long distances (links, radius, spacing) in miles rather than kilometers.
    var usesMiles: Bool { self != .metric }

    /// Heights and elevations in feet rather than meters. Only US customary uses feet;
    /// the UK gives heights in meters.
    var usesFeet: Bool { self == .imperial }

    /// Same rule as Android `formatDistance`: miles or kilometers to one decimal place, and
    /// short distances (under 0.1 mi or 1 km) in the height unit, rounded to 10. Decimals
    /// follow `locale`.
    func format(kilometers km: Double, locale: Locale = .current) -> String {
        let oneDecimal = FloatingPointFormatStyle<Double>.number.precision(.fractionLength(1)).locale(locale)
        if usesMiles {
            let miles = km * 0.621371
            if miles >= 0.1 { return "\(miles.formatted(oneDecimal)) mi" }
        } else if km >= 1 {
            return "\(km.formatted(oneDecimal)) km"
        }
        if usesFeet { return "\(Int((km * 3280.84 / 10).rounded()) * 10) ft" }
        return "\(Int((km * 100).rounded()) * 10) m"
    }

    var antennaHeightRange: ClosedRange<Double> {
        switch self {
        case .imperial: 0...100
        case .uk, .metric: 0...30
        }
    }

    var heightSymbol: String {
        switch self {
        case .imperial: "ft"
        case .uk, .metric: "m"
        }
    }

    func displayHeight(meters: Double) -> Double {
        switch self {
        case .imperial: meters * 3.28084
        case .uk, .metric: meters
        }
    }

    func meters(fromDisplayedHeight value: Double) -> Double {
        switch self {
        case .imperial: value / 3.28084
        case .uk, .metric: value
        }
    }

    func formatHeight(meters: Double) -> String {
        let value = displayHeight(meters: meters)
        return "\(value.formatted(.number.precision(.fractionLength(0)))) \(heightSymbol)"
    }
}
