import SwiftUI

struct ChannelsListScreen: View {
    let resetID: UUID

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ChannelMonitorStore.self) private var monitorStore
    @Environment(LiveFeedService.self) private var liveFeed
    @State private var viewModel = ChannelsViewModel()
    @State private var isShowingAddChannel = false
    @State private var navigationPath = NavigationPath()
    @State private var lastProcessedLiveEventID: Int?
    @State private var liveRefreshTask: Task<Void, Never>?
    @State private var searchText = ""
    @State private var isSearchPresented = false

    var body: some View {
        NavigationStack(path: $navigationPath) {
            List {
                ChannelsHeader(
                    isConnected: liveFeed.isConnected,
                    regionName: regionFilter.selectedRegion.map(regionFilter.label(for:)),
                    isSearchPresented: isSearchPresented,
                    toggleSearch: toggleSearch,
                    addChannel: { isShowingAddChannel = true }
                )
                .iPadWindowControlsClearance()
                .listRowInsets(EdgeInsets(top: 18, leading: 20, bottom: 8, trailing: 20))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)

                if isSearchPresented {
                    InstrumentSearchField(text: $searchText, prompt: "Search channels")
                        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 8, trailing: 20))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }

                if viewModel.isLoading {
                    LoadingIndicator(title: "Syncing mesh traffic")
                        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 8, trailing: 20))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }

                if !viewModel.isLoading,
                   viewModel.lastUpdatedAt != nil || viewModel.errorMessage != nil {
                    DataLoadStatusView(
                        lastUpdatedAt: viewModel.lastUpdatedAt,
                        errorMessage: viewModel.errorMessage,
                        hasContent: !viewModel.channels.isEmpty,
                        retry: retryChannelLoad
                    )
                    .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 8, trailing: 20))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }

                if !monitoredChannels.isEmpty {
                    Section {
                        ForEach(monitoredChannels) { channel in
                            channelRow(channel)
                        }
                    } header: {
                        MonitoringSectionHeader(count: monitoredChannels.count)
                            .textCase(nil)
                    }
                }

                if viewModel.isLoading && viewModel.channels.isEmpty {
                    Section {
                        ForEach(0..<4, id: \.self) { _ in
                            ChannelSkeletonRow()
                                .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 20))
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                        }
                    }
                } else {
                    Section {
                        ForEach(orderedChannels) { channel in
                            channelRow(channel)
                        }
                    } header: {
                        Text("Server Monitored Channels")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(NodeScopeBackground())
            .listStyle(.plain)
            .adaptiveContentWidth()
            .background(NodeScopeBackground())
            .contentMargins(.bottom, 104, for: .scrollContent)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: MeshChannel.self) { channel in
                ChannelDetailScreen(channel: channel)
            }
            .overlay {
                if orderedChannels.isEmpty && monitoredChannels.isEmpty && !viewModel.isLoading {
                    ContentUnavailableView(
                        searchText.isEmpty ? "No channels yet" : "No matching channels",
                        systemImage: searchText.isEmpty ? "number" : "magnifyingglass"
                    )
                }
            }
        }
        .onChange(of: resetID) {
            navigationPath = NavigationPath()
            isShowingAddChannel = false
            searchText = ""
            isSearchPresented = false
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
        .onChange(of: liveFeed.recentEvents.first?.id) {
            scheduleLiveRefreshIfNeeded()
        }
        .onDisappear {
            liveRefreshTask?.cancel()
        }
        .sheet(isPresented: $isShowingAddChannel) {
            MonitorChannelSheet()
        }
    }

    /// Mirrors ChannelDetailScreen's live-refresh: a burst of GRP_TXT events
    /// (one per observer that heard the same message) gets coalesced into a
    /// single re-fetch after a short quiet period, rather than hammering
    /// /api/channels and /api/packets on every individual event.
    private func scheduleLiveRefreshIfNeeded() {
        guard let latestEvent = liveFeed.recentEvents.first,
              latestEvent.id != lastProcessedLiveEventID else { return }
        lastProcessedLiveEventID = latestEvent.id
        guard isPossiblyChannelRelevant(latestEvent) else { return }

        liveRefreshTask?.cancel()
        liveRefreshTask = Task {
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            async let channels: Void = viewModel.loadChannels(region: regionFilter.selectedRegion, forceRefresh: true)
            async let summaries: Void = viewModel.refreshMonitoredSummaries(
                channels: monitorStore.channels,
                region: regionFilter.selectedRegion,
                monitorStore: monitorStore,
                forceRefresh: true
            )
            _ = await (channels, summaries)
        }
    }

    /// Deliberately permissive: the feed's `"message"` event type is
    /// undocumented and unmodeled here, and a `"packet"` event can arrive
    /// with `decoded` entirely absent (async server-side decode). Rather
    /// than risk silently dropping real channel activity because of an
    /// event shape we haven't verified against live traffic, anything that
    /// isn't confirmed to be an unrelated payload type triggers a refresh.
    private func isPossiblyChannelRelevant(_ event: LiveEnvelope) -> Bool {
        guard event.type == "packet" else { return true }
        let payloadType = event.data?.decoded?.header?.payloadType
        return payloadType == nil || payloadType == PayloadType.grpTxt.rawValue
    }

    private var orderedChannels: [MeshChannel] {
        viewModel.channels.filter { serverChannel in
            !monitorStore.channels.contains { $0.channelName == serverChannel.name }
                && matchesSearch(serverChannel)
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
        .filter(matchesSearch)
    }

    private var monitoredChannelIDs: String {
        monitorStore.channels.map(\.id).sorted().joined(separator: "|")
    }

    private func matchesSearch(_ channel: MeshChannel) -> Bool {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return true }
        return channel.name.localizedCaseInsensitiveContains(query)
            || channel.lastMessage?.localizedCaseInsensitiveContains(query) == true
            || channel.lastSender?.localizedCaseInsensitiveContains(query) == true
    }

    private func retryChannelLoad() {
        Task {
            await viewModel.loadChannels(region: regionFilter.selectedRegion, forceRefresh: true)
        }
    }

    private func toggleSearch() {
        withAnimation(.easeInOut(duration: 0.2)) {
            isSearchPresented.toggle()
            if !isSearchPresented {
                searchText = ""
            }
        }
    }

    private func channelRow(_ channel: MeshChannel) -> some View {
        NavigationLink(value: channel) {
            ChannelCard(
                name: channel.name,
                lastMessage: channel.lastMessage,
                lastSender: channel.lastSender,
                messageCount: channel.messageCount,
                lastActivity: channel.lastActivity,
                isMonitored: monitorStore.channel(matching: channel) != nil
            )
        }
        .buttonStyle(.plain)
        .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 20))
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
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

