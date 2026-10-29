import Foundation

struct FieldSnapshot: Codable, Equatable, Sendable {
    static let schema = "org.nodescope.field.snapshot"
    static let version = 1

    enum Activity: String, Codable, Sendable {
        case nearby
        case plan
    }

    struct Source: Codable, Equatable, Sendable {
        let backend: String
        let origin: String
        let displayName: String
        let regionScope: String?
    }

    struct Coordinate: Codable, Equatable, Sendable {
        let latitude: Double
        let longitude: Double

        init?(_ coordinate: FieldCoordinate) {
            latitude = coordinate.latitude
            longitude = coordinate.longitude
        }
    }

    struct Target: Codable, Equatable, Sendable {
        let kind: FieldEntityID.Kind
        let identifier: String
        let displayName: String
        let role: String?
        let reportedCoordinate: Coordinate?
        let coordinateAt: String?
        let activityAt: String?
        let catalogFetchedAt: String
    }

    struct Reference: Codable, Equatable, Sendable {
        enum Category: String, Codable, Sendable {
            case device
            case manual
        }

        let category: Category
        let coordinate: Coordinate
        let measuredAt: String?
        let horizontalAccuracyMeters: Double?
    }

    struct Geometry: Codable, Equatable, Sendable {
        let distanceMeters: Double
        let trueBearingDegrees: Double?
    }

    struct State: Codable, Equatable, Sendable {
        let cached: Bool
        let partial: Bool
        let unsupported: [String]
        let limitations: [String]
    }

    let schema: String
    let version: Int
    let activity: Activity
    let capturedAt: String
    let appVersion: String
    let source: Source
    let target: Target?
    /// Present only after an explicit per-export opt-in.
    let reference: Reference?
    let geometry: Geometry?
    let packetIdentifiers: [String]
    let routeIdentifiers: [String]
    let state: State

    init(
        activity: Activity,
        capturedAt: String,
        appVersion: String,
        source: Source,
        target: Target? = nil,
        reference: Reference? = nil,
        geometry: Geometry? = nil,
        packetIdentifiers: [String] = [],
        routeIdentifiers: [String] = [],
        state: State
    ) {
        schema = Self.schema
        version = Self.version
        self.activity = activity
        self.capturedAt = capturedAt
        self.appVersion = appVersion
        self.source = source
        self.target = target
        self.reference = reference
        self.geometry = geometry
        self.packetIdentifiers = packetIdentifiers
        self.routeIdentifiers = routeIdentifiers
        self.state = state
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        schema = try values.decode(String.self, forKey: .schema)
        version = try values.decode(Int.self, forKey: .version)
        guard schema == Self.schema, version == Self.version else {
            throw DecodingError.dataCorruptedError(
                forKey: .version,
                in: values,
                debugDescription: "Unsupported Field Snapshot schema or version"
            )
        }
        activity = try values.decode(Activity.self, forKey: .activity)
        capturedAt = try values.decode(String.self, forKey: .capturedAt)
        appVersion = try values.decode(String.self, forKey: .appVersion)
        source = try values.decode(Source.self, forKey: .source)
        target = try values.decodeIfPresent(Target.self, forKey: .target)
        reference = try values.decodeIfPresent(Reference.self, forKey: .reference)
        geometry = try values.decodeIfPresent(Geometry.self, forKey: .geometry)
        packetIdentifiers = try values.decodeIfPresent([String].self, forKey: .packetIdentifiers) ?? []
        routeIdentifiers = try values.decodeIfPresent([String].self, forKey: .routeIdentifiers) ?? []
        state = try values.decode(State.self, forKey: .state)
    }
}
