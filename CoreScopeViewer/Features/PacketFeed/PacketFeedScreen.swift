import SwiftUI

struct PacketFeedScreen: View {
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @State private var filterType: Int?

    var body: some View {
        NavigationStack {
            List(filteredEvents) { envelope in
                PacketRow(data: envelope.data)
            }
            .listStyle(.plain)
            .adaptiveContentWidth()
            .background(Color(uiColor: .systemBackground).ignoresSafeArea())
            .navigationTitle("Live Packets")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    RegionFilterMenu()
                    Menu {
                        Button("All Types") { filterType = nil }
                        ForEach(PayloadType.allCases, id: \.rawValue) { type in
                            Button(type.name) { filterType = type.rawValue }
                        }
                    } label: {
                        Image(systemName: filterType == nil
                            ? "line.3.horizontal.decrease.circle"
                            : "line.3.horizontal.decrease.circle.fill")
                    }
                }
            }
            .overlay {
                if filteredEvents.isEmpty {
                    ContentUnavailableView(
                        liveFeed.isConnected ? "Waiting for packets…" : "Not connected",
                        systemImage: "dot.radiowaves.left.and.right"
                    )
                }
            }
            .overlay {
                if regionFilter.selectedRegion != nil && !observerRegionLookup.isLoaded {
                    LoadingIndicator(title: "Loading region…")
                }
            }
        }
    }

    private var filteredEvents: [LiveEnvelope] {
        var events = liveFeed.recentEvents.filter { $0.type == "packet" }

        if let filterType {
            events = events.filter { $0.data.decoded?.header?.payloadType == filterType }
        }

        if let selectedRegion = regionFilter.selectedRegion {
            // /api/observers is the only source of an observer's region, so
            // a packet from an observer we haven't resolved yet is excluded
            // rather than guessed at while a region filter is active.
            events = events.filter { observerRegionLookup.iataById[$0.data.observerId ?? ""] == selectedRegion }
        }

        return events
    }
}

private struct PacketRow: View {
    let data: LivePacketData

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(data.decoded?.header?.payloadTypeName ?? "UNKNOWN")
                    .font(.headline)
                Spacer()
                if let snr = data.snr {
                    Text(String(format: "%.1f dB", snr))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            HStack {
                if let observerName = data.observerName {
                    Label(observerName, systemImage: "dot.radiowaves.left.and.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let hash = data.hash {
                    Text(String(hash.prefix(8)))
                        .font(.caption.monospaced())
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .padding(.vertical, 2)
    }
}
