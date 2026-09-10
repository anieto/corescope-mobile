import SwiftUI

@main
struct CoreScopeViewerApp: App {
    @State private var settings: AnalyzerSettings
    @State private var liveFeed: LiveFeedService
    @State private var regionFilter = RegionFilterStore()
    @State private var observerRegionLookup = ObserverRegionLookup()

    init() {
        let settings = AnalyzerSettings()
        _settings = State(initialValue: settings)
        _liveFeed = State(initialValue: LiveFeedService(settings: settings))
    }

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environment(settings)
                .environment(liveFeed)
                .environment(regionFilter)
                .environment(observerRegionLookup)
                .task {
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
