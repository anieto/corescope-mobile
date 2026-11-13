import MapKit
import Observation
import SwiftUI

// MARK: - Model

@Observable
@MainActor
final class NodeCheckModel {
    enum State {
        case idle
        case loading
        case choose(query: String, matches: [MeshNode])
        case report(NodeCheckReport)
        /// The analyzer has never heard this node. A full key can still be watched for.
        case notFound(query: String, fullKey: String?)
        case failed(String)
        case unsupported(String)
    }

    private(set) var state: State = .idle
    /// Observer id → observer, for regions and map positions.
    private(set) var observers: [String: MeshObserver] = [:]
    private var backend: (any AnalyzerBackend)?
    private var loadID = UUID()

    var observerRegions: [String: String] {
        observers.compactMapValues { $0.iata?.isEmpty == false ? $0.iata : nil }
    }

    func configure(settings: AnalyzerSettings) {
        let backend = AnalyzerBackendFactory.make(settings: settings)
        let changed = self.backend?.sourceSession.id != backend.sourceSession.id
        self.backend = backend
        if !backend.capabilities.contains(.nodeCheck) {
            state = .unsupported(BackendCapability.nodeCheck.beaconExplanation)
        } else if changed {
            state = .idle
            observers = [:]
        }
    }

    /// The node whose report is showing, for Check Again.
    var shownKey: String? {
        if case .report(let report) = state { return report.node.publicKey }
        return nil
    }

    var isSupported: Bool {
        if case .unsupported = state { return false }
        return true
    }

