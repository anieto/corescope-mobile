import Foundation
import Observation

@Observable
@MainActor
final class PacketReplayStore {
    private(set) var requestID = UUID()
    private(set) var routes: [[String]] = []
    private(set) var selectedRouteIndex = 0
    private(set) var packetHash = ""

    var resolvedPath: [String] {
        routes.indices.contains(selectedRouteIndex) ? routes[selectedRouteIndex] : []
    }

    func replay(routes: [[String]], selectedIndex: Int = 0, packetHash: String) {
        self.routes = routes
        selectedRouteIndex = routes.indices.contains(selectedIndex) ? selectedIndex : 0
        self.packetHash = packetHash
        requestID = UUID()
    }

    func selectRoute(at index: Int) {
        guard routes.indices.contains(index) else { return }
        selectedRouteIndex = index
    }
}
