import CryptoKit
import CommonCrypto
import Foundation
import Observation
import Security

@Observable
@MainActor
final class ChannelMonitorStore {
    private static let service = "org.meshtexas.nodescope.monitored-channels"
    private static let account = "channels"

    private(set) var channels: [MonitoredChannel] = []

    init() {
        load()
    }

    func monitorHashtag(_ value: String) throws -> MonitoredChannel {
        guard let channelName = Self.normalizedHashtagName(value) else {
            throw ChannelMonitorError.invalidHashtag
        }

        let channel = MonitoredChannel(
            channelName: channelName,
            keyHex: ChannelCrypto.derivedKeyHex(for: channelName),
            displayName: nil,
            createdAt: Date(),
            messageCount: nil,
            lastMessage: nil,
            lastActivity: nil
        )
        try add(channel)
        return channel
    }

    nonisolated static func normalizedHashtagName(_ value: String) -> String? {
        let trimmedName = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else { return nil }

        let nameWithoutPrefix = trimmedName.hasPrefix("#")
            ? String(trimmedName.dropFirst())
            : trimmedName
        guard !nameWithoutPrefix.isEmpty else { return nil }

        if nameWithoutPrefix.caseInsensitiveCompare("Public") == .orderedSame {
            return "Public"
        }
        return trimmedName.hasPrefix("#") ? trimmedName : "#\(trimmedName)"
    }

    func monitorPSK(_ keyHex: String, displayName: String?) throws -> MonitoredChannel {
        let normalizedKey = keyHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard ChannelCrypto.isValidKey(normalizedKey) else { throw ChannelMonitorError.invalidKey }

        let name = "psk:\(normalizedKey.prefix(8))"
        let trimmedLabel = displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let channel = MonitoredChannel(
            channelName: name,
            keyHex: normalizedKey,
            displayName: trimmedLabel?.isEmpty == false ? trimmedLabel : nil,
            createdAt: Date(),
            messageCount: nil,
            lastMessage: nil,
            lastActivity: nil
        )
        try add(channel)
        return channel
    }

    func generatePrivateChannel(displayName: String?) throws -> MonitoredChannel {
        var bytes = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw ChannelMonitorError.keyGenerationFailed
        }

        let keyHex = bytes.map { String(format: "%02x", $0) }.joined()
        return try monitorPSK(keyHex, displayName: displayName)
    }

    func remove(_ channel: MonitoredChannel) throws {
        channels.removeAll { $0.id == channel.id }
        try persist()
    }

    func channel(matching meshChannel: MeshChannel) -> MonitoredChannel? {
        let name = meshChannel.hash.hasPrefix("user:")
            ? String(meshChannel.hash.dropFirst("user:".count))
            : meshChannel.name
        return channels.first { $0.channelName == name }
    }

    func updateSummary(for channelName: String, messages: [ChannelMessage]) {
        guard let index = channels.firstIndex(where: { $0.channelName == channelName }) else { return }
        let latestMessage = messages.max(by: { $0.timestamp < $1.timestamp })
        let existing = channels[index]
        let updated = MonitoredChannel(
            channelName: existing.channelName,
            keyHex: existing.keyHex,
            displayName: existing.displayName,
            createdAt: existing.createdAt,
            messageCount: messages.count,
            lastMessage: latestMessage?.text,
            lastActivity: latestMessage?.timestamp
        )
        guard updated != existing else { return }
        channels[index] = updated
        try? persist()
    }

    private func add(_ channel: MonitoredChannel) throws {
        channels.removeAll { $0.channelName == channel.channelName }
        channels.append(channel)
        channels.sort { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        try persist()
    }

    private func load() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let storedChannels = try? JSONDecoder().decode([MonitoredChannel].self, from: data) else {
            return
        }
        channels = storedChannels.map(Self.migratingLegacyPublicChannel)
        if channels != storedChannels {
            try? persist()
        }
    }

    nonisolated static func migratingLegacyPublicChannel(_ channel: MonitoredChannel) -> MonitoredChannel {
        guard normalizedHashtagName(channel.channelName) == "Public",
              channel.channelName != "Public" else { return channel }

        return MonitoredChannel(
            channelName: "Public",
            keyHex: ChannelCrypto.derivedKeyHex(for: "Public"),
            displayName: channel.displayName,
            createdAt: channel.createdAt,
            messageCount: channel.messageCount,
            lastMessage: channel.lastMessage,
            lastActivity: channel.lastActivity
        )
    }

    private func persist() throws {
        let data = try JSONEncoder().encode(channels)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account
        ]
        SecItemDelete(query as CFDictionary)

        var item = query
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw ChannelMonitorError.keychain(status) }
    }
}

