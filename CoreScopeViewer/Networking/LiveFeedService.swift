import Foundation
import Observation

/// Single shared WebSocket connection to the analyzer host, broadcasting
/// "packet" and "message" events (see "WebSocket Messages" in
/// docs/api-spec.md). Injected once via the environment so the map, live
/// feed, and channels screens all observe the same stream instead of each
/// opening their own socket.
@Observable
@MainActor
final class LiveFeedService: NSObject, URLSessionWebSocketDelegate {
    private(set) var recentEvents: [LiveEnvelope] = []
    private(set) var isConnected = false
    private(set) var lastError: String?

    private let maxEvents = 200
    private let decoder = JSONDecoder()

    private var settings: AnalyzerSettings?
    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?

    init(settings: AnalyzerSettings) {
        self.settings = settings
        super.init()
        session = URLSession(
            configuration: .default,
            delegate: self,
            delegateQueue: nil
        )
    }

    func connect() {
        guard let settings else { return }
        disconnect()

        let socketTask = session.webSocketTask(with: settings.webSocketURL)
        task = socketTask
        socketTask.resume()
        isConnected = false
        lastError = nil
        listen()
    }

    func disconnect() {
        receiveTask?.cancel()
        reconnectTask?.cancel()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        isConnected = false
    }

    private func listen() {
        receiveTask = Task { [weak self] in
            while let self, !Task.isCancelled, let socketTask = self.task {
                do {
                    let message = try await socketTask.receive()
                    self.handle(message)
                } catch {
                    guard !Task.isCancelled else { return }
                    self.connectionFailed(error.localizedDescription)
                    return
                }
            }
        }
    }

    private func handle(_ message: URLSessionWebSocketTask.Message) {
        let data: Data
        switch message {
        case .string(let text):
            guard let converted = text.data(using: .utf8) else { return }
            data = converted
        case .data(let raw):
            data = raw
        @unknown default:
            return
        }

        do {
            let envelope = try decoder.decode(LiveEnvelope.self, from: data)
            // Heartbeat frames (see LiveEnvelope) carry no packet data and
            // aren't meant to be displayed — just proof the connection is
            // alive. Nothing else currently depends on receiving them.
            guard envelope.data != nil else { return }
            recentEvents.insert(envelope, at: 0)
            if recentEvents.count > maxEvents {
                recentEvents.removeLast(recentEvents.count - maxEvents)
            }
        } catch {
            lastError = "Decode error: \(error.localizedDescription)"
        }
    }

    private func scheduleReconnect() {
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
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
            guard let self, webSocketTask === self.task else { return }
            self.isConnected = true
            self.lastError = nil
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
            guard let self, webSocketTask === self.task else { return }
            self.connectionFailed(message)
        }
    }

    private func connectionFailed(_ message: String) {
        isConnected = false
        lastError = message
        scheduleReconnect()
    }
}
