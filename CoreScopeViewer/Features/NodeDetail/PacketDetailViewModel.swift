import Foundation
import Observation

@Observable
@MainActor
final class PacketDetailViewModel {
    var detail: PacketDetailResponse?
    var isLoading = false
    var errorMessage: String?

    private var apiClient: APIClient?
    private var cacheNamespace = ""

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
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
            errorMessage = nil
        }
        isLoading = detail == nil
        defer { isLoading = false }

        do {
            detail = try await APIResponseCache.shared.refresh(for: cacheKey) {
                try await apiClient.get("/api/packets/\(hash.urlPathComponentEncoded)")
            }
            errorMessage = nil
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return
            }
            errorMessage = error.localizedDescription
        }
    }
}
