import Foundation

enum MyNodesBudgetError: Error, Equatable {
    case exhausted, nodeExhausted, busy, invalidated
}

/// One dashboard batch. Each operation must wrap exactly one HTTP attempt, including retries.
/// Cache hits bypass this gate. The repository schedules work when capacity is available.
actor MyNodesRequestBudget {
    private let maximumRequests: Int
    private let maximumPerNode: Int
    private let maximumConcurrent: Int
    private var consumed = 0
    private var perNode: [String: Int] = [:]
    private var active = 0
    private var valid = true

    init(maximumRequests: Int = 20, maximumPerNode: Int = 4, maximumConcurrent: Int = 2) {
        self.maximumRequests = max(0, maximumRequests)
        self.maximumPerNode = max(0, maximumPerNode)
        self.maximumConcurrent = max(0, maximumConcurrent)
    }

    func invalidate() { valid = false }

    func perform<T: Sendable>(
        node: String? = nil,
        operation: @Sendable () async throws -> T
    ) async throws -> T {
        try Task.checkCancellation()
        guard valid else { throw MyNodesBudgetError.invalidated }
        guard consumed < maximumRequests else { throw MyNodesBudgetError.exhausted }
        let key = node?.lowercased()
        if let key, perNode[key, default: 0] >= maximumPerNode {
            throw MyNodesBudgetError.nodeExhausted
        }
        guard active < maximumConcurrent else { throw MyNodesBudgetError.busy }
        consumed += 1
        active += 1
        if let key { perNode[key, default: 0] += 1 }
        defer { active -= 1 }
        let value = try await operation()
        try Task.checkCancellation()
        guard valid else { throw MyNodesBudgetError.invalidated }
        return value
    }
}

/// Attach to a client dedicated to one selected node; all pages and explicit retries share its allowance.
/// Repository cache reads happen before this transport boundary.
struct MyNodesRequestAllowance: Sendable {
    let budget: MyNodesRequestBudget
    let node: String
    var cooldownKey: String? = nil
    static let coreScopeCooldown = BeaconRateLimit()

    func data(for request: URLRequest, session: URLSession) async throws -> (Data, URLResponse) {
        if let cooldownKey {
            try await Self.coreScopeCooldown.check(origin: cooldownKey, now: .now)
        }
        var uncached = request
        uncached.cachePolicy = .reloadIgnoringLocalCacheData
        let requestCopy = uncached
        let result = try await budget.perform(node: node) {
            try await session.data(for: requestCopy, delegate: MyNodesRedirectPolicy())
        }
        if let cooldownKey, let response = result.1 as? HTTPURLResponse, response.statusCode == 429 {
            let until = await Self.coreScopeCooldown.record(origin: cooldownKey,
                retryAfter: response.value(forHTTPHeaderField: "Retry-After"), now: .now)
            throw BeaconAPIError.rateLimited(until: until)
        }
        return result
    }
}

/// A redirect is returned to the client as a non-success status, never followed outside the gate.
private final class MyNodesRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession, task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}
