import Foundation

enum NodeScopeDeepLink: Equatable, Sendable {
    case node(String)
    case observer(String)
    case channel(String)
    case packet(String)

    init?(url: URL) {
        guard url.scheme?.lowercased() == "nodescope",
              let kind = url.host?.lowercased() else {
            return nil
        }

        let identifier = url.pathComponents
            .filter { $0 != "/" }
            .joined(separator: "/")
            .removingPercentEncoding?
            .trimmingCharacters(in: .whitespacesAndNewlines)

        guard let identifier, !identifier.isEmpty else { return nil }

        switch kind {
        case "node":
            self = .node(identifier)
        case "observer":
            self = .observer(identifier)
        case "channel":
            self = .channel(identifier)
        case "packet":
            self = .packet(identifier)
        default:
            return nil
        }
    }

    var url: URL? {
        let pair: (kind: String, identifier: String) = switch self {
        case .node(let identifier): ("node", identifier)
        case .observer(let identifier): ("observer", identifier)
        case .channel(let identifier): ("channel", identifier)
        case .packet(let identifier): ("packet", identifier)
        }

        var components = URLComponents()
        components.scheme = "nodescope"
        components.host = pair.kind
        components.path = "/" + pair.identifier
        return components.url
    }
}
