import SwiftUI

struct ChannelsListScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @State private var viewModel = ChannelsViewModel()

    var body: some View {
        NavigationStack {
            List(orderedChannels) { channel in
                NavigationLink(value: channel) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(channel.name).font(.headline)
                        if let lastMessage = channel.lastMessage {
                            Text(lastMessage)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Text("\(channel.messageCount) messages")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
            }
            .navigationTitle("Channels")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    RegionFilterMenu()
                }
            }
            .navigationDestination(for: MeshChannel.self) { channel in
                ChannelDetailScreen(channel: channel)
            }
            .overlay {
                if viewModel.channels.isEmpty && !viewModel.isLoading {
                    ContentUnavailableView("No channels yet", systemImage: "number")
                }
            }
            .overlay {
                if viewModel.isLoading {
                    LoadingIndicator()
                }
            }
        }
        .task(id: regionFilter.selectedRegion) {
            viewModel.configure(settings: settings)
            await viewModel.loadChannels(region: regionFilter.selectedRegion)
        }
        .refreshable {
            await viewModel.loadChannels(region: regionFilter.selectedRegion)
        }
    }

    private var orderedChannels: [MeshChannel] {
        viewModel.channels.sorted { lhs, rhs in
            let lhsIsPublic = lhs.name.caseInsensitiveCompare("Public") == .orderedSame
            let rhsIsPublic = rhs.name.caseInsensitiveCompare("Public") == .orderedSame
            if lhsIsPublic != rhsIsPublic {
                return lhsIsPublic
            }
            return lhs.lastActivity > rhs.lastActivity
        }
    }
}
