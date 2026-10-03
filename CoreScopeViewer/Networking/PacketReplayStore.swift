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

    var resolvedPath: [String] {
        routes.indices.contains(selectedRouteIndex) ? routes[selectedRouteIndex].keys : []
    }

    var selectedRoute: RouteOption? {
        routes.indices.contains(selectedRouteIndex) ? routes[selectedRouteIndex] : nil
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
        selectedRouteIndex = routes.indices.contains(selectedIndex) ? selectedIndex : 0
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
        guard routes.indices.contains(index) else { return }
        selectedRouteIndex = index
    }
}
