import Foundation
import Observation

/// Wire document for CoreScope's optional account API. Values deliberately remain raw
/// strings so reading node selections cannot discard unrelated settings or future fields.
struct CoreScopeAccountSettings: Codable, Sendable {
    struct Document: Codable, Sendable {
        let v: Int
        let keys: [String: String]
    }

    struct AllowedKey: Codable, Sendable {
        let key: String
        let kind: String
        let id: String?
    }

    let revision: Int64
    let generation: String
    let doc: Document?
    let allowlist: [AllowedKey]

    /// Revisions can restart after deletion; the generation must participate in every write.
    var hasSupportedDocument: Bool {
        if let doc {
            return revision > 0 && !generation.isEmpty && doc.v == 1
        }
        return revision == 0 && generation.isEmpty
    }

    var supportsNodeFavorites: Bool {
        supportsSet(key: "meshcore-favorites", identityField: nil)
    }

    var supportsOwnNodes: Bool {
        supportsSet(key: "meshcore-my-nodes", identityField: "pubkey")
    }

    private func supportsSet(key: String, identityField: String?) -> Bool {
        guard hasSupportedDocument else { return false }
        let entries = allowlist.filter { $0.key == key }
        guard entries.count == 1, let entry = entries.first else { return false }
        return entry.kind == "set" && entry.id == identityField
    }
}

/// Edits are scoped to one source and account. Device favorites enter only through the sign-in offer.
struct CoreScopeAccountSelectionState: Codable, Sendable {
    var baseline: CoreScopeAccountSettings
    var favorites: Set<String>
    // Optional so account selections saved by earlier builds still decode.
    var removedMyNodes: Set<String>?

    var myNodeNames: [String: String] {
        let names = (try? CoreScopeFavoriteMerge.selection(baseline).myNodes) ?? [:]
        return names.filter { removedMyNodes?.contains($0.key) != true }
    }

    var favoriteKeys: Set<String> { favorites.union(myNodeNames.keys) }

    mutating func removeFavorite(_ publicKey: String) {
        let key = publicKey.lowercased()
        favorites.remove(key)
        if myNodeNames[key] != nil {
            removedMyNodes = (removedMyNodes ?? []).union([key])
        }
    }

    init(remote: CoreScopeAccountSettings) throws {
        baseline = remote
        favorites = Set(try CoreScopeFavoriteMerge.selection(remote).favorites.map { $0.lowercased() })
    }

    mutating func refresh(_ remote: CoreScopeAccountSettings) throws {
        guard baseline.generation == remote.generation else { throw CoreScopeAccountError.reset }
        let (_, selection) = try CoreScopeFavoriteMerge.document(base: baseline, local: favorites, remote: remote)
        favorites = Set(selection.favorites.map { $0.lowercased() })
        removedMyNodes = removedMyNodes?.intersection(selection.myNodes.keys)
        baseline = remote
    }

    var hasPendingEdits: Bool {
        guard let remote = try? CoreScopeFavoriteMerge.selection(baseline) else { return true }
        return favorites != Set(remote.favorites.map { $0.lowercased() }) || removedMyNodes?.isEmpty == false
    }

    /// Preserve edits made after the uploaded snapshot was captured.
    mutating func acknowledge(_ accepted: CoreScopeAccountSettings, sentFavorites: Set<String>) throws {
        let remote = try CoreScopeFavoriteMerge.selection(accepted)
        favorites = CoreScopeFavoriteMerge.merge(base: sentFavorites, local: favorites,
            remote: Set(remote.favorites.map { $0.lowercased() }))
        removedMyNodes = removedMyNodes?.intersection(remote.myNodes.keys)
        baseline = accepted
    }

    mutating func restore(onto remote: CoreScopeAccountSettings) throws {
        let previous = favoriteKeys
        self = try Self(remote: remote)
        favorites.formUnion(previous)
    }
}

