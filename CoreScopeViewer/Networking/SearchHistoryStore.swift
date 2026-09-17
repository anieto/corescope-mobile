import Foundation
import Observation

struct SearchHistoryItem: Codable, Identifiable, Hashable, Sendable {
    let source: String
    let query: String
    let searchedAt: Date

    var id: String {
        "\(source)|\(query.lowercased())"
    }
}

@Observable
@MainActor
final class SearchHistoryStore {
    private static let defaultsKey = "searchHistory"
    private static let maximumItemsPerSource = 10

    private(set) var items: [SearchHistoryItem] = []

    init() {
        load()
    }

    func record(_ query: String, source: String) {
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedQuery.isEmpty else { return }

        items.removeAll {
            $0.source == source && $0.query.caseInsensitiveCompare(normalizedQuery) == .orderedSame
        }
        items.insert(
            SearchHistoryItem(source: source, query: normalizedQuery, searchedAt: .now),
            at: 0
        )

        var sourceItemCount = 0
        items.removeAll { item in
            guard item.source == source else { return false }
            sourceItemCount += 1
            return sourceItemCount > Self.maximumItemsPerSource
        }
        persist()
    }

    func remove(_ item: SearchHistoryItem) {
        items.removeAll { $0.id == item.id }
        persist()
    }

    func clear(source: String) {
        items.removeAll { $0.source == source }
        persist()
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: Self.defaultsKey),
              let storedItems = try? JSONDecoder().decode([SearchHistoryItem].self, from: data) else {
            return
        }
        items = storedItems
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        UserDefaults.standard.set(data, forKey: Self.defaultsKey)
    }
}
