import Foundation
import Testing
@testable import CoreScopeViewer

/// Importing packages into the saved library (D step 4), from the shared v3 reference. Mirrors Android's SavedPlansImportTest.
@MainActor
struct SavedPlansImportTests {
    private let shared: URL

    init() {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures").path) {
            root.deleteLastPathComponent()
        }
        shared = root.appendingPathComponent("shared/fixtures/saved-plans")
    }

    /// A library folder: the reference library, or an empty one.
    private func library(reference: Bool) throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        if reference {
            try FileManager.default.copyItem(at: shared.appendingPathComponent("v3/reference/plans.json"), to: folder.appendingPathComponent("plans.json"))
            try FileManager.default.copyItem(at: shared.appendingPathComponent("v3/reference/results"), to: folder.appendingPathComponent("results"))
        }
        return folder.appendingPathComponent("plans.json")
    }

    private func model(_ file: URL) async -> CandidatePlansModel {
        let model = CandidatePlansModel(store: DeploymentPlanStore(fileURL: file))
        await model.load()
        return model
    }

    private func current(_ model: CandidatePlansModel) -> SavedPlansImport.Library {
        .init(plans: model.plans, studyPlans: model.studyPlans, studies: model.studies)
    }

    /// The reference library exported (with results) and read back, as a recipient sees it.
    private func referencePackage(_ contents: SavedPlansExport.Contents = .library) async throws -> SavedPlansPackage {
        let file = try library(reference: true)
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let source = await model(file)
        let package = try #require(SavedPlansExport.package(contents, plans: source.plans, studyPlans: source.studyPlans,
            studies: source.studies, includesResults: true, includesNotes: true, exportedAtMilliseconds: 1_794_790_000_000, appVersion: "0.10.7"))
        return try SavedPlansPackage.read(try package.encoded())
    }

    @Test func aLibraryRestoresAsCopiesWithTheirResults() async throws {
        let package = try await referencePackage()
        let file = try library(reference: false)
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let target = await model(file)
        let preview = SavedPlansImport.preview(package, library: current(target))
        #expect(preview.items.allSatisfy { $0.status == .new })
        let records = try SavedPlansImport.records(preview, library: current(target), selected: preview.defaultSelection, inputsOnly: false)
        #expect(await target.importRecords(records))

        let reopened = await model(file)
        #expect(reopened.studies.count == 6 && reopened.studyPlans.count == 2 && reopened.plans.count == 1)
        #expect(Set(reopened.studies.map(\.id)).isDisjoint(with: package.studies.map(\.id)))
        for study in reopened.studies {
            let source = try #require(package.studies.first { $0.id == study.importOrigin?.studyID })
            #expect(study.name == source.name && study.createdAtMilliseconds == source.createdAtMilliseconds && study.origin == source.origin)
            #expect(study.importOrigin?.revision == source.revision && study.revision == 1)
            // Memberships point at the new copies of their plans.
            let sourcePlan = package.studyPlans.first { $0.id == source.planID }
            #expect(reopened.studyPlans.first { $0.id == study.planID }?.name == sourcePlan?.name)
            #expect((study.resultRef != nil) == (source.resultRef != nil))
            if study.resultRef != nil { #expect(study.resultStatus == .available) }
        }
    }

    @Test func aBackupOfThisLibraryIsAlreadyHere() async throws {
        // Export converted or left out some results, so their bytes differ; that alone isn't a difference.
        let package = try await referencePackage()
        let file = try library(reference: true)
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let target = await model(file)
        let preview = SavedPlansImport.preview(package, library: current(target))
        #expect(preview.items.allSatisfy { $0.status == .alreadyHere })
        #expect(preview.defaultSelection.isEmpty)
        // Ticking everything anyway imports nothing.
        #expect(throws: SavedPlansImport.Refusal.nothingSelected) {
            try SavedPlansImport.records(preview, library: current(target), selected: Set(preview.items.map(\.id)), inputsOnly: false)
        }
    }

    @Test func aFileThatBringsAMissingResultIsOffered() async throws {
        let package = try await referencePackage()
        let file = try library(reference: true)
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        // This device lost study 1's result file (shared with its copy, study 6), as after a restore without results.
        let results = file.deletingLastPathComponent().appendingPathComponent("results")
        let sha = try #require(package.studies.first { $0.id == "40000000-0000-4000-8000-000000000001" }?.resultRef?.sha256)
        try FileManager.default.removeItem(at: results.appendingPathComponent("\(sha).result"))
        let target = await model(file)
        let preview = SavedPlansImport.preview(package, library: current(target))
        let status = Dictionary(uniqueKeysWithValues: preview.items.map { ($0.name, $0.status) })
        #expect(status["Ridge relay site"] == .partlyHere(1))
        #expect(status["Ridge relay coverage (copy)"] == .new)
        #expect(status["Inputs only"] == .alreadyHere)
        let records = try SavedPlansImport.records(preview, library: current(target), selected: preview.defaultSelection, inputsOnly: false)
        #expect(Set(records.studies.map(\.name)) == ["Ridge relay coverage 2", "Ridge relay coverage (copy) 2"])
        #expect(records.studies.allSatisfy { $0.coverage?.calculatedResult != nil })
        // The plan already here takes its new study; nothing is replaced.
        #expect(records.studyPlans.isEmpty)
        #expect(records.studies.first { $0.name == "Ridge relay coverage 2" }?.planID == "30000000-0000-4000-8000-000000000001")
    }

    @Test func aStudyImportedTwiceBecomesACopy() async throws {
        let package = try await referencePackage(.study("40000000-0000-4000-8000-000000000002"))
        let file = try library(reference: false)
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let target = await model(file)
        for _ in 0..<2 {
            let preview = SavedPlansImport.preview(package, library: current(target))
            #expect(await target.importRecords(try SavedPlansImport.records(preview, library: current(target),
                selected: preview.defaultSelection, inputsOnly: false)))
        }
        #expect(Set(target.studies.map(\.name)) == ["Ridge to valley", "Ridge to valley 2"])
        #expect(target.studies.allSatisfy { $0.planID == nil && $0.resultStatus == .available })
        let again = SavedPlansImport.preview(package, library: current(target))
        #expect(again.items.first?.status == .alreadyHere && again.copiesMatches)
    }

    @Test func aResultThatCantBeUsedNeedsAcknowledgement() async throws {
        var package = try await referencePackage(.study("40000000-0000-4000-8000-000000000001"))
        package.unusableResults = [package.studies[0].id]
        package.studies[0].resultRef = nil
        let empty = SavedPlansImport.Library(plans: [], studyPlans: [], studies: [])
        let preview = SavedPlansImport.preview(package, library: empty)
        #expect(preview.unusable == ["Ridge relay coverage"])
        #expect(throws: SavedPlansImport.Refusal.nothingSelected) {
            try SavedPlansImport.records(preview, library: empty, selected: preview.defaultSelection, inputsOnly: false)
        }
        let records = try SavedPlansImport.records(preview, library: empty, selected: preview.defaultSelection, inputsOnly: true)
        #expect(records.studies.count == 1 && records.studies[0].coverage?.calculatedResult == nil)
    }

    @Test func aFullLibraryRefusesTheWholeImport() async throws {
        let package = try await referencePackage(.study("40000000-0000-4000-8000-000000000004"))
        let template = package.studies[0]
        let full = SavedPlansImport.Library(plans: [], studyPlans: [], studies: (0..<100).map { index in
            var study = template
            study.id = UUID().uuidString.lowercased()
            study.name = "Study \(index)"
            return study
        })
        let preview = SavedPlansImport.preview(package, library: full)
        #expect(throws: SavedPlansImport.Refusal.tooMany("studies")) {
            try SavedPlansImport.records(preview, library: full, selected: preview.defaultSelection, inputsOnly: false)
        }
    }
}
