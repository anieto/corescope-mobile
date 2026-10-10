import Foundation
import Observation

/// Single shared live connection to the analyzer host, broadcasting
/// "packet" and "message" events (see "WebSocket Messages" in
/// docs/api-spec.md). Injected once via the environment so the map, live
/// feed, and channels screens all observe the same stream instead of each
/// opening their own socket. The socket itself lives in an `AnalyzerLiveFeed`.
@Observable
@MainActor
final class LiveFeedService {
    private(set) var recentEvents: [LiveEnvelope] = []
    private(set) var eventSequence = 0
    private(set) var isConnected = false
    private(set) var lastError: String?

    private let maxEvents = 200
    private let settings: AnalyzerSettings
    private let makeFeed: @MainActor (AnalyzerSettings) -> any AnalyzerLiveFeed
    private var feed: (any AnalyzerLiveFeed)?
    private var eventTask: Task<Void, Never>?
    private var connectedSource: String?
    private var generation = UUID()

    init(
        settings: AnalyzerSettings,
        makeFeed: @escaping @MainActor (AnalyzerSettings) -> any AnalyzerLiveFeed = AnalyzerBackendFactory.makeLiveFeed
    ) {
        self.settings = settings
        self.makeFeed = makeFeed
    }

    func connect() {
        disconnect()
        let source = settings.webSocketURL.absoluteString
        if connectedSource != source {
            recentEvents = []
            connectedSource = source
        }
        lastError = nil
        let feed = makeFeed(settings)
        self.feed = feed
        let generation = self.generation
        eventTask = Task { [weak self] in
            for await event in feed.events {
                guard let self, !Task.isCancelled, self.generation == generation else { return }
                switch event {
                case .packet(let envelope):
                    self.recentEvents.insert(envelope, at: 0)
                    self.eventSequence &+= 1
                    if self.recentEvents.count > self.maxEvents {
                        self.recentEvents.removeLast(self.recentEvents.count - self.maxEvents)
                    }
                case .connected:
                    self.isConnected = true
                    self.lastError = nil
                case .disconnected(let message):
                    self.isConnected = false
                    self.lastError = message
                case .decodingFailed(let message):
                    self.lastError = "Decode error: \(message)"
                }
            }
        }
        feed.connect()
    }

    func disconnect() {
        generation = UUID()
        eventTask?.cancel()
        eventTask = nil
        feed?.disconnect()
        feed = nil
        isConnected = false
    }
}
