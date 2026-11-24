import AppIntents
import SwiftUI
import WidgetKit

struct WidgetNode: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Choose a Node"
    static let defaultQuery = WidgetNodeQuery()

    let id: String
    let name: String

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct WidgetNodeQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [WidgetNode] {
        let available = choices
        return identifiers.compactMap { id in available.first { $0.id == id } }
    }

    func suggestedEntities() async throws -> [WidgetNode] { choices }

    private var choices: [WidgetNode] {
        (MyNodesWidgetCatalog.read()?.choices ?? [])
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            .map { WidgetNode(id: $0.id, name: $0.name) }
    }
}

protocol MyNodesSelectionIntent: WidgetConfigurationIntent {
    var selectedIDs: [String] { get }
}

struct MyNodesConfiguration: MyNodesSelectionIntent {
    static let title: LocalizedStringResource = "My Nodes"

    @Parameter(title: "Node")
    var node: WidgetNode?

    var selectedIDs: [String] { node.map { [$0.id] } ?? [] }
}

struct MyNodesListConfiguration: MyNodesSelectionIntent {
    static let title: LocalizedStringResource = "My Nodes List"

    @Parameter(title: "Nodes", size: .init(min: 1, max: 3))
    var nodes: [WidgetNode]?

    var selectedIDs: [String] { nodes?.map(\.id) ?? [] }
}

private struct NodeReading: Codable, Sendable {
    let lastHeard: Date?
    let checked: Date
}

private struct WidgetNodeRow: Identifiable, Sendable {
    let id: String
    let name: String
    let link: URL?
    let reading: NodeReading?
}

private struct NodeActivity: TimelineEntry {
    let date = Date.now
    var rows: [WidgetNodeRow] = []
    var unavailable = false
    var hasChoices = false
    var textureEnabled = true

    var name: String? { rows.first?.name }
    var link: URL? { rows.first?.link }
    var reading: NodeReading? { rows.first?.reading }
}

private struct NodeProvider<Selection: MyNodesSelectionIntent>: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> NodeActivity {
        let names = context.family == .systemMedium ? ["DistantWatch", "Cedar Ridge", "Hill Country"] : ["DistantWatch"]
        return NodeActivity(rows: names.enumerated().map { index, name in
            WidgetNodeRow(id: name, name: name, link: nil, reading: NodeReading(
                lastHeard: .now.addingTimeInterval(Double(-180 * (index + 1))), checked: .now))
        })
    }

    func snapshot(for configuration: Selection, in context: Context) async -> NodeActivity {
        if context.isPreview { return placeholder(in: context) }
        return await timelineEntry(for: configuration, fetch: false)
    }

    func timeline(for configuration: Selection, in context: Context) async -> Timeline<NodeActivity> {
        let entry = await timelineEntry(for: configuration, fetch: true)
        return Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(30 * 60)))
    }

    private func timelineEntry(for configuration: Selection, fetch: Bool) async -> NodeActivity {
        let catalog = MyNodesWidgetCatalog.read()
        let textureEnabled = catalog?.textureEnabled ?? true
        let hasChoices = catalog?.choices.isEmpty == false
        var seen = Set<String>()
        let ids = Array(configuration.selectedIDs.filter { seen.insert($0).inserted }.prefix(3))
        guard let catalog, !ids.isEmpty else {
            return NodeActivity(unavailable: !ids.isEmpty, hasChoices: hasChoices, textureEnabled: textureEnabled)
        }
        let choices = Dictionary(uniqueKeysWithValues: catalog.choices.map { ($0.id, $0) })
        let rows = await withTaskGroup(of: WidgetNodeRow.self) { tasks in
            for id in ids {
                guard let choice = choices[id], let endpoint = choice.endpoint else { continue }
                tasks.addTask {
                    let reading = await WidgetReadings.shared.read(
                        choice: choice, endpoint: endpoint, revision: catalog.revision, refresh: fetch)
                    return WidgetNodeRow(id: id, name: choice.name, link: choice.detailURL, reading: reading)
                }
            }

            var results: [String: WidgetNodeRow] = [:]
            for await row in tasks { results[row.id] = row }
            return ids.compactMap { results[$0] }
        }
        guard let latestCatalog = MyNodesWidgetCatalog.read(), latestCatalog.revision == catalog.revision else {
            return NodeActivity(unavailable: true, textureEnabled: textureEnabled)
        }
        return NodeActivity(rows: rows, unavailable: rows.count < ids.count, hasChoices: hasChoices,
                            textureEnabled: latestCatalog.textureEnabled ?? true)
    }
}

