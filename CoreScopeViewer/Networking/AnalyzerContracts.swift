import Foundation

final class AnalyzerSourceSession: @unchecked Sendable {
    let id = UUID()
    private let lock = NSLock()
    private var valid = true
    private var cancellations: [UUID: @Sendable () -> Void] = [:]

    var isCurrent: Bool {
        lock.lock()
        defer { lock.unlock() }
        return valid
    }

    func invalidate() {
        lock.lock()
        valid = false
        let callbacks = Array(cancellations.values)
        cancellations.removeAll()
        lock.unlock()
        callbacks.forEach { $0() }
    }

    func registerCancellation(_ cancel: @escaping @Sendable () -> Void) -> UUID {
        let identifier = UUID()
        lock.lock()
        let shouldCancel = !valid
        if valid { cancellations[identifier] = cancel }
        lock.unlock()
        if shouldCancel { cancel() }
        return identifier
    }

    func removeCancellation(_ identifier: UUID) {
        lock.lock()
        cancellations[identifier] = nil
        lock.unlock()
    }

    func check() throws {
        try Task.checkCancellation()
        guard isCurrent else { throw CancellationError() }
    }
}

struct AnalyzerCursor: Codable, Hashable, Sendable {
    let value: String
}

struct AnalyzerPage<Item: Codable & Sendable>: Codable, Sendable {
    let items: [Item]
    let nextCursor: AnalyzerCursor?
    let total: Int?
}

enum AnalyzerPagingError: Error, LocalizedError {
    case invalidCursor
    case invalidPageSize
    case repeatedCursor
    case pageBudgetExceeded

    var errorDescription: String? {
        switch self {
        case .invalidCursor: "This page belongs to a different request. Refresh to continue."
        case .invalidPageSize: "The page size must be greater than zero."
        case .repeatedCursor: "The server repeated a page cursor. Refresh to try again."
        case .pageBudgetExceeded: "The requested history exceeds the page budget. Try a smaller window."
        }
    }
}

enum AnalyzerPaging {
    static func collect<Item: Codable & Sendable, Key: Hashable>(
        limit: Int,
        maximumPages: Int = 20,
        identity: (Item) -> Key,
        fetch: (AnalyzerCursor?, Int) async throws -> AnalyzerPage<Item>
    ) async throws -> [Item] {
        guard limit > 0 else { return [] }
        guard maximumPages > 0 else { throw AnalyzerPagingError.pageBudgetExceeded }
        var result: [Item] = []
        var seenItems = Set<Key>()
        var seenCursors = Set<AnalyzerCursor>()
        var cursor: AnalyzerCursor?
        for _ in 0..<maximumPages {
            try Task.checkCancellation()
            let page = try await fetch(cursor, limit - result.count)
            try Task.checkCancellation()
            for item in page.items where seenItems.insert(identity(item)).inserted {
                result.append(item)
                if result.count == limit { return result }
            }
            guard let next = page.nextCursor else { return result }
            guard seenCursors.insert(next).inserted else { throw AnalyzerPagingError.repeatedCursor }
            cursor = next
        }
        throw AnalyzerPagingError.pageBudgetExceeded
    }
}
