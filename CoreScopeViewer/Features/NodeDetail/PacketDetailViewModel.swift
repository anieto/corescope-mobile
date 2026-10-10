import Foundation
import Observation

@Observable
@MainActor
final class PacketDetailViewModel {
    var detail: PacketDetailResponse?
    var isLoading = false
    var errorMessage: String?
    var lastUpdatedAt: Date?

    private var apiClient: (any AnalyzerBackend)?
    private var cacheNamespace = ""

    func configure(settings: AnalyzerSettings) {
        apiClient = AnalyzerBackendFactory.make(settings: settings)
        cacheNamespace = settings.host.lowercased()
    }

    func loadPacket(hash: String) async {
        guard let apiClient else { return }
        let cacheKey = "packet-detail-\(cacheNamespace)-\(hash.lowercased())"
        if let cached: PacketDetailResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 24 * 60 * 60
        ) {
            detail = cached
            lastUpdatedAt = await APIResponseCache.shared.savedAt(for: cacheKey)
            errorMessage = nil
        }
        isLoading = detail == nil
        defer { isLoading = false }

        do {
            detail = try await APIResponseCache.shared.refresh(for: cacheKey) {
                try await apiClient.packetDetail(hash: hash)
            }
            lastUpdatedAt = .now
            errorMessage = nil
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return
            }
            errorMessage = error.localizedDescription
        }
    }
}
