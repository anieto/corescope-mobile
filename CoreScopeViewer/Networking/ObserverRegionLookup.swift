import CoreLocation
import Foundation
import Observation

/// Maps an observer's id or name to its region (iata) and coordinate, built
/// once from /api/observers. Several endpoints have no server-side region
/// filter of their own — the live WebSocket feed only carries an observer
/// id, and channel messages only carry observer names — so filtering those
/// by the shared region selection has to happen client-side against this
/// lookup instead of a per-screen fetch. The coordinate lookup separately
/// lets the live map treat "the observer that heard this packet" as the
/// final point in its hop chain, since a packet's `path.hops` only lists
/// relays *before* the observer, never the observer itself.
@Observable
@MainActor
final class ObserverRegionLookup {
    private(set) var iataById: [String: String] = [:]
    private(set) var iataByName: [String: String] = [:]
    private(set) var coordinateById: [String: CLLocationCoordinate2D] = [:]
    private(set) var coordinateByName: [String: CLLocationCoordinate2D] = [:]
    private(set) var isLoaded = false

    private var apiClient: APIClient?
    private var cacheNamespace = ""

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
        cacheNamespace = settings.host.lowercased()
    }

    /// Call when the analyzer host changes — a lookup built from the old
    /// host's observers is meaningless (and likely wrong) for the new one.
    func reset() {
        iataById = [:]
        iataByName = [:]
        coordinateById = [:]
        coordinateByName = [:]
        isLoaded = false
    }

    func load() async {
        guard let apiClient else { return }
        let cacheKey = "observers-\(cacheNamespace)"
        let cached: ObserversResponse? = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 7 * 24 * 60 * 60
        )
        if let cached {
            apply(cached)
        }
        guard let response: ObserversResponse = try? await APIResponseCache.shared.refresh(for: cacheKey, loader: {
            try await apiClient.get("/api/observers")
        }) else { return }
        apply(response)
    }

    private func apply(_ response: ObserversResponse) {
        var byId: [String: String] = [:]
        var byName: [String: String] = [:]
        var byCoordinateId: [String: CLLocationCoordinate2D] = [:]
        var byCoordinateName: [String: CLLocationCoordinate2D] = [:]
        for observer in response.observers {
            if let iata = observer.iata {
                byId[observer.id] = iata
                byId[observer.id.lowercased()] = iata
                if let name = observer.name {
                    byName[name] = iata
                    byName[name.lowercased()] = iata
                }
            }
            if let lat = observer.lat, let lon = observer.lon {
                let coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                byCoordinateId[observer.id] = coordinate
                byCoordinateId[observer.id.lowercased()] = coordinate
                if let name = observer.name {
                    byCoordinateName[name] = coordinate
                    byCoordinateName[name.lowercased()] = coordinate
                }
            }
        }
        iataById = byId
        iataByName = byName
        coordinateById = byCoordinateId
        coordinateByName = byCoordinateName
        isLoaded = true
    }
}
