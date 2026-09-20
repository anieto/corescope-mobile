import Foundation
import Testing
@testable import CoreScopeViewer

struct ModelDecodingTests {
    @Test func decodesNodesResponse() throws {
        let json = """
        {
          "nodes": [
            {
              "public_key": "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd12",
              "name": "SAT-Repeater-1",
              "role": "repeater",
              "lat": 29.4241,
              "lon": -98.4936,
              "last_seen": "2026-09-01T12:00:00.000Z",
              "first_seen": "2026-01-01T00:00:00.000Z",
              "advert_count": 42,
              "hash_size": 2,
              "hash_size_inconsistent": false
            }
          ],
          "total": 1,
          "counts": { "repeaters": 1, "rooms": 0, "companions": 0, "sensors": 0 }
        }
        """.data(using: .utf8)!

        let response = try APIClient.makeDecoder().decode(NodesResponse.self, from: json)

        #expect(response.nodes.count == 1)
        #expect(response.nodes[0].role == "repeater")
        #expect(response.nodes[0].coordinate?.latitude == 29.4241)
        #expect(response.total == 1)
        #expect(response.counts.repeaters == 1)
    }

    @Test func decodesNodeWithoutOptionalFields() throws {
        // /api/nodes/:pubkey/health returns a "full node row" without
        // hash_size / last_heard / default_scope — the shared MeshNode
        // model must still decode it.
        let json = """
        {
          "public_key": "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd12",
          "name": null,
          "role": "sensor",
          "lat": null,
          "lon": null,
          "last_seen": "2026-09-01T12:00:00Z",
          "first_seen": "2026-01-01T00:00:00Z",
          "advert_count": 3
        }
        """.data(using: .utf8)!

        let node = try APIClient.makeDecoder().decode(MeshNode.self, from: json)

        #expect(node.name == nil)
        #expect(node.coordinate == nil)
        #expect(node.hashSizeInconsistent == nil)
    }

    @Test func decodesLiveEnvelope() throws {
        let json = """
        {
          "type": "packet",
          "data": {
            "id": 991,
            "raw": "4F01A3",
            "decoded": {
              "header": {
                "routeType": 1,
                "payloadType": 4,
                "payloadVersion": 1,
                "payloadTypeName": "ADVERT"
              },
              "path": { "hops": ["a1", "b2"] }
            },
            "snr": 6.5,
            "rssi": -92,
            "hash": "deadbeef",
            "observer_id": "obs-1",
            "observer_name": "SAT Repeater",
            "path_json": "[\\"a1\\",\\"b2\\"]",
            "observation_count": 3,
            "resolved_path": ["a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9", null]
          }
        }
        """.data(using: .utf8)!

        let envelope = try JSONDecoder().decode(LiveEnvelope.self, from: json)
        let data = try #require(envelope.data)

        #expect(envelope.type == "packet")
        #expect(data.decoded?.header?.payloadTypeName == "ADVERT")
        #expect(data.snr == 6.5)
        #expect(envelope.id == 991)
        // The published docs call this field "observer" but the live server
        // actually sends "observer_id" — verified against raw WS traffic.
        #expect(data.observerId == "obs-1")
        // `resolved_path` is undocumented but present on real traffic: full
        // pubkeys aligned 1:1 with `path.hops`, with `null` where the server
        // itself couldn't resolve that hop.
        #expect(data.resolvedPath?.count == 2)
        #expect(data.resolvedPath?[0] == "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")
        #expect(data.resolvedPath?[1] == nil)
    }

    @Test func decodesLiveEnvelopeWithMissingDecoded() throws {
        let json = """
        {
          "type": "packet",
          "data": {
            "id": 992,
            "observer_id": "obs-2",
            "path_json": "[\\"c3\\",\\"d4\\"]"
          }
        }
        """.data(using: .utf8)!

        let envelope = try JSONDecoder().decode(LiveEnvelope.self, from: json)
        let data = try #require(envelope.data)

        #expect(envelope.type == "packet")
        #expect(envelope.id == 992)
        #expect(data.decoded == nil)
        #expect(data.pathJson == #"["c3","d4"]"#)
    }

    @Test func decodesHeartbeatWithoutData() throws {
        let json = #"{"type":"heartbeat"}"#.data(using: .utf8)!

        let envelope = try JSONDecoder().decode(LiveEnvelope.self, from: json)

        #expect(envelope.type == "heartbeat")
        #expect(envelope.data == nil)
        #expect(envelope.id == -1)
    }

    @Test func decodesRegionsMap() throws {
        let json = """
        { "AUS": "Austin, TX", "SAT": "San Antonio, TX" }
        """.data(using: .utf8)!

        let regions = try JSONDecoder().decode(RegionsMap.self, from: json)

        #expect(regions["AUS"] == "Austin, TX")
        #expect(regions.count == 2)
    }

    @Test func decodesIataCoordinatesWithoutRadius() throws {
        let json = """
        {
          "coords": {
            "AUS": { "lat": 30.1975, "lon": -97.6664 }
          }
        }
        """.data(using: .utf8)!

        let response = try JSONDecoder().decode(IataCoordsResponse.self, from: json)

        #expect(response.coords["AUS"]?.lat == 30.1975)
        #expect(response.coords["AUS"]?.radiusKm == nil)
    }

    @Test func payloadTypeNameFallsBackForUnknownCodes() {
        #expect(PayloadType.name(for: 4) == "ADVERT")
        #expect(PayloadType.name(for: 999) == "UNKNOWN(999)")
    }
}
