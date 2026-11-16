import Compression
import CryptoKit
import Foundation

/// A `.nodescope` export package (docs/node-planner/saved-plans-v3-package.md), identical on Android
/// (SavedPlansPackage.kt) and checked against shared/fixtures/saved-plans/packages.
///
/// Reading follows the document's steps in order and is strict throughout. Records go through the
/// library's own v3 validation, results through B's readers. A result that can't be used here doesn't
/// refuse the package: its study is listed in `unusableResults` and travels without its reference.
struct SavedPlansPackage: Equatable, Sendable {
    enum Scope: String, Codable, Sendable { case study, plan, library }
    enum Platform: String, Codable, Sendable { case ios, android }
    enum OmissionReason: String, Codable, Sendable { case notOnDevice, couldNotBeVerified, needsNewerVersion, notReadableHere }
    struct ExportedBy: Codable, Equatable, Sendable {
        var platform: Platform
        var appVersion: String
    }
    struct Omission: Codable, Equatable, Sendable {
        var studyID: String
        var reason: OmissionReason
    }

    enum Failure: Error, Equatable {
        /// Refused; the reason is for logs and tests, not for display.
        case invalid(String)
        /// A newer package version: "This file needs a newer version of NodeScope".
        case needsNewerVersion
    }

    static let format = "nodescope.saved-plans"
    static let fileExtension = "nodescope"
    static let maximumPackageBytes = 512 * 1024 * 1024
    static let maximumManifestBytes = 16 * 1024 * 1024
    static let maximumExpandedBytes = 512 * 1024 * 1024
    static let resultLimits = [ResultRef.portableCoverage: PortableCoverageResult.maximumBytes,
                               ResultRef.portableLinkProfile: PortableLinkProfileResult.maximumBytes]

    var scope: Scope
    var exportedAtMilliseconds: Int64
    var exportedBy: ExportedBy
    var includesResults: Bool
    var includesNotes: Bool
    var candidateSitePlans: [DeploymentPlan] = []
    var studyPlans: [StudyPlan] = []
    var studies: [SavedStudy]
    var omittedResults: [Omission] = []
    /// Result bytes by SHA-256: one per distinct result a study references.
    var results: [String: Data] = [:]
    /// After reading: studies whose result can't be imported here. Their `resultRef` is already removed;
    /// they can come in as inputs only, after an explicit acknowledgement.
    var unusableResults: Set<String> = []

    // MARK: Reading

