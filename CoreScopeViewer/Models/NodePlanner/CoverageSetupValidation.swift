import Foundation

enum CoverageSetupIssue: String, Sendable {
    case required, number, latitude, longitude, frequency, height, nonnegative, unknownField
}

/// Basic editor validation. Passing does not establish terrain availability or RF model suitability.
enum CoverageSetupValidation {
    static func supportsCoordinate(latitude: Double, longitude: Double) -> Bool {
        latitude.isFinite && longitude.isFinite && (-85...85).contains(latitude) && (-180...180).contains(longitude)
    }

    static func number(_ text: String) -> Double? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let pattern = #"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$"#
        guard value.range(of: pattern, options: .regularExpression) == value.startIndex..<value.endIndex,
              !value.isEmpty, let result = Double(value), result.isFinite else { return nil }
        return result
    }

    static func issue(field: String, text: String, heightInFeet: Bool = false) -> CoverageSetupIssue? {
        guard ["latitude", "longitude", "frequency", "power", "txHeight", "txGain", "txLoss",
               "rxHeight", "rxGain", "rxLoss", "sensitivity", "reserve"].contains(field) else { return .unknownField }
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .required }
        guard let parsed = number(text) else { return .number }
        let value = heightInFeet && ["txHeight", "rxHeight"].contains(field) ? parsed * 0.3048 : parsed
        switch field {
        case "latitude": return (-85...85).contains(value) ? nil : .latitude
        case "longitude": return (-180...180).contains(value) ? nil : .longitude
        case "frequency": return (30...6000).contains(value) ? nil : .frequency
        case "txHeight", "rxHeight": return (1...3000).contains(value) ? nil : .height
        case "txLoss", "rxLoss", "reserve": return value >= 0 ? nil : .nonnegative
        default: return nil
        }
    }
}
