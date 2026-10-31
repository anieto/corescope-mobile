import Foundation
import Darwin
import CoverageStudy

final class MappedRaster: NSObject, RasterReader {
    let pointer: UnsafeMutableRawPointer
    let length: Int
    let width: Int32
    let height: Int32
    let north: Double
    let west: Double
    let ppd: Int32
    init(path: String) {
        let fd = open(path, O_RDONLY)
        precondition(fd >= 0)
        let size = lseek(fd, 0, SEEK_END)
        precondition(size >= 32 && size <= 200_000_032)
        guard let mapping = mmap(nil, Int(size), PROT_READ, MAP_PRIVATE, fd, 0), mapping != MAP_FAILED else { fatalError("Mapping failed") }
        close(fd)
        pointer = mapping
        length = Int(size)
        func integer(_ offset: Int) -> Int32 {
            var value: UInt32 = 0
            for n in 0..<4 { value = (value << 8) | UInt32(mapping.load(fromByteOffset: offset + n, as: UInt8.self)) }
            return Int32(bitPattern: value)
        }
        precondition(integer(0) == 0x43535632)
        width = integer(4)
        height = integer(8)
        north = Double(integer(12)) / 1e6
        west = Double(integer(16)) / 1e6
        ppd = integer(20)
        precondition(width > 1 && height > 1 && Int64(width) * Int64(height) * 2 + 32 == size)
        super.init()
    }
    func value(index: Int32) -> Int32 {
        let offset = 32 + Int(index) * 2
        precondition(offset >= 32 && offset + 1 < length)
        let high = UInt16(pointer.load(fromByteOffset: offset, as: UInt8.self))
        let low = UInt16(pointer.load(fromByteOffset: offset + 1, as: UInt8.self))
        return Int32(Int16(bitPattern: (high << 8) | low))
    }
    deinit { munmap(pointer, length) }
}
final class Stop: NSObject, StopSignal, @unchecked Sendable {
    private let lock = NSLock()
    private var requested: UInt64 = 0
    func cancel() { lock.lock(); requested = DispatchTime.now().uptimeNanoseconds; lock.unlock() }
    func isCancelled() -> Bool { lock.lock(); defer { lock.unlock() }; return requested != 0 }
    func requestTime() -> UInt64 { lock.lock(); defer { lock.unlock() }; return requested }
}
func now() -> UInt64 { DispatchTime.now().uptimeNanoseconds }
let output = URL(fileURLWithPath: CommandLine.arguments[2])
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
let begin = now()
let raster = MappedRaster(path: CommandLine.arguments[1])
guard let siteLat = Double(CommandLine.arguments[3]), let siteLon = Double(CommandLine.arguments[4]) else { fatalError("Invalid site") }
let engine = WideEngine(reader: raster, width: raster.width, height: raster.height, north: raster.north,
    west: raster.west, ppd: raster.ppd, siteLatitude: siteLat, siteLongitude: siteLon)
precondition(engine.valid())
if CommandLine.arguments.count > 5 && CommandLine.arguments[5] == "diagnostic" {
    try engine.diagnosticCsv().write(to: output.appendingPathComponent("diagnostic.csv"), atomically: true, encoding: .utf8)
    exit(0)
}
let setupMs = Double(now() - begin) / 1e6
var runs: [[String: Any]] = []
for i in 0..<WideScenarios.shared.count() {
    let name = WideScenarios.shared.name(index: i)
    let loaded = engine.makeJob(config: WideScenarios.shared.config(index: i))
    guard let job = loaded.job else { fatalError(loaded.estimate.reason) }
    let start = now()
    var maxStep: UInt64 = 0
    while job.state == .running {
        let t = now(); _ = job.step(maxCells: 16, stop: NeverStop()); maxStep = max(maxStep, now() - t)
    }
    let elapsed = Double(now() - start) / 1e6
    precondition(job.state == .complete)
    try job.csv().write(to: output.appendingPathComponent(name + ".csv"), atomically: true, encoding: .utf8)
    runs.append(["name": name, "compute_ms": elapsed, "max_step_ms": Double(maxStep) / 1e6,
        "cells": loaded.estimate.cells, "sample_work_bound": loaded.estimate.sampleWorkUpperBound])
    print("\(name) \(elapsed) ms")
}
let rejected = engine.makeJob(config: WideConfig(radiusM: 100000, gridM: 200, profileM: 30,
    minEastM: -100000, maxEastM: 100000, minNorthM: -100000, maxNorthM: 100000))
precondition(rejected.job == nil && rejected.estimate.reason == "cell_budget")
let sensitivityStart = now()
try engine.sensitivityCsv().write(to: output.appendingPathComponent("sensitivity.csv"), atomically: true, encoding: .utf8)
let sensitivityMs = Double(now() - sensitivityStart) / 1e6
let signal = Stop()
guard let cancelled = engine.makeJob(config: WideScenarios.shared.config(index: 5)).job else { fatalError("No job") }
DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(20)) { signal.cancel() }
while cancelled.state == .running { _ = cancelled.step(maxCells: 16, stop: signal) }
let end = now()
precondition(cancelled.state == .cancelled)
let completed = cancelled.completed
_ = cancelled.step(maxCells: 16, stop: NeverStop())
precondition(completed == cancelled.completed)
var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
let metrics: [String: Any] = ["map_setup_ms": setupMs, "runs": runs,
    "sensitivity_ms": sensitivityMs, "process_peak_rss_bytes": usage.ru_maxrss,
    "cancel_latency_ms": Double(end - signal.requestTime()) / 1e6,
    "cancel_completed": completed, "oversized_rejected": true]
try JSONSerialization.data(withJSONObject: metrics, options: [.prettyPrinted, .sortedKeys])
    .write(to: output.appendingPathComponent("metrics.json"))
