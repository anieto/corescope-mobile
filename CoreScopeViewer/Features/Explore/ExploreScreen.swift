import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ExploreScreen: View {
    let resetID: UUID
    let isTabActive: Bool
    let openMap: () -> Void
    let openChannels: () -> Void
    let openObservers: () -> Void

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favoritesStore
    @Environment(RecentItemsStore.self) private var recentItemsStore
    @Environment(AppNavigationStore.self) private var appNavigationStore
    @State private var navigationPath = NavigationPath()
    @State private var visibleItems: [FavoriteItem] = []
    @State private var visibleRecentItems: [RecentItem] = []
    @State private var nodeViewModel = MapViewModel()
    @State private var observerViewModel = ObserversViewModel()
    @State private var channelViewModel = ChannelsViewModel()
    @State private var searchPackets: [Packet] = []
    @State private var isAddFavoritePresented = false
    @State private var isSearchPresented = false
    @State private var lastHandledNavigationRequestID: UUID?

    var body: some View {
        NavigationStack(path: $navigationPath) {
            ScrollViewReader { proxy in
                List {
                ExploreHeader(
                    count: visibleItems.count,
                    canReorder: canReorder,
                    search: { isSearchPresented = true },
                    addFavorite: { isAddFavoritePresented = true }
                )
                    .iPadWindowControlsClearance()
                    .favoritesListRow(top: 18, bottom: 10)

                NetworkAtAGlanceCard(
                    analyzerHost: settings.host,
                    nodes: nodeViewModel.nodes,
                    observers: observerViewModel.observers,
                    packets: searchPackets,
                    favorites: visibleItems,
                    isLoading: searchIsLoading,
                    showActiveNodes: appNavigationStore.showActiveNodes,
                    showActiveObservers: appNavigationStore.showActiveObservers,
                    showLivePackets: { navigationPath.append(ExploreDestination.livePackets) },
                    showFavorites: {
                        withAnimation {
                            proxy.scrollTo(ExploreScrollTarget.favorites, anchor: .top)
                        }
                    }
                )
                .favoritesListRow(top: 0, bottom: 8)

                DataLoadStatusView(
                    lastUpdatedAt: nodeViewModel.lastUpdatedAt,
                    errorMessage: nodeViewModel.errorMessage,
                    hasContent: !nodeViewModel.nodes.isEmpty,
                    retry: { Task { await refreshFavoriteNodes() } }
                )
                .favoritesListRow(top: 0, bottom: 14)

                if visibleItems.isEmpty && visibleRecentItems.isEmpty {
                    ExploreEmptyGuidance(
                        search: { isSearchPresented = true },
                        openMap: openMap,
                        openChannels: openChannels,
                        openObservers: openObservers
                    )
                    .favoritesListRow(top: 0, bottom: 14)
                }

                favoriteSection(kind: .channel, title: "Channels")
                favoriteSection(kind: .node, title: "Nodes")
                favoriteSection(kind: .observer, title: "Observers")
                recentSection
            }
            .scrollContentBackground(.hidden)
            .background(NodeScopeBackground())
            .listStyle(.plain)
            .adaptiveContentWidth()
            .floatingDockScrollClearance()
            .refreshable {
                await refreshExploreData()
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: MeshNode.self) { node in
                NodeDetailScreen(node: node)
            }
            .navigationDestination(for: MeshObserver.self) { observer in
                ObserverDetailScreen(observer: observer)
            }
            .navigationDestination(for: MeshChannel.self) { channel in
                ChannelDetailScreen(channel: channel)
            }
            .navigationDestination(for: ChannelMessage.self) { message in
                PacketDetailScreen(message: message)
            }
            .navigationDestination(for: ExploreDestination.self) { destination in
                switch destination {
                case .livePackets:
                    PacketFeedScreen()
                }
            }
            }
        }
        .sheet(isPresented: $isAddFavoritePresented) {
            FavoriteNodeFinder(
                nodes: nodeViewModel.nodes,
                isLoading: nodeViewModel.isLoading,
                errorMessage: nodeViewModel.errorMessage
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $isSearchPresented) {
            GlobalSearchSheet(
                nodes: nodeViewModel.nodes,
                observers: observerViewModel.observers,
                channels: channelViewModel.channels,
                packets: searchPackets,
                isLoading: searchIsLoading,
                selectNode: { navigationPath.append($0) },
                selectObserver: { navigationPath.append($0) },
                selectChannel: { navigationPath.append($0) },
                selectMessage: { navigationPath.append($0) }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .onChange(of: resetID) {
            navigationPath = NavigationPath()
        }
        .onChange(of: settings.host) { updateVisibleItems() }
        .onChange(of: favoritesStore.items) { updateVisibleItems() }
        .onChange(of: recentItemsStore.items) { updateVisibleItems() }
        .onChange(of: appNavigationStore.requestID) {
            openRequestedDeepLinkIfAvailable()
        }
        .task(id: settings.host) {
            updateVisibleItems()
            nodeViewModel.resetForAnalyzerSource()
            nodeViewModel.configure(settings: settings)
            observerViewModel.configure(settings: settings)
            channelViewModel.configure(settings: settings)
            searchPackets = []
            async let nodes: Void = nodeViewModel.loadNodes(region: nil)
            async let observers: Void = observerViewModel.loadObservers()
            async let channels: Void = channelViewModel.loadChannels(region: nil)
            async let packets: Void = loadSearchPackets()
            _ = await (nodes, observers, channels, packets)
            refreshFavoriteNodeSnapshots()
            openRequestedDeepLinkIfAvailable()
        }
        .task(id: "\(settings.host)|\(isTabActive)") {
            guard isTabActive else { return }

            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(60))
                } catch {
                    return
                }
                guard !Task.isCancelled else { return }
                await refreshFavoriteNodes()
            }
        }
    }

    @ViewBuilder
    private var recentSection: some View {
        if !visibleRecentItems.isEmpty {
            Section {
                ForEach(visibleRecentItems) { item in
                    recentRow(item)
                }
            } header: {
                HStack {
                    Text("Recently Viewed")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                    Spacer()
                    Button("Clear") {
                        recentItemsStore.clear(source: activeSource)
                    }
                    .font(.caption.weight(.semibold))
                    .textCase(nil)
                }
            }
        }
    }

    @ViewBuilder
    private func favoriteSection(kind: FavoriteKind, title: LocalizedStringKey) -> some View {
        let items = visibleItems.filter { $0.kind == kind }
        if !items.isEmpty {
            Section {
                ForEach(items) { item in
                    favoriteRow(item)
                        .id(scrollTarget(for: item))
                }
                .onMove { source, destination in
                    moveFavorites(items, kind: kind, from: source, to: destination)
                }
            } header: {
                Text(title)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
            }
        }
    }

    @ViewBuilder
    private func favoriteRow(_ item: FavoriteItem) -> some View {
        if let node = item.node {
            NavigationLink(value: node) {
                FavoriteRow(item: item)
            }
            .favoriteRowStyle(remove: { favoritesStore.remove(item) })
            .nodeQuickActions(node)
        } else if let observer = item.observer {
            NavigationLink(value: observer) {
                FavoriteRow(item: item)
            }
            .favoriteRowStyle(remove: { favoritesStore.remove(item) })
        } else if let channel = item.channel {
            NavigationLink(value: channel) {
                FavoriteRow(item: item)
            }
            .favoriteRowStyle(remove: { favoritesStore.remove(item) })
        } else {
            FavoriteRow(item: item)
                .favoriteRowStyle(remove: { favoritesStore.remove(item) })
        }
    }

    @ViewBuilder
    private func recentRow(_ item: RecentItem) -> some View {
        if let node = item.node {
            NavigationLink(value: node) {
                RecentRow(item: item)
            }
            .recentRowStyle(remove: { recentItemsStore.remove(item) })
            .nodeQuickActions(node)
        } else if let observer = item.observer {
            NavigationLink(value: observer) {
                RecentRow(item: item)
            }
            .recentRowStyle(remove: { recentItemsStore.remove(item) })
        } else if let channel = item.channel {
            NavigationLink(value: channel) {
                RecentRow(item: item)
            }
            .recentRowStyle(remove: { recentItemsStore.remove(item) })
        } else if let message = item.message {
            NavigationLink(value: message) {
                RecentRow(item: item)
            }
            .recentRowStyle(remove: { recentItemsStore.remove(item) })
        }
    }

    private func openRequestedDeepLinkIfAvailable() {
        guard lastHandledNavigationRequestID != appNavigationStore.requestID else { return }

        switch appNavigationStore.destination {
        case .node(let publicKey):
            guard let node = nodeViewModel.nodes.first(where: {
                $0.publicKey.caseInsensitiveCompare(publicKey) == .orderedSame
            }) else { return }
            navigationPath = NavigationPath()
            navigationPath.append(node)
        case .channel(let hash):
            guard let channel = channelViewModel.channels.first(where: {
                $0.hash.caseInsensitiveCompare(hash) == .orderedSame
            }) else { return }
            navigationPath = NavigationPath()
            navigationPath.append(channel)
        case .packet(let hash):
            guard let packet = searchPackets.first(where: {
                $0.hash.caseInsensitiveCompare(hash) == .orderedSame
            }) else { return }
            navigationPath = NavigationPath()
            navigationPath.append(deepLinkMessage(for: packet))
        default:
            return
        }

        lastHandledNavigationRequestID = appNavigationStore.requestID
    }

    private func deepLinkMessage(for packet: Packet) -> ChannelMessage {
        ChannelMessage(
            sender: packet.observerName ?? "Unknown",
            text: "",
            timestamp: packet.firstSeen,
            senderTimestamp: nil,
            packetId: packet.id,
            packetHash: packet.hash,
            repeats: packet.observationCount,
            observers: packet.observerName.map { [$0] } ?? [],
            hops: 0,
            snr: packet.snr
        )
    }

    private func refreshExploreData() async {
        async let nodes: Void = nodeViewModel.loadNodes(region: nil, forceRefresh: true)
        async let observers: Void = observerViewModel.loadObservers()
        async let channels: Void = channelViewModel.loadChannels(region: nil, forceRefresh: true)
        async let packets: Void = loadSearchPackets()
        _ = await (nodes, observers, channels, packets)
        refreshFavoriteNodeSnapshots()
    }

    private func refreshFavoriteNodes() async {
        await nodeViewModel.loadNodes(region: nil, forceRefresh: true)
        refreshFavoriteNodeSnapshots()
    }

    private func refreshFavoriteNodeSnapshots() {
        favoritesStore.refreshNodes(nodeViewModel.nodes, source: activeSource)
    }

    private func updateVisibleItems() {
        let source = AnalyzerSettings.normalizedHost(settings.host)
        visibleItems = favoritesStore.items
            .filter { $0.source == source }
        visibleRecentItems = Array(
            recentItemsStore.items
                .lazy
                .filter { $0.source == source }
                .prefix(10)
        )
    }

    private var firstFavoriteID: String? {
        for kind in [FavoriteKind.channel, .node, .observer] {
            if let item = visibleItems.first(where: { $0.kind == kind }) {
                return item.id
            }
        }
        return nil
    }

    private func scrollTarget(for item: FavoriteItem) -> ExploreScrollTarget {
        item.id == firstFavoriteID ? .favorites : .favorite(item.id)
    }

    private var activeSource: String {
        AnalyzerSettings.normalizedHost(settings.host)
    }

    private var searchIsLoading: Bool {
        nodeViewModel.isLoading || observerViewModel.isLoading || channelViewModel.isLoading
    }

    private func loadSearchPackets() async {
        let client = APIClient(settings: settings)
        let cacheKey = "explore-search-packets-\(client.cacheIdentifier)"
        if let cached: PacketsResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 7 * 24 * 60 * 60
        ) {
            searchPackets = cached.packets
        }
        do {
            let response: PacketsResponse = try await APIResponseCache.shared.refresh(for: cacheKey) {
                try await client.get(
                    "/api/packets",
                    query: [URLQueryItem(name: "limit", value: "1000")]
                )
            }
            searchPackets = response.packets
        } catch {
            // Keep the cached packet index searchable while the analyzer is unavailable.
        }
    }

    private var canReorder: Bool {
        FavoriteKind.allCases.contains { kind in
            visibleItems.lazy.filter { $0.kind == kind }.dropFirst().isEmpty == false
        }
    }

    private func moveFavorites(
        _ items: [FavoriteItem],
        kind: FavoriteKind,
        from source: IndexSet,
        to destination: Int
    ) {
        var reorderedItems = items
        reorderedItems.move(fromOffsets: source, toOffset: destination)
        favoritesStore.reorder(
            reorderedItems,
            kind: kind,
            source: AnalyzerSettings.normalizedHost(settings.host)
        )
    }
}

