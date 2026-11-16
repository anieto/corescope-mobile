import Foundation
import Testing
@testable import CoreScopeViewer

/// A2 library rules: atomic saves into new plans, stale updates refused, Save as new and Duplicate
/// keep originals, deleting a plan keeps its studies.
@MainActor
struct StudyPlanLibraryTests {
    private func library() async throws -> CandidatePlansModel {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let model = CandidatePlansModel(store: DeploymentPlanStore(fileURL: directory.appendingPathComponent("plans.json")))
        await model.load()
        #expect(model.isLoaded)
        return model
    }

    private func linkProfile(_ name: String) -> SavedStudy {
        let antenna = SavedAntenna(heightMeters: 6, gainDbi: 3, lossDb: 0)
        let inputs = SavedLinkProfile(
            pointA: SavedEndpoint(name: "A", latitude: 30.27, longitude: -97.74, antenna: antenna),
            pointB: SavedEndpoint(name: "B", latitude: 30.30, longitude: -97.70, antenna: antenna),
            frequencyMHz: 910.525, transmitPowerDbm: 22, sensitivityDbm: -132, refractionK: 4.0 / 3.0, sampleCount: nil)
        return SavedStudy(id: UUID().uuidString.lowercased(), name: name, createdAtMilliseconds: 1,
                          updatedAtMilliseconds: 1, kind: .linkProfile, linkProfile: inputs)
    }

