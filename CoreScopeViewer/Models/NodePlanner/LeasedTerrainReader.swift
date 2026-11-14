import CryptoKit
import Foundation
import Darwin

public enum TerrainReadFailure: Error, Equatable {
    case invalidDescriptor, corrupt, io, cancelled, closed, invalidRange
}

/// Serialized worker-owned reader. The repository transfers one pin to `open`;
/// release is called once, on failed open or after the file handle closes.
/// The repository must keep leased files immutable. Do not use on the UI actor.
public final class LeasedTerrainReader {
    private var file: FileHandle?
    private var mapping: UnsafeMutableRawPointer?
    private var mappingLength = 0
    private(set) var mappedReadCalls = 0
    var mappedBytes: Int { mappingLength }
    private let blockCaching: Bool
    private static let blockSamples = 8192
    private var blockLimit = 4
    var blockCacheCapacityBytes: Int { blockLimit * Self.blockSamples * 2 }
    private var blocks: [Int: Data] = [:]
    private var access: [Int: UInt64] = [:]
    private var sequence: UInt64 = 0
    private(set) var blockHits = 0
    private(set) var blockMisses = 0
    private(set) var fileReadCalls = 0
    private(set) var fileReadBytes = 0
    var cachedBlockBytes: Int { blocks.values.reduce(0) { $0 + $1.count } }
    private var release: (() -> Void)?
    public let side: Int
    public static let maximumReadSamples = 32768

    /// The mosaic divides a fixed budget among its readers before any sampling.
    /// Standalone readers retain the original four-block limit.
    func configureBlockCache(blockCount: Int) {
        blockLimit = min(64, max(4, blockCount))
        while blocks.count > blockLimit, let oldest = access.min(by: { $0.value < $1.value })?.key {
            blocks.removeValue(forKey: oldest)
            access.removeValue(forKey: oldest)
        }
    }

    private init(file: FileHandle, side: Int, blockCaching: Bool, release: @escaping () -> Void) {
        self.blockCaching = blockCaching
        self.file = file
        self.side = side
        self.release = release
    }

