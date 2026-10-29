import Foundation

struct CacheStorageUsage: Equatable, Sendable {
    let bytes: Int64
    let fileCount: Int
}

/// Shared by dashboard windows, with a disposable lifetime separate from the live feed.
final class MyNodesRepositories: Sendable {
    let base: MyNodesBaseRepository
    let analytics: MyNodesAnalyticsRepository
    let lifetime: AnalyzerSourceSession
    private let parent: AnalyzerSourceSession
    private let cancellationID: UUID

    init(parent: AnalyzerSourceSession, lifetime: AnalyzerSourceSession,
         base: MyNodesBaseRepository, analytics: MyNodesAnalyticsRepository) {
        self.parent = parent
        self.lifetime = lifetime
        self.base = base
        self.analytics = analytics
        self.cancellationID = parent.registerCancellation { lifetime.invalidate() }
    }

    func invalidate() {
        lifetime.invalidate()
        parent.removeCancellation(cancellationID)
    }

    deinit {
        lifetime.invalidate()
        parent.removeCancellation(cancellationID)
    }
}

actor CacheStorageService {
    static let shared = CacheStorageService()

    private let cacheDirectoryNames = [
        "APIResponseCache",
        "MapResponseCache",
        "MyNodesSnapshots"
    ]

    private let cacheRoot: URL
    private var snapshotStore: MyNodesSnapshotStore?
    private struct RepositoryKey: Hashable {
        let source: String
        let sessionID: UUID
    }
    private var repositories: [RepositoryKey: MyNodesRepositories] = [:]
    private var clearingMyNodes = false

    /// The factory must bind both repositories to the supplied disposable lifetime.
    func myNodesRepositories(source: String, session: AnalyzerSourceSession,
                             make: (AnalyzerSourceSession) throws -> (MyNodesBaseRepository, MyNodesAnalyticsRepository)) throws -> MyNodesRepositories {
        try session.check()
        guard !clearingMyNodes else { throw CancellationError() }
        repositories = repositories.filter { $0.value.lifetime.isCurrent }
        let key = RepositoryKey(source: source, sessionID: session.id)
        if let existing = repositories[key] { return existing }
        let lifetime = AnalyzerSourceSession()
        do {
            let (base, analytics) = try make(lifetime)
            guard base.source == source, analytics.source == source else { throw APIError.invalidResponse }
            let owned = MyNodesRepositories(parent: session, lifetime: lifetime, base: base, analytics: analytics)
            try lifetime.check()
            repositories[key] = owned
            return owned
        } catch {
            lifetime.invalidate()
            throw error
        }
    }

    func myNodesCoreScope(client: APIClient, session: AnalyzerSourceSession) throws -> MyNodesRepositories {
        try myNodesRepositories(source: "corescope|\(client.cacheIdentifier)", session: session) { lifetime in
            (.coreScope(client: client, session: lifetime), .coreScope(client: client, session: lifetime))
        }
    }

    func myNodesBeacon(client: BeaconAPIClient, serverInfo: BeaconServerInfo) throws -> MyNodesRepositories {
        try serverInfo.requireSupported()
        return try myNodesRepositories(source: client.cacheIdentifier, session: client.sourceSession) { lifetime in
            let scoped = try client.replacingSourceSession(lifetime)
            return (try .beacon(client: scoped, serverInfo: serverInfo), try .beacon(client: scoped, serverInfo: serverInfo))
        }
    }

    init(cacheRoot: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]) {
        self.cacheRoot = cacheRoot
    }

    /// Every window obtains the same store. Recovery runs on this actor, off the UI thread.
    func myNodesSnapshots() throws -> MyNodesSnapshotStore {
        guard !clearingMyNodes else { throw CancellationError() }
        if let snapshotStore { return snapshotStore }
        let store = try MyNodesSnapshotStore(fileURL: snapshotFile)
        snapshotStore = store
        return store
    }

    /// Clears all disposable My Nodes data, including in-memory base/catalog/analytics caches.
    func clearMyNodesSnapshots() async throws {
        guard !clearingMyNodes else { throw CancellationError() }
        clearingMyNodes = true
        defer { clearingMyNodes = false }
        let owned = Array(repositories.values)
        repositories.removeAll()
        // Invalidate every generation before the first actor hop.
        owned.forEach { $0.invalidate() }
        for repository in owned {
            await repository.base.clear()
            await repository.analytics.clear()
        }
        if let snapshotStore {
            try await snapshotStore.clear()
        } else if FileManager.default.fileExists(atPath: snapshotFile.path) {
            // Clearing unopened storage must not recover candidates or decode corrupt data.
            try FileManager.default.removeItem(at: snapshotFile)
        }
    }

    private var snapshotFile: URL {
        cacheRoot.appendingPathComponent("MyNodesSnapshots", isDirectory: true)
            .appendingPathComponent("snapshots.json")
    }

    func usage() -> CacheStorageUsage {
        var totalBytes = Int64(URLCache.shared.currentDiskUsage + URLCache.shared.currentMemoryUsage)
        var fileCount = 0

        for directory in cacheDirectories {
            guard let enumerator = FileManager.default.enumerator(
                at: directory,
                includingPropertiesForKeys: [
                    .isRegularFileKey,
                    .fileAllocatedSizeKey,
                    .totalFileAllocatedSizeKey
                ],
                options: [.skipsHiddenFiles]
            ) else {
                continue
            }

            for case let fileURL as URL in enumerator {
                guard let values = try? fileURL.resourceValues(
                    forKeys: [.isRegularFileKey, .fileAllocatedSizeKey, .totalFileAllocatedSizeKey]
                ), values.isRegularFile == true else {
                    continue
                }

                totalBytes += Int64(values.totalFileAllocatedSize ?? values.fileAllocatedSize ?? 0)
                fileCount += 1
            }
        }

        return CacheStorageUsage(bytes: totalBytes, fileCount: fileCount)
    }

    func clear() async -> Bool {
        await APIResponseCache.shared.clear()
        await MapViewModel.clearCachedResponses()
        await ChannelListCache.shared.clear()
        await ChannelMessagesCache.shared.clear()
        await PacketFeedCache.shared.clear()
        URLCache.shared.removeAllCachedResponses()

        let defaults = UserDefaults.standard
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("last-map-refresh-") {
            defaults.removeObject(forKey: key)
        }
        do {
            try await clearMyNodesSnapshots()
            return true
        } catch {
            return false
        }
    }

    private var cacheDirectories: [URL] {
        cacheDirectoryNames.map { cacheRoot.appendingPathComponent($0, isDirectory: true) }
    }
}