    static func read(_ data: Data) throws -> SavedPlansPackage {
        let files = try PackageArchive.entries(data)
        guard let manifest = files["manifest.json"] else { throw Failure.invalid("manifest.json is missing") }
        guard StrictJSONText.isStrict(manifest),
              let root = try? JSONSerialization.jsonObject(with: manifest) as? [String: Any] else {
            throw Failure.invalid("manifest.json is not valid JSON")
        }
        guard root["format"] as? String == format else { throw Failure.invalid("not a NodeScope saved-plans package") }
        guard let version = integer(root["version"]), version >= 1 else { throw Failure.invalid("package version") }
        guard version == 1 else { throw Failure.needsNewerVersion }
        let fields: Set<String> = ["format", "version", "scope", "exportedAtMilliseconds", "exportedBy", "includesResults",
                                   "includesNotes", "candidateSitePlans", "studyPlans", "studies", "omittedResults"]
        guard Set(root.keys) == fields else { throw Failure.invalid("manifest fields") }
        guard let scope = (root["scope"] as? String).flatMap(Scope.init(rawValue:)) else { throw Failure.invalid("unknown scope") }
        guard let exported = integer(root["exportedAtMilliseconds"]), (0...253_402_300_799_999).contains(exported) else {
            throw Failure.invalid("exportedAtMilliseconds")
        }
        guard let by = root["exportedBy"] as? [String: Any], Set(by.keys) == ["platform", "appVersion"],
              let platform = (by["platform"] as? String).flatMap(Platform.init(rawValue:)),
              let appVersion = by["appVersion"] as? String,
              appVersion.range(of: #"^[0-9A-Za-z.+-]{1,32}$"#, options: .regularExpression) != nil else {
            throw Failure.invalid("exportedBy")
        }
        guard let includesResults = boolean(root["includesResults"]), let includesNotes = boolean(root["includesNotes"]) else {
            throw Failure.invalid("includes flags must be booleans")
        }
        guard let sites = root["candidateSitePlans"] as? [Any], let plans = root["studyPlans"] as? [Any],
              var studyObjects = root["studies"] as? [Any], let omittedObjects = root["omittedResults"] as? [Any] else {
            throw Failure.invalid("record collections must be arrays")
        }

        // A result in a newer result version makes that study's result unusable, not the package.
        var unusable = Set<String>()
        for index in studyObjects.indices {
            guard var study = studyObjects[index] as? [String: Any], var ref = study["resultRef"] as? [String: Any],
                  let format = ref["format"] as? String, resultLimits[format] != nil, let refVersion = integer(ref["version"]),
                  refVersion != 1 else { continue }
            guard refVersion > 1 else { throw Failure.invalid("resultRef.version") }
            if let id = study["id"] as? String { unusable.insert(id) }
            ref["version"] = 1
            study["resultRef"] = ref
            studyObjects[index] = study
        }

        // Records: exactly the library's v3 rules, through its own codec.
        let document: DeploymentPlanDocument
        do {
            let records = try JSONSerialization.data(withJSONObject: ["schemaVersion": 3, "documentRevision": 1,
                "plans": sites, "studyPlans": plans, "studies": studyObjects])
            document = try DeploymentPlanCodec.decode(records)
        } catch {
            throw Failure.invalid("records")
        }
        var studies = document.studies

        switch scope {
        case .study:
            guard studies.count == 1, document.studyPlans.isEmpty, document.plans.isEmpty, studies[0].planID == nil else {
                throw Failure.invalid("a study package holds exactly one study and nothing else")
            }
        case .plan:
            guard document.studyPlans.count == 1, document.plans.isEmpty,
                  studies.allSatisfy({ $0.planID == document.studyPlans[0].id }) else {
                throw Failure.invalid("a plan package holds exactly one plan and its studies")
            }
        case .library: break
        }
        if !includesNotes, document.studyPlans.contains(where: { $0.notes != nil }) {
            throw Failure.invalid("notes present although the package says they were omitted")
        }

        // Results: every reference has its entry, every entry is referenced, and the bytes match.
        var results: [String: Data] = [:]
        for index in studies.indices {
            guard let ref = studies[index].resultRef else { continue }
            guard includesResults else { throw Failure.invalid("a result reference in a package without results") }
            guard let limit = resultLimits[ref.format] else { throw Failure.invalid("results that aren't portable never travel") }
            guard let content = files["results/\(ref.sha256).result"] else { throw Failure.invalid("result entry missing") }
            guard content.count == ref.byteLength, DeploymentPlanStore.sha256(content) == ref.sha256 else {
                throw Failure.invalid("result bytes don't match their reference")
            }
            results[ref.sha256] = content
            let id = studies[index].id
            if unusable.contains(id) || content.count > limit || !readable(content, ref: ref, study: studies[index]) {
                unusable.insert(id)
                studies[index].resultRef = nil
            }
        }
        let referenced = Set(document.studies.compactMap { $0.resultRef.map { "results/\($0.sha256).result" } })
        guard Set(files.keys).subtracting(["manifest.json"]) == referenced else {
            throw Failure.invalid("a result entry no study references")
        }
        // Results only unusable studies referenced aren't kept.
        let kept = Set(studies.compactMap { $0.resultRef?.sha256 })
        results = results.filter { kept.contains($0.key) }

        var omitted: [Omission] = []
        let ids = Set(document.studies.map(\.id))
        for object in omittedObjects {
            guard let item = object as? [String: Any], Set(item.keys) == ["studyID", "reason"],
                  let studyID = item["studyID"] as? String, ids.contains(studyID),
                  !omitted.contains(where: { $0.studyID == studyID }) else {
                throw Failure.invalid("omittedResults must name each package study at most once")
            }
            guard let reason = (item["reason"] as? String).flatMap(OmissionReason.init(rawValue:)) else {
                throw Failure.invalid("unknown omission reason")
            }
            guard document.studies.first(where: { $0.id == studyID })?.resultRef == nil else {
                throw Failure.invalid("an omitted result cannot also be included")
            }
            omitted.append(Omission(studyID: studyID, reason: reason))
        }

        return SavedPlansPackage(scope: scope, exportedAtMilliseconds: Int64(exported),
            exportedBy: ExportedBy(platform: platform, appVersion: appVersion), includesResults: includesResults,
            includesNotes: includesNotes, candidateSitePlans: document.plans, studyPlans: document.studyPlans,
            studies: studies, omittedResults: omitted, results: results, unusableResults: unusable)
    }

