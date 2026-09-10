import Foundation
import Observation

@Observable
@MainActor
final class ChannelsViewModel {
    var channels: [MeshChannel] = []
    var messages: [ChannelMessage] = []
    var isLoading = false
    var errorMessage: String?

    private var apiClient: APIClient?

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
    }

    func loadChannels(region: String?) async {
        guard let apiClient else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            var query: [URLQueryItem] = []
            if let region {
                query.append(URLQueryItem(name: "region", value: region))
            }
            let response: ChannelsResponse = try await apiClient.get("/api/channels", query: query)
            channels = response.channels
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadMessages(hash: String) async {
        guard let apiClient else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let response: ChannelMessagesResponse = try await apiClient.get(
                "/api/channels/\(hash.urlPathComponentEncoded)/messages"
            )
            messages = response.messages
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
