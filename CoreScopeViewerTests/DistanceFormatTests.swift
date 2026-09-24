import Testing
@testable import CoreScopeViewer

/// Mirrors Android's `DistanceFormatTest` so both apps show the same distances.
struct DistanceFormatTests {
    @Test func imperialUsesMilesAndFeetForShortLinks() {
        #expect(DistanceUnit.imperial.format(kilometers: 10) == "6.2 mi")
        #expect(DistanceUnit.imperial.format(kilometers: 0.17) == "0.1 mi")
        #expect(DistanceUnit.imperial.format(kilometers: 0.15) == "490 ft")
        #expect(DistanceUnit.imperial.format(kilometers: 0) == "0 ft")
    }

    @Test func metricUsesKilometersAndMetersForShortLinks() {
        #expect(DistanceUnit.metric.format(kilometers: 10) == "10.0 km")
        #expect(DistanceUnit.metric.format(kilometers: 1) == "1.0 km")
        #expect(DistanceUnit.metric.format(kilometers: 0.347) == "350 m")
    }
}
