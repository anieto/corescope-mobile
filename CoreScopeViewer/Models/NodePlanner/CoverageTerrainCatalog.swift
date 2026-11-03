import Foundation

enum CoverageTerrainCatalogFailure: Error, Equatable {
    case oversized, invalidDocument, unsupportedVersion, duplicateTile
}

/// A bounded, pinned regional catalog. Parsing validates records, not provider trust or availability.
/// The caller must obtain this document from its configured trusted source.
struct CoverageTerrainCatalog: Sendable {
    static let maximumBytes = 4 * 1024 * 1024
    let descriptors: [CoverageTerrainDescriptor]

    init(data: Data) throws {
        guard data.count <= Self.maximumBytes else { throw CoverageTerrainCatalogFailure.oversized }
        let document: Document
        do { document = try JSONDecoder().decode(Document.self, from: data) }
        catch { throw CoverageTerrainCatalogFailure.invalidDocument }
        guard document.version == 1 else { throw CoverageTerrainCatalogFailure.unsupportedVersion }
        guard (1...4096).contains(document.tiles.count) else {
            throw CoverageTerrainCatalogFailure.invalidDocument
        }
        var records: [CoverageTerrainDescriptor] = []
        var seen = Set<CoverageTerrainTile>()
        for tile in document.tiles {
            let descriptor: CoverageTerrainDescriptor
            do {
                descriptor = try CoverageTerrainDescriptor(dataset: document.dataset,
                    revision: document.revision, tile: tile.tile, compressedSHA256: tile.sha256,
                    packedBytes: tile.bytes, side: document.side, url: tile.url,
                    verticalDatum: document.verticalDatum, attribution: document.attribution,
                    sourceType: document.sourceType, originURL: tile.url)
            } catch { throw CoverageTerrainCatalogFailure.invalidDocument }
            guard seen.insert(descriptor.coordinate).inserted else {
                throw CoverageTerrainCatalogFailure.duplicateTile
            }
            records.append(descriptor)
        }
        descriptors = records
    }

    func resolve(_ footprint: CoverageTerrainFootprint) throws -> [CoverageTerrainDescriptor] {
        try footprint.resolve(catalog: descriptors)
    }

    private struct Document: Decodable {
        let version: Int
        let dataset, revision, verticalDatum, attribution, sourceType: String
        let side: Int
        let tiles: [Tile]
        enum CodingKeys: String, CodingKey {
            case version, dataset, revision, attribution, side, tiles
            case verticalDatum = "vertical_datum"
            case sourceType = "source_type"
        }
    }

    private struct Tile: Decodable {
        let tile, sha256, url: String
        let bytes: Int64
    }
}
