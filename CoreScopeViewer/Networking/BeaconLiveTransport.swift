import Foundation

@MainActor
protocol BeaconSocket: AnyObject {
    var response: HTTPURLResponse? { get }
    var closeCode: Int { get }
    func receive() async throws -> Data
    func send(_ data: Data) async throws
    func close()
}

@MainActor
final class BeaconURLSocket: BeaconSocket {
    private let session: URLSession
    private let task: URLSessionWebSocketTask

    init(url: URL) {
        session = URLSession(configuration: .ephemeral)
        task = session.webSocketTask(with: URLRequest(url: url, timeoutInterval: 20))
        task.maximumMessageSize = 1_048_576
        task.resume()
    }

    var response: HTTPURLResponse? { task.response as? HTTPURLResponse }
    var closeCode: Int { task.closeCode.rawValue }

    func receive() async throws -> Data {
        switch try await task.receive() {
        case .data(let data): return data
        case .string(let value): return Data(value.utf8)
        @unknown default: throw APIError.invalidResponse
        }
    }

    func send(_ data: Data) async throws {
        guard let text = String(data: data, encoding: .utf8) else { throw APIError.invalidResponse }
        try await task.send(.string(text))
    }

    func close() {
        task.cancel(with: .goingAway, reason: nil)
        session.invalidateAndCancel()
    }
}

@MainActor
final class BeaconLiveTransport {
    enum Event: Sendable {
        case frame(BeaconLiveProtocol.Incoming)
        case disconnected(String)
    }

    let events: AsyncStream<Event>
    private let continuation: AsyncStream<Event>.Continuation
    private let client: BeaconAPIClient
    private let url: URL
    private let makeSocket: @MainActor (URL) -> any BeaconSocket
    private let sleep: @Sendable (TimeInterval) async throws -> Void
    private let now: @Sendable () -> Date
    private var subscriptions = BeaconLiveProtocol.Subscriptions()
    private var socket: (any BeaconSocket)?
    private var loop: Task<Void, Never>?
    private var timer: Task<Void, Never>?
    private var sender: Task<Void, Never>?
    private var cancellationID: UUID?
    private var generation = UUID()
    private var awaiting: [String: Date] = [:]
    private var helloReceived = false
    private var ended = false
    private var foregroundedAt: Date?
    private var backoff: Task<Void, Error>?

    init(
        client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo,
        makeSocket: @escaping @MainActor (URL) -> any BeaconSocket = { BeaconURLSocket(url: $0) },
        sleep: @escaping @Sendable (TimeInterval) async throws -> Void = { try await Task.sleep(for: .seconds($0)) },
        now: @escaping @Sendable () -> Date = { .now }
    ) throws {
        try verifiedServerInfo.requireSupported()
        guard var components = URLComponents(url: client.baseURL, resolvingAgainstBaseURL: false) else {
            throw APIError.invalidURL
        }
        components.scheme = "wss"
        components.path = "/ws"
        guard let url = components.url else { throw APIError.invalidURL }
        self.url = url
        self.client = client
        self.makeSocket = makeSocket
        self.sleep = sleep
        self.now = now
        let stream = AsyncStream<Event>.makeStream(bufferingPolicy: .bufferingNewest(256))
        events = stream.stream
        continuation = stream.continuation
    }

    func update(subscriptions: Set<LiveFeedSubscription>, configuration: LiveFeedConfiguration) {
        guard !ended, client.sourceSession.isCurrent else { return }
        enqueue(self.subscriptions.update(subscriptions: subscriptions, configuration: configuration))
    }

    func connect() {
        guard loop == nil, !ended, client.sourceSession.isCurrent else { return }
        cancellationID = client.sourceSession.registerCancellation { [weak self] in
            Task { @MainActor in self?.disconnect() }
        }
        loop = Task { [weak self] in await self?.run() }
    }

    /// iOS drops a suspended app's sockets; reconnect without backoff unless the server asked us to wait.
    func resumeAfterForeground() {
        guard loop != nil, !ended else { return }
        foregroundedAt = now()
        backoff?.cancel()
    }

    func disconnect() {
        guard !ended else { return }
        ended = true
        loop?.cancel()
        loop = nil
        stopSocket()
        if let cancellationID { client.sourceSession.removeCancellation(cancellationID) }
        cancellationID = nil
        continuation.finish()
    }

