import Foundation

struct LiveFeedConfiguration: Equatable, Sendable {
    var resolvePath = false
    var includeObserverKey = false
    var includeRepeats = false
    var recoverObservers = false
}

struct LiveFeedSubscription: Hashable, Sendable {
    var regions: Set<String> = []
    var payloadTypes: Set<Int> = []
}

struct LiveFeedDemand: Equatable, Sendable {
    var subscription = LiveFeedSubscription()
    var configuration = LiveFeedConfiguration()
}

struct LiveBrowseFilter: Hashable, Sendable {
    let region: String?
    let payloadType: Int?
}

struct LiveBrowseSnapshot: Sendable {
    let filter: LiveBrowseFilter
    let rows: [BeaconLiveRecovery.Row]
    let possiblyIncomplete: Bool
}

enum AnalyzerLiveEvent: Sendable {
    case packet(LiveEnvelope)
    case browseSnapshot(LiveBrowseSnapshot)
    case nodeUpdated(BeaconLiveProtocol.NodeUpdate)
    case observerStatus(BeaconLiveProtocol.ObserverStatus)
    case metadataInvalidated
    case nodeCatalog([BeaconNodeReader.Node])
    case metadataRecoveryFailed(String)
    case observerCatalog([BeaconObserverReader.Observer])
    case observerRecoveryFailed(String)
    case observerCatalogInvalidated
    case browseRemoved(LiveBrowseFilter)
    case recoveryFailed(LiveBrowseFilter, String)
    case recoveryCompleted(LiveBrowseFilter)
    case connected
    case disconnected(String?)
    case decodingFailed(String)
}

@MainActor
protocol AnalyzerLiveFeed: AnyObject {
    var events: AsyncStream<AnalyzerLiveEvent> { get }
    var usesBrowseSnapshots: Bool { get }
    func connect()
    func disconnect()
    func subscribe(_ subscriptions: Set<LiveFeedSubscription>)
    func configure(_ configuration: LiveFeedConfiguration)
    func refreshObservers()
    /// The app just returned to the foreground. iOS drops a suspended app's sockets,
    /// so check the connection now and skip any reconnect backoff if it died.
    func resumeAfterForeground()
}

extension AnalyzerLiveFeed {
    var usesBrowseSnapshots: Bool { false }
    func refreshObservers() {}
    func resumeAfterForeground() {}
}

@MainActor
protocol LiveObserverDetailProvider: AnyObject {
    func observerDetail(id: String) async throws -> MeshObserver
}

@MainActor
protocol LivePacketDetailProvider: AnyObject {
    func packetDetail(hash: String) async throws -> PacketDetailResponse
}

@MainActor
final class UnavailableBeaconLiveFeed: AnalyzerLiveFeed {
    let usesBrowseSnapshots = true
    let events: AsyncStream<AnalyzerLiveEvent>

    init(message: String) {
        events = AsyncStream { continuation in
            continuation.yield(.disconnected(message))
            continuation.finish()
        }
    }

    func connect() {}
    func disconnect() {}
    func subscribe(_ subscriptions: Set<LiveFeedSubscription>) {}
    func configure(_ configuration: LiveFeedConfiguration) {}
}

@MainActor
final class CoreScopeLiveFeed: NSObject, AnalyzerLiveFeed, URLSessionWebSocketDelegate {
    // CoreScope broadcasts its fixed payload; it has no subscription/configuration frames.
    func subscribe(_ subscriptions: Set<LiveFeedSubscription>) {}
    func configure(_ configuration: LiveFeedConfiguration) {}

    let events: AsyncStream<AnalyzerLiveEvent>
    private let continuation: AsyncStream<AnalyzerLiveEvent>.Continuation
    private let url: URL
    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var foregroundedAt: Date?

    init(url: URL) {
        self.url = url
        let stream = AsyncStream<AnalyzerLiveEvent>.makeStream()
        events = stream.stream
        continuation = stream.continuation
        super.init()
    }

    func connect() {
        stopSocket()
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        self.session = session
        let socket = session.webSocketTask(with: url)
        self.socket = socket
        continuation.yield(.disconnected(nil))
        socket.resume()
        receiveTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    let message = try await socket.receive()
                    guard let self, self.socket === socket, !Task.isCancelled else { return }
                    do {
                        if let envelope = try Self.decode(message) {
                            self.continuation.yield(.packet(envelope))
                        }
                    } catch {
                        self.continuation.yield(.decodingFailed(error.localizedDescription))
                    }
                } catch {
                    guard let self, self.socket === socket, !Task.isCancelled else { return }
                    self.connectionFailed(error.localizedDescription)
                    return
                }
            }
        }
    }

    func disconnect() {
        stopSocket()
        continuation.finish()
    }

    func resumeAfterForeground() {
        guard session != nil || reconnectTask != nil else { return }
        foregroundedAt = .now
        guard let socket, reconnectTask == nil else {
            connect()
            return
        }
        // A socket killed during suspension can still look open until the next read fails.
        socket.sendPing { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor [weak self] in
                guard let self, self.socket === socket else { return }
                self.connectionFailed(error?.localizedDescription ?? "Connection lost.")
            }
        }
    }

    private func stopSocket() {
        receiveTask?.cancel()
        reconnectTask?.cancel()
        reconnectTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
    }

    nonisolated static func decode(_ message: URLSessionWebSocketTask.Message) throws -> LiveEnvelope? {
        let data: Data
        switch message {
        case .string(let text):
            data = Data(text.utf8)
        case .data(let bytes):
            data = bytes
        @unknown default:
            return nil
        }
        let envelope = try JSONDecoder().decode(LiveEnvelope.self, from: data)
        // Heartbeat frames (see LiveEnvelope) carry no packet data and
        // aren't meant to be displayed — just proof the connection is
        // alive. Nothing else currently depends on receiving them.
        return envelope.data == nil ? nil : envelope
    }

    private func connectionFailed(_ message: String) {
        continuation.yield(.disconnected(message))
        reconnectTask?.cancel()
        // Failures right after returning to the foreground are the suspension, not the server.
        let delay: Duration = foregroundedAt.map { Date.now.timeIntervalSince($0) < 5 } == true ? .zero : .seconds(3)
        foregroundedAt = nil
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard let self, !Task.isCancelled else { return }
            self.connect()
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        Task { @MainActor [weak self] in
            guard let self, webSocketTask === self.socket else { return }
            self.continuation.yield(.connected)
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        let message = reason.flatMap { String(data: $0, encoding: .utf8) }
            ?? "Server closed the connection (code \(closeCode.rawValue))."
        Task { @MainActor [weak self] in
            guard let self, webSocketTask === self.socket else { return }
            self.connectionFailed(message)
        }
    }
}
