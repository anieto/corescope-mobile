import Foundation
import Observation

@Observable
@MainActor
final class ChannelsViewModel {
    var channels: [MeshChannel] = []
    var messages: [ChannelMessage] = []
    var isLoading = false
    var errorMessage: String?

    private var apiClient: APIClient?
    private var configuredSourceIdentifier: String?

    func configure(settings: AnalyzerSettings) {
        let client = APIClient(settings: settings)
        if configuredSourceIdentifier != client.cacheIdentifier {
            channels = []
            messages = []
            configuredSourceIdentifier = client.cacheIdentifier
        }
        apiClient = client
    }

    func loadChannels(region: String?, forceRefresh: Bool = false) async {
        guard let apiClient else { return }
        isLoading = channels.isEmpty
        defer { isLoading = false }
        do {
            let key = ChannelListCache.Key(source: apiClient.cacheIdentifier, region: region)
            channels = try await ChannelListCache.shared.load(
                for: key,
                using: apiClient,
                forceRefresh: forceRefresh
            )
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    static func preload(settings: AnalyzerSettings, region: String?) async {
        let apiClient = APIClient(settings: settings)
        let key = ChannelListCache.Key(source: apiClient.cacheIdentifier, region: region)
        _ = try? await ChannelListCache.shared.load(for: key, using: apiClient)
    }

    func loadMessages(hash: String, forceRefresh: Bool = false) async {
        guard let apiClient else { return }
        isLoading = messages.isEmpty
        defer { isLoading = false }
        do {
            let key = ChannelMessagesCache.Key(source: apiClient.cacheIdentifier, hash: hash)
            messages = try await ChannelMessagesCache.shared.load(
                for: key,
                using: apiClient,
                forceRefresh: forceRefresh
            )
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadMonitoredMessages(channel: MonitoredChannel, region: String?, forceRefresh: Bool = false) async {
        guard let apiClient,
              let channelHash = ChannelCrypto.channelHash(for: channel.keyHex) else { return }
        isLoading = messages.isEmpty
        defer { isLoading = false }

        do {
            let key = PacketFeedCache.Key(source: apiClient.cacheIdentifier, region: region)
            let packets = try await PacketFeedCache.shared.load(
                for: key,
                using: apiClient,
                forceRefresh: forceRefresh
            )
            messages = packets.compactMap { packet in
                guard let payload = Self.parsePayload(for: packet) else { return nil }
                return Self.message(from: packet, payload: payload, channel: channel, channelHash: channelHash)
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refreshMonitoredSummaries(
        channels: [MonitoredChannel],
        region: String?,
        monitorStore: ChannelMonitorStore,
        forceRefresh: Bool = false
    ) async {
        guard let apiClient, !channels.isEmpty else { return }

        do {
            let key = PacketFeedCache.Key(source: apiClient.cacheIdentifier, region: region)
            let packets = try await PacketFeedCache.shared.load(for: key, using: apiClient, forceRefresh: forceRefresh)

            // Each packet's payload is independent of which channel is being
            // summarized, so it's parsed once here rather than once per
            // channel — with several monitored channels that previously meant
            // re-decoding the same JSON for every one of them.
            let parsedPackets = packets.compactMap { packet in
                Self.parsePayload(for: packet).map { (packet: packet, payload: $0) }
            }

            for channel in channels {
                guard let channelHash = ChannelCrypto.channelHash(for: channel.keyHex) else { continue }
                let messages = parsedPackets.compactMap {
                    Self.message(from: $0.packet, payload: $0.payload, channel: channel, channelHash: channelHash)
                }
                monitorStore.updateSummary(for: channel.channelName, messages: messages)
            }
        } catch {
            // The monitored channel remains usable; the detail screen will
            // surface a network failure if the person opens it explicitly.
        }
    }

    private static let payloadDecoder = JSONDecoder()

    private static func parsePayload(for packet: Packet) -> GroupPayload? {
        guard let decodedJSON = packet.decodedJson,
              let data = decodedJSON.data(using: .utf8) else {
            return nil
        }
        return try? payloadDecoder.decode(GroupPayload.self, from: data)
    }

    private static func message(
        from packet: Packet,
        payload: GroupPayload,
        channel: MonitoredChannel,
        channelHash: Int
    ) -> ChannelMessage? {
        if payload.type == "CHAN", payload.channel == channel.channelName {
            return ChannelMessage(
                sender: payload.sender ?? "Unknown",
                text: payload.text ?? "",
                timestamp: packet.firstSeen,
                senderTimestamp: payload.senderTimestamp,
                packetId: packet.id,
                packetHash: packet.hash,
                repeats: packet.observationCount,
                observers: packet.observerName.map { [$0] } ?? [],
                hops: payload.pathLength ?? 0,
                snr: packet.snr
            )
        }

        guard payload.type == "GRP_TXT",
              payload.channelHash == channelHash,
              let mac = payload.mac,
              let encryptedData = payload.encryptedData,
              let decrypted = ChannelCrypto.decrypt(
                keyHex: channel.keyHex,
                macHex: mac,
                encryptedHex: encryptedData
              ) else {
            return nil
        }

        return ChannelMessage(
            sender: decrypted.sender,
            text: decrypted.text,
            timestamp: packet.firstSeen,
            senderTimestamp: decrypted.senderTimestamp,
            packetId: packet.id,
            packetHash: packet.hash,
            repeats: packet.observationCount,
            observers: packet.observerName.map { [$0] } ?? [],
            hops: 0,
            snr: packet.snr
        )
    }
}

private struct GroupPayload: Decodable {
    let type: String
    let channel: String?
    let channelHash: Int?
    let mac: String?
    let encryptedData: String?
    let sender: String?
    let text: String?
    let senderTimestamp: Double?
    let pathLength: Int?

    enum CodingKeys: String, CodingKey {
        case type, channel, mac, sender, text
        case channelHash
        case encryptedData
        case senderTimestamp = "sender_timestamp"
        case pathLength = "path_len"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decode(String.self, forKey: .type)
        channel = try container.decodeIfPresent(String.self, forKey: .channel)
        mac = try container.decodeIfPresent(String.self, forKey: .mac)
        encryptedData = try container.decodeIfPresent(String.self, forKey: .encryptedData)
        sender = try container.decodeIfPresent(String.self, forKey: .sender)
        text = try container.decodeIfPresent(String.self, forKey: .text)
        senderTimestamp = try container.decodeIfPresent(Double.self, forKey: .senderTimestamp)
        pathLength = try container.decodeIfPresent(Int.self, forKey: .pathLength)

        if let value = try? container.decode(Int.self, forKey: .channelHash) {
            channelHash = value
        } else if let value = try? container.decode(String.self, forKey: .channelHash) {
            channelHash = Int(value) ?? Int(value, radix: 16)
        } else {
            channelHash = nil
        }
    }
}
