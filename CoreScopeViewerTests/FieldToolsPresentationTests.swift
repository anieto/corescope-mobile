import Foundation
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
}