private struct ChannelsHeader: View {
    let isConnected: Bool
    let regionName: String?
    let isSearchPresented: Bool
    let toggleSearch: () -> Void
    let addChannel: () -> Void

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) {
                    Text("Channels")
                        .font(.largeTitle.bold())
                }
                HStack(spacing: 6) {
                    Circle()
                        .fill(isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                        .frame(width: 7, height: 7)
                    Text(isConnected ? "Live mesh traffic" : "Reconnecting")
                    if let regionName {
                        Text("· \(regionName)")
                    }
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            }

            Spacer()

            HStack(spacing: 10) {
                Button(action: toggleSearch) {
                    Image(systemName: isSearchPresented ? "xmark" : "magnifyingglass")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(NodeScopeStyle.signal)
                        .frame(width: 40, height: 40)
                        .background(.thinMaterial, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isSearchPresented ? "Close channel search" : "Search channels")

                RegionFilterMenu()
                    .foregroundStyle(NodeScopeStyle.signal)
                    .padding(.horizontal, 10)
                    .frame(minWidth: 40, minHeight: 40)
                    .background(.thinMaterial, in: Capsule())
                    .accessibilityLabel("Filter channels by region")

                Button(action: addChannel) {
                    Image(systemName: "plus")
                        .font(.body.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(NodeScopeStyle.signal, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add Channel")
            }
        }
    }
}

private struct MonitoringSectionHeader: View {
    let count: Int

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: "lock.shield.fill")
                .foregroundStyle(NodeScopeStyle.activity)
                .font(.caption.weight(.bold))
            VStack(alignment: .leading, spacing: 2) {
                Text("Monitoring on This Device")
                    .font(.caption.weight(.bold))
                Text("\(count) channel\(count == 1 ? "" : "s") · keys remain local")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
    }
}

private struct ChannelCard: View {
    let name: String
    let lastMessage: String?
    let lastSender: String?
    let messageCount: Int
    let lastActivity: Date
    let isMonitored: Bool
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: isMonitored ? "lock.bubble.fill" : "number")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(isMonitored ? NodeScopeStyle.activity : NodeScopeStyle.signal)
                .frame(width: 38, height: 38)
                .background(
                    (isMonitored ? NodeScopeStyle.activity : NodeScopeStyle.signal).opacity(0.13),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )

            VStack(alignment: .leading, spacing: 7) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(name)
                        .font(.headline)
                    Spacer()
                    HStack(spacing: 4) {
                        Image(systemName: "bubble.left.fill")
                        Text("\(messageCount)")
                    }
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    if lastActivity != .distantPast {
                        Text(RelativeTime.string(from: lastActivity))
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
                if let lastMessage {
                    if let lastSender {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 4) {
                                Image(systemName: "person.wave.2.fill")
                                Text(lastSender)
                            }
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(SenderColor.color(for: lastSender, colorScheme: colorScheme))

                            Text(lastMessage)
                                .font(.subheadline)
                                .foregroundStyle(.primary)
                                .lineLimit(2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(
                            SenderColor.bubbleFill(for: lastSender, colorScheme: colorScheme),
                            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                        )
                        .overlay {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(SenderColor.bubbleStroke(for: lastSender, colorScheme: colorScheme), lineWidth: 1)
                        }
                    } else {
                        Text(lastMessage)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
            }
        }
        .padding(14)
        .instrumentCard()
    }
}

private struct ChannelSkeletonRow: View {
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 12)
                .fill(NodeScopeStyle.signal.opacity(0.12))
                .frame(width: 38, height: 38)
            VStack(alignment: .leading, spacing: 9) {
                Capsule().fill(.quaternary).frame(width: 120, height: 12)
                Capsule().fill(.quaternary).frame(height: 10)
                Capsule().fill(.quaternary).frame(width: 72, height: 8)
            }
        }
        .padding(14)
        .instrumentCard()
        .redacted(reason: .placeholder)
        .modifier(SkeletonPulseModifier())
        .accessibilityLabel("Loading channel")
    }
}

private struct SkeletonPulseModifier: ViewModifier {
    @State private var isDimmed = false

    func body(content: Content) -> some View {
        content
            .opacity(isDimmed ? 0.58 : 1)
            .onAppear { isDimmed = true }
            .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: isDimmed)
    }
}
