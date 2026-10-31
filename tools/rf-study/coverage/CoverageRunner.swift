// Isolated iOS simulator driver. Shared Kotlin owns sampling, validation and RF math.
import Foundation
import Darwin
import CoverageStudy

final class CancellationSignal: NSObject, StopSignal, @unchecked Sendable {
    private let lock = NSLock()
    private var requested: UInt64 = 0
    func cancel() {
        lock.lock()
        requested = DispatchTime.now().uptimeNanoseconds
        lock.unlock()
    }
    func isCancelled() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return requested != 0
    }
    func requestedAt() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return requested
    }
}

func write(_ text: String, _ path: URL) throws {
    try text.write(to: path, atomically: true, encoding: .utf8)
}
let output = URL(fileURLWithPath: CommandLine.arguments[2])
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
let start = DispatchTime.now().uptimeNanoseconds
let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let bytes = KotlinByteArray(size: Int32(data.count))
for (index, value) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: value)) }
let loaded = CoverageEngine.companion.load(bytes: bytes)
guard let engine = loaded.engine else { fatalError("Terrain load failed: \(loaded.state)") }
let decodeMs = Double(DispatchTime.now().uptimeNanoseconds - start) / 1e6
precondition(CoverageEngine.companion.load(bytes: KotlinByteArray(size: 1)).state == .invalidInput)
precondition(engine.predict(latitude: .nan, longitude: -114.3, receiverHeightM: 1.5, stop: NeverStop()).state == .invalidInput)
precondition(engine.predict(latitude: 51.16, longitude: -114.3, receiverHeightM: -1, stop: NeverStop()).state == .invalidInput)
var runs: [[String: Any]] = []
for scenario in ["clean", "gap", "cancel"] {
    let source = scenario == "gap" ? engine.withGap() : engine
    let job = source.doNewJob()
    let signal = CancellationSignal()
    if scenario == "cancel" {
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(20)) { signal.cancel() }
    }
    let begin = DispatchTime.now().uptimeNanoseconds
    var maxStep: UInt64 = 0
    while job.state == .running {
        let before = DispatchTime.now().uptimeNanoseconds
        _ = job.step(maxCells: 16, stop: signal)
        maxStep = max(maxStep, DispatchTime.now().uptimeNanoseconds - before)
    }
    let end = DispatchTime.now().uptimeNanoseconds
    let completed = job.completed
    if scenario == "cancel" {
        precondition(job.state == .cancelled && completed < job.total)
        _ = job.step(maxCells: 16, stop: NeverStop())
        precondition(job.completed == completed)
    } else { precondition(job.state == .complete) }
    try write(job.csv(), output.appendingPathComponent("\(scenario).csv"))
    runs.append(["scenario": scenario, "state": job.state.name, "completed": completed,
        "compute_ms": Double(end - begin) / 1e6, "max_step_ms": Double(maxStep) / 1e6,
        "cancel_latency_ms": signal.requestedAt() == 0 ? NSNull() : Double(end - signal.requestedAt()) / 1e6])
}
var links = "fixture,east_m,north_m,rx_height_m,latitude,longitude,state,loss_db,samples\n"
for (name, east, north, height) in [("off_grid",1375.0,1825.0,1.5),("high_receiver",1375,1825,10),
    ("west",-2225,-1625,1.5),("near",10,20,1.5),("gap_crossing",3500,0,1.5)] {
    let point = engine.pointAt(eastM: east, northM: north)
    let source = name == "gap_crossing" ? engine.withGap() : engine
    let result = source.predict(latitude: point.latitude, longitude: point.longitude, receiverHeightM: height, stop: NeverStop())
    let loss = result.lossDb.map { String($0.doubleValue) } ?? ""
    links += "\(name),\(east),\(north),\(height),\(point.latitude),\(point.longitude),\(result.state.name),\(loss),\(result.sampleCount)\n"
}
try write(links, output.appendingPathComponent("links.csv"))
var usage = rusage()
getrusage(RUSAGE_SELF, &usage)
let metrics: [String: Any] = ["decode_ms": decodeMs, "runs": runs,
    "process_peak_rss_bytes": usage.ru_maxrss, "boundary_checks_passed": true]
let encoded = try JSONSerialization.data(withJSONObject: metrics, options: [.prettyPrinted, .sortedKeys])
try encoded.write(to: output.appendingPathComponent("metrics.json"))
print(String(decoding: encoded, as: UTF8.self))
