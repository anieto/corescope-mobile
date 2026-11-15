import Foundation
import Observation

/// A tool a What's New item can open directly.
enum AppFeature: String, Decodable, Sendable {
    case nodePlanner, estimatedCoverage, linkProfile, savedPlans, nodeCheck
}

enum AppNavigationDestination: Sendable {
    case feature(AppFeature)
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

    func open(_ feature: AppFeature) {
        destination = .feature(feature)
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
