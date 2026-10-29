import SwiftUI

struct FavoriteChangeRow: Identifiable {
    let entity: MyNodesEntity
    let title: String
    let metric: MyNodesMetric?
    let result: MyNodesReviewResult
    let previousAt: Date?
    let fresh: Bool

    var id: String { entity.id }
    var revision: Date { metric?.fetchedAt ?? .distantPast }

    var message: LocalizedStringResource {
        switch result.state {
        case .firstVisit: "First visit · starting comparison history"
        case .expiredBaseline: "Previous review is over 7 days old · starting again"
        case .unavailable: "Comparison unavailable"
        case .unchanged: "No activity change"
        case .changed:
            switch result.event?.change {
            case "newlyActive", "observerActive": "Recently heard again"
            case "noLongerRecentlyHeard", "observerInactive": "No longer recently heard"
            default: "Activity changed"
            }
        }
    }
}

/// Owns a single foreground Changes visit. Refreshes keep its captured baseline.
@MainActor @Observable
final class FavoriteChangesModel {
    private(set) var rows: [FavoriteChangeRow] = []
    private(set) var loading = false
    private(set) var error: LocalizedStringResource?
    private var session: MyNodesVisitSession?
    private var generation = UUID()
    private var commands: AsyncStream<Command>.Continuation?
    private enum Command { case load(Bool), invalidated }

    func refresh(force: Bool) { commands?.yield(.load(force)) }

    func review(_ row: FavoriteChangeRow) async {
        guard let session, let metric = row.metric, row.fresh,
              rows.contains(where: { $0.id == row.id && $0.revision == row.revision }) else { return }
        let token = generation
        do {
            _ = try await session.reviewed(entity: row.entity, metric: metric, freshFetch: true, at: .now)
        } catch {
            if generation == token { self.error = "Review history could not be saved. Refresh to try again." }
        }
    }

    func run(settings: AnalyzerSettings, items: [FavoriteItem]) async {
        await run(items: items, acquire: {
            if let beacon = settings.beaconBackend {
                return try await CacheStorageService.shared.myNodesBeacon(client: beacon.client, serverInfo: beacon.serverInfo)
            }
            return try await CacheStorageService.shared.myNodesCoreScope(
                client: APIClient(baseURL: settings.baseURL), session: settings.sourceSession)
        }, snapshots: { try await CacheStorageService.shared.myNodesSnapshots() })
    }

    func run(items: [FavoriteItem], acquire: () async throws -> MyNodesRepositories,
             snapshots: () async throws -> MyNodesSnapshotStore) async {
        let token = UUID()
        generation = token
        let previousSession = session
        session = nil
        if let previousSession { try? await previousSession.finish() }
        guard generation == token, !Task.isCancelled else { return }
        rows = []
        error = nil
        let stream = AsyncStream<Command>.makeStream()
        commands = stream.continuation
        var opened: MyNodesVisitSession?
        var owned: MyNodesRepositories?
        var registration: UUID?
        do {
            let repositories = try await acquire()
            owned = repositories
            let source = repositories.base.source.split(separator: "|", maxSplits: 1).map(String.init)
            let namespace = MyNodesNamespace(origin: source.last ?? "",
                backend: source.first ?? "corescope", selectionScope: "local", policyVersion: 1)
            let store = try await snapshots()
            let visit = try await MyNodesVisitSession(store: store, source: repositories.lifetime, namespace: namespace,
                nodes: Set(items.filter { $0.kind == .node }.map(\.entityID)),
                favoriteObservers: Set(items.filter { $0.kind == .observer }.map(\.entityID)), at: .now)
            opened = visit
            try Task.checkCancellation()
            session = visit
            registration = repositories.lifetime.registerCancellation { stream.continuation.yield(.invalidated) }
            stream.continuation.yield(.load(true))
            for await command in stream.stream {
                try Task.checkCancellation()
                if case .invalidated = command {
                    rows = []
                    error = "Comparison data was cleared or the source changed. Reopen Changes to continue."
                    break
                }
                if case .load(let force) = command {
                    loading = true
                    error = nil
                    do {
                        let keys = items.filter { $0.kind == .node }.map(\.entityID)
                        let loaded = items.isEmpty ? [] : try await visit.loadBase(repository: repositories.base, publicKeys: keys, forceRefresh: force)
                        let catalog = try await repositories.base.observers()
                        try Task.checkCancellation()
                        try repositories.lifetime.check()
                        let captured = await visit.visit
                        let now = Date.now
                        rows = items.map { item in
                            let entity = MyNodesEntity(kind: item.kind == .node ? .node : .observer, value: item.entityID)
                            let metric: MyNodesMetric?
                            let fresh: Bool
                            if item.kind == .node, let row = loaded.first(where: { $0.publicKey == item.entityID.lowercased() }),
                               let snapshot = row.snapshot {
                                fresh = row.state == .fetched
                                metric = MyNodesMetric(activity: MyNodesActivity(lastSeen: snapshot.node.lastSeen,
                                    lastHeard: snapshot.node.lastHeard, fetchedAt: snapshot.fetchedAt,
                                    evaluatedAt: now, successfulRecord: fresh))
                            } else if item.kind == .observer, let snapshot = catalog.snapshot,
                                      let observer = snapshot.observers.first(where: { $0.id == item.entityID }) {
                                fresh = catalog.state == .fetched
                                metric = MyNodesMetric(observer: MyNodesObserverStatus(explicitOnline: nil, statusAt: nil,
                                    lastSeen: observer.lastSeen, fetchedAt: snapshot.fetchedAt, evaluatedAt: now, successfulRecord: fresh))
                            } else { metric = nil; fresh = false }
                            // A later bounded batch may return a cache hit for evidence fetched in this visit.
                            if !force, !fresh, let previous = rows.first(where: { $0.id == entity.id }),
                               previous.fresh, previous.revision == metric?.fetchedAt { return previous }
                            let key = MyNodesSnapshotStore.metricKey(entity: entity, category: item.kind == .node ? .activity : .observer)
                            let baseline = captured.baselines[key]
                            let result = fresh && (metric?.fetchedAt ?? .distantPast) >= captured.startedAt
                                ? metric?.review(namespace: namespace, entity: entity, baseline: baseline,
                                    expired: captured.expiredKeys.contains(key)) ?? .unavailable : .unavailable
                            return FavoriteChangeRow(entity: entity, title: item.title, metric: metric, result: result,
                                previousAt: baseline?.metric.evaluatedAt, fresh: fresh)
                        }
                    } catch {
                        if Task.isCancelled { throw error }
                        self.error = "Changes could not be updated. Refresh to try again."
                    }
                    loading = false
                }
            }
        } catch {
            if !Task.isCancelled { self.error = "Changes could not be opened. Try again." }
        }
        if let registration { owned?.lifetime.removeCancellation(registration) }
        stream.continuation.finish()
        if generation == token { session = nil; commands = nil; loading = false }
        if let opened {
            do { try await opened.finish() }
            catch { if generation == token { self.error = "Review history could not be saved." } }
        }
    }
}

