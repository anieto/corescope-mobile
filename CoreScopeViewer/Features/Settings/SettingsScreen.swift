import SwiftUI

struct SettingsScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @Environment(AppearanceSettings.self) private var appearanceSettings
    @State private var hostInput: String = ""
    @State private var sourceSaveStatus: SourceSaveStatus?
    @State private var sourceSaveID = UUID()

    private enum SourceSaveStatus: Equatable {
        case connecting
        case connected
        case failed(String)
    }

    var body: some View {
        @Bindable var appearanceSettings = appearanceSettings

        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        AboutScreen()
                    } label: {
                        Label("About & How to Use", systemImage: "info.circle")
                    }
                }

                Section("Appearance") {
                    Picker("Appearance", selection: $appearanceSettings.mode) {
                        ForEach(AppearanceSettings.Mode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }

                Section {
                    TextField("analyzer.meshtexas.org", text: $hostInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)

                    Button("Save & Reconnect") {
                        applyHostChange(hostInput)
                    }
                    .disabled(hostInput.trimmingCharacters(in: .whitespaces).isEmpty)

                    Button("Reset to MeshTexas Default", role: .destructive) {
                        hostInput = AnalyzerSettings.defaultHost
                        applyHostChange(hostInput)
                    }
                } header: {
                    Text("Analyzer Source")
                } footer: {
                    Text(
                        "The CoreScope server this app connects to. Point it at any " +
                        "community's analyzer — every screen reads its regions, areas, " +
                        "and map defaults from that host, nothing is hardcoded to Texas."
                    )
                }

                Section("Connection") {
                    LabeledContent("Status") {
                        // A plain HStack instead of `Label` here — a `Label`
                        // nested inside `LabeledContent`'s trailing content
                        // was inflating the whole row to several hundred
                        // points tall (some SF Symbol/List-row sizing
                        // interaction), even though the symbol itself
                        // renders at a normal size everywhere else in the
                        // app. An explicitly-sized Image sidesteps it.
                        HStack(spacing: 4) {
                            Image(systemName: liveFeed.isConnected ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .font(.body)
                            Text(liveFeed.isConnected ? "Connected" : "Disconnected")
                        }
                        .foregroundStyle(liveFeed.isConnected ? .green : .red)
                    }
                    LabeledContent("Server", value: settings.host)
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
            .overlay(alignment: .bottom) {
                if let sourceSaveStatus {
                    sourceSaveBanner(for: sourceSaveStatus)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(.thinMaterial, in: Capsule())
                        .padding(.bottom, 18)
                        .accessibilityAddTraits(.isStaticText)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: sourceSaveStatus)
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
        showSourceConnectionStatus()
        Task {
            async let regions: Void = regionFilter.loadRegions()
            async let coords: Void = regionFilter.loadIataCoords()
            async let observerRegions: Void = observerRegionLookup.load()
            _ = await (regions, coords, observerRegions)
        }
    }

    @ViewBuilder
    private func sourceSaveBanner(for status: SourceSaveStatus) -> some View {
        switch status {
        case .connecting:
            HStack(spacing: 8) {
                ProgressView()
                Text("Source saved — connecting")
            }
            .foregroundStyle(.primary)
        case .connected:
            Label("Source connected", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .failed(let message):
            Label(message, systemImage: "xmark.circle.fill")
                .foregroundStyle(.red)
        }
    }

    private func showSourceConnectionStatus() {
        let saveID = UUID()
        sourceSaveID = saveID
        sourceSaveStatus = .connecting
        Task {
            for _ in 0..<80 {
                try? await Task.sleep(for: .milliseconds(100))
                guard !Task.isCancelled, sourceSaveID == saveID else { return }

                if let error = liveFeed.lastError {
                    sourceSaveStatus = .failed("Couldn't connect: \(error)")
                    try? await Task.sleep(for: .seconds(4))
                    guard sourceSaveID == saveID else { return }
                    sourceSaveStatus = nil
                    return
                }

                if liveFeed.isConnected {
                    sourceSaveStatus = .connected
                    try? await Task.sleep(for: .seconds(2))
                    guard sourceSaveID == saveID else { return }
                    sourceSaveStatus = nil
                    return
                }
            }

            guard sourceSaveID == saveID else { return }
            sourceSaveStatus = .failed("Couldn't connect to \(settings.host).")
            try? await Task.sleep(for: .seconds(4))
            guard sourceSaveID == saveID else { return }
            sourceSaveStatus = nil
        }
    }
}
