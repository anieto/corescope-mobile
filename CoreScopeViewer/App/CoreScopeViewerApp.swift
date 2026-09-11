import SwiftUI

@main
struct NodeScopeApp: App {
    @State private var settings: AnalyzerSettings
    @State private var liveFeed: LiveFeedService
    @State private var regionFilter = RegionFilterStore()
    @State private var observerRegionLookup = ObserverRegionLookup()
    @State private var packetReplayStore = PacketReplayStore()
    @State private var appearanceSettings = AppearanceSettings()
    @State private var analyzerSourceRegistry = AnalyzerSourceRegistry()
    @State private var channelMonitorStore = ChannelMonitorStore()
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false

    init() {
        let settings = AnalyzerSettings()
        _settings = State(initialValue: settings)
        _liveFeed = State(initialValue: LiveFeedService(settings: settings))
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if hasCompletedOnboarding {
                    RootTabView()
                } else {
                    OnboardingScreen {
                        hasCompletedOnboarding = true
                    }
                }
            }
            .environment(settings)
            .environment(liveFeed)
            .environment(regionFilter)
            .environment(observerRegionLookup)
            .environment(packetReplayStore)
            .environment(appearanceSettings)
            .environment(analyzerSourceRegistry)
            .environment(channelMonitorStore)
            .preferredColorScheme(appearanceSettings.mode.colorScheme)
            .task {
                await analyzerSourceRegistry.refresh()
            }
            .task(id: hasCompletedOnboarding) {
                guard hasCompletedOnboarding else { return }
                liveFeed.connect()
                regionFilter.configure(settings: settings)
                observerRegionLookup.configure(settings: settings)
                async let regions: Void = regionFilter.loadRegions()
                async let coords: Void = regionFilter.loadIataCoords()
                async let observerRegions: Void = observerRegionLookup.load()
                _ = await (regions, coords, observerRegions)
            }
        }
    }
}
