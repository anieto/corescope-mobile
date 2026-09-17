import SwiftUI
import UIKit

struct ExploreScreen: View {
    let resetID: UUID
    let openMap: () -> Void
    let openChannels: () -> Void
    let openObservers: () -> Void

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favoritesStore
    @Environment(RecentItemsStore.self) private var recentItemsStore
    @State private var navigationPath = NavigationPath()
    @State private var visibleItems: [FavoriteItem] = []
    @State private var visibleRecentItems: [RecentItem] = []
    @State private var nodeViewModel = MapViewModel()
    @State private var observerViewModel = ObserversViewModel()
    @State private var channelViewModel = ChannelsViewModel()
    @State private var searchPackets: [Packet] = []
    @State private var isAddFavoritePresented = false
    @State private var isSearchPresented = false

    var body: some View {
        NavigationStack(path: $navigationPath) {
            List {
                ExploreHeader(
                    count: visibleItems.count,
                    canReorder: canReorder,
                    search: { isSearchPresented = true },
                    addFavorite: { isAddFavoritePresented = true }
                )
                    .iPadWindowControlsClearance()
                    .favoritesListRow(top: 18, bottom: 10)

                favoriteSection(kind: .channel, title: "Channels")
                favoriteSection(kind: .node, title: "Nodes")
                favoriteSection(kind: .observer, title: "Observers")
                recentSection
            }
            .scrollContentBackground(.hidden)
            .background(NodeScopeBackground())
            .listStyle(.plain)
            .adaptiveContentWidth()
            .contentMargins(.bottom, 104, for: .scrollContent)
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
            .overlay {
                if visibleItems.isEmpty && visibleRecentItems.isEmpty {
                    ContentUnavailableView {
                        Label("Explore Your Mesh", systemImage: "safari")
                    } description: {
                        Text("Search for nodes, observers, and channels, then save the ones you want to follow.")
                    } actions: {
                        Button("Search the Network") {
                            isSearchPresented = true
                        }
                        .buttonStyle(.borderedProminent)
                        Button("Open Map", action: openMap)
                        Button("Browse Channels", action: openChannels)
                        Button("Browse Observers", action: openObservers)
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

    private func updateVisibleItems() {
        let source = AnalyzerSettings.normalizedHost(settings.host)
        visibleItems = favoritesStore.items
            .filter { $0.source == source }
        visibleRecentItems = recentItemsStore.items
            .filter { $0.source == source }
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
                    .background(.thinMaterial, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Search the network")

            if canReorder {
                EditButton()
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(NodeScopeStyle.signal)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 40)
                    .background(.thinMaterial, in: Capsule())
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
        content.contextMenu {
            Button {
                dismiss()
                appNavigationStore.showOnMap(node)
            } label: {
                Label("Show on Map", systemImage: "map")
            }
            .disabled(!canShowOnMap)

            Button {
                UIPasteboard.general.string = node.publicKey
            } label: {
                Label("Copy Public Key", systemImage: "doc.on.doc")
            }
        }
    }

    private var canShowOnMap: Bool {
        guard let coordinate = node.coordinate else { return false }
        return coordinate.latitude != 0 || coordinate.longitude != 0
    }
}
