import Foundation
import Observation

/// Node Check's live test: "did anyone hear me?" Watches the shared live feed for
/// adverts from one node after the test starts and lists the observers that
/// report them as they arrive. CoreScope's feed carries one event per observer
/// report, so observers appear one by one.
@Observable
@MainActor
final class NodeCheckLiveTest {
    enum Phase: Equatable {
        case idle
        case listening
        case finished(stopped: Bool)
    }

    static let duration: TimeInterval = 120

    let publicKey: String
    private(set) var phase: Phase = .idle
    private(set) var startedAt: Date?
    private(set) var remaining: TimeInterval = NodeCheckLiveTest.duration
    /// Closest first, each observer once with its shortest path.
    private(set) var observers: [NodeCheckReport.HeardObserver] = []
    private(set) var regions: [String] = []
    private(set) var sawFlood = false
    private(set) var sawZeroHop = false

    private var heard: [String: NodeCheckReport.HeardObserver] = [:]
    private var counted: Set<String> = []
    private var observerRegions: [String: String] = [:]
    private var timer: Task<Void, Never>?

    init(publicKey: String) {
        self.publicKey = publicKey.lowercased()
    }

    var hasHeard: Bool { !observers.isEmpty }
    var onlyZeroHop: Bool { sawZeroHop && !sawFlood }

    func start(observerRegions: [String: String]) {
        timer?.cancel()
        self.observerRegions = observerRegions
        heard = [:]
        counted = []
        observers = []
        regions = []
        sawFlood = false
        sawZeroHop = false
        let started = Date()
        startedAt = started
        remaining = Self.duration
        phase = .listening
        timer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, !Task.isCancelled else { return }
                self.remaining = max(0, Self.duration - Date().timeIntervalSince(started))
                if self.remaining <= 0 {
                    self.finish(stopped: false)
                    return
                }
            }
        }
    }

    func stop() {
        guard phase == .listening else { return }
        finish(stopped: true)
    }

    func reset() {
        timer?.cancel()
        timer = nil
        phase = .idle
    }

    /// Reads any new reports of this node's adverts from the live feed's recent events.
    func ingest(_ events: [LiveEnvelope]) {
        guard phase == .listening, let startedAt else { return }
        var changed = false
        for envelope in events where envelope.receivedAt >= startedAt {
            guard let data = envelope.data,
                  let decoded = data.decoded,
                  decoded.header?.payloadType == PayloadType.advert.rawValue
                    || decoded.header?.payloadTypeName == PayloadType.advert.name,
                  decoded.payload?.publicKey?.lowercased() == publicKey,
                  let observerID = data.observerId else { continue }
            // Each report counts once; the feed keeps recent events across updates.
            let reportKey = "\(data.id)|\(observerID)"
            guard counted.insert(reportKey).inserted else { continue }

            // MeshCore route types 0 and 1 are flood; 2 and 3 are direct, which is how a
            // zero-hop advert travels.
            if let route = data.routeType ?? decoded.header?.routeType {
                if route <= 1 { sawFlood = true } else { sawZeroHop = true }
            }
            let hops = decoded.path?.hops?.count ?? Self.hopCount(data.pathJson)
            let entry = NodeCheckReport.HeardObserver(
                observerID: observerID,
                name: data.observerName ?? String(localized: "Unnamed observer"),
                region: observerRegions[observerID],
                hops: hops,
                snr: data.snr.map { ($0 * 10).rounded() / 10 },
                rssi: data.rssi
            )
            if let previous = heard[observerID], (previous.hops ?? 99) <= (entry.hops ?? 99) { continue }
            heard[observerID] = entry
            changed = true
        }
        guard changed else { return }
        observers = heard.values.sorted {
            ($0.hops ?? 99, -($0.snr ?? -99)) < ($1.hops ?? 99, -($1.snr ?? -99))
        }
        regions = Array(Set(observers.compactMap(\.region))).sorted()
    }

    private func finish(stopped: Bool) {
        timer?.cancel()
        timer = nil
        phase = .finished(stopped: stopped)
    }

    private static func hopCount(_ pathJSON: String?) -> Int? {
        guard let pathJSON, let data = pathJSON.data(using: .utf8),
              let hops = try? JSONDecoder().decode([String].self, from: data) else { return nil }
        return hops.count
    }
}
