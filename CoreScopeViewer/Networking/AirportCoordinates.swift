import CoreLocation
import Foundation

/// Region codes are IATA airport codes. Analyzers publish centers for only
/// some of their regions (MeshTexas: 2 of 13), so the rest come from this
/// bundled table of public-domain OurAirports positions, the same file
/// Android ships (`iata-airports.csv`, rows of `CODE,lat,lon`).
enum AirportCoordinates {
    private static let table: [String: CLLocationCoordinate2D] = {
        guard let url = Bundle.main.url(forResource: "iata-airports", withExtension: "csv"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return [:]
        }
        return parse(text)
    }()

    static func coordinate(for code: String) -> CLLocationCoordinate2D? {
        table[code.uppercased()]
    }

    /// Same rules as Android `parseAirportTable`: malformed rows are skipped.
    static func parse(_ text: String) -> [String: CLLocationCoordinate2D] {
        var result: [String: CLLocationCoordinate2D] = [:]
        for line in text.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count == 3, parts[0].count == 3,
                  let latitude = Double(parts[1]), let longitude = Double(parts[2]),
                  (-90...90).contains(latitude), (-180...180).contains(longitude) else {
                continue
            }
            result[parts[0].uppercased()] = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        }
        return result
    }
}
