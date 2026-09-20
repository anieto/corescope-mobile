import Foundation
import Observation

enum FavoriteKind: String, Codable, CaseIterable, Sendable {
    case node
    case observer
    case channel
}

struct FavoriteItem: Codable, Identifiable, Hashable, Sendable {
    let source: String
    let kind: FavoriteKind
    let entityID: String
    let title: String
    let subtitle: String?
    let favoritedAt: Date
    let node: MeshNode?
    let observer: MeshObserver?
    let channel: MeshChannel?

    var id: String {
        "\(source)|\(kind.rawValue)|\(entityID)"
    }
}

@Observable
@MainActor
final class FavoritesStore {
    private static let defaultsKey = "favoriteItems"

    private(set) var items: [FavoriteItem] = []

    init() {
        load()
    }

    func contains(kind: FavoriteKind, entityID: String, source: String) -> Bool {
        items.contains { item in
            item.kind == kind && item.entityID == entityID && item.source == source
        }
    }

    func toggle(node: MeshNode, source: String) {
        toggle(
            kind: .node,
            entityID: node.publicKey,
            source: source,
            title: node.name ?? "Unnamed Node",
            subtitle: node.publicKey,
            node: node
        )
    }

    func toggle(observer: MeshObserver, source: String) {
        toggle(
            kind: .observer,
            entityID: observer.id,
            source: source,
            title: observer.name ?? "Unnamed Observer",
            subtitle: observer.iata ?? observer.id,
            observer: observer
        )
    }

    func toggle(channel: MeshChannel, source: String) {
        toggle(
            kind: .channel,
            entityID: channel.id,
            source: source,
            title: channel.name,
            subtitle: channel.lastSender,
            channel: channel
        )
    }

    func remove(_ item: FavoriteItem) {
        items.removeAll { $0.id == item.id }
        persist()
    }

    /// Replaces saved node snapshots with the analyzer's latest values while
    /// preserving favorite order and the original date each node was saved.
    func refreshNodes(_ nodes: [MeshNode], source: String) {
        let nodesByPublicKey = Dictionary(
            nodes.map { ($0.publicKey.lowercased(), $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var didChange = false

        let refreshedItems = items.map { item in
            guard item.kind == .node,
                  item.source == source,
                  let node = nodesByPublicKey[item.entityID.lowercased()],
                  item.node != node else {
                return item
            }

            didChange = true
            return FavoriteItem(
                source: item.source,
                kind: item.kind,
                entityID: item.entityID,
                title: node.name ?? "Unnamed Node",
                subtitle: node.publicKey,
                favoritedAt: item.favoritedAt,
                node: node,
                observer: nil,
                channel: nil
            )
        }

        guard didChange else { return }
        items = refreshedItems
        persist()
    }

    private func toggle(
        kind: FavoriteKind,
        entityID: String,
        source: String,
        title: String,
        subtitle: String?,
        node: MeshNode? = nil,
        observer: MeshObserver? = nil,
        channel: MeshChannel? = nil
    ) {
        if let index = items.firstIndex(where: {
            $0.kind == kind && $0.entityID == entityID && $0.source == source
        }) {
            items.remove(at: index)
        } else {
            items.insert(
                FavoriteItem(
                    source: source,
                    kind: kind,
                    entityID: entityID,
                    title: title,
                    subtitle: subtitle,
                    favoritedAt: .now,
                    node: node,
                    observer: observer,
                    channel: channel
                ),
                at: 0
            )
        }
        persist()
    }

    func reorder(_ reorderedItems: [FavoriteItem], kind: FavoriteKind, source: String) {
        guard reorderedItems.allSatisfy({ $0.kind == kind && $0.source == source }) else { return }
        let existingIDs = Set(items.lazy.filter { $0.kind == kind && $0.source == source }.map(\.id))
        guard existingIDs == Set(reorderedItems.map(\.id)) else { return }

        var reorderedIterator = reorderedItems.makeIterator()
        items = items.map { item in
            guard item.kind == kind && item.source == source else { return item }
            return reorderedIterator.next() ?? item
        }
        persist()
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: Self.defaultsKey),
              let storedItems = try? JSONDecoder().decode([FavoriteItem].self, from: data) else {
            return
        }
        items = storedItems
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        UserDefaults.standard.set(data, forKey: Self.defaultsKey)
    }
}
