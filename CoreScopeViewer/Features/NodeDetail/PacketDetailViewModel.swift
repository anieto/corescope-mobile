import Foundation
import Observation

@Observable
@MainActor
final class PacketDetailViewModel {
    var detail: PacketDetailResponse?
    var isLoading = false
    var errorMessage: String?

    private var apiClient: APIClient?

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
    }

    func loadPacket(hash: String) async {
        guard let apiClient else { return }
        isLoading = true
        defer { isLoading = false }

        do {
            detail = try await apiClient.get("/api/packets/\(hash.urlPathComponentEncoded)")
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