private actor WidgetReadings {
    static let shared = WidgetReadings()

    private struct Response: Decodable {
        struct Node: Decodable {
            let lastHeard: Date?
            let lastSeen: Date?
        }

        let node: Node
    }

    private struct Cache: Codable {
        let revision: UUID
        var readings: [String: NodeReading]
    }

    private struct RefreshKey: Hashable {
        let revision: UUID
        let id: String
    }

    private let key = "myNodesWidgetReadings"
    private var inFlight: [RefreshKey: Task<NodeReading?, Never>] = [:]

    func read(choice: MyNodesWidgetChoice, endpoint: URL, revision: UUID, refresh: Bool) async -> NodeReading? {
        let previous = cache(revision: revision).readings[choice.id]
        guard refresh, previous.map({ Date.now.timeIntervalSince($0.checked) >= 5 * 60 }) ?? true else {
            return previous
        }
        let refreshKey = RefreshKey(revision: revision, id: choice.id)
        if let task = inFlight[refreshKey] {
            return await task.value ?? previous
        }
        let task = Task { await Self.fetchReading(endpoint) }
        inFlight[refreshKey] = task
        let reading = await task.value
        inFlight[refreshKey] = nil
        guard MyNodesWidgetCatalog.read()?.revision == revision else { return nil }
        guard let reading else { return previous }
        var current = cache(revision: revision)
        current.readings[choice.id] = reading
        UserDefaults.standard.set(try? JSONEncoder().encode(current), forKey: key)
        return reading
    }

    private func cache(revision: UUID) -> Cache {
        if let data = UserDefaults.standard.data(forKey: key),
           let current = try? JSONDecoder().decode(Cache.self, from: data), current.revision == revision {
            return current
        }
        return Cache(revision: revision, readings: [:])
    }

    private static func fetchReading(_ endpoint: URL) async -> NodeReading? {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 15
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            let (data, response) = try await session.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .custom {
                let value = try $0.singleValueContainer().decode(String.self)
                if let fractional = try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(value) {
                    return fractional
                }
                return try Date.ISO8601FormatStyle().parse(value)
            }
            decoder.keyDecodingStrategy = .convertFromSnakeCase
            let node = try decoder.decode(Response.self, from: data).node
            return NodeReading(lastHeard: [node.lastHeard, node.lastSeen].compactMap { $0 }.max(), checked: .now)
        } catch {
            return nil
        }
    }
}

private struct MyNodesWidgetView: View {
    let entry: NodeActivity

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 4) {
                if entry.name != nil {
                    antenna
                } else {
                    Label { Text("My Nodes").font(.monaspace(.caption)) } icon: { antenna }
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .widgetAccentable()
                }
                Spacer(minLength: 0)
                NodeCheckWidgetLink()
            }
            if let name = entry.name {
                Text(name)
                    .font(.monaspace(.headline))
                    .lineLimit(3)
                    .minimumScaleFactor(0.8)
                    .layoutPriority(1)
                    .widgetAccentable()
            }

            Spacer(minLength: 3)

            if let reading = entry.reading {
                Text("Last heard")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let lastHeard = reading.lastHeard {
                    Text(lastHeard, style: .relative)
                        .font(.monaspace(.subheadline))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                } else {
                    Text("Unknown").font(.subheadline)
                }
                let checkedFormat = widgetCheckedFormat(reading.checked)
                Text("Checked \(reading.checked, format: checkedFormat)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
            } else {
                Text(status)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .widgetURL(entry.link ?? URL(string: "nodescope://widget-node"))
        .containerBackground(for: .widget) {
            MeshBackground(subdued: true, compact: true, textureEnabled: entry.textureEnabled)
        }
    }

    private var antenna: some View {
        Image(systemName: "antenna.radiowaves.left.and.right")
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.accentColor)
            .widgetAccentable()
            .accessibilityHidden(true)
    }

    private var status: LocalizedStringKey {
        if entry.name != nil { return "Waiting for data" }
        if entry.unavailable { return "Choose another node in Edit Widget." }
        if entry.hasChoices { return "Choose a node in Edit Widget." }
        return "Save or watch a node in NodeScope, then choose it in Edit Widget."
    }
}

private struct NodeCheckWidgetLink: View {
    var body: some View {
        Link(destination: MyNodesWidgetCatalog.nodeCheckURL) {
            Text("Node Check")
                .font(.caption2.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 6)
                .frame(minHeight: 18)
                .foregroundStyle(Color.accentColor)
                .background(Color.accentColor.opacity(0.12), in: Capsule())
        }
        .fixedSize()
    }
}

private func widgetCheckedFormat(_ checked: Date) -> Date.FormatStyle {
    Calendar.current.isDateInToday(checked)
        ? .dateTime.hour().minute()
        : .dateTime.month(.abbreviated).day().hour().minute()
}

private struct MyNodesListView: View {
    let entry: NodeActivity