    /// The shared reference library (one result file deliberately absent), in a new folder.
    private func referenceLibrary() async throws -> CandidatePlansModel {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures").path) {
            root.deleteLastPathComponent()
        }
        let reference = root.appendingPathComponent("shared/fixtures/saved-plans/v3/reference")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("plans.json")
        try FileManager.default.copyItem(at: reference.appendingPathComponent("plans.json"), to: file)
        try FileManager.default.copyItem(at: reference.appendingPathComponent("results"), to: directory.appendingPathComponent("results"))
        let model = CandidatePlansModel(store: DeploymentPlanStore(fileURL: file))
        await model.load()
        #expect(model.isLoaded)
        return model
    }

    @Test func changingInputsUnderAnUnreadableResultNeedsConfirmation() async throws {
        let model = try await referenceLibrary()
        let opened = try #require(model.studies.first { $0.resultStatus == .missing })
        let home: CandidatePlansModel.Destination = opened.planID.map { .plan($0) } ?? .standalone

        // A rename keeps the reference: a later restore can still bring the file back.
        var renamed = opened
        renamed.name = "Renamed"
        #expect(!model.dropsUnreadableResult(renamed))
        let kept = try #require(await model.saveStudy(renamed, mode: .update, destination: home))
        #expect(kept.resultRef == opened.resultRef)

        var changed = kept
        changed.coverage?.reserveDb += 1
        #expect(model.dropsUnreadableResult(changed))
        #expect(await model.saveStudy(changed, mode: .update, destination: home) == nil)
        #expect(model.studies.first { $0.id == opened.id }?.resultRef == opened.resultRef)

        let inputsOnly = try #require(await model.saveStudy(changed, mode: .update, destination: home, inputsOnly: true))
        #expect(inputsOnly.resultRef == nil && inputsOnly.resultStatus == .none)
    }

    @Test func candidateSitesMoveIntoPlansAsInputsOnlyCoverage() async throws {
        let model = try await library()
        let tower = CandidateSite(id: UUID().uuidString.lowercased(), name: "Water tower", latitude: 30.27, longitude: -97.74,
            assumptions: CandidateSiteAssumptions(antennaHeightMeters: 30, frequencyMHz: 915, transmitPowerDBm: 20,
                                                  antennaGainDBi: 5, cableLossDB: 1.5, additionalLossDB: 0.5))
        let ridge = CandidateSite(id: UUID().uuidString.lowercased(), name: "Ridge", latitude: 30.40, longitude: -97.90,
            assumptions: CandidateSiteAssumptions(frequencyMHz: 10_000))
        let pole = CandidateSite(id: UUID().uuidString.lowercased(), name: "Pole", latitude: 89, longitude: 0,
            assumptions: CandidateSiteAssumptions())
        #expect(await model.save(DeploymentPlan(id: UUID().uuidString.lowercased(), name: "North sites", createdAtMilliseconds: 1,
            updatedAtMilliseconds: 1, selectedSiteID: nil, sites: [tower, ridge, pole])))

        let conversion = model.candidateConversion(unit: .metric)
        #expect(conversion.notes.count == 2)
        #expect(await model.moveCandidateSites(conversion))
        #expect(model.plans.isEmpty)
        let plan = try #require(model.studyPlans.first)
        #expect(model.studyPlans.count == 1 && plan.name == "North sites")
        let studies = CandidatePlansModel.sorted(model.studies, updated: \.updatedAtMilliseconds)
        #expect(studies.map(\.name) == ["Water tower", "Ridge"])
        #expect(studies.allSatisfy { $0.planID == plan.id && $0.kind == .coverage && $0.resultRef == nil })
        let moved = try #require(studies.first?.coverage)
        #expect(moved.transmitter == SavedEndpoint(name: "Water tower", latitude: 30.27, longitude: -97.74,
                                                   antenna: SavedAntenna(heightMeters: 30, gainDbi: 5, lossDb: 2)))
        #expect(moved.frequencyMHz == 915 && moved.transmitPowerDbm == 20)
        #expect(moved.nodes.isEmpty && moved.calculatedResult == nil)
        // Out of Coverage's range: the default frequency.
        #expect(studies.last?.coverage?.frequencyMHz != 10_000)
    }

    @Test func newPlanAndItsFirstStudyAreOneSave() async throws {
        let model = try await library()
        #expect(await model.saveStudy(linkProfile("Ridge"), mode: .saveAsNew, destination: .newPlan, newPlanName: "") == nil)
        #expect(model.studyPlans.isEmpty && model.studies.isEmpty)

        let saved = try #require(await model.saveStudy(linkProfile("Ridge"), mode: .saveAsNew, destination: .newPlan,
                                                       newPlanName: "North site"))
        #expect(model.studyPlans.map(\.name) == ["North site"])
        #expect(saved.planID == model.studyPlans.first?.id)
        #expect(saved.revision == 1)
    }

    @Test func aStaleUpdateIsRefusedAndSaveAsNewStillWorks() async throws {
        let model = try await library()
        let opened = try #require(await model.saveStudy(linkProfile("Ridge"), mode: .saveAsNew, destination: .standalone))
        #expect(await model.renameStudy(opened.id, to: "Renamed elsewhere"))

        var edited = opened
        edited.linkProfile?.frequencyMHz = 915
        #expect(await model.saveStudy(edited, mode: .update, destination: .standalone) == nil)
        #expect(model.error != nil)
        #expect(model.studies.map(\.name) == ["Renamed elsewhere"])

        let copy = try #require(await model.saveStudy(edited, mode: .saveAsNew, destination: .standalone))
        #expect(copy.id != opened.id)
        #expect(model.studies.count == 2)
        #expect(model.studies.first { $0.id == opened.id }?.linkProfile?.frequencyMHz == 910.525)
    }

    @Test func anOldEditorCantUndoALaterMove() async throws {
        let model = try await library()
        let plan = try #require(await model.createPlan(name: "North site", notes: nil))
        let opened = try #require(await model.saveStudy(linkProfile("Ridge"), mode: .saveAsNew, destination: .plan(plan.id)))
        #expect(await model.moveStudy(opened.id, to: nil))
        #expect(await model.saveStudy(opened, mode: .update, destination: .plan(plan.id)) == nil)
        #expect(model.studies.first?.planID == nil)
    }

    @Test func anUpdateKeepsIdentityAndAdvancesTheRevision() async throws {
        let model = try await library()
        let opened = try #require(await model.saveStudy(linkProfile("Ridge"), mode: .saveAsNew, destination: .standalone))
        var edited = opened
        edited.name = "Ridge, taller mast"
        let updated = try #require(await model.saveStudy(edited, mode: .update, destination: .standalone))
        #expect(updated.id == opened.id && updated.revision == 2)
        #expect(model.studies.map(\.name) == ["Ridge, taller mast"])
    }

    @Test func savingIntoAMissingPlanKeepsTheDraft() async throws {
        let model = try await library()
        #expect(await model.saveStudy(linkProfile("Ridge"), mode: .saveAsNew,
                                      destination: .plan(UUID().uuidString.lowercased())) == nil)
        #expect(model.studies.isEmpty)
        #expect(model.error != nil)
    }

    @Test func deletingAPlanKeepsItsStudiesAsStandalone() async throws {
        let model = try await library()
        let plan = try #require(await model.createPlan(name: "North site", notes: "Water tower"))
        _ = try #require(await model.saveStudy(linkProfile("One"), mode: .saveAsNew, destination: .plan(plan.id)))
        _ = try #require(await model.saveStudy(linkProfile("Two"), mode: .saveAsNew, destination: .plan(plan.id)))
        #expect(await model.deletePlan(plan.id))
        #expect(model.studyPlans.isEmpty)
        #expect(model.studies.count == 2 && model.studies.allSatisfy { $0.planID == nil })
    }

    @Test func duplicatesAreIndependentAndNamedDistinctly() async throws {
        let model = try await library()
        let plan = try #require(await model.createPlan(name: "North site", notes: nil))
        let original = try #require(await model.saveStudy(linkProfile("Ridge"), mode: .saveAsNew, destination: .plan(plan.id)))
        let first = try #require(await model.duplicateStudy(original.id))
        let second = try #require(await model.duplicateStudy(original.id))
        #expect([first.name, second.name] == ["Ridge copy", "Ridge copy 2"])
        #expect(first.id != original.id && first.planID == plan.id && first.linkProfile == original.linkProfile)
        #expect(await model.deleteStudy(first.id))
        #expect(model.studies.map(\.id).sorted() == [original.id, second.id].sorted())
    }
}
