import Foundation
import Testing
@testable import CoreScopeViewer

private actor SavedStudyElevationProbe: NodePlannerElevationProviding {
    var requests = 0
    func elevations(for coordinates: [NodePlannerCoordinate]) async throws -> NodePlannerTerrainProfile {
        requests += 1
        throw NodePlannerElevationError.unavailable
    }
}

/// Flat terrain at the requested coordinates, counting requests.
private actor FlatTerrainProbe: NodePlannerElevationProviding {
    var requests = 0
    func elevations(for coordinates: [NodePlannerCoordinate]) async throws -> NodePlannerTerrainProfile {
        requests += 1
        return NodePlannerTerrainProfile(
            samples: coordinates.enumerated().map { NodePlannerTerrainSample(coordinate: $1, elevationMeters: 100 + Double($0)) },
            provenance: NodePlannerElevationProvenance(fetchedAt: Date(timeIntervalSince1970: 1_794_000_000), origin: .network))
    }
}

@MainActor
struct SavedStudyRestorationTests {
    @Test func observerRadioDefaultsUseTheFrequencyMajorityAndSnapToMeshCore() {
        let result = PlannerRadioDefault.resolve(observerRadios: [
            "910.525,62.5,7,5", "917.25,62.5,7,5", "917.250024,125,8,6", nil, "bad"
        ], sourceCountry: "US", deviceRegion: "DE")
        #expect(result.preset.id == "meshcore-nz-narrow")
        #expect(result.origin == .observers)
        let atLimit = PlannerRadioDefault.resolve(observerRadios: ["911.525,62.5,7,5"],
                                                   sourceCountry: "NZ", deviceRegion: nil)
        #expect(atLimit.preset.id == "meshcore-us-ca")
        let outside = PlannerRadioDefault.resolve(observerRadios: [
            "911.526,62.5,7,5", "911.526,62.5,7,5", "910.525,62.5,7,5",
            "910.525,62.5,7", "910.525,NaN,7,5", "910.525,62.5,7,garbage"
        ], sourceCountry: "NZ", deviceRegion: "US")
        #expect(outside.preset.id == "meshcore-nz-narrow")
        #expect(outside.origin == .analyzerCountry)
    }

    @Test func brazilObserversDoNotDisplaceAustralianOrNewZealandPresets() {
        let expected = [
            ("923.125,62.5,8,8", "meshcore-br-narrow"),
            ("916.575,62.5,7,7", "meshcore-au-narrow"),
            ("917.375,62.5,7,5", "meshcore-nz-narrow")
        ]
        for (report, id) in expected {
            let resolved = PlannerRadioDefault.resolve(observerRadios: [report], sourceCountry: "US", deviceRegion: "GB")
            #expect(resolved.origin == .observers)
            #expect(resolved.preset.id == id)
        }
    }

    @Test func savedBrazilProfileIsRecognizedByItsExactRadioValues() throws {
        let model = LinkProfileModel()
        model.latitudeA = 50.5
        model.longitudeA = -115.5
        model.latitudeB = 50.51
        model.longitudeB = -115.52
        model.frequencyMHz = 923.125
        model.transmitPowerDBm = 22
        model.receiverSensitivityDBm = -130
        let study = try #require(model.savedSnapshot())
        let reopened = LinkProfileModel()
        reopened.restore(study)
        #expect(reopened.radioPreset == .brazilNarrow)
        #expect(reopened.savedSnapshot()?.linkProfile == study.linkProfile)
        #expect(LinkProfileRadioPreset.brazilNarrow.receiverSensitivityDBm == -130)
        #expect(LinkProfileRadioPreset.matching(frequencyMHz: 923.125, powerDBm: 21, sensitivityDBm: -130) == .custom)
    }

    @Test func previousCoveragePresetIDRestoresWithoutChangingRadioValues() throws {
        let preset = try #require(CoverageRadioPreset.preset(id: "meshcore-nz-narrow"))
        let original = EstimatedCoverageModel(distanceUnit: .metric)
        original.latitude = 50.5
        original.longitude = -115.5
        original.applyRadioPreset(preset)
        let saved = try #require(original.savedSnapshot())
        let draft = StudyDraft(id: UUID().uuidString.lowercased(), kind: .coverage,
            createdAtMilliseconds: 1, updatedAtMilliseconds: 1, title: "Saved Coverage",
            host: "analyzer.example", region: nil, editor: original.draftEditor())
        let reopened = EstimatedCoverageModel(distanceUnit: .metric)
        reopened.restore(draft: draft, study: saved, result: nil)
        #expect(reopened.radioPresetID == "meshcore-nz-narrow")
        #expect(reopened.savedSnapshot()?.coverage?.frequencyMHz == saved.coverage?.frequencyMHz)
        #expect(reopened.transmitPowerDbm == preset.transmitPowerDbm)
        #expect(reopened.sensitivityDbm == preset.sensitivityDbm)
    }

