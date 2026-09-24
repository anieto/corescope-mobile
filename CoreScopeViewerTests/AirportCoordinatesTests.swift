import CoreLocation
import Testing
@testable import CoreScopeViewer

/// Region framing uses the bundled airport table before Apple's airport
/// search, which sends SJT and ACT to San Antonio International.
struct AirportCoordinatesTests {
    private func expect(_ code: String, near latitude: Double, _ longitude: Double) {
        let coordinate = AirportCoordinates.coordinate(for: code)
        #expect(coordinate != nil, "\(code) missing")
        if let coordinate {
            #expect(abs(coordinate.latitude - latitude) < 0.2 && abs(coordinate.longitude - longitude) < 0.2, "\(code) at \(coordinate)")
        }
    }

    @Test func meshTexasRegionsResolveToTheirOwnCities() {
        expect("SJT", near: 31.36, -100.50) // San Angelo
        expect("ACT", near: 31.61, -97.23) // Waco
        expect("GGG", near: 32.38, -94.71) // Longview
        expect("sat", near: 29.53, -98.47) // case-insensitive
    }

    @Test func unknownCodesAndMalformedRowsAreSkipped() {
        #expect(AirportCoordinates.coordinate(for: "ZZ9") == nil)
        let parsed = AirportCoordinates.parse("AAA,1.0,2.0\nBB,1,2\nCCC,x,2\nDDD,95,2\n\n")
        #expect(Set(parsed.keys) == ["AAA"])
    }
}