    func loadObservers() async {
        guard let backend, observers.isEmpty, isSupported else { return }
        guard let response = try? await backend.observers() else { return }
        observers = Dictionary(response.observers.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    /// A name, a key (8 or more hex characters), or a contact link shared from the MeshCore app.
    func check(_ rawQuery: String) async {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty, isSupported else { return }
        if let key = Self.key(in: query) {
            await check(key: key)
        } else {
            await search(name: String(query.prefix(64)))
        }
    }

    func check(key: String) async {
        guard let backend, isSupported else { return }
        let id = UUID()
        loadID = id
        state = .loading
        do {
            let input = try await backend.nodeCheckInput(publicKey: key.lowercased())
            guard loadID == id else { return }
            if let input {
                state = .report(NodeCheckRules.report(for: input))
            } else {
                state = .notFound(query: key, fullKey: key.count == 64 ? key.lowercased() : nil)
            }
        } catch {
            guard loadID == id else { return }
            state = .failed(error.localizedDescription)
        }
    }

    private func search(name: String) async {
        guard let backend else { return }
        let id = UUID()
        loadID = id
        state = .loading
        do {
            let found = try await backend.searchNodes(query: name)
            guard loadID == id else { return }
            let exact = found.filter { ($0.name ?? "").caseInsensitiveCompare(name) == .orderedSame }
            let matches = exact.isEmpty ? found : exact
            switch matches.count {
            case 0: state = .notFound(query: name, fullKey: nil)
            case 1: await check(key: matches[0].publicKey)
            default: state = .choose(query: name, matches: matches)
            }
        } catch {
            guard loadID == id else { return }
            state = .failed(error.localizedDescription)
        }
    }

    static func key(in text: String) -> String? {
        if let range = text.range(of: #"public_key=([0-9A-Fa-f]{64})"#, options: .regularExpression) {
            return String(text[range].dropFirst("public_key=".count)).lowercased()
        }
        let compact = text.filter { !$0.isWhitespace }
        guard (8...64).contains(compact.count), compact.allSatisfy(\.isHexDigit) else { return nil }
        return compact.lowercased()
    }
}

// MARK: - Screen

/// Node Check: is a node getting out? Opened from Explore (any node, including one
/// the analyzer has never heard) or from a node's page.
struct NodeCheckScreen: View {
    var initialNode: MeshNode? = nil
    /// Hides "Open node page" when the check was opened from that page.
    var openedFromNodePage = false

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favorites
    @State private var model = NodeCheckModel()
    @State private var query = ""
    @FocusState private var searchFocused: Bool

    private var savedNodes: [FavoriteItem] {
        favorites.items.filter {
            $0.source == AnalyzerSettings.normalizedHost(settings.host) && $0.kind == .node
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if initialNode == nil, model.isSupported {
                    searchCard
                }
                content
            }
            .padding(16)
            .adaptiveContentWidth()
        }
        .scrollDismissesKeyboard(.interactively)
        .meshPageBackground()
        .navigationTitle("Node Check")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            if let key = model.shownKey { await model.check(key: key) }
        }
        .toolbar {
            if let key = model.shownKey {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await model.check(key: key) }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("Check Again")
                }
            }
        }
        .task(id: settings.sourceSession.id) {
            model.configure(settings: settings)
            if let initialNode {
                await model.check(key: initialNode.publicKey)
            }
            await model.loadObservers()
        }
    }

    private var searchCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Is your node getting out? See which observers hear it, and what to fix if something looks wrong.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .wrapsFully()
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                TextField("Node name, public key, or contact link", text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .focused($searchFocused)
                    .onSubmit(runSearch)
                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Clear")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))

            Button(action: runSearch) {
                Label("Check Node", systemImage: "checkmark.seal")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(query.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(16)
        .instrumentCard()
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .idle where initialNode != nil, .loading:
            LoadingIndicator(title: String(localized: "Checking…"))
                .frame(maxWidth: .infinity, alignment: .leading)
        case .idle:
            if !savedNodes.isEmpty {
                savedNodesCard
            }
            NodeCheckHelpCard()
        case .choose(let name, let matches):
            chooseCard(name: name, matches: matches)
        case .report(let report):
            NodeCheckReportView(
                report: report,
                observers: model.observers,
                showsNodeLink: !openedFromNodePage
            )
            .id(report.node.publicKey)
        case .notFound(let name, let fullKey):
            notFoundView(query: name, fullKey: fullKey)
        case .failed(let message):
            NodeCheckMessageCard(level: .warn, title: String(localized: "Couldn’t check this node"), message: message) {
                Button(action: retry) {
                    Label("Try Again", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        case .unsupported(let message):
            NodeCheckMessageCard(level: .info, title: String(localized: "Not available on this source"), message: message)
        }
    }

    private var savedNodesCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Your nodes")
                .font(.monaspace(.headline))
            ForEach(savedNodes) { item in
                Button {
                    query = item.title
                    Task { await model.check(key: item.entityID) }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: NodeRoleStyle.symbolName(for: item.node?.role ?? "unknown"))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .frame(width: 30, height: 30)
                            .background(NodeRoleStyle.color(for: item.node?.role ?? "unknown"), in: Circle())
                        Text(item.title)
                            .font(.monaspace(.subheadline))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .instrumentCard()
    }

    private func chooseCard(name: String, matches: [MeshNode]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Which node is yours?")
                .font(.monaspace(.headline))
            Text("More than one node matches “\(name)”. Pick yours, or enter its public key.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .wrapsFully()
            ForEach(matches) { node in
                Button {
                    Task { await model.check(key: node.publicKey) }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: NodeRoleStyle.symbolName(for: node.role))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .frame(width: 30, height: 30)
                            .background(NodeRoleStyle.color(for: node.role), in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(node.name ?? String(localized: "Unnamed node"))
                                .font(.monaspace(.subheadline).weight(.semibold))
                                .foregroundStyle(.primary)
                            HStack(spacing: 6) {
                                Text(node.role.capitalized)
                                Text(node.publicKey.prefix(8).uppercased()).font(.monaspace(.caption))
                                if let heard = node.latestActivity {
                                    Text("heard \(NodeCheckRules.ago(heard))")
                                }
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(10)
                    .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .instrumentCard()
    }

    @ViewBuilder
    private func notFoundView(query: String, fullKey: String?) -> some View {
        NodeCheckMessageCard(
            level: .bad,
            title: String(localized: "We haven’t heard this node"),
            message: fullKey == nil
                ? String(localized: "No observer on this analyzer has picked it up. To test a new node, enter its full public key; you can then run a live test and watch for its first advert.")
                : String(localized: "No observer on this analyzer has picked it up yet. Run a live test, send a flood advert, and watch for its first one. If nothing arrives, check that the radio uses the same settings as other nodes in your area and that a repeater is in range.")
        )
        if let fullKey {
            NodeCheckLiveTestCard(
                publicKey: fullKey,
                nodeName: nil,
                observers: model.observers,
                startsImmediately: false,
                onHeard: { Task { await model.check(key: fullKey) } },
                close: nil
            )
            .id(fullKey)
        }
    }

    private func retry() {
        if let initialNode {
            Task { await model.check(key: initialNode.publicKey) }
        } else {
            runSearch()
        }
    }

    private func runSearch() {
        searchFocused = false
        Task { await model.check(query) }
    }
}

// MARK: - Report

private struct NodeCheckReportView: View {
    let report: NodeCheckReport
    let observers: [String: MeshObserver]
    let showsNodeLink: Bool
    @State private var showsLiveTest = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            NodeCheckSummaryCard(
                report: report,
                showsNodeLink: showsNodeLink,
                startLiveTest: { withAnimation { showsLiveTest = true } }
            )
            if showsLiveTest {
                NodeCheckLiveTestCard(
                    publicKey: report.node.publicKey,
                    nodeName: report.node.name,
                    observers: observers,
                    startsImmediately: true,
                    onHeard: nil,
                    close: { withAnimation { showsLiveTest = false } }
                )
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            NodeCheckFindingsCard(findings: report.findings)
            if let advert = report.latestAdvert, !advert.observers.isEmpty {
                NodeCheckObserversCard(
                    title: advert.kind == "flood" ? "Who heard its last flood advert" : "Who heard its last advert",
                    subtitle: advert.sentAt.map { String(localized: "Sent \(NodeCheckRules.ago($0)). Closest first; Direct means no repeater in between.") }
                        ?? String(localized: "Closest first; Direct means no repeater in between."),
                    observers: advert.observers,
                    showsPackets: false
                )
            }
            if !report.direct.isEmpty {
                NodeCheckObserversCard(
                    title: "Observers that hear it directly",
                    subtitle: String(localized: "Average signal of everything they’ve heard straight from this node."),
                    observers: report.direct,
                    showsPackets: true
                )
            }
        }
    }
}

private struct NodeCheckSummaryCard: View {
    let report: NodeCheckReport
    let showsNodeLink: Bool
    let startLiveTest: () -> Void

    private var node: NodeCheckInput.Node { report.node }

    private var meshNode: MeshNode {
        MeshNode(publicKey: node.publicKey, name: node.name, role: node.role, lat: node.lat, lon: node.lon,
                 lastSeen: node.lastSeen, firstSeen: node.firstSeen, advertCount: nil, hashSize: nil,
                 hashSizeInconsistent: nil, lastHeard: node.lastHeard, defaultScope: nil)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: NodeRoleStyle.symbolName(for: node.role))
                    .font(.monaspace(.title3).weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(NodeRoleStyle.color(for: node.role), in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(node.name ?? String(localized: "Unnamed node"))
                        .font(.monaspace(.title3).bold())
                        .wrapsFully()
                    HStack(spacing: 6) {
                        Text(node.role.capitalized)
                            .font(.caption.weight(.bold))
                            .foregroundStyle(NodeRoleStyle.color(for: node.role))
                        Text(node.publicKey.prefix(16).uppercased() + "…")
                            .font(.monaspace(.caption))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            NodeCheckStatusBadge(level: report.level, text: report.headline)

            NodeCheckStatGrid(stats: [
                .init(label: "Last heard", value: report.lastHeard.map { NodeCheckRules.ago($0) } ?? "—",
                      note: report.lastHeard.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? String(localized: "Not heard")),
                .init(label: "Reached", value: report.latestAdvert.map { "\($0.observers.count)" } ?? "—",
                      note: report.latestAdvert.map {
                          $0.observers.count == 1 ? String(localized: "observer, last advert") : String(localized: "observers, last advert")
                      } ?? String(localized: "No recent advert")),
                .init(label: "Regions", value: report.latestAdvert.map { "\($0.regions.count)" } ?? "—",
                      note: report.latestAdvert.map { $0.regions.joined(separator: ", ") }.flatMap { $0.isEmpty ? nil : $0 } ?? String(localized: "None yet")),
                .init(label: "Nearest", value: NodeCheckStatGrid.hops(report.latestAdvert?.observers.first?.hops),
                      note: report.direct.isEmpty ? String(localized: "None hear it directly")
                        : report.direct.count == 1 ? String(localized: "1 hears it directly")
                        : String(localized: "\(report.direct.count) hear it directly")),
            ])

            VStack(spacing: 10) {
                Button(action: startLiveTest) {
                    Label("Run Live Test", systemImage: "dot.radiowaves.left.and.right")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(NodeScopeStyle.activity)

                if showsNodeLink {
                    NavigationLink {
                        NodeDetailScreen(node: meshNode)
                    } label: {
                        Label("Open Node Page", systemImage: "info.circle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(16)
        .instrumentCard()
    }
}

struct NodeCheckStatusBadge: View {
    let level: NodeCheckReport.Level
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(level.color).frame(width: 9, height: 9)
            Text(text)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(level.color)
                .wrapsFully()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(level.color.opacity(0.14), in: Capsule())
        .accessibilityElement(children: .combine)
    }
}

extension NodeCheckReport.Level {
    /// Traffic-light scale, matching meshtexas.org: green good, amber check, red problem.
    var color: Color {
        switch self {
        case .good: NodeScopeStyle.healthy
        case .warn: NodeScopeStyle.activity
        case .bad: .red
        case .info: NodeScopeStyle.signal
        }
    }

    var symbol: String {
        switch self {
        case .good: "checkmark"
        case .warn: "exclamationmark"
        case .bad: "xmark"
        case .info: "info"
        }
    }
}

private struct NodeCheckStatGrid: View {
    struct Stat: Identifiable {
        let label: LocalizedStringKey
        let value: String
        let note: String
        var id: String { "\(label)" }
    }

    let stats: [Stat]

    static func hops(_ hops: Int?) -> String {
        guard let hops else { return "—" }
        if hops == 0 { return String(localized: "Direct") }
        return hops == 1 ? String(localized: "1 hop") : String(localized: "\(hops) hops")
    }

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(stats) { stat in
                VStack(alignment: .leading, spacing: 3) {
                    Text(stat.label)
                        .font(.caption2.weight(.semibold))
                        .textCase(.uppercase)
                        .foregroundStyle(.secondary)
                    Text(stat.value)
                        .font(.monaspace(.headline))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Text(stat.note)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .wrapsFully()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityElement(children: .combine)
            }
        }
    }
}

private struct NodeCheckFindingsCard: View {
    let findings: [NodeCheckReport.Finding]

    private var groups: [(LocalizedStringKey, [NodeCheckReport.Finding])] {
        [
            ("Needs attention", findings.filter { $0.level == .bad || $0.level == .warn }),
            ("Looking good", findings.filter { $0.level == .good }),
            ("Good to know", findings.filter { $0.level == .info }),
        ].filter { !$0.1.isEmpty }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(Array(groups.enumerated()), id: \.offset) { index, group in
                if index > 0 { Divider() }
                VStack(alignment: .leading, spacing: 12) {
                    Text(group.0)
                        .font(.caption.weight(.semibold))
                        .textCase(.uppercase)
                        .foregroundStyle(.secondary)
                    ForEach(group.1) { finding in
                        NodeCheckFindingRow(finding: finding)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .instrumentCard()
    }
}

private struct NodeCheckFindingRow: View {
    let finding: NodeCheckReport.Finding

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: finding.level.symbol)
                .font(.caption.weight(.bold))
                .foregroundStyle(finding.level.color)
                .frame(width: 26, height: 26)
                .background(finding.level.color.opacity(0.15), in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(finding.title)
                    .font(.subheadline.weight(.semibold))
                    .wrapsFully()
                Text(finding.detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .wrapsFully()
                if let fix = finding.fix {
                    (Text("What to do: ").bold() + Text(fix))
                        .font(.subheadline)
                        .wrapsFully()
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                        .padding(.top, 4)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Observers grouped by region, the region with the closest observer first, five rows each.
struct NodeCheckObserversCard: View {
    let title: LocalizedStringKey
    let subtitle: String?
    let observers: [NodeCheckReport.HeardObserver]
    let showsPackets: Bool
    var embedded = false
    @State private var expanded: Set<String> = []

    private static let rowsPerRegion = 5

    private var groups: [(region: String, observers: [NodeCheckReport.HeardObserver])] {
        let grouped = Dictionary(grouping: observers) { $0.region ?? String(localized: "Unknown") }
        return grouped.map { ($0.key, $0.value) }.sorted { lhs, rhs in
            let left = lhs.observers.map { $0.hops ?? 99 }.min() ?? 99
            let right = rhs.observers.map { $0.hops ?? 99 }.min() ?? 99
            if left != right { return left < right }
            if lhs.observers.count != rhs.observers.count { return lhs.observers.count > rhs.observers.count }
            return lhs.region < rhs.region
        }
    }

    var body: some View {
        let content = VStack(alignment: .leading, spacing: 14) {
            if !embedded {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.monaspace(.headline)).wrapsFully()
                    if let subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(.secondary).wrapsFully()
                    }
                }
            }
            ForEach(groups, id: \.region) { group in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Text(group.region)
                            .font(.monaspace(.caption).weight(.semibold))
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(Color.secondary.opacity(0.14), in: RoundedRectangle(cornerRadius: 6))
                        Text(group.observers.count == 1 ? String(localized: "1 observer") : String(localized: "\(group.observers.count) observers"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    let showsAll = expanded.contains(group.region)
                    let rows = showsAll ? group.observers : Array(group.observers.prefix(Self.rowsPerRegion))
                    ForEach(rows) { observer in
                        NodeCheckObserverRow(observer: observer, showsPackets: showsPackets)
                        if observer.id != rows.last?.id { Divider() }
                    }
                    let hidden = group.observers.count - Self.rowsPerRegion
                    if hidden > 0, !showsAll {
                        Button(String(localized: "Show \(hidden) more in \(group.region)")) {
                            withAnimation { _ = expanded.insert(group.region) }
                        }
                        .font(.subheadline)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)

        if embedded {
            content
        } else {
            content.padding(16).instrumentCard()
        }
    }
}

private struct NodeCheckObserverRow: View {
    let observer: NodeCheckReport.HeardObserver
    let showsPackets: Bool

    private var tone: NodeCheckReport.Level {
        guard let snr = observer.snr else { return .info }
        return snr >= 5 ? .good : snr >= -5 ? .warn : .bad
    }

    var body: some View {
        HStack(spacing: 10) {
            Text(observer.name)
                .font(.monaspace(.subheadline))
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            badge
            VStack(alignment: .trailing, spacing: 3) {
                HStack(spacing: 6) {
                    Text(observer.snr.map { NodeCheckRules.signedDecibels($0) } ?? "—")
                    if let rssi = observer.rssi {
                        Text("\(Int(rssi)) dBm").foregroundStyle(.secondary)
                    }
                }
                .font(.monaspace(.caption))
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.secondary.opacity(0.15))
                        Capsule().fill(tone.color).frame(width: proxy.size.width * fill)
                    }
                }
                .frame(width: 96, height: 4)
                .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    /// SNR from -15 dB (barely decodable) to +12 dB (strong) fills the bar.
    private var fill: Double {
        guard let snr = observer.snr else { return 0 }
        return min(1, max(0.04, (snr + 15) / 27))
    }

    @ViewBuilder
    private var badge: some View {
        let text: String = if showsPackets {
            observer.packets.map { String(localized: "\($0) pkts") } ?? "—"
        } else {
            NodeCheckStatGrid.hops(observer.hops)
        }
        let direct = !showsPackets && observer.hops == 0
        Text(text)
            .font(.caption.weight(.medium))
            .foregroundStyle(direct ? NodeScopeStyle.healthy : .secondary)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background((direct ? NodeScopeStyle.healthy : Color.secondary).opacity(0.14), in: Capsule())
    }
}

// MARK: - Live test

struct NodeCheckLiveTestCard: View {
    let publicKey: String
    let nodeName: String?
    let observers: [String: MeshObserver]
    let startsImmediately: Bool
    /// Called the first time a node the analyzer had never heard is heard.
    let onHeard: (() -> Void)?
    let close: (() -> Void)?

    @Environment(LiveFeedService.self) private var liveFeed
    @State private var test: NodeCheckLiveTest
    @State private var started = false

    init(publicKey: String, nodeName: String?, observers: [String: MeshObserver], startsImmediately: Bool,
         onHeard: (() -> Void)?, close: (() -> Void)?) {
        self.publicKey = publicKey
        self.nodeName = nodeName
        self.observers = observers
        self.startsImmediately = startsImmediately
        self.onHeard = onHeard
        self.close = close
        _test = State(initialValue: NodeCheckLiveTest(publicKey: publicKey))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Live test: did anyone hear me?")
                .font(.monaspace(.headline))
                .wrapsFully()

            switch test.phase {
            case .idle:
                intro(test)
            case .listening, .finished:
                progress(test)
                if test.hasHeard {
                    results(test)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .instrumentCard()
        .onAppear {
            guard !started else { return }
            started = true
            if startsImmediately { begin(test) }
        }
        .onDisappear { test.reset() }
        .onChange(of: liveFeed.eventSequence) {
            let before = test.hasHeard
            test.ingest(liveFeed.recentEvents)
            if !before, test.hasHeard { onHeard?() }
        }
    }

    private func begin(_ test: NodeCheckLiveTest) {
        test.start(observerRegions: observers.compactMapValues { $0.iata?.isEmpty == false ? $0.iata : nil })
    }

    @ViewBuilder
    private func intro(_ test: NodeCheckLiveTest) -> some View {
        Text("Send a flood advert from \(nodeName ?? String(localized: "your node")) and watch which observers report it, as it happens.")
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .wrapsFully()
        VStack(alignment: .leading, spacing: 6) {
            step(1, "Start the test below.")
            step(2, "Send a flood advert. In the MeshCore app, send an advert and choose Flood. On a repeater or room server, run the advert command.")
            step(3, "Watch for up to two minutes as observers report it.")
        }
        Button {
            begin(test)
        } label: {
            Label("Start Live Test", systemImage: "dot.radiowaves.left.and.right")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(NodeScopeStyle.activity)
    }

    private func step(_ number: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("\(number).").font(.monaspace(.subheadline).weight(.semibold))
            Text(text).font(.subheadline).wrapsFully()
        }
    }

    @ViewBuilder
    private func progress(_ test: NodeCheckLiveTest) -> some View {
        let listening = test.phase == .listening
        HStack(spacing: 10) {
            PulsingDot(color: statusColor(test), pulses: listening && !test.hasHeard)
            Text(statusText(test))
                .font(.subheadline.weight(.semibold))
                .wrapsFully()
            Spacer(minLength: 8)
            if listening {
                Text(Duration.seconds(test.remaining).formatted(.time(pattern: .minuteSecond)))
                    .font(.monaspace(.caption))
                    .foregroundStyle(.secondary)
                Button("Stop") { test.stop() }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            }
        }
        if listening {
            ProgressView(value: test.remaining, total: NodeCheckLiveTest.duration)
                .tint(NodeScopeStyle.signal)
        }
        if let note = note(test) {
            Text(note)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .wrapsFully()
        }
        if case .finished = test.phase {
            HStack(spacing: 10) {
                Button {
                    begin(test)
                } label: {
                    Label("Run Again", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(NodeScopeStyle.activity)
                if let close {
                    Button(action: close) {
                        Text("Done").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    @ViewBuilder
    private func results(_ test: NodeCheckLiveTest) -> some View {
        let nearest = test.observers.first?.hops
        NodeCheckStatGrid(stats: [
            .init(label: "Observers", value: "\(test.observers.count)", note: String(localized: "heard it")),
            .init(label: "Regions", value: "\(test.regions.count)", note: test.regions.isEmpty ? "—" : test.regions.joined(separator: ", ")),
            .init(label: "Nearest", value: NodeCheckStatGrid.hops(nearest),
                  note: nearest == 0 ? String(localized: "no repeater needed") : String(localized: "through repeaters")),
        ])
        NodeCheckLiveMap(heard: test.observers, observers: observers)
        NodeCheckObserversCard(title: "", subtitle: nil, observers: test.observers, showsPackets: false, embedded: true)
    }

    private func statusColor(_ test: NodeCheckLiveTest) -> Color {
        if test.hasHeard { return NodeScopeStyle.healthy }
        if case .finished = test.phase { return NodeScopeStyle.activity }
        return NodeScopeStyle.signal
    }

    private func statusText(_ test: NodeCheckLiveTest) -> String {
        let count = test.observers.count
        switch test.phase {
        case .idle:
            return ""
        case .listening:
            if count == 0 { return String(localized: "Listening for your advert…") }
            return count == 1 ? String(localized: "Heard! 1 observer so far") : String(localized: "Heard! \(count) observers so far")
        case .finished(let stopped):
            if count == 0 { return stopped ? String(localized: "Test stopped") : String(localized: "Nothing heard in two minutes") }
            let regions = test.regions.count
            return String(localized: "Heard by \(count == 1 ? String(localized: "1 observer") : String(localized: "\(count) observers")) in \(regions == 1 ? String(localized: "1 region") : String(localized: "\(regions) regions"))")
        }
    }

    private func note(_ test: NodeCheckLiveTest) -> String? {
        switch test.phase {
        case .idle:
            return nil
        case .listening:
            if test.onlyZeroHop {
                return String(localized: "That was a zero-hop advert, so only radios in direct range pass it on. Send a flood advert to see how far it really travels.")
            }
            return test.hasHeard
                ? String(localized: "More observers may still report it, so keep watching until the timer runs out.")
                : String(localized: "Send a flood advert now: in the MeshCore app, send an advert and choose Flood; on a repeater or room server, run advert. Reports usually arrive within a few seconds.")
        case .finished(let stopped):
            if test.hasHeard { return stopped ? String(localized: "Test stopped.") : String(localized: "Test complete.") }
            return stopped ? nil
                : String(localized: "No observer reported an advert from this node. Check that it was a flood advert, that the radio uses the same settings as other nodes in your area, and that a repeater is in range.")
        }
    }
}

private struct PulsingDot: View {
    let color: Color
    let pulses: Bool
    @State private var dimmed = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 10, height: 10)
            .opacity(pulses && dimmed && !reduceMotion ? 0.3 : 1)
            .animation(pulses && !reduceMotion ? .easeInOut(duration: 0.7).repeatForever(autoreverses: true) : .default, value: dimmed)
            .onAppear { dimmed = true }
            .accessibilityHidden(true)
    }
}

/// The observers that heard the node: green when they heard it directly, blue through repeaters.
private struct NodeCheckLiveMap: View {
    let heard: [NodeCheckReport.HeardObserver]
    let observers: [String: MeshObserver]
    @State private var position: MapCameraPosition = .automatic

    private struct Pin: Identifiable {
        let id: String
        let name: String
        let coordinate: CLLocationCoordinate2D
        let direct: Bool
    }

    private var pins: [Pin] {
        heard.compactMap { entry in
            guard let observer = observers[entry.observerID], let lat = observer.lat, let lon = observer.lon,
                  !(lat == 0 && lon == 0) else { return nil }
            return Pin(id: entry.observerID, name: entry.name,
                       coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon), direct: entry.hops == 0)
        }
    }

    var body: some View {
        let pins = pins
        if !pins.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Map(position: $position, interactionModes: []) {
                    ForEach(pins) { pin in
                        Annotation(pin.name, coordinate: pin.coordinate, anchor: .center) {
                            Circle()
                                .fill(pin.direct ? NodeScopeStyle.healthy : NodeScopeStyle.signal)
                                .frame(width: 12, height: 12)
                                .overlay(Circle().stroke(.white, lineWidth: 2))
                        }
                        .annotationTitles(.hidden)
                    }
                }
                .frame(height: 220)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .onChange(of: pins.count) { position = .automatic }
                .accessibilityLabel(String(localized: "Map of \(pins.count) observers that heard this node"))

                HStack(spacing: 14) {
                    legend(NodeScopeStyle.healthy, String(localized: "Heard it directly"))
                    legend(NodeScopeStyle.signal, String(localized: "Through repeaters"))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }

    private func legend(_ color: Color, _ text: String) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text)
        }
    }
}

// MARK: - Shared pieces

struct NodeCheckMessageCard<Actions: View>: View {
    let level: NodeCheckReport.Level
    let title: String
    let message: String
    @ViewBuilder var actions: Actions

    init(level: NodeCheckReport.Level, title: String, message: String, @ViewBuilder actions: () -> Actions) {
        self.level = level
        self.title = title
        self.message = message
        self.actions = actions()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.monaspace(.headline))
                .foregroundStyle(level.color)
                .wrapsFully()
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .wrapsFully()
            actions
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .instrumentCard()
    }
}

extension NodeCheckMessageCard where Actions == EmptyView {
    init(level: NodeCheckReport.Level, title: String, message: String) {
        self.init(level: level, title: title, message: message) { EmptyView() }
    }
}

private struct NodeCheckHelpCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Help")
                .font(.monaspace(.headline))
            DisclosureGroup("Where do I find my node’s public key?") {
                Text("In the MeshCore app, a companion’s public key is in its settings. For a repeater or room server, it’s shown when you log in to manage it. You can also share the node as a contact and paste the link here. The first 8 or more characters of the key are enough; names work too, but several nodes can share a name.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .wrapsFully()
                    .padding(.top, 6)
            }
            DisclosureGroup("How do I check a brand-new node?") {
                Text("Enter its full public key. If the analyzer hasn’t heard it yet, you can run a live test, send a flood advert, and watch for its first one.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .wrapsFully()
                    .padding(.top, 6)
            }
        }
        .font(.subheadline)
        .padding(16)
        .instrumentCard()
    }
}

// MARK: - Entry points

/// The node page's way in: the verdict at a glance, tapping through to the full check.
struct NodeCheckLinkCard: View {
    let node: MeshNode
    @Environment(AnalyzerSettings.self) private var settings
    @State private var report: NodeCheckReport?
    @State private var loading = true

    var body: some View {
        NavigationLink {
            NodeCheckScreen(initialNode: node, openedFromNodePage: true)
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "checkmark.seal")
                    .font(.monaspace(.title2))
                    .foregroundStyle(report?.level.color ?? NodeScopeStyle.signal)
                    .frame(width: 48, height: 48)
                    .background((report?.level.color ?? NodeScopeStyle.signal).opacity(0.13), in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text("Node Check")
                        .font(.monaspace(.headline))
                    if let report {
                        Text(report.headline)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(report.level.color)
                            .wrapsFully()
                        Text(summary(report))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .wrapsFully()
                    } else {
                        Text(loading ? String(localized: "Checking whether observers hear it…")
                             : String(localized: "Is it getting out? Findings and a live advert test"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .wrapsFully()
                    }
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(16)
            .instrumentCard()
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens Node Check for this node")
        .task(id: "\(settings.sourceSession.id)|\(node.publicKey)") {
            loading = true
            let backend = AnalyzerBackendFactory.make(settings: settings)
            if let input = try? await backend.nodeCheckInput(publicKey: node.publicKey) {
                report = NodeCheckRules.report(for: input)
            }
            loading = false
        }
    }

    private func summary(_ report: NodeCheckReport) -> String {
        let issues = report.findings.filter { $0.level == .bad || $0.level == .warn }.count
        switch issues {
        case 0: return String(localized: "Findings and a live advert test")
        case 1: return String(localized: "1 thing to check · Live test")
        default: return String(localized: "\(issues) things to check · Live test")
        }
    }
}

/// Explore's way in, for any node, including one the analyzer hasn't heard yet.
struct NodeCheckExploreCard: View {
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                Image(systemName: "checkmark.seal")
                    .font(.monaspace(.title3).weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(NodeScopeStyle.healthy, in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Node Check")
                        .font(.monaspace(.headline))
                        .foregroundStyle(.primary)
                    Text("Is your node getting out? Check any node, or watch for a new one's first advert")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .wrapsFully()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .padding(16)
            .instrumentCard()
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens Node Check")
    }
}

extension View {
    /// Lets wrapping text take all the height it needs. Inside nested stacks with padding
    /// and backgrounds, SwiftUI can offer multi-line text too little height and cut it off
    /// with an ellipsis.
    func wrapsFully() -> some View {
        fixedSize(horizontal: false, vertical: true)
    }
}