    @Test func sourceCountryOverridesDeviceAndOnlySpecifiedCountriesAreMapped() {
        let groups = [
            "meshcore-us-ca": ["US", "CA"],
            "meshcore-eu-uk-narrow": ["AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
                "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO",
                "SK", "SI", "ES", "SE", "GB", "CH", "NO", "IS", "LI"],
            "meshcore-au-narrow": ["AU"],
            "meshcore-nz-narrow": ["NZ"],
            "meshcore-br-narrow": ["BR"]
        ]
        for (id, countries) in groups {
            for country in countries {
                let resolved = PlannerRadioDefault.resolve(observerRadios: [nil, "invalid"], sourceCountry: country, deviceRegion: "JP")
                #expect(resolved.origin == .analyzerCountry)
                #expect(resolved.preset.id == id)
            }
        }
        let device = PlannerRadioDefault.resolve(observerRadios: [], sourceCountry: "CR", deviceRegion: "GB")
        #expect(device.origin == .deviceRegion && device.preset.id == "meshcore-eu-uk-narrow")
        let fallback = PlannerRadioDefault.resolve(observerRadios: [], sourceCountry: "CR", deviceRegion: "JP")
        #expect(fallback.origin == .fallback && fallback.preset == .standard)
    }

    @Test func freshNonUSDefaultsAreRecognizedAndUserChangesWin() {
        let european = PlannerRadioDefault.resolve(observerRadios: [], sourceCountry: "GB", deviceRegion: "US")
        let link = LinkProfileModel()
        link.initializeRadio(european)
        #expect(link.usesDefaultEquipment && link.frequencyMHz == 869.618)
        #expect(link.transmitPowerDBm == 14 && link.receiverSensitivityDBm == -130)
        link.applyRadioPreset(.unitedStatesNarrow)
        link.startOver()
        link.initializeRadio(european)
        #expect(!link.usesDefaultEquipment && link.frequencyMHz == 910.525)
        #expect(link.radioDefaultOrigin == nil)
        let coverage = EstimatedCoverageModel(distanceUnit: .metric)
        coverage.initializeRadio(european)
        #expect(coverage.radioPresetID == european.preset.id)
        coverage.applyRadioPreset(.standard)
        coverage.initializeRadio(european)
        #expect(coverage.frequencyMHz == 910.525 && coverage.radioDefaultOrigin == nil)
    }

    @Test func aCalculatedLinkProfileSavesItsResultAndReopensItWithoutTerrain() async throws {
        let probe = FlatTerrainProbe()
        let original = LinkProfileModel(elevationProvider: probe)
        original.latitudeA = 50.5
        original.longitudeA = -115.5
        original.latitudeB = 50.51
        original.longitudeB = -115.52
        original.heightA = 6.096
        original.heightB = 1.5
        original.sampleCount = 30
        let budget = NodePlannerLinkBudget(transmitPowerDBm: original.transmitPowerDBm, gainADB: original.gainADB,
            gainBDB: original.gainBDB, lossADB: original.lossADB, lossBDB: original.lossBDB,
            receiverSensitivityDBm: original.receiverSensitivityDBm)
        let analysis = try await NodePlannerLinkAnalyzer(elevationProvider: probe,
                                                         now: { Date(timeIntervalSince1970: 1_794_000_100) }).analyze(
            NodePlannerLinkAnalysisRequest(pointA: try NodePlannerCoordinate(latitude: 50.5, longitude: -115.5),
                pointB: try NodePlannerCoordinate(latitude: 50.51, longitude: -115.52), heightAMeters: 6.096, heightBMeters: 1.5,
                frequencyMHz: original.frequencyMHz, refractionK: original.refractionK, linkBudget: budget, sampleCount: 30))
        original.result = analysis
        let snapshot = try #require(original.savedSnapshot())
        #expect(snapshot.linkProfileResult != nil)

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let store = DeploymentPlanStore(fileURL: directory.appendingPathComponent("plans.json"))
        _ = try await store.load()
        try await store.save(DeploymentPlanDocument(schemaVersion: 3, plans: [], studies: [snapshot]))
        let stored = try #require(try await DeploymentPlanStore(fileURL: directory.appendingPathComponent("plans.json")).load().studies.first)
        #expect(stored.resultRef?.format == ResultRef.portableLinkProfile)
        #expect(stored.resultStatus == .available)

        let requests = await probe.requests
        let reopened = LinkProfileModel(elevationProvider: probe)
        reopened.restore(stored)
        #expect(reopened.result == analysis)
        #expect(await probe.requests == requests)
        // Saving it again keeps the same result bytes.
        #expect(reopened.savedSnapshot()?.linkProfileResult == snapshot.linkProfileResult)
        // An edit clears it and waits for Calculate.
        reopened.configurationChanged()
        #expect(reopened.result == nil && reopened.awaitsExplicitCalculation)
    }