    /// B's strict reader accepts the payload for exactly this study's inputs.
    private static func readable(_ data: Data, ref: ResultRef, study: SavedStudy) -> Bool {
        if let inputs = study.linkProfile {
            return ref.inputFingerprint == PortableLinkProfileResult.studyFingerprint(inputs)
                && (try? PortableLinkProfileResult.decode(data, inputs: inputs)) != nil
        }
        guard let coverage = study.coverage, ref.inputFingerprint == PortableCoverageResult.studyFingerprint(coverage),
              let inputs = try? PortableCoverageResult.inputs(coverage) else { return false }
        return (try? PortableCoverageResult.decode(data, inputs: inputs, snapshots: PortableCoverageResult.snapshots(coverage),
                                                   catalogComplete: coverage.catalogComplete)) != nil
    }

    /// A JSON integer (not a boolean, not a fraction).
    private static func integer(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let double = number.doubleValue
        guard double.rounded() == double, abs(double) < 9.2e18 else { return nil }
        return number.int64Value
    }

    private static func boolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }

    // MARK: Writing

    /// The package file. Every referenced result must be in `results`; a package that wouldn't read back
    /// exactly as written is refused.
    func encoded() throws -> Data {
        var studies = self.studies
        for index in studies.indices {
            studies[index].coverage?.calculatedResult = nil
            studies[index].linkProfileResult = nil
            studies[index].resultStatus = .none
        }
        let manifest = Manifest(format: Self.format, version: 1, scope: scope, exportedAtMilliseconds: exportedAtMilliseconds,
            exportedBy: exportedBy, includesResults: includesResults, includesNotes: includesNotes,
            candidateSitePlans: candidateSitePlans, studyPlans: studyPlans, studies: studies, omittedResults: omittedResults)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        var entries = [("manifest.json", try encoder.encode(manifest))]
        for sha in Set(studies.compactMap { $0.resultRef?.sha256 }).sorted() {
            guard let data = results[sha] else { throw Failure.invalid("result missing for export") }
            entries.append(("results/\(sha).result", data))
        }
        let archive = try PackageArchive.archive(entries)
        guard archive.count <= Self.maximumPackageBytes else { throw Failure.invalid("package size out of range") }
        return archive
    }

    private struct Manifest: Encodable {
        var format: String
        var version: Int
        var scope: Scope
        var exportedAtMilliseconds: Int64
        var exportedBy: ExportedBy
        var includesResults: Bool
        var includesNotes: Bool
        var candidateSitePlans: [DeploymentPlan]
        var studyPlans: [StudyPlan]
        var studies: [SavedStudy]
        var omittedResults: [Omission]
    }
}

/// The ZIP subset a package uses: stored or deflate entries, no ZIP64, encryption or extra paths.
enum PackageArchive {
    static let maximumEntries = 101
    static let maximumCentralDirectory = 1024 * 1024
    private static let allowedFlags: UInt16 = 0x0002 | 0x0004 | 0x0008 | 0x0800

    private struct Entry {
        var name: String
        var flags: UInt16
        var method: UInt16
        var crc: UInt32
        var compressed: Int
        var expanded: Int
        var local: Int
    }

    private static func fail(_ reason: String) -> SavedPlansPackage.Failure { .invalid(reason) }

