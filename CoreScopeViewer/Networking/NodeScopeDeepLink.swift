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

/// The `meshcore://contact/add` link the MeshCore app reads from contact QR
/// codes and links (docs/qr_codes.md in meshcore-dev/MeshCore). A bare public
/// key isn't enough; the app also needs a name and a contact type.
struct MeshCoreContactLink: Equatable, Sendable {
    let name: String
    let publicKey: String
    let type: Int

    init?(node: MeshNode) {
        self.init(name: node.name, publicKey: node.publicKey, role: node.role)
    }

    init?(name: String?, publicKey: String, role: String) {
        let key = publicKey.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard key.count == 64, key.allSatisfy(\.isHexDigit),
              let type = Self.contactType(for: role) else {
            return nil
        }
        let trimmedName = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.name = trimmedName.isEmpty ? String(key.prefix(8)).uppercased() : trimmedName
        self.publicKey = key
        self.type = type
    }

    /// 1 companion, 2 repeater, 3 room server, 4 sensor.
    static func contactType(for role: String) -> Int? {
        switch role.lowercased() {
        case "companion", "chat": 1
        case "repeater": 2
        case "room", "room_server": 3
        case "sensor": 4
        default: nil
        }
    }

    var urlString: String {
        "meshcore://contact/add?name=\(Self.encode(name))&public_key=\(publicKey)&type=\(type)"
    }

    var url: URL? { URL(string: urlString) }

    /// Strict encoding: the app decodes queries form-style, so a literal "+"
    /// would come back as a space unless it's escaped too.
    private static func encode(_ value: String) -> String {
        let unreserved = CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
    }
}