@MainActor @Observable
final class CoreScopeAccountSelectionStore {
    static let shared = CoreScopeAccountSelectionStore()
    private let defaults: UserDefaults
    private(set) var revision = 0
    private(set) var widgetWatchRevision = 0
    private(set) var activeAccounts: [String: Int64]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        activeAccounts = defaults.data(forKey: "corescope-active-accounts-v2")
            .flatMap { try? JSONDecoder().decode([String: Int64].self, from: $0) } ?? [:]
    }

    func activate(origin: String, userID: Int64) {
        // Only one identity per source, even if the URL spelling changes.
        let source = AnalyzerSettings.normalizedHost(origin)
        guard activeAccounts[origin] != userID else { return }
        activeAccounts = activeAccounts.filter { AnalyzerSettings.normalizedHost($0.key) != source }
        activeAccounts[origin] = userID
        persistActiveAccounts()
    }

    func deactivate(origin: String) {
        activeAccounts[origin] = nil
        persistActiveAccounts()
    }

    func visibleState(source: String) -> CoreScopeAccountSelectionState? {
        _ = revision
        guard let (origin, userID) = activeAccounts.first(where: {
            AnalyzerSettings.normalizedHost($0.key) == source
        }) else { return nil }
        return try? load(origin: origin, userID: userID)
    }

    var sources: [String] { activeAccounts.keys.map(AnalyzerSettings.normalizedHost).sorted() }

    func removeFavorite(_ key: String, source: String) throws {
        guard let (origin, userID) = activeAccounts.first(where: {
            AnalyzerSettings.normalizedHost($0.key) == source
        }), var state = try load(origin: origin, userID: userID) else { return }
        state.removeFavorite(key)
        try save(state, origin: origin, userID: userID)
    }

    func isActive(source: String) -> Bool {
        activeAccounts.keys.contains { AnalyzerSettings.normalizedHost($0) == source }
    }

    func toggleFavorite(_ key: String, source: String) {
        guard CoreScopeFavoriteMerge.validKey(key),
              let (origin, userID) = activeAccounts.first(where: { AnalyzerSettings.normalizedHost($0.key) == source }),
              var state = try? load(origin: origin, userID: userID) else { return }
        let normalizedKey = key.lowercased()
        if state.favoriteKeys.contains(normalizedKey) { state.removeFavorite(normalizedKey) }
        else { state.favorites.insert(normalizedKey) }
        try? save(state, origin: origin, userID: userID)
    }

    func offeredDeviceFavorites(_ keys: Set<String>, state: CoreScopeAccountSelectionState,
                                origin: String, userID: Int64) -> Set<String> {
        guard !defaults.bool(forKey: offerKey(origin: origin, userID: userID)) else { return [] }
        return keys.filter(CoreScopeFavoriteMerge.validKey).subtracting(state.favoriteKeys)
    }

    func finishDeviceFavoritesOffer(origin: String, userID: Int64) {
        let key = offerKey(origin: origin, userID: userID)
        if !defaults.bool(forKey: key) { defaults.set(true, forKey: key) }
    }

    private func offerKey(origin: String, userID: Int64) -> String {
        "corescope-device-favorites-offer|\(AnalyzerSettings.normalizedHost(origin).lowercased())|\(userID)"
    }

    func widgetWatchesChanged() {
        widgetWatchRevision += 1
    }

    private func persistActiveAccounts() {
        widgetWatchesChanged()
        defaults.set(try? JSONEncoder().encode(activeAccounts), forKey: "corescope-active-accounts-v2")
    }

    static func key(origin: String, userID: Int64) -> String {
        "corescope-account-selections-v2|\(origin)|\(userID)"
    }

    func load(origin: String, userID: Int64) throws -> CoreScopeAccountSelectionState? {
        guard let data = defaults.data(forKey: Self.key(origin: origin, userID: userID)) else { return nil }
        return try JSONDecoder().decode(CoreScopeAccountSelectionState.self, from: data)
    }

    func save(_ state: CoreScopeAccountSelectionState, origin: String, userID: Int64) throws {
        defaults.set(try JSONEncoder().encode(state), forKey: Self.key(origin: origin, userID: userID))
        revision += 1
    }

    func remove(origin: String, userID: Int64) {
        if activeAccounts[origin] == userID {
            activeAccounts[origin] = nil
            persistActiveAccounts()
        }
        revision += 1
        defaults.removeObject(forKey: Self.key(origin: origin, userID: userID))
        defaults.removeObject(forKey: "corescope-favorite-baseline|\(origin)|\(userID)")
    }
}
