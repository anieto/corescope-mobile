import CryptoKit
import Foundation
import zlib

enum CoverageTerrainDecodeFailure: Error, Equatable {
    case corrupt, cancelled, io
}

/// Caller owns this temporary decoded output until close; publishing copies it into the cache.
final class DecodedCoverageTerrain {
    let fileURL: URL
    let sha256: String
    private let directory: URL
    fileprivate init(directory: URL, sha256: String) {
        self.directory = directory
        fileURL = directory.appendingPathComponent("terrain.hgt")
        self.sha256 = sha256
    }
    func close() throws {
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
    deinit { try? close() }
}

/// Serialized background-worker decoder. Input is a private immutable download file.
/// Accepts one gzip member only, with a <=64 KiB header and no concatenation/trailing bytes.
enum CoverageTerrainDecoder {
    static func decode(_ descriptor: CoverageTerrainDescriptor, packedFile: URL, workspace: URL,
                       cancelled: () -> Bool = { false }) throws -> DecodedCoverageTerrain {
        func check() throws { if cancelled() { throw CoverageTerrainDecodeFailure.cancelled } }
        try check()
        let input = try FileHandle(forReadingFrom: packedFile)
        defer { try? input.close() }
        // Bound optional name/comment/extra fields before invoking the gzip implementation.
        let prefix = try input.read(upToCount: Int(min(65536, descriptor.packedBytes + 1))) ?? Data()
        try validateHeader(prefix)
        try input.seek(toOffset: 0)
        let directory = workspace.appendingPathComponent(".decode-" + UUID().uuidString.lowercased())
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        var transferred = false
        defer { if !transferred { try? FileManager.default.removeItem(at: directory) } }
        let destination = directory.appendingPathComponent("terrain.hgt")
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else { throw CoverageTerrainDecodeFailure.io }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var stream = z_stream()
        guard inflateInit2_(&stream, MAX_WBITS + 16, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
            throw CoverageTerrainDecodeFailure.io
        }
        defer { inflateEnd(&stream) }
        var packedHash = SHA256(), decodedHash = SHA256()
        var received: Int64 = 0, written: Int64 = 0
        var ended = false
        var outputBuffer = [UInt8](repeating: 0, count: 65536)
        while !ended {
            try check()
            let bytes = try input.read(upToCount: Int(min(65536, descriptor.packedBytes - received + 1))) ?? Data()
            guard !bytes.isEmpty, Int64(bytes.count) <= descriptor.packedBytes - received else {
                throw CoverageTerrainDecodeFailure.corrupt
            }
            received += Int64(bytes.count)
            packedHash.update(data: bytes)
            try bytes.withUnsafeBytes { raw in
                stream.next_in = UnsafeMutablePointer(mutating: raw.bindMemory(to: UInt8.self).baseAddress)
                stream.avail_in = uInt(bytes.count)
                repeat {
                    try check()
                    let before = stream.avail_in
                    // One-byte probe detects expansion without ever writing beyond dimensions.
                    let capacity = Int(min(65536, descriptor.decodedBytes - written + 1))
                    let status = outputBuffer.withUnsafeMutableBufferPointer { buffer -> Int32 in
                        stream.next_out = buffer.baseAddress
                        stream.avail_out = uInt(capacity)
                        return inflate(&stream, Z_NO_FLUSH)
                    }
                    let count = capacity - Int(stream.avail_out)
                    guard Int64(count) <= descriptor.decodedBytes - written else { throw CoverageTerrainDecodeFailure.corrupt }
                    if count > 0 {
                        let decoded = Data(outputBuffer.prefix(count))
                        try output.write(contentsOf: decoded)
                        decodedHash.update(data: decoded)
                        written += Int64(count)
                    }
                    if status == Z_STREAM_END {
                        guard stream.avail_in == 0 else { throw CoverageTerrainDecodeFailure.corrupt }
                        ended = true
                        break
                    }
                    guard status == Z_OK || (status == Z_BUF_ERROR && stream.avail_in == 0) else {
                        throw CoverageTerrainDecodeFailure.corrupt
                    }
                    if stream.avail_in == 0 && stream.avail_out > 0 { break }
                    guard count > 0 || stream.avail_in < before else { throw CoverageTerrainDecodeFailure.corrupt }
                } while true
                stream.next_in = nil
                stream.next_out = nil
            }
        }
        try check()
        guard (try input.read(upToCount: 1) ?? Data()).isEmpty, received == descriptor.packedBytes,
              written == descriptor.decodedBytes,
              hex(packedHash.finalize()) == descriptor.compressedSHA256 else { throw CoverageTerrainDecodeFailure.corrupt }
        try output.synchronize()
        try output.close()
        try check()
        let result = DecodedCoverageTerrain(directory: directory, sha256: hex(decodedHash.finalize()))
        transferred = true
        return result
    }

    private static func hex(_ digest: SHA256.Digest) -> String {
        digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Full CRC validation is performed by zlib; this bounds the optional header.
    private static func validateHeader(_ bytes: Data) throws {
        guard bytes.count >= 10, bytes[0] == 0x1f, bytes[1] == 0x8b, bytes[2] == 8,
              bytes[3] & 0xe0 == 0 else { throw CoverageTerrainDecodeFailure.corrupt }
        let flags = bytes[3]
        var position = 10
        if flags & 4 != 0 {
            guard position + 2 <= bytes.count else { throw CoverageTerrainDecodeFailure.corrupt }
            let length = Int(bytes[position]) | Int(bytes[position + 1]) << 8
            position += 2 + length
            guard position <= bytes.count else { throw CoverageTerrainDecodeFailure.corrupt }
        }
        for flag: UInt8 in [8, 16] where flags & flag != 0 {
            guard position < bytes.count, let zero = bytes[position...].firstIndex(of: 0) else {
                throw CoverageTerrainDecodeFailure.corrupt
            }
            position = zero + 1
        }
        if flags & 2 != 0 { position += 2 }
        guard position <= bytes.count else { throw CoverageTerrainDecodeFailure.corrupt }
    }
}
