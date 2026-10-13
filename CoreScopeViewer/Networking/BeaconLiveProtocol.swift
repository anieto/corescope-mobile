import Foundation

enum BeaconLiveProtocol {
    static let pingInterval: TimeInterval = 30

    static func reconnectDelay(attempt: Int) -> TimeInterval {
        let delays: [TimeInterval] = [1, 2, 5, 10, 30]
        return delays[max(0, min(attempt, delays.count - 1))]
    }

    struct Command: Encodable, Sendable {
        struct Scope: Encodable, Sendable {
            let iatas: [String]
            let payloadTypes: [Int]
            let events = ["packetObservation", "observerStatus", "nodeUpdate"]
        }

        let v = 1
        let type: String
        let id: String
        var scope: Scope?
        var subscriptionId: String?
        var resolvePath: Bool?
        var includeObserverKey: Bool?
        var includeRepeats: Bool?

        func encoded() throws -> Data {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            return try encoder.encode(self)
        }

        static func configure(_ configuration: LiveFeedConfiguration) -> Command {
            Command(type: "configure", id: UUID().uuidString,
                    resolvePath: configuration.resolvePath,
                    includeObserverKey: configuration.includeObserverKey,
                    includeRepeats: configuration.includeRepeats)
        }

        static func ping() -> Command { Command(type: "ping", id: UUID().uuidString) }
    }

    enum Incoming: Sendable {
        case hello
        case subscribed(requestID: String, subscriptionID: String)
        case acknowledged(requestID: String)
        case packet(Observation)
        case observer(ObserverStatus)
        case node(NodeUpdate)
        case recoveryRequired
        case ignored
    }

    struct Observation: Decodable, Sendable {
        struct Header: Decodable, Sendable {
            let payloadType: Int
            let routeType: Int
            let observationCount: Int
        }

        struct Hearing: Decodable, Sendable {
            let observerId: String
            let observerName: String?
            let observerPublicKey: String?
            let iata: String?
            let heardAt: Date
            let rssi: Double?
            let snr: Double?
            let resolvedPath: [BeaconPacketReader.ResolvedHop]?
        }

        let packetHash: String
        let packet: Header
        let observation: Hearing

        var observationIdentity: String { packetHash + "|" + observation.observerId.lowercased() }
        var resolvedPath: [String?]? { observation.resolvedPath?.map(\.confirmedKey) }
    }

    struct NodeUpdate: Decodable, Sendable {
        enum Position: Equatable, Sendable {
            case unchanged
            case clear
            case coordinate(latitude: Double, longitude: Double)
        }

        let nodeId: String
        let publicKey: String
        let nodeType: Int
        let name: String?
        let isObserver: Bool?
        let iatas: [BeaconNodeReader.Node.Region]?
        let position: Position

        private enum CodingKeys: String, CodingKey {
            case nodeId, publicKey, nodeType, name, isObserver, iatas, lat, lng
        }

