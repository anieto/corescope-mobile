import Foundation
import Testing
@testable import CoreScopeViewer

/// Building packages from the saved library (D step 3), from the shared v3 reference. Mirrors Android's SavedPlansExportTest.
struct SavedPlansExportTests {
    private let shared: URL

    init() {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures").path) {
            root.deleteLastPathComponent()
        }
        shared = root.appendingPathComponent("shared/fixtures/saved-plans")
    }

    /// The reference library as loaded: results attached, one missing, one in Android's legacy format.
    private func reference() async throws -> DeploymentPlanDocument {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: shared.appendingPathComponent("v3/reference/plans.json"), to: folder.appendingPathComponent("plans.json"))
        try FileManager.default.copyItem(at: shared.appendingPathComponent("v3/reference/results"), to: folder.appendingPathComponent("results"))
        return try await DeploymentPlanStore(fileURL: folder.appendingPathComponent("plans.json")).load()
    }

    private func export(_ document: DeploymentPlanDocument, _ contents: SavedPlansExport.Contents,
                        results: Bool = true, notes: Bool = true) throws -> SavedPlansPackage {
        try #require(SavedPlansExport.package(contents, plans: document.plans, studyPlans: document.studyPlans,
            studies: document.studies, includesResults: results, includesNotes: notes,
            exportedAtMilliseconds: 1_794_790_000_000, appVersion: "0.10.7"))
    }

    @Test func aLibraryBackupSaysWhichResultsStayBehindAndReadsBack() async throws {
        let document = try await reference()
        let package = try export(document, .library)
        #expect(package.studies.count == 6 && package.studyPlans.count == 2 && package.candidateSitePlans.count == 1)
        let reasons = Dictionary(uniqueKeysWithValues: package.omittedResults.map { ($0.studyID, $0.reason) })
        #expect(reasons == ["40000000-0000-4000-8000-000000000003": .notReadableHere,   // Android's legacy format
                            "40000000-0000-4000-8000-000000000005": .notOnDevice])
        #expect(package.results.count == 2)  // the imported copy shares its original's file
        let read = try SavedPlansPackage.read(try package.encoded())
        #expect(read.unusableResults.isEmpty)
        #expect(read.studies.map(\.id) == document.studies.map(\.id))
        #expect(read.studies.first { $0.id == "40000000-0000-4000-8000-000000000001" }?.resultRef
            == document.studies.first { $0.id == "40000000-0000-4000-8000-000000000001" }?.resultRef)
    }

    @Test func aStudyTravelsAloneAndAPlanCanLeaveItsNotes() async throws {
        let document = try await reference()
        let study = try export(document, .study("40000000-0000-4000-8000-000000000002"))
        #expect(study.studies.count == 1 && study.studies[0].planID == nil && study.studyPlans.isEmpty)
        let plan = try export(document, .plan("30000000-0000-4000-8000-000000000001"), notes: false)
        #expect(plan.studyPlans.count == 1 && plan.studyPlans[0].notes == nil && plan.studies.count == 2)
        let inputs = try export(document, .plan("30000000-0000-4000-8000-000000000001"), results: false)
        #expect(inputs.results.isEmpty && inputs.studies.allSatisfy { $0.resultRef == nil } && inputs.omittedResults.isEmpty)
        for package in [study, plan, inputs] { _ = try SavedPlansPackage.read(try package.encoded()) }
        #expect(SavedPlansExport.package(.study(UUID().uuidString.lowercased()), plans: [], studyPlans: [], studies: [],
            includesResults: true, includesNotes: true, exportedAtMilliseconds: 0, appVersion: "1") == nil)
    }

    /// iOS 0.10.6's own plist result travels converted to the portable format, which reads back for its study.
    /// The recorded plist has reference study 3's inputs (LegacyCoverageFixtureTests).
    @Test func thisPlatformsLegacyResultIsConverted() async throws {
        let plist = try Data(contentsOf: shared.appendingPathComponent("legacy/ios-0.10.6-coverage.plist"))
        var study = try await reference().studies.first { $0.id == "40000000-0000-4000-8000-000000000003" }!
        study.coverage?.calculatedResult = plist
        study.resultRef = ResultRef(format: ResultRef.iosLegacy, version: 1, byteLength: plist.count,
                                    sha256: DeploymentPlanStore.sha256(plist), inputFingerprint: nil)
        study.resultStatus = .available
        let package = try #require(SavedPlansExport.package(.study(study.id), plans: [], studyPlans: [], studies: [study],
            includesResults: true, includesNotes: true, exportedAtMilliseconds: 1, appVersion: "0.10.7"))
        #expect(package.omittedResults.isEmpty)
        #expect(package.studies[0].resultRef?.format == ResultRef.portableCoverage)
        let read = try SavedPlansPackage.read(try package.encoded())
        #expect(read.unusableResults.isEmpty && read.results.count == 1)
    }

    @Test func thePreviewNamesWhatTheFileReveals() async throws {
        let package = try export(try await reference(), .library)
        let preview = SavedPlansExport.Preview(package)
        #expect(preview.studies == 6 && preview.plans == 2 && preview.candidateSitePlans == 1)
        #expect(preview.locations == 1 + 4 * 1 + 2 * 2)  // a candidate site, four coverage sites, two Link Profiles' endpoints
        #expect(preview.nodes == 2 && preview.nodeNames == ["Hilltop", "Far repeater"])
        #expect(preview.analyzers == ["analyzer.example · SAT"])
        #expect(preview.unknownSource == 2)
        #expect(preview.notedPlans == 1)
        #expect(preview.omitted.count == 2)
        #expect(SavedPlansExport.fileName(package, exportedAt: Date(timeIntervalSince1970: 1_794_790_000)).hasSuffix(".nodescope"))
    }
}
