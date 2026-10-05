import Foundation
import Testing
@testable import CoreScopeViewer

struct URLPathEncodingTests {
    @Test func encodesHashCharacterInChannelName() {
        // CoreScope uses a channel's display name as its "hash" identifier
        // (e.g. "#bot"). Left unescaped, "#" is a URL fragment delimiter and
        // silently truncates the path — this must never reach a raw URL.
        let encoded = "#bot".urlPathComponentEncoded
        #expect(!encoded.contains("#"))

        let url = URL(string: "https://example.com/api/channels/\(encoded)/messages")
        // `.path` decodes percent-escapes back to "#bot" when read, which is
        // correct Foundation behavior — the point is that the *fragment* is
        // nil, proving "#" never got parsed as a fragment delimiter, and the
        // raw request line still contains the escaped form.
        #expect(url?.fragment == nil)
        #expect(url?.path == "/api/channels/#bot/messages")
        #expect(url?.absoluteString.contains("%23bot") == true)
    }

    @Test func leavesSafeIdentifiersUnchanged() {
        #expect("VolenteTX_Pi5_RAK13302".urlPathComponentEncoded == "VolenteTX_Pi5_RAK13302")
        #expect("abcd1234abcd1234".urlPathComponentEncoded == "abcd1234abcd1234")
    }

    @Test func encodesOtherReservedCharacters() {
        let encoded = "Public Channel?/2".urlPathComponentEncoded
        #expect(!encoded.contains(" "))
        #expect(!encoded.contains("?"))
        #expect(!encoded.contains("/"))
    }
}

struct NodeScopeDeepLinkTests {
    @Test func roundTripsEverySupportedDestination() {
        let links: [NodeScopeDeepLink] = [
            .node("abc123"),
            .observer("SAT/Observer 1"),
            .channel("#Public"),
            .packet("packet-hash")
        ]

        for link in links {
            let url = try! #require(link.url)
            #expect(NodeScopeDeepLink(url: url) == link)
        }
    }

    @Test func rejectsUnsupportedLinks() {
        #expect(NodeScopeDeepLink(url: URL(string: "https://example.com/node/abc")!) == nil)
        #expect(NodeScopeDeepLink(url: URL(string: "nodescope://unknown/abc")!) == nil)
        #expect(NodeScopeDeepLink(url: URL(string: "nodescope://node")!) == nil)
    }
}

struct MeshCoreContactLinkTests {
    private let key = "9CD8FCF22A47333B591D96A2B848B73F457B1BB1A3EA2453A885F9E5787765B1"

    @Test func buildsTheMeshCoreAppContactLink() {
        let link = try! #require(MeshCoreContactLink(name: "Example Contact", publicKey: key, role: "companion"))
        #expect(link.urlString == "meshcore://contact/add?name=Example%20Contact&public_key=\(key.lowercased())&type=1")
    }

    @Test func mapsRolesToContactTypes() {
        #expect(MeshCoreContactLink(name: "R", publicKey: key, role: "repeater")?.type == 2)
        #expect(MeshCoreContactLink(name: "R", publicKey: key, role: "Room")?.type == 3)
        #expect(MeshCoreContactLink(name: "S", publicKey: key, role: "sensor")?.type == 4)
        #expect(MeshCoreContactLink(name: "?", publicKey: key, role: "unknown") == nil)
    }

    @Test func escapesReservedCharactersInNames() {
        let link = try! #require(MeshCoreContactLink(name: "A+B & C=D #1", publicKey: key, role: "repeater"))
        #expect(link.urlString.hasPrefix("meshcore://contact/add?name=A%2BB%20%26%20C%3DD%20%231&"))
        let items = URLComponents(url: try! #require(link.url), resolvingAgainstBaseURL: false)?.queryItems
        #expect(items?.first { $0.name == "name" }?.value == "A+B & C=D #1")
    }

    @Test func fallsBackToAKeyPrefixForUnnamedNodes() {
        #expect(MeshCoreContactLink(name: "  ", publicKey: key, role: "repeater")?.name == "9CD8FCF2")
    }

    @Test func rejectsIncompleteKeys() {
        #expect(MeshCoreContactLink(name: "R", publicKey: "9cd8fcf2", role: "repeater") == nil)
        #expect(MeshCoreContactLink(name: "R", publicKey: String(repeating: "z", count: 64), role: "repeater") == nil)
    }
}

struct ChannelNameNormalizationTests {
    @Test func usesMeshCoreWellKnownPublicChannelKey() {
        #expect(ChannelCrypto.derivedKeyHex(for: "Public") == ChannelCrypto.publicKeyHex)
        #expect(ChannelCrypto.derivedKeyHex(for: "#public") == ChannelCrypto.publicKeyHex)
    }

    @Test func preservesCanonicalPublicChannelWithoutHash() {
        #expect(ChannelMonitorStore.normalizedHashtagName("Public") == "Public")
        #expect(ChannelMonitorStore.normalizedHashtagName("#Public") == "Public")
        #expect(ChannelMonitorStore.normalizedHashtagName(" public ") == "Public")
    }

    @Test func addsHashToOtherChannelNamesWhenNeeded() {
        #expect(ChannelMonitorStore.normalizedHashtagName("mesh") == "#mesh")
        #expect(ChannelMonitorStore.normalizedHashtagName("#mesh") == "#mesh")
    }

    @Test func rejectsEmptyChannelNames() {
        #expect(ChannelMonitorStore.normalizedHashtagName("   ") == nil)
        #expect(ChannelMonitorStore.normalizedHashtagName("#") == nil)
    }

    @Test func migratesLegacyPublicChannel() {
        let legacy = MonitoredChannel(
            channelName: "#Public",
            keyHex: "591935b15b1c88e2d5f6be0a054604fc",
            displayName: nil,
            createdAt: .distantPast,
            messageCount: 4,
            lastMessage: "Hello",
            lastActivity: .distantPast
        )

        let migrated = ChannelMonitorStore.migratingLegacyPublicChannel(legacy)

        #expect(migrated.channelName == "Public")
        #expect(migrated.keyHex == ChannelCrypto.derivedKeyHex(for: "Public"))
        #expect(migrated.messageCount == legacy.messageCount)
        #expect(migrated.lastMessage == legacy.lastMessage)
    }

    @Test func repairsCanonicalPublicChannelSavedWithDerivedKey() {
        let legacy = MonitoredChannel(
            channelName: "Public",
            keyHex: "591935b15b1c88e2d5f6be0a054604fc",
            displayName: nil,
            createdAt: .distantPast,
            messageCount: nil,
            lastMessage: nil,
            lastActivity: nil
        )

        let migrated = ChannelMonitorStore.migratingLegacyPublicChannel(legacy)

        #expect(migrated.channelName == "Public")
        #expect(migrated.keyHex == ChannelCrypto.publicKeyHex)
    }
}

struct PacketFeedCacheTests {
    @Test func requestsOnlyGroupTextPacketsUsingServerParameter() {
        let query = PacketFeedCache.channelPacketQuery(region: "AUS")

        #expect(query.contains(URLQueryItem(name: "limit", value: "1000")))
        #expect(query.contains(URLQueryItem(name: "type", value: "5")))
        #expect(query.contains(URLQueryItem(name: "region", value: "AUS")))
        #expect(!query.contains { $0.name == "payloadType" })
    }
}