private enum ExploreDestination: Hashable {
    case livePackets
}

private enum ExploreScrollTarget: Hashable {
    case favorites
    case favorite(String)
}

private struct ExploreEmptyGuidance: View {
    let search: () -> Void
    let openMap: () -> Void
    let openChannels: () -> Void
    let openObservers: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("Explore Your Mesh", systemImage: "safari")
        } description: {
            Text("Search for nodes, observers, and channels, then save the ones you want to follow.")
        } actions: {
            Button("Search the Network", action: search)
                .buttonStyle(.borderedProminent)
            Button("Open Map", action: openMap)
            Button("Browse Channels", action: openChannels)
            Button("Browse Observers", action: openObservers)
        }
        .padding(.vertical, 12)
    }
}

private struct NetworkAtAGlanceCard: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let analyzerHost: String
    let nodes: [MeshNode]
    let observers: [MeshObserver]
    let packets: [Packet]
    let favorites: [FavoriteItem]
    let isLoading: Bool
    let showActiveNodes: () -> Void
    let showActiveObservers: () -> Void
    let showLivePackets: () -> Void
    let showFavorites: () -> Void
    @State private var metricOrder = GlanceMetricKind.allCases
    @State private var hiddenMetrics: Set<GlanceMetricKind> = []
    @State private var isCustomizationPresented = false
    @State private var isExporting = false
    @State private var exportDocument = TextExportDocument()
    @State private var exportContentType = UTType.json
    @State private var exportFilename = "network-summary"

    private let activeInterval: TimeInterval = 15 * 60
    private let recentPacketInterval: TimeInterval = 60 * 60

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 5) {
                    Label("Network at a Glance", systemImage: "gauge.with.dots.needle.50percent")
                        .font(.headline)
                    Label("Entire network", systemImage: "globe.americas.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(.thinMaterial, in: Capsule())
                }
                Spacer()
                if isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .accessibilityLabel("Updating network summary")
                }
                Menu {
                    Button {
                        isCustomizationPresented = true
                    } label: {
                        Label("Customize Dashboard", systemImage: "slider.horizontal.3")
                    }
                    Button {
                        prepareExport(.csv)
                    } label: {
                        Label("Export CSV", systemImage: "tablecells")
                    }
                    Button {
                        prepareExport(.json)
                    } label: {
                        Label("Export JSON", systemImage: "curlybraces")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .foregroundStyle(NodeScopeStyle.signal)
                .accessibilityLabel("Network summary actions")
            }

            LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                ForEach(visibleMetrics) { metric in
                    GlanceMetricSlot(
                        metric: metric,
                        activeNodeCount: activeNodeCount,
                        nodeCount: nodes.count,
                        activeObserverCount: activeObserverCount,
                        observerCount: observers.count,
                        recentPacketValue: recentPacketValue,
                        averageSNR: averageSNR,
                        showActiveNodes: showActiveNodes,
                        showActiveObservers: showActiveObservers,
                        showLivePackets: showLivePackets
                    )
                }
            }

            Divider()

            Button(action: showFavorites) {
                HStack(spacing: 10) {
                    Image(systemName: "star.fill")
                        .foregroundStyle(.yellow)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(favoriteSummary)
                            .font(.subheadline.weight(.semibold))
                        Text(favoriteBreakdown)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .buttonStyle(.plain)
            .accessibilityHint("Scrolls to your saved items")
        }
        .padding(16)
        .instrumentCard()
        .accessibilityElement(children: .contain)
        .task(id: analyzerHost) { loadPreferences() }
        .sheet(isPresented: $isCustomizationPresented) {
            GlanceCustomizationSheet(
                metricOrder: $metricOrder,
                hiddenMetrics: $hiddenMetrics,
                save: savePreferences
            )
        }
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: exportContentType,
            defaultFilename: exportFilename
        ) { _ in }
    }

    private var columns: [GridItem] {
        if dynamicTypeSize.isAccessibilitySize {
            [GridItem(.flexible())]
        } else {
            [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
        }
    }

    private var visibleMetrics: [GlanceMetricKind] {
        metricOrder.filter { !hiddenMetrics.contains($0) }
    }

    private func loadPreferences() {
        let preferences = GlancePreferences.load(for: analyzerHost)
        metricOrder = preferences.order
        hiddenMetrics = preferences.hidden
    }

    private func savePreferences() {
        GlancePreferences(order: metricOrder, hidden: hiddenMetrics).save(for: analyzerHost)
    }

    private func prepareExport(_ format: StatisticsExportFormat) {
        let data: Data
        switch format {
        case .csv:
            var rows = [["metric", "value", "detail"]]
            rows += visibleMetrics.map { [$0.rawValue, metricValue(for: $0), metricDetail(for: $0)] }
            rows.append(["favorites", "\(activeFavoriteCount)", favoriteBreakdown])
            data = StatisticsExportBuilder.csv(rows: rows)
        case .json:
            let metrics = visibleMetrics.map {
                GlanceMetricExport(id: $0.rawValue, value: metricValue(for: $0), detail: metricDetail(for: $0))
            }
            data = StatisticsExportBuilder.jsonData(
                NetworkGlanceExport(
                    analyzer: AnalyzerSettings.normalizedHost(analyzerHost),
                    exportedAt: .now,
                    scope: "entire_network",
                    metrics: metrics,
                    activeFavorites: activeFavoriteCount,
                    favoriteCount: favorites.count
                )
            )
        }

        exportDocument = TextExportDocument(data: data)
        exportContentType = format.contentType
        exportFilename = "network-summary"
        isExporting = true
    }

    private func metricValue(for metric: GlanceMetricKind) -> String {
        switch metric {
        case .activeNodes: "\(activeNodeCount)"
        case .observersOnline: "\(activeObserverCount)"
        case .packets: recentPacketValue
        case .averageSNR: averageSNR.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? ""
        }
    }

    private func metricDetail(for metric: GlanceMetricKind) -> String {
        switch metric {
        case .activeNodes: "of \(nodes.count) seen"
        case .observersOnline: "of \(observers.count) known"
        case .packets: "in the last hour"
        case .averageSNR: averageSNR == nil ? "no recent samples" : "dB in the last hour"
        }
    }

    private var activeNodeCount: Int {
        nodes.count { isActive($0.lastSeen) }
    }

    private var activeObserverCount: Int {
        observers.count { isActive($0.lastSeen) }
    }

    private var recentPackets: [Packet] {
        packets.filter { $0.timestamp.timeIntervalSinceNow > -recentPacketInterval }
    }

    private var recentPacketValue: String {
        let count = recentPackets.count
        return count == packets.count && packets.count == 1_000 ? "\(count)+" : "\(count)"
    }

    private var averageSNR: Double? {
        let samples = recentPackets.compactMap(\.snr)
        guard !samples.isEmpty else { return nil }
        return samples.reduce(0, +) / Double(samples.count)
    }

    private var activeFavoriteCount: Int {
        let activeNodeKeys = Set(nodes.filter { isActive($0.lastSeen) }.map(\.publicKey))
        let activeObserverIDs = Set(observers.filter { isActive($0.lastSeen) }.map(\.id))
        return favorites.count { item in
            if let node = item.node {
                return activeNodeKeys.contains(node.publicKey)
            }
            if let observer = item.observer {
                return activeObserverIDs.contains(observer.id)
            }
            return false
        }
    }

    private var favoriteSummary: String {
        if favorites.isEmpty {
            return "No favorites saved yet"
        }
        return "\(activeFavoriteCount) active favorite\(activeFavoriteCount == 1 ? "" : "s")"
    }

    private var favoriteBreakdown: String {
        let channels = favorites.count { $0.kind == .channel }
        let nodes = favorites.count { $0.kind == .node }
        let observers = favorites.count { $0.kind == .observer }
        let channelLabel = channels == 1 ? String(localized: "channel") : String(localized: "channels")
        let nodeLabel = nodes == 1 ? String(localized: "node") : String(localized: "nodes")
        let observerLabel = observers == 1 ? String(localized: "observer") : String(localized: "observers")
        return "\(channels) \(channelLabel) · \(nodes) \(nodeLabel) · \(observers) \(observerLabel)"
    }

    private func isActive(_ date: Date) -> Bool {
        date.timeIntervalSinceNow > -activeInterval
    }
}