    /// Every entry by name, expanded and verified (steps 1–5).
    static func entries(_ data: Data) throws -> [String: Data] {
        let bytes = LittleEndian(data)
        let size = data.count
        guard (22...SavedPlansPackage.maximumPackageBytes).contains(size) else { throw fail("package size out of range") }
        var eocd: Int?
        var offset = size - 22
        while offset >= max(0, size - 22 - 65535) {
            if bytes.u32(offset) == 0x0605_4b50, offset + 22 + Int(bytes.u16(offset + 20)) == size { eocd = offset; break }
            offset -= 1
        }
        guard let eocd else { throw fail("no end of central directory") }
        guard eocd < 20 || bytes.u32(eocd - 20) != 0x0706_4b50 else { throw fail("ZIP64 is not supported") }
        let disk = bytes.u16(eocd + 4), directoryDisk = bytes.u16(eocd + 6)
        let here = Int(bytes.u16(eocd + 8)), total = Int(bytes.u16(eocd + 10))
        let directorySize = bytes.u32(eocd + 12), directoryOffset = bytes.u32(eocd + 16)
        guard disk == 0, directoryDisk == 0, here == total else { throw fail("multi-disk archives are not supported") }
        guard total != 0xFFFF, directorySize != 0xFFFF_FFFF, directoryOffset != 0xFFFF_FFFF else { throw fail("ZIP64 is not supported") }
        guard (1...maximumEntries).contains(total) else { throw fail("entry count") }
        guard Int(directorySize) <= maximumCentralDirectory, Int(directoryOffset) + Int(directorySize) == eocd else {
            throw fail("central directory out of place")
        }

        var entries: [Entry] = []
        var position = Int(directoryOffset)
        for _ in 0..<total {
            guard position + 46 <= eocd, bytes.u32(position) == 0x0201_4b50 else { throw fail("damaged central directory") }
            let flags = bytes.u16(position + 8), method = bytes.u16(position + 10)
            let crc = bytes.u32(position + 16), compressed = bytes.u32(position + 20), expanded = bytes.u32(position + 24)
            let nameLength = Int(bytes.u16(position + 28)), extraLength = Int(bytes.u16(position + 30))
            let commentLength = Int(bytes.u16(position + 32)), startDisk = bytes.u16(position + 34)
            let local = bytes.u32(position + 42)
            guard position + 46 + nameLength <= eocd else { throw fail("damaged central directory") }
            let nameBytes = data.subdata(in: data.startIndex + position + 46 ..< data.startIndex + position + 46 + nameLength)
            position += 46 + nameLength + extraLength + commentLength
            guard position <= eocd else { throw fail("damaged central directory") }
            guard compressed != 0xFFFF_FFFF, expanded != 0xFFFF_FFFF, local != 0xFFFF_FFFF else { throw fail("ZIP64 is not supported") }
            guard flags & 0x2041 == 0 else { throw fail("encrypted entries are not supported") }
            guard flags & ~allowedFlags == 0 else { throw fail("unsupported entry flags") }
            guard method == 0 || method == 8 else { throw fail("unsupported compression method") }
            guard startDisk == 0 else { throw fail("multi-disk archives are not supported") }
            guard nameBytes.allSatisfy({ $0 < 0x80 }), let name = String(data: nameBytes, encoding: .ascii),
                  name == "manifest.json" || name.range(of: #"^results/[0-9a-f]{64}\.result$"#, options: .regularExpression) != nil else {
                throw fail("unexpected entry name")
            }
            guard !entries.contains(where: { $0.name == name }) else { throw fail("duplicate entry") }
            let limit = name == "manifest.json" ? SavedPlansPackage.maximumManifestBytes : PortableCoverageResult.maximumBytes
            guard Int(expanded) <= limit else { throw fail("\(name) is larger than its limit") }
            guard method == 8 || compressed == expanded else { throw fail("stored entry sizes differ") }
            guard Int(compressed) <= Int(expanded) + 1024 + Int(expanded) / 1000 else { throw fail("compressed size out of range") }
            entries.append(Entry(name: name, flags: flags, method: method, crc: crc, compressed: Int(compressed),
                                 expanded: Int(expanded), local: Int(local)))
        }
        guard position == eocd else { throw fail("damaged central directory") }
        guard entries.reduce(0, { $0 + $1.expanded }) <= SavedPlansPackage.maximumExpandedBytes else {
            throw fail("package expands beyond its limit")
        }

        let boundaries = entries.map(\.local).sorted() + [Int(directoryOffset)]
        var out: [String: Data] = [:]
        for entry in entries {
            let local = entry.local
            guard local + 30 <= Int(directoryOffset), bytes.u32(local) == 0x0403_4b50 else { throw fail("damaged local header") }
            let nameLength = Int(bytes.u16(local + 26)), extraLength = Int(bytes.u16(local + 28))
            guard bytes.u16(local + 6) == entry.flags, bytes.u16(local + 8) == entry.method,
                  local + 30 + nameLength <= Int(directoryOffset),
                  data.subdata(in: data.startIndex + local + 30 ..< data.startIndex + local + 30 + nameLength) == Data(entry.name.utf8) else {
                throw fail("local header disagrees with central directory")
            }
            let start = local + 30 + nameLength + extraLength
            let following = boundaries[boundaries.firstIndex(of: local)! + 1]
            guard start + entry.compressed <= following else { throw fail("entries overlap") }
            let raw = data.subdata(in: data.startIndex + start ..< data.startIndex + start + entry.compressed)
            let content = entry.method == 0 ? raw : try inflate(raw, expected: entry.expanded)
            guard content.count == entry.expanded else { throw fail("compressed data does not match its sizes") }
            guard CRC32.checksum(content) == entry.crc else { throw fail("checksum mismatch") }
            out[entry.name] = content
        }
        return out
    }

