import SwiftUI
import UIKit

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
    private static let shortcutTabs: [Tab] = [.map, .explore, .channels, .observers, .settings]
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.isHingedDevice) private var isHingedDevice
    /// Covers the iPad's first moments while the Map tab settles (see `settleIPadMapLayout`).
    @State private var isSettlingLaunchLayout = true
    @State private var contextAction = TabBarContextActionStore()
    @State private var keyboardCommands = KeyboardCommands()
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
        // ⌘1–⌘5 switch tabs with a hardware keyboard (iPad, and the Mac's "Designed for iPad" app).
        .background {
            ForEach(Array(Self.shortcutTabs.enumerated()), id: \.element) { index, tab in
                Button(tab.title) {
                    guard !KeyboardCommands.isModalPresented else { return }
                    selectedTab = tab
                }
                    .keyboardShortcut(KeyEquivalent(Character("\(index + 1)")), modifiers: .command)
                    .hidden()
            }
            // One owner for ⌘F and ⌘R: every loaded tab claiming the same key left none working.
            Button("Search") { keyboardCommands.perform(.search) }
                .keyboardShortcut("f", modifiers: .command)
                .hidden()
            Button("Refresh") { keyboardCommands.perform(.refresh) }
                .keyboardShortcut("r", modifiers: .command)
                .hidden()
        }
        .environment(keyboardCommands)
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
            // A search field left focused in the tab being left would keep taking typing and
            // ⌘-shortcuts (such as ⌘F) from a hardware keyboard while hidden.
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
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

/// ⌘F and ⌘R for a hardware keyboard (iPad, and the Mac's "Designed for iPad" app). Screens
/// register their search and refresh actions while on screen; the most recently shown one runs,
/// so a screen pushed on top (Node Check over Explore) takes them until it is left.
@Observable
@MainActor
final class KeyboardCommands {
    enum Command { case search, refresh }

    private struct Handler {
        let id: UUID
        let command: Command
        let action: () -> Void
    }

    private var handlers: [Handler] = []

    func register(_ command: Command, id: UUID, action: @escaping () -> Void) {
        handlers.removeAll { $0.id == id }
        handlers.append(Handler(id: id, command: command, action: action))
    }

    func unregister(_ id: UUID) {
        handlers.removeAll { $0.id == id }
    }

    func perform(_ command: Command) {
        guard !Self.isModalPresented else { return }
        handlers.last { $0.command == command }?.action()
    }

    /// True while a sheet, popover or cover is up. Touch can't reach the tabs or the screen
    /// beneath one, so the keyboard shortcuts stand down too rather than act behind it.
    static var isModalPresented: Bool {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow?.rootViewController }
            .contains { $0.presentedViewController != nil }
    }
}

private struct KeyboardCommandModifier: ViewModifier {
    let command: KeyboardCommands.Command
    let action: () -> Void
    @Environment(KeyboardCommands.self) private var commands: KeyboardCommands?
    @State private var id = UUID()

    func body(content: Content) -> some View {
        content
            .onAppear { commands?.register(command, id: id, action: action) }
            .onDisappear { commands?.unregister(id) }
    }
}

extension View {
    /// Runs `action` for ⌘F (`.search`) or ⌘R (`.refresh`) while this view is on screen.
    func keyboardCommand(_ command: KeyboardCommands.Command, action: @escaping () -> Void) -> some View {
        modifier(KeyboardCommandModifier(command: command, action: action))
    }
}