private struct NetworkGlanceExport: Encodable {
    let analyzer: String
    let exportedAt: Date
    let scope: String
    let metrics: [GlanceMetricExport]
    let activeFavorites: Int
    let favoriteCount: Int
}

private struct GlanceMetricExport: Encodable {
    let id: String
    let value: String
    let detail: String
}

private enum GlanceMetricKind: String, Codable, CaseIterable, Identifiable {
    case activeNodes
    case observersOnline
    case packets
    case averageSNR

    var id: String { rawValue }

    var title: LocalizedStringResource {
        switch self {
        case .activeNodes: "Active nodes"
        case .observersOnline: "Observers online"
        case .packets: "Packets"
        case .averageSNR: "Average SNR"
        }
    }

    var symbol: String {
        switch self {
        case .activeNodes: "point.3.connected.trianglepath.dotted"
        case .observersOnline: "antenna.radiowaves.left.and.right"
        case .packets: "waveform.path.ecg"
        case .averageSNR: "waveform"
        }
    }
}

private struct GlancePreferences: Codable {
    var order: [GlanceMetricKind]
    var hidden: Set<GlanceMetricKind>

    static func load(for analyzerHost: String) -> GlancePreferences {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey(for: analyzerHost)),
              let decoded = try? JSONDecoder().decode(GlancePreferences.self, from: data) else {
            return GlancePreferences(order: GlanceMetricKind.allCases, hidden: [])
        }