    @Test func linkRestoresBothEndpointsWithoutRequestingTerrain() async throws {
        let probe = SavedStudyElevationProbe()
        let original = LinkProfileModel(elevationProvider: probe)
        original.latitudeA = 50.5
        original.longitudeA = -115.5
        original.latitudeB = 50.51
        original.longitudeB = -115.52
        original.nameA = "Saved ridge"
        original.heightA = 6.096
        original.heightB = 1.5
        original.gainADB = 4
        original.gainBDB = 2
        original.lossADB = 0.5
        original.lossBDB = 1.25
        original.frequencyMHz = 869.618
        original.transmitPowerDBm = 14
        original.receiverSensitivityDBm = -130
        original.refractionK = 1.2
        original.sampleCount = 50
        let snapshot = try #require(original.savedSnapshot())
        let reopened = LinkProfileModel(elevationProvider: probe)
        reopened.restore(snapshot)
        reopened.initializeRadio(.resolve(observerRadios: [], sourceCountry: "AU", deviceRegion: "US"))
        #expect(reopened.radioDefaultOrigin == nil)
        #expect(reopened.savedSnapshot()?.linkProfile == snapshot.linkProfile)
        #expect(reopened.savedSnapshot()?.id == snapshot.id)
        #expect(reopened.result == nil && !reopened.isAnalyzing)
        reopened.configurationChanged()
        try await Task.sleep(for: .milliseconds(750))
        #expect(await probe.requests == 0)
        #expect(reopened.awaitsExplicitCalculation)
        reopened.analyze()
        for _ in 0..<100 {
            if await probe.requests > 0 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(await probe.requests == 1)
    }

    @Test func coverageResultSurvivesDiskRoundTripAndRendersOffline() async throws {
        let original = EstimatedCoverageModel(distanceUnit: .metric)
        original.latitude = 29.38
        original.longitude = -98.37
        original.radiusMeters = 1000
        original.spacingMeters = 1000
        let inputs = try original.inputs()
        var run = try CoverageNodeRun(nodes: [], catalogComplete: false, evaluationLimit: 200, radiusMeters: 1000)
        let completed = run.complete()
        #expect(completed)
        let cells = try (0..<inputs.grid.count).map { index -> CoverageAreaCell in
            let point = try #require(inputs.grid.point(at: index))
            switch point.state {
            case .nearSite: return .init(state: .nearSite, lossDb: nil, marginDb: nil)
            case .outsideRadius: return .init(state: .outsideRadius, lossDb: nil, marginDb: nil)
            case .eligible:
                if index == 1 { return .init(state: .notComputed, lossDb: nil, marginDb: nil) }
                return .init(state: .qualified, lossDb: 100,
                    marginDb: try inputs.budget.reception(pathLossDb: 100).adjustedMarginDb)
            }
        }
        let result = CoverageStudyResult(executionID: UUID(), inputs: inputs,
            limits: .init(areaSamples: 120_000_000, areaGeometry: 120_000_000,
                nodeSamples: 30_000_000, nodeGeometry: 30_000_000, nodeModelCalls: 1000),
            area: .init(cells: cells, chargedSamples: 100, chargedGeometry: 100, workLimited: true),
            nodes: .init(run: run, chargedTerrainEvaluations: 0, chargedGeometryEvaluations: 0, modelCalls: 0))
        var saved = try #require(original.savedSnapshot())
        saved.coverage?.calculatedResult = try SavedCoverageCalculation(result).encoded()
        let document = DeploymentPlanDocument(schemaVersion: 2, plans: [], studies: [saved])
        let decoded = try DeploymentPlanCodec.decode(DeploymentPlanCodec.encode(document))
        let reopened = EstimatedCoverageModel(distanceUnit: .imperial)
        reopened.restore(try #require(decoded.studies.first))
        reopened.initializeRadio(.resolve(observerRadios: [], sourceCountry: "AU", deviceRegion: "US"))
        #expect(reopened.frequencyMHz == original.frequencyMHz && reopened.radioDefaultOrigin == nil)
        let restored = try #require(reopened.activeResult)
        #expect(restored.area.cells.map(\.marginDb) == result.area.cells.map(\.marginDb))
        #expect(restored.area.pendingCount == 1)
        #expect(!reopened.isBusy)
        await reopened.renderOverlay()
        #expect(reopened.layer != nil)
        let resaved = try #require(reopened.savedSnapshot()?.coverage?.calculatedResult)
        // Saving again writes the portable format, which reads back as the same result.
        #expect(PortableCoverageResult.isPortable(resaved))
        #expect(try PortableCoverageResult.decode(resaved, inputs: reopened.inputs(), snapshots: [],
                                                  catalogComplete: false).area.pendingCount == 1)
        reopened.transmitPowerDbm += 1
        #expect(reopened.activeResult == nil)
        #expect(reopened.savedSnapshot()?.coverage?.calculatedResult == nil)
        saved.coverage?.calculatedResult = Data([0, 1, 2])
        reopened.restore(saved)
        #expect(reopened.activeResult == nil)
    }

