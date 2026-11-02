import Foundation

enum DeploymentPlanStoreError: Error, Equatable {
    case notLoaded
    case changedOnDisk
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

    func load() throws -> DeploymentPlanDocument {
        loaded = false
        let bytes = try read()
        let document = try bytes.map(DeploymentPlanCodec.decode) ?? DeploymentPlanDocument(plans: [])
        baseline = bytes
        loaded = true
        return document
    }

    /// Saves a complete validated snapshot. On failure the caller keeps its draft.
    /// A prior successful load is required; changed/deleted files require an explicit reload.
    func save(_ document: DeploymentPlanDocument) throws {
        guard loaded else { throw DeploymentPlanStoreError.notLoaded }
        let bytes = try DeploymentPlanCodec.encode(document)
        guard try read() == baseline else { throw DeploymentPlanStoreError.changedOnDisk }
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try write(bytes, fileURL)
        baseline = bytes
    }

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