        init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            nodeId = try values.decode(String.self, forKey: .nodeId)
            publicKey = try values.decode(String.self, forKey: .publicKey)
            nodeType = try values.decode(Int.self, forKey: .nodeType)
            name = try values.decodeIfPresent(String.self, forKey: .name)
            isObserver = try values.decodeIfPresent(Bool.self, forKey: .isObserver)
            iatas = try values.decodeIfPresent([BeaconNodeReader.Node.Region].self, forKey: .iatas)
            guard UUID(uuidString: nodeId) != nil,
                  publicKey.utf8.count == 64,
                  publicKey.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }) else {
                throw APIError.invalidResponse
            }
            if !values.contains(.lat), !values.contains(.lng) {
                position = .unchanged
            } else {
                guard values.contains(.lat), values.contains(.lng) else { throw APIError.invalidResponse }
                let latitude = try values.decodeIfPresent(Double.self, forKey: .lat)
                let longitude = try values.decodeIfPresent(Double.self, forKey: .lng)
                if let latitude, let longitude, latitude.isFinite, longitude.isFinite,
                   (-90...90).contains(latitude), (-180...180).contains(longitude),
                   latitude != 0 || longitude != 0 {
                    position = .coordinate(latitude: latitude, longitude: longitude)
                } else {
                    position = .clear
                }
            }
        }
    }

    struct ObserverStatus: Decodable, Sendable {
        let observerId: String
        let displayName: String?
        let iata: String?
        let online: Bool
        let batteryMv: Int?
        let uptimeSeconds: Int?
        let lastStatusAt: Date?
        var radio: String? = nil

        func applying(to observer: MeshObserver) -> MeshObserver {
            guard observer.id.lowercased() == observerId.lowercased() else { return observer }
            var result = MeshObserver(
                id: observer.id, name: displayName, iata: iata,
                lastSeen: observer.lastSeen, firstSeen: observer.firstSeen, packetCount: observer.packetCount,
                model: observer.model, firmware: observer.firmware, clientVersion: observer.clientVersion,
                radio: radio, batteryMv: batteryMv, uptimeSecs: uptimeSeconds, noiseFloor: observer.noiseFloor,
                packetsLastHour: observer.packetsLastHour, lat: observer.lat, lon: observer.lon,
                nodeRole: observer.nodeRole
            )
            result.online = online
            result.lastStatusAt = lastStatusAt
            return result
        }
    }

    struct NodeState: Sendable {
        private(set) var latest: NodeUpdate
        private(set) var name: String?
        private(set) var nameHeardAt: Date?
        private(set) var position: NodeUpdate.Position = .unchanged
        private(set) var positionHeardAt: Date?
        private(set) var regions: [String: BeaconNodeReader.Node.Region] = [:]

        init(_ update: NodeUpdate) {
            latest = update
            merge(update)
        }

        mutating func merge(_ update: NodeUpdate) {
            let heardAt = update.iatas?.compactMap(\.lastHeard).max()
            let previousHeardAt = regions.values.compactMap(\.lastHeard).max()
            let isOlder = previousHeardAt.map { previous in heardAt.map { $0 < previous } ?? false } ?? false
            if !isOlder {
                latest = update
                if let name = update.name {
                    self.name = name
                    nameHeardAt = heardAt
                }
                if update.position != .unchanged {
                    position = update.position
                    positionHeardAt = heardAt
                }
            }
            // Each advert carries only the membership that heard it, not a full region list.
            for region in update.iatas ?? [] {
                let code = region.iata.uppercased()
                if let previous = regions[code]?.lastHeard,
                   region.lastHeard == nil || region.lastHeard.map({ $0 < previous }) == true { continue }
                regions[code] = region
            }
        }

        func applying(to existing: MeshNode?) -> MeshNode {
            let heardAt = regions.values.compactMap(\.lastHeard).max()
            let existingActivity = existing?.latestActivity
            let older = existingActivity.map { date in heardAt.map { $0 < date } ?? true } ?? false
            let olderPosition = existingActivity.map { date in positionHeardAt.map { $0 < date } ?? true } ?? false
            let olderName = existingActivity.map { date in nameHeardAt.map { $0 < date } ?? true } ?? false
            var latitude = existing?.lat
            var longitude = existing?.lon
            if !olderPosition {
                switch position {
                case .unchanged: break
                case .clear: latitude = nil; longitude = nil
                case .coordinate(let lat, let lon): latitude = lat; longitude = lon
                }
            }
            let role: String
            switch latest.nodeType {
            case 1: role = "companion"
            case 2: role = "repeater"
            case 3: role = "room"
            case 4: role = "sensor"
            default: role = "unknown"
            }
            return MeshNode(
                publicKey: existing?.publicKey ?? latest.publicKey.lowercased(),
                name: olderName ? existing?.name : name ?? existing?.name,
                role: older ? existing?.role ?? role : role, lat: latitude, lon: longitude,
                lastSeen: existing?.lastSeen, firstSeen: existing?.firstSeen,
                advertCount: existing?.advertCount, hashSize: existing?.hashSize,
                hashSizeInconsistent: existing?.hashSizeInconsistent,
                lastHeard: [existing?.lastHeard, heardAt].compactMap { $0 }.max(),
                defaultScope: existing?.defaultScope
            )
        }
    }

    private struct Envelope: Decodable {
        let v: Int
        let type: String
        let event: String?
        let id: String?
        let subscriptionId: String?
    }

    private struct Event<Payload: Decodable>: Decodable {
        let data: Payload
    }

    static func decode(_ data: Data) throws -> Incoming {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        let envelope = try decoder.decode(Envelope.self, from: data)
        guard envelope.v == 1 else { throw APIError.invalidResponse }
        switch envelope.type {
        case "hello": return .hello
        case "subscribed":
            guard let request = envelope.id, let subscription = envelope.subscriptionId,
                  !request.isEmpty, !subscription.isEmpty else { throw APIError.invalidResponse }
            return .subscribed(requestID: request, subscriptionID: subscription)
        case "lagged": return .recoveryRequired
        case "configured", "unsubscribed", "pong":
            guard let identifier = envelope.id else { throw APIError.invalidResponse }
            return .acknowledged(requestID: identifier)
        case "event":
            switch envelope.event {
            case "packetObservation":
                let value = try decoder.decode(Event<Observation>.self, from: data).data
                guard !value.packetHash.isEmpty, value.packet.observationCount >= 0,
                      UUID(uuidString: value.observation.observerId) != nil else {
                    throw APIError.invalidResponse
                }
                return .packet(value)
            case "observerStatus":
                let value = try decoder.decode(Event<ObserverStatus>.self, from: data).data
                guard UUID(uuidString: value.observerId) != nil else { throw APIError.invalidResponse }
                return .observer(value)
            case "nodeUpdate":
                return .node(try decoder.decode(Event<NodeUpdate>.self, from: data).data)
            default: return .ignored
            }
        default: return .ignored
        }
    }

    struct Subscriptions {
        private var desired: Set<LiveFeedSubscription> = []
        private var pending: [String: LiveFeedSubscription] = [:]
        private var active: [LiveFeedSubscription: String] = [:]
        private var connected = false
        private var configuration = LiveFeedConfiguration()

        mutating func update(
            subscriptions: Set<LiveFeedSubscription>, configuration: LiveFeedConfiguration
        ) -> [Command] {
            desired = subscriptions
            let changed = self.configuration != configuration
            self.configuration = configuration
            guard connected else { return [] }
            var commands = reconcile()
            if changed { commands.append(.configure(configuration)) }
            return commands
        }

        mutating func hello() -> [Command] {
            connected = true
            pending = [:]
            active = [:]
            return [.configure(configuration)] + reconcile()
        }

        mutating func disconnected() {
            connected = false
            pending = [:]
            active = [:]
        }

        mutating func acknowledge(requestID: String, subscriptionID: String) -> [Command] {
            guard connected, let subscription = pending.removeValue(forKey: requestID) else { return [] }
            if desired.contains(subscription) {
                active[subscription] = subscriptionID
                return []
            }
            // A screen can disappear before its subscribe acknowledgement arrives.
            return [Command(type: "unsubscribe", id: UUID().uuidString, subscriptionId: subscriptionID)]
        }

        private mutating func reconcile() -> [Command] {
            var commands: [Command] = []
            for (subscription, identifier) in active where !desired.contains(subscription) {
                commands.append(Command(type: "unsubscribe", id: UUID().uuidString, subscriptionId: identifier))
            }
            active = active.filter { desired.contains($0.key) }
            let awaiting = Set(pending.values)
            for subscription in desired where active[subscription] == nil && !awaiting.contains(subscription) {
                let requestID = UUID().uuidString
                pending[requestID] = subscription
                commands.append(Command(
                    type: "subscribe", id: requestID,
                    scope: .init(iatas: subscription.regions.sorted(), payloadTypes: subscription.payloadTypes.sorted())
                ))
            }
            return commands
        }
    }
}
