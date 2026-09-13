import Foundation
import Observation

/// The one shared "which region am I looking at" selection, so a user can
/// narrow the whole app to one region instead of setting the filter
/// separately on each screen. Regions themselves come from the host's
/// /api/config/regions, not a hardcoded Texas list, so this works the same
/// way against any CoreScope host.
@Observable
@MainActor
final class RegionFilterStore {
    private static let selectedRegionDefaultsKey = "selectedRegion"

    var regions: RegionsMap = [:]
    var selectedRegion: String? {
        didSet {
            UserDefaults.standard.set(selectedRegion, forKey: Self.selectedRegionDefaultsKey)
        }
    }
    private(set) var iataCoords: [String: IataCoordinate] = [:]

    private var apiClient: APIClient?
    private var cacheNamespace = ""

    init() {
        selectedRegion = UserDefaults.standard.string(forKey: Self.selectedRegionDefaultsKey)
    }

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
        cacheNamespace = settings.host.lowercased()
    }

    /// Call after `configure` changes (e.g. the analyzer host changed) —
    /// the old region list and selection no longer make sense.
    func reset() {
        regions = [:]
        selectedRegion = nil
        iataCoords = [:]
    }

    var options: [String] {
        regions.keys.sorted()
    }

    func label(for code: String) -> String {
        regions[code] ?? code
    }

    func loadRegions() async {
        guard let apiClient else { return }
        let cacheKey = "regions-\(cacheNamespace)"
        if let cached: RegionsMap = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 7 * 24 * 60 * 60
        ) {
            regions = cached
        }
        if let fresh: RegionsMap = try? await APIResponseCache.shared.refresh(for: cacheKey, loader: {
            try await apiClient.get("/api/config/regions")
        }) {
            regions = fresh
        }
    }

    /// Powers "zoom the map to the selected region" — a center + rough
    /// radius per region code, independent of the node list.
    func loadIataCoords() async {
        guard let apiClient else { return }
        let cacheKey = "iata-coordinates-\(cacheNamespace)"
        if let cached: IataCoordsResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 7 * 24 * 60 * 60
        ) {
            iataCoords = cached.coords
        }
        if let fresh: IataCoordsResponse = try? await APIResponseCache.shared.refresh(for: cacheKey, loader: {
            try await apiClient.get("/api/iata-coords")
        }) {
            iataCoords = fresh.coords
        }
    }
}
