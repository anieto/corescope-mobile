import Foundation
import Darwin
import CoverageStudy

// No full-file heap copy or memory mapping. Each read decodes at most one 128-sample row.
final class DiskRows: NSObject, TerrainRows {
    let fd: Int32
    let width: Int32
    let height: Int32
    let north: Double
    let west: Double
    let ppd: Int32

    init(path: String) {
        let descriptor = open(path, O_RDONLY)
        precondition(descriptor >= 0)
        let size = lseek(descriptor, 0, SEEK_END)
        precondition(size >= 32 && size <= 200_000_032)
        var header = [UInt8](repeating: 0, count: 32)
        precondition(pread(descriptor, &header, header.count, 0) == header.count)
        func integer(_ offset: Int) -> Int32 {
            var value: UInt32 = 0
            for i in 0..<4 { value = (value << 8) | UInt32(header[offset+i]) }
            return Int32(bitPattern: value)
        }
        precondition(integer(0) == 0x43535632)
        width = integer(4); height = integer(8)
        precondition(width > 1 && height > 1 && Int64(width)*Int64(height)*2+32 == size)
        north = Double(integer(12))/1e6; west = Double(integer(16))/1e6
        ppd = integer(20); fd = descriptor
        super.init()
    }

    func read(start: Int32, count: Int32) -> KotlinShortArray? {
        guard start >= 0, count >= 1, count <= 128,
              Int64(start)+Int64(count) <= Int64(width)*Int64(height) else { return nil }
        var bytes = [UInt8](repeating: 0, count: Int(count)*2)
        let succeeded = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let base = buffer.baseAddress else { return false }
            var done = 0
            while done < buffer.count {
                let received = pread(fd, base.advanced(by: done), buffer.count-done, off_t(32+Int64(start)*2+Int64(done)))
                if received < 0 && errno == EINTR { continue }
                if received <= 0 { return false }
                done += received
            }
            return true
        }
        guard succeeded else { return nil }
        let result = KotlinShortArray(size: count)
        for i in 0..<Int(count) {
            result.set(index: Int32(i), value: Int16(bitPattern: UInt16(bytes[i*2]) << 8 | UInt16(bytes[i*2+1])))
        }
        return result
    }
    deinit { close(fd) }
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
let source = DiskRows(path: CommandLine.arguments[1])
var runs: [[String: Any]] = []
for i in 0..<BoundedSuite.shared.count() {
    let start = now()
    let run = BoundedSuite.shared.run(index: i, source: source, width: source.width, height: source.height,
                                     north: source.north, west: source.west, ppd: source.ppd,
                                     latitude: 50.96, longitude: -115.16)
    let elapsed = Double(now()-start)/1e6
    try run.csv.write(to: output.appendingPathComponent(run.name+".csv"), atomically: true, encoding: .utf8)
    runs.append(["name": run.name, "elapsed_ms": elapsed,
                 "result": try JSONSerialization.jsonObject(with: Data(run.metrics.utf8))])
}
let stop = Stop()
let cache = BoundedRaster(source: source, width: source.width, height: source.height, capacityBytes: 131072, stop: stop)
let engine = WideEngine(reader: cache, width: source.width, height: source.height, north: source.north,
                        west: source.west, ppd: source.ppd, siteLatitude: 50.96, siteLongitude: -115.16)
let job = BoundedJob(engine: engine, config: BoundedSuite.shared.config(index: 4))
let ready = DispatchSemaphore(value: 0)
DispatchQueue.global().async { ready.signal(); Thread.sleep(forTimeInterval: 0.01); stop.cancel() }
ready.wait()
while job.state == .running { _ = job.step(maxCells: 8, stop: stop) }
let ended = now()
precondition(job.state == .cancelled)
let before = job.csv()
precondition(job.step(maxCells: 8, stop: NeverStop()) == 0 && job.csv() == before)
try before.write(to: output.appendingPathComponent("cancel.csv"), atomically: true, encoding: .utf8)
cache.clear(); precondition(cache.residentBytes == 0)
var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
let metrics: [String: Any] = ["runs": runs, "process_peak_rss_bytes": usage.ru_maxrss,
                            "cancel_latency_ms": Double(ended-stop.requestTime())/1e6,
                            "cancel_state": "CANCELLED", "cancel_samples": job.samplesUsed,
                            "cancel_completed": job.completed, "cache_released": true]
try JSONSerialization.data(withJSONObject: metrics, options: [.prettyPrinted, .sortedKeys])
    .write(to: output.appendingPathComponent("metrics.json"))
