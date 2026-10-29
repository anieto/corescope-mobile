import Foundation
import MapKit
import Testing
@testable import CoreScopeViewer

@MainActor
struct FieldToolsPresentationTests {
    @Test func distanceOrderingFilteringAndSearchArePreparedInTheModel() throws {
        let decoder = APIClient.makeDecoder()
        let nodes = try decoder.decode(NodesResponse.self, from: Data("""
        {
          "nodes": [
            {"public_key":"far","name":"Far Relay","role":"repeater","lat":30.0,"lon":-98.0},
            {"public_key":"near","name":"Near Relay","role":"repeater","lat":30.0,"lon":-97.01},
            {"public_key":"unset","name":"Unset","role":"repeater","lat":0.0,"lon":0.0}
          ],
          "total":3,
          "counts":{"repeaters":3,"rooms":0,"companions":0,"sensors":0}
        }
        """.utf8))
        let observers = try decoder.decode(ObserversResponse.self, from: Data("""
        {
          "observers": [
            {"id":"observer-1","name":"North Observer","lat":30.0,"lon":-97.02}
          ]
        }
        """.utf8))
        let reference = try #require(FieldCoordinate(latitude: 30.0, longitude: -97.0))
        let model = FieldToolsViewModel(reference: reference)

        model.nodes = nodes.nodes
        model.observers = observers.observers

        #expect(model.visibleItems.map(\.id) == ["node:near", "observer:observer-1", "node:far"])
        model.filter = .observers
        #expect(model.visibleItems.map(\.id) == ["observer:observer-1"])
        model.filter = .all
        model.searchText = "far"
        #expect(model.visibleItems.map(\.id) == ["node:far"])
    }

    @Test func alphabeticalFallbackIsStableWithoutAReference() throws {
        let decoder = APIClient.makeDecoder()
        let response = try decoder.decode(NodesResponse.self, from: Data("""
        {
          "nodes": [
            {"public_key":"b","name":"Zulu","role":"repeater","lat":31.0,"lon":-97.0},
            {"public_key":"a","name":"Alpha","role":"repeater","lat":30.0,"lon":-97.0}
          ],
          "total":2,
          "counts":{"repeaters":2,"rooms":0,"companions":0,"sensors":0}
        }
        """.utf8))
        let model = FieldToolsViewModel(reference: nil)

        model.nodes = response.nodes

        #expect(model.visibleItems.map(\.id) == ["node:a", "node:b"])
    }

    @Test func mapItemsAreViewportLimitedAndRetainTheActiveTarget() throws {
        let response = try APIClient.makeDecoder().decode(NodesResponse.self, from: Data("""
        {
          "nodes": [
            {"public_key":"near-a","name":"Near A","role":"repeater","lat":30.0,"lon":-97.0},
            {"public_key":"near-b","name":"Near B","role":"repeater","lat":30.001,"lon":-97.0},
            {"public_key":"far","name":"Far","role":"repeater","lat":40.0,"lon":-120.0}
          ],
          "total":3,"counts":{"repeaters":3,"rooms":0,"companions":0,"sensors":0}
        }
        """.utf8))
        let items = response.nodes.map(FieldCatalogItem.node)
        let center = MKMapPoint(CLLocationCoordinate2D(latitude: 30.0, longitude: -97.0))
        let viewport = MKMapRect(
            x: center.x - 10_000,
            y: center.y - 10_000,
            width: 20_000,
            height: 20_000
        )

        let displayed = FieldToolsViewModel.mapItems(
            from: items,
            visibleMapRect: viewport,
            limit: 1,
            retainingIDs: ["node:far"]
        )

        #expect(displayed.map(\.id) == ["node:near-a", "node:far"])
    }

