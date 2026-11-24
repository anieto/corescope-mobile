import SwiftUI
import WidgetKit

private struct NodeActivity: TimelineEntry, Sendable {
    let date: Date
    let name: String
    let lastHeard: Date?
    let checked: Date?
}

private struct NodeProvider: TimelineProvider, Sendable {
    private struct Response: Decodable {
        struct Node: Decodable {
            let name: String?
            let lastHeard: Date?
            let lastSeen: Date?
        }

        let node: Node
    }

    private static let endpoint = URL(string: "https://analyzer.meshtexas.org/api/nodes/5744bc79d51c2cdb3386a56a9513bd34a15f43c9a4e3d5446992b0ea601c349b")!
    private var defaults: UserDefaults { .standard }
    private let cacheKey = "myNodesPrototypeActivity"

    func placeholder(in context: Context) -> NodeActivity {
        NodeActivity(date: .now, name: "My Nodes", lastHeard: nil, checked: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (NodeActivity) -> Void) {
        completion(cachedActivity ?? placeholder(in: context))
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<NodeActivity>) -> Void) {
        let fallback = cachedActivity ?? placeholder(in: context)
        Task {
            let activity = await fetchActivity() ?? fallback
            completion(Timeline(entries: [activity], policy: .after(.now.addingTimeInterval(30 * 60))))
        }
    }

    private var cachedActivity: NodeActivity? {
        guard let cache = defaults.dictionary(forKey: cacheKey),
              let checked = cache["checked"] as? Date,
              let name = cache["name"] as? String else { return nil }
        return NodeActivity(date: .now, name: name, lastHeard: cache["lastHeard"] as? Date, checked: checked)
    }

    private func fetchActivity() async -> NodeActivity? {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 15
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: Self.endpoint)
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            let (data, response) = try await session.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            decoder.keyDecodingStrategy = .convertFromSnakeCase
            let node = try decoder.decode(Response.self, from: data).node
            let now = Date.now
            let name = node.name ?? "MeshTexas node"
            let lastHeard = [node.lastHeard, node.lastSeen].compactMap { $0 }.max()
            var cache: [String: Any] = ["name": name, "checked": now]
            cache["lastHeard"] = lastHeard
            defaults.set(cache, forKey: cacheKey)
            return NodeActivity(date: now, name: name, lastHeard: lastHeard, checked: now)
        } catch {
            return nil
        }
    }
}

@main
struct MyNodesPrototype: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "MyNodesPrototype", provider: NodeProvider()) { entry in
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.name).font(.headline).lineLimit(2)
                if let checked = entry.checked {
                    if let lastHeard = entry.lastHeard {
                        Text("Last heard")
                        Text(lastHeard, style: .relative)
                    } else {
                        Text("Last heard unknown")
                    }
                    Text("Checked")
                    Text(checked, style: .time)
                } else {
                    Text("Waiting for data")
                }
            }
            .font(.caption)
            .frame(maxWidth: .infinity, alignment: .leading)
            .containerBackground(.background, for: .widget)
        }
        .configurationDisplayName("My Nodes Prototype")
        .description("Checks activity for one public MeshTexas node.")
        .supportedFamilies([.systemSmall])
    }
}
