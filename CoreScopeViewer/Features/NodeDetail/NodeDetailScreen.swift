import CoreImage.CIFilterBuiltins
import SafariServices
import SwiftUI
import UIKit

struct NodeDetailScreen: View {
    let node: MeshNode
    private let openedFromNodeVision: Bool

    init(node: MeshNode, openedFromNodeVision: Bool = false) {
        self.node = node
        self.openedFromNodeVision = openedFromNodeVision
    }

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favoritesStore
    @Environment(RecentItemsStore.self) private var recentItemsStore
    @Environment(AppNavigationStore.self) private var appNavigationStore
    @State private var viewModel = NodeDetailViewModel()
    @State private var nodeCatalogViewModel = MapViewModel()
    @State private var linkedNodesByPublicKey: [String: MeshNode] = [:]
    @State private var selectedLinkedNode: MeshNode?
    @State private var meshCoreContact: MeshCoreContactLink?
    @State private var browserLink: BrowserLink?
    @State private var unclaimedRepeaterURL: URL?
    @State private var repeaterClient: CoreScopeAccountClient?
    @State private var showsRepeaterManagement = false
    @State private var showsClaimConfirmation = false
    @State private var repeaterMetadata: RepeaterMetadata?
    @State private var metadataSourceID: UUID?
    @State private var showsFieldTools = false
    @State private var showsNodeVision = false
    @State private var showsWatchSettings = false
    @State private var watchTask: Task<Void, Never>?
    @State private var watchMessage: String?
    @State private var showsWatchAlert = false
    @State private var watchIntent: Bool?
    @State private var isWatched = false

    private enum WatchAction {
        case refresh, confirm, set(Bool)

        var isInteractive: Bool {
            if case .refresh = self { return false }
            return true
        }
    }

    private var hasFieldCoordinate: Bool {
        guard let latitude = displayedNode.lat, let longitude = displayedNode.lon else { return false }
        return !(latitude == 0 && longitude == 0)
            && FieldCoordinate(latitude: latitude, longitude: longitude) != nil
    }

