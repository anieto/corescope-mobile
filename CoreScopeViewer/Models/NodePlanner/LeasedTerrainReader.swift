import CryptoKit
import Foundation

public enum TerrainReadFailure: Error, Equatable {
    case invalidDescriptor, corrupt, io, cancelled, closed, invalidRange
}

/// Serialized worker-owned reader. The repository transfers one pin to `open`;
/// release is called once, on failed open or after the file handle closes.
/// The repository must keep leased files immutable. Do not use on the UI actor.
public final class LeasedTerrainReader {
    private var file: FileHandle?
    private var release: (() -> Void)?
    public let side: Int
    public static let maximumReadSamples = 32768

    private init(file: FileHandle, side: Int, release: @escaping () -> Void) {
        self.file = file
        self.side = side
        self.release = release
    }

    public static func open(
        url: URL, side: Int, decodedSHA256: String,
        cancelled: () -> Bool = { false }, release: @escaping () -> Void
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
            let result = LeasedTerrainReader(file: file, side: side, release: release)
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
        do {
            try file.seek(toOffset: UInt64(start * 2))
            var bytes = Data()
            while bytes.count < count * 2 {
                if cancelled() { throw TerrainReadFailure.cancelled }
                guard let part = try file.read(upToCount: count * 2 - bytes.count), !part.isEmpty else {
                    throw TerrainReadFailure.corrupt
                }
                bytes.append(part)
            }
            if cancelled() { throw TerrainReadFailure.cancelled }
            return (0..<count).map {
                Int16(bitPattern: UInt16(bytes[$0 * 2]) << 8 | UInt16(bytes[$0 * 2 + 1]))
            }
        } catch let error as TerrainReadFailure {
            throw error
        } catch {
            throw TerrainReadFailure.io
        }
    }

    public func close() {
        guard let file else { return }
        self.file = nil
        try? file.close()
        let callback = release
        release = nil
        callback?()
    }

    deinit { close() }
}
