import SwiftUI

struct ChannelsListScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ChannelMonitorStore.self) private var monitorStore
    @State private var viewModel = ChannelsViewModel()
    @State private var isShowingAddChannel = false

    var body: some View {
        NavigationStack {
            List {
                if !monitoredChannels.isEmpty {
                    Section("Monitoring on This Device") {
                        ForEach(monitoredChannels) { channel in
                            channelRow(channel)
                        }
                    }
                }

                Section {
                    ForEach(orderedChannels) { channel in
                        channelRow(channel)
                    }
                }
            }
            .navigationTitle("Channels")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 16) {
                        Button {
                            isShowingAddChannel = true
                        } label: {
                            Label("Add Channel", systemImage: "plus")
                        }
                        RegionFilterMenu()
                    }
                }
            }
            .navigationDestination(for: MeshChannel.self) { channel in
                ChannelDetailScreen(channel: channel)
            }
            .overlay {
                if viewModel.channels.isEmpty && monitorStore.channels.isEmpty && !viewModel.isLoading {
                    ContentUnavailableView("No channels yet", systemImage: "number")
                }
            }
            .overlay {
                if viewModel.isLoading {
                    LoadingIndicator()
                }
            }
        }
        .task(id: "\(settings.host)|\(regionFilter.selectedRegion ?? "")") {
            viewModel.configure(settings: settings)
            await viewModel.loadChannels(region: regionFilter.selectedRegion)
        }
        .task(id: monitoredChannelIDs) {
            viewModel.configure(settings: settings)
            await viewModel.refreshMonitoredSummaries(
                channels: monitorStore.channels,
                region: regionFilter.selectedRegion,
                monitorStore: monitorStore
            )
        }
        .refreshable {
            await viewModel.loadChannels(region: regionFilter.selectedRegion, forceRefresh: true)
        }
        .sheet(isPresented: $isShowingAddChannel) {
            MonitorChannelSheet()
        }
    }

    private var orderedChannels: [MeshChannel] {
        viewModel.channels.filter { serverChannel in
            !monitorStore.channels.contains { $0.channelName == serverChannel.name }
        }
        .sorted { lhs, rhs in
            let lhsIsPublic = lhs.name.caseInsensitiveCompare("Public") == .orderedSame
            let rhsIsPublic = rhs.name.caseInsensitiveCompare("Public") == .orderedSame
            if lhsIsPublic != rhsIsPublic {
                return lhsIsPublic
            }
            return lhs.lastActivity > rhs.lastActivity
        }
    }

    private var monitoredChannels: [MeshChannel] {
        monitorStore.channels.map { monitoredChannel in
            MeshChannel(
                hash: "user:\(monitoredChannel.channelName)",
                name: monitoredChannel.title,
                lastMessage: monitoredChannel.lastMessage ?? "Monitored locally",
                lastSender: nil,
                messageCount: monitoredChannel.messageCount ?? 0,
                lastActivity: monitoredChannel.lastActivity ?? .distantPast
            )
        }
    }

    private var monitoredChannelIDs: String {
        monitorStore.channels.map(\.id).sorted().joined(separator: "|")
    }

    private func channelRow(_ channel: MeshChannel) -> some View {
        NavigationLink(value: channel) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(channel.name).font(.headline)
                    if monitorStore.channel(matching: channel) != nil {
                        Image(systemName: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
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
        .swipeActions {
            if let monitoredChannel = monitorStore.channel(matching: channel) {
                Button(role: .destructive) {
                    try? monitorStore.remove(monitoredChannel)
                } label: {
                    Label("Stop Monitoring", systemImage: "trash")
                }
            }
        }
    }
}
