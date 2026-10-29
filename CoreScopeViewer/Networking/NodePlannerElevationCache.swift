import Foundation

struct NodePlannerCachedElevation: Codable, Equatable, Sendable {
    let coordinate: NodePlannerCoordinate
    let elevationMeters: Double
    let fetchedAt: Date
}

protocol NodePlannerElevationCaching: Sendable {
    func elevation(
        for coordinate: NodePlannerCoordinate,
        newerThan cutoff: Date
    ) async -> NodePlannerCachedElevation?

    func store(_ elevations: [NodePlannerCachedElevation]) async
}

actor NodePlannerElevationDiskCache: NodePlannerElevationCaching {
    private struct Envelope: Codable {
        let schemaVersion: Int
        let provider: String
        let dataset: String
        var records: [NodePlannerCachedElevation]
    }

    private static let schemaVersion = 1

    private let fileURL: URL
    private var records: [NodePlannerCoordinate: NodePlannerCachedElevation]?

    init(fileURL: URL = NodePlannerElevationDiskCache.defaultFileURL()) {
        self.fileURL = fileURL
    }

    func elevation(
        for coordinate: NodePlannerCoordinate,
        newerThan cutoff: Date
    ) -> NodePlannerCachedElevation? {
        loadIfNeeded()
        guard let record = records?[coordinate],
              record.fetchedAt >= cutoff,
              record.elevationMeters.isFinite else { return nil }
        return record
    }

    func store(_ elevations: [NodePlannerCachedElevation]) {
        guard !elevations.isEmpty else { return }
        loadIfNeeded()
        for elevation in elevations where elevation.elevationMeters.isFinite {
            records?[elevation.coordinate] = elevation
        }
        persist()
    }

    private func loadIfNeeded() {
        guard records == nil else { return }
        guard let data = try? Data(contentsOf: fileURL),
              let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              envelope.schemaVersion == Self.schemaVersion,
              envelope.provider == NodePlannerElevationProvenance.provider,
              envelope.dataset == NodePlannerElevationProvenance.dataset else {
            records = [:]
            return
        }
        records = Dictionary(
            envelope.records.map { ($0.coordinate, $0) },
            uniquingKeysWith: { _, newer in newer }
        )
    }

    private func persist() {
        guard let records else { return }
        let envelope = Envelope(
            schemaVersion: Self.schemaVersion,
            provider: NodePlannerElevationProvenance.provider,
            dataset: NodePlannerElevationProvenance.dataset,
            records: records.values.sorted {
                if $0.coordinate.latitude == $1.coordinate.latitude {
                    $0.coordinate.longitude < $1.coordinate.longitude
                } else {
                    $0.coordinate.latitude < $1.coordinate.latitude
                }
            }
        )
        guard let data = try? JSONEncoder().encode(envelope) else { return }
        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: fileURL, options: .atomic)
        } catch {
            // A cache write must never prevent a fresh analysis from completing.
        }
    }

    private static func defaultFileURL() -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base
            .appendingPathComponent("NodePlanner", isDirectory: true)
            .appendingPathComponent("elevation-cache-v1.json", isDirectory: false)
    }
}