struct FavoriteChangesScreen: View {
    let isTabActive: Bool
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favorites
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = FavoriteChangesModel()

    private var items: [FavoriteItem] {
        favorites.items.filter {
            $0.source == AnalyzerSettings.normalizedHost(settings.host) && ($0.kind == .node || $0.kind == .observer)
        }
    }
    private struct VisitIdentity: Hashable {
        let source: UUID
        let active: Bool
        let items: [String]
    }

    var body: some View {
        ZStack {
            MeshBackground(subdued: true).ignoresSafeArea()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 12) {
                        FavoriteSourceIdentity(host: settings.host)
                        Text("Activity since your last review").font(.headline)
                        Text("Compares saved nodes and observers using the 15-minute recently-heard window. Silence does not mean offline.")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Text("Fresh comparisons you view become the baseline for your next visit. History expires after 7 days.")
                            .font(.footnote).foregroundStyle(.secondary)
                        if model.loading { ProgressView("Checking changes…") }
                        if let error = model.error { Text(error).font(.footnote).foregroundStyle(.secondary) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
                    if items.isEmpty {
                        ContentUnavailableView("No favorites to compare", systemImage: "star",
                            description: Text("Save nodes or observers in Favorites to follow changes here."))
                    }
                    ForEach(model.rows) { row in
                        FavoriteChangeCard(row: row, active: scenePhase == .active && isTabActive) { await model.review(row) }
                    }
                    if !items.isEmpty {
                        Button("Load More", systemImage: "arrow.down.circle") { model.refresh(force: false) }
                            .buttonStyle(.bordered).disabled(model.loading)
                    }
                }
                .padding(16)
            }
            .adaptiveScrollContentWidth()
        }
        .navigationTitle("Changes")
        .toolbar(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Refresh", systemImage: "arrow.clockwise") { model.refresh(force: true) }
                    .disabled(model.loading || scenePhase != .active || !isTabActive)
            }
        }
        .task(id: VisitIdentity(source: settings.sourceSession.id, active: scenePhase == .active && isTabActive,
                               items: items.map(\.id))) {
            guard scenePhase == .active, isTabActive, settings.sourceIsReady else { return }
            await model.run(settings: settings, items: items)
        }
    }
}

private struct FavoriteChangeCard: View {
    let row: FavoriteChangeRow
    let active: Bool
    let reviewed: () async -> Void
    @State private var visible = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(row.title).font(.headline)
            Text(row.message).font(.subheadline.weight(.semibold))
            if let activity = row.metric?.activity {
                Text(activity.state == .recentlyHeard ? "Recently heard" :
                        activity.state == .notRecentlyHeard ? "Not recently heard" : "Activity unknown")
                    .font(.subheadline)
            }
            if let observer = row.metric?.observer {
                Text(observer.state == .active ? "Recently seen" :
                        observer.state == .inactive ? "Not recently seen" : "Observer activity unknown")
                    .font(.subheadline)
            }
            if let at = row.previousAt { Text("Last reviewed: \(at, format: .dateTime)").font(.caption).foregroundStyle(.secondary) }
            if let at = row.metric?.fetchedAt { Text("Checked: \(at, format: .dateTime)").font(.caption).foregroundStyle(.secondary) }
            if row.result.state == .unavailable {
                Text("A fresh successful check and comparable history are required. Missing or cached evidence is not an unchanged result.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
        .onScrollVisibilityChange(threshold: 0.5) { visible = $0 }
        .task(id: "\(visible)-\(active)-\(row.revision.timeIntervalSince1970)") {
            guard visible, active else { return }
            await reviewed()
        }
    }
}
