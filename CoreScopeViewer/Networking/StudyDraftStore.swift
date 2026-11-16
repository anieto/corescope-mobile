import Foundation

/// Unsaved work in a Link Profile or Estimated Coverage session (deliverable C), kept so a source
/// switch, termination or crash doesn't lose it. One per editing session; never a named study.
struct StudyDraft: Codable, Equatable, Identifiable, Sendable {
    static let format = "nodescope.study-draft"
    var format = StudyDraft.format
    var version = 1
    var id: String
    var kind: SavedStudy.Kind
    var createdAtMilliseconds: Int64
    var updatedAtMilliseconds: Int64
    /// For the Unfinished studies list: the study's name, or where it is.
    var title: String
    /// The analyzer and region it was being edited against.
    var host: String?
    var region: String?
    /// The plan the tool was opened from, and the saved study it edits at the revision it opened.
    var planID: String?
    var savedStudyID: String?
    var savedRevision: Int64?
    /// The tool's raw editor values, including text that isn't a valid number yet.
    var editor: [String: String] = [:]
    /// Coverage: the captured node catalog, kept until an explicit refresh.
    var catalog: [SavedCoverageNode]?
    var catalogComplete = false
    /// A completed result for exactly these inputs: a portable payload in results/, by SHA-256.
    var resultSha256: String?
}

enum StudyDraftStoreError: Error, Equatable {
    case full
    case writeFailed
}

/// Drafts in `CandidateSites/drafts/<id>.json` beside the library (never a cache), each written
/// atomically on its own, so sessions can't overwrite one another. A draft's result is an ordinary
/// content-addressed result file; the library's cleanup keeps every file a draft refers to and deletes
/// nothing while any draft can't be read. Drafts are never evicted: at `maximumDrafts` a new one is
/// refused instead. Mirrors Android's StudyDraftStore.
struct StudyDraftStore: Sendable {
    static let maximumDrafts = 50
    /// A draft holds inputs and at most 200 captured nodes; far below this.
    private static let maximumDraftBytes = 2 * 1024 * 1024
    private static let maximumResultBytes = 8 * 1024 * 1024
    /// Shared with the library's cleanup, so a draft's file can't be collected while it's being written.
    static let lock = NSLock()

    let directory: URL
    private var drafts: URL { directory.appendingPathComponent("drafts", isDirectory: true) }
    private var results: URL { directory.appendingPathComponent("results", isDirectory: true) }

    static func application() throws -> StudyDraftStore {
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                  appropriateFor: nil, create: true)
        return StudyDraftStore(directory: support.appendingPathComponent("CandidateSites", isDirectory: true))
    }

    /// Saves `draft` and, if given, its `result` payload; returns the draft as stored.
    @discardableResult
    func save(_ draft: StudyDraft, result: Data?) throws -> StudyDraft {
        try Self.lock.withLock {
            let target = drafts.appendingPathComponent("\(draft.id).json")
            if !FileManager.default.fileExists(atPath: target.path), files().count >= Self.maximumDrafts {
                throw StudyDraftStoreError.full
            }
            var stored = draft
            stored.resultSha256 = result.map(DeploymentPlanStore.sha256)
            do {
                try FileManager.default.createDirectory(at: drafts, withIntermediateDirectories: true)
                // The reference first, then the file, so cleanup never sees the file without its owner.
                try JSONEncoder().encode(stored).write(to: target, options: .atomic)
                if let result, let sha = stored.resultSha256 {
                    try FileManager.default.createDirectory(at: results, withIntermediateDirectories: true)
                    let file = results.appendingPathComponent("\(sha).result")
                    if (try? Data(contentsOf: file)).map(DeploymentPlanStore.sha256) != sha {
                        try result.write(to: file, options: .atomic)
                    }
                }
            } catch {
                throw StudyDraftStoreError.writeFailed
            }
            return stored
        }
    }

    /// Every readable draft, most recently edited first.
    func list() -> [StudyDraft] {
        Self.lock.withLock {
            files().compactMap(read).sorted {
                $0.updatedAtMilliseconds != $1.updatedAtMilliseconds ? $0.updatedAtMilliseconds > $1.updatedAtMilliseconds : $0.id < $1.id
            }
        }
    }

    func draft(_ id: String) -> StudyDraft? {
        Self.lock.withLock { read(drafts.appendingPathComponent("\(id).json")) }
    }

    /// The draft's result payload, if its file is here and intact.
    func result(_ draft: StudyDraft) -> Data? {
        guard let sha = draft.resultSha256 else { return nil }
        let file = results.appendingPathComponent("\(sha).result")
        guard let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= Self.maximumResultBytes,
              let data = try? Data(contentsOf: file), DeploymentPlanStore.sha256(data) == sha else { return nil }
        return data
    }

    func delete(_ id: String) {
        Self.lock.withLock { try? FileManager.default.removeItem(at: drafts.appendingPathComponent("\(id).json")) }
    }

    /// Result files drafts refer to; nil if any draft can't be read, so cleanup deletes nothing.
    /// Call while holding `lock`.
    func referencedResults() -> Set<String>? {
        var referenced = Set<String>()
        for file in files() {
            guard let draft = read(file) else { return nil }
            if let sha = draft.resultSha256 { referenced.insert(sha) }
        }
        return referenced
    }

    private func files() -> [URL] {
        ((try? FileManager.default.contentsOfDirectory(at: drafts, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "json" }
    }

    private func read(_ file: URL) -> StudyDraft? {
        guard let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= Self.maximumDraftBytes,
              let data = try? Data(contentsOf: file),
              let draft = try? JSONDecoder().decode(StudyDraft.self, from: data),
              draft.format == StudyDraft.format, draft.version == 1, file.lastPathComponent == "\(draft.id).json" else { return nil }
        return draft
    }
}
