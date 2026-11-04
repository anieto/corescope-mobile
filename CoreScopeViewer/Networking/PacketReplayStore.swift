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
        selectedIndex: Int = 0,
        packetHash: String,
        observedAt: Date,
        snr: Double?,
        rssi: Double?,
        sender: String?,
        messageText: String?
    ) {
        self.routes = routes
        // With Realistic propagation on, a replay shows the whole packet, like the live map does.
        if routes.count > 1, UserDefaults.standard.bool(forKey: LiveMapPreferences.realisticPropagationKey) {
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
