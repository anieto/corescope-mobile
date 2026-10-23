import Foundation
import Testing
@testable import CoreScopeViewer

struct FieldContractsTests {
    private let key = "9CD8FCF22A47333B591D96A2B848B73F457B1BB1A3EA2453A885F9E5787765B1"

    @Test func identityIsSourceQualifiedAndNormalizesNodeKeys() throws {
        let coreScope = try #require(FieldSourceID(backend: "CoreScope", origin: "https://one.example/base"))
        let beacon = try #require(FieldSourceID(backend: "beacon", origin: "https://one.example/base"))
        let first = try #require(FieldEntityID(source: coreScope, kind: .node, value: key))
        let same = try #require(FieldEntityID(source: coreScope, kind: .node, value: key.lowercased()))
        let otherSource = try #require(FieldEntityID(source: beacon, kind: .node, value: key))

        #expect(first == same)
        #expect(first.id == same.id)
        #expect(first != otherSource)
        #expect(first.id != otherSource.id)
    }

    @Test func identityEncodingHasNoDelimiterCollision() throws {
        let firstSource = try #require(FieldSourceID(backend: "a", origin: "bc"))
        let secondSource = try #require(FieldSourceID(backend: "ab", origin: "c"))
        let first = try #require(FieldEntityID(source: firstSource, kind: .observer, value: "d"))
        let second = try #require(FieldEntityID(source: secondSource, kind: .observer, value: "d"))
        #expect(first.id != second.id)
    }

    @Test func parsesTheThreeApprovedNodeCodeForms() throws {
        let normalized = key.lowercased()
        #expect(FieldQRCode(payload: key) == .publicKey(normalized))
        #expect(FieldQRCode(payload: "nodescope://node/\(key)") == .nodeScope(kind: .node, value: normalized))

        let contact = try #require(MeshCoreContactLink(name: "Hill Top + West", publicKey: key, type: 2))
        #expect(FieldQRCode(payload: contact.urlString) == .meshCoreContact(contact))
    }

    @Test func parsesNodeScopeObserverWithoutTreatingItAsANodeKey() {
        #expect(FieldQRCode(payload: "nodescope://observer/SAT-01") == .nodeScope(kind: .observer, value: "SAT-01"))
    }

    @Test func rejectsUnsupportedOrAmbiguousPayloads() {
        let tooLarge = String(repeating: "a", count: FieldQRCode.maximumPayloadBytes + 1)
        let invalid = [
            "", "abcd", String(repeating: "z", count: 64),
            "https://example.com/node/\(key)",
            "nodescope://node/abcd", "nodescope://channel/test", "nodescope://node/\(key)?source=evil",
            "meshcore://contact/add?name=R&public_key=\(key)&type=2&type=3",
            "meshcore://contact/add?name=R&public_key=\(key)&type=5",
            "meshcore://contact/delete?name=R&public_key=\(key)&type=2",
            tooLarge
        ]
        for payload in invalid {
            #expect(FieldQRCode(payload: payload) == nil)
        }
    }

    @Test func meshCoreNamesAreBounded() {
        let longName = String(repeating: "é", count: 65)
        #expect(longName.utf8.count == 130)
        #expect(MeshCoreContactLink(name: longName, publicKey: key, type: 2) == nil)
    }

    @Test func requestBudgetsMatchTheSharedPlatformContract() {
        #expect(FieldRequestBudget.automaticCatalogRefreshInterval == 30)
        #expect(FieldRequestBudget.staleCatalogLifetime == 604_800)
        #expect(FieldRequestBudget.pinnedEvidenceFreshLifetime == 300)
        #expect(FieldRequestBudget.maximumBeaconCatalogPagesPerKind == 20)
        #expect(FieldRequestBudget.maximumLivePackets == 200)
        #expect(FieldRequestBudget.maximumLiveMetadataEntries == 1_000)
    }
}