    /// A study records the analyzer its inputs came from and keeps the saved one until they're captured again.
    /// The screens set `catalogOrigin` and `analyzerOrigin` from the current analyzer; Android tests the same rules.
    @Test func studiesRecordWhereTheirInputsCameFrom() throws {
        let link = LinkProfileModel(elevationProvider: SavedStudyElevationProbe())
        link.analyzerOrigin = StudyOrigin(host: "analyzer.example", region: "AUS", capturedAtMilliseconds: 1)
        link.latitudeA = 50.5; link.longitudeA = -115.5; link.latitudeB = 50.51; link.longitudeB = -115.52
        let profile = try #require(link.savedSnapshot())
        #expect(profile.origin == StudyOrigin(host: "analyzer.example", region: "AUS", capturedAtMilliseconds: 1))
        let again = LinkProfileModel(elevationProvider: SavedStudyElevationProbe())
        again.restore(profile)
        again.analyzerOrigin = StudyOrigin(host: "other.example", region: nil, capturedAtMilliseconds: 2)
        #expect(again.savedSnapshot()?.origin == profile.origin)
        again.latitudeB = 50.6
        #expect(again.savedSnapshot()?.origin?.host == "other.example")

        let coverage = EstimatedCoverageModel(distanceUnit: .metric)
        coverage.latitude = 50.5
        coverage.longitude = -115.5
        coverage.catalogOrigin = StudyOrigin(host: "analyzer.example", region: "AUS", capturedAtMilliseconds: 1)
        let saved = try #require(coverage.savedSnapshot())
        #expect(saved.origin?.host == "analyzer.example")
        let reopened = EstimatedCoverageModel(distanceUnit: .metric)
        reopened.restore(saved)
        #expect(reopened.savedSnapshot()?.origin == saved.origin)
        // A study saved without a source keeps none until its nodes are loaded again.
        var unknown = saved
        unknown.origin = nil
        reopened.restore(unknown)
        #expect(reopened.savedSnapshot()?.origin == nil)
    }

    @Test func coverageRestoresFrozenNodesAndEveryInputWithoutRunning() throws {
        let original = EstimatedCoverageModel(distanceUnit: .imperial)
        original.latitude = 50.5
        original.longitude = -115.5
        original.transmitterName = "Saved ridge"
        original.transmitHeightMeters = 6.096
        original.receiveHeightMeters = 2
        original.transmitGainDbi = 4
        original.receiveGainDbi = 3
        original.transmitLossDb = 0.5
        original.receiveLossDb = 1.5
        original.frequencyMHz = 869.618
        original.transmitPowerDbm = 14
        original.sensitivityDbm = -130
        original.reserveDb = 7
        original.spacingMeters = 2000
        original.setCatalog([MeshNode(publicKey: "saved-node", name: "Old name", role: "repeater",
            lat: 50.52, lon: -115.51, lastSeen: Date(timeIntervalSince1970: 1_700_000_000),
            firstSeen: nil, advertCount: nil, hashSize: nil, hashSizeInconsistent: nil,
            lastHeard: nil, defaultScope: nil)], sourceID: "original-source")
        let snapshot = try #require(original.savedSnapshot())
        let reopened = EstimatedCoverageModel(distanceUnit: .metric)
        reopened.restore(snapshot)
        #expect(reopened.savedSnapshot()?.coverage == snapshot.coverage)
        #expect(reopened.savedSnapshot()?.id == snapshot.id)
        #expect(reopened.usesSavedCatalog)
        #expect(reopened.knownNodes.first?.name == "Old name")
        #expect(reopened.nodeSnapshots.first?.sourceID == "original-source")
        #expect(reopened.activeResult == nil && !reopened.isBusy)
        // Acceptance is remembered app-wide; restoring a study neither grants nor clears it.
        #expect(reopened.acceptsScenario == original.acceptsScenario)
        reopened.latitude = 50.6
        reopened.refreshKnownNodes()
        #expect(reopened.nodeSnapshots == original.nodeSnapshots)
    }
}
