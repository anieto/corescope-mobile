import Foundation
import Observation

@Observable
@MainActor
final class PacketReplayStore {
    private(set) var requestID = UUID()
    private(set) var stopRequestID = UUID()
    private(set) var isReplayActive = false
    private(set) var routes: [RouteOption] = []
    private(set) var selectedRouteIndex = 0
    private(set) var packetHash = ""
    private(set) var observedAt = Date.now
    private(set) var snr: Double?
    private(set) var rssi: Double?
    private(set) var sender: String?
    private(set) var messageText: String?

    /// `selectedRouteIndex` value that replays every route of the packet together.
    nonisolated static let allRoutes = -1

    var showsAllRoutes: Bool { selectedRouteIndex == Self.allRoutes }

    /// The one route a picker's [selection] shows, or nil for All Routes (only offered with more
    /// than one route; with one, that route is shown whatever the selection).
    nonisolated static func singleRoute(_ selection: Int, count: Int) -> Int? {
        if count == 1 { return 0 }
        return (0..<count).contains(selection) ? selection : nil
    }

    /// The routes being replayed, with their index in `routes`: all of them, or the selected one.
    var shownRoutes: [(index: Int, route: RouteOption)] {
        if showsAllRoutes { return Array(routes.enumerated()).map { (index: $0.offset, route: $0.element) } }
        return routes.indices.contains(selectedRouteIndex) ? [(selectedRouteIndex, routes[selectedRouteIndex])] : []
    }

    /// Every node on the shown routes, in route order, each once.
    var resolvedPath: [String] {
        var seen = Set<String>()
        return shownRoutes.flatMap(\.route.keys).filter { seen.insert($0.lowercased()).inserted }
    }

    func replay(
        routes: [RouteOption],
        selectedIndex: Int = PacketReplayStore.allRoutes,
        packetHash: String,
        observedAt: Date,
        snr: Double?,
        rssi: Double?,
        sender: String?,
        messageText: String?
    ) {
        self.routes = routes
        // Every route together unless one was chosen (packet details' route picker), so a replay
        // shows how the packet spread rather than one observer's part of it.
        if routes.count > 1, selectedIndex == Self.allRoutes {
            selectedRouteIndex = Self.allRoutes
        } else {
            selectedRouteIndex = routes.indices.contains(selectedIndex) ? selectedIndex : 0
        }
        self.packetHash = packetHash
        self.observedAt = observedAt
        self.snr = snr
        self.rssi = rssi
        self.sender = sender
        self.messageText = messageText
        isReplayActive = true
        requestID = UUID()
    }

    func stopReplay() {
        guard isReplayActive else { return }
        isReplayActive = false
        stopRequestID = UUID()
    }

    func selectRoute(at index: Int) {
        guard routes.indices.contains(index) || (index == Self.allRoutes && routes.count > 1) else { return }
        selectedRouteIndex = index
    }
}

/// Live traffic paused by hand, from the Live Packets panel or the map. The panel's list stops
/// changing and the map stops drawing new traffic (anything already moving finishes), so the two
/// stay in step. Packets keep arriving meanwhile; `newPacketCount` says how many, and resuming
/// returns to live rather than replaying what was missed.
@Observable
@MainActor
final class LiveTrafficPause {
    private(set) var isPaused = false
    private var newPacketHashes: Set<String> = []

    var newPacketCount: Int { newPacketHashes.count }

    func pause() {
        guard !isPaused else { return }
        newPacketHashes = []
        isPaused = true
    }

    func resume() {
        isPaused = false
        newPacketHashes = []
    }

    /// Counts a packet that arrived while paused (by hash, so its other observations don't).
    func noteArrival(hash: String) {
        guard isPaused, !newPacketHashes.contains(hash) else { return }
        newPacketHashes.insert(hash)
    }
}
