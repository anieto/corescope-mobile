import Foundation
import Testing
@testable import CoreScopeViewer

struct DeploymentPlanStoreTests {
    private func temporaryFile() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root.appendingPathComponent("plans.json")
    }
    private func document(_ name: String) -> DeploymentPlanDocument {
        DeploymentPlanDocument(plans: [DeploymentPlan(
            id: "11111111-1111-4111-8111-111111111111", name: name,
            createdAtMilliseconds: 1, updatedAtMilliseconds: 1, sites: []
        )])
    }

    @Test func saveReopenReplaceAndDelete() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = DeploymentPlanStore(fileURL: file)
        #expect(try await store.load().plans.isEmpty)
        try await store.save(document("First"))
        #expect(try await DeploymentPlanStore(fileURL: file).load() == document("First"))
        try await store.save(document("Renamed"))
        #expect(try await DeploymentPlanStore(fileURL: file).load() == document("Renamed"))
        try await store.save(DeploymentPlanDocument(plans: []))
        #expect(try await DeploymentPlanStore(fileURL: file).load().plans.isEmpty)
    }

    @Test func loadRequiredAndUnreadableFilesArePreserved() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        for raw in ["broken", "{\"schemaVersion\":2,\"plans\":[]}"] {
            let bytes = Data(raw.utf8)
            try bytes.write(to: file)
            let store = DeploymentPlanStore(fileURL: file)
            await #expect(throws: (any Error).self) { try await store.load() }
            await #expect(throws: DeploymentPlanStoreError.notLoaded) { try await store.save(document("New")) }
            #expect(try Data(contentsOf: file) == bytes)
        }
    }

    @Test func changedAndDeletedFilesRequireReload() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = DeploymentPlanStore(fileURL: file)
        _ = try await store.load()
        try await store.save(document("First"))
        try DeploymentPlanCodec.encode(document("External")).write(to: file)
        await #expect(throws: DeploymentPlanStoreError.changedOnDisk) { try await store.save(document("Draft")) }
        #expect(try await store.load() == document("External"))
        try FileManager.default.removeItem(at: file)
        await #expect(throws: DeploymentPlanStoreError.changedOnDisk) { try await store.save(document("Draft")) }
    }

    @Test func failedWritePreservesPreviousSaveAndDraft() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let previous = try DeploymentPlanCodec.encode(document("First"))
        try previous.write(to: file)
        let store = DeploymentPlanStore(fileURL: file, write: { _, _ in throw CocoaError(.fileWriteOutOfSpace) })
        _ = try await store.load()
        let draft = document("Unsaved")
        await #expect(throws: (any Error).self) { try await store.save(draft) }
        #expect(draft == document("Unsaved"))
        #expect(try Data(contentsOf: file) == previous)
        #expect(try await store.load() == document("First"))
    }

    @Test func invalidDraftDoesNotReplaceSavedPlan() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = DeploymentPlanStore(fileURL: file)
        _ = try await store.load()
        try await store.save(document("First"))
        await #expect(throws: DeploymentPlanError.invalidData) { try await store.save(document("")) }
        #expect(try await store.load() == document("First"))
    }

    @Test func oversizedLibraryIsNotReset() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try Data(repeating: 32, count: DeploymentPlanCodec.maximumBytes + 1).write(to: file)
        let store = DeploymentPlanStore(fileURL: file)
        await #expect(throws: DeploymentPlanError.tooLarge) { try await store.load() }
        await #expect(throws: DeploymentPlanStoreError.notLoaded) { try await store.save(document("New")) }
        #expect(try Data(contentsOf: file).count == DeploymentPlanCodec.maximumBytes + 1)
    }
}
