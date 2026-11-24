import SwiftUI

struct SettingsScreen: View {
    let resetID: UUID

    @Environment(AnalyzerSettings.self) private var settings
    @State private var showsWhatsNew = false
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @Environment(AppearanceSettings.self) private var appearanceSettings
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .localeDefault
    @AppStorage(AppearanceSettings.backgroundTextureDefaultsKey) private var backgroundTextureEnabled = true
    @AppStorage(Glossary.showsExplanationsDefaultsKey) private var showsTermExplanations = true
    @AppStorage(LiveMapPreferences.realisticPropagationKey) private var realisticPropagation = false
    @AppStorage(LiveMapPreferences.groupsNodesKey) private var groupsNodes = true
    @State private var hostInput = ""
    @State private var sourceSaveStatus: SourceSaveStatus?
    /// A source switch waiting because unsaved work couldn't be kept: the chosen host and what's affected.
    @State private var pendingSwitch: (host: String, affected: [String])?
    @State private var sourceSaveID = UUID()
    @State private var navigationPath = NavigationPath()

    private var accountAction: String {
        if (try? AccountSessionKeychain.load(origin: settings.baseURL.absoluteString)) != nil {
            return String(localized: "Manage account")
        }
        return String(localized: "Sign in")
    }

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
                    SettingsHeader()

