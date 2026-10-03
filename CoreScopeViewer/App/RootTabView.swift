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

        var title: LocalizedStringKey {
            switch self {
            case .map: "Map"
            case .explore: "Explore"
            case .channels: "Channels"
            case .observers: "Observers"
            case .settings: "Settings"
            }
        }

        var symbol: String {
            switch self {
            case .map: "map"
            case .explore: "safari"
            case .channels: "bubble.left.and.bubble.right"
            case .observers: "antenna.radiowaves.left.and.right"
            case .settings: "slider.horizontal.3"
            }
        }
    }

    @State private var selectedTab: Tab = .map
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
        }
        .background {
            NodeScopeBackground()
        }
        .tabViewStyle(.tabBarOnly)
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
        }
        .onChange(of: selectedTab) {
            lastSelectedTabRawValue = selectedTab.rawValue
        }
        .onChange(of: settings.host) {
            resetAllTabs()
        }
        .task(id: "\(settings.host)|\(regionFilter.selectedRegion ?? "")") {
            await ChannelsViewModel.preload(settings: settings, region: regionFilter.selectedRegion)
        }
    }

    private var tabSelection: Binding<Tab> {
        Binding(
            get: { selectedTab },
            set: { tab in
                if selectedTab == tab {
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
        for tab in Tab.allCases where tab != .settings {
            resetIDs[tab] = UUID()
        }
    }
}

