import SafariServices
import SwiftUI

@main
struct NodeScopeApp: App {
    #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
    @UIApplicationDelegateAdaptor(StagingPushDelegate.self) private var pushDelegate
    #endif
    @State private var settings: AnalyzerSettings
    @State private var liveFeed: LiveFeedService
    @State private var regionFilter = RegionFilterStore()
    @State private var observerRegionLookup = ObserverRegionLookup()
    @State private var packetReplayStore = PacketReplayStore()
    @State private var liveTrafficPause = LiveTrafficPause()
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
        AppFont.styleNavigationTitles()
        let settings = AnalyzerSettings()
        _settings = State(initialValue: settings)
        _liveFeed = State(initialValue: LiveFeedService(settings: settings))
    }

    var body: some Scene {
        WindowGroup {
            #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
            let pushNotice = StagingPush.shared.notice
            #endif
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
                        LoadingIndicator(title: String(localized: "Checking analyzer source…"))
                    }
                } else {
                    OnboardingScreen {
                        WhatsNew.markCurrentSeen()
                        hasCompletedOnboarding = true
                    }
                }
            }
            .resolvesDuoHinge()
            .modifier(InAppWebLinks())
            .background {
                NodeScopeBackground()
            }
            .environment(settings)
            .environment(liveFeed)
            .environment(regionFilter)
            .environment(observerRegionLookup)
            .environment(packetReplayStore)
            .environment(liveTrafficPause)
            .environment(appearanceSettings)
            .environment(analyzerSourceRegistry)
            .environment(channelMonitorStore)
            .environment(favoritesStore)
            .environment(recentItemsStore)
            .environment(searchHistoryStore)
            .environment(appNavigationStore)
            .preferredColorScheme(appearanceSettings.mode.colorScheme)
            .onOpenURL { url in
                if url.isFileURL {
                    // Not opened in place: the system's copy in this app's Inbox is removed once read.
                    PackageImportPresenter.present(PackageImportRequest(url: url, removesFile: url.pathComponents.contains { $0.hasSuffix("Inbox") }))
                    return
                }
                guard let deepLink = NodeScopeDeepLink(url: url) else { return }
                appNavigationStore.open(deepLink)
            }
            #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
            .task(id: scenePhase) {
                if scenePhase == .active { await StagingPush.shared.refresh() }
            }
            .alert("Watched-node alert", isPresented: Binding(
                get: { StagingPush.shared.notice != nil },
                set: { if !$0, StagingPush.shared.notice == pushNotice { StagingPush.shared.notice = nil } }
            ), presenting: pushNotice) { notice in
                if case .switchSource(let origin, let route) = notice {
                    Button("Switch and open") {
                        StagingPush.shared.switchSource(for: route, to: origin, settings: settings)
                    }
                    Button("Cancel", role: .cancel) {}
                } else {
                    Button("OK", role: .cancel) {}
                }
            } message: { notice in
                switch notice {
                case .switchSource(let origin, _):
                    Text("This alert is from \(AnalyzerSettings.normalizedHost(origin.absoluteString)).")
                case .unavailable:
                    Text("This alert can no longer be opened.")
                case .unsavedDrafts:
                    Text("Save your open studies before switching analyzers.")
                }
            }
            .task(id: "\(hasCompletedOnboarding)|\(scenePhase)|\(settings.sourceIsReady)|\(settings.backendSelectionError ?? "")|\(settings.sourceSession.id)|\(StagingPush.shared.routeID)") {
                for attempt in 0..<3 {
                    guard !Task.isCancelled, await StagingPush.shared.consume(
                        for: settings, navigation: appNavigationStore, onboarded: hasCompletedOnboarding,
                        active: scenePhase == .active
                    ) else { return }
                    if attempt < 2 { try? await Task.sleep(for: .seconds(1)) }
                }
            }
            #endif
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


// Present from this window's frontmost controller so links also work inside sheets.
private struct InAppWebLinks: ViewModifier {
    @State private var anchor = WebLinkWindow()

    func body(content: Content) -> some View {
        content
            .background { WindowReader(anchor: anchor).allowsHitTesting(false) }
            .environment(\.openURL, OpenURLAction { url in
                anchor.presentBrowser(for: url) ? .handled : .systemAction
            })
    }
}

@MainActor
private final class WebLinkWindow {
    weak var window: UIWindow?

    func presentBrowser(for url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http",
              url.host != nil, var presenter = window?.rootViewController else { return false }
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        guard presenter.viewIfLoaded?.window != nil, !presenter.isBeingDismissed else { return false }
        presenter.present(SFSafariViewController(url: url), animated: true)
        return true
    }
}

private struct WindowReader: UIViewRepresentable {
    let anchor: WebLinkWindow

    func makeUIView(context: Context) -> WindowView {
        let view = WindowView()
        view.anchor = anchor
        return view
    }

    func updateUIView(_ view: WindowView, context: Context) {}

    final class WindowView: UIView {
        weak var anchor: WebLinkWindow?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            anchor?.window = window
        }
    }
}
