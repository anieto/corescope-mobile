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
    @State private var favoritesStore = FavoritesStore()
    @State private var recentItemsStore = RecentItemsStore()
    @State private var searchHistoryStore = SearchHistoryStore()
    @State private var appNavigationStore = AppNavigationStore()
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let settings = AnalyzerSettings()
        _settings = State(initialValue: settings)
        _liveFeed = State(initialValue: LiveFeedService(settings: settings))
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if hasCompletedOnboarding {
                    if settings.sourceIsReady {
                        RootTabView()
                    } else if let error = settings.backendSelectionError {
                        VStack(spacing: 16) {
                            ContentUnavailableView("Couldn't identify this server", systemImage: "network.slash", description: Text(error))
                            Button("Retry") { settings.requestSourceResolution() }
                            Button("Choose Another Source") { hasCompletedOnboarding = false }
                        }
                        .padding()
                    } else {
                        LoadingIndicator(title: "Checking analyzer source…")
                    }
                } else {
                    OnboardingScreen {
                        hasCompletedOnboarding = true
                    }
                }
            }
            .resolvesDuoHinge()
            .background {
                NodeScopeBackground()
            }
            .environment(settings)
            .environment(liveFeed)
            .environment(regionFilter)
            .environment(observerRegionLookup)
            .environment(packetReplayStore)
            .environment(appearanceSettings)
            .environment(analyzerSourceRegistry)
            .environment(channelMonitorStore)
            .environment(favoritesStore)
            .environment(recentItemsStore)
            .environment(searchHistoryStore)
            .environment(appNavigationStore)
            .preferredColorScheme(appearanceSettings.mode.colorScheme)
            .onOpenURL { url in
                guard let deepLink = NodeScopeDeepLink(url: url) else { return }
                appNavigationStore.open(deepLink)
            }
            .task {
                await analyzerSourceRegistry.refresh()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { liveFeed.resumeAfterForeground() }
            }
            .task(id: "\(hasCompletedOnboarding)|\(settings.sourceResolutionID)") {
                guard hasCompletedOnboarding else { return }
                liveFeed.disconnect()
                let host = AnalyzerSettings.normalizedHost(settings.host).lowercased()
                let listedSource = analyzerSourceRegistry.sources.first {
                    AnalyzerSettings.normalizedHost($0.host).lowercased() == host
                }
                let requiresBeacon = listedSource?.backendType == .beacon
                let knownCoreScope = !requiresBeacon && (host == AnalyzerSettings.defaultHost || listedSource?.backendType == .coreScope)
                await settings.resolveSource(knownCoreScope: knownCoreScope, requiresBeacon: requiresBeacon)
            }
            .task(id: "\(hasCompletedOnboarding)|\(settings.sourceIsReady)|\(settings.sourceSession.id)") {
                guard hasCompletedOnboarding, settings.sourceIsReady else { return }
                liveFeed.connect()
                regionFilter.configure(settings: settings)
                observerRegionLookup.configure(settings: settings)
                async let regions: Void = regionFilter.loadRegions()
                async let picks: Void = regionFilter.loadQuickPicks()
                async let coords: Void = regionFilter.loadIataCoords()
                async let observerRegions: Void = observerRegionLookup.load()
                _ = await (regions, picks, coords, observerRegions)
            }
        }
    }
}
