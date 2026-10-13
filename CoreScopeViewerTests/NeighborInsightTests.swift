import Foundation
import Testing
@testable import CoreScopeViewer

struct NeighborInsightTests {
    @Test func onlyFullPublicKeysAreLinkable() {
        let full = String(repeating: "Ab", count: 32)
        let identity = NeighborIdentity(publicKey: full, displayPrefix: nil, sourceID: "server-id")
        #expect(identity.linkablePublicKey == full.lowercased())

        let prefix = NeighborIdentity(publicKey: nil, displayPrefix: "abcdef12", sourceID: nil)
        #expect(prefix.linkablePublicKey == nil)
        #expect(prefix.displayPrefix == "abcdef12")
    }

    @Test func coordinateValidityPreservesUnknownAndRejectsSentinels() {
        func neighbor(latitude: Double?, longitude: Double?) -> NeighborInsight {
            NeighborInsight(
                identity: NeighborIdentity(publicKey: nil, displayPrefix: "abcdef12", sourceID: nil),
                name: nil,
                role: nil,
                lastHeard: nil,
                observationCount: nil,
                signal: NeighborSignal(snr: nil, rssi: nil),
                direction: .unknown,
                latitude: latitude,
                longitude: longitude
            )
        }

        #expect(neighbor(latitude: nil, longitude: nil).hasValidCoordinate == false)
        #expect(neighbor(latitude: 0, longitude: 0).hasValidCoordinate == false)
        #expect(neighbor(latitude: 30, longitude: -98).hasValidCoordinate)
        #expect(neighbor(latitude: 91, longitude: -98).hasValidCoordinate == false)
    }

    @Test func missingSignalAndDirectionRemainUnknown() {
        let insight = NeighborInsight(
            identity: NeighborIdentity(publicKey: nil, displayPrefix: "abcdef12", sourceID: nil),
            name: "Neighbor",
            role: nil,
            lastHeard: nil,
            observationCount: nil,
            signal: NeighborSignal(snr: nil, rssi: nil),
            direction: .unknown,
            latitude: nil,
            longitude: nil
        )

        #expect(insight.signal.snr == nil)
        #expect(insight.signal.rssi == nil)
        #expect(insight.direction == .unknown)
    }
}
