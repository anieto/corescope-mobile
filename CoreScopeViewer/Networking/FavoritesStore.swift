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

    private(set) var deviceItems: [FavoriteItem] = []

    private let accountStore: CoreScopeAccountSelectionStore
    private let defaults: UserDefaults
    private var accountSnapshots: [String: MeshNode] = [:]

    init(defaults: UserDefaults = .standard, accountStore: CoreScopeAccountSelectionStore = .shared) {
        self.defaults = defaults
        self.accountStore = accountStore
        load()
    }

    /// Account membership is projected for display and never serialized as a device favorite.
    var items: [FavoriteItem] {
        var result = deviceItems
        for source in accountStore.sources {
            guard let state = accountStore.visibleState(source: source),
                  let selection = try? CoreScopeFavoriteMerge.selection(state.baseline) else { continue }
            let localKeys = Set(deviceItems.filter { $0.source == source && $0.kind == .node }.map { $0.entityID.lowercased() })
            let keys = state.favorites.union(selection.claimed.keys).subtracting(localKeys)
            let added = keys.map { key -> FavoriteItem in
                let claimedName = selection.claimed[key].flatMap { $0.isEmpty ? nil : $0 }
                let node = accountSnapshots["\(source)|\(key)"] ?? MeshNode(publicKey: key, name: claimedName,
                    role: "unknown", lat: nil, lon: nil, lastSeen: nil, firstSeen: nil, advertCount: nil,
                    hashSize: nil, hashSizeInconsistent: nil, lastHeard: nil, defaultScope: nil)
                return FavoriteItem(source: source, kind: .node, entityID: key,
                    title: node.name ?? String(key.prefix(12)), subtitle: key, favoritedAt: .distantPast,
                    node: node, observer: nil, channel: nil)
            }.sorted {
                let comparison = $0.title.localizedStandardCompare($1.title)
                return comparison == .orderedSame ? $0.entityID < $1.entityID : comparison == .orderedAscending
            }
            result.append(contentsOf: added)
        }
        return result
    }

    func isDeviceFavorite(_ item: FavoriteItem) -> Bool {
        deviceItems.contains { $0.source == item.source && $0.kind == item.kind && $0.entityID.lowercased() == item.entityID.lowercased() }
    }

    func isAccountFavorite(_ item: FavoriteItem) -> Bool {
        item.kind == .node && accountStore.visibleState(source: item.source)?.favorites.contains(item.entityID.lowercased()) == true
    }

    func membershipLabel(_ item: FavoriteItem) -> String {
        var labels: [String] = []
        if isDeviceFavorite(item) { labels.append("Device") }
        if isAccountFavorite(item) { labels.append("Account") }
        if let state = accountStore.visibleState(source: item.source),
           let remote = try? CoreScopeFavoriteMerge.selection(state.baseline), remote.claimed[item.entityID.lowercased()] != nil {
            labels.append("Claimed")
        }
        return labels.joined(separator: " · ")
    }

    func removeAccountFavorite(_ item: FavoriteItem) {
        try? accountStore.removeFavorite(item.entityID, source: item.source)
    }

    func contains(kind: FavoriteKind, entityID: String, source: String) -> Bool {
        deviceItems.contains { item in
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
        deviceItems.removeAll { $0.id == item.id }
        persist()
    }

    func replaceChannel(from oldID: String, with channel: MeshChannel, source: String) {
        guard let oldIndex = deviceItems.firstIndex(where: {
            $0.kind == .channel && $0.entityID == oldID && $0.source == source
        }) else { return }
        let oldItem = deviceItems[oldIndex]
        deviceItems.removeAll {
            $0.kind == .channel && $0.source == source && ($0.entityID == oldID || $0.entityID == channel.id)
        }
        deviceItems.insert(
            FavoriteItem(
                source: source, kind: .channel, entityID: channel.id,
                title: channel.name, subtitle: channel.lastSender,
                favoritedAt: oldItem.favoritedAt, node: nil, observer: nil,
                channel: channel
            ),
            at: min(oldIndex, deviceItems.count)
        )
        persist()
    }

    /// Replaces saved node snapshots with the analyzer's latest values while
    /// preserving favorite order and the original date each node was saved.
    func refreshNodes(_ nodes: [MeshNode], source: String) {
        for node in nodes {
            accountSnapshots["\(source)|\(node.publicKey.lowercased())"] = node
        }
        let nodesByPublicKey = Dictionary(
            nodes.map { ($0.publicKey.lowercased(), $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var didChange = false

        let refreshedItems = deviceItems.map { item in
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
        deviceItems = refreshedItems
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
        if let index = deviceItems.firstIndex(where: {
            $0.kind == kind && $0.entityID == entityID && $0.source == source
        }) {
            deviceItems.remove(at: index)
        } else {
            deviceItems.insert(
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
        let existingIDs = Set(deviceItems.lazy.filter { $0.kind == kind && $0.source == source }.map(\.id))
        guard existingIDs == Set(reorderedItems.map(\.id)) else { return }

        var reorderedIterator = reorderedItems.makeIterator()
        deviceItems = deviceItems.map { item in
            guard item.kind == kind && item.source == source else { return item }
            return reorderedIterator.next() ?? item
        }
        persist()
    }

    func nodeKeys(source: String) -> Set<String> {
        Set(deviceItems.filter { $0.source == source && $0.kind == .node }.map { $0.entityID.lowercased() })
    }

    /// Apply account reconciliation only to this source's nodes; keep other favorites and order.
    func applyAccountNodes(_ keys: Set<String>, names: [String: String], source: String) {
        var seen = Set<String>()
        deviceItems = deviceItems.filter { item in
            guard item.source == source, item.kind == .node else { return true }
            let key = item.entityID.lowercased()
            return keys.contains(key) && seen.insert(key).inserted
        }
        for key in keys.subtracting(seen).sorted() {
            let name = names[key].flatMap { $0.isEmpty ? nil : $0 }
            let node = MeshNode(publicKey: key, name: name, role: "unknown", lat: nil, lon: nil,
                lastSeen: nil, firstSeen: nil, advertCount: nil, hashSize: nil,
                hashSizeInconsistent: nil, lastHeard: nil, defaultScope: nil)
            deviceItems.append(FavoriteItem(source: source, kind: .node, entityID: key,
                title: name ?? String(key.prefix(12)), subtitle: key, favoritedAt: .now,
                node: node, observer: nil, channel: nil))
        }
        persist()
    }

    private func load() {
        guard let data = defaults.data(forKey: Self.defaultsKey),
              let storedItems = try? JSONDecoder().decode([FavoriteItem].self, from: data) else {
            return
        }
        deviceItems = storedItems
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(deviceItems) else { return }
        defaults.set(data, forKey: Self.defaultsKey)
    }
}