    public static func open(
        url: URL, side: Int, decodedSHA256: String,
        cancelled: () -> Bool = { false },
        blockCaching: Bool = !ProcessInfo.processInfo.arguments.contains("-CoverageDisableTerrainBlockCache"),
        memoryMapping: Bool = !ProcessInfo.processInfo.arguments.contains("-CoverageDisableTerrainMapping"),
        release: @escaping () -> Void
    ) throws -> LeasedTerrainReader {
        var handle: FileHandle?
        var transferred = false
        defer {
            if !transferred {
                try? handle?.close()
                release()
            }
        }
        guard (2...3601).contains(side), 3600 % (side - 1) == 0,
              decodedSHA256.utf8.count == 64,
              decodedSHA256.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
            throw TerrainReadFailure.invalidDescriptor
        }
        do {
            if cancelled() { throw TerrainReadFailure.cancelled }
            let file = try FileHandle(forReadingFrom: url)
            handle = file
            let size = side * side * 2
            guard try file.seekToEnd() == UInt64(size) else { throw TerrainReadFailure.corrupt }
            try file.seek(toOffset: 0)
            var hash = SHA256()
            var remaining = size
            while remaining > 0 {
                if cancelled() { throw TerrainReadFailure.cancelled }
                guard let bytes = try file.read(upToCount: min(65536, remaining)), !bytes.isEmpty else {
                    throw TerrainReadFailure.corrupt
                }
                hash.update(data: bytes)
                remaining -= bytes.count
            }
            let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
            guard digest == decodedSHA256, try file.seekToEnd() == UInt64(size) else {
                throw TerrainReadFailure.corrupt
            }
            if cancelled() { throw TerrainReadFailure.cancelled }
            let result = LeasedTerrainReader(file: file, side: side, blockCaching: blockCaching, release: release)
            if memoryMapping {
                // Map the same descriptor that passed the digest and size checks.
                // The repository keeps this leased inode immutable until close.
                let pointer = mmap(nil, size, PROT_READ, MAP_PRIVATE, file.fileDescriptor, 0)
                if let pointer, pointer != MAP_FAILED {
                    result.mapping = pointer
                    result.mappingLength = size
                }
                // Address-space/resource failures retain the bounded block-reader fallback.
            }
            transferred = true
            return result
        } catch let error as TerrainReadFailure {
            throw error
        } catch {
            throw TerrainReadFailure.io
        }
    }

    /// North-first, row-major signed HGT samples. -32768 remains nodata, not zero.
    /// Reads may cross rows but never exceed 64 KiB of encoded samples.
    public func read(start: Int, count: Int, cancelled: () -> Bool = { false }) throws -> [Int16] {
        guard let file else { throw TerrainReadFailure.closed }
        guard start >= 0, (1...Self.maximumReadSamples).contains(count),
              start <= side * side - count else { throw TerrainReadFailure.invalidRange }
        if cancelled() { throw TerrainReadFailure.cancelled }
        if let mapping {
            // Byte-wise loads avoid alignment assumptions and preserve signed big-endian HGT values.
            let bytes = UnsafeRawBufferPointer(start: mapping, count: mappingLength)
            var result: [Int16] = []
            result.reserveCapacity(count)
            for index in start..<(start + count) {
                if index % 1024 == 0, cancelled() { throw TerrainReadFailure.cancelled }
                result.append(Int16(bitPattern: UInt16(bytes[index * 2]) << 8 | UInt16(bytes[index * 2 + 1])))
            }
            if cancelled() { throw TerrainReadFailure.cancelled }
            mappedReadCalls += 1
            return result
        }
        do {
            if !blockCaching {
                let bytes = try readBytes(file, start: start, count: count, cancelled: cancelled)
                return decode(bytes, start: 0, count: count)
            }
            let offset = start % Self.blockSamples
            if count <= Self.blockSamples - offset {
                let bytes = try blockBytes(file, index: start / Self.blockSamples, cancelled: cancelled)
                let result = decode(bytes, start: offset, count: count)
                if cancelled() { throw TerrainReadFailure.cancelled }
                return result
            }
            var result: [Int16] = []
            result.reserveCapacity(count)
            var position = start
            while position < start + count {
                if cancelled() { throw TerrainReadFailure.cancelled }
                let block = position / Self.blockSamples
                let offset = position % Self.blockSamples
                let length = min(start + count - position, Self.blockSamples - offset)
                let bytes = try blockBytes(file, index: block, cancelled: cancelled)
                result.append(contentsOf: decode(bytes, start: offset, count: length))
                position += length
            }
            if cancelled() { throw TerrainReadFailure.cancelled }
            return result
        } catch let error as TerrainReadFailure {
            throw error
        } catch {
            throw TerrainReadFailure.io
        }
    }

    /// Allocation-free access for bilinear interpolation's one- or two-sample row.
    /// A single-sample request must not read an unused neighbor (which may be nodata or out of bounds).
    func readPair(start: Int, adjacent: Bool, cancelled: () -> Bool = { false }) throws -> (Int16, Int16) {
        guard file != nil else { throw TerrainReadFailure.closed }
        let count = adjacent ? 2 : 1
        guard start >= 0, start <= side * side - count else { throw TerrainReadFailure.invalidRange }
        if cancelled() { throw TerrainReadFailure.cancelled }
        guard let mapping else {
            let values = try read(start: start, count: count, cancelled: cancelled)
            return (values[0], adjacent ? values[1] : values[0])
        }
        let bytes = UnsafeRawBufferPointer(start: mapping, count: mappingLength)
        let offset = start * 2
        let first = Int16(bitPattern: UInt16(bytes[offset]) << 8 | UInt16(bytes[offset + 1]))
        let second = adjacent
            ? Int16(bitPattern: UInt16(bytes[offset + 2]) << 8 | UInt16(bytes[offset + 3])) : first
        if cancelled() { throw TerrainReadFailure.cancelled }
        mappedReadCalls += 1
        return (first, second)
    }

    private func decode(_ bytes: Data, start: Int, count: Int) -> [Int16] {
        (start..<(start + count)).map {
            Int16(bitPattern: UInt16(bytes[$0 * 2]) << 8 | UInt16(bytes[$0 * 2 + 1]))
        }
    }

    private func blockBytes(_ file: FileHandle, index: Int, cancelled: () -> Bool) throws -> Data {
        sequence &+= 1
        if let bytes = blocks[index] {
            blockHits += 1
            access[index] = sequence
            return bytes
        }
        blockMisses += 1
        let start = index * Self.blockSamples
        let bytes = try readBytes(file, start: start, count: min(Self.blockSamples, side * side - start),
                                  cancelled: cancelled)
        // Production mosaics use four blocks per reader, up to 16 MiB across 256 readers.
        // Leased files are immutable, so cache hits preserve the validated source bytes.
        if blocks.count == blockLimit, let oldest = access.min(by: { $0.value < $1.value })?.key {
            blocks.removeValue(forKey: oldest)
            access.removeValue(forKey: oldest)
        }
        blocks[index] = bytes
        access[index] = sequence
        return bytes
    }

    private func readBytes(_ file: FileHandle, start: Int, count: Int,
                           cancelled: () -> Bool) throws -> Data {
        try file.seek(toOffset: UInt64(start * 2))
        var bytes = Data()
        while bytes.count < count * 2 {
            if cancelled() { throw TerrainReadFailure.cancelled }
            fileReadCalls += 1
            guard let part = try file.read(upToCount: count * 2 - bytes.count), !part.isEmpty else {
                throw TerrainReadFailure.corrupt
            }
            fileReadBytes += part.count
            bytes.append(part)
        }
        if cancelled() { throw TerrainReadFailure.cancelled }
        return bytes
    }

    public func close() {
        guard let file else { return }
        self.file = nil
        if let mapping {
            munmap(mapping, mappingLength)
            self.mapping = nil
            mappingLength = 0
        }
        blocks.removeAll()
        access.removeAll()
        try? file.close()
        let callback = release
        release = nil
        callback?()
    }

    deinit { close() }
}
