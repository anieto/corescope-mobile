import Foundation
import os

/// Local, bounded diagnostics. Sharing is explicit; no automatic upload or telemetry.
final class CoverageRunDiagnostics {
    private let started = ContinuousClock.now
    private var lastWrite = ContinuousClock.now
    private let fileURL: URL?
    private(set) var pauseReason: String?
    private var minimumAvailableBytes: UInt64?
    private var phase = "validating"
    private var finished = false
    private let inputs: CoverageStudyInputs
    private let availableMemory: () -> UInt64?

    init(inputs: CoverageStudyInputs, fileURL: URL? = nil,
         availableMemory: @escaping () -> UInt64? = CoverageRunDiagnostics.deviceAvailableMemory) {
        self.inputs = inputs
        self.fileURL = fileURL
        self.availableMemory = availableMemory
    }

    /// Simulator processes do not have the iOS app memory-limit contract.
    /// Nil means unavailable; a real device's zero still means exhausted headroom.
    static func deviceAvailableMemory() -> UInt64? {
        #if os(iOS) && !targetEnvironment(simulator)
        return UInt64(os_proc_available_memory())
        #else
        return nil
        #endif
    }

    func setPhase(_ phase: String) {
        self.phase = phase
        finished = phase.hasPrefix("failed") || phase == "cancelled"
        persist()
    }

    /// Advisory headroom check at path boundaries; it cannot guarantee protection from jetsam.
    func shouldPause() -> Bool {
        if let available = availableMemory() {
            minimumAvailableBytes = min(minimumAvailableBytes ?? available, available)
            if available < 128 * 1024 * 1024 {
                pauseReason = "low available memory"
                return true
            }
        }
        if phase == "calculating", ContinuousClock.now - lastWrite >= .seconds(2) {
            persist()
        }
        return pauseReason != nil
    }

    func finishIfNeeded(cancelled: Bool) {
        if !finished { setPhase(cancelled ? "cancelled" : "failed before completion") }
    }

    func finish(_ result: CoverageStudyResult) {
        finished = true
        phase = result.hasPendingWork ? "partial; can continue in this screen" : "completed"
        persist(result: result)
    }

    private func persist(result: CoverageStudyResult? = nil) {
        guard let fileURL else { return }
        let b = inputs.budget
        let document: [String: Any] = [
            "formatVersion": 1, "updatedAt": Date().ISO8601Format(),
            "phase": phase, "elapsedSeconds": elapsedSeconds,
            "minimumAvailableMemoryBytes": minimumAvailableBytes.map { NSNumber(value: $0) } ?? NSNull(),
            "pauseReason": pauseReason ?? "",
            "latitude": inputs.transmitter.latitude, "longitude": inputs.transmitter.longitude,
            "radiusMeters": inputs.grid.radiusMeters, "spacingMeters": inputs.grid.spacingMeters,
            "frequencyMHz": inputs.frequencyMHz, "transmitPowerDbm": b.transmitPowerDbm,
            "txHeightMeters": inputs.transmitHeightMeters, "rxHeightMeters": inputs.receiveHeightMeters,
            "txGainDbi": b.transmitGainDbi, "rxGainDbi": b.receiveGainDbi,
            "txLossDb": b.transmitLossDb, "rxLossDb": b.receiveLossDb,
            "receiverSensitivityDbm": b.sensitivityDbm, "reserveDb": b.reserveDb,
            "model": CoverageRFKernel.scenario, "modelRevision": CoverageRFKernel.sourceRevision,
            "pendingMapSamples": result?.area.pendingCount ?? 0,
            "chargedAreaSamples": result?.area.chargedSamples ?? 0,
            "chargedAreaGeometry": result?.area.chargedGeometry ?? 0,
            "terrainKeys": result?.terrainSources?.descriptors.map(\.cacheKey) ?? [],
            "nodeComparisons": result.map { output in
                output.nodes.run.nodesToEvaluate.map { node -> [String: Any] in
                    let status = output.nodes.run.status(for: node)
                    let margin = status.adjustedMarginDb
                    return [
                        "nodeID": node.nodeID, "sourceID": node.sourceID,
                        "latitude": node.latitude, "longitude": node.longitude,
                        "state": status.state.rawValue, "reason": status.reason.rawValue,
                        "adjustedMarginDb": margin.map { NSNumber(value: $0) } ?? NSNull(),
                        "receiveLevelDbm": margin.map { NSNumber(value: $0 + b.sensitivityDbm + b.reserveDb) } ?? NSNull(),
                        "pathLossDb": margin.map { NSNumber(value: b.maximumLossDb - $0) } ?? NSNull()
                    ]
                }
            } ?? [],
            "note": "A report left in calculating/preparing terrain does not identify the cause of termination."
        ]
        do {
            let data = try JSONSerialization.data(withJSONObject: document, options: [.prettyPrinted, .sortedKeys])
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            // Diagnostics must never fail a calculation or overwrite the saved plan library.
        }
        lastWrite = .now
    }

    private var elapsedSeconds: Double {
        let value = (ContinuousClock.now - started).components
        return Double(value.seconds) + Double(value.attoseconds) / 1e18
    }

    static var applicationURL: URL {
        URL.applicationSupportDirectory.appendingPathComponent("CoverageDiagnostics/latest.json")
    }

    static func readLatest() -> String? {
        try? String(contentsOf: applicationURL, encoding: .utf8)
    }
}
