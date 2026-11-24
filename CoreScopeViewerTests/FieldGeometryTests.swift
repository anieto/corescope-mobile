import Foundation
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

    @Test func showsCameraOffsetsOnTheCorrectSideAcrossNorth() throws {
        let right = try #require(FieldGeometry.cameraHorizontalOffset(bearing: 10, heading: 350, halfWidthTangent: 0.5))
        let left = try #require(FieldGeometry.cameraHorizontalOffset(bearing: 350, heading: 10, halfWidthTangent: 0.5))
        #expect(abs(right - 0.72794) < 0.00001)
        #expect(right == -left)
        let cropped = try #require(FieldGeometry.cameraHorizontalOffset(bearing: 10, heading: 350, halfWidthTangent: 0.25))
        #expect(cropped > 1)
        #expect(FieldGeometry.cameraHorizontalOffset(bearing: 230, heading: 0, halfWidthTangent: 0.5) == -.infinity)
    }

    @Test func cameraHeadingUsesTheRearLensAndIgnoresVerticalPointing() {
        #expect(FieldGeometry.rearCameraHeadingDegrees(deviceScreenAxis: SIMD3(-1, 0, 0)) == 0)
        #expect(FieldGeometry.rearCameraHeadingDegrees(deviceScreenAxis: SIMD3(0, 1, 0)) == 90)
        #expect(FieldGeometry.rearCameraHeadingDegrees(deviceScreenAxis: SIMD3(0, 0, 1)) == nil)
    }

    @Test func bearingGuideMovesOppositeCameraTiltWithoutAltitude() {
        let level = FieldGeometry.cameraBearingGuideOffset(pitch: 0, halfHeightTangent: 0.5)
        let up = FieldGeometry.cameraBearingGuideOffset(pitch: 10, halfHeightTangent: 0.5)
        let down = FieldGeometry.cameraBearingGuideOffset(pitch: -10, halfHeightTangent: 0.5)
        #expect(abs(level - 0.14) < 0.00001)
        #expect(up < 0 && down > level)
    }

    @Test func projectsAntennaElevationAndRejectsUncertainty() throws {
        let level = try #require(FieldGeometry.cameraVerticalOffset(
            distance: 500, antennaElevation: 160, phoneElevation: 100,
            phoneAccuracy: 5, pitch: 0, halfHeightTangent: 0.5))
        let tilted = try #require(FieldGeometry.cameraVerticalOffset(
            distance: 500, antennaElevation: 160, phoneElevation: 100,
            phoneAccuracy: 5, pitch: 10, halfHeightTangent: 0.5))
        #expect(level > 0 && tilted < 0)
        #expect(FieldGeometry.cameraVerticalOffset(distance: 16_737, antennaElevation: 203,
            phoneElevation: 288.8, phoneAccuracy: 30, pitch: 0, halfHeightTangent: 0.5) != nil)
        #expect(FieldGeometry.cameraVerticalOffset(distance: 46, antennaElevation: 291.6,
            phoneElevation: 288.8, phoneAccuracy: 30, pitch: 0, halfHeightTangent: 0.5) == nil)
        #expect(FieldGeometry.cameraVerticalOffset(distance: 46, antennaElevation: 292,
            phoneElevation: 285, phoneAccuracy: 5, pitch: 0, halfHeightTangent: 0.5) != nil)
        #expect(FieldGeometry.cameraElevationUncertainty(distance: 46, phoneAccuracy: 5) > 8)
        #expect(FieldGeometry.cameraVerticalOffset(distance: 30, antennaElevation: 160,
            phoneElevation: 100, phoneAccuracy: 5, pitch: 0, halfHeightTangent: 0.5) == nil)
        #expect(FieldGeometry.cameraVerticalOffset(distance: 50, antennaElevation: 160,
            phoneElevation: 100, phoneAccuracy: 20, pitch: 0, halfHeightTangent: 0.5) == nil)
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


