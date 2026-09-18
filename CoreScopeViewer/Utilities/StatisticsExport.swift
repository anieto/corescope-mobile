import SwiftUI
import UniformTypeIdentifiers

struct TextExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText, .json] }

    let data: Data

    init(data: Data = Data()) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

enum StatisticsExportFormat {
    case csv
    case json

    var contentType: UTType {
        switch self {
        case .csv: .commaSeparatedText
        case .json: .json
        }
    }

    var filenameExtension: String {
        switch self {
        case .csv: "csv"
        case .json: "json"
        }
    }
}

enum StatisticsExportBuilder {
    static func jsonData<T: Encodable>(_ value: T) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .iso8601
        return (try? encoder.encode(value)) ?? Data("{}".utf8)
    }

    static func csv(rows: [[String]]) -> Data {
        let text = rows.map { $0.map(escapeCSV).joined(separator: ",") }.joined(separator: "\n")
        return Data((text + "\n").utf8)
    }

    private static func escapeCSV(_ value: String) -> String {
        guard value.contains(",") || value.contains("\"") || value.contains("\n") else { return value }
        return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    static func safeFilenameComponent(_ value: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        let sanitized = value.unicodeScalars.map { allowed.contains($0) ? Character($0) : "-" }
        return String(sanitized).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    }
}