        let knownOrder = decoded.order.filter { GlanceMetricKind.allCases.contains($0) }
        let missing = GlanceMetricKind.allCases.filter { !knownOrder.contains($0) }
        return GlancePreferences(order: knownOrder + missing, hidden: decoded.hidden)
    }

    func save(for analyzerHost: String) {
        guard let data = try? JSONEncoder().encode(self) else { return }
        UserDefaults.standard.set(data, forKey: Self.defaultsKey(for: analyzerHost))
    }

    private static func defaultsKey(for analyzerHost: String) -> String {
        "glance-preferences-\(AnalyzerSettings.normalizedHost(analyzerHost))"
    }
}

private struct GlanceMetricSlot: View {
    let metric: GlanceMetricKind
    let activeNodeCount: Int
    let nodeCount: Int
    let activeObserverCount: Int
    let observerCount: Int
    let recentPacketValue: String
    let averageSNR: Double?
    let showActiveNodes: () -> Void
    let showActiveObservers: () -> Void
    let showLivePackets: () -> Void

    var body: some View {
        VStack {
            switch metric {
            case .activeNodes:
                GlanceMetric(
                    value: "\(activeNodeCount)", label: "Active nodes",
                    detail: "of \(nodeCount) seen", color: .green, action: showActiveNodes
                )
            case .observersOnline:
                GlanceMetric(
                    value: "\(activeObserverCount)", label: "Observers online",
                    detail: "of \(observerCount) known", color: NodeScopeStyle.signal,
                    action: showActiveObservers
                )
            case .packets:
                GlanceMetric(
                    value: recentPacketValue, label: "Packets", detail: "in the last hour",
                    color: .orange, action: showLivePackets
                )
            case .averageSNR:
                GlanceMetric(
                    value: averageSNR.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? "—",
                    label: "Average SNR",
                    detail: averageSNR == nil ? "no recent samples" : "dB in the last hour",
                    color: .purple, action: nil
                )
            }
        }
    }
}