    @ViewBuilder
    private var nodeVisionActions: some View {
        if hasFieldCoordinate {
            if !openedFromNodeVision {
                Button { showsNodeVision = true } label: {
                    Label("Open in NodeVision", systemImage: "camera")
                }
                .buttonStyle(StandaloneActionButtonStyle())
            }

            if LegacyFieldGuidance.isEnabled {
                Button { showsFieldTools = true } label: {
                    FieldGuidanceLinkCard(entityName: displayedNode.name ?? String(localized: "this node"))
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private var nodeVisionDestination: some View {
        let item = FieldCatalogItem.node(displayedNode)
        if let coordinate = item.coordinate {
            NodeVisionScreen(name: item.name, target: coordinate,
                             repeaterKey: displayedNode.publicKey)
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if viewModel.isLoading {
                    LoadingIndicator(title: String(localized: "Loading node data…"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                DataLoadStatusView(
                    lastUpdatedAt: viewModel.lastUpdatedAt,
                    errorMessage: viewModel.errorMessage,
                    hasContent: viewModel.health != nil || viewModel.paths != nil
                        || viewModel.reach != nil || viewModel.neighbors != nil
                        || viewModel.beaconHealth != nil,
                    retry: retryNodeData
                )

                NodeIdentityCard(
                    node: displayedNode,
                    isCheckingWatch: watchTask != nil,
                    isWatched: isWatched,
                    onWatch: settings.beaconBackend == nil ? { updateWatch(.confirm) } : nil
                )

                if metadataSourceID == settings.sourceSession.id,
                   let repeaterMetadata, repeaterMetadata.pubkey == node.publicKey.lowercased() {
                    RepeaterMetadataCard(metadata: repeaterMetadata, openCoreScope: coreScopeRepeaterURL.map { url in
                        { browserLink = BrowserLink(url: url) }
                    })
                }

                if repeaterClient != nil, coreScopeRepeaterURL != nil {
                    Button("Manage Repeater", systemImage: "antenna.radiowaves.left.and.right") {
                        showsRepeaterManagement = true
                    }
                    .buttonStyle(.bordered)
                } else if let unclaimedRepeaterURL, unclaimedRepeaterURL == coreScopeRepeaterURL {
                    Button {
                        showsClaimConfirmation = true
                    } label: {
                        Label("Claim Repeater", systemImage: "person.badge.plus")
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 28)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .tint(Color(red: 0, green: 0.39, blue: 0.65))
                }

                nodeVisionActions

                if settings.beaconBackend != nil {
                    BeaconFeatureNotice(capability: .nodeHealth)
                } else {
                    NodeCheckLinkCard(node: displayedNode)
                }

                NavigationLink {
                    NodeAnalyticsScreen(node: displayedNode)
                } label: {
                    NodeAnalyticsLinkCard()
                }
                .buttonStyle(.plain)

                if let reach = viewModel.reach {
                    NodeReachCard(reach: reach)
                } else if let neighbors = viewModel.neighbors {
                    BeaconNodeReachCard(neighborCount: neighbors.neighbors.count)
                } else if viewModel.isLoading {
                    NodeReachLoadingCard()
                }

                if let health = viewModel.health {
                    NodeHealthCard(stats: health.stats)
                    if !health.observers.isEmpty {
                        NodeObserversCard(
                            observers: health.observers,
                            openObserver: { appNavigationStore.openObserver(id: $0) }
                        )
                    }
                }

                if let beaconHealth = viewModel.beaconHealth {
                    if beaconHealth.hasActivity {
                        BeaconNodeHealthCard(health: beaconHealth)
                    }
                    if !beaconHealth.heardIn.isEmpty {
                        BeaconHeardInCard(regions: beaconHealth.heardIn)
                    }
                }

                if let reach = viewModel.reach {
                    if !reach.links.isEmpty {
                        NodeLinksCard(
                            links: reach.links,
                            nodesByPublicKey: linkedNodesByPublicKey,
                            openNode: { selectedLinkedNode = $0 }
                        )
                    }
                }

                if let neighbors = viewModel.neighbors {
                    if neighbors.neighbors.isEmpty {
                        NodeNeighborsEmptyCard()
                    } else {
                        NodeNeighborsCard(
                            neighbors: neighbors.neighbors,
                            nodesByPublicKey: linkedNodesByPublicKey,
                            openNode: { selectedLinkedNode = $0 }
                        )
                    }
                }

                if let paths = viewModel.paths, !paths.paths.isEmpty {
                    NodePathsCard(paths: paths.paths, total: paths.totalPaths)
                }
            }
            .padding(16)
            .adaptiveContentWidth()
        }
        .background(MeshBackground(subdued: true).ignoresSafeArea())
        .refreshable {
            await refreshNodeData(forceCatalogRefresh: true)
        }
        .navigationTitle(displayedNode.name ?? "Node")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            recentItemsStore.record(node: node, source: favoriteSource)
        }
        .toolbar {
            if hasFieldCoordinate {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showsFieldTools = true
                    } label: {
                        Image(systemName: "map")
                    }
                    .accessibilityLabel("Open node in Field Map")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                favoriteButton
            }
            ToolbarItem(placement: .topBarTrailing) {
                quickActionsMenu
            }
        }
        .navigationDestination(item: $selectedLinkedNode) { linkedNode in
            NodeDetailScreen(node: linkedNode, openedFromNodeVision: openedFromNodeVision)
                .id(linkedNode.publicKey)
        }
        .navigationDestination(isPresented: $showsWatchSettings) {
            CoreScopeAccountScreen(watchNode: displayedNode, onWatchAdded: { showWatchConfirmation(watched: true) })
                .id(settings.sourceSession.id)
        }
        .task(id: "\(settings.sourceSession.id)|\(node.publicKey)") {
            isWatched = false
            updateWatch(.refresh)
        }
        .alert(watchAlertTitle, isPresented: $showsWatchAlert) {
            if let watchIntent {
                if watchIntent {
                    Button("Watch node") { updateWatch(.set(true)) }
                } else {
                    Button("Stop watching", role: .destructive) { updateWatch(.set(false)) }
                }
                Button("Cancel", role: .cancel) {}
            } else {
                Button("OK", role: .cancel) {}
            }
        } message: {
            let name = displayedNode.name ?? String(localized: "this node")
            if let watchIntent {
                if watchIntent {
                    Text("Add \(name) to your CoreScope watch list.")
                } else {
                    Text("Remove \(name) from your CoreScope watch list.")
                }
            } else {
                Text(watchMessage ?? "")
            }
        }
        .onDisappear { watchTask?.cancel() }
        .alert("Claim this repeater?", isPresented: $showsClaimConfirmation) {
            Button("Continue to CoreScope") {
                if let unclaimedRepeaterURL, unclaimedRepeaterURL == coreScopeRepeaterURL {
                    browserLink = BrowserLink(url: unclaimedRepeaterURL)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Only claim repeaters you run and maintain. Site admins can remove an incorrect claim. Sign in to CoreScope and choose Claim to finish.")
        }
        .task(id: coreScopeRepeaterURL) {
            repeaterClient = nil
            if let url = coreScopeRepeaterURL,
               let client = try? CoreScopeAccountClient.restoredRepeaterClient(settings: settings),
               (try? await client.supportsRepeaterManagement()) == true,
               url == coreScopeRepeaterURL, !Task.isCancelled {
                repeaterClient = client
            } else {
                await refreshRepeaterClaim()
            }
        }
        .sheet(isPresented: $showsRepeaterManagement, onDismiss: {
            Task { await refreshRepeaterMetadata(force: true) }
        }) {
            if let repeaterClient {
                AccountRepeaterManagementSheet(client: repeaterClient, publicKey: displayedNode.publicKey,
                    name: displayedNode.name ?? displayedNode.publicKey)
            }
        }
        .sheet(item: $browserLink, onDismiss: {
            Task {
                await refreshRepeaterClaim()
                await refreshRepeaterMetadata(force: true)
            }
        }) { link in
            InAppBrowser(url: link.url)
                .ignoresSafeArea()
        }
        .sheet(item: $meshCoreContact) { contact in
            MeshCoreContactSheet(contact: contact)
        }
        .fullScreenCover(isPresented: $showsNodeVision) { nodeVisionDestination }
        .fullScreenCover(isPresented: $showsFieldTools) {
            FieldToolsScreen(
                initialNode: displayedNode,
                closeAction: { showsFieldTools = false }
            )
        }
        .onChange(of: nodeCatalogViewModel.nodes) {
            linkedNodesByPublicKey = nodeCatalogViewModel.nodesByPubkey
        }
        .task(id: "\(settings.sourceSession.id)|\(node.publicKey)|\(settings.sourceIsReady)") {
            await refreshRepeaterMetadata(force: false)
        }
        .task(id: node.publicKey) {
            viewModel.configure(settings: settings)
            nodeCatalogViewModel.configure(settings: settings)
            await refreshNodeData(forceCatalogRefresh: false)
        }
    }

    private func refreshRepeaterClaim() async {
        unclaimedRepeaterURL = nil
        guard let url = coreScopeRepeaterURL,
              let backend = AnalyzerBackendFactory.make(settings: settings) as? CoreScopeBackend else { return }
        let key = displayedNode.publicKey
        let isUnclaimed = try? await backend.repeaterIsUnclaimed(publicKey: key)
        guard backend.sourceSession.isCurrent, !Task.isCancelled, url == coreScopeRepeaterURL else { return }
        if isUnclaimed == true { unclaimedRepeaterURL = url }
    }

    private var coreScopeRepeaterURL: URL? {
        guard settings.beaconBackend == nil,
              displayedNode.role.lowercased() == "repeater",
              displayedNode.publicKey.count == 64,
              displayedNode.publicKey.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) })
        else { return nil }
        var url = URLComponents(url: settings.baseURL, resolvingAgainstBaseURL: false)
        url?.path = "/"
        url?.fragment = "/nodes/\(displayedNode.publicKey.lowercased())?section=node-repeater-info"
        return url?.url
    }

    private var quickActionsMenu: some View {
        Menu {
            Button {
                appNavigationStore.showOnMap(displayedNode)
            } label: {
                Label("Show on Map", systemImage: "map")
            }
            .disabled(!canShowOnMap)

            Button {
                UIPasteboard.general.string = displayedNode.publicKey
            } label: {
                Label("Copy Public Key", systemImage: "doc.on.doc")
            }

            if let contact = MeshCoreContactLink(node: displayedNode) {
                Button {
                    meshCoreContact = contact
                } label: {
                    Label("Add to MeshCore", systemImage: "qrcode")
                }
            }

            if let coreScopeRepeaterURL {
                Button {
                    browserLink = BrowserLink(url: coreScopeRepeaterURL)
                } label: {
                    Label("Open Repeater in CoreScope", systemImage: "arrow.up.right")
                }
            }

            if let url = NodeScopeDeepLink.node(displayedNode.publicKey).url {
                ShareLink(item: url) {
                    Label("Share Node Link", systemImage: "square.and.arrow.up")
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel("Node actions")
    }

    private var watchAlertTitle: String {
        switch watchIntent {
        case true?: String(localized: "Watch this node?")
        case false?: String(localized: "Stop watching this node?")
        case nil: String(localized: "Watched-node alerts")
        }
    }

    private func updateWatch(_ action: WatchAction) {
        guard watchTask == nil, settings.beaconBackend == nil else { return }
        let origin = settings.baseURL
        let sourceSession = settings.sourceSession
        let key = displayedNode.publicKey.lowercased()

        watchTask = Task {
            defer { watchTask = nil }
            do {
                let cookie = try AccountSessionKeychain.load(origin: origin.absoluteString)
                if cookie == nil { throw CoreScopeAccountError.signedOut }
                let client = try CoreScopeAccountClient(origin: origin, cookie: cookie, lifetime: sourceSession,
                    save: { try AccountSessionKeychain.save($0, origin: origin.absoluteString) })
                if action.isInteractive, try await client.accountCapabilities()?.notifications != true {
                    try Task.checkCancellation()
                    try sourceSession.check()
                    presentWatchAlert(message: String(localized: "This analyzer has CoreScope account notifications turned off."))
                    return
                }
                let notifications: CoreScopeAccountNotifications
                switch action {
                case .refresh, .confirm:
                    notifications = try await client.notifications()
                case .set(let watched):
                    let user = try await client.me()
                    try Task.checkCancellation()
                    notifications = try await client.setWatch(key, watched: watched, csrf: user.csrfToken)
                }
                try Task.checkCancellation()
                try sourceSession.check()
                isWatched = notifications.watches.contains { $0.pubkey == key }

                switch action {
                case .refresh:
                    break
                case .confirm:
                    presentWatchAlert(intent: !isWatched)
                case .set:
                    showWatchConfirmation(watched: isWatched)
                }
            } catch CoreScopeAccountError.signedOut {
                guard !Task.isCancelled, sourceSession.isCurrent else { return }
                isWatched = false
                switch action {
                case .refresh:
                    break
                case .confirm:
                    presentWatchAlert(intent: true)
                case .set(true):
                    showsWatchSettings = true
                case .set(false):
                    presentWatchAlert(message: CoreScopeAccountError.signedOut.localizedDescription)
                }
            } catch {
                guard !Task.isCancelled, sourceSession.isCurrent else { return }
                guard action.isInteractive else { return }
                presentWatchAlert(message: error.localizedDescription)
            }
        }
    }

    private func presentWatchAlert(intent: Bool? = nil, message: String? = nil) {
        watchIntent = intent
        watchMessage = message
        showsWatchAlert = true
    }

    private func showWatchConfirmation(watched: Bool) {
        isWatched = watched
        let name = displayedNode.name ?? String(localized: "this node")
        presentWatchAlert(message: watched
            ? String(localized: "You’re watching \(name). Manage alerts in CoreScope Account.")
            : String(localized: "You’re no longer watching \(name)."))
    }

    private var canShowOnMap: Bool {
        guard let coordinate = displayedNode.coordinate else { return false }
        return coordinate.latitude != 0 || coordinate.longitude != 0
    }

    private var favoriteButton: some View {
        let isFavorite = favoritesStore.contains(
            kind: .node,
            entityID: displayedNode.publicKey,
            source: favoriteSource
        )
        return Button {
            favoritesStore.toggle(node: displayedNode, source: favoriteSource)
        } label: {
            Image(systemName: isFavorite ? "star.fill" : "star")
        }
        .tint(NodeScopeStyle.signal)
        .accessibilityLabel(isFavorite ? "Remove node from favorites" : "Add node to favorites")
    }

    private var displayedNode: MeshNode {
        viewModel.health?.node ?? node
    }

    private var favoriteSource: String {
        AnalyzerSettings.normalizedHost(settings.host)
    }

    private func retryNodeData() {
        Task {
            await refreshNodeData(forceCatalogRefresh: true)
        }
    }

    private func refreshRepeaterMetadata(force: Bool) async {
        guard settings.sourceIsReady else { return }
        let backend = AnalyzerBackendFactory.make(settings: settings)
        let catalog = await FieldCatalogRepository.shared.repeaterMetadata(for: backend, force: force)
        guard backend.sourceSession.isCurrent, !Task.isCancelled else { return }
        repeaterMetadata = catalog[node.publicKey]
        metadataSourceID = backend.sourceSession.id
    }

    private func refreshNodeData(forceCatalogRefresh: Bool) async {
        async let details: Void = viewModel.load(pubkey: node.publicKey)
        async let catalog: Void = nodeCatalogViewModel.loadNodes(
            region: nil,
            forceRefresh: forceCatalogRefresh
        )
        if forceCatalogRefresh { await refreshRepeaterMetadata(force: true) }
        _ = await (details, catalog)
        linkedNodesByPublicKey = nodeCatalogViewModel.nodesByPubkey
    }
}

private struct NodeAnalyticsLinkCard: View {
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "chart.xyaxis.line")
                .font(.monaspace(.title2))
                .foregroundStyle(NodeScopeStyle.signal)
                .frame(width: 48, height: 48)
                .background(NodeScopeStyle.signal.opacity(0.13), in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text("Node Analytics")
                    .font(.monaspace(.headline))
                Text("Activity, signal, coverage, and peer trends")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
            }

            Spacer(minLength: 8)

            Image(systemName: "chevron.right")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(16)
        .instrumentCard()
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens historical analytics for this node")
    }
}

private struct NodeReachLoadingCard: View {
    var body: some View {
        NodeDetailCard(title: "Mesh Reach", symbol: "network") {
            HStack(spacing: 8) {
                ForEach(0..<3, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 7) {
                        Capsule()
                            .fill(NodeScopeStyle.signal.opacity(0.16))
                            .frame(width: 34, height: 14)
                        Capsule()
                            .fill(Color.secondary.opacity(0.12))
                            .frame(height: 9)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(NodeScopeStyle.signal.opacity(0.06), in: RoundedRectangle(cornerRadius: 12))
                }
            }

            HStack(spacing: 8) {
                MeshRouteActivityIndicator()
                Text("Measuring network reach")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Loading mesh reach")
    }
}

private struct NodeIdentityCard: View {
    let node: MeshNode
    let isCheckingWatch: Bool
    let isWatched: Bool
    let onWatch: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: NodeRoleStyle.symbolName(for: node.role))
                    .font(.monaspace(.title2).weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 50, height: 50)
                    .background(NodeRoleStyle.color(for: node.role), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(node.name ?? "Unnamed Node")
                        .font(.monaspace(.title2).bold())
                    HStack(spacing: 6) {
                        Text(NodeRoleStyle.label(for: node.role))
                            .font(.caption.weight(.bold))
                            .foregroundStyle(NodeRoleStyle.color(for: node.role))
                        if let roleTerm = GlossaryTerm(role: node.role) {
                            LearnButton(term: roleTerm)
                        }
                        if let lastSeen = node.lastSeen {
                            Text("· Seen \(RelativeTime.string(from: lastSeen))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        } else if let lastHeard = node.lastHeard {
                            Text("· Heard \(RelativeTime.string(from: lastHeard))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        if node.lastSeen != nil || node.lastHeard != nil {
                            LearnButton(term: .lastHeard)
                        }
                    }
                }
                Spacer(minLength: 0)

                if let onWatch {
                    Button(action: onWatch) {
                        Group {
                            if isCheckingWatch {
                                ProgressView().tint(isWatched ? .white : NodeScopeStyle.signal)
                            } else {
                                Image(systemName: isWatched ? "bell.fill" : "bell")
                                    .font(.body.weight(.semibold))
                            }
                        }
                        .frame(width: 44, height: 44)
                        .background(isWatched ? Color(red: 0.08, green: 0.41, blue: 0.27) : NodeScopeStyle.signal.opacity(0.13), in: Circle())
                        .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(isWatched ? Color.white : NodeScopeStyle.signal)
                    .disabled(isCheckingWatch)
                    .accessibilityLabel(isCheckingWatch ? "Checking watch status…" : isWatched ? "Stop watching" : "Watch node")
                }
            }

            Divider().opacity(0.35)

            VStack(alignment: .leading, spacing: 5) {
                Text("Public Key")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(node.publicKey.uppercased())
                    .font(.monaspace(.caption))
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack(spacing: 18) {
                NodeDateMetric(label: "First Seen", date: node.firstSeen)
                NodeDateMetric(label: "Last Seen", date: node.lastSeen)
            }
        }
        .padding(16)
        .instrumentCard()
    }
}

private struct NodeDateMetric: View {
    let label: LocalizedStringKey
    let date: Date?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            if let date {
                Text(date, format: .dateTime.month().day().year())
                    .font(.subheadline.weight(.semibold))
            } else {
                Text("Unknown")
                    .font(.subheadline.weight(.semibold))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct NodeHealthCard: View {
    let stats: NodeHealthStats

    var body: some View {
        NodeDetailCard(title: "Health", symbol: "waveform.path.ecg") {
            HStack(spacing: 8) {
                NodeMetricTile(value: stats.totalTransmissions.formatted(), label: "Transmissions")
                NodeMetricTile(value: stats.packetsToday.formatted(), label: "Today")
                NodeMetricTile(
                    value: stats.avgSnr.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? "—",
                    label: "Avg SNR",
                    learn: .snr
                )
            }

            HStack(spacing: 14) {
                Label("\(stats.totalObservations.formatted()) observations", systemImage: "ear.fill")
                if let avgHops = stats.avgHops {
                    Label("\(avgHops, specifier: "%.1f") avg hops", systemImage: "point.3.connected.trianglepath.dotted")
                }
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
        }
    }
}

private struct NodeMetricTile: View {
    let value: String
    let label: LocalizedStringKey
    var learn: GlossaryTerm?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value)
                .font(.monaspace(.headline))
            HStack(spacing: 2) {
                Text(label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                if let learn {
                    LearnButton(term: learn)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(NodeScopeStyle.signal.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Beacon's counterpart to the health card: observations, not transmissions, from
/// Beacon's hourly totals. Averages cover the seven days.
private struct BeaconNodeHealthCard: View {
    let health: BeaconNodeHealth

    var body: some View {
        NodeDetailCard(title: "Health", symbol: "waveform.path.ecg", learn: .observation) {
            HStack(spacing: 8) {
                NodeMetricTile(value: health.observationsDay?.formatted() ?? "—", label: "24 h")
                NodeMetricTile(value: health.observationsWeek?.formatted() ?? "—", label: "7 days")
                NodeMetricTile(
                    value: health.avgSnr.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? "—",
                    label: "Avg SNR",
                    learn: .snr
                )
            }

            if health.avgRssi != nil || health.avgHops != nil {
                HStack(spacing: 14) {
                    if let avgRssi = health.avgRssi {
                        Label("\(avgRssi, specifier: "%.0f") dBm avg RSSI", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    if let avgHops = health.avgHops {
                        Label("\(avgHops, specifier: "%.1f") avg hops", systemImage: "point.3.connected.trianglepath.dotted")
                    }
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            }

            if let lastHeard = health.lastHeard {
                Text("Last heard \(RelativeTime.string(from: lastHeard))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// Where a Beacon node was heard. Beacon reports regions, not observers.
private struct BeaconHeardInCard: View {
    let regions: [BeaconNodeHealth.HeardIn]
    @State private var isExpanded = false

    private var visibleRegions: ArraySlice<BeaconNodeHealth.HeardIn> {
        regions.prefix(isExpanded ? regions.count : 3)
    }

    var body: some View {
        NodeDetailCard(title: "Heard In", symbol: "map") {
            VStack(spacing: 0) {
                ForEach(visibleRegions) { region in
                    HStack(spacing: 10) {
                        Text(region.iata)
                            .font(.monaspace(.subheadline).weight(.semibold))
                            .frame(minWidth: 40, alignment: .leading)
                        if let name = region.name, name.uppercased() != region.iata {
                            Text(name)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        if let lastHeard = region.lastHeard {
                            Text(RelativeTime.string(from: lastHeard))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 9)
                    .accessibilityElement(children: .combine)
                    if region.id != visibleRegions.last?.id {
                        Divider().opacity(0.3)
                    }
                }
            }
            if regions.count > 3 {
                ExpandSectionButton(isExpanded: $isExpanded, totalCount: regions.count, noun: "regions")
            }
        }
    }
}

private struct NodeObserversCard: View {
    let observers: [NodeObserverStat]
    let openObserver: (String) -> Void
    @State private var isExpanded = false

    private var visibleObservers: ArraySlice<NodeObserverStat> {
        observers.prefix(isExpanded ? observers.count : 3)
    }

    var body: some View {
        NodeDetailCard(title: "Heard By", symbol: "ear.badge.waveform", learn: .observer) {
            VStack(spacing: 0) {
                ForEach(visibleObservers) { observer in
                    Button {
                        openObserver(observer.observerId)
                    } label: {
                        HStack(spacing: 10) {
                        Circle()
                            .fill(NodeScopeStyle.healthy.opacity(0.14))
                            .frame(width: 32, height: 32)
                            .overlay {
                                Image(systemName: "antenna.radiowaves.left.and.right")
                                    .font(.caption)
                                    .foregroundStyle(NodeScopeStyle.healthy)
                            }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(observer.observerName ?? observer.observerId)
                                .font(.monaspace(.subheadline).weight(.semibold))
                                .lineLimit(1)
                            HStack(spacing: 8) {
                                if let iata = observer.iata { Text(iata) }
                                if let avgSnr = observer.avgSnr { Text("\(avgSnr, specifier: "%.1f") dB") }
                            }
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("\(observer.packetCount) pkts")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(NodeScopeStyle.signal)
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .padding(.vertical, 9)
                    if observer.id != visibleObservers.last?.id {
                        Divider().opacity(0.3)
                    }
                }
            }
            if observers.count > 3 {
                ExpandSectionButton(isExpanded: $isExpanded, totalCount: observers.count, noun: "observers")
            }
        }
    }
}

private struct NodeReachCard: View {
    let reach: NodeReachResponse

    var body: some View {
        NodeDetailCard(title: "Mesh Reach", symbol: "network") {
            HStack(spacing: 8) {
                NodeMetricTile(value: reach.importance.neighborDegree.formatted(), label: "Neighbors")
                NodeMetricTile(value: reach.importance.bidirectionalLinks.formatted(), label: "Two-way Links")
                NodeMetricTile(value: reach.importance.directObservers.formatted(), label: "Observers")
            }
            Text("Measured across a \(reach.window.days)-day network window")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

private struct BeaconNodeReachCard: View {
    let neighborCount: Int

    var body: some View {
        NodeDetailCard(title: "Mesh Reach", symbol: "network") {
            HStack(spacing: 8) {
                NodeMetricTile(value: neighborCount.formatted(), label: "Neighbors")
                NodeMetricTile(value: "—", label: "Two-way Links")
                NodeMetricTile(value: "—", label: "Observers")
            }
            Text("Beacon reports nearby nodes, but not link direction or observer coverage.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

private struct NodeNeighborsCard: View {
    let neighbors: [NeighborInsight]
    let nodesByPublicKey: [String: MeshNode]
    let openNode: (MeshNode) -> Void
    @State private var isExpanded = false

    private var visibleNeighbors: ArraySlice<NeighborInsight> {
        neighbors.prefix(isExpanded ? neighbors.count : 3)
    }

    var body: some View {
        NodeDetailCard(title: "Links", symbol: "link") {
            VStack(spacing: 0) {
                ForEach(visibleNeighbors) { neighbor in
                    let linkedNode = neighbor.identity.linkablePublicKey.flatMap { nodesByPublicKey[$0] }
                    if let linkedNode {
                        Button {
                            openNode(linkedNode)
                        } label: {
                            NodeNeighborRow(
                                name: neighbor.name,
                                displayPrefix: neighbor.identity.displayPrefix,
                                snr: neighbor.signal.snr,
                                showsDisclosure: true
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Opens node details")
                    } else {
                        NodeNeighborRow(
                            name: neighbor.name,
                            displayPrefix: neighbor.identity.displayPrefix,
                            snr: neighbor.signal.snr,
                            showsDisclosure: false
                        )
                    }

                    if neighbor.id != visibleNeighbors.last?.id {
                        Divider().opacity(0.3)
                    }
                }
            }
            if neighbors.count > 3 {
                ExpandSectionButton(
                    isExpanded: $isExpanded,
                    totalCount: neighbors.count,
                    noun: "links"
                )
            }
        }
    }
}

private struct NodeNeighborsEmptyCard: View {
    var body: some View {
        NodeDetailCard(title: "Links", symbol: "link") {
            ContentUnavailableView(
                String(localized: "No Neighbors Reported"),
                systemImage: "point.3.connected.trianglepath.dotted",
                description: Text("Beacon has not recorded any neighboring nodes for this node yet.")
            )
            .frame(maxWidth: .infinity)
        }
    }
}

private struct NodeNeighborRow: View {
    let name: String?
    let displayPrefix: String?
    let snr: Double?
    let showsDisclosure: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.right.circle.fill")
                .foregroundStyle(NodeScopeStyle.activity)

            VStack(alignment: .leading, spacing: 2) {
                Text(name ?? displayPrefix ?? String(localized: "Unknown Node"))
                    .font(.monaspace(.subheadline).weight(.semibold))
                    .lineLimit(2)
                    .layoutPriority(1)
                if let snr {
                    Text("SNR \(snr, format: .number.precision(.fractionLength(1))) dB")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Signal unavailable")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            if showsDisclosure {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 9)
        .contentShape(Rectangle())
    }
}

private struct NodeLinksCard: View {
    let links: [ReachLink]
    let nodesByPublicKey: [String: MeshNode]
    let openNode: (MeshNode) -> Void
    @State private var isExpanded = false

    private var visibleLinks: ArraySlice<ReachLink> {
        links.prefix(isExpanded ? links.count : 3)
    }

    var body: some View {
        NodeDetailCard(title: "Links", symbol: "link") {
            VStack(spacing: 0) {
                ForEach(visibleLinks) { link in
                    if let linkedNode = nodesByPublicKey[link.pubkey.lowercased()] {
                        Button {
                            openNode(linkedNode)
                        } label: {
                            NodeLinkRow(link: link, showsDisclosure: true)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Opens node details")
                        .contextMenu {
                            copyPublicKeyButton(for: link)
                        }
                    } else {
                        NodeLinkRow(link: link, showsDisclosure: false)
                            .contextMenu {
                                copyPublicKeyButton(for: link)
                            }
                            .accessibilityHint("Touch and hold to copy the public key")
                    }

                    if link.id != visibleLinks.last?.id {
                        Divider().opacity(0.3)
                    }
                }
            }
            if links.count > 3 {
                ExpandSectionButton(isExpanded: $isExpanded, totalCount: links.count, noun: "links")
            }
        }
    }

    private func copyPublicKeyButton(for link: ReachLink) -> some View {
        Button {
            UIPasteboard.general.string = link.pubkey
        } label: {
            Label("Copy Public Key", systemImage: "doc.on.doc")
        }
    }
}

private struct NodeLinkRow: View {
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .localeDefault
    let link: ReachLink
    let showsDisclosure: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: link.bidir ? "arrow.left.arrow.right.circle.fill" : "arrow.right.circle.fill")
                .foregroundStyle(link.bidir ? NodeScopeStyle.healthy : NodeScopeStyle.activity)

            VStack(alignment: .leading, spacing: 2) {
                Text(link.name)
                    .font(.monaspace(.subheadline).weight(.semibold))
                    .lineLimit(2)
                    .layoutPriority(1)
                Text(link.bidir ? "Bidirectional" : "One way")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if let distanceKm = link.distanceKm {
                Text(distanceUnit.format(kilometers: distanceKm))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }

            if showsDisclosure {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 9)
        .contentShape(Rectangle())
    }
}

private struct NodePathsCard: View {
    let paths: [NodePath]
    let total: Int
    @State private var isExpanded = false

    private var visiblePaths: ArraySlice<NodePath> {
        paths.prefix(isExpanded ? paths.count : 3)
    }

    var body: some View {
        NodeDetailCard(title: "Known Paths", symbol: "point.topleft.down.curvedto.point.bottomright.up") {
            Text("\(total) resolved routes")
                .font(.caption)
                .foregroundStyle(.secondary)

            VStack(spacing: 0) {
                ForEach(visiblePaths) { path in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(path.hops.map(\.name).joined(separator: " → "))
                            .font(.monaspace(.subheadline).weight(.medium))
                            .fixedSize(horizontal: false, vertical: true)
                        HStack(spacing: 10) {
                            Label("\(path.hops.count) hops", systemImage: "point.3.connected.trianglepath.dotted")
                            Text("Seen \(path.count)×")
                        }
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(NodeScopeStyle.signal)
                    }
                    .padding(.vertical, 10)
                    if path.id != visiblePaths.last?.id { Divider().opacity(0.3) }
                }
            }
            if paths.count > 3 {
                ExpandSectionButton(isExpanded: $isExpanded, totalCount: paths.count, noun: "paths")
            }
        }
    }
}

private struct ExpandSectionButton: View {
    @Binding var isExpanded: Bool
    let totalCount: Int
    let noun: String

    var body: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.22)) {
                isExpanded.toggle()
            }
        } label: {
            HStack {
                Text(isExpanded ? "Show less" : "Show all \(totalCount) \(noun)")
                Spacer()
                Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
            }
            .font(.monaspace(.caption).weight(.bold))
            .foregroundStyle(NodeScopeStyle.signal)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(NodeScopeStyle.signal.opacity(0.09), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

private struct NodeDetailCard<Content: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    let learn: GlossaryTerm?
    @ViewBuilder let content: Content

    init(title: LocalizedStringKey, symbol: String, learn: GlossaryTerm? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.symbol = symbol
        self.learn = learn
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 4) {
                Label(title, systemImage: symbol)
                    .font(.monaspace(.headline))
                    .foregroundStyle(NodeScopeStyle.signal)
                if let learn {
                    LearnButton(term: learn)
                }
            }
            content
        }
        .padding(16)
        .instrumentCard()
    }
}

extension MeshCoreContactLink: Identifiable {
    var id: String { urlString }
}

/// A QR code plus open/copy/share actions for a node's MeshCore contact link.
/// The QR is the path that works everywhere: the MeshCore app opens the link
/// directly, and other MeshCore apps can scan or paste it.
private struct MeshCoreContactSheet: View {
    let contact: MeshCoreContactLink
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var openFailed = false
    @State private var copied = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if let image = MeshCoreContactSheet.qrImage(for: contact.urlString) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .padding(14)
                            .background(.white, in: RoundedRectangle(cornerRadius: 16))
                            .frame(maxWidth: 280)
                            .accessibilityLabel("QR code for adding \(contact.name) as a MeshCore contact")
                    }

                    VStack(spacing: 4) {
                        Text(contact.name)
                            .font(.monaspace(.headline))
                        Text(typeLabel)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    VStack(spacing: 10) {
                        if let url = contact.url {
                            Button {
                                openURL(url) { accepted in
                                    openFailed = !accepted
                                }
                            } label: {
                                Label("Open in MeshCore", systemImage: "arrow.up.forward.app")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                        }

                        Button {
                            UIPasteboard.general.string = contact.urlString
                            copied = true
                        } label: {
                            Label(copied ? "Copied" : "Copy Contact Link",
                                  systemImage: copied ? "checkmark" : "doc.on.doc")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(StandaloneActionButtonStyle())

                        ShareLink(item: contact.urlString) {
                            Label("Share Contact Link", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(StandaloneActionButtonStyle())
                    }
                    .controlSize(.large)

                    if openFailed {
                        Text("No installed app opened MeshCore links. Scan the code from your MeshCore app, or copy the link and import it there.")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                            .multilineTextAlignment(.center)
                    }

                    Text("Scan this code from your MeshCore app\u{2019}s add-contact screen on another phone, or open it directly on this one. Other MeshCore apps can import the copied link or the code.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(20)
                .frame(maxWidth: .infinity)
            }
            .meshPageBackground()
            .navigationTitle("Add to MeshCore")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
    }

    private var typeLabel: String {
        switch contact.type {
        case 1: String(localized: "Companion")
        case 2: String(localized: "Repeater")
        case 3: String(localized: "Room Server")
        default: String(localized: "Sensor")
        }
    }

    static func qrImage(for text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?
            .transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}


private struct RepeaterMetadataCard: View {
    let metadata: RepeaterMetadata
    let openCoreScope: (() -> Void)?
    @State private var expanded = false
    @State private var showsInfo = false
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .localeDefault

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 4) {
                Button {
                    withAnimation { expanded.toggle() }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Reported Details").font(.monaspace(.headline))
                            if let status = metadata.status {
                                Text(statusTitle(status)).foregroundStyle(.secondary)
                            }
                        }
                        .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 8)
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .foregroundStyle(.secondary)
                            .accessibilityHidden(true)
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityValue(expanded ? Text("Expanded") : Text("Collapsed"))

                Button {
                    showsInfo = true
                } label: {
                    Image(systemName: "info.circle")
                        .foregroundStyle(.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("About Reported Details")
            }

            if expanded {
                VStack(alignment: .leading, spacing: 8) {
                    if let power = metadata.power {
                        ReportedDetailRow("Power source") {
                            Text(powerTitle(power))
                        }
                    }
                    if let height = metadata.heightM {
                        ReportedDetailRow("Reported antenna height") {
                            let value = distanceUnit.displayHeight(meters: height).formatted(.number.precision(.fractionLength(0...3)))
                            Text("\(value) \(distanceUnit.heightSymbol)").font(.monaspace(.subheadline))
                        }
                    }
                    if let gain = metadata.gainDbi {
                        ReportedDetailRow("Antenna gain") {
                            if gain == 0 {
                                Text("0 dBi (server default or reported)")
                            } else {
                                Text("\(gain.formatted()) dBi").font(.monaspace(.subheadline))
                            }
                        }
                    }
                    ForEach(Array(metadata.claimers.enumerated()), id: \.offset) { _, claim in
                        ReportedDetailRow("Claimed by") {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: claim.displayName)
                                if let date = claim.claimedAt {
                                    Text(date, format: .dateTime).font(.caption)
                                }
                            }
                        }
                    }
                    if let editor = metadata.updatedBy {
                        ReportedDetailRow("Last edited by") { Text(verbatim: editor) }
                    }
                    if let date = metadata.updatedAt {
                        ReportedDetailRow("Details updated") { Text(date, format: .dateTime) }
                    }
                    if let notes = metadata.notes {
                        Text("Notes").font(.monaspace(.subheadline))
                        Text(verbatim: notes)
                            .textSelection(.enabled)
                    }
                }
                .padding(.top, 8)
            }
        }
        .font(.subheadline)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .instrumentCard()
        .alert("About Reported Details", isPresented: $showsInfo) {
            if let openCoreScope {
                Button("Open CoreScope", action: openCoreScope)
            }
            Button("Done", role: .cancel) {}
        } message: {
            Text("These details come from community reports on this CoreScope analyzer. A claim means someone says they operate the repeater. Reported status reflects what someone entered. Last heard reflects activity the analyzer observed.")
        }
    }

    private func statusTitle(_ status: String) -> LocalizedStringKey {
        switch status {
        case "active": "Reported active"
        case "maintenance": "Reported maintenance"
        default: "Reported decommissioned"
        }
    }

    private func powerTitle(_ power: String) -> LocalizedStringKey {
        switch power {
        case "mains": "Mains"
        case "battery": "Battery"
        default: "Solar"
        }
    }
}


/// Keeps short label-value pairs aligned and gives long values the full card width.
private struct ReportedDetailRow<Value: View>: View {
    let label: LocalizedStringKey
    let value: Value

    init(_ label: LocalizedStringKey, @ViewBuilder value: () -> Value) {
        self.label = label
        self.value = value()
    }

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(label).fixedSize()
                Spacer(minLength: 0)
                value
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(.secondary)
                    .fixedSize()
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(label)
                value.foregroundStyle(.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct BrowserLink: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

private struct InAppBrowser: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}
