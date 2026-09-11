import SwiftUI

struct RootTabView: View {
    @Environment(PacketReplayStore.self) private var packetReplayStore

    private enum Tab: Hashable {
        case map
        case channels
        case observers
        case settings
    }

    @State private var selectedTab: Tab = .map

    var body: some View {
        TabView(selection: $selectedTab) {
            MapScreen(isTabActive: selectedTab == .map)
                .tabItem { Label("Map", systemImage: "map") }
                .tag(Tab.map)

            ChannelsListScreen()
                .tabItem { Label("Channels", systemImage: "number") }
                .tag(Tab.channels)

            ObserversListScreen()
                .tabItem { Label("Observers", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(Tab.observers)

            SettingsScreen()
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .onChange(of: packetReplayStore.requestID) {
            selectedTab = .map
        }
    }
}
