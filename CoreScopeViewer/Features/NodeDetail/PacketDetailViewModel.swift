import Foundation
import Observation

@Observable
@MainActor
final class BeaconPacketDetailViewModel {
    private(set) var detail: PacketDetailResponse?
    private(set) var routes: [RouteOption] = []
    private(set) var isLoading = false
    private(set) var errorMessage: String?
    private var sourceSessionID: UUID?
    private var loadID = UUID()

    func belongsToSource(_ identifier: UUID) -> Bool {
        sourceSessionID == identifier
    }

    func load(hash: String, sourceSessionID: UUID, service: LiveFeedService) async {
        if let previous = self.sourceSessionID, previous != sourceSessionID {
            loadID = UUID()
            detail = nil
            routes = []
            isLoading = false
            errorMessage = String(localized: "The server changed. Return to the packet list.")
            return
        }
        guard !isLoading else { return }
        self.sourceSessionID = sourceSessionID
        let requestID = UUID()
        loadID = requestID
        isLoading = true
        errorMessage = nil
        defer { if loadID == requestID { isLoading = false } }
        do {
            let response = try await service.packetDetail(hash: hash, sourceSessionID: sourceSessionID)
            guard loadID == requestID, !Task.isCancelled else { return }
            detail = response
            routes = RouteOptions.make(from: response.observations.map { observation in
                HeardPath(path: observation.resolvedPath ?? [], hearing: observation.observerName.map { name in
                    RouteHearing(observer: name, region: observation.observerIata,
                                 snr: observation.snr, rssi: observation.rssi)
                })
            })
        } catch {
            guard loadID == requestID, !Task.isCancelled else { return }
            if error is CancellationError {
                errorMessage = String(localized: "The live connection changed. Retry or return to the packet list.")
                return
            }
            errorMessage = error.localizedDescription
        }
    }
}

@Observable
@MainActor
final class PacketDetailViewModel {
    var detail: PacketDetailResponse?
    var isLoading = false
    var errorMessage: String?
    var lastUpdatedAt: Date?

    private var apiClient: (any AnalyzerBackend)?
    private var cacheNamespace = ""
    private var activeLoadID = UUID()

    func configure(settings: AnalyzerSettings) {
        let backend = AnalyzerBackendFactory.make(settings: settings)
        if apiClient?.sourceSession.id != backend.sourceSession.id {
            activeLoadID = UUID()
            detail = nil
            lastUpdatedAt = nil
            errorMessage = nil
            isLoading = false
        }
        apiClient = backend
        cacheNamespace = backend.cacheIdentifier
    }

    func loadPacket(hash: String) async {
        guard let apiClient else { return }
        let loadID = UUID()
        activeLoadID = loadID
        let cacheKey = "packet-detail-\(cacheNamespace)-\(hash.lowercased())"
        if let cached: PacketDetailResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 24 * 60 * 60
        ) {
            let savedAt = await APIResponseCache.shared.savedAt(for: cacheKey)
            guard isCurrent(loadID, backend: apiClient) else { return }
            detail = cached
            lastUpdatedAt = savedAt
            errorMessage = nil
        }
        guard isCurrent(loadID, backend: apiClient) else { return }
        isLoading = detail == nil
        defer {
            if activeLoadID == loadID, apiClient.sourceSession.isCurrent { isLoading = false }
        }

        do {
            let response: PacketDetailResponse = try await APIResponseCache.shared.refresh(for: cacheKey, session: apiClient.sourceSession) {
                try await apiClient.packetDetail(hash: hash)
            }
            guard isCurrent(loadID, backend: apiClient) else { return }
            detail = response
            lastUpdatedAt = .now
            errorMessage = nil
        } catch {
            guard isCurrent(loadID, backend: apiClient) else { return }
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return
            }
            errorMessage = error.localizedDescription
        }
    }

    private func isCurrent(_ loadID: UUID, backend: any AnalyzerBackend) -> Bool {
        activeLoadID == loadID && backend.sourceSession.isCurrent && !Task.isCancelled
    }
}