enum ChannelMonitorError: LocalizedError {
    case invalidHashtag
    case invalidKey
    case keyGenerationFailed
    case keychain(OSStatus)

    var errorDescription: String? {
        switch self {
        case .invalidHashtag:
            "Enter a channel name."
        case .invalidKey:
            "Enter a 32-character hexadecimal PSK."
        case .keyGenerationFailed:
            "Couldn't generate a secure channel key."
        case .keychain:
            "Couldn't save the channel securely on this device."
        }
    }
}

enum ChannelCrypto {
    static func isValidKey(_ key: String) -> Bool {
        key.count == 32 && key.allSatisfy { $0.isHexDigit }
    }

    static func derivedKeyHex(for channelName: String) -> String {
        let digest = SHA256.hash(data: Data(channelName.utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    static func channelHash(for keyHex: String) -> Int? {
        guard let key = Data(hexadecimalString: keyHex) else { return nil }
        let digest = SHA256.hash(data: key)
        return Int(Array(digest)[0])
    }

    static func decrypt(keyHex: String, macHex: String, encryptedHex: String) -> DecryptedMessage? {
        guard let key = Data(hexadecimalString: keyHex),
              let ciphertext = Data(hexadecimalString: encryptedHex),
              let suppliedMAC = Data(hexadecimalString: macHex),
              key.count == kCCKeySizeAES128,
              ciphertext.isEmpty == false,
              ciphertext.count.isMultiple(of: kCCBlockSizeAES128),
              suppliedMAC.count == 2 else {
            return nil
        }

        var secret = key
        secret.append(Data(repeating: 0, count: 16))
        let authenticationKey = SymmetricKey(data: secret)
        let expectedMAC = Data(HMAC<SHA256>.authenticationCode(for: ciphertext, using: authenticationKey))
        guard expectedMAC.prefix(2) == suppliedMAC else { return nil }

        var plaintext = Data(repeating: 0, count: ciphertext.count)
        let outputCapacity = plaintext.count
        var outputLength = 0
        let status = plaintext.withUnsafeMutableBytes { outputBuffer in
            key.withUnsafeBytes { keyBuffer in
                ciphertext.withUnsafeBytes { ciphertextBuffer in
                    CCCrypt(
                        CCOperation(kCCDecrypt),
                        CCAlgorithm(kCCAlgorithmAES),
                        CCOptions(kCCOptionECBMode),
                        keyBuffer.baseAddress,
                        kCCKeySizeAES128,
                        nil,
                        ciphertextBuffer.baseAddress,
                        ciphertext.count,
                        outputBuffer.baseAddress,
                        outputCapacity,
                        &outputLength
                    )
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        plaintext.removeSubrange(outputLength..<plaintext.count)
        return DecryptedMessage(plaintext: plaintext)
    }

    struct DecryptedMessage: Sendable {
        let sender: String
        let text: String
        let senderTimestamp: Double

        fileprivate init?(plaintext: Data) {
            guard plaintext.count >= 5 else { return nil }
            let bytes = [UInt8](plaintext)
            let timestamp = UInt32(bytes[0])
                | (UInt32(bytes[1]) << 8)
                | (UInt32(bytes[2]) << 16)
                | (UInt32(bytes[3]) << 24)
            let textBytes = bytes.dropFirst(5).prefix { $0 != 0 }
            guard let message = String(bytes: textBytes, encoding: .utf8) else { return nil }
            let parts = message.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            if parts.count == 2, message.contains(": ") {
                sender = String(parts[0])
                text = String(parts[1]).trimmingCharacters(in: .whitespaces)
            } else {
                sender = "Unknown"
                text = message
            }
            senderTimestamp = Double(timestamp)
        }
    }
}

private extension Data {
    init?(hexadecimalString: String) {
        guard hexadecimalString.count.isMultiple(of: 2) else { return nil }
        var data = Data()
        var index = hexadecimalString.startIndex
        while index < hexadecimalString.endIndex {
            let nextIndex = hexadecimalString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexadecimalString[index..<nextIndex], radix: 16) else { return nil }
            data.append(byte)
            index = nextIndex
        }
        self = data
    }
}
