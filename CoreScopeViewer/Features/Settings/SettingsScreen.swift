import SwiftUI

struct SettingsScreen: View {
    let resetID: UUID

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @Environment(AppearanceSettings.self) private var appearanceSettings
    @State private var hostInput = ""
    @State private var sourceSaveStatus: SourceSaveStatus?
    @State private var sourceSaveID = UUID()
    @State private var navigationPath = NavigationPath()

    fileprivate enum SourceSaveStatus: Equatable {
        case connecting
        case connected
        case failed(String)
    }

    var body: some View {
        @Bindable var appearanceSettings = appearanceSettings

        NavigationStack(path: $navigationPath) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    SettingsHeader(isConnected: liveFeed.isConnected)

                    SettingsPanel(title: "Appearance", symbol: "circle.lefthalf.filled") {
                        Picker("Appearance", selection: $appearanceSettings.mode) {
                            ForEach(AppearanceSettings.Mode.allCases) { mode in
                                Text(mode.label).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                    }

                    SettingsPanel(title: "Analyzer Source", symbol: "server.rack") {
                        NavigationLink {
                            AnalyzerSourcePickerScreen(
                                selectedHost: $hostInput,
                                appliesSelectionImmediately: true,
                                onSelection: { host in applyHostChange(host) }
                            )
                        } label: {
                            SettingsNavigationRow(
                                title: "Choose Source",
                                value: settings.host,
                                symbol: "point.3.connected.trianglepath.dotted"
                            )
                        }
                        .buttonStyle(.plain)

                        Text("Every screen reads regions, areas, and map defaults from this CoreScope analyzer.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    SettingsPanel(title: "Connection", symbol: "wave.3.right") {
                        ConnectionStatusRow(
                            isConnected: liveFeed.isConnected,
                            host: settings.host,
                            error: liveFeed.lastError
                        )
                    }

                    NavigationLink {
                        AboutScreen()
                    } label: {
                        SettingsNavigationRow(
                            title: "About & How to Use",
                            value: "NodeScope field guide",
                            symbol: "info.circle.fill"
                        )
                        .padding(16)
                        .instrumentCard()
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)
                .padding(.bottom, 112)
            }
            .background(NodeScopeBackground())
            .toolbar(.hidden, for: .navigationBar)
            .onAppear { hostInput = settings.host }
            .overlay(alignment: .bottom) {
                if let sourceSaveStatus {
                    SettingsSaveBanner(status: sourceSaveStatus)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 104)
                        .accessibilityAddTraits(.isStaticText)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: sourceSaveStatus)
        }
        .onChange(of: resetID) {
            navigationPath = NavigationPath()
        }
    }

    private func applyHostChange(_ newHost: String) {
        let normalizedHost = AnalyzerSettings.normalizedHost(newHost)
        guard !normalizedHost.isEmpty else { return }
        hostInput = normalizedHost
        settings.host = normalizedHost
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

private struct SettingsHeader: View {
    let isConnected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Text("Settings")
                    .font(.largeTitle.bold())
                MeshNodeMotif(color: NodeScopeStyle.signal)
            }
            HStack(spacing: 6) {
                Circle()
                    .fill(isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                    .frame(width: 7, height: 7)
                Text(isConnected ? "Analyzer connected" : "Analyzer offline")
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SettingsPanel<Content: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    @ViewBuilder let content: Content

    init(title: LocalizedStringKey, symbol: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.symbol = symbol
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(title, systemImage: symbol)
                .font(.caption.weight(.bold))
                .foregroundStyle(NodeScopeStyle.signal)
                .textCase(.uppercase)
            content
        }
        .padding(16)
        .instrumentCard()
    }
}

private struct SettingsNavigationRow: View {
    let title: LocalizedStringKey
    let value: String
    let symbol: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(NodeScopeStyle.signal)
                .frame(width: 32, height: 32)
                .background(NodeScopeStyle.signal.opacity(0.11), in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(value)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.bold())
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }
}

private struct ConnectionStatusRow: View {
    let isConnected: Bool
    let host: String
    let error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                HStack(spacing: 7) {
                    Circle()
                        .fill(isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                        .frame(width: 9, height: 9)
                    Text(isConnected ? "Connected" : "Disconnected")
                        .font(.subheadline.weight(.semibold))
                }
                Spacer()
                Text(host)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            if let error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(NodeScopeStyle.activity)
            }
        }
    }
}

private struct SettingsSaveBanner: View {
    let status: SettingsScreen.SourceSaveStatus

    var body: some View {
        HStack(spacing: 8) {
            switch status {
            case .connecting:
                MeshRouteActivityIndicator()
                Text("Source saved — connecting")
            case .connected:
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(NodeScopeStyle.healthy)
                Text("Source connected")
            case .failed(let message):
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(NodeScopeStyle.activity)
                Text(message)
                    .lineLimit(2)
            }
        }
        .font(.subheadline.weight(.semibold))
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: Capsule())
    }
}
