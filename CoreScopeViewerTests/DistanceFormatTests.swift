import Foundation
import Testing
@testable import CoreScopeViewer

/// Mirrors Android's `DistanceFormatTest` so both apps show the same distances.
struct DistanceFormatTests {
    private let us = Locale(identifier: "en_US")

    @Test func imperialUsesMilesAndFeetForShortLinks() {
        #expect(DistanceUnit.imperial.format(kilometers: 10, locale: us) == "6.2 mi")
        #expect(DistanceUnit.imperial.format(kilometers: 0.17, locale: us) == "0.1 mi")
        #expect(DistanceUnit.imperial.format(kilometers: 0.15, locale: us) == "490 ft")
        #expect(DistanceUnit.imperial.format(kilometers: 0, locale: us) == "0 ft")
    }

    @Test func metricUsesKilometersAndMetersForShortLinks() {
        #expect(DistanceUnit.metric.format(kilometers: 10, locale: us) == "10.0 km")
        #expect(DistanceUnit.metric.format(kilometers: 1, locale: us) == "1.0 km")
        #expect(DistanceUnit.metric.format(kilometers: 0.347, locale: us) == "350 m")
    }

    @Test func ukUsesMilesAndMetersForShortLinks() {
        let gb = Locale(identifier: "en_GB")
        #expect(DistanceUnit.uk.format(kilometers: 10, locale: gb) == "6.2 mi")
        #expect(DistanceUnit.uk.format(kilometers: 0.15, locale: gb) == "150 m")
        #expect(DistanceUnit.uk.formatHeight(meters: 6) == "6 m")
        #expect(DistanceUnit.uk.usesMiles && !DistanceUnit.uk.usesFeet)
    }

    @Test func decimalsFollowTheLocale() {
        let brazil = Locale(identifier: "pt_BR")
        #expect(DistanceUnit.metric.format(kilometers: 12.34, locale: brazil) == "12,3 km")
        #expect(DistanceUnit.imperial.format(kilometers: 10, locale: brazil) == "6,2 mi")
    }

    @Test func defaultFollowsTheRegion() {
        #expect(DistanceUnit.defaultUnit(for: Locale(identifier: "en_US")) == .imperial)
        #expect(DistanceUnit.defaultUnit(for: Locale(identifier: "en_GB")) == .uk)
        #expect(DistanceUnit.defaultUnit(for: Locale(identifier: "pt_BR")) == .metric)
        #expect(DistanceUnit.defaultUnit(for: Locale(identifier: "de_DE")) == .metric)
    }
}
