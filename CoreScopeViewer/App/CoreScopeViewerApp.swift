import SafariServices
import SwiftUI
import WidgetKit

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
    @State private var widgetWatches: WidgetWatches?
    @State private var pendingWidgetNode: String?
    @State private var appNavigationStore = AppNavigationStore()
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @AppStorage(AppearanceSettings.backgroundTextureDefaultsKey) private var backgroundTextureEnabled = true
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
                if url.scheme == "nodescope", url.host == MyNodesWidgetCatalog.nodeCheckHost {
                    appNavigationStore.open(.nodeCheck)
                    return
                }
                if url.scheme == "nodescope", url.host == "widget-node" {
                    pendingWidgetNode = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                        .queryItems?.first(where: { $0.name == "id" })?.value ?? ""
                    openWidgetSelectionIfReady()
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
            .onChange(of: widgetChoices, initial: true) {
                publishWidgetCatalog()
                openWidgetSelectionIfReady()
            }
            .onChange(of: backgroundTextureEnabled) { publishWidgetCatalog() }
            .onChange(of: settings.sourceIsReady) { openWidgetSelectionIfReady() }
            .task(id: widgetWatchRequest) {
                guard scenePhase == .active, let request = widgetWatchRequest else { return }
                await refreshWidgetWatches(request)
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

    private func publishWidgetCatalog() {
        if (try? MyNodesWidgetCatalog.publish(widgetChoices, textureEnabled: backgroundTextureEnabled)) == true {
            WidgetCenter.shared.reloadTimelines(ofKind: MyNodesWidgetCatalog.widgetKind)
            WidgetCenter.shared.reloadTimelines(ofKind: MyNodesWidgetCatalog.listWidgetKind)
        }
    }

    private struct WidgetWatchRequest: Equatable {
        let sourceSessionID: UUID
        let accountID: Int64
        let revision: Int
        let active: Bool
    }

    private struct WidgetWatches {
        let request: WidgetWatchRequest
        let nodes: [CoreScopeWatchedNode]
    }

    private var widgetWatchRequest: WidgetWatchRequest? {
        let store = CoreScopeAccountSelectionStore.shared
        guard hasCompletedOnboarding, settings.sourceIsReady, settings.beaconBackend == nil,
              let accountID = store.activeAccounts.first(where: {
                  AnalyzerSettings.normalizedHost($0.key) == AnalyzerSettings.normalizedHost(settings.host)
              })?.value else { return nil }
        return WidgetWatchRequest(sourceSessionID: settings.sourceSession.id, accountID: accountID,
                                  revision: store.widgetWatchRevision, active: scenePhase == .active)
    }

    private var widgetChoices: [MyNodesWidgetChoice] {
        guard hasCompletedOnboarding, settings.sourceIsReady, settings.beaconBackend == nil else { return [] }
        let source = AnalyzerSettings.normalizedHost(settings.host)
        var nodes: [String: MyNodesWidgetChoice] = [:]
        for favorite in favoritesStore.deviceItems where favorite.kind == .node && favorite.source == source {
            let key = favorite.entityID.lowercased()
            nodes[key] = MyNodesWidgetChoice(source: source, key: key, name: favorite.title, accountID: nil)
        }
        if let current = widgetWatchRequest, let watches = widgetWatches,
           watches.request.sourceSessionID == current.sourceSessionID,
           watches.request.accountID == current.accountID,
           watches.request.revision == current.revision {
            for node in watches.nodes {
                let key = node.pubkey.lowercased()
                if nodes[key] == nil {
                    let name = node.name.isEmpty ? String(key.prefix(12)) : node.name
                    nodes[key] = MyNodesWidgetChoice(source: source, key: key, name: name, accountID: current.accountID)
                }
            }
        }
        return nodes.values.filter { $0.endpoint != nil }.sorted { $0.id < $1.id }
    }

    private func refreshWidgetWatches(_ request: WidgetWatchRequest) async {
        let session = settings.sourceSession
        var nodes: [CoreScopeWatchedNode] = []
        do {
            let origin = settings.baseURL
            if let cookie = try AccountSessionKeychain.load(origin: origin.absoluteString) {
                let client = try CoreScopeAccountClient(origin: origin, cookie: cookie, lifetime: session,
                    save: { token in
                        // A refresh must not restore a session that was replaced while it ran.
                        if try AccountSessionKeychain.load(origin: origin.absoluteString) == cookie {
                            try AccountSessionKeychain.save(token, origin: origin.absoluteString)
                        }
                    })
                if try await client.me().id == request.accountID {
                    nodes = try await client.notifications().watches
                }
            }
        } catch CoreScopeAccountError.signedOut {
            nodes = []
        } catch {
            // Keep a previously verified list on a transient error for this same identity.
            if let previous = widgetWatches, previous.request == request {
                nodes = previous.nodes
            }
        }
        guard !Task.isCancelled, session.isCurrent, widgetWatchRequest == request else { return }
        widgetWatches = WidgetWatches(request: request, nodes: nodes)
        openWidgetSelectionIfReady()
    }

    private func openWidgetSelectionIfReady() {
        guard settings.sourceIsReady, let id = pendingWidgetNode else { return }
        if let choice = widgetChoices.first(where: { $0.id == id }) {
            pendingWidgetNode = nil
            appNavigationStore.openWatchedNode(publicKey: choice.key, sourceSessionID: settings.sourceSession.id)
        } else if id.isEmpty || widgetWatchRequest == nil || widgetWatches?.request == widgetWatchRequest {
            pendingWidgetNode = nil
            appNavigationStore.openMyNodes()
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
