import simd
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

    @Test func turnsTheShortWayAcrossNorth() {
        #expect(FieldGeometry.nearestEquivalentAngle(to: 1, from: 359) == 361)
        #expect(FieldGeometry.nearestEquivalentAngle(to: 359, from: 361) == 359)
        #expect(FieldGeometry.nearestEquivalentAngle(to: 90, from: 720) == 810)
        #expect(FieldGeometry.nearestEquivalentAngle(to: 45, from: nil) == 45)
    }

    // Axes below are in Core Motion's x-true-north, z-vertical frame: x north, y west, z up.

    @Test func headingFollowsTheBackOfAnUprightPhone() throws {
        // Portrait and upright, screen facing the user, back of the phone facing east.
        let heading = try #require(FieldGeometry.pointingHeadingDegrees(
            deviceTopAxis: SIMD3(0, 0, 1),
            deviceScreenAxis: SIMD3(0, 1, 0)
        ))
        #expect(abs(heading - 90) < 0.000_001)
    }

    @Test func headingFollowsTheTopEdgeOfAFlatPhone() throws {
        // Face up with the top edge pointing northeast.
        let heading = try #require(FieldGeometry.pointingHeadingDegrees(
            deviceTopAxis: SIMD3(0.5.squareRoot(), -(0.5.squareRoot()), 0),
            deviceScreenAxis: SIMD3(0, 0, 1)
        ))
        #expect(abs(heading - 45) < 0.000_001)
    }

    @Test func headingStaysPutWhileTiltingTowardUpright() throws {
        // Facing south, tilted back 60° from flat: top edge and back both lean toward the south.
        let tilt = 60.0 * .pi / 180
        let heading = try #require(FieldGeometry.pointingHeadingDegrees(
            deviceTopAxis: SIMD3(-cos(tilt), 0, sin(tilt)),
            deviceScreenAxis: SIMD3(sin(tilt), 0, cos(tilt))
        ))
        #expect(abs(heading - 180) < 0.000_001)
    }

    @Test func detectsWhetherMatrixRowsHoldTheDeviceAxes() throws {
        // Facing north, tilted back 60° from flat: right edge east, top edge up toward the north.
        let tilt = 60.0 * .pi / 180
        let deviceAxes: [SIMD3<Double>] = [
            SIMD3(0, -1, 0),
            SIMD3(cos(tilt), 0, sin(tilt)),
            SIMD3(-sin(tilt), 0, cos(tilt)),
        ]
        // Gravity in device coordinates: straight down projected onto each device axis.
        let gravity = SIMD3<Double>(0, -sin(tilt), -cos(tilt))
        let transposed = (0..<3).map { index in SIMD3(deviceAxes[0][index], deviceAxes[1][index], deviceAxes[2][index]) }

        #expect(FieldGeometry.deviceAxesUseMatrixRows(rows: deviceAxes, gravity: gravity) == true)
        #expect(FieldGeometry.deviceAxesUseMatrixRows(rows: transposed, gravity: gravity) == false)
    }

    @Test func cannotTellMatrixLayoutWhileFlat() {
        let turned: [SIMD3<Double>] = [SIMD3(0, -1, 0), SIMD3(1, 0, 0), SIMD3(0, 0, 1)]
        #expect(FieldGeometry.deviceAxesUseMatrixRows(rows: turned, gravity: SIMD3(0, 0, -1)) == nil)
    }
}
