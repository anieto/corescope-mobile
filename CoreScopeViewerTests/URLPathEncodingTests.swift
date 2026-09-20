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

struct ChannelNameNormalizationTests {
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
            keyHex: ChannelCrypto.derivedKeyHex(for: "#Public"),
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
}
