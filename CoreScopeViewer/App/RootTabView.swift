import SwiftUI

struct RootTabView: View {
    @Environment(PacketReplayStore.self) private var packetReplayStore
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter

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
        TabView(selection: $selectedTab) {
            MapScreen(isTabActive: selectedTab == .map, resetID: resetIDs[.map] ?? UUID())
                .toolbar(.hidden, for: .tabBar)
                .tabItem { Label("Map", systemImage: "map") }
                .tag(Tab.map)

            ExploreScreen(
                resetID: resetIDs[.explore] ?? UUID(),
                openMap: { selectedTab = .map },
                openChannels: { selectedTab = .channels },
                openObservers: { selectedTab = .observers }
            )
                .toolbar(.hidden, for: .tabBar)
                .tabItem { Label("Explore", systemImage: "safari") }
                .tag(Tab.explore)

            ChannelsListScreen(resetID: resetIDs[.channels] ?? UUID())
                .toolbar(.hidden, for: .tabBar)
                .tabItem { Label("Channels", systemImage: "number") }
                .tag(Tab.channels)

            ObserversListScreen(resetID: resetIDs[.observers] ?? UUID())
                .toolbar(.hidden, for: .tabBar)
                .tabItem { Label("Observers", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(Tab.observers)

            SettingsScreen(resetID: resetIDs[.settings] ?? UUID())
                .toolbar(.hidden, for: .tabBar)
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .toolbar(.hidden, for: .tabBar)
        .overlay(alignment: .bottom) {
            FloatingTabDock(selectedTab: selectedTab) { tab in
                if selectedTab == tab {
                    resetIDs[tab] = UUID()
                } else {
                    selectedTab = tab
                }
            }
                .frame(maxWidth: 680)
                .padding(.horizontal, 18)
                .padding(.bottom, 8)
        }
        .onChange(of: packetReplayStore.requestID) {
            selectedTab = .map
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
        .task(id: "\(settings.host)|\(regionFilter.selectedRegion ?? "")") {
            await ChannelsViewModel.preload(settings: settings, region: regionFilter.selectedRegion)
        }
    }
}

private struct FloatingTabDock: View {
    let selectedTab: RootTabView.Tab
    let select: (RootTabView.Tab) -> Void

    var body: some View {
        HStack(spacing: 4) {
            ForEach(RootTabView.Tab.allCases, id: \.self) { tab in
                Button {
                    select(tab)
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: tab.symbol)
                            .font(.system(size: 16, weight: .semibold))
                        Text(tab.title)
                            .font(.caption2.weight(.semibold))
                    }
                    .foregroundStyle(selectedTab == tab ? Color.white : Color.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(
                        selectedTab == tab ? NodeScopeStyle.signal : Color.clear,
                        in: Capsule()
                    )
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selectedTab == tab ? .isSelected : [])
            }
        }
        .padding(5)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(Color.white.opacity(0.16), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.2), radius: 18, y: 8)
    }
}
