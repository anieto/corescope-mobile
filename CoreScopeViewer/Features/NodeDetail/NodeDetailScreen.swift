import SwiftUI

struct NodeDetailScreen: View {
    let node: MeshNode
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favoritesStore
    @State private var viewModel = NodeDetailViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if viewModel.isLoading {
                    LoadingIndicator(title: "Loading node data…")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                DataLoadStatusView(
                    lastUpdatedAt: viewModel.lastUpdatedAt,
                    errorMessage: viewModel.errorMessage,
                    hasContent: viewModel.health != nil || viewModel.paths != nil || viewModel.reach != nil,
                    retry: retryNodeData
                )

                NodeIdentityCard(node: node)

                if let reach = viewModel.reach {
                    NodeReachCard(reach: reach)
                } else if viewModel.isLoading {
                    NodeReachLoadingCard()
                }

                if let health = viewModel.health {
                    NodeHealthCard(stats: health.stats)
                    if !health.observers.isEmpty {
                        NodeObserversCard(observers: health.observers)
                    }
                }

                if let reach = viewModel.reach {
                    if !reach.links.isEmpty {
                        NodeLinksCard(links: reach.links)
                    }
                }

                if let paths = viewModel.paths, !paths.paths.isEmpty {
                    NodePathsCard(paths: paths.paths, total: paths.totalPaths)
                }
            }
            .padding(16)
            .padding(.bottom, 104)
            .adaptiveContentWidth()
        }
        .background(NodeScopeBackground())
        .navigationTitle(node.name ?? "Node")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                favoriteButton
            }
        }
        .task {
            viewModel.configure(settings: settings)
            await viewModel.load(pubkey: node.publicKey)
        }
    }

    private var favoriteButton: some View {
        let isFavorite = favoritesStore.contains(
            kind: .node,
            entityID: node.publicKey,
            source: favoriteSource
        )
        return Button {
            favoritesStore.toggle(node: node, source: favoriteSource)
        } label: {
            Image(systemName: isFavorite ? "star.fill" : "star")
        }
        .tint(NodeScopeStyle.signal)
        .accessibilityLabel(isFavorite ? "Remove node from favorites" : "Add node to favorites")
    }

    private var favoriteSource: String {
        AnalyzerSettings.normalizedHost(settings.host)
    }

    private func retryNodeData() {
        Task {
            await viewModel.load(pubkey: node.publicKey)
        }
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

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: NodeRoleStyle.symbolName(for: node.role))
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 50, height: 50)
                    .background(NodeRoleStyle.color(for: node.role), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(node.name ?? "Unnamed Node")
                        .font(.title2.bold())
                    HStack(spacing: 6) {
                        Text(node.role.capitalized)
                            .font(.caption.weight(.bold))
                            .foregroundStyle(NodeRoleStyle.color(for: node.role))
                        Text("· Seen \(RelativeTime.string(from: node.lastSeen))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Divider().opacity(0.35)

            VStack(alignment: .leading, spacing: 5) {
                Text("Public Key")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(node.publicKey.uppercased())
                    .font(.caption.monospaced())
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
    let date: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(date, format: .dateTime.month().day().year())
                .font(.subheadline.weight(.semibold))
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
                    value: stats.avgSnr.map { String(format: "%.1f", $0) } ?? "—",
                    label: "Avg SNR"
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

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value)
                .font(.headline.monospacedDigit())
                .minimumScaleFactor(0.75)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(NodeScopeStyle.signal.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct NodeObserversCard: View {
    let observers: [NodeObserverStat]
    @State private var isExpanded = false

    private var visibleObservers: ArraySlice<NodeObserverStat> {
        observers.prefix(isExpanded ? observers.count : 3)
    }

    var body: some View {
        NodeDetailCard(title: "Heard By", symbol: "ear.badge.waveform") {
            VStack(spacing: 0) {
                ForEach(visibleObservers) { observer in
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
                                .font(.subheadline.weight(.semibold))
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
                    }
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

private struct NodeLinksCard: View {
    let links: [ReachLink]
    @State private var isExpanded = false

    private var visibleLinks: ArraySlice<ReachLink> {
        links.prefix(isExpanded ? links.count : 3)
    }

    var body: some View {
        NodeDetailCard(title: "Links", symbol: "link") {
            VStack(spacing: 0) {
                ForEach(visibleLinks) { link in
                    HStack(spacing: 10) {
                        Image(systemName: link.bidir ? "arrow.left.arrow.right.circle.fill" : "arrow.right.circle.fill")
                            .foregroundStyle(link.bidir ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(link.name)
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(2)
                                .layoutPriority(1)
                            Text(link.bidir ? "Bidirectional" : "One way")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if let distanceKm = link.distanceKm {
                            Text("\(distanceKm, specifier: "%.1f") km")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 9)
                    if link.id != visibleLinks.last?.id { Divider().opacity(0.3) }
                }
            }
            if links.count > 3 {
                ExpandSectionButton(isExpanded: $isExpanded, totalCount: links.count, noun: "links")
            }
        }
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
            Text("\(total) resolved route\(total == 1 ? "" : "s")")
                .font(.caption)
                .foregroundStyle(.secondary)

            VStack(spacing: 0) {
                ForEach(visiblePaths) { path in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(path.hops.map(\.name).joined(separator: " → "))
                            .font(.subheadline.weight(.medium))
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
            .font(.caption.weight(.bold))
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
    @ViewBuilder let content: Content

    init(title: LocalizedStringKey, symbol: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.symbol = symbol
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(title, systemImage: symbol)
                .font(.headline)
                .foregroundStyle(NodeScopeStyle.signal)
            content
        }
        .padding(16)
        .instrumentCard()
    }
}
