import Foundation
import Observation

/// The one shared "which region am I looking at" selection, so a user can
/// narrow the whole app to one region instead of setting the filter
/// separately on each screen. Regions themselves come from the host's
/// /api/config/regions, not a hardcoded Texas list, so this works the same
/// way against any CoreScope host.
///
/// `selectedRegion` is the server's `region=` value: one code, or a quick
/// pick's codes joined with commas ("AUS,SAT,ACT"), which CoreScope accepts
/// everywhere. Compare against it with `includes(_:)`, never `==`.
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
    private(set) var quickPicks: [RegionQuickPick] = []

    private var apiClient: (any AnalyzerBackend)?
    private var cacheNamespace = ""

    init() {
        selectedRegion = UserDefaults.standard.string(forKey: Self.selectedRegionDefaultsKey)
    }

    func configure(settings: AnalyzerSettings) {
        let backend = AnalyzerBackendFactory.make(settings: settings)
        if let previous = apiClient, previous.sourceSession.id != backend.sourceSession.id { reset() }
        apiClient = backend
        cacheNamespace = backend.cacheIdentifier
    }

    /// Call after `configure` changes (e.g. the analyzer host changed) —
    /// the old region list and selection no longer make sense.
    func reset() {
        regions = [:]
        selectedRegion = nil
        iataCoords = [:]
        quickPicks = []
    }

    var options: [String] {
        regions.keys.sorted()
    }

    func label(for code: String) -> String {
        if let pick = pick(matching: code) { return pick.name }
        if code.contains(",") { return selectedCodes(in: code).sorted().joined(separator: ", ") }
        return regions[code] ?? code
    }

    /// The selected codes, or nil for the entire network.
    var selectedCodes: Set<String>? {
        selectedRegion.map { selectedCodes(in: $0) }.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Whether [code] (an observer's or packet's region) is inside the selection.
    func includes(_ code: String?) -> Bool {
        guard let selected = selectedCodes else { return true }
        guard let code else { return false }
        return selected.contains(code.uppercased())
    }

    /// Quick picks limited to this host's regions, without any left empty.
    var availablePicks: [RegionQuickPick] {
        let known = Set(regions.keys.map { $0.uppercased() })
        return quickPicks.compactMap { pick in
            let codes = pick.regions.map { $0.uppercased() }.filter(known.contains)
            return codes.isEmpty ? nil : RegionQuickPick(name: pick.name, description: pick.description, regions: codes)
        }
    }

    /// The pick whose codes are exactly the selection, as on the website.
    var activePick: RegionQuickPick? {
        selectedRegion.flatMap(pick(matching:))
    }

    func select(_ pick: RegionQuickPick) {
        selectedRegion = pick.regions.count == 1 ? pick.regions[0] : pick.regions.joined(separator: ",")
    }

    func isSelected(_ pick: RegionQuickPick) -> Bool {
        activePick == pick
    }

    /// Short text for a selection badge: the pick's name, else the code(s).
    var selectionTitle: String? {
        guard let selectedRegion else { return nil }
        return activePick?.name ?? selectedCodes(in: selectedRegion).sorted().joined(separator: ", ")
    }

    private func selectedCodes(in value: String) -> Set<String> {
        Set(value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).uppercased() }.filter { !$0.isEmpty })
    }

    private func pick(matching value: String) -> RegionQuickPick? {
        let codes = selectedCodes(in: value)
        guard codes.count > 1 else { return nil }
        return availablePicks.first { Set($0.regions) == codes }
    }

    func loadRegions() async {
        guard let apiClient else { return }
        let cacheKey = "regions-\(cacheNamespace)"
        if let cached: RegionsMap = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 7 * 24 * 60 * 60
        ) {
            guard apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
            regions = cached
        }
        if let fresh: RegionsMap = try? await APIResponseCache.shared.refresh(for: cacheKey, session: apiClient.sourceSession, loader: {
            try await apiClient.regions()
        }) {
            guard apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
            regions = fresh
        }
    }

    /// Named groups of regions; a failure (or an older server) only means none are offered.
    func loadQuickPicks() async {
        guard let apiClient else { return }
        let cacheKey = "region-quick-picks-\(cacheNamespace)"
        if let cached: [RegionQuickPick] = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 7 * 24 * 60 * 60
        ) {
            guard apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
            quickPicks = cached
        }
        if let fresh: [RegionQuickPick] = try? await APIResponseCache.shared.refresh(for: cacheKey, session: apiClient.sourceSession, loader: {
            try await apiClient.regionQuickPicks()
        }) {
            guard apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
            quickPicks = fresh
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
            guard apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
            iataCoords = cached.coords
        }
        if let fresh: IataCoordsResponse = try? await APIResponseCache.shared.refresh(for: cacheKey, session: apiClient.sourceSession, loader: {
            try await apiClient.iataCoordinates()
        }) {
            guard apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
            iataCoords = fresh.coords
        }
    }
}