                    SettingsPanel(title: "Display", symbol: "circle.lefthalf.filled") {
                        SettingsPickerCaption("Appearance")
                        AdaptiveChoicePicker("Appearance", selection: $appearanceSettings.mode,
                                             options: AppearanceSettings.Mode.allCases) { Text($0.label) }

                        SettingsPickerCaption("Units")
                        AdaptiveChoicePicker("Units", selection: $distanceUnit,
                                             options: DistanceUnit.allCases) { Text($0.label) }

                        Divider()

                        Toggle("Background Texture", isOn: $backgroundTextureEnabled)
                        Text("Network lines and nodes behind page content.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)

                        Divider()

                        Toggle("Term Explanations", isOn: $showsTermExplanations)
                        Text("Show \(Image(systemName: "info.circle")) beside terms like SNR and hops.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    SettingsPanel(title: "Live Map", symbol: "map") {
                        Toggle("Group Nearby Nodes", isOn: $groupsNodes)
                        Text("Combine close nodes into numbered bubbles when zoomed out. On a large mesh, turning this off can make the map lag when you pan or tap a node.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)

                        Divider()

                        Toggle("Realistic Propagation", isOn: $realisticPropagation)
                        Text("Collect a packet's reports from different observers for a few seconds, then animate its paths together. Runs slightly behind live; the analyzer sets the delay.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    SettingsPanel(title: "Analyzer", symbol: "server.rack", learnTerm: .analyzer) {
                        NavigationLink {
                            AnalyzerSourcePickerScreen(
                                selectedHost: $hostInput,
                                appliesSelectionImmediately: true,
                                onSelection: { host in applyHostChange(host) }
                            )
                        } label: {
                            SettingsNavigationRow(
                                title: "Choose Source",
                                value: nil,
                                symbol: "point.3.connected.trianglepath.dotted",
                                sourceName: settings.host,
                                isConnected: liveFeed.isConnected,
                                connectionError: liveFeed.lastError
                            )
                        }
                        .buttonStyle(.plain)

                        if settings.beaconBackend == nil {
                            Divider()

                            NavigationLink {
                                CoreScopeAccountScreen().id(settings.sourceSession.id)
                            } label: {
                                SettingsNavigationRow(
                                    title: "CoreScope Account",
                                    value: accountAction,
                                    symbol: "person.crop.circle"
                                )
                            }
                            .buttonStyle(.plain)
                        }

                        Divider()

                        NavigationLink {
                            AnalyzerDiagnosticsScreen()
                        } label: {
                            SettingsNavigationRow(
                                title: "Analyzer Diagnostics",
                                value: String(localized: "Check connection and features"),
                                symbol: "stethoscope"
                            )
                        }
                        .buttonStyle(.plain)

                        Divider()

                        NavigationLink {
                            StorageSettingsScreen()
                        } label: {
                            SettingsNavigationRow(
                                title: "Cache & Storage",
                                value: String(localized: "Review or clear downloaded data"),
                                symbol: "internaldrive.fill"
                            )
                        }
                        .buttonStyle(.plain)
                    }

                    SettingsPanel(title: "Help & Support", symbol: "questionmark.circle") {
                        let releaseHistory = WhatsNew.history
                        if let latest = releaseHistory.first {
                            Button { showsWhatsNew = true } label: {
                                SettingsNavigationRow(
                                    title: "What's New",
                                    value: String(localized: "Highlights in version \(latest.version)"),
                                    symbol: "sparkles"
                                )
                            }
                            .buttonStyle(.plain)
                            .sheet(isPresented: $showsWhatsNew) { WhatsNewSheet(releases: releaseHistory, showsSettingsHint: false) }

                            Divider()
                        }

                        NavigationLink {
                            AboutScreen()
                        } label: {
                            SettingsNavigationRow(
                                title: "About & How to Use",
                                value: String(localized: "NodeScope field guide"),
                                symbol: "info.circle.fill"
                            )
                        }
                        .buttonStyle(.plain)

                        Divider()

                        NavigationLink {
                            GlossaryScreen()
                        } label: {
                            SettingsNavigationRow(
                                title: "Glossary",
                                value: String(localized: "What the terms in NodeScope mean"),
                                symbol: "book.closed.fill"
                            )
                        }
                        .buttonStyle(.plain)

                        Divider()

                        NavigationLink {
                            SupportDevelopmentScreen()
                        } label: {
                            SettingsNavigationRow(
                                title: "Support Development",
                                value: String(localized: "Leave an optional one-time tip"),
                                symbol: "heart.fill"
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)
                // Room between the last card and the floating tab bar.
                .padding(.bottom, 24)
                .adaptiveContentWidth()
            }
            .background(MeshBackground().ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .onAppear { hostInput = settings.host }
            .overlay(alignment: .bottom) {
                if let sourceSaveStatus {
                    SettingsSaveBanner(status: sourceSaveStatus)
                        .padding(.horizontal, 20)
                        .accessibilityAddTraits(.isStaticText)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: sourceSaveStatus)
        }
        .onChange(of: resetID) {
            navigationPath = NavigationPath()
        }
        .alert("Unsaved work couldn't be kept", isPresented: Binding(get: { pendingSwitch != nil }, set: { if !$0 { pendingSwitch = nil } }),
               presenting: pendingSwitch) { pending in
            Button("Retry") { applyHostChange(pending.host) }
            Button("Switch without saving", role: .destructive) { applyHostChange(pending.host, keepingDrafts: false) }
            Button("Cancel", role: .cancel) { pendingSwitch = nil }
        } message: { pending in
            Text("These unfinished studies couldn't be saved on this device: \(pending.affected.joined(separator: ", ")). Switching analyzer now loses their latest changes. Saved plans aren't affected.")
        }
    }

    /// Open tools' unsaved work is written to recovery drafts first; if that fails, the switch waits for
    /// Retry, Cancel or Switch without saving.
    private func applyHostChange(_ newHost: String, keepingDrafts: Bool = true) {
        let normalizedHost = AnalyzerSettings.normalizedHost(newHost)
        guard !normalizedHost.isEmpty else { return }
        if keepingDrafts {
            let failed = StudyDraftRegistry.shared.flushAll()
            guard failed.isEmpty else {
                pendingSwitch = (normalizedHost, failed)
                return
            }
        }
        pendingSwitch = nil
        hostInput = normalizedHost
        let sameHost = AnalyzerSettings.normalizedHost(settings.host).lowercased() == normalizedHost.lowercased()
        settings.host = normalizedHost
        if sameHost { settings.requestSourceResolution() }
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
                    sourceSaveStatus = .failed(String(localized: "Couldn't connect: \(error)"))
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
            sourceSaveStatus = .failed(String(localized: "Couldn't connect to \(settings.host)."))
            try? await Task.sleep(for: .seconds(4))
            guard sourceSaveID == saveID else { return }
            sourceSaveStatus = nil
        }
    }
}

private struct SettingsHeader: View {
    var body: some View {
        HStack(spacing: 10) {
            Text("Settings")
                .font(.monaspace(.largeTitle).bold())
            MeshNodeMotif(color: NodeScopeStyle.signal)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SettingsPanel<Content: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    let learnTerm: GlossaryTerm?
    @ViewBuilder let content: Content

    init(title: LocalizedStringKey, symbol: String, learnTerm: GlossaryTerm? = nil,
         @ViewBuilder content: () -> Content) {
        self.title = title
        self.symbol = symbol
        self.learnTerm = learnTerm
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Label(title, systemImage: symbol)
                    .font(.monaspace(.caption).weight(.bold))
                    .foregroundStyle(NodeScopeStyle.signal)
                    .textCase(.uppercase)
                if let learnTerm { LearnButton(term: learnTerm) }
            }
            content
        }
        .padding(16)
        .instrumentCard()
    }
}

private struct SettingsNavigationRow: View {
    let title: LocalizedStringKey
    let value: String?
    let symbol: String
    var sourceName: String? = nil
    var isConnected: Bool? = nil
    var connectionError: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(NodeScopeStyle.signal)
                .frame(width: 32, height: 32)
                .background(NodeScopeStyle.signal.opacity(0.11), in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                if let sourceName {
                    Text(sourceName)
                        .font(.monaspace(.caption))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                if let value {
                    Text(value)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                if let isConnected {
                    ConnectionStatusRow(isConnected: isConnected, error: connectionError)
                        .padding(.top, 2)
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.bold())
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }
}

/// A small title above a segmented picker, whose own label is hidden.
private struct SettingsPickerCaption: View {
    let title: LocalizedStringKey

    init(_ title: LocalizedStringKey) {
        self.title = title
    }

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.secondary)
            .accessibilityHidden(true)
    }
}

private struct ConnectionStatusRow: View {
    let isConnected: Bool
    let error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            connectionStatus
            if let error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(NodeScopeStyle.activity)
            }
        }
    }

    private var connectionStatus: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                .frame(width: 7, height: 7)
                .accessibilityHidden(true)
            Text(isConnected ? "Connected" : "Disconnected")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
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
