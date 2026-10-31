import SwiftUI

struct RootTabView: View {
    @Environment(PacketReplayStore.self) private var packetReplayStore
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(AppNavigationStore.self) private var appNavigationStore

    fileprivate enum Tab: String, Hashable, CaseIterable {
        case map
        case explore
        case channels
        case observers
        case settings
        case contextAction

        var title: LocalizedStringKey {
            switch self {
            case .map: "Map"
            case .explore: "Explore"
            case .channels: "Channels"
            case .observers: "Observers"
            case .settings: "Settings"
            case .contextAction: "Action"
            }
        }

        var symbol: String {
            switch self {
            case .map: "map"
            case .explore: "safari"
            case .channels: "bubble.left.and.bubble.right"
            case .observers: "antenna.radiowaves.left.and.right"
            case .settings: "slider.horizontal.3"
            case .contextAction: "ellipsis"
            }
        }
    }

    @State private var selectedTab: Tab = .map
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.isHingedDevice) private var isHingedDevice
    /// Covers the iPad's first moments while the Map tab settles (see `settleIPadMapLayout`).
    @State private var isSettlingLaunchLayout = true
    @State private var contextAction = TabBarContextActionStore()
    @AppStorage("lastSelectedTab") private var lastSelectedTabRawValue = Tab.map.rawValue
    @State private var resetIDs: [Tab: UUID] = Dictionary(
        uniqueKeysWithValues: Tab.allCases.map { ($0, UUID()) }
    )

    var body: some View {
        TabView(selection: tabSelection) {
            SwiftUI.Tab("Map", systemImage: "map", value: Tab.map) {
                MapPacketWorkspaceScreen(
                    isTabActive: selectedTab == .map,
                    resetID: resetIDs[.map] ?? UUID()
                )
            }

            SwiftUI.Tab("Explore", systemImage: "safari", value: Tab.explore) {
                ExploreScreen(
                    resetID: resetIDs[.explore] ?? UUID(),
                    isTabActive: selectedTab == .explore,
                    openMap: { selectedTab = .map },
                    openChannels: { selectedTab = .channels },
                    openObservers: { selectedTab = .observers }
                )
            }

            SwiftUI.Tab("Channels", systemImage: "bubble.left.and.bubble.right", value: Tab.channels) {
                ChannelsListScreen(resetID: resetIDs[.channels] ?? UUID())
            }

            SwiftUI.Tab("Observers", systemImage: "antenna.radiowaves.left.and.right", value: Tab.observers) {
                ObserversListScreen(resetID: resetIDs[.observers] ?? UUID())
            }

            SwiftUI.Tab("Settings", systemImage: "slider.horizontal.3", value: Tab.settings) {
                SettingsScreen(resetID: resetIDs[.settings] ?? UUID())
                    .id(resetIDs[.settings])
            }

            if let kind = contextAction.kind {
                SwiftUI.Tab(value: Tab.contextAction) {
                    Color.clear
                } label: {
                    Label(kind.title, systemImage: kind.systemImage)
                }
            }
        }
        .environment(contextAction)
        .background {
            NodeScopeBackground()
        }
        .tabViewStyle(.tabBarOnly)
        // The app's own background, already on screen while it opens, so the cover reads as the
        // app still opening.
        .overlay {
            if isSettlingLaunchLayout && settlesIPadMapLayout {
                NodeScopeBackground()
                    .ignoresSafeArea()
                    .transition(.opacity)
            }
        }
        .onChange(of: packetReplayStore.requestID) {
            selectedTab = .map
        }
        .onChange(of: appNavigationStore.requestID) {
            switch appNavigationStore.destination {
            case .mapNode, .activeNodes:
                selectedTab = .map
            case .observer, .activeObservers:
                selectedTab = .observers
            case .node, .channel, .packet:
                selectedTab = .explore
            case nil:
                break
            }
        }
        .onAppear {
            if lastSelectedTabRawValue == "favorites" {
                selectedTab = .explore
            } else {
                selectedTab = Tab(rawValue: lastSelectedTabRawValue) ?? .map
            }
            if selectedTab == .map && settlesIPadMapLayout {
                Task { await settleIPadMapLayout() }
            } else {
                isSettlingLaunchLayout = false
            }
        }
        .onChange(of: selectedTab) {
            lastSelectedTabRawValue = selectedTab.rawValue
        }
        .onChange(of: settings.sourceSession.id) {
            resetAllTabs()
        }
        .task(id: "\(settings.sourceSession.id)|\(regionFilter.selectedRegion ?? "")") {
            await ChannelsViewModel.preload(settings: settings, region: regionFilter.selectedRegion)
        }
    }

    /// A regular iPad, where the Map tab is a split view; the Duo and iPhone lay it out otherwise.
    private var settlesIPadMapLayout: Bool {
        horizontalSizeClass == .regular && !isHingedDevice
    }

    /// The first time the Map tab shows on iPad, the top tab bar is not merged into its split
    /// view's bar, which then sits under the tab bar and pushes both panes a bar's height down
    /// until the tab is visited again. A frame on another tab does that visit, under a cover of the
    /// app's background so it isn't seen.
    private func settleIPadMapLayout() async {
        try? await Task.sleep(for: .milliseconds(50))
        selectedTab = .explore
        try? await Task.sleep(for: .milliseconds(16))
        selectedTab = .map
        try? await Task.sleep(for: .milliseconds(100))
        withAnimation(.easeOut(duration: 0.2)) { isSettlingLaunchLayout = false }
    }

    private var tabSelection: Binding<Tab> {
        Binding(
            get: { selectedTab },
            set: { tab in
                if tab == .contextAction {
                    contextAction.perform()
                } else if selectedTab == tab {
                    resetIDs[tab] = UUID()
                } else {
                    selectedTab = tab
                }
            }
        )
    }

    private func resetAllTabs() {
        // Keep Settings alive while it reports the connection result for the
        // newly selected analyzer. The picker dismisses itself, while every
        // data-bearing tab still receives a fresh navigation/data reset.
        for tab in Tab.allCases where tab != .settings && tab != .contextAction {
            resetIDs[tab] = UUID()
        }
    }
}
