import SwiftUI

extension CoverageStudyRunFailure {
    var coverageMessage: LocalizedStringKey {
        switch self {
        case .network: "Terrain request failed. Check your connection and retry; the provider may be temporarily unavailable."
        case .terrain: "Required terrain is missing, changed or invalid. Retry, or choose another location. Missing heights are never replaced with sea level."
        case .storage: "Terrain cache could not be prepared. Free device storage or wait for another calculation to finish, then retry with a smaller radius."
        case .limits: "This study exceeds the download or calculation budget. Choose a smaller radius or wider sample spacing."
        case .operation, .invalidResult: "Calculation could not produce a valid result. Review radio inputs and try a smaller area. No partial result was published."
        }
    }
}

extension CoverageNodeStatus {
    var coverageLabel: String {
        switch reason {
        case .outsideRadius: return String(localized: "Outside study radius")
        case .processingLimit: return String(localized: "Not evaluated — work limit")
        case .missingTerrain: return String(localized: "Unknown — missing terrain")
        case .unsupportedPath: return String(localized: "Unknown — unsupported path")
        case .accuracyUnresolved: return String(localized: "Unknown — sampling did not converge")
        case .thresholdUnresolved: return String(localized: "Unknown — too close to threshold")
        default: break
        }
        switch state {
        case .inCoverage: return String(localized: "Predicted above threshold")
        case .belowThreshold: return String(localized: "Predicted below threshold")
        case .unknown: return String(localized: "Unknown")
        case .stale: return String(localized: "Changed — calculate again")
        case .notEvaluated: return String(localized: "Not evaluated")
        }
    }
}

struct CoverageResultsView: View {
    let result: CoverageStudyResult
    var body: some View {
        let qualified = result.area.cells.filter { $0.state == .qualified }
        let above = qualified.filter { ($0.marginDb ?? -.infinity) >= 0 }.count
        let unresolved = result.area.cells.filter { ![.qualified, .nearSite, .outsideRadius].contains($0.state) }.count
        VStack(alignment: .leading, spacing: 8) {
            Text("Estimated result").font(.monaspace(.headline))
            Text("\(above) above threshold · \(qualified.count - above) below · \(unresolved) unresolved samples")
                .font(.monaspace(.caption))
            Text("Green: predicted above threshold. Clear within the study radius: below threshold. Gray hatching: unresolved or within the unsupported 250 m near-site area. Outside the radius is not analyzed.")
                .font(.caption)
            Text("This is a sampled estimate, not measured reception or a guarantee. Gaps and work-limited areas remain unknown. The nearest 200 available nodes use the same assumed receiver; the catalog is incomplete.")
                .font(.caption).foregroundStyle(.secondary)
            if let model = result.model {
                Text(model.scenario).font(.caption)
                Text("Model revision: \(model.sourceRevision)").font(.monaspace(.caption2)).textSelection(.enabled)
            }
            if let sources = result.terrainSources {
                Text("\(sources.descriptors.count) terrain tiles · EGM96 heights").font(.caption)
                Text("Mapzen / AWS Skadi uses mixed regional elevation sources; trees and buildings may remain in the terrain surface.").font(.caption)
                if let url = URL(string: "https://github.com/tilezen/joerd/blob/master/docs/attribution.md") {
                    Link("Terrain source credits", destination: url)
                }
                DisclosureGroup("Terrain provenance") {
                    ForEach(sources.descriptors, id: \.cacheKey) { source in
                        VStack(alignment: .leading) {
                            Text(source.tile).font(.monaspace(.caption))
                            Text(source.url).font(.caption2).textSelection(.enabled)
                            Text(source.sourceETag ?? source.compressedSHA256 ?? "").font(.monaspace(.caption2)).textSelection(.enabled)
                        }
                    }
                }
            }
        }
    }
}
