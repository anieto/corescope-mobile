import Foundation
import Observation

@Observable
@MainActor
final class PacketReplayStore {
    private(set) var requestID = UUID()
    private(set) var resolvedPath: [String] = []
    private(set) var packetHash = ""

    func replay(path: [String], packetHash: String) {
        resolvedPath = path
        self.packetHash = packetHash
        requestID = UUID()
    }
}
