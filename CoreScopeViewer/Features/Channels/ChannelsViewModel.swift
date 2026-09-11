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

    func loadMessages(hash: String) async {
        guard let apiClient else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let response: ChannelMessagesResponse = try await apiClient.get(
                "/api/channels/\(hash.urlPathComponentEncoded)/messages"
            )
            messages = response.messages
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadMonitoredMessages(channel: MonitoredChannel, region: String?) async {
        guard let apiClient,
              let channelHash = ChannelCrypto.channelHash(for: channel.keyHex) else { return }
        isLoading = messages.isEmpty
        defer { isLoading = false }

        do {
            var query = [
                URLQueryItem(name: "limit", value: "1000"),
                URLQueryItem(name: "payloadType", value: "5")
            ]
            if let region {
                query.append(URLQueryItem(name: "region", value: region))
            }
            let response: PacketsResponse = try await apiClient.get("/api/packets", query: query)
            messages = response.packets.compactMap {
                decrypt(packet: $0, channel: channel, channelHash: channelHash)
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refreshMonitoredSummaries(
        channels: [MonitoredChannel],
        region: String?,
        monitorStore: ChannelMonitorStore
    ) async {
        guard let apiClient, !channels.isEmpty else { return }

        do {
            var query = [
                URLQueryItem(name: "limit", value: "1000"),
                URLQueryItem(name: "payloadType", value: "5")
            ]
            if let region {
                query.append(URLQueryItem(name: "region", value: region))
            }
            let response: PacketsResponse = try await apiClient.get("/api/packets", query: query)

            for channel in channels {
                guard let channelHash = ChannelCrypto.channelHash(for: channel.keyHex) else { continue }
                let messages = response.packets.compactMap {
                    decrypt(packet: $0, channel: channel, channelHash: channelHash)
                }
                monitorStore.updateSummary(for: channel.channelName, messages: messages)
            }
        } catch {
            // The monitored channel remains usable; the detail screen will
            // surface a network failure if the person opens it explicitly.
        }
    }

    private func decrypt(packet: Packet, channel: MonitoredChannel, channelHash: Int) -> ChannelMessage? {
        guard let decodedJSON = packet.decodedJson,
              let data = decodedJSON.data(using: .utf8),
              let payload = try? JSONDecoder().decode(GroupPayload.self, from: data) else {
            return nil
        }

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