    @Test func targetEvidenceProjectsOnlyCatalogFactsWithProvenance() throws {
        let decoder = APIClient.makeDecoder()
        let nodes = try decoder.decode(NodesResponse.self, from: Data("""
        {
          "nodes": [{
            "public_key":"node-1","name":"Relay","role":"repeater","lat":30.0,"lon":-97.0,
            "last_seen":"2026-10-23T12:00:00Z","advert_count":42
          }],
          "total":1,"counts":{"repeaters":1,"rooms":0,"companions":0,"sensors":0}
        }
        """.utf8))
        let fetchedAt = try #require(ISO8601DateFormatter().date(from: "2026-10-23T12:05:00Z"))

        let evidence = FieldTargetEvidence(
            item: .node(try #require(nodes.nodes.first)),
            catalogFetchedAt: fetchedAt,
            sourceHost: "example.test"
        )

        #expect(evidence.activityAt != nil)
        #expect(evidence.catalogFetchedAt == fetchedAt)
        #expect(evidence.sourceHost == "example.test")
        #expect(evidence.facts == [.init(kind: .advertisements, value: "42")])
    }

    @Test func observerEvidenceDoesNotInventMissingSignalOrStatus() throws {
        let decoder = APIClient.makeDecoder()
        let response = try decoder.decode(ObserversResponse.self, from: Data("""
        {"observers":[{"id":"observer-1","lat":30.0,"lon":-97.0,"packetsLastHour":7}]}
        """.utf8))

        let evidence = FieldTargetEvidence(
            item: .observer(try #require(response.observers.first)),
            catalogFetchedAt: nil,
            sourceHost: "example.test"
        )

        #expect(evidence.activityAt == nil)
        #expect(evidence.facts == [.init(kind: .packetsLastHour, value: "7")])
    }

    @Test func analyticsEvidenceProjectsMeasuredReceiverFacts() throws {
        let response = try APIClient.makeDecoder().decode(NodeAnalyticsResponse.self, from: Data("""
        {
          "node":{"public_key":"node-1","role":"repeater"},
          "timeRange":{"from":"2026-10-22T12:00:00Z","to":"2026-10-23T12:00:00Z","days":1},
          "snrTrend":[
            {"timestamp":"2026-10-23T11:00:00Z","snr":4.0,"rssi":-100,"observer_id":"west"},
            {"timestamp":"2026-10-23T11:30:00Z","snr":7.5,"rssi":-92,"observer_id":"east"}
          ],
          "observerCoverage":[
            {"observer_id":"west","observer_name":"West","packetCount":3,"avgSnr":4.0,
             "firstSeen":"2026-10-23T09:00:00Z","lastSeen":"2026-10-23T11:00:00Z"},
            {"observer_id":"east","observer_name":"East","packetCount":5,"avgSnr":8.0,
             "firstSeen":"2026-10-23T09:00:00Z","lastSeen":"2026-10-23T11:30:00Z"}
          ],
          "computedStats":{"totalPackets":8,"uniqueObservers":2,"avgPacketsPerDay":8}
        }
        """.utf8))

        let evidence = FieldTargetAnalyticsEvidence(node: response)

        #expect(evidence.activityCount == 8)
        #expect(evidence.receiverCount == 2)
        #expect(evidence.latestSnr == 7.5)
        #expect(evidence.latestRssi == -92)
        #expect(evidence.strongestReceiverName == "East")
        #expect(evidence.strongestReceiverSnr == 8)
        #expect(evidence.latestPacketHash == nil)
    }

    @Test func observerAnalyticsExposesOnlyAnExactLatestPacketReference() throws {
        let older = try #require(ISO8601DateFormatter().date(from: "2026-10-23T11:00:00Z"))
        let newer = try #require(ISO8601DateFormatter().date(from: "2026-10-23T11:30:00Z"))
        let analytics = ObserverAnalyticsResponse(
            context: nil,
            timeline: [.init(label: "11:00", count: 2), .init(label: "11:30", count: 3)],
            packetTypes: [:],
            nodesTimeline: [],
            snrDistribution: [],
            recentPackets: [
                .init(id: 1, hash: "older-hash", timestamp: older, payloadType: 4,
                      snr: 2, rssi: -100, direction: nil),
                .init(id: 2, hash: "latest-hash", timestamp: newer, payloadType: 4,
                      snr: 6, rssi: -90, direction: nil)
            ]
        )

        let evidence = FieldTargetAnalyticsEvidence(observer: analytics)

        #expect(evidence.activityCount == 5)
        #expect(evidence.latestPacketHash == "latest-hash")
        #expect(evidence.latestPacketTimestamp == newer)
        #expect(evidence.latestSnr == 6)
    }

    @Test func evidenceFallbackDistinguishesRateLimitsFromOtherFailures() throws {
        let retryAt = try #require(ISO8601DateFormatter().date(from: "2026-10-23T12:30:00Z"))

        #expect(FieldEvidenceRepository.fallbackReason(for: APIError.http(429)) == .rateLimited(until: nil))
        #expect(
            FieldEvidenceRepository.fallbackReason(for: BeaconAPIError.rateLimited(until: retryAt))
                == .rateLimited(until: retryAt)
        )
        #expect(FieldEvidenceRepository.fallbackReason(for: APIError.http(503)) == .refreshFailed)
    }
}
