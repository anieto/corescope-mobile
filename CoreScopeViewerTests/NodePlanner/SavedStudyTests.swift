import Foundation
import Testing
@testable import CoreScopeViewer

struct SavedStudyTests {
    private func cases() throws -> [[String: Any]] {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" {
            let file = root.appendingPathComponent("shared/fixtures/candidate-sites/plan-v2-cases.json")
            if FileManager.default.fileExists(atPath: file.path) {
                return try #require(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [[String: Any]])
            }
            root.deleteLastPathComponent()
        }
        throw DeploymentPlanError.invalidData
    }

    private func document() throws -> DeploymentPlanDocument {
        let fixture = try #require(cases().first)
        return try DeploymentPlanCodec.decode(JSONSerialization.data(withJSONObject: #require(fixture["document"])))
    }

    private func file() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("plans.json")
    }

    @Test func sharedFixturesAndInputRoundTrips() throws {
        for fixture in try cases() {
            let name = try #require(fixture["name"] as? String)
            let bytes = try JSONSerialization.data(withJSONObject: #require(fixture["document"]))
            if fixture["valid"] as? Bool == true {
                let decoded = try DeploymentPlanCodec.decode(bytes)
                #expect(try DeploymentPlanCodec.decode(DeploymentPlanCodec.encode(decoded)) == decoded, "Round trip: \(name)")
            } else {
                #expect(throws: (any Error).self, "Reject: \(name)") { try DeploymentPlanCodec.decode(bytes) }
            }
        }
        let snapshot = try document()
        #expect(snapshot.studies[0].linkProfile?.pointA.antenna.heightMeters == 6.096)
        #expect(snapshot.studies[0].linkProfile?.pointB.name == nil)
        #expect(snapshot.studies[1].coverage?.radiusMeters == 16_093)
        #expect(snapshot.studies[1].coverage?.nodes.first?.name == "Old node name")
    }

    @Test func migrationPreservesLegacyBytesAndUnknownAssumptions() async throws {
        let url = try file()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let next = try document()
        let legacy = DeploymentPlanDocument(plans: next.plans)
        let bytes = try DeploymentPlanCodec.encode(legacy)
        try bytes.write(to: url)
        let store = DeploymentPlanStore(fileURL: url)
        #expect(try await store.load() == legacy)
        #expect(!FileManager.default.fileExists(atPath: url.appendingPathExtension("v1-backup").path))
        try await store.save(next)
        #expect(try Data(contentsOf: url.appendingPathExtension("v1-backup")) == bytes)
        let reopened = try await DeploymentPlanStore(fileURL: url).load()
        #expect(reopened == next)
        #expect(reopened.plans == legacy.plans)
        #expect(reopened.plans.first?.sites.first?.assumptions.frequencyMHz == nil)
        await #expect(throws: DeploymentPlanError.unsupportedVersion) { try await store.save(legacy) }
        #expect(try await store.load() == next)
    }

    @Test func interruptedMigrationCanBeRetriedWithoutLosingOriginal() async throws {
        let url = try file()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let next = try document()
        let bytes = try DeploymentPlanCodec.encode(DeploymentPlanDocument(plans: next.plans))
        try bytes.write(to: url)
        let failing = DeploymentPlanStore(fileURL: url, write: { _, _ in throw CocoaError(.fileWriteOutOfSpace) })
        _ = try await failing.load()
        await #expect(throws: (any Error).self) { try await failing.save(next) }
        #expect(try Data(contentsOf: url) == bytes)
        #expect(try Data(contentsOf: url.appendingPathExtension("v1-backup")) == bytes)
        let retry = DeploymentPlanStore(fileURL: url)
        _ = try await retry.load()
        try await retry.save(next)
        #expect(try await retry.load() == next)
    }

    @Test func conflictingBackupAndCorruptLibraryStayProtected() async throws {
        let url = try file()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let next = try document()
        let bytes = try DeploymentPlanCodec.encode(DeploymentPlanDocument(plans: next.plans))
        try bytes.write(to: url)
        let backup = url.appendingPathExtension("v1-backup")
        try Data("other original".utf8).write(to: backup)
        let store = DeploymentPlanStore(fileURL: url)
        _ = try await store.load()
        await #expect(throws: DeploymentPlanStoreError.changedOnDisk) { try await store.save(next) }
        #expect(try Data(contentsOf: url) == bytes)
        try Data("corrupt".utf8).write(to: url)
        await #expect(throws: (any Error).self) { try await store.load() }
        await #expect(throws: DeploymentPlanStoreError.notLoaded) { try await store.save(next) }
        #expect(try Data(contentsOf: url) == Data("corrupt".utf8))
    }

    @Test func nonfiniteAndDuplicateIdentitiesAreRejected() throws {
        var inputs = try #require(document().studies[1].coverage)
        inputs.receiver.gainDbi = .infinity
        #expect(throws: DeploymentPlanError.invalidData) { try inputs.validate() }
    }
}
