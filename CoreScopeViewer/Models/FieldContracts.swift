import Foundation

struct FieldSourceID: Codable, Hashable, Sendable {
    let backend: String
    let origin: String

    init?(backend: String, origin: String) {
        let normalizedBackend = backend.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let canonicalOrigin = origin.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedBackend.isEmpty, !canonicalOrigin.isEmpty else { return nil }
        self.backend = normalizedBackend
        self.origin = canonicalOrigin
    }
}

struct FieldEntityID: Codable, Hashable, Identifiable, Sendable {
    enum Kind: String, Codable, Sendable {
        case node
        case observer
    }

    let source: FieldSourceID
    let kind: Kind
    let value: String

    init?(source: FieldSourceID, kind: Kind, value: String) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= 512 else { return nil }
        self.source = source
        self.kind = kind
        self.value = kind == .node ? trimmed.lowercased() : trimmed
    }

    var id: String {
        Self.encode([source.backend, source.origin, kind.rawValue, value])
    }

    private static func encode(_ components: [String]) -> String {
        components.map { "\($0.utf8.count):\($0)" }.joined()
    }
}

enum FieldQRCode: Equatable, Sendable {
    case nodeScope(kind: FieldEntityID.Kind, value: String)
    case meshCoreContact(MeshCoreContactLink)
    case publicKey(String)

    static let maximumPayloadBytes = 2_048

    init?(payload: String) {
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= Self.maximumPayloadBytes else { return nil }

        if let key = Self.publicKey(trimmed) {
            self = .publicKey(key)
            return
        }

        guard let components = URLComponents(string: trimmed),
              components.user == nil,
              components.password == nil,
              components.port == nil,
              components.fragment == nil else { return nil }

        switch components.scheme?.lowercased() {
        case "nodescope":
            guard components.query == nil,
                  let linkURL = components.url,
                  let link = NodeScopeDeepLink(url: linkURL) else { return nil }
            switch link {
            case .node(let value):
                guard let key = Self.publicKey(value) else { return nil }
                self = .nodeScope(kind: .node, value: key)
            case .observer(let value):
                guard !value.isEmpty, value.utf8.count <= 512 else { return nil }
                self = .nodeScope(kind: .observer, value: value)
            case .channel, .packet:
                return nil
            }
        case "meshcore":
            guard components.host?.lowercased() == "contact",
                  components.path == "/add",
                  let items = components.queryItems,
                  items.count == 3,
                  Set(items.map(\.name)) == Set(["name", "public_key", "type"]),
                  let name = items.first(where: { $0.name == "name" })?.value,
                  let key = items.first(where: { $0.name == "public_key" })?.value,
                  let typeValue = items.first(where: { $0.name == "type" })?.value,
                  let type = Int(typeValue),
                  let contact = MeshCoreContactLink(name: name, publicKey: key, type: type) else { return nil }
            self = .meshCoreContact(contact)
        default:
            return nil
        }
    }

    private static func publicKey(_ value: String) -> String? {
        let normalized = value.lowercased()
        guard normalized.count == 64,
              normalized.unicodeScalars.allSatisfy({ scalar in
                  (48...57).contains(scalar.value) || (97...102).contains(scalar.value)
              }) else { return nil }
        return normalized
    }
}

enum FieldRequestBudget {
    static let automaticCatalogRefreshInterval: TimeInterval = 30
    static let staleCatalogLifetime: TimeInterval = 7 * 24 * 60 * 60
    static let pinnedEvidenceFreshLifetime: TimeInterval = 5 * 60
    static let maximumBeaconCatalogPagesPerKind = 20
    static let maximumLivePackets = 200
    static let maximumLiveMetadataEntries = 1_000
}
