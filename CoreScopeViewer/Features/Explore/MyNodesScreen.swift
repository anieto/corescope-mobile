import SwiftUI

/// Current activity for saved nodes and observers; does not advance comparison history.
struct MyNodesScreen: View {
    let isTabActive: Bool
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favorites
    @Environment(\.scenePhase) private var scenePhase
    @State private var rows: [String: MyNodesBaseRow] = [:]
    @State private var catalog: MyNodesObserverCatalogRow?
    @State private var loading = false
    @State private var error: LocalizedStringResource?
    @State private var revision = 0
    @State private var forceRefresh = false
    @State private var generation = UUID()
    @State private var showsActivityHelp = false

    private var nodes: [FavoriteItem] {
        favorites.items.filter { $0.source == AnalyzerSettings.normalizedHost(settings.host) && $0.kind == .node }
    }
    private var observers: [FavoriteItem] {
        favorites.items.filter { $0.source == AnalyzerSettings.normalizedHost(settings.host) && $0.kind == .observer }
    }

    var body: some View {
        ZStack {
            NodeScopeBackground()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    ActivitySourceCard(host: settings.host, loading: loading, error: error)
                    if nodes.isEmpty && observers.isEmpty {
                        ContentUnavailableView("No favorites yet", systemImage: "star",
                            description: Text("Add nodes in Favorites or save observers from their detail pages to follow them here."))
                    }
                    if !nodes.isEmpty {
                        Text("Nodes").font(.headline).accessibilityAddTraits(.isHeader)
                        ForEach(nodes) { item in
                            MyNodesActivityRow(item: item, row: rows[item.entityID.lowercased()])
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(16)
                                .instrumentCard()
                        }
                        if !loading && nodes.contains(where: { rows[$0.entityID.lowercased()] == nil || rows[$0.entityID.lowercased()]?.state == .deferred }) {
                        Button("Load More", systemImage: "arrow.down.circle") {
                            forceRefresh = false
                            revision += 1
                        }
                        .buttonStyle(.bordered)
                        .disabled(loading || scenePhase != .active || !isTabActive)
                        }
                    }
                    if !observers.isEmpty {
                        Text("Favorite observers").font(.headline).accessibilityAddTraits(.isHeader)
                        ForEach(observers) { item in
                            MyNodesObserverRow(item: item, catalog: catalog)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(16)
                                .instrumentCard()
                        }
                    }
                }
                .padding(16)
            }
            .adaptiveScrollContentWidth()
        }
        .navigationTitle("Activity")
        .toolbar(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("About activity", systemImage: "info.circle") { showsActivityHelp = true }
                Button("Refresh", systemImage: "arrow.clockwise") {
                    forceRefresh = true
                    revision += 1
                }
                .disabled(loading || scenePhase != .active || !isTabActive)
            }
        }
        .sheet(isPresented: $showsActivityHelp) {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Recently heard means the source recorded activity within 15 minutes. Silence does not mean a node is offline.")
                        Text("Last heard is the latest reported activity. Checked is when the app retrieved that information. Saved data keeps its original check time.")
                        Text("Missing observers have unknown activity. Some sources return an incomplete observer list.")
                    }.padding()
                }
                .navigationTitle("About activity")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showsActivityHelp = false } } }
            }
            .presentationDetents([.medium, .large])
        }
        .task(id: LoadIdentity(session: settings.sourceSession.id, active: scenePhase == .active && isTabActive,
                               keys: nodes.map(\.entityID), observers: observers.map(\.entityID), revision: revision)) {
            await load()
        }
        .onDisappear { generation = UUID() }
    }

    private struct LoadIdentity: Equatable {
        let session: UUID
        let active: Bool
        let keys: [String]
        let observers: [String]
        let revision: Int
    }

    @MainActor private func load() async {
        let token = UUID()
        generation = token
        loading = false
        guard scenePhase == .active, isTabActive, settings.sourceIsReady else { return }
        let keys = nodes.map(\.entityID)
        guard !keys.isEmpty || !observers.isEmpty else { rows = [:]; catalog = nil; return }
        loading = true
        let refresh = forceRefresh
        forceRefresh = false
        error = nil
        defer { if generation == token { loading = false } }
        var ownedLifetime: AnalyzerSourceSession?
        do {
            let owned: MyNodesRepositories
            if let beacon = settings.beaconBackend {
                owned = try await CacheStorageService.shared.myNodesBeacon(client: beacon.client, serverInfo: beacon.serverInfo)
            } else {
                owned = try await CacheStorageService.shared.myNodesCoreScope(
                    client: APIClient(baseURL: settings.baseURL), session: settings.sourceSession)
            }
            ownedLifetime = owned.lifetime
            try Task.checkCancellation()
            let invalidation = AsyncStream<Void>.makeStream()
            let registration = owned.lifetime.registerCancellation {
                invalidation.continuation.yield(())
                invalidation.continuation.finish()
            }
            defer {
                owned.lifetime.removeCancellation(registration)
                invalidation.continuation.finish()
            }
            let cached = try await owned.base.cached(publicKeys: keys)
            guard generation == token else { return }
            rows = cached.mapValues { MyNodesBaseRow(publicKey: $0.node.publicKey.lowercased(), snapshot: $0, state: .cached) }
            catalog = try await owned.base.observers()
            let loaded = try await owned.base.load(publicKeys: keys, forceRefresh: refresh) { row in
                Task { @MainActor in
                    guard generation == token, owned.lifetime.isCurrent else { return }
                    rows[row.publicKey] = row
                }
            }
            try Task.checkCancellation()
            try owned.lifetime.check()
            guard generation == token else { return }
            rows = Dictionary(loaded.map { ($0.publicKey, $0) }, uniquingKeysWith: { _, last in last })
            catalog = try await owned.base.observers()
            loading = false
            for await _ in invalidation.stream {
                guard generation == token, !Task.isCancelled else { return }
                rows = [:]
                catalog = nil
                self.error = "Activity data was cleared or the source changed. Refresh to continue."
            }
        } catch {
            guard generation == token, !Task.isCancelled else { return }
            if ownedLifetime?.isCurrent == false { rows = [:]; catalog = nil }
            self.error = "Activity could not be updated. Refresh to try again."
        }
    }
}

