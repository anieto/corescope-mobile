import SwiftUI

struct SettingsScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @State private var hostInput: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("analyzer.meshtexas.org", text: $hostInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                } header: {
                    Text("Analyzer Host")
                } footer: {
                    Text(
                        "The CoreScope server this app connects to. Point it at any " +
                        "community's analyzer — every screen reads its regions, areas, " +
                        "and map defaults from that host, nothing is hardcoded to Texas."
                    )
                }

                Section {
                    Button("Save & Reconnect") {
                        applyHostChange(hostInput)
                    }
                    Button("Reset to MeshTexas Default", role: .destructive) {
                        hostInput = AnalyzerSettings.defaultHost
                        applyHostChange(hostInput)
                    }
                }

                Section("Live Connection") {
                    LabeledContent("Status", value: liveFeed.isConnected ? "Connected" : "Disconnected")
                    if let error = liveFeed.lastError {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { hostInput = settings.host }
        }
    }

    /// Changing hosts invalidates the region list and any selection made
    /// against the old host, so reload it fresh rather than leaving a
    /// stale region code silently filtering the new host's data.
    private func applyHostChange(_ newHost: String) {
        settings.host = newHost
        liveFeed.connect()
        regionFilter.reset()
        regionFilter.configure(settings: settings)
        observerRegionLookup.reset()
        observerRegionLookup.configure(settings: settings)
        Task {
            async let regions: Void = regionFilter.loadRegions()
            async let coords: Void = regionFilter.loadIataCoords()
            async let observerRegions: Void = observerRegionLookup.load()
            _ = await (regions, coords, observerRegions)
        }
    }
}
