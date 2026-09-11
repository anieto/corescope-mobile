import Foundation

/// A locally monitored MeshCore channel. The key is kept only in the
/// Keychain on this device and is never sent to an analyzer.
struct MonitoredChannel: Codable, Sendable, Identifiable, Hashable {
    let channelName: String
    let keyHex: String
    let displayName: String?
    let createdAt: Date
    let messageCount: Int?
    let lastMessage: String?
    let lastActivity: Date?

    var id: String { channelName }

    var title: String {
        displayName?.isEmpty == false ? displayName! : channelName
    }
}
