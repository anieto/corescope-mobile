import CryptoKit
import Foundation

/// Saved-plans v3 input fingerprints (docs/node-planner/saved-plans-v3-contract-gate.md). SHA-256
/// over a versioned binary stream, identical on Android (PortableInputFingerprint.kt) and checked
/// against shared vectors in shared/fixtures/saved-plans/contract-vectors-v1.json. These tie a
/// saved result to the exact inputs it was calculated from; they are not the diagnostic
/// prediction fingerprint, which stays separate.
enum PortableInputFingerprint {
    enum Failure: Error, Equatable { case nonFinite }

    /// Wire encoding: binary64 and int64 big-endian, 0/1 booleans, u32-length UTF-8, u32 counts.
    struct Writer {
        private var bytes: [UInt8]

        init(domain: String) { bytes = Array(domain.utf8) + [0] }

        mutating func double(_ value: Double) throws {
            guard value.isFinite else { throw Failure.nonFinite }
            append(value.bitPattern) // keeps -0.0 distinct from 0.0
        }
        mutating func int64(_ value: Int64) { append(UInt64(bitPattern: value)) }
        mutating func boolean(_ value: Bool) { bytes.append(value ? 1 : 0) }
        mutating func string(_ value: String) {
            let encoded = Array(value.utf8) // stored bytes as given; no normalization
            count(encoded.count)
            bytes += encoded
        }
        mutating func count(_ value: Int) {
            let count = UInt32(value)
            bytes += [UInt8(count >> 24 & 0xff), UInt8(count >> 16 & 0xff), UInt8(count >> 8 & 0xff), UInt8(count & 0xff)]
        }
        mutating func optionalInt64(_ value: Int64?) {
            guard let value else { bytes.append(0); return }
            bytes.append(1)
            int64(value)
        }

        func sha256() -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }

        private mutating func append(_ value: UInt64) {
            for shift in stride(from: 56, through: 0, by: -8) { bytes.append(UInt8(value >> UInt64(shift) & 0xff)) }
        }
    }

    /// A captured node, as the coverage fingerprint records it.
    struct Node: Equatable {
        let sourceID: String
        let nodeID: String
        let latitude: Double
        let longitude: Double
    }

    /// Every input a coverage result depends on: the twelve doubles in saved inputValues order
    /// (transmitter latitude and longitude, frequency, transmit and receive heights, power, gains,
    /// losses, sensitivity, reserve), the six grid integers (radius, spacing, minEast, maxNorth,
    /// columns, rows), catalogComplete and the ordered captured nodes.
    static func coverage(doubles: [Double], grid: [Int64], catalogComplete: Bool, nodes: [Node]) throws -> String {
        precondition(doubles.count == 12 && grid.count == 6)
        var writer = Writer(domain: "nodescope.coverage-inputs.v1")
        for value in doubles { try writer.double(value) }
        for value in grid { writer.int64(value) }
        writer.boolean(catalogComplete)
        writer.count(nodes.count)
        for node in nodes {
            writer.string(node.sourceID)
            writer.string(node.nodeID)
            try writer.double(node.latitude)
            try writer.double(node.longitude)
        }
        return writer.sha256()
    }

    /// A Link Profile's calculation inputs: each endpoint's latitude, longitude, height, gain and
    /// loss, then frequency, power, sensitivity, refraction and the optional sample count.
    /// Endpoint names are labels, not inputs, and are left out.
    static func linkProfile(pointA: [Double], pointB: [Double], frequencyMHz: Double, transmitPowerDbm: Double,
                            sensitivityDbm: Double, refractionK: Double, sampleCount: Int64?) throws -> String {
        precondition(pointA.count == 5 && pointB.count == 5)
        var writer = Writer(domain: "nodescope.link-profile-inputs.v1")
        for value in pointA + pointB + [frequencyMHz, transmitPowerDbm, sensitivityDbm, refractionK] {
            try writer.double(value)
        }
        writer.optionalInt64(sampleCount)
        return writer.sha256()
    }
}