private struct GlanceCustomizationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var metricOrder: [GlanceMetricKind]
    @Binding var hiddenMetrics: Set<GlanceMetricKind>
    let save: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(metricOrder) { metric in
                        Toggle(isOn: visibilityBinding(for: metric)) {
                            Label(metric.title, systemImage: metric.symbol)
                        }
                        .disabled(!hiddenMetrics.contains(metric) && visibleCount == 1)
                    }
                    .onMove(perform: moveMetrics)
                } header: {
                    Text("Dashboard Metrics")
                } footer: {
                    Text("Drag metrics into your preferred order and hide the ones you don’t need.")
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Customize Dashboard")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Reset") {
                        metricOrder = GlanceMetricKind.allCases
                        hiddenMetrics = []
                        save()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        save()
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private var visibleCount: Int {
        metricOrder.count - hiddenMetrics.count
    }

    private func visibilityBinding(for metric: GlanceMetricKind) -> Binding<Bool> {
        Binding(
            get: { !hiddenMetrics.contains(metric) },
            set: { isVisible in
                if isVisible {
                    hiddenMetrics.remove(metric)
                } else if visibleCount > 1 {
                    hiddenMetrics.insert(metric)
                }
                save()
            }
        )
    }

    private func moveMetrics(from source: IndexSet, to destination: Int) {
        metricOrder.move(fromOffsets: source, toOffset: destination)
        save()
    }
}

private struct GlanceMetric: View {
    let value: String
    let label: LocalizedStringKey
    let detail: String
    let color: Color
    let action: (() -> Void)?

