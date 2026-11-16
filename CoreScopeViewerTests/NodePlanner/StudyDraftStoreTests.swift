import Foundation
import Testing
@testable import CoreScopeViewer

/// Recovery drafts (deliverable C, step 1), as Android's StudyDraftStoreTest.
struct StudyDraftStoreTests {
    private let root: URL
    private let store: StudyDraftStore

    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        store = StudyDraftStore(directory: root)
    }

    private func draft(_ title: String, updated: Int64 = 1, editor: [String: String] = [:]) -> StudyDraft {
        StudyDraft(id: UUID().uuidString.lowercased(), kind: .coverage, createdAtMilliseconds: 1, updatedAtMilliseconds: updated,
                   title: title, host: "analyzer.example", region: "AUS", editor: editor)
    }

    /// A library save, which runs result cleanup.
    private func saveLibrary() async throws {
        let library = DeploymentPlanStore(fileURL: root.appendingPathComponent("plans.json"))
        let document = try await library.load()
        try await library.save(DeploymentPlanDocument(schemaVersion: 3, plans: document.plans, studies: document.studies,
                                                      studyPlans: document.studyPlans))
    }

    @Test func sessionsAreSavedListedAndDeletedIndependently() throws {
        let first = try store.save(draft("Ridge", updated: 10), result: nil)
        let second = try store.save(draft("Valley", updated: 20), result: nil)
        #expect(store.list().map(\.title) == ["Valley", "Ridge"])
        var edited = first
        edited.title = "Ridge, edited"
        edited.updatedAtMilliseconds = 30
        try store.save(edited, result: nil)
        #expect(store.list().map(\.title) == ["Ridge, edited", "Valley"])
        store.delete(second.id)
        #expect(store.list().map(\.id) == [first.id])
    }

    @Test func incompleteTextAndTheCatalogComeBackExactly() throws {
        var value = draft("Ridge", editor: ["latitude": "29.", "txHeight": ""])
        value.catalog = [SavedCoverageNode(sourceID: "analyzer.example", nodeID: "a1", name: "Hilltop", role: "repeater",
                                           latitude: 29.4, longitude: -98.5, lastSeenMilliseconds: 1_794_770_000_000)]
        value.catalogComplete = true
        value.planID = UUID().uuidString.lowercased()
        value.savedRevision = 3
        let saved = try store.save(value, result: nil)
        #expect(store.draft(saved.id) == saved)
    }

    @Test func aDraftsResultSurvivesLibraryCleanupUntilTheDraftGoes() async throws {
        let payload = Data(#"{"format":"nodescope.coverage-result"}"#.utf8)
        let saved = try store.save(draft("Ridge"), result: payload)
        try await saveLibrary()
        #expect(store.result(saved) == payload)
        store.delete(saved.id)
        try await saveLibrary()
        #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("results/\(saved.resultSha256!).result").path))
    }

    @Test func anUnreadableDraftStopsCleanupDeletingAnything() async throws {
        let drafts = root.appendingPathComponent("drafts", isDirectory: true)
        try FileManager.default.createDirectory(at: drafts, withIntermediateDirectories: true)
        try Data("{ not a draft".utf8).write(to: drafts.appendingPathComponent("\(UUID().uuidString.lowercased()).json"))
        let results = root.appendingPathComponent("results", isDirectory: true)
        try FileManager.default.createDirectory(at: results, withIntermediateDirectories: true)
        let stray = results.appendingPathComponent(String(repeating: "ab", count: 32) + ".result")
        try Data("x".utf8).write(to: stray)
        try await saveLibrary()
        #expect(FileManager.default.fileExists(atPath: stray.path))
    }

    @MainActor @Test func unsavedLinkProfileWorkIsKeptAsADraftUntilSaved() throws {
        let model = LinkProfileModel()
        model.markClean()
        let session = StudyDraftSession(kind: .linkProfile, store: store)
        // Nothing placed yet: nothing to keep.
        session.flush(model.draftContent(), host: "analyzer.example", region: "AUS")
        #expect(store.list().isEmpty)
        model.latitudeA = 40
        model.longitudeA = -90
        model.nameA = "Hill"
        session.flush(model.draftContent(), host: "analyzer.example", region: "AUS")
        let draft = try #require(store.list().first)
        #expect(draft.title == "Hill" && draft.editor["latitudeA"] == "40.0" && draft.host == "analyzer.example")
        // Saved: the draft goes.
        model.markClean()
        session.flush(model.draftContent(), host: "analyzer.example", region: "AUS")
        #expect(store.list().isEmpty)
    }

    @MainActor @Test func aLinkProfileDraftResumesExactly() throws {
        let model = LinkProfileModel()
        model.markClean()
        model.latitudeA = 40
        model.longitudeA = -90
        model.nameA = "Hill"
        model.heightB = 7.5
        model.sampleCount = 40
        let session = StudyDraftSession(kind: .linkProfile, store: store)
        session.flush(model.draftContent(), host: "analyzer.example", region: "AUS")
        let draft = try #require(store.draft(session.id))
        let resumed = CandidatePlansModel().resume(draft, from: store)
        let reopened = LinkProfileModel()
        reopened.restore(draft: resumed.draft, study: resumed.study, result: resumed.result)
        #expect(reopened.draftEditor() == model.draftEditor())
        #expect(reopened.awaitsExplicitCalculation && reopened.result == nil)
        // Still unsaved work, so it stays a draft.
        #expect(reopened.draftContent() != nil)
    }

    @MainActor @Test func aSourceSwitchLearnsWhichWorkCouldntBeKept() throws {
        // A store whose folder is a file can't write anything.
        let blockedFolder = root.appendingPathComponent("not-a-folder")
        try Data("x".utf8).write(to: blockedFolder)
        let failing = StudyDraftSession(kind: .linkProfile, store: StudyDraftStore(directory: blockedFolder))
        let working = StudyDraftSession(kind: .coverage, store: store)
        let registry = StudyDraftRegistry.shared
        defer { registry.flushers[failing.id] = nil; registry.flushers[working.id] = nil }
        registry.flushers[failing.id] = { (DraftContent(title: "Ridge", editor: ["latitude": "29"]), "analyzer.example", "AUS") }
        registry.flushers[working.id] = { (DraftContent(title: "Valley", editor: ["latitude": "30"]), "analyzer.example", "AUS") }
        #expect(registry.flushAll() == ["Ridge"])
        #expect(store.list().map(\.title) == ["Valley"])
        // Open sessions are known, so their drafts can be left out of the Unfinished list.
        #expect(registry.openIDs.contains(working.id))
    }

    @Test func aFullStoreRefusesNewDraftsButNeverEvicts() throws {
        let drafts = try (1...StudyDraftStore.maximumDrafts).map { try store.save(draft("Draft \($0)", updated: Int64($0)), result: nil) }
        #expect(throws: StudyDraftStoreError.full) { try store.save(draft("One more"), result: nil) }
        #expect(store.list().count == StudyDraftStore.maximumDrafts)
        var first = drafts[0]
        first.title = "Still saving"
        try store.save(first, result: nil)
        #expect(store.list().contains { $0.title == "Still saving" })
    }
}
