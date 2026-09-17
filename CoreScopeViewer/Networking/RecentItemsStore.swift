import Foundation
import Observation

enum RecentItemKind: String, Codable, Sendable {
    case node
    case observer
    case channel
    case packet
}

struct RecentItem: Codable, Identifiable, Hashable, Sendable {
    let source: String
    let kind: RecentItemKind
    let entityID: String
    let title: String
    let subtitle: String?
    let viewedAt: Date
    let node: MeshNode?
    let observer: MeshObserver?
    let channel: MeshChannel?
    let message: ChannelMessage?

    var id: String {
        "\(source)|\(kind.rawValue)|\(entityID)"
    }
}

@Observable
@MainActor
final class RecentItemsStore {
    private static let defaultsKey = "recentItems"
    private static let maximumItemsPerSource = 20

    private(set) var items: [RecentItem] = []

    init() {
        load()
    }

    func record(node: MeshNode, source: String) {
        record(
            RecentItem(
                source: source,
                kind: .node,
                entityID: node.publicKey,
                title: node.name ?? "Unnamed Node",
                subtitle: node.role.capitalized,
                viewedAt: .now,
                node: node,
                observer: nil,
                channel: nil,
                message: nil
            )
        )
    }

    func record(observer: MeshObserver, source: String) {
        record(
            RecentItem(
                source: source,
                kind: .observer,
                entityID: observer.id,
                title: observer.name ?? "Unnamed Observer",
                subtitle: observer.iata ?? observer.id,
                viewedAt: .now,
                node: nil,
                observer: observer,
                channel: nil,
                message: nil
            )
        )
    }

    func record(channel: MeshChannel, source: String) {
        record(
            RecentItem(
                source: source,
                kind: .channel,
                entityID: channel.id,
                title: channel.name,
                subtitle: channel.lastSender,
                viewedAt: .now,
                node: nil,
                observer: nil,
                channel: channel,
                message: nil
            )
        )
    }

    func record(message: ChannelMessage, source: String) {
        record(
            RecentItem(
                source: source,
                kind: .packet,
                entityID: message.packetHash,
                title: "Packet \(message.packetHash.prefix(10).uppercased())",
                subtitle: message.sender,
                viewedAt: .now,
                node: nil,
                observer: nil,
                channel: nil,
                message: message
            )
        )
    }

    func remove(_ item: RecentItem) {
        items.removeAll { $0.id == item.id }
        persist()
    }

    func clear(source: String) {
        items.removeAll { $0.source == source }
        persist()
    }

    private func record(_ item: RecentItem) {
        items.removeAll { $0.id == item.id }
        items.insert(item, at: 0)

        var sourceItemCount = 0
        items.removeAll { existingItem in
            guard existingItem.source == item.source else { return false }
            sourceItemCount += 1
            return sourceItemCount > Self.maximumItemsPerSource
        }
        persist()
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: Self.defaultsKey),
              let storedItems = try? JSONDecoder().decode([RecentItem].self, from: data) else {
            return
        }
        items = storedItems
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        UserDefaults.standard.set(data, forKey: Self.defaultsKey)
    }
}
