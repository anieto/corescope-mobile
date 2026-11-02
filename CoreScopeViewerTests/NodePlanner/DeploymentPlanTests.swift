import Foundation
import Testing
@testable import CoreScopeViewer

struct DeploymentPlanTests {
    private func fixtures() throws -> [[String: Any]] {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" {
            let file = root.appendingPathComponent("shared/fixtures/candidate-sites/plan-v1-cases.json")
            if FileManager.default.fileExists(atPath: file.path) {
                return try #require(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [[String: Any]])
            }
            root.deleteLastPathComponent()
        }
        throw DeploymentPlanError.invalidData
    }

    @Test func sharedContractFixturesAndRoundTrips() throws {
        let cases = try fixtures()
        #expect(cases.count == 30)
        for fixture in cases {
            let name = try #require(fixture["name"] as? String)
            let valid = try #require(fixture["valid"] as? Bool)
            let object = try #require(fixture["document"])
            let bytes = try JSONSerialization.data(withJSONObject: object)
            if valid {
                let decoded = try DeploymentPlanCodec.decode(bytes)
                #expect(try DeploymentPlanCodec.decode(DeploymentPlanCodec.encode(decoded)) == decoded,
                        "Round trip: \(name)")
            } else {
                #expect(throws: (any Error).self, "Reject: \(name)") { try DeploymentPlanCodec.decode(bytes) }
            }
        }
    }

    @Test func rejectsNonFiniteAssumptionsAndOversizeInput() throws {
        for value in [Double.nan, Double.infinity, -Double.infinity] {
            let assumptions = CandidateSiteAssumptions(antennaHeightMeters: value)
            #expect(throws: DeploymentPlanError.invalidData) { try assumptions.validate() }
        }
        #expect(throws: DeploymentPlanError.tooLarge) {
            try DeploymentPlanCodec.decode(Data(repeating: 32, count: DeploymentPlanCodec.maximumBytes + 1))
        }
    }

    @Test func manualInputPreservesUnknownAndCanonicalizesDateLine() throws {
        let id = "22222222-2222-4222-8222-222222222222"
        let site = try CandidateSiteInput.make(id: id, name: "Ridge",
            values: ["29,5", "180", "", "915", "0", "-3", "0", ""])
        #expect(site.latitude == 29.5)
        #expect(site.longitude == -180)
        #expect(site.assumptions.antennaHeightMeters == nil)
        #expect(site.assumptions.transmitPowerDBm == 0)
        #expect(site.assumptions.antennaGainDBi == -3)
        #expect(site.assumptions.additionalLossDB == nil)
        for text in ["nan", "inf", "1e999", "0x1p2", "1.2.3", "1,2,3", "12 watts"] {
            #expect(throws: DeploymentPlanError.invalidData) {
                try CandidateSiteInput.make(id: id, name: "Ridge", values: ["29", "-98", "", "", text, "", "", ""])
            }
        }
        #expect(throws: DeploymentPlanError.invalidData) {
            try CandidateSiteInput.make(id: id, name: "Ridge", values: ["", "-98", "", "", "", "", "", ""])
        }
        #expect(throws: DeploymentPlanError.invalidData) {
            try CandidateSiteInput.make(id: id, name: "Ridge", values: [])
        }
    }

    @Test func imperialHeightsConvertBeforeValidationAndStorage() throws {
        let id = "22222222-2222-4222-8222-222222222222"
        func input(_ height: String, feet: Bool = true) throws -> CandidateSite {
            try CandidateSiteInput.make(id: id, name: "Ridge",
                values: ["29", "-98", height, "915", "0", "-3", "0", ""], heightInFeet: feet)
        }
        #expect(try input("20").assumptions.antennaHeightMeters == 6.096)
        #expect(try input("20,5").assumptions.antennaHeightMeters == 6.2484)
        #expect(try input("20", feet: false).assumptions.antennaHeightMeters == 20)
        #expect(try input("").assumptions.antennaHeightMeters == nil)
        #expect(try input("0").assumptions.antennaHeightMeters == 0)
        #expect(try input(String(10_000 / 0.3048)).assumptions.antennaHeightMeters == 10_000)
        for text in ["-1", "32809", "1e999", "nan", "20 ft"] {
            #expect(throws: DeploymentPlanError.invalidData) { try input(text) }
        }
        let site = try input("20")
        let plan = DeploymentPlan(id: id, name: "Plan", createdAtMilliseconds: 0,
            updatedAtMilliseconds: 0, selectedSiteID: site.id, sites: [site])
        let decoded = try DeploymentPlanCodec.decode(DeploymentPlanCodec.encode(DeploymentPlanDocument(plans: [plan])))
        #expect(decoded.plans.first?.sites.first?.assumptions.antennaHeightMeters == 6.096)
    }

    @Test func missingAssumptionsStayUnknown() throws {
        let fixture = try #require(try fixtures().first { $0["name"] as? String == "unspecified_assumptions" })
        let object = try #require(fixture["document"])
        let document = try DeploymentPlanCodec.decode(JSONSerialization.data(withJSONObject: object))
        let assumptions = try #require(document.plans.first?.sites.first?.assumptions)
        #expect(assumptions.antennaHeightMeters == nil)
        #expect(assumptions.transmitPowerDBm == nil)
        #expect(assumptions.frequencyMHz == nil)
        #expect(assumptions.antennaGainDBi == nil)
        #expect(assumptions.cableLossDB == nil)
        #expect(assumptions.additionalLossDB == nil)
    }
}
