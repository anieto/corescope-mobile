import Foundation
import Observation

enum AppNavigationDestination: Sendable {
    case mapNode(MeshNode)
    case activeNodes
    case node(String)
    case observer(String)
    case channel(String)
    case packet(String)
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

    func open(_ deepLink: NodeScopeDeepLink) {
        destination = switch deepLink {
        case .node(let publicKey): .node(publicKey)
        case .observer(let id): .observer(id)
        case .channel(let hash): .channel(hash)
        case .packet(let hash): .packet(hash)
        }
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
