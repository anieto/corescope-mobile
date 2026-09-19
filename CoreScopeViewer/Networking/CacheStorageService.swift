import Foundation

struct CacheStorageUsage: Equatable, Sendable {
    let bytes: Int64
    let fileCount: Int
}

actor CacheStorageService {
    static let shared = CacheStorageService()

    private let cacheDirectoryNames = [
        "APIResponseCache",
        "MapResponseCache"
    ]

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

    func clear() async {
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
    }

    private var cacheDirectories: [URL] {
        let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return cacheDirectoryNames.map {
            root.appendingPathComponent($0, isDirectory: true)
        }
    }
}
