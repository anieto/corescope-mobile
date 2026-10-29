import Testing
@testable import CoreScopeViewer

/// Mirrors Android's `FieldGeometryTest` and the canonical geometry-v1 fixture.
struct FieldGeometryTests {
    @Test func calculatesDistanceAndInitialBearing() throws {
        let austin = try #require(FieldCoordinate(latitude: 30.2672, longitude: -97.7431))
        let dallas = try #require(FieldCoordinate(latitude: 32.7767, longitude: -96.7970))

        let result = FieldGeometry.geometry(from: austin, to: dallas)

        #expect(abs(result.distanceMeters - 293_095.4775595935) < 0.001)
        #expect(abs(try #require(result.initialBearingDegrees) - 17.57049352937031) < 0.000_001)
        #expect(result.cardinalDirection == .northNortheast)
    }

    @Test func crossesTheDateLineByTheShortPath() throws {
        let west = try #require(FieldCoordinate(latitude: 0, longitude: 179.9))
        let east = try #require(FieldCoordinate(latitude: 0, longitude: -179.9))

        let result = FieldGeometry.geometry(from: west, to: east)

        #expect(abs(result.distanceMeters - 22_239.016046706758) < 0.001)
        #expect(result.initialBearingDegrees == 90)
        #expect(result.cardinalDirection == .east)
    }

    @Test func sameCoordinateHasNoDirection() throws {
        let coordinate = try #require(FieldCoordinate(latitude: 30, longitude: -97))

        let result = FieldGeometry.geometry(from: coordinate, to: coordinate)

        #expect(result.distanceMeters == 0)
        #expect(result.initialBearingDegrees == nil)
        #expect(result.cardinalDirection == nil)
    }

    @Test func validatesCoordinateRangesButAllowsNullIsland() {
        #expect(FieldCoordinate(latitude: 0, longitude: 0) != nil)
        #expect(FieldCoordinate(latitude: 91, longitude: 0) == nil)
        #expect(FieldCoordinate(latitude: 0, longitude: -181) == nil)
        #expect(FieldCoordinate(latitude: .nan, longitude: 0) == nil)
    }

    @Test func normalizesCardinalBearings() {
        #expect(FieldGeometry.cardinalDirection(for: 0) == .north)
        #expect(FieldGeometry.cardinalDirection(for: 11.25) == .northNortheast)
        #expect(FieldGeometry.cardinalDirection(for: -90) == .west)
        #expect(FieldGeometry.cardinalDirection(for: 360) == .north)
    }

    @Test func rotatesTrueBearingRelativeToDeviceHeading() {
        #expect(FieldGeometry.relativeHeadingRotation(trueBearingDegrees: 20, deviceTrueHeadingDegrees: 350) == 30)
        #expect(FieldGeometry.relativeHeadingRotation(trueBearingDegrees: 350, deviceTrueHeadingDegrees: 20) == 330)
        #expect(FieldGeometry.relativeHeadingRotation(trueBearingDegrees: 90, deviceTrueHeadingDegrees: 90) == 0)
        #expect(FieldGeometry.relativeHeadingRotation(trueBearingDegrees: .nan, deviceTrueHeadingDegrees: 0) == nil)
    }
}