    /// Raw deflate that must end exactly at `expected` bytes and consume all of `input`; never expands further.
    private static func inflate(_ input: Data, expected: Int) throws -> Data {
        guard !input.isEmpty else { throw fail("damaged compressed data") }
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw fail("damaged compressed data")
        }
        defer { compression_stream_destroy(stream) }
        var output = Data(count: expected + 1)
        let (status, produced, remaining) = input.withUnsafeBytes { source in
            output.withUnsafeMutableBytes { target -> (compression_status, Int, Int) in
                stream.pointee.src_ptr = source.bindMemory(to: UInt8.self).baseAddress!
                stream.pointee.src_size = source.count
                stream.pointee.dst_ptr = target.bindMemory(to: UInt8.self).baseAddress!
                stream.pointee.dst_size = target.count
                var status = COMPRESSION_STATUS_OK
                while status == COMPRESSION_STATUS_OK && stream.pointee.dst_size > 0 {
                    let before = (stream.pointee.src_size, stream.pointee.dst_size)
                    status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                    if status == COMPRESSION_STATUS_OK && before == (stream.pointee.src_size, stream.pointee.dst_size) { break }
                }
                return (status, target.count - stream.pointee.dst_size, stream.pointee.src_size)
            }
        }
        guard status == COMPRESSION_STATUS_END, produced == expected, remaining == 0 else {
            throw fail("compressed data does not match its sizes")
        }
        output.count = produced
        return output
    }

    /// Entries in order, each deflated when that's smaller, otherwise stored.
    static func archive(_ entries: [(String, Data)]) throws -> Data {
        var out = Data(), central = Data()
        for (name, content) in entries {
            let crc = CRC32.checksum(content)
            let deflated = deflate(content)
            let method: UInt16 = deflated == nil ? 0 : 8
            let body = deflated ?? content
            let nameBytes = Data(name.utf8)
            guard out.count < Int(UInt32.max), content.count < Int(UInt32.max) else { throw fail("package size out of range") }
            let local = UInt32(out.count)
            // Version 2.0, no flags, 1980-01-01 00:00.
            out.append(le32: 0x0403_4b50); out.append(le16: 20); out.append(le16: 0); out.append(le16: method)
            out.append(le16: 0); out.append(le16: 0x0021); out.append(le32: crc)
            out.append(le32: UInt32(body.count)); out.append(le32: UInt32(content.count))
            out.append(le16: UInt16(nameBytes.count)); out.append(le16: 0)
            out.append(nameBytes); out.append(body)
            central.append(le32: 0x0201_4b50); central.append(le16: 20); central.append(le16: 20); central.append(le16: 0)
            central.append(le16: method); central.append(le16: 0); central.append(le16: 0x0021); central.append(le32: crc)
            central.append(le32: UInt32(body.count)); central.append(le32: UInt32(content.count))
            central.append(le16: UInt16(nameBytes.count)); central.append(le16: 0); central.append(le16: 0)
            central.append(le16: 0); central.append(le16: 0); central.append(le32: 0); central.append(le32: local)
            central.append(nameBytes)
        }
        let directoryOffset = UInt32(out.count)
        out.append(central)
        out.append(le32: 0x0605_4b50); out.append(le16: 0); out.append(le16: 0)
        out.append(le16: UInt16(entries.count)); out.append(le16: UInt16(entries.count))
        out.append(le32: UInt32(central.count)); out.append(le32: directoryOffset); out.append(le16: 0)
        return out
    }

    /// Raw deflate, or nil when it wouldn't be smaller.
    private static func deflate(_ content: Data) -> Data? {
        guard !content.isEmpty else { return nil }
        var target = Data(count: content.count)
        let written = content.withUnsafeBytes { source in
            target.withUnsafeMutableBytes { output in
                compression_encode_buffer(output.bindMemory(to: UInt8.self).baseAddress!, output.count,
                                          source.bindMemory(to: UInt8.self).baseAddress!, source.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written > 0, written < content.count else { return nil }
        target.count = written
        return target
    }

    private struct LittleEndian {
        let data: Data
        init(_ data: Data) { self.data = data }
        func u16(_ offset: Int) -> UInt16 {
            let base = data.startIndex + offset
            return UInt16(data[base]) | UInt16(data[base + 1]) << 8
        }
        func u32(_ offset: Int) -> UInt32 { UInt32(u16(offset)) | UInt32(u16(offset + 2)) << 16 }
    }
}

private extension Data {
    mutating func append(le16 value: UInt16) { append(contentsOf: [UInt8(value & 0xFF), UInt8(value >> 8)]) }
    mutating func append(le32 value: UInt32) { append(le16: UInt16(value & 0xFFFF)); append(le16: UInt16(value >> 16)) }
}

enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { index in
        var value = UInt32(index)
        for _ in 0..<8 { value = value & 1 == 1 ? 0xEDB8_8320 ^ (value >> 1) : value >> 1 }
        return value
    }

    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { bytes in
            for byte in bytes { crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        }
        return crc ^ 0xFFFF_FFFF
    }
}

/// Checks what JSONSerialization lets through but the package format refuses: duplicate object fields
/// and bare words other than true, false, null and numbers. Assumes otherwise well-formed JSON.
enum StrictJSONText {
    static func isStrict(_ data: Data) -> Bool {
        var stack: [(object: Bool, keys: Set<String>, expectingKey: Bool)] = []
        var index = data.startIndex
        let end = data.endIndex
        while index < end {
            let byte = data[index]
            switch byte {
            case UInt8(ascii: "\""):
                let isKey = stack.last.map { $0.object && $0.expectingKey } ?? false
                if isKey { stack[stack.count - 1].expectingKey = false }
                var cursor = index + 1, escaped = false
                while cursor < end {
                    let c = data[cursor]
                    if c == UInt8(ascii: "\\") { escaped = true; cursor += 2; continue }
                    if c == UInt8(ascii: "\"") { break }
                    cursor += 1
                }
                guard cursor < end else { return false }
                if isKey {
                    let raw = data[index...cursor]
                    let key = escaped ? (try? JSONSerialization.jsonObject(with: Data(raw), options: .fragmentsAllowed)) as? String
                                      : String(data: data[(index + 1)..<cursor], encoding: .utf8)
                    guard let key, stack[stack.count - 1].keys.insert(key).inserted else { return false }
                }
                index = cursor + 1
                continue
            case UInt8(ascii: "{"): stack.append((true, [], true))
            case UInt8(ascii: "["): stack.append((false, [], false))
            case UInt8(ascii: "}"), UInt8(ascii: "]"): if !stack.isEmpty { stack.removeLast() }
            case UInt8(ascii: ","): if stack.last?.object == true { stack[stack.count - 1].expectingKey = true }
            case UInt8(ascii: " "), UInt8(ascii: "\n"), UInt8(ascii: "\r"), UInt8(ascii: "\t"), UInt8(ascii: ":"): break
            default:
                var cursor = index
                while cursor < end, !" \n\r\t,:]}".utf8.contains(data[cursor]) { cursor += 1 }
                let word = String(decoding: data[index..<cursor], as: UTF8.self)
                guard ["true", "false", "null"].contains(word)
                        || word.range(of: #"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$"#, options: .regularExpression) != nil else {
                    return false
                }
                index = cursor
                continue
            }
            index += 1
        }
        return stack.isEmpty
    }
}