    var body: some View {
        if let action {
            Button(action: action) { content }
                .buttonStyle(GlanceMetricButtonStyle())
                .accessibilityHint("Opens related details")
        } else {
            content
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(value)
                .font(.title2.bold())
                .foregroundStyle(color)
                .contentTransition(.numericText())
            HStack(spacing: 4) {
                Text(label)
                    .font(.caption.weight(.semibold))
                if action != nil {
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.tertiary)
                }
            }
            Text(detail)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            action == nil ? Color.clear : color.opacity(0.07),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct GlanceMetricButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.72 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private struct ExploreHeader: View {
    let count: Int
    let canReorder: Bool
    let search: () -> Void
    let addFavorite: () -> Void

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 5) {
                Text("Explore")
                    .font(.largeTitle.bold())
                Text("\(count) saved item\(count == 1 ? "" : "s") on this analyzer")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button(action: search) {
                Image(systemName: "magnifyingglass")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(NodeScopeStyle.signal)
                    .frame(width: 40, height: 40)
                    .nodeScopeFloatingGlass(cornerRadius: 20)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Search the network")

            if canReorder {
                EditButton()
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(NodeScopeStyle.signal)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 40)
                    .nodeScopeFloatingGlass(cornerRadius: 20)
                    .accessibilityHint("Reorder favorites within each section")
            }

            Button(action: addFavorite) {
                Image(systemName: "plus")
                    .font(.body.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(NodeScopeStyle.signal, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Add favorite node")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct FavoriteRow: View {
    let item: FavoriteItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(item.node == nil ? NodeScopeStyle.signal : .white)
                .frame(width: 40, height: 40)
                .background(symbolColor, in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.headline)
                if let subtitle = item.subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer()
        }
        .padding(12)
        .instrumentCard()
    }

    private var symbol: String {
        if let node = item.node {
            return NodeRoleStyle.symbolName(for: node.role)
        }
        return switch item.kind {
        case .node: "point.3.connected.trianglepath.dotted"
        case .observer: "antenna.radiowaves.left.and.right"
        case .channel: "number"
        }
    }

    private var symbolColor: Color {
        if let node = item.node {
            return NodeRoleStyle.color(for: node.role)
        }
        return NodeScopeStyle.signal.opacity(0.13)
    }
}

private struct RecentRow: View {
    let item: RecentItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(item.node == nil ? symbolColor : .white)
                .frame(width: 38, height: 38)
                .background(backgroundColor, in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                HStack(spacing: 6) {
                    if let subtitle = item.subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .lineLimit(1)
                        Text("·")
                    }
                    Text(item.viewedAt, style: .relative)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding(12)
        .instrumentCard()
        .contentShape(Rectangle())
    }

    private var symbol: String {
        if let node = item.node {
            return NodeRoleStyle.symbolName(for: node.role)
        }
        return switch item.kind {
        case .node: "point.3.connected.trianglepath.dotted"
        case .observer: "antenna.radiowaves.left.and.right"
        case .channel: "number"
        case .packet: "waveform.path.ecg.rectangle.fill"
        }
    }

    private var symbolColor: Color {
        switch item.kind {
        case .node: NodeScopeStyle.signal
        case .observer: NodeScopeStyle.healthy
        case .channel: NodeScopeStyle.signal
        case .packet: NodeScopeStyle.activity
        }
    }

    private var backgroundColor: Color {
        if let node = item.node {
            return NodeRoleStyle.color(for: node.role)
        }
        return symbolColor.opacity(0.13)
    }
}

private struct FavoriteNodeFinder: View {
    let nodes: [MeshNode]
    let isLoading: Bool
    let errorMessage: String?

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favoritesStore
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [MeshNode] = []
    @State private var selectedRoles: Set<String> = []

    private let roles = ["repeater", "room", "companion", "sensor"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                InstrumentSearchField(text: $query, prompt: "Name, public key, or role")
                    .padding(.horizontal, 16)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        FavoriteRoleFilterChip(
                            title: "All",
                            symbol: "circle.grid.2x2.fill",
                            color: NodeScopeStyle.signal,
                            isSelected: selectedRoles.isEmpty,
                            action: { selectedRoles = [] }
                        )
                        ForEach(roles, id: \.self) { role in
                            FavoriteRoleFilterChip(
                                title: role.capitalized,
                                symbol: NodeRoleStyle.symbolName(for: role),
                                color: NodeRoleStyle.color(for: role),
                                isSelected: selectedRoles.contains(role),
                                action: { toggleRole(role) }
                            )
                        }
                    }
                    .padding(.horizontal, 16)
                }

                if isLoading && nodes.isEmpty {
                    LoadingIndicator(title: "Loading nodes…")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                } else if let errorMessage, nodes.isEmpty {
                    ContentUnavailableView(
                        "Couldn't Load Nodes",
                        systemImage: "wifi.exclamationmark",
                        description: Text(errorMessage)
                    )
                } else if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && selectedRoles.isEmpty {
                    ContentUnavailableView(
                        "Find a Node",
                        systemImage: "magnifyingglass",
                        description: Text("Search by node name, public key, or role.")
                    )
                } else if results.isEmpty {
                    ContentUnavailableView.search(text: query)
                } else {
                    List(results) { node in
                        FavoriteNodeSearchRow(
                            node: node,
                            isFavorite: favoritesStore.contains(
                                kind: .node,
                                entityID: node.publicKey,
                                source: favoriteSource
                            ),
                            toggleFavorite: {
                                favoritesStore.toggle(node: node, source: favoriteSource)
                            }
                        )
                    }
                    .listStyle(.plain)
                }
            }
            .padding(.top, 8)
            .navigationTitle("Add Favorite Node")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear(perform: updateResults)
            .onChange(of: query) { updateResults() }
            .onChange(of: nodes) { updateResults() }
            .onChange(of: selectedRoles) { updateResults() }
        }
    }

    private var favoriteSource: String {
        AnalyzerSettings.normalizedHost(settings.host)
    }

    private func updateResults() {
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedQuery.isEmpty || !selectedRoles.isEmpty else {
            results = []
            return
        }

        results = nodes.filter { node in
            let matchesQuery = normalizedQuery.isEmpty
                || node.name?.localizedCaseInsensitiveContains(normalizedQuery) == true
                || node.publicKey.localizedCaseInsensitiveContains(normalizedQuery)
                || node.role.localizedCaseInsensitiveContains(normalizedQuery)
            let matchesRole = selectedRoles.isEmpty || selectedRoles.contains(node.role)
            return matchesQuery && matchesRole
        }
        .sorted { lhs, rhs in
            let lhsName = lhs.name ?? lhs.publicKey
            let rhsName = rhs.name ?? rhs.publicKey
            return lhsName.localizedCaseInsensitiveCompare(rhsName) == .orderedAscending
        }
    }

    private func toggleRole(_ role: String) {
        if selectedRoles.contains(role) {
            selectedRoles.remove(role)
        } else {
            selectedRoles.insert(role)
        }
    }
}

private struct FavoriteRoleFilterChip: View {
    let title: String
    let symbol: String
    let color: Color
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.caption.weight(.semibold))
                .foregroundStyle(isSelected ? .white : color)
                .padding(.horizontal, 11)
                .padding(.vertical, 8)
                .background(isSelected ? color : color.opacity(0.11), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

private struct FavoriteNodeSearchRow: View {
    let node: MeshNode
    let isFavorite: Bool
    let toggleFavorite: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: NodeRoleStyle.symbolName(for: node.role))
                .font(.body.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(NodeRoleStyle.color(for: node.role), in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(node.name ?? "Unnamed Node")
                    .font(.headline)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(node.role.capitalized)
                    Text("·")
                    Text(node.publicKey.prefix(10).uppercased())
                        .font(.caption.monospaced())
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            Button(action: toggleFavorite) {
                Image(systemName: isFavorite ? "star.fill" : "star")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(NodeScopeStyle.signal)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isFavorite ? "Remove node from favorites" : "Add node to favorites")
        }
        .contentShape(Rectangle())
        .padding(.vertical, 4)
    }
}

