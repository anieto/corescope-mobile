import CoreLocation
import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageRadioPresetsTests {
    @MainActor
    @Test func imperialDefaultUsesTenMilesWithValidStudyInputs() throws {
        let model = EstimatedCoverageModel(distanceUnit: .imperial)
        model.place(CLLocationCoordinate2D(latitude: 50.5, longitude: -115.5), name: nil)
        let inputs = try model.inputs()
        #expect(inputs.grid.radiusMeters == 16_093)
        #expect(model.grid?.columns == inputs.grid.columns)
    }

    @Test func sensitivityFollowsSpreadingFactorAndBandwidth() {
        // −174 + 10·log10(BW) + 6 dB noise figure + (10 − 2.5·SF), to the nearest 0.5 dB.
        #expect(CoverageRadioPreset.sensitivity(spreadingFactor: 7, bandwidthKHz: 62.5) == -127.5)
        #expect(CoverageRadioPreset.sensitivity(spreadingFactor: 8, bandwidthKHz: 62.5) == -130)
        #expect(CoverageRadioPreset.sensitivity(spreadingFactor: 11, bandwidthKHz: 250) == -131.5)
        #expect(CoverageRadioPreset.sensitivity(spreadingFactor: 7, bandwidthKHz: 250) == -121.5)
    }

    @Test func presetsAreDistinctAndWithinInputLimits() throws {
        let ids = CoverageRadioPreset.all.map(\.id)
        #expect(Set(ids).count == ids.count)
        #expect(CoverageRadioPreset.standard.frequencyMHz == 910.525)
        for preset in CoverageRadioPreset.all {
            #expect((30...6000).contains(preset.frequencyMHz))
            #expect(preset.sensitivityDbm < -100 && preset.sensitivityDbm > -140)
            #expect(CoverageRadioPreset.preset(id: preset.id) == preset)
        }
        #expect(CoverageRadioPreset.preset(id: nil) == nil)
        #expect(CoverageRadioPreset.preset(id: "missing") == nil)
        #expect(CoverageAntennaPreset.matching(gainDbi: 2.15)?.id == "half-wave-dipole")
        #expect(CoverageAntennaPreset.matching(gainDbi: 2.3) == nil)
        #expect(CoverageHeightPreset.matching(meters: 6.096, in: CoverageHeightPreset.transmitter) == .rooftop)
        #expect(CoverageHeightPreset.matching(meters: 4, in: CoverageHeightPreset.transmitter) == nil)
    }

    @MainActor
    @Test func modelDefaultsProduceValidStudyInputs() throws {
        let model = EstimatedCoverageModel(distanceUnit: .metric)
        #expect(model.setupProblem == .noTransmitter)
        model.place(CLLocationCoordinate2D(latitude: 50.5, longitude: -115.5), name: nil)
        let inputs = try model.inputs()
        #expect(inputs.frequencyMHz == CoverageRadioPreset.standard.frequencyMHz)
        #expect(inputs.budget.transmitPowerDbm == 22)
        #expect(inputs.budget.sensitivityDbm == -127.5)
        #expect(inputs.budget.reserveDb == 10)
        #expect(inputs.transmitHeightMeters == 6)
        #expect(inputs.receiveHeightMeters == 1.5)
        #expect(inputs.grid.radiusMeters == 20_000 && inputs.grid.spacingMeters == 1000)
        #expect(model.undo?.coordinate == nil)
    }

    @MainActor
    @Test func editingAPresetValueMakesTheRadioCustomAndUndoRestoresPlacement() {
        let model = EstimatedCoverageModel(distanceUnit: .metric)
        #expect(model.radioPreset == .standard)
        model.radioBinding(\.transmitPowerDbm).wrappedValue = 30
        #expect(model.radioPreset == nil && model.transmitPowerDbm == 30)
        model.applyRadioPreset(CoverageRadioPreset.all[1])
        #expect(model.frequencyMHz == 869.618 && model.transmitPowerDbm == 14 && model.sensitivityDbm == -130)

        model.place(CLLocationCoordinate2D(latitude: 1, longitude: 2), name: "First")
        model.place(CLLocationCoordinate2D(latitude: 3, longitude: 4), name: nil)
        #expect(model.transmitterName == nil)
        model.undoLastChange()
        #expect(model.latitude == 1 && model.longitude == 2 && model.transmitterName == "First")
        model.clearTransmitter()
        #expect(model.coordinate == nil && model.setupProblem == .noTransmitter)
        model.undoLastChange()
        #expect(model.latitude == 1)
    }

    @MainActor
    @Test func knownNodesAreAvailableBeforePlacementAndRemainBounded() {
        let model = EstimatedCoverageModel(distanceUnit: .metric)
        let nodes = (0..<205).map { index in
            MeshNode(publicKey: String(index), name: "Node \(index)", role: "repeater",
                lat: 10, lon: Double(index) / 10, lastSeen: nil, firstSeen: nil,
                advertCount: nil, hashSize: nil, hashSizeInconsistent: nil,
                lastHeard: nil, defaultScope: nil)
        }
        model.setCatalog(nodes + [nodes[0]], sourceID: "test")
        #expect(model.coordinate == nil)
        #expect(model.knownNodes.count == 200)
        #expect(Set(model.knownNodes.map(\.id)).count == 200)
        #expect(model.nodeStatuses.isEmpty)

        model.place(CLLocationCoordinate2D(latitude: 10, longitude: 20.4), name: nil)
        model.refreshKnownNodes()
        #expect(model.knownNodes.first?.id == "204")
        model.clearTransmitter()
        model.refreshKnownNodes()
        #expect(model.knownNodes.count == 200)
        #expect(model.nodeStatuses.isEmpty)
    }

    @MainActor
    @Test func unsupportedPlacementPreservesTransmitterAndUndo() {
        let model = EstimatedCoverageModel(distanceUnit: .metric)
        model.place(CLLocationCoordinate2D(latitude: 10, longitude: 20), name: "Original")
        let undoID = model.undo?.id
        for point in [
            CLLocationCoordinate2D(latitude: 86, longitude: 20),
            CLLocationCoordinate2D(latitude: -86, longitude: 20),
            CLLocationCoordinate2D(latitude: 10, longitude: 181)
        ] {
            model.place(point, name: nil)
            #expect(model.latitude == 10 && model.longitude == 20)
            #expect(model.transmitterName == "Original")
            #expect(model.undo?.id == undoID)
            #expect(model.inputError != nil)
        }
        model.place(CLLocationCoordinate2D(latitude: 85, longitude: 20), name: nil)
        #expect(model.latitude == 85)
        #expect(model.inputError == nil)
    }

    @MainActor
    @Test func outOfRangeValuesAreReportedPerField() {
        let model = EstimatedCoverageModel(distanceUnit: .metric)
        model.place(CLLocationCoordinate2D(latitude: 10, longitude: 10), name: nil)
        model.transmitHeightMeters = 0.5
        #expect(model.issue(for: "txHeight") == .height)
        #expect(model.setupProblem == .field("txHeight", .height))
        model.transmitHeightMeters = 6
        model.receiveLossDb = -1
        #expect(model.setupProblem == .field("rxLoss", .nonnegative))
    }
}