struct FavoriteSourceIdentity: View {
    let host: String
    @Environment(AnalyzerSourceRegistry.self) private var sourceRegistry

    var body: some View {
        let name = sourceRegistry.sources.first {
            AnalyzerSettings.normalizedHost($0.host) == AnalyzerSettings.normalizedHost(host)
        }?.name.trimmingCharacters(in: .whitespacesAndNewlines)
        VStack(alignment: .leading, spacing: 4) {
            Text(name.flatMap { $0.isEmpty ? nil : $0 } ?? host).font(.headline)
            if let name, !name.isEmpty, name.caseInsensitiveCompare(host) != .orderedSame {
                Text(host).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

private struct ActivitySourceCard: View {
    let host: String
    let loading: Bool
    let error: LocalizedStringResource?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            FavoriteSourceIdentity(host: host)
            if loading { ProgressView("Updating activity…").font(.subheadline) }
            if let error {
                Label { Text(error) } icon: { Image(systemName: "exclamationmark.circle") }
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .instrumentCard()
    }
}

private struct MyNodesActivityRow: View {
    let item: FavoriteItem
    let row: MyNodesBaseRow?

    var body: some View {
        let node = row?.snapshot?.node ?? item.node
        if let node {
            NavigationLink(value: node) {
                ActivityItemContent(title: node.name ?? item.title,
                    symbol: NodeRoleStyle.symbolName(for: node.role), color: NodeRoleStyle.color(for: node.role),
                    lastSeen: row?.snapshot?.node.latestActivity, fetchedAt: row?.snapshot?.fetchedAt,
                    state: row?.state)
            }
            .buttonStyle(.plain)
        } else {
            ActivityItemContent(title: item.title, symbol: "questionmark", color: .gray,
                lastSeen: nil, fetchedAt: nil, state: row?.state)
        }
    }
}

private struct MyNodesObserverRow: View {
    let item: FavoriteItem
    let catalog: MyNodesObserverCatalogRow?

    var body: some View {
        let record = catalog?.snapshot?.observers.first { $0.id == item.entityID }
        if let observer = record ?? item.observer {
            NavigationLink(value: observer) {
                ActivityItemContent(title: observer.name ?? item.title,
                    symbol: "dot.radiowaves.left.and.right", color: .blue,
                    lastSeen: record?.lastSeen, fetchedAt: catalog?.snapshot?.fetchedAt,
                    state: catalog?.state, incomplete: catalog?.snapshot?.completeness != .complete)
            }
            .buttonStyle(.plain)
        } else {
            ActivityItemContent(title: item.title, symbol: "dot.radiowaves.left.and.right", color: .blue,
                lastSeen: nil, fetchedAt: catalog?.snapshot?.fetchedAt, state: catalog?.state)
        }
    }
}

private struct ActivityItemContent: View {
    let title: String
    let symbol: String
    let color: Color
    let lastSeen: Date?
    let fetchedAt: Date?
    let state: MyNodesBaseRow.State?
    var incomplete = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .font(.title3.weight(.semibold)).foregroundStyle(.white)
                    .frame(width: 42, height: 42).background(color, in: Circle())
                    .accessibilityHidden(true)
                Text(title).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary).accessibilityHidden(true)
            }
            TimelineView(.periodic(from: .now, by: 60)) { context in
                let activity = MyNodesActivity(lastSeen: lastSeen, lastHeard: nil,
                    fetchedAt: fetchedAt ?? .distantPast, evaluatedAt: context.date,
                    successfulRecord: state == .fetched || state == .cached)
                VStack(alignment: .leading, spacing: 8) {
                    Label(activity.state == .recentlyHeard ? "Recently heard" :
                            activity.state == .notRecentlyHeard ? "Not recently heard" : "Activity unknown",
                          systemImage: activity.state == .recentlyHeard ? "waveform.path" : "clock")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(activity.state == .recentlyHeard ? Color.accentColor : Color.secondary)
                    if let lastSeen {
                        (Text("Last heard ") + Text(lastSeen, style: .relative) + Text(" ago"))
                            .font(.subheadline)
                            .accessibilityLabel(Text("Last heard: \(lastSeen, format: .dateTime)"))
                    }
                    if let fetchedAt {
                        (Text(state == .cached ? "Saved data · checked " : "Checked ") +
                         Text(fetchedAt, style: .relative) + Text(" ago"))
                            .font(.caption).foregroundStyle(.secondary)
                        if context.date.timeIntervalSince(fetchedAt) > 300 {
                            Text("Refresh needed").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            if let issue {
                Text(issue).font(.caption).foregroundStyle(.secondary)
            }
            if incomplete {
                Text("Observer list may be incomplete").font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private var issue: LocalizedStringResource? {
        switch state {
        case .fetched, .cached: nil
        case .unavailable: "Unavailable on this source"
        case .deferred: "Not checked yet"
        case .failed: "Update failed · retry with Refresh"
        case .rateLimited: "Server rate limit · try later"
        case nil: "Not checked yet"
        }
    }
}