private struct GlobalSearchSheet: View {
    let nodes: [MeshNode]
    let observers: [MeshObserver]
    let channels: [MeshChannel]
    let packets: [Packet]
    let isLoading: Bool
    let selectNode: (MeshNode) -> Void
    let selectObserver: (MeshObserver) -> Void
    let selectChannel: (MeshChannel) -> Void
    let selectMessage: (ChannelMessage) -> Void

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(ChannelMonitorStore.self) private var monitorStore
    @Environment(FavoritesStore.self) private var favoritesStore
    @Environment(SearchHistoryStore.self) private var searchHistoryStore
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var nodeResults: [MeshNode] = []
    @State private var observerResults: [MeshObserver] = []
    @State private var channelResults: [MeshChannel] = []
    @State private var packetResults: [Packet] = []

    var body: some View {
        NavigationStack {
            VStack(spacing: 10) {
                InstrumentSearchField(text: $query, prompt: "Search the network")
                    .padding(.horizontal, 16)
                    .onSubmit { recordSearch() }

                if normalizedQuery.isEmpty {
                    recentSearches
                } else if !hasResults && isLoading {
                    LoadingIndicator(title: "Indexing network…")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                } else if !hasResults {
                    ContentUnavailableView.search(text: normalizedQuery)
                } else {
                    resultsList
                }
            }
            .padding(.top, 8)
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear(perform: updateResults)
            .onChange(of: query) { updateResults() }
            .onChange(of: nodes) { updateResults() }
            .onChange(of: observers) { updateResults() }
            .onChange(of: channels) { updateResults() }
            .onChange(of: packets) { updateResults() }
        }
    }