struct NodeVisionElevationTests {
    @Test func discoveryMarkersLeaveTheEdgesWhenTiltedFartherAway() {
        #expect(discoveryMarkerY(projectedY: -24, paneHeight: 800, minimumY: 166, maximumY: 700) == 166)
        #expect(discoveryMarkerY(projectedY: 824, paneHeight: 800, minimumY: 166, maximumY: 700) == 700)
        #expect(discoveryMarkerY(projectedY: -25, paneHeight: 800, minimumY: 166, maximumY: 700) == nil)
        #expect(discoveryMarkerY(projectedY: 825, paneHeight: 800, minimumY: 166, maximumY: 700) == nil)
    }

    @Test func reportedAndDefaultHeights() {
        #expect(VisionAntennaHeight(role: "repeater", reported: 12).meters == 12)
        #expect(!VisionAntennaHeight(role: "repeater", reported: 12).approximate)
        for role in ["repeater", "room", "room-server"] {
            #expect(VisionAntennaHeight(role: role, reported: nil).meters == CoverageHeightPreset.rooftop.meters)
            #expect(VisionAntennaHeight(role: role, reported: nil).approximate)
        }
        #expect(VisionAntennaHeight(role: "companion", reported: 12).meters == CoverageHeightPreset.handheld.meters)
    }

    @Test func placementModeAppliesToEveryMarker() {
        let distances = [30.0, 500]
        for mode in [
            VisionElevationMode(altitude: nil, accuracy: 2, distances: distances, nearDistance: 5),
            VisionElevationMode(altitude: 100, accuracy: 10, distances: distances, nearDistance: 5)
        ] {
            #expect(mode == .guide)
            #expect(mode.offset(distance: 30, antenna: nil, pitch: 2, tangent: 0.5) ==
                    mode.offset(distance: 500, antenna: 150, pitch: 2, tangent: 0.5))
        }
        let mode = VisionElevationMode(altitude: 100, accuracy: 2, distances: [500], nearDistance: 5)
        #expect(mode == .elevation(altitude: 100, accuracy: 2))
        #expect(mode.offset(distance: 500, antenna: nil, pitch: 2, tangent: 0.5) == nil)
        #expect(mode.offset(distance: 500, antenna: 150, pitch: 2, tangent: 0.5) != nil)
    }

    @Test func altitudeDeadbandAccumulatesChanges() {
        let stable = stableVisionAltitude(previous: 100, current: 100.2, distance: 100)
        #expect(stable == 100)
        #expect(stableVisionAltitude(previous: stable, current: 100.3, distance: 100) == 100.3)
        #expect(stableVisionAltitude(previous: stable, current: nil, distance: 100) == nil)
    }

    @Test func terrainBatchesCacheByNodeAndCoordinateAndKeepSuccessfulBatches() async throws {
        let provider = VisionTestTerrainProvider()
        let cache = VisionTerrainCache(provider: provider)
        let points = try Set((0..<101).map { index in
            let coordinate = try #require(FieldCoordinate(latitude: Double(index) / 100, longitude: 0))
            return try #require(VisionTerrainPoint(key: String(format: "%03d", index), coordinate: coordinate))
        })
        let first = await cache.resolve(points)
        #expect(first.count == 100)
        #expect(await provider.requests.map(\.count) == [100, 2])
        let cachedPoints = Set(first.keys)
        _ = await cache.resolve(points)
        #expect(await provider.requests.count == 2)
        let coordinate = try #require(FieldCoordinate(latitude: 2, longitude: 0))
        let moved = try #require(VisionTerrainPoint(key: "000", coordinate: coordinate))
        let updated = await cache.resolve(cachedPoints.union([moved]))
        #expect(updated[moved] == 42)
        #expect(await provider.requests.last == [moved.coordinate, moved.coordinate])
    }
}

private actor VisionTestTerrainProvider: NodePlannerElevationProviding {
    private(set) var requests: [[NodePlannerCoordinate]] = []

    func elevations(for coordinates: [NodePlannerCoordinate]) async throws -> NodePlannerTerrainProfile {
        requests.append(coordinates)
        if requests.count == 2 { throw NodePlannerElevationError.unavailable }
        return NodePlannerTerrainProfile(
            samples: coordinates.map { NodePlannerTerrainSample(coordinate: $0, elevationMeters: 42) },
            provenance: NodePlannerElevationProvenance(fetchedAt: .now, origin: .network)
        )
    }
}
