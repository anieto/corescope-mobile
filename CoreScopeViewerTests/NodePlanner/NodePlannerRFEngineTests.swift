import Testing
@testable import CoreScopeViewer

struct NodePlannerRFEngineTests {
    private let primitiveTolerance = 1e-9
    private let pathTolerance = 1e-8

    @Test func matchesPrimitiveGoldenValues() {
        #expect(abs(NodePlannerRFEngine.wavelength(frequencyMHz: 915) - 0.32764203060109287) < primitiveTolerance)
        #expect(abs(NodePlannerRFEngine.fresnelRadius(
            frequencyMHz: 915,
            distanceToAMeters: 5_000,
            distanceToBMeters: 5_000
        ) - 28.620011818703574) < primitiveTolerance)
        #expect(abs(NodePlannerRFEngine.earthBulge(
            distanceToAMeters: 5_000,
            distanceToBMeters: 5_000,
            refractionK: 4.0 / 3.0
        ) - 1.4715115366504474) < primitiveTolerance)
        #expect(abs(NodePlannerRFEngine.pathLoss(
            distanceMeters: 10_000,
            frequencyMHz: 915
        ) - 111.67842188132899) < primitiveTolerance)
        #expect(abs(NodePlannerRFEngine.diffractionLoss(
            obstructionHeightMeters: 0,
            distanceToAMeters: 5_000,
            distanceToBMeters: 5_000,
            frequencyMHz: 915
        ) - 6.032852208563606) < primitiveTolerance)
    }

    @Test func matchesClearPathFixture() throws {
        let result = try NodePlannerRFEngine.analyze(request(midpointElevation: 0, antennaHeight: 50))

        #expect(abs(result.totalPathLossDB - 111.67842188132899) < pathTolerance)
        #expect(abs(result.worstClearancePercent - 169.5613851271561) < pathTolerance)
        #expect(result.clearanceStatus == .clear)
        #expect(result.obstructions.isEmpty)
    }

    @Test func matchesMarginalPathFixture() throws {
        let result = try NodePlannerRFEngine.analyze(request(midpointElevation: 0, antennaHeight: 20))

        #expect(abs(result.totalPathLossDB - 111.67842188132899) < pathTolerance)
        #expect(abs(result.worstClearancePercent - 64.73962547856438) < pathTolerance)
        #expect(result.clearanceStatus == .marginal)
        #expect(result.obstructions.isEmpty)
    }

    @Test func matchesBlockedPathFixture() throws {
        let result = try NodePlannerRFEngine.analyze(request(midpointElevation: 30, antennaHeight: 10))

        #expect(abs(result.peakDiffractionLossDB - 14.313515197566385) < pathTolerance)
        #expect(abs(result.totalPathLossDB - 125.99193707889538) < pathTolerance)
        #expect(abs(result.worstClearancePercent - -75.0227207195579) < pathTolerance)
        #expect(result.clearanceStatus == .blocked)
        #expect(result.obstructions.count == 1)
    }

    @Test func calculatesLinkBudgetFixture() throws {
        let budget = NodePlannerLinkBudget(
            transmitPowerDBm: 22,
            gainADB: 3,
            gainBDB: 6,
            lossADB: 1,
            lossBDB: 2,
            receiverSensitivityDBm: -120
        )
        let result = try NodePlannerRFEngine.analyze(
            request(midpointElevation: 0, antennaHeight: 50, linkBudget: budget)
        )

        #expect(abs(try #require(result.receivePowerDBm) - -83.67842188132899) < pathTolerance)
        #expect(abs(try #require(result.linkMarginDB) - 36.32157811867101) < pathTolerance)
    }

    @Test func rejectsInvalidRequests() {
        #expect(throws: NodePlannerRFError.insufficientElevationPoints) {
            try NodePlannerRFEngine.analyze(
                NodePlannerPathRequest(
                    profile: [],
                    heightAMeters: 1,
                    heightBMeters: 1,
                    frequencyMHz: 915,
                    refractionK: 4.0 / 3.0,
                    linkBudget: nil
                )
            )
        }
        #expect(throws: NodePlannerRFError.nonIncreasingDistance) {
            try NodePlannerRFEngine.analyze(
                NodePlannerPathRequest(
                    profile: [
                        NodePlannerElevationPoint(distanceMeters: 0, elevationMeters: 1),
                        NodePlannerElevationPoint(distanceMeters: 0, elevationMeters: 2)
                    ],
                    heightAMeters: 1,
                    heightBMeters: 1,
                    frequencyMHz: 915,
                    refractionK: 4.0 / 3.0,
                    linkBudget: nil
                )
            )
        }
    }

    @Test func splitsPathIntoClearanceStretches() {
        let ridge = NodePlannerPathRequest(
            profile: [0, 2_500, 5_000, 7_500, 10_000].map {
                NodePlannerElevationPoint(distanceMeters: $0, elevationMeters: $0 == 5_000 ? 60 : 0)
            },
            heightAMeters: 50,
            heightBMeters: 50,
            frequencyMHz: 915,
            refractionK: 4.0 / 3.0,
            linkBudget: nil
        )
        // The ridge darkens both gaps beside it; the ends next to A and B stay clear.
        #expect(NodePlannerRFEngine.clearanceStretches(ridge) == [
            NodePlannerClearanceStretch(startIndex: 0, endIndex: 1, clearance: .clear),
            NodePlannerClearanceStretch(startIndex: 1, endIndex: 3, clearance: .obstructed),
            NodePlannerClearanceStretch(startIndex: 3, endIndex: 4, clearance: .clear)
        ])
        #expect(NodePlannerRFEngine.clearanceStretches(request(midpointElevation: 0, antennaHeight: 20)) == [
            NodePlannerClearanceStretch(startIndex: 0, endIndex: 2, clearance: .marginal)
        ])
        #expect(NodePlannerRFEngine.clearance(forPercent: 80) == .clear)
        #expect(NodePlannerRFEngine.clearance(forPercent: 60) == .marginal)
        #expect(NodePlannerRFEngine.clearance(forPercent: 59.9) == .obstructed)
    }

    private func request(
        midpointElevation: Double,
        antennaHeight: Double,
        linkBudget: NodePlannerLinkBudget? = nil
    ) -> NodePlannerPathRequest {
        NodePlannerPathRequest(
            profile: [
                NodePlannerElevationPoint(distanceMeters: 0, elevationMeters: 0),
                NodePlannerElevationPoint(distanceMeters: 5_000, elevationMeters: midpointElevation),
                NodePlannerElevationPoint(distanceMeters: 10_000, elevationMeters: 0)
            ],
            heightAMeters: antennaHeight,
            heightBMeters: antennaHeight,
            frequencyMHz: 915,
            refractionK: 4.0 / 3.0,
            linkBudget: linkBudget
        )
    }
}
