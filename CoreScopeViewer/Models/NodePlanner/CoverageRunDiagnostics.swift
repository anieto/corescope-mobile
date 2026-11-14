import Foundation
import CryptoKit
import os

/// Local, bounded diagnostics. Sharing is explicit; no automatic upload or telemetry.
final class CoverageRunDiagnostics {
    private let started = ContinuousClock.now
    private var lastWrite = ContinuousClock.now
    private let fileURL: URL?
    private(set) var pauseReason: String?
    private var minimumAvailableBytes: UInt64?
    private var peakResidentBytes: UInt64 = 0
    private var phase = "validating"
    private var finished = false
    private var timings: [String: Double] = [:]
    private var counters: [String: Int] = [:]
    private let inputs: CoverageStudyInputs
    private let availableMemory: () -> UInt64?

    init(inputs: CoverageStudyInputs, fileURL: URL? = nil,
         availableMemory: @escaping () -> UInt64? = CoverageRunDiagnostics.deviceAvailableMemory) {
        self.inputs = inputs
        self.fileURL = fileURL
        self.availableMemory = availableMemory
    }

    func measure<T>(_ stage: String, _ operation: () throws -> T) rethrows -> T {
        let start = ContinuousClock.now
        defer { recordDuration(stage, since: start) }
        return try operation()
    }

    func recordDuration(_ stage: String, since start: ContinuousClock.Instant) {
        let elapsed = start.duration(to: .now).components
        timings[stage, default: 0] += Double(elapsed.seconds) + Double(elapsed.attoseconds) / 1e18
        counters[stage + "Calls", default: 0] += 1
    }

    func recordRadiusReuse(_ area: CoverageAreaResult) {
        counters["radiusReusedPredictions"] = area.cells.filter { $0.state == .qualified }.count
        counters["radiusPendingSamples"] = area.pendingCount
    }

    func recordCache(_ mosaic: CoverageTerrainMosaic) {
        counters["crossingCacheHits"] = mosaic.crossingCacheHits
        counters["elevationCacheHits"] = mosaic.elevationCacheHits
        counters["elevationReads"] = mosaic.elevationReads
        counters.merge(mosaic.profilePassCounts) { _, new in new }
        counters.merge(mosaic.readerPerformanceCounters) { _, new in new }
        counters["interpolationCalls"] = mosaic.interpolationCalls
        counters["timedInterpolationCalls"] = mosaic.timedInterpolationCalls
        counters["timedReaderCalls"] = mosaic.timedReaderCalls
        counters["timedCoordinateCalls"] = mosaic.timedCoordinateCalls
        timings["crossingDiscovery"] = mosaic.crossingSeconds
        timings["sampledInterpolationInclusive"] = mosaic.timedInterpolationSeconds
        timings["sampledReaderInclusive"] = mosaic.timedReaderSeconds
        timings["sampledPathCoordinates"] = mosaic.timedCoordinateSeconds
        // Estimates are explicitly named: periodic samples can be biased by cache/access patterns.
        // Reader time is nested within interpolation, which is nested within pathPreparation.
        if mosaic.timedInterpolationCalls > 0 {
            let scale = Double(mosaic.interpolationCalls) / Double(mosaic.timedInterpolationCalls)
            timings["estimatedInterpolationInclusive"] = mosaic.timedInterpolationSeconds * scale
            timings["estimatedReaderInclusive"] = mosaic.timedReaderSeconds * scale
        }
        if mosaic.timedCoordinateCalls > 0 {
            timings["estimatedPathCoordinates"] = mosaic.timedCoordinateSeconds
                * Double(mosaic.elevationReads) / Double(mosaic.timedCoordinateCalls)
        }
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
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let status = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        if status == KERN_SUCCESS { peakResidentBytes = max(peakResidentBytes, UInt64(info.resident_size)) }
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

    /// Versioned digest of every grid verdict/loss/margin and exact-node verdict/margin.
    /// Excludes execution IDs, timings and counters; comparison requires matching study inputs.
    static func predictionFingerprint(_ result: CoverageStudyResult) -> String {
        var hash = SHA256()
        func add(_ value: String) { hash.update(data: Data((value + "\n").utf8)) }
        func bits(_ value: Double?) -> String { value.map { String($0.bitPattern, radix: 16) } ?? "nil" }
        add("coverage-predictions-v1")
        add("\(result.grid.radiusMeters),\(result.grid.spacingMeters),\(result.grid.columns),\(result.grid.rows),\(result.grid.minEastMeters),\(result.grid.maxNorthMeters)")
        for cell in result.area.cells { add("\(cell.state.rawValue),\(bits(cell.lossDb)),\(bits(cell.marginDb))") }
        for node in result.nodes.run.nodesToEvaluate {
            let status = result.nodes.run.status(for: node)
            add("\(bits(node.latitude)),\(bits(node.longitude)),\(status.state.rawValue),\(status.reason.rawValue),\(bits(status.adjustedMarginDb))")
        }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func persist(result: CoverageStudyResult? = nil) {
        guard let fileURL else { return }
        let b = inputs.budget
        let document: [String: Any] = [
            "terrainMappingRequested": !ProcessInfo.processInfo.arguments.contains("-CoverageDisableTerrainMapping"),
            "terrainBlockCacheEnabled": !ProcessInfo.processInfo.arguments.contains("-CoverageDisableTerrainBlockCache"),
            "pathReuseEnabled": !ProcessInfo.processInfo.arguments.contains("-CoverageDisablePathReuse"),
            "predictionFingerprint": result.map(Self.predictionFingerprint) ?? "",
            "timingSeconds": timings, "performanceCounters": counters,
            "executionID": result?.executionID.uuidString ?? "",
            "formatVersion": 2, "updatedAt": Date().ISO8601Format(),
            "phase": phase, "elapsedSeconds": elapsedSeconds,
            "peakObservedResidentBytes": peakResidentBytes,
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

    /// This measures raster preparation, not MapKit's asynchronous tile presentation.
    static func recordRasterPreparation(executionID: UUID, seconds: Double) {
        guard let data = try? Data(contentsOf: applicationURL),
              var document = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              document["executionID"] as? String == executionID.uuidString else { return }
        var timing = document["timingSeconds"] as? [String: Double] ?? [:]
        timing["rasterPreparation"] = seconds
        document["timingSeconds"] = timing
        guard let updated = try? JSONSerialization.data(withJSONObject: document, options: [.prettyPrinted, .sortedKeys]) else { return }
        try? updated.write(to: applicationURL, options: .atomic)
    }

    static var applicationURL: URL {
        URL.applicationSupportDirectory.appendingPathComponent("CoverageDiagnostics/latest.json")
    }

    static func readLatest() -> String? {
        try? String(contentsOf: applicationURL, encoding: .utf8)
    }
}