    private func run() async {
        var attempt = 0
        while !Task.isCancelled && !ended && client.sourceSession.isCurrent {
            var delay = BeaconLiveProtocol.reconnectDelay(attempt: attempt)
            var coolingDown = false
            let started = now()
            do {
                try await client.checkCooldown()
                try client.sourceSession.check()
                guard !ended else { return }
                let socket = makeSocket(url)
                self.socket = socket
                let generation = self.generation
                startTimer(generation: generation)
                while !Task.isCancelled {
                    let data = try await socket.receive()
                    try client.sourceSession.check()
                    guard !ended, self.generation == generation else { return }
                    let frame = try BeaconLiveProtocol.decode(data)
                    switch frame {
                    case .hello:
                        guard !helloReceived else { throw APIError.invalidResponse }
                        helloReceived = true
                        enqueue(subscriptions.hello())
                    case .subscribed(let requestID, let subscriptionID):
                        awaiting[requestID] = nil
                        enqueue(subscriptions.acknowledge(requestID: requestID, subscriptionID: subscriptionID))
                    case .acknowledged(let requestID):
                        awaiting[requestID] = nil
                    default:
                        guard helloReceived else { throw APIError.invalidResponse }
                    }
                    emit(.frame(frame))
                    if case .hello = frame { emit(.frame(.recoveryRequired)) }
                }
            } catch {
                guard !Task.isCancelled, !ended, client.sourceSession.isCurrent else { return }
                if case BeaconAPIError.rateLimited(let deadline) = error {
                    delay = max(delay, deadline.timeIntervalSince(now()))
                    coolingDown = true
                }
                if socket?.response?.statusCode == 429 || socket?.closeCode == 1013 {
                    let retry = socket?.response?.value(forHTTPHeaderField: "Retry-After") ?? "60"
                    let deadline = await client.recordCooldown(retryAfter: retry)
                    delay = max(delay, deadline.timeIntervalSince(now()))
                    coolingDown = true
                }
                guard !ended, client.sourceSession.isCurrent else { return }
                emit(.disconnected(error.localizedDescription))
            }
            stopSocket()
            if now().timeIntervalSince(started) >= 60 { attempt = 0 } else { attempt = min(attempt + 1, 4) }
            // A drop right after returning to the foreground is the suspension, not the server.
            if !coolingDown, let foregroundedAt, now().timeIntervalSince(foregroundedAt) < 5 {
                self.foregroundedAt = nil
                attempt = 0
                continue
            }
            let sleep = self.sleep
            let wait = max(1, delay)
            let backoff = Task { try await sleep(wait) }
            if !coolingDown { self.backoff = backoff }
            _ = await withTaskCancellationHandler {
                await backoff.result
            } onCancel: {
                backoff.cancel()
            }
            self.backoff = nil
            if Task.isCancelled { return }
            if case .failure = await backoff.result { attempt = 0 }
        }
    }

    private func stopSocket() {
        generation = UUID()
        timer?.cancel()
        timer = nil
        sender?.cancel()
        sender = nil
        socket?.close()
        socket = nil
        awaiting = [:]
        helloReceived = false
        subscriptions.disconnected()
    }

    private func enqueue(_ commands: [BeaconLiveProtocol.Command]) {
        guard !commands.isEmpty, let socket else { return }
        let previous = sender
        let generation = generation
        sender = Task { [weak self] in
            await previous?.value
            guard let self, !Task.isCancelled, !self.ended, self.generation == generation else { return }
            do {
                for command in commands {
                    try self.client.sourceSession.check()
                    guard !Task.isCancelled, self.generation == generation else { return }
                    self.awaiting[command.id] = self.now().addingTimeInterval(40)
                    try await socket.send(command.encoded())
                }
            } catch {
                guard self.generation == generation else { return }
                socket.close()
            }
        }
    }

    private func startTimer(generation: UUID) {
        let started = now()
        timer = Task { [weak self] in
            guard let self else { return }
            var lastPing = started
            do {
                while !Task.isCancelled {
                    try await self.sleep(10)
                    guard !self.ended, self.generation == generation else { return }
                    try self.client.sourceSession.check()
                    let now = self.now()
                    if (!self.helloReceived && now.timeIntervalSince(started) >= 20) ||
                        self.awaiting.values.contains(where: { $0 <= now }) {
                        self.socket?.close()
                        return
                    }
                    if self.helloReceived && now.timeIntervalSince(lastPing) >= BeaconLiveProtocol.pingInterval {
                        lastPing = now
                        self.enqueue([.ping()])
                    }
                }
            } catch { return }
        }
    }

    private func emit(_ event: Event) {
        if case .dropped = continuation.yield(event) {
            continuation.yield(.frame(.recoveryRequired))
        }
    }
}
