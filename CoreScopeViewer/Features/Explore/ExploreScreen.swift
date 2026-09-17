import SwiftUI

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
    @State private var isAddFavoritePresented = false

    var body: some View {
        NavigationStack(path: $navigationPath) {
            List {
                ExploreHeader(
                    count: visibleItems.count,
                    canReorder: canReorder,
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
                            isAddFavoritePresented = true
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
            await nodeViewModel.loadNodes(region: nil)
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
}
