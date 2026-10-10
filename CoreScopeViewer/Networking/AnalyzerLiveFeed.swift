import Foundation

enum AnalyzerLiveEvent: Sendable {
    case packet(LiveEnvelope)
    case connected
    case disconnected(String?)
    case decodingFailed(String)
}

@MainActor
protocol AnalyzerLiveFeed: AnyObject {
    var events: AsyncStream<AnalyzerLiveEvent> { get }
    func connect()
    func disconnect()
}

@MainActor
final class CoreScopeLiveFeed: NSObject, AnalyzerLiveFeed, URLSessionWebSocketDelegate {
    let events: AsyncStream<AnalyzerLiveEvent>
    private let continuation: AsyncStream<AnalyzerLiveEvent>.Continuation
    private let url: URL
    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?

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

    private func stopSocket() {
        receiveTask?.cancel()
        reconnectTask?.cancel()
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