    @ViewBuilder
    private var recentSearches: some View {
        let items = searchHistoryStore.items.filter { $0.source == favoriteSource }
        if items.isEmpty {
            ContentUnavailableView(
                "Search the Network",
                systemImage: "magnifyingglass",
                description: Text("Find nodes, observers, channels, public keys, and packet hashes.")
            )
        } else {
            List {
                Section {
                    ForEach(items) { item in
                        Button {
                            query = item.query
                        } label: {
                            Label(item.query, systemImage: "clock.arrow.circlepath")
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                searchHistoryStore.remove(item)
                            } label: {
                                Label("Remove", systemImage: "trash")
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text("Recent Searches")
                        Spacer()
                        Button("Clear") {
                            searchHistoryStore.clear(source: favoriteSource)
                        }
                        .textCase(nil)
                    }
                }
            }
            .listStyle(.plain)
        }
    }

    private var resultsList: some View {
        List {
            if !channelResults.isEmpty {
                Section("Channels") {
                    ForEach(channelResults) { channel in
                        searchResultRow(
                            title: channel.name,
                            subtitle: channel.lastSender ?? channel.lastMessage,
                            symbol: "number",
                            color: NodeScopeStyle.signal,
                            isFavorite: isFavorite(kind: .channel, id: channel.id),
                            select: { select(channel) },
                            toggleFavorite: { favoritesStore.toggle(channel: channel, source: favoriteSource) }
                        )
                    }
                }
            }

            if !nodeResults.isEmpty {
                Section("Nodes") {
                    ForEach(nodeResults) { node in
                        searchResultRow(
                            title: node.name ?? "Unnamed Node",
                            subtitle: "\(node.role.capitalized) · \(node.publicKey.prefix(12).uppercased())",
                            symbol: NodeRoleStyle.symbolName(for: node.role),
                            color: NodeRoleStyle.color(for: node.role),
                            isFavorite: isFavorite(kind: .node, id: node.publicKey),
                            select: { select(node) },
                            toggleFavorite: { favoritesStore.toggle(node: node, source: favoriteSource) }
                        )
                        .nodeQuickActions(node)
                    }
                }
            }

            if !observerResults.isEmpty {
                Section("Observers") {
                    ForEach(observerResults) { observer in
                        searchResultRow(
                            title: observer.name ?? "Unnamed Observer",
                            subtitle: [observer.iata, observer.model, observer.id].compactMap { $0 }.joined(separator: " · "),
                            symbol: "antenna.radiowaves.left.and.right",
                            color: NodeScopeStyle.healthy,
                            isFavorite: isFavorite(kind: .observer, id: observer.id),
                            select: { select(observer) },
                            toggleFavorite: { favoritesStore.toggle(observer: observer, source: favoriteSource) }
                        )
                    }
                }
            }

            if !packetResults.isEmpty {
                Section("Packets") {
                    ForEach(packetResults) { packet in
                        searchResultRow(
                            title: packet.payloadTypeName,
                            subtitle: packet.hash,
                            symbol: "waveform.path.ecg.rectangle.fill",
                            color: NodeScopeStyle.activity,
                            isFavorite: nil,
                            select: { select(message(for: packet)) },
                            toggleFavorite: nil
                        )
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    private var normalizedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var favoriteSource: String {
        AnalyzerSettings.normalizedHost(settings.host)
    }

    private var hasResults: Bool {
        !nodeResults.isEmpty || !observerResults.isEmpty || !channelResults.isEmpty || !packetResults.isEmpty
    }

    private func updateResults() {
        let query = normalizedQuery
        guard !query.isEmpty else {
            nodeResults = []
            observerResults = []
            channelResults = []
            packetResults = []
            return
        }

        nodeResults = Array(nodes.lazy.filter { node in
            node.name?.localizedCaseInsensitiveContains(query) == true
                || node.publicKey.localizedCaseInsensitiveContains(query)
                || node.role.localizedCaseInsensitiveContains(query)
        }.prefix(20))

        observerResults = Array(observers.lazy.filter { observer in
            observer.name?.localizedCaseInsensitiveContains(query) == true
                || observer.id.localizedCaseInsensitiveContains(query)
                || observer.iata?.localizedCaseInsensitiveContains(query) == true
                || observer.model?.localizedCaseInsensitiveContains(query) == true
        }.prefix(20))

        let monitoredChannels = monitorStore.channels.map { monitoredChannel in
            MeshChannel(
                hash: "user:\(monitoredChannel.channelName)",
                name: monitoredChannel.title,
                lastMessage: monitoredChannel.lastMessage ?? "Monitored locally",
                lastSender: nil,
                messageCount: monitoredChannel.messageCount ?? 0,
                lastActivity: monitoredChannel.lastActivity ?? .distantPast
            )
        }
        let allChannels = monitoredChannels + channels.filter { channel in
            !monitorStore.channels.contains { $0.channelName == channel.name }
        }
        channelResults = Array(allChannels.lazy.filter { channel in
            channel.name.localizedCaseInsensitiveContains(query)
                || channel.lastMessage?.localizedCaseInsensitiveContains(query) == true
                || channel.lastSender?.localizedCaseInsensitiveContains(query) == true
                || channel.hash.localizedCaseInsensitiveContains(query)
        }.prefix(20))

        packetResults = Array(packets.lazy.filter { packet in
            packet.hash.localizedCaseInsensitiveContains(query)
                || packet.payloadTypeName.localizedCaseInsensitiveContains(query)
                || packet.observerName?.localizedCaseInsensitiveContains(query) == true
        }.prefix(20))
    }

    private func recordSearch() {
        searchHistoryStore.record(normalizedQuery, source: favoriteSource)
    }

    private func isFavorite(kind: FavoriteKind, id: String) -> Bool {
        favoritesStore.contains(kind: kind, entityID: id, source: favoriteSource)
    }

    private func select(_ node: MeshNode) {
        recordSearch()
        dismiss()
        selectNode(node)
    }

    private func select(_ observer: MeshObserver) {
        recordSearch()
        dismiss()
        selectObserver(observer)
    }

    private func select(_ channel: MeshChannel) {
        recordSearch()
        dismiss()
        selectChannel(channel)
    }

    private func select(_ message: ChannelMessage) {
        recordSearch()
        dismiss()
        selectMessage(message)
    }

    private func message(for packet: Packet) -> ChannelMessage {
        ChannelMessage(
            sender: packet.observerName ?? "Unknown",
            text: "",
            timestamp: packet.firstSeen,
            senderTimestamp: nil,
            packetId: packet.id,
            packetHash: packet.hash,
            repeats: packet.observationCount,
            observers: packet.observerName.map { [$0] } ?? [],
            hops: 0,
            snr: packet.snr
        )
    }

    private func searchResultRow(
        title: String,
        subtitle: String?,
        symbol: String,
        color: Color,
        isFavorite: Bool?,
        select: @escaping () -> Void,
        toggleFavorite: (() -> Void)?
    ) -> some View {
        HStack(spacing: 10) {
            Button(action: select) {
                HStack(spacing: 12) {
                    Image(systemName: symbol)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 38, height: 38)
                        .background(color, in: Circle())
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title)
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        if let subtitle, !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if let isFavorite, let toggleFavorite {
                Button(action: toggleFavorite) {
                    Image(systemName: isFavorite ? "star.fill" : "star")
                        .foregroundStyle(NodeScopeStyle.signal)
                        .frame(width: 40, height: 40)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isFavorite ? "Remove from favorites" : "Add to favorites")
            }
        }
    }
}

private extension View {
    func favoritesListRow(top: CGFloat = 6, bottom: CGFloat = 6) -> some View {
        listRowInsets(EdgeInsets(top: top, leading: 20, bottom: bottom, trailing: 20))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }

    func favoriteRowStyle(remove: @escaping () -> Void) -> some View {
        buttonStyle(.plain)
            .favoritesListRow()
            .swipeActions {
                Button(role: .destructive, action: remove) {
                    Label("Remove Favorite", systemImage: "star.slash")
                }
            }
    }

    func recentRowStyle(remove: @escaping () -> Void) -> some View {
        buttonStyle(.plain)
            .favoritesListRow(top: 5, bottom: 5)
            .swipeActions {
                Button(role: .destructive, action: remove) {
                    Label("Remove from Recent", systemImage: "clock.badge.xmark")
                }
            }
    }

    func nodeQuickActions(_ node: MeshNode) -> some View {
        modifier(NodeQuickActionsModifier(node: node))
    }
}

private struct NodeQuickActionsModifier: ViewModifier {
    let node: MeshNode

    @Environment(AppNavigationStore.self) private var appNavigationStore
    @Environment(\.dismiss) private var dismiss

    func body(content: Content) -> some View {
        content
            .contextMenu {
                Button {
                    showOnMap()
                } label: {
                    Label("Show on Map", systemImage: "map")
                }
                .disabled(!canShowOnMap)

                Button {
                    copyPublicKey()
                } label: {
                    Label("Copy Public Key", systemImage: "doc.on.doc")
                }
            }
            .accessibilityAction(named: "Show on Map") {
                guard canShowOnMap else { return }
                showOnMap()
            }
            .accessibilityAction(named: "Copy Public Key") {
                copyPublicKey()
            }
    }

    private func showOnMap() {
        dismiss()
        appNavigationStore.showOnMap(node)
    }

    private func copyPublicKey() {
        UIPasteboard.general.string = node.publicKey
    }

    private var canShowOnMap: Bool {
        guard let coordinate = node.coordinate else { return false }
        return coordinate.latitude != 0 || coordinate.longitude != 0
    }
}
