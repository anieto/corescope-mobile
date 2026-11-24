import Foundation

struct MyNodesWidgetChoice: Codable, Equatable, Sendable, Identifiable {
    let source: String
    let key: String
    let name: String
    let accountID: Int64?

    var id: String { "\(source)|\(accountID.map(String.init) ?? "device")|\(key)" }

    var endpoint: URL? {
        guard key.utf8.count == 64, key.utf8.allSatisfy({
            (48...57).contains($0) || (97...102).contains($0)
        }) else { return nil }
        var components = URLComponents(string: "https://\(source)")
        guard components?.host != nil, components?.user == nil, components?.password == nil,
              components?.path == "", components?.query == nil, components?.fragment == nil else { return nil }
        components?.path = "/api/nodes/\(key)"
        return components?.url
    }

    var detailURL: URL? {
        var components = URLComponents()
        components.scheme = "nodescope"
        components.host = "widget-node"
        components.queryItems = [URLQueryItem(name: "id", value: id)]
        return components.url
    }
}

struct MyNodesWidgetCatalog: Codable, Sendable {
    static let widgetKind = "MyNodes"
    static let listWidgetKind = "MyNodesList"
    static let nodeCheckHost = "node-check"
    static let nodeCheckURL = URL(string: "nodescope://\(nodeCheckHost)")!
    private static let group = "group.com.btdev.nodescope.widgets"
    let revision: UUID
    let choices: [MyNodesWidgetChoice]
    let textureEnabled: Bool?

    private static var location: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("my-nodes-widget-v1.json")
    }

    static func read() -> Self? {
        guard let location, let data = try? Data(contentsOf: location) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }

    @discardableResult
    static func publish(_ choices: [MyNodesWidgetChoice], textureEnabled: Bool) throws -> Bool {
        guard let location else { return false }
        let previous = read()
        guard previous?.choices != choices || previous?.textureEnabled != textureEnabled else { return false }
        let revision = previous.flatMap { $0.choices == choices ? $0.revision : nil } ?? UUID()
        let catalog = Self(revision: revision, choices: choices, textureEnabled: textureEnabled)
        try JSONEncoder().encode(catalog).write(to: location, options: .atomic)
        return true
    }
}
