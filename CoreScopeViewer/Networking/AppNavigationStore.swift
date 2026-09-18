import Foundation
import Observation

enum AppNavigationDestination: Sendable {
    case mapNode(MeshNode)
    case activeNodes
    case observer(String)
    case activeObservers
}

@Observable
@MainActor
final class AppNavigationStore {
    private(set) var requestID = UUID()
    private(set) var destination: AppNavigationDestination?

    func showOnMap(_ node: MeshNode) {
        destination = .mapNode(node)
        requestID = UUID()
    }

    func openObserver(id: String) {
        destination = .observer(id)
        requestID = UUID()
    }

    func showActiveNodes() {
        destination = .activeNodes
        requestID = UUID()
    }

    func showActiveObservers() {
        destination = .activeObservers
        requestID = UUID()
    }
}
