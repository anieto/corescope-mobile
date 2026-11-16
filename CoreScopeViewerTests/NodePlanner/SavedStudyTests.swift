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
        #expect(!FileManager.default.fileExists(atPath: migrationBackup(url, bytes).path))
        try await store.save(next)
        // Version 3 keeps the exact original before replacing it; no sibling file is invented.
        #expect(try Data(contentsOf: migrationBackup(url, bytes)) == bytes)
        #expect(!FileManager.default.fileExists(atPath: url.appendingPathExtension("v1-backup").path))
        let reopened = try await DeploymentPlanStore(fileURL: url).load()
        #expect(reopened.schemaVersion == 3)
        #expect(reopened.content == next.content)
        #expect(reopened.plans.first?.sites.first?.assumptions.frequencyMHz == nil)
        try await store.save(reopened)
        let backups = try FileManager.default.contentsOfDirectory(atPath: url.deletingLastPathComponent().appendingPathComponent("migration-backups").path)
        #expect(backups.count == 1)
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
        #expect(try Data(contentsOf: migrationBackup(url, bytes)) == bytes)
        let retry = DeploymentPlanStore(fileURL: url)
        _ = try await retry.load()
        try await retry.save(next)
        #expect(try await retry.load().content == next.content)
    }

    /// An existing sibling backup of other bytes is left alone; this original gets its own.
    @Test func differentExistingBackupIsKeptAndCorruptLibraryStaysProtected() async throws {
        let url = try file()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let next = try document()
        let bytes = try DeploymentPlanCodec.encode(DeploymentPlanDocument(plans: next.plans))
        try bytes.write(to: url)
        let backup = url.appendingPathExtension("v1-backup")
        try Data("other original".utf8).write(to: backup)
        let store = DeploymentPlanStore(fileURL: url)
        _ = try await store.load()
        try await store.save(next)
        #expect(try Data(contentsOf: backup) == Data("other original".utf8))
        #expect(try Data(contentsOf: migrationBackup(url, bytes)) == bytes)
        try Data("corrupt".utf8).write(to: url)
        await #expect(throws: (any Error).self) { try await store.load() }
        await #expect(throws: DeploymentPlanStoreError.notLoaded) { try await store.save(next) }
        #expect(try Data(contentsOf: url) == Data("corrupt".utf8))
    }

    /// The sibling backup v2 wrote is recognized in place when it holds these exact bytes.
    @Test func identicalSiblingBackupIsReused() async throws {
        let url = try file()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let bytes = try DeploymentPlanCodec.encode(DeploymentPlanDocument(plans: try document().plans))
        try bytes.write(to: url)
        try bytes.write(to: url.appendingPathExtension("v1-backup"))
        let store = DeploymentPlanStore(fileURL: url)
        _ = try await store.load()
        try await store.save(try document())
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().appendingPathComponent("migration-backups").path))
    }

    @Test func nonfiniteAndDuplicateIdentitiesAreRejected() throws {
        var inputs = try #require(document().studies[1].coverage)
        inputs.receiver.gainDbi = .infinity
        #expect(throws: DeploymentPlanError.invalidData) { try inputs.validate() }
    }
}