    private var sharedChecked: Date? {
        guard let checked = entry.rows.first?.reading?.checked,
              entry.rows.allSatisfy({ row in
                  row.reading.map { Calendar.current.isDate($0.checked, equalTo: checked, toGranularity: .minute) } == true
              }) else { return nil }
        return checked
    }

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let checked = sharedChecked
            let heardWidth = width * (checked == nil ? 0.26 : 0.30)
            let checkedWidth: CGFloat? = checked == nil ? width * 0.22 : nil
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Label("My Nodes", systemImage: "antenna.radiowaves.left.and.right")
                        .font(.monaspace(.caption))
                        .foregroundStyle(Color.accentColor)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                    Spacer(minLength: 0)
                    if !entry.rows.isEmpty {
                        heading("Last heard", width: heardWidth, alignment: .center)
                        if let checkedWidth {
                            heading("Checked", width: checkedWidth)
                        }
                    }
                }

                if entry.rows.isEmpty {
                    Spacer(minLength: 2)
                    Text(status)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                ForEach(entry.rows) { row in
                    if let link = row.link {
                        Link(destination: link) { nodeRow(row, heardWidth: heardWidth, checkedWidth: checkedWidth) }
                    } else {
                        nodeRow(row, heardWidth: heardWidth, checkedWidth: checkedWidth)
                    }
                }
                Spacer(minLength: 0)
                HStack(spacing: 8) {
                    NodeCheckWidgetLink()
                    Spacer(minLength: 0)
                    if let checked {
                        Text("Checked \(checked, format: widgetCheckedFormat(checked))")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                }
            }
        }
        .widgetURL(URL(string: "nodescope://widget-node"))
        .containerBackground(for: .widget) {
            MeshBackground(subdued: true, compact: true, textureEnabled: entry.textureEnabled)
        }
    }

    private func heading(_ title: LocalizedStringKey, width: CGFloat, alignment: Alignment = .trailing) -> some View {
        Text(title)
            .font(.caption2)
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(width: width, alignment: alignment)
    }

    private func nodeRow(_ row: WidgetNodeRow, heardWidth: CGFloat, checkedWidth: CGFloat?) -> some View {
        HStack(spacing: 8) {
            Text(row.name)
                .font(.monaspace(.footnote).weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(2)
                .minimumScaleFactor(0.85)
                .frame(maxWidth: .infinity, alignment: .leading)

            Group {
                if let lastHeard = row.reading?.lastHeard {
                    Text(lastHeard, style: .relative)
                        .font(.monaspace(.caption2))
                        .accessibilityLabel(Text("Last heard \(lastHeard, style: .relative)"))
                } else {
                    Text(row.reading == nil ? "Waiting" : "Unknown")
                        .font(.caption2)
                }
            }
            .foregroundStyle(.primary)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .multilineTextAlignment(.center)
            .frame(width: heardWidth, alignment: .center)

            if let checkedWidth {
                Group {
                    if let checked = row.reading?.checked {
                        Text(checked, format: widgetCheckedFormat(checked))
                            .accessibilityLabel(Text("Checked \(checked, format: widgetCheckedFormat(checked))"))
                    } else {
                        Text("—")
                            .accessibilityLabel("No check yet")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .minimumScaleFactor(0.8)
                .multilineTextAlignment(.trailing)
                .frame(width: checkedWidth, alignment: .trailing)
            }
        }
        .frame(minHeight: 29)
        .contentShape(Rectangle())
    }

    private var status: LocalizedStringKey {
        if entry.unavailable { return "Choose other nodes in Edit Widget." }
        if entry.hasChoices { return "Choose up to three nodes in Edit Widget." }
        return "Save or watch nodes in NodeScope to add them here."
    }
}

struct MyNodesPrototype: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: MyNodesWidgetCatalog.widgetKind, intent: MyNodesConfiguration.self, provider: NodeProvider<MyNodesConfiguration>()) {
            MyNodesWidgetView(entry: $0)
        }
        .configurationDisplayName("My Nodes")
        .description("Choose a favorite or watched node to see when it was last heard.")
        .supportedFamilies([.systemSmall])
        .promptsForUserConfiguration()
    }
}


struct MyNodesListWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: MyNodesWidgetCatalog.listWidgetKind, intent: MyNodesListConfiguration.self,
                               provider: NodeProvider<MyNodesListConfiguration>()) {
            MyNodesListView(entry: $0)
        }
        .configurationDisplayName("My Nodes List")
        .description("Choose up to three favorite or watched nodes to see when they were last heard.")
        .supportedFamilies([.systemMedium])
        .promptsForUserConfiguration()
    }
}

@main
struct MyNodesWidgets: WidgetBundle {
    var body: some Widget {
        MyNodesPrototype()
        MyNodesListWidget()
    }
}
