import CryptoKit
import Foundation

enum DeploymentPlanStoreError: Error, Equatable {
    case notLoaded
    case changedOnDisk
    case storageFull
}

/// One actor per library URL. Keep plans outside purgeable terrain/analyzer caches.
actor DeploymentPlanStore {
    private let fileURL: URL
    private let write: @Sendable (Data, URL) throws -> Void
    private var loaded = false
    private var baseline: Data?

    init(fileURL: URL, write: @escaping @Sendable (Data, URL) throws -> Void = { data, url in
        try data.write(to: url, options: .atomic)
    }) {
        self.fileURL = fileURL
        self.write = write
    }

    static func applicationStore() throws -> DeploymentPlanStore {
        let directory = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        ).appendingPathComponent("CandidateSites", isDirectory: true)
        return DeploymentPlanStore(fileURL: directory.appendingPathComponent("plans.json"))
    }

    private var directory: URL { fileURL.deletingLastPathComponent() }
    private var results: URL { directory.appendingPathComponent("results", isDirectory: true) }
    /// The last loaded or saved document as stored: version, revisions and result references.
    private var stored: DeploymentPlanDocument?
    /// All referenced result files together (schema: 512 MiB for A1).
    static let maximumResultStorage = 512 * 1024 * 1024

    /// Reads versions 1–3 (docs/node-planner/saved-plans-v3-schema.md). Loading never rewrites anything.
    /// In memory a coverage result travels in `calculatedResult`; on disk it's an immutable file in
    /// `results/`, named by its SHA-256.
    func load() throws -> DeploymentPlanDocument {
        loaded = false
        let bytes = try read()
        let document = try bytes.map(DeploymentPlanCodec.decode) ?? DeploymentPlanDocument(plans: [])
        baseline = bytes
        stored = document
        loaded = true
        return withResults(document)
    }

    /// Saves a complete snapshot as version 3. On failure the caller keeps its draft and the old files.
    /// A prior successful load is required; changed/deleted files require an explicit reload.
    /// Returns the document as saved: revisions, references and result states.
    @discardableResult
    func save(_ document: DeploymentPlanDocument) throws -> DeploymentPlanDocument {
        guard loaded, let previous = stored else { throw DeploymentPlanStoreError.notLoaded }
        guard try read() == baseline else { throw DeploymentPlanStoreError.changedOnDisk }
        var payloads: [String: Data] = [:]
        let next = try version3(document, previous: previous, payloads: &payloads)
        let bytes = try DeploymentPlanCodec.encode(next)
        var seen = Set<String>(), total = 0
        for ref in next.studies.compactMap(\.resultRef) where seen.insert(ref.sha256).inserted { total += ref.byteLength }
        guard total <= Self.maximumResultStorage else { throw DeploymentPlanStoreError.storageFull }

        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // 1. Result files first: an interrupted save leaves only unreferenced files behind.
        for (sha, data) in payloads { try install(sha, data) }
        // 2. Before the first version 3 file replaces a version 1 or 2 original, keep the original.
        if previous.schemaVersion < 3, let baseline { try backUp(baseline) }
        // 3. The manifest, atomically.
        try write(bytes, fileURL)
        baseline = bytes
        stored = next
        // 4. Only then remove result files nothing references any more.
        collectGarbage(next)
        return withResults(next)
    }

    /// The document as version 3: result bytes moved to files, revisions advanced where records changed.
    private func version3(_ document: DeploymentPlanDocument, previous: DeploymentPlanDocument,
                          payloads: inout [String: Data]) throws -> DeploymentPlanDocument {
        let old = Dictionary(previous.studies.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let known = Dictionary(previous.studies.compactMap { $0.resultRef.map { ($0.sha256, $0) } }, uniquingKeysWith: { first, _ in first })
        let studies: [SavedStudy] = document.studies.map { input in
            var study = input
            let before = old[study.id]
            if let data = study.coverage?.calculatedResult ?? study.linkProfileResult {
                let sha = Self.sha256(data)
                payloads[sha] = data
                // Unchanged bytes keep the reference they were saved under (a migrated result keeps its format);
                // a new record with bytes the library already holds, such as a duplicate, shares that reference.
                let existing = before == nil ? known[sha] : before?.resultRef.flatMap { $0.sha256 == sha ? $0 : nil }
                // A portable payload without a readable fingerprint fails the manifest's validation, so it's never saved.
                // A Link Profile accepts only its portable format; anything else fails validation too.
                study.resultRef = existing ?? (study.kind == .linkProfile
                    ? ResultRef(format: ResultRef.portableLinkProfile, version: 1, byteLength: data.count, sha256: sha,
                                inputFingerprint: PortableLinkProfileResult.fingerprint(of: data))
                    : PortableCoverageResult.isPortable(data)
                    ? ResultRef(format: ResultRef.portableCoverage, version: 1, byteLength: data.count, sha256: sha,
                                inputFingerprint: PortableCoverageResult.fingerprint(of: data))
                    : ResultRef(format: ResultRef.iosLegacy, version: 1, byteLength: data.count, sha256: sha, inputFingerprint: nil))
            } else if let before, before.resultRef != nil, Self.sameInputs(before, study) {
                // A result that couldn't be read here stays attached while the calculation inputs are unchanged.
                study.resultRef = before.resultRef
            } else if before == nil, let ref = study.resultRef, known[ref.sha256] == ref {
                // A duplicate shares a reference the library already holds, even one this device can't read.
                study.resultRef = ref
            } else {
                study.resultRef = nil
            }
            study.coverage?.calculatedResult = nil
            study.linkProfileResult = nil
            study.resultStatus = .none
            study.revision = nil
            return study
        }
        func revised<T: Equatable>(_ item: T, _ before: T?, _ revision: Int64?, _ strip: (T) -> T) throws -> Int64 {
            guard let prior = revision else { return 1 }
            if let before, strip(before) == strip(item) { return prior }
            guard prior < DeploymentPlanDocument.maximumRevision else { throw DeploymentPlanError.invalidData }
            return prior + 1
        }
        let oldPlans = Dictionary(previous.plans.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let oldContainers = Dictionary(previous.studyPlans.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let plans = try document.plans.map { plan -> DeploymentPlan in
            var next = plan
            next.revision = try revised(plan, oldPlans[plan.id], oldPlans[plan.id]?.revision) { var copy = $0; copy.revision = nil; return copy }
            return next
        }
        let containers = try document.studyPlans.map { container -> StudyPlan in
            var next = container
            next.revision = try revised(container, oldContainers[container.id], oldContainers[container.id]?.revision) {
                var copy = $0; copy.revision = nil; return copy
            }
            return next
        }
        let revisedStudies = try studies.map { study -> SavedStudy in
            var next = study
            next.revision = try revised(study, old[study.id], old[study.id]?.revision) {
                var copy = $0
                copy.revision = nil; copy.resultStatus = .none; copy.coverage?.calculatedResult = nil; copy.linkProfileResult = nil
                return copy
            }
            return next
        }
        let revision = previous.documentRevision ?? 0
        guard revision < DeploymentPlanDocument.maximumRevision else { throw DeploymentPlanError.invalidData }
        return DeploymentPlanDocument(schemaVersion: 3, plans: plans, studies: revisedStudies,
                                      documentRevision: revision + 1, studyPlans: containers)
    }

    /// Calculation inputs only: names, plan membership and result fields don't count.
    static func sameInputs(_ a: SavedStudy, _ b: SavedStudy) -> Bool {
        var left = a.coverage, right = b.coverage
        left?.calculatedResult = nil
        right?.calculatedResult = nil
        return a.kind == b.kind && a.linkProfile == b.linkProfile && left == right
    }

    /// Attach result bytes and states for callers; the stored form keeps only references.
    private func withResults(_ document: DeploymentPlanDocument) -> DeploymentPlanDocument {
        var document = document
        guard document.schemaVersion == 3 else {
            for index in document.studies.indices where document.studies[index].coverage?.calculatedResult != nil {
                document.studies[index].resultStatus = .available
            }
            return document
        }
        for index in document.studies.indices {
            guard let ref = document.studies[index].resultRef else { continue }
            let url = results.appendingPathComponent("\(ref.sha256).result")
            guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  let data = size <= ref.byteLength ? try? Data(contentsOf: url) : Data() else {
                document.studies[index].resultStatus = .missing
                continue
            }
            if data.count != ref.byteLength || Self.sha256(data) != ref.sha256 {
                document.studies[index].resultStatus = .unverified
            } else if ref.format == ResultRef.portableLinkProfile, let inputs = document.studies[index].linkProfile {
                // Shown only for the inputs it was calculated from.
                if ref.inputFingerprint == PortableLinkProfileResult.studyFingerprint(inputs) {
                    document.studies[index].linkProfileResult = data
                    document.studies[index].resultStatus = .available
                } else {
                    document.studies[index].resultStatus = .unverified
                }
            } else if ref.format == ResultRef.portableCoverage, let coverage = document.studies[index].coverage,
                      ref.inputFingerprint != PortableCoverageResult.studyFingerprint(coverage) {
                // A portable result shows only for the inputs it was calculated from.
                document.studies[index].resultStatus = .unverified
            } else if ref.format == ResultRef.iosLegacy || ref.format == ResultRef.portableCoverage,
                      document.studies[index].coverage != nil {
                document.studies[index].coverage?.calculatedResult = data
                document.studies[index].resultStatus = .available
            } else {
                document.studies[index].resultStatus = .unsupported
            }
        }
        return document
    }

    /// Content-addressed install. An existing file is trusted only after its bytes are checked.
    private func install(_ sha: String, _ data: Data) throws {
        try FileManager.default.createDirectory(at: results, withIntermediateDirectories: true)
        let target = results.appendingPathComponent("\(sha).result")
        if let existing = try? Data(contentsOf: target), existing.count == data.count, Self.sha256(existing) == sha { return }
        try data.write(to: target, options: .atomic)
    }

    /// Keep the exact original: reuse the existing sibling v1 backup or an identical earlier one,
    /// otherwise add a new content-addressed backup. A different existing backup is never overwritten.
    private func backUp(_ original: Data) throws {
        let sibling = fileURL.appendingPathExtension("v1-backup")
        if (try? Data(contentsOf: sibling)) == original { return }
        let backups = directory.appendingPathComponent("migration-backups", isDirectory: true)
        try FileManager.default.createDirectory(at: backups, withIntermediateDirectories: true)
        let target = backups.appendingPathComponent("\(Self.sha256(original)).json")
        if (try? Data(contentsOf: target)) == original { return }
        try original.write(to: target, options: .atomic)
    }

    /// Remove result files the saved manifest doesn't reference. Backups hold inline results, not references.
    private func collectGarbage(_ document: DeploymentPlanDocument) {
        let referenced = Set(document.studies.compactMap { $0.resultRef?.sha256 })
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: results.path) else { return }
        for name in names where name.hasSuffix(".result") {
            let sha = String(name.dropLast(".result".count))
            guard sha.range(of: #"^[0-9a-f]{64}$"#, options: .regularExpression) != nil, !referenced.contains(sha) else { continue }
            try? FileManager.default.removeItem(at: results.appendingPathComponent(name))
        }
    }

    static func sha256(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }

    private func read() throws -> Data? {
        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: fileURL)
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile {
            return nil
        }
        defer { try? handle.close() }
        var bytes = Data()
        while true {
            let remaining = DeploymentPlanCodec.maximumBytes + 1 - bytes.count
            guard remaining > 0 else { throw DeploymentPlanError.tooLarge }
            guard let chunk = try handle.read(upToCount: min(65536, remaining)), !chunk.isEmpty else { break }
            bytes.append(chunk)
            guard bytes.count <= DeploymentPlanCodec.maximumBytes else { throw DeploymentPlanError.tooLarge }
        }
        return bytes
    }
}

/// Disk use of a saved-plans folder, for Settings: result files and pre-upgrade backups.
struct SavedPlansUsage: Equatable, Sendable {
    var resultBytes: Int64 = 0
    var backupBytes: Int64 = 0

    /// Measures CandidateSites/ next to `fileURL`. Missing folders count as empty.
    static func measure(fileURL: URL) -> SavedPlansUsage {
        let directory = fileURL.deletingLastPathComponent()
        func size(of url: URL) -> Int64 {
            Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        func total(_ folder: String, suffix: String) -> Int64 {
            let files = (try? FileManager.default.contentsOfDirectory(
                at: directory.appendingPathComponent(folder, isDirectory: true), includingPropertiesForKeys: [.fileSizeKey])) ?? []
            return files.filter { $0.lastPathComponent.hasSuffix(suffix) }.reduce(0) { $0 + size(of: $1) }
        }
        let sibling = URL(fileURLWithPath: fileURL.path + ".v1-backup")
        let siblingBytes = FileManager.default.fileExists(atPath: sibling.path) ? size(of: sibling) : 0
        return SavedPlansUsage(resultBytes: total("results", suffix: ".result"),
                               backupBytes: total("migration-backups", suffix: ".json") + siblingBytes)
    }

    static func application() -> SavedPlansUsage {
        guard let store = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                        appropriateFor: nil, create: false) else { return .init() }
        return measure(fileURL: store.appendingPathComponent("CandidateSites/plans.json"))
    }
}
