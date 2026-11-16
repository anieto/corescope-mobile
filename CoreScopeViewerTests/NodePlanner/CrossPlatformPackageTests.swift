import Foundation
import Testing
@testable import CoreScopeViewer

/// iOS ↔ Android round trips (D step 5). Each platform exports the shared v3 reference library to
/// shared/fixtures/saved-plans/packages/<platform>-library-v1.nodescope (recorded when absent, then kept), and
/// imports the other platform's file into an empty library. Mirrors Android's CrossPlatformPackageTest.
@MainActor
struct CrossPlatformPackageTests {
    private let shared: URL

    init() {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures").path) {
            root.deleteLastPathComponent()
        }
        shared = root.appendingPathComponent("shared/fixtures/saved-plans")
    }

    private var written: URL { shared.appendingPathComponent("packages/ios-library-v1.nodescope") }
    private var fromAndroid: URL { shared.appendingPathComponent("packages/android-library-v1.nodescope") }

    private func library(reference: Bool) async throws -> (CandidatePlansModel, URL) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        if reference {
            try FileManager.default.copyItem(at: shared.appendingPathComponent("v3/reference/plans.json"), to: folder.appendingPathComponent("plans.json"))
            try FileManager.default.copyItem(at: shared.appendingPathComponent("v3/reference/results"), to: folder.appendingPathComponent("results"))
        }
        let file = folder.appendingPathComponent("plans.json")
        let model = CandidatePlansModel(store: DeploymentPlanStore(fileURL: file))
        await model.load()
        return (model, file)
    }

    private func restore(_ package: SavedPlansPackage) async throws -> CandidatePlansModel {
        let (target, file) = try await library(reference: false)
        let current = SavedPlansImport.Library(plans: target.plans, studyPlans: target.studyPlans, studies: target.studies)
        let preview = SavedPlansImport.preview(package, library: current)
        #expect(await target.importRecords(try SavedPlansImport.records(preview, library: current,
            selected: preview.defaultSelection, inputsOnly: false)))
        let reopened = CandidatePlansModel(store: DeploymentPlanStore(fileURL: file))
        await reopened.load()
        return reopened
    }

    private func available(_ model: CandidatePlansModel) -> Set<String> {
        Set(model.studies.filter { $0.resultStatus == .available }.compactMap { $0.importOrigin?.studyID })
    }

    @Test func iOSsLibraryFileReadsBackAndRestores() async throws {
        if !FileManager.default.fileExists(atPath: written.path) {
            let (source, _) = try await library(reference: true)
            let package = try #require(SavedPlansExport.package(.library, plans: source.plans, studyPlans: source.studyPlans,
                studies: source.studies, includesResults: true, includesNotes: true, exportedAtMilliseconds: 1_794_790_000_000,
                appVersion: "0.10.7"))
            try package.encoded().write(to: written)
        }
        let package = try SavedPlansPackage.read(Data(contentsOf: written))
        #expect(package.exportedBy.platform == .ios)
        let restored = try await restore(package)
        #expect(restored.studies.count == 6)
        // iOS can't read Android's 0.10.6 result, so it and the result missing from this device stay behind.
        #expect(available(restored) == ["40000000-0000-4000-8000-000000000001", "40000000-0000-4000-8000-000000000002",
                                        "40000000-0000-4000-8000-000000000006"])
    }

    @Test func androidsLibraryFileRestoresOnIOS() async throws {
        let package = try SavedPlansPackage.read(Data(contentsOf: fromAndroid))
        #expect(package.exportedBy.platform == .android)
        #expect(package.unusableResults.isEmpty)
        let restored = try await restore(package)
        #expect(restored.studies.count == 6 && restored.studyPlans.count == 2 && restored.plans.count == 1)
        #expect(restored.studyPlans.first { $0.name == "Ridge relay site" }?.notes == "Compare the roof mount with a 10 m mast.")
        // Android converted its own 0.10.6 result, so iOS shows it too.
        #expect(available(restored) == ["40000000-0000-4000-8000-000000000001", "40000000-0000-4000-8000-000000000002",
                                        "40000000-0000-4000-8000-000000000003", "40000000-0000-4000-8000-000000000006"])
    }
}
