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
    @State private var isFiltersPresented = false
    @State private var visibleServerChannels: [MeshChannel] = []
    @State private var visibleMonitoredChannels: [MeshChannel] = []
    @State private var sourceFilter = ChannelSourceFilter.all
    @State private var activityFilter = ChannelActivityFilter.all
    @State private var sortOption = ChannelSortOption.recent

    var body: some View {
        NavigationStack(path: $navigationPath) {
            List {
                ChannelsHeader(
                    isConnected: liveFeed.isConnected,
                    isSearchPresented: isSearchPresented,
                    toggleSearch: toggleSearch,
                    filterCount: activeFilterCount,
                    filterSummary: filterSummary,
                    showFilters: { isFiltersPresented = true },
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

                if !visibleMonitoredChannels.isEmpty {
                    Section {
                        ForEach(visibleMonitoredChannels) { channel in
                            channelRow(channel)
                        }
                    } header: {
                        MonitoringSectionHeader(count: visibleMonitoredChannels.count)
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
                        ForEach(visibleServerChannels) { channel in
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
            .floatingDockScrollClearance()
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: MeshChannel.self) { channel in
                ChannelDetailScreen(channel: channel)
            }
            .overlay {
                if visibleServerChannels.isEmpty && visibleMonitoredChannels.isEmpty && !viewModel.isLoading {
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
            updateVisibleChannels()
        }
        .task(id: monitoredChannelIDs) {
            viewModel.configure(settings: settings)
            await viewModel.refreshMonitoredSummaries(
                channels: monitorStore.channels,
                region: regionFilter.selectedRegion,
                monitorStore: monitorStore
            )
            updateVisibleChannels()
        }
        .refreshable {
            await viewModel.loadChannels(region: regionFilter.selectedRegion, forceRefresh: true)
            updateVisibleChannels()
        }
        .onChange(of: viewModel.channels) { updateVisibleChannels() }
        .onChange(of: monitoredChannelIDs) { updateVisibleChannels() }
        .onChange(of: searchText) { updateVisibleChannels() }
        .onChange(of: sourceFilter) { updateVisibleChannels() }
        .onChange(of: activityFilter) { updateVisibleChannels() }
        .onChange(of: sortOption) { updateVisibleChannels() }
        .onChange(of: liveFeed.recentEvents.first?.id) {
            scheduleLiveRefreshIfNeeded()
        }
        .onDisappear {
            liveRefreshTask?.cancel()
        }
        .sheet(isPresented: $isShowingAddChannel) {
            MonitorChannelSheet()
        }
        .sheet(isPresented: $isFiltersPresented) {
            ChannelFiltersSheet(
                sourceFilter: $sourceFilter,
                activityFilter: $activityFilter,
                sortOption: $sortOption
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
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

    private func updateVisibleChannels() {
        let monitored = monitorStore.channels.map { monitoredChannel in
            MeshChannel(
                hash: "user:\(monitoredChannel.channelName)",
                name: monitoredChannel.title,
                lastMessage: monitoredChannel.lastMessage ?? "Monitored locally",
                lastSender: nil,
                messageCount: monitoredChannel.messageCount ?? 0,
                lastActivity: monitoredChannel.lastActivity ?? .distantPast
            )
        }

        visibleMonitoredChannels = sourceFilter == .server
            ? []
            : sortChannels(monitored.filter(matchesFilters))

        let monitoredNames = Set(monitorStore.channels.map(\.channelName))
        let server = viewModel.channels.filter { channel in
            !monitoredNames.contains(channel.name) && matchesFilters(channel)
        }
        visibleServerChannels = sourceFilter == .monitored
            ? []
            : sortChannels(server)
    }

    private func matchesFilters(_ channel: MeshChannel) -> Bool {
        let matchesActivity = activityFilter == .all || channel.lastActivity.timeIntervalSinceNow > -3600
        return matchesActivity && matchesSearch(channel)
    }

    private func sortChannels(_ channels: [MeshChannel]) -> [MeshChannel] {
        channels.sorted { lhs, rhs in
            switch sortOption {
            case .recent:
                let lhsIsPublic = lhs.name.caseInsensitiveCompare("Public") == .orderedSame
                let rhsIsPublic = rhs.name.caseInsensitiveCompare("Public") == .orderedSame
                if lhsIsPublic != rhsIsPublic { return lhsIsPublic }
                return lhs.lastActivity > rhs.lastActivity
            case .messageCount:
                return lhs.messageCount == rhs.messageCount
                    ? lhs.lastActivity > rhs.lastActivity
                    : lhs.messageCount > rhs.messageCount
            case .name:
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
        }
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

    private var activeFilterCount: Int {
        (regionFilter.selectedRegion == nil ? 0 : 1)
            + (sourceFilter == .all ? 0 : 1)
            + (activityFilter == .all ? 0 : 1)
            + (sortOption == .recent ? 0 : 1)
    }

    private var filterSummary: String {
        var values = [regionFilter.selectedRegion.map(regionFilter.label(for:)) ?? String(localized: "Entire network")]
        if sourceFilter != .all {
            values.append(String(localized: sourceFilter.title))
        }
        if activityFilter != .all {
            values.append(String(localized: activityFilter.title))
        }
        if sortOption != .recent {
            values.append(String(localized: sortOption.title))
        }
        return values.formatted(.list(type: .and, width: .narrow))
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

private enum ChannelSourceFilter: String, CaseIterable, Identifiable {
    case all
    case monitored
    case server

    var id: String { rawValue }
    var title: LocalizedStringResource {
        switch self {
        case .all: "All Channels"
        case .monitored: "Monitored on This Device"
        case .server: "Server Monitored"
        }
    }
}

private enum ChannelActivityFilter: String, CaseIterable, Identifiable {
    case all
    case recent

    var id: String { rawValue }
    var title: LocalizedStringResource {
        switch self {
        case .all: "All Activity"
        case .recent: "Active in One Hour"
        }
    }
}

private enum ChannelSortOption: String, CaseIterable, Identifiable {
    case recent
    case messageCount
    case name

    var id: String { rawValue }
    var title: LocalizedStringResource {
        switch self {
        case .recent: "Recent Activity"
        case .messageCount: "Message Count"
        case .name: "Name"
        }
    }
}

private struct ChannelsHeader: View {
    let isConnected: Bool
    let isSearchPresented: Bool
    let toggleSearch: () -> Void
    let filterCount: Int
    let filterSummary: String
    let showFilters: () -> Void
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
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                Text(filterSummary)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            HStack(spacing: 10) {
                Button(action: showFilters) {
                    HStack(spacing: 4) {
                        Image(systemName: filterCount == 0
                            ? "line.3.horizontal.decrease.circle"
                            : "line.3.horizontal.decrease.circle.fill")
                        if filterCount > 0 {
                            Text("\(filterCount)")
                                .font(.caption.weight(.bold))
                        }
                    }
                    .font(.body.weight(.semibold))
                    .foregroundStyle(NodeScopeStyle.signal)
                    .padding(.horizontal, filterCount > 0 ? 10 : 0)
                    .frame(minWidth: 40, minHeight: 40)
                    .nodeScopeFloatingGlass(cornerRadius: 20)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Filter and sort channels")
                .accessibilityValue(filterCount == 0 ? "No filters active" : "\(filterCount) active")

                Button(action: toggleSearch) {
                    Image(systemName: isSearchPresented ? "xmark" : "magnifyingglass")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(NodeScopeStyle.signal)
                        .frame(width: 40, height: 40)
                        .nodeScopeFloatingGlass(cornerRadius: 20)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isSearchPresented ? "Close channel search" : "Search channels")

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

private struct ChannelFiltersSheet: View {
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(\.dismiss) private var dismiss
    @Binding var sourceFilter: ChannelSourceFilter
    @Binding var activityFilter: ChannelActivityFilter
    @Binding var sortOption: ChannelSortOption

    var body: some View {
        NavigationStack {
            Form {
                Section("Scope") {
                    NavigationLink {
                        RegionFilterSelectionScreen()
                    } label: {
                        HStack {
                            Label("Region", systemImage: "globe.americas")
                            Spacer()
                            Text(regionFilter.selectedRegion.map(regionFilter.label(for:)) ?? "Entire Network")
                                .foregroundStyle(.secondary)
                        }
                    }
                }

            Section("Source") {
                ForEach(ChannelSourceFilter.allCases) { option in
                    Button {
                        sourceFilter = option
                    } label: {
                        Label(option.title, systemImage: sourceFilter == option ? "checkmark" : "tray.full")
                    }
                }
            }

            Section("Activity") {
                ForEach(ChannelActivityFilter.allCases) { option in
                    Button {
                        activityFilter = option
                    } label: {
                        Label(option.title, systemImage: activityFilter == option ? "checkmark" : "clock")
                    }
                }
            }

            Section("Sort By") {
                ForEach(ChannelSortOption.allCases) { option in
                    Button {
                        sortOption = option
                    } label: {
                        Label(option.title, systemImage: sortOption == option ? "checkmark" : "arrow.up.arrow.down")
                    }
                }
            }
            }
            .navigationTitle("Channel Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Reset") {
                        regionFilter.selectedRegion = nil
                        sourceFilter = .all
                        activityFilter = .all
                        sortOption = .recent
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
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
