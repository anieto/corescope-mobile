import MapKit
import SwiftUI
import UIKit

struct MapScreen: View {
    let isTabActive: Bool
    let resetID: UUID
    var showLivePackets: (() -> Void)? = nil
    var togglePacketSidebar: (() -> Void)? = nil
    var pinsPacketSidebarToggle = false
    var centersBottomControlsInLeadingPane = false
    var bottomObscuredFraction: Double = 0
    /// Width covered on the right by a panel drawn over the map (the Duo's packet sidebar). The
    /// map itself is not resized, so its nodes stay put; its controls move clear of the panel,
    /// and the camera pans alongside the panel's slide so the visible part stays centred.
    var trailingObscuredWidth: CGFloat = 0

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @Environment(PacketReplayStore.self) private var packetReplayStore
    @Environment(AppNavigationStore.self) private var appNavigationStore
    @Environment(AnalyzerSourceRegistry.self) private var analyzerSourceRegistry
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel = MapViewModel()
    @State private var liveFeedConsumer = UUID()
    @State private var isVisible = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var visibleRegion: MKCoordinateRegion?
    @State private var selectedNode: MeshNode?
    @State private var displayedNodes: [MeshNode] = []
    @State private var displayCoordinatesByNodeID: [String: CLLocationCoordinate2D] = [:]
    @State private var fanOriginsByNodeID: [String: CLLocationCoordinate2D] = [:]
    /// Live traffic to or from a node grouped into a bubble is drawn to the bubble. Drawn at the
    /// hidden nodes' own positions, hops between nodes in one bubble were a few points long and
    /// their markers popped on and off inside it, which looked like the packet ping-ponging.
    @State private var bubbleByNodeLocation: [CoordinateKey: CLLocationCoordinate2D] = [:]
    @State private var nodeClusters: [NodeCluster] = []
    @State private var iataCoordinates: [String: CLLocationCoordinate2D] = [:]
    @State private var iataRadiusKm: [String: Double] = [:]
    @State private var mapDisplayStyle = MapDisplayStyle.persisted
    @State private var nodeFilters = MapNodeFilterSelection()
    @State private var filteredNodeCount = 0
    @State private var locationManager = MapLocationManager()
    @State private var userLocation: CLLocationCoordinate2D?
    @Namespace private var mapScope
    @State private var hasCenteredCamera = false
    @State private var activePings: [ActivePing] = []
    @State private var replayPings: [ActivePing] = []
    /// Observers that heard the replayed route, labelled where the replay ends.
    @State private var replayReceivers: [ReplayReceiver] = []
    /// Nodes hidden behind a replay's observer markers (see `refreshReplayHiddenNodes`).
    @State private var replayHiddenNodeIDs: Set<String> = []
    /// The replay request and route now showing. One tap on Replay reaches the map several ways
    /// (the request, the Map tab opening, the route selection that came with it); each started
    /// the replay again, so it was seen to begin and then restart.
    @State private var startedReplay: StartedReplay?
    /// The replay was asked for from another screen: give the viewer a moment to take in the map
    /// before it plays. Used once, by the next replay that's drawn.
    @State private var replayNeedsLeadIn = false
    /// The replay's own clock. Moving traffic isn't drawn while the camera moves (it would trail
    /// the map), so the replay pauses while the user pans or zooms and resumes where it was,
    /// instead of carrying on unseen and fading hops under their finger.
    @State private var replayPausedAt: Date?
    @State private var replayClockOffset: TimeInterval = 0

    private struct StartedReplay: Equatable {
        let request: UUID
        let routeIndex: Int
    }
    @State private var isReplayMode = false
    @State private var showsReplayRouteOnly = true
    @State private var processedEventIds: Set<String> = []
    @AppStorage(LiveMapPreferences.realisticPropagationKey) private var realisticPropagation = false
    @AppStorage(LiveMapPreferences.groupsNodesKey) private var groupsNodes = true
    /// Realistic propagation: each packet's observations, held by hash until its buffer ends.
    @State private var heldPropagation: [String: HeldPropagation] = [:]
    @State private var heldPropagationRelease: Task<Void, Never>?
    /// Observers each live packet has already been drawn reaching. An observer reports every copy
    /// it hears, including neighbours' re-broadcasts, which drew the packet going out a kilometre or
    /// two and straight back to the same observer, again and again. Only its first copy is drawn.
    @State private var reachedObserversByHash: [String: Set<String>] = [:]
    @State private var reachedObserverHashOrder: [String] = []
    @State private var isInitialLoadComplete = false
    @State private var isChangingRegion = false
    @State private var isLocatingUser = false
    @State private var isRefreshingMap = false
    @State private var pendingReplayRequestID: UUID?
    @State private var loadedAnalyzerSessionID: UUID?
    @State private var lastFramedMapScopeID: String?
    @State private var displayUpdateTask: Task<Void, Never>?
    @State private var incomingEventTask: Task<Void, Never>?
    @State private var pendingMapTapTask: Task<Void, Never>?
    @State private var viewportResizeTask: Task<Void, Never>?
    @State private var displayUpdateID = UUID()
    @State private var isSearchPresented = false
    @State private var isMapFiltersPresented = false
    @State private var isMapKeyPresented = false
    @State private var selectedRouteDetails: MapRouteDetails?
    @State private var highlightedNodeID: String?
    @State private var lastHandledNavigationRequestID: UUID?
    @State private var isMapCameraMoving = false
    /// A replay is framed and waits for the camera to settle before it starts.
    @State private var isReplayAwaitingCamera = false
    @State private var replayCameraFallback: Task<Void, Never>?
    @State private var mapViewportSize = CGSize.zero
    @Environment(\.isHingedDevice) private var isHingedDevice
    /// The top inset outside the navigation stack (status bar and tab bar). The wide header is
    /// placed from this: on the first appearance the hidden navigation bar's height is still in
    /// the stack's own safe area, which dropped the header by a bar height until the next visit.
    @State private var chromeTopInset: CGFloat = 0

    private struct MapNodeNameLabel: View {
        let name: String
        let side: LabelSide
        let colorScheme: ColorScheme

        var body: some View {
            Text(name)
                .font(.monaspace(.caption2).weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 4)
                .padding(.vertical, 2)
                .background(
                    colorScheme == .dark ? Color.black.opacity(0.72) : Color.white.opacity(0.82),
                    in: Capsule()
                )
                .offset(side.offset)
                .allowsHitTesting(false)
        }
    }

    /// Which side of its 32 pt marker frame a node's name sits on.
    enum LabelSide {
        case below, above, left, right

        /// Facing away from `towardHub` (a screen offset from the marker to its fan's hub).
        init(awayFrom towardHub: CGSize) {
            if abs(towardHub.width) > abs(towardHub.height) * 1.2 {
                self = towardHub.width > 0 ? .left : .right
            } else {
                self = towardHub.height > 0 ? .above : .below
            }
        }

        /// The frame edge the label is aligned to, and how far it's moved out from there so it
        /// clears the marker by 4 pt (the frame is 32 pt, the marker 10 pt, so 25 = 16 + 9).
        var alignment: Alignment {
            switch self {
            case .below: .top
            case .above: .bottom
            case .left: .trailing
            case .right: .leading
            }
        }

        var offset: CGSize {
            switch self {
            case .below: CGSize(width: 0, height: 25)
            case .above: CGSize(width: 0, height: -25)
            case .left: CGSize(width: -25, height: 0)
            case .right: CGSize(width: 25, height: 0)
            }
        }
    }

    /// From the centre of its frame toward a point `offset` away (a fanned marker's true position).
    private struct LeaderLine: Shape {
        let offset: CGSize

        func path(in rect: CGRect) -> Path {
            var path = Path()
            path.move(to: CGPoint(x: rect.midX, y: rect.midY))
            path.addLine(to: CGPoint(x: rect.midX + offset.width, y: rect.midY + offset.height))
            return path
        }
    }

    /// A node drawn away from its true position so overlapping markers can be told apart.
    private struct FannedNode: Identifiable {
        let id: String
        let truePosition: CLLocationCoordinate2D
        let shownPosition: CLLocationCoordinate2D
        let color: Color
    }

    private struct HeldObservation {
        let key: String
        let event: LiveEnvelope
    }

    private struct HeldPropagation {
        let releaseAt: Date
        var observations: [HeldObservation] = []
    }

    private struct NodeCluster: Identifiable, Sendable {
        let id: String
        let coordinate: CLLocationCoordinate2D
        let count: Int
    }

    private func updateLiveFeedDemand() {
        let demand: LiveFeedDemand? = isVisible && isTabActive && scenePhase == .active
            ? LiveFeedDemand(configuration: LiveFeedConfiguration(
                resolvePath: true, includeObserverKey: true, includeRepeats: true
            ))
            : nil
        liveFeed.setDemand(demand, for: liveFeedConsumer)
    }

    private struct CoordinateKey: Hashable, Sendable {
        let latitude: Double
        let longitude: Double

        init(_ coordinate: CLLocationCoordinate2D) {
            latitude = coordinate.latitude
            longitude = coordinate.longitude
        }
    }

    /// One hop of one packet. Every observer of a packet reports its path, and
    /// most hops are shared, so each is drawn once rather than once per observer.
    private struct PacketSegmentKey: Hashable, Sendable {
        let packetHash: String
        let from: CoordinateKey
        let to: CoordinateKey
    }

    private struct DisplayRegion: Sendable {
        let centerLatitude: Double
        let centerLongitude: Double
        let latitudeDelta: Double
        let longitudeDelta: Double

        init(_ region: MKCoordinateRegion) {
            centerLatitude = region.center.latitude
            centerLongitude = region.center.longitude
            latitudeDelta = region.span.latitudeDelta
            longitudeDelta = region.span.longitudeDelta
        }
    }

    private struct DisplayResult: Sendable {
        let nodes: [MeshNode]
        let clusters: [NodeCluster]
        let displayCoordinatesByNodeID: [String: CLLocationCoordinate2D]
        let filteredCount: Int
        /// For fanned-out nodes, the point they fan out from (their group's centre).
        var fanOriginsByNodeID: [String: CLLocationCoordinate2D] = [:]
        /// For grouped nodes, keyed by their position: the bubble they're drawn as.
        var bubbleByNodeLocation: [CoordinateKey: CLLocationCoordinate2D] = [:]
    }

    private struct WideMapHeader: View {
        let isConnected: Bool
        let isRefreshing: Bool
        let togglePacketSidebar: (() -> Void)?
        let retry: () -> Void
        let search: () -> Void
        @Binding var mapDisplayStyle: MapDisplayStyle

        var body: some View {
            HStack(alignment: .top) {
                HStack(spacing: 8) {
                    if let togglePacketSidebar {
                        Button(action: togglePacketSidebar) {
                            Image(systemName: "list.bullet.rectangle")
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain)
                        .nodeScopeFloatingGlass(cornerRadius: 18)
                        .accessibilityLabel("Toggle live packets")
                    }

                    HStack(spacing: 7) {
                        Circle()
                            .fill(isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                            .frame(width: 7, height: 7)
                        Text("Live Map")
                            .font(.monaspace(.headline))
                    }
                    .padding(.horizontal, 14)
                    .frame(height: 44)
                    .nodeScopeFloatingGlass(cornerRadius: 18)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(isConnected ? "Live Map, connected" : "Live Map, reconnecting")

                }

                Spacer()

                HStack(spacing: 2) {
                    Button(action: retry) {
                        Image(systemName: "arrow.clockwise")
                            .frame(width: 44, height: 44)
                    }
                    .disabled(isRefreshing)
                    .accessibilityLabel("Refresh map data")

                    Button(action: search) {
                        Image(systemName: "magnifyingglass")
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Search nodes")

                    Menu {
                        ForEach(MapDisplayStyle.allCases) { style in
                            Button {
                                mapDisplayStyle = style
                            } label: {
                                if mapDisplayStyle == style {
                                    Label(style.title, systemImage: "checkmark")
                                } else {
                                    Label(style.title, systemImage: style.systemImage)
                                }
                            }
                        }
                    } label: {
                        Image(systemName: "map")
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Map style")
                }
                .buttonStyle(.plain)
                .nodeScopeFloatingGlass(cornerRadius: 22)
            }
            .foregroundStyle(.primary)
        }
    }

    private var usesWideMapHeader: Bool {
        horizontalSizeClass == .regular
    }

    private var wideMapHeaderTopPadding: CGFloat {
        isHingedDevice ? 32 : 8
    }

    private var wideMapStatusTopPadding: CGFloat {
        isHingedDevice ? 84 : 60
    }

    var body: some View {
        NavigationStack {
                    GeometryReader { geometry in
                // One timeline for both modes, paused outside replay, so leaving a replay keeps the
                // same Map: rebuilding it lost a camera the user had moved and showed the whole US.
                TimelineView(
                    .animation(
                        minimumInterval: mapUpdateInterval,
                        paused: !isReplayMode || !isTabActive
                            || !pingsForDisplay.contains { !$0.isExpired(at: replayClock()) }
                    )
                ) { context in
                    mapContent(at: isReplayMode ? replayClock(context.date) : .now)
                }
                .onAppear { mapViewportSize = geometry.size }
                .onChange(of: geometry.size) { _, size in
                    mapViewportSize = size
                    viewportResizeTask?.cancel()
                    viewportResizeTask = Task {
                        try? await Task.sleep(for: .milliseconds(150))
                        guard !Task.isCancelled else { return }
                        refitReplayForVisibleMapArea()
                    }
                }
                .onChange(of: bottomObscuredFraction) {
                    refitReplayForVisibleMapArea()
                }
                .onChange(of: trailingObscuredWidth) { oldWidth, newWidth in
                    recenterAfterObscuredWidthChange(by: newWidth - oldWidth)
                }
            }
                .mapScope(mapScope)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    if isTabActive && !usesWideMapHeader {
                        if #available(iOS 27.0, *), pinsPacketSidebarToggle,
                       let togglePacketSidebar {
                        ToolbarItem(placement: .topBarPinnedTrailing) {
                            Button(action: togglePacketSidebar) {
                                Image(systemName: "list.bullet.rectangle")
                            }
                            .accessibilityLabel("Toggle live packets")
                        }
                        .visibilityPriority(.high)
                    }

                    ToolbarItemGroup(placement: .topBarLeading) {
                        if !pinsPacketSidebarToggle, let togglePacketSidebar {
                            Button(action: togglePacketSidebar) {
                                Image(systemName: "list.bullet.rectangle")
                            }
                            .accessibilityLabel("Toggle live packets")
                        }

                        HStack(spacing: 6) {
                            Circle()
                                .fill(liveFeed.isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                                .frame(width: 7, height: 7)
                            Text("Live Map")
                                .font(.monaspace(.headline))
                                .fixedSize()
                        }
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(liveFeed.isConnected ? "Live Map, connected" : "Live Map, reconnecting")
                    }
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button(action: retryMapLoad) {
                            Image(systemName: "arrow.clockwise")
                        }
                        .disabled(isRefreshingMap)
                        .accessibilityLabel("Refresh map data")

                        Button(action: toggleMapSearch) {
                            Image(systemName: "magnifyingglass")
                        }
                        .accessibilityLabel("Search nodes")

                        Menu {
                            ForEach(MapDisplayStyle.allCases) { style in
                                Button {
                                    mapDisplayStyle = style
                                } label: {
                                    if mapDisplayStyle == style {
                                        Label(style.title, systemImage: "checkmark")
                                    } else {
                                        Label(style.title, systemImage: style.systemImage)
                                    }
                                }
                            }
                        } label: {
                            Image(systemName: "map")
                        }

                    }
                    }
                }
                .toolbarVisibility(usesWideMapHeader ? .hidden : .automatic, for: .navigationBar)
                .navigationDestination(item: $selectedNode) { node in
                    NodeDetailScreen(node: node)
                }
                .overlay(alignment: .top) {
                    if isTabActive && usesWideMapHeader {
                        WideMapHeader(
                            isConnected: liveFeed.isConnected,
                            isRefreshing: isRefreshingMap,
                            togglePacketSidebar: togglePacketSidebar,
                            retry: retryMapLoad,
                            search: toggleMapSearch,
                            mapDisplayStyle: $mapDisplayStyle
                        )
                        .padding(.horizontal, 20)
                        .padding(.trailing, trailingObscuredWidth)
                        .padding(.top, chromeTopInset + wideMapHeaderTopPadding)
                        .ignoresSafeArea(.container, edges: .top)
                    }
                }
                .overlay(alignment: .topLeading) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) {
                            MapRegionScopeControl()
                            MapNodeFilterButton(
                                activeFilterCount: nodeFilters.activeFilterCount,
                                action: { isMapFiltersPresented = true }
                            )
                            MapKeyButton { isMapKeyPresented = true }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        if isShowingMapLoadingIndicator {
                            LoadingIndicator(title: mapLoadingTitle)
                                .allowsHitTesting(false)
                        } else if !viewModel.nodes.isEmpty {
                            DataLoadStatusView(
                                lastUpdatedAt: viewModel.lastUpdatedAt,
                                errorMessage: viewModel.errorMessage,
                                hasContent: true,
                                retry: retryMapLoad
                            )
                            .frame(maxWidth: 300, alignment: .leading)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, usesWideMapHeader ? 20 : 12)
                    .padding(.top, usesWideMapHeader ? chromeTopInset + wideMapStatusTopPadding : 12)
                    .ignoresSafeArea(.container, edges: usesWideMapHeader ? .top : [])
                }
                .overlay(alignment: .bottomLeading) {
                    VStack(spacing: 10) {
                        if let showLivePackets {
                            Button(action: showLivePackets) {
                                Image(systemName: "list.bullet.rectangle")
                                    .font(.monaspace(.title3).weight(.semibold))
                                    .foregroundStyle(colorScheme == .dark ? Color.white : Color.accentColor)
                                    .frame(width: 44, height: 44)
                            }
                            .buttonStyle(.plain)
                            .nodeScopeFloatingGlass(cornerRadius: 10)
                            .accessibilityLabel("Show live packets")
                        }

                        Button(action: centerOnUserLocation) {
                            Image(systemName: "location.fill")
                                .font(.monaspace(.title3).weight(.semibold))
                                .foregroundStyle(colorScheme == .dark ? Color.white : Color.accentColor)
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain)
                        .nodeScopeFloatingGlass(cornerRadius: 10)
                        .accessibilityLabel("Center on my location")
                    }
                    .padding(.leading, 12)
                    // MapKit reserves the lower-left corner for its logo and
                    // legal attribution on every device class.
                    .padding(.bottom, 36)
                }
                .overlay(alignment: .bottomTrailing) {
                    zoomControl
                        .padding(.trailing, 12 + trailingObscuredWidth)
                        .padding(.bottom, 36)
                }
                .overlay(alignment: .bottom) {
                    if packetReplayStore.isReplayActive || isReplayMode {
                        HStack(spacing: 0) {
                            VStack(spacing: 8) {
                                if !isReplayMode {
                                    Text(isChangingRegion || viewModel.isLoading
                                         ? "Loading route locations…"
                                         : "Not enough known locations to replay this route")
                                        .font(.caption)
                                        .multilineTextAlignment(.center)
                                        .padding(8)
                                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
                                }
                                replayControl
                                if isReplayMode {
                                    routeOptionsControl
                                }
                            }
                            // Stay between the locate and zoom controls on narrow phones.
                            .padding(.horizontal, 64)
                            .frame(maxWidth: .infinity)

                            if centersBottomControlsInLeadingPane {
                                // Reserve horizontal space without stretching the bottom overlay vertically.
                                Color.clear
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 0)
                            }
                        }
                        .padding(.bottom, viewModel.nodes.isEmpty ? 12 : 44)
                        .padding(.trailing, trailingObscuredWidth)
                    }
                }
                .overlay(alignment: .bottom) {
                    if !viewModel.nodes.isEmpty {
                        HStack(spacing: 0) {
                            Text("\(filteredNodeCount) nodes")
                                .font(.caption)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(.thinMaterial, in: Capsule())
                                .frame(maxWidth: .infinity)

                            // Centred on the left screen, like the replay controls, rather than
                            // on the hinge when the map spans both screens.
                            if centersBottomControlsInLeadingPane {
                                Color.clear
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 0)
                            }
                        }
                        .padding(.bottom, 12)
                        .padding(.trailing, trailingObscuredWidth)
                    }
                }
                .overlay {
                    if let errorMessage = viewModel.errorMessage, viewModel.nodes.isEmpty {
                        ContentUnavailableView(
                            "Couldn't load nodes",
                            systemImage: "wifi.slash",
                            description: Text(errorMessage)
                        )
                    } else if liveFeed.usesBrowseSnapshots, !viewModel.isLoading, viewModel.nodes.isEmpty {
                        ContentUnavailableView(
                            "No Nodes Reported Yet",
                            systemImage: "antenna.radiowaves.left.and.right.slash",
                            description: Text(
                                "Beacon adds nodes as it hears their advertisements. Some repeaters advertise only every 12–47 hours, so a quiet region can take time to fill in."
                            )
                        )
                        .frame(maxWidth: 360)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 20)
                        .background(.thickMaterial, in: RoundedRectangle(cornerRadius: 24))
                        .overlay {
                            RoundedRectangle(cornerRadius: 24)
                                .stroke(.white.opacity(0.28), lineWidth: 1)
                        }
                        .shadow(color: .black.opacity(0.16), radius: 16, y: 8)
                        .padding(.horizontal, 24)
                    }
                }
                .overlay(alignment: .top) {
                    if liveFeed.usesBrowseSnapshots, liveFeed.metadataNeedsRecovery {
                        HStack {
                            if liveFeed.metadataRecoveryError == nil {
                                Text("Map data may be outdated. Waiting for refresh…")
                            } else {
                                Text("Map data may be outdated.")
                                Button("Retry") { liveFeed.retryMetadataRecovery() }
                            }
                        }
                        .font(.caption)
                        .padding(8)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.top, 8)
                    }
                }
        }
        .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.top } action: { chromeTopInset = $0 }
        .sheet(isPresented: $isSearchPresented) {
            MapNodeSearchSheet(nodes: viewModel.nodes, selectNode: focusOnSearchedNode)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $isMapKeyPresented) {
            NavigationStack {
                MapKeyScreen()
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Done") { isMapKeyPresented = false }
                        }
                    }
            }
            .tint(colorScheme == .dark ? Color.white : Color.accentColor)
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $isMapFiltersPresented) {
            MapNodeFilterSheet(
                filters: $nodeFilters,
                observers: mapObserverOptions
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(item: $selectedRouteDetails) { route in
            MapRouteDetailsSheet(route: route)
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .onChange(of: resetID) {
            incomingEventTask?.cancel()
            incomingEventTask = nil
            selectedNode = nil
            pendingReplayRequestID = nil
            startedReplay = nil
            isReplayAwaitingCamera = false
            replayCameraFallback?.cancel()
            replayPings = []
            isReplayMode = false
            showsReplayRouteOnly = true
            isSearchPresented = false
            isMapFiltersPresented = false
            selectedRouteDetails = nil
            highlightedNodeID = nil
        }
        .onAppear {
            isVisible = true
            updateLiveFeedDemand()
        }
        .onChange(of: isTabActive) { updateLiveFeedDemand() }
        .onChange(of: scenePhase) { updateLiveFeedDemand() }
        .onChange(of: settings.sourceSession.id) { updateLiveFeedDemand() }
        .onDisappear {
            isVisible = false
            liveFeed.setDemand(nil, for: liveFeedConsumer)
            displayUpdateTask?.cancel()
            incomingEventTask?.cancel()
            pendingMapTapTask?.cancel()
            viewportResizeTask?.cancel()
            incomingEventTask = nil
            pendingMapTapTask = nil
            viewportResizeTask = nil
        }
        .task {
            processedEventIds = Set(liveFeed.recentEvents.map { liveEventKey(for: $0) })
            isInitialLoadComplete = true
        }
        .task(id: mapLoadTaskID) {
            let sourceHost = settings.host
            let sourceSessionID = settings.sourceSession.id
            let selectedRegion = regionFilter.selectedRegion
            let mapScopeID = mapLoadTaskID
            let shouldFrameMapScope = lastFramedMapScopeID != mapScopeID
            isChangingRegion = true
            let analyzerChanged = loadedAnalyzerSessionID != sourceSessionID

            if analyzerChanged {
                nodeFilters = MapNodeFilterSelection.persisted(for: sourceHost)
            } else {
                // An observer belongs to one region, so changing scope resets
                // an observer selection that may no longer be available.
                nodeFilters.selectedObserverID = nil
            }

            // Routes are scoped independently from the node list. Remove
            // animations created under the previous scope before rebuilding
            // from region-filtered packet observations.
            activePings = []
            discardHeldPropagation()
            processedEventIds = []

            async let minimumLoaderDuration: Void = keepRegionLoaderVisible()
            defer {
                isChangingRegion = false
                if !Task.isCancelled {
                    queuePacketReplay()
                }
            }

            if analyzerChanged {
                loadedAnalyzerSessionID = sourceSessionID
                viewModel.resetForAnalyzerSource()
                iataCoordinates = [:]
                iataRadiusKm = [:]
                activePings = []
                discardHeldPropagation()
                isReplayAwaitingCamera = false
                replayCameraFallback?.cancel()
                replayPings = []
                isReplayMode = false
                hasCenteredCamera = false
                cameraPosition = .automatic
            }

            viewModel.configure(settings: settings)
            await viewModel.loadMapDefaults()
            // Off the critical path: the map shouldn't wait on it to show nodes.
            Task { await viewModel.loadLiveMapConfig() }

            guard !Task.isCancelled else { return }

            // A newer host or region selection started loading while this
            // request was suspended. Only the current request may move the
            // camera or replace the displayed data.
            guard sourceHost == settings.host, sourceSessionID == settings.sourceSession.id,
                  selectedRegion == regionFilter.selectedRegion else {
                return
            }

            // These requests are independent. Starting the IATA lookup now
            // avoids making users wait for a full node refresh before the map
            // can move to their selected region.
            async let nodes: Void = viewModel.loadNodes(region: regionFilter.selectedRegion)
            async let packets: Void = viewModel.loadPackets(region: regionFilter.selectedRegion)

            // Once the camera has reached the selected IATA area, cached node
            // data is ready to use. Don't hold the map behind a loader while
            // the freshness check continues in the background.
            if shouldFrameMapScope {
                async let regionZoom: Void = zoomToRegionSelection()
                _ = await (regionZoom, minimumLoaderDuration)
            } else {
                await minimumLoaderDuration
            }
            isChangingRegion = false
            _ = await (nodes, packets)

            // Loading a new analyzer can replace the map's annotations after
            // the first camera move. Reapply the analyzer/region camera once
            // that data settles so MapKit's automatic content fit cannot use
            // distant observed traffic as the final landing viewport.
            guard !Task.isCancelled,
                  sourceHost == settings.host, sourceSessionID == settings.sourceSession.id,
                  selectedRegion == regionFilter.selectedRegion else {
                return
            }
            if shouldFrameMapScope {
                await zoomToRegionSelection()
                lastFramedMapScopeID = mapScopeID
            }

            processHistoricalPackets()
            processIncomingEvents()
            applyBeaconNodeUpdates()
            handleNavigationRequest()
        }
        .task(id: sourceRegionViewportSignature) {
            guard let signature = sourceRegionViewportSignature,
                  regionFilter.selectedRegion == nil,
                  communitySourceViewport == nil,
                  loadedAnalyzerSessionID == settings.sourceSession.id,
                  !isReplayMode else { return }
            await Task.yield()
            guard !Task.isCancelled,
                  signature == sourceRegionViewportSignature,
                  let viewport = sourceRegionViewport else { return }
            moveCamera(to: viewport)
            lastFramedMapScopeID = mapLoadTaskID
        }
        .onChange(of: liveFeed.beaconNodeSequence) {
            applyBeaconNodeUpdates()
        }
        .onChange(of: liveFeed.eventSequence) {
            if isInitialLoadComplete {
                scheduleIncomingEventProcessing()
            }
        }
        .onChange(of: observerRegionLookup.isLoaded) { _, isLoaded in
            guard isLoaded else { return }
            validateSelectedObserver()
            rebuildPathsAfterLoadingObservers()
            if !isChangingRegion {
                replayPendingPacketIfNeeded()
            }
        }
        .onChange(of: viewModel.nodes) {
            updateDisplayedNodes()
            if !isChangingRegion {
                replayPendingPacketIfNeeded()
            }
        }
        .onChange(of: nodeFilters) {
            nodeFilters.persist(for: settings.host)
            updateDisplayedNodes()
            if !isChangingRegion {
                rebuildPathsForCurrentFilters()
            }
        }
        .onChange(of: mapDisplayStyle) {
            UserDefaults.standard.set(mapDisplayStyle.rawValue, forKey: MapDisplayStyle.defaultsKey)
        }
        .onChange(of: packetReplayStore.requestID) {
            replayNeedsLeadIn = !isTabActive
            queuePacketReplay()
        }
        .onChange(of: packetReplayStore.stopRequestID) {
            stopPacketReplay()
        }
        .onChange(of: isTabActive) { _, isActive in
            guard isActive, packetReplayStore.isReplayActive else { return }
            queuePacketReplay()
        }
        .onChange(of: appNavigationStore.requestID) {
            handleNavigationRequest()
        }
        .onChange(of: groupsNodes) {
            updateDisplayedNodes()
        }
        .onChange(of: realisticPropagation) { _, isOn in
            // Turning it off shows anything still held straight away.
            if !isOn { releaseHeldPropagation(all: true) }
        }
        .onChange(of: packetReplayStore.selectedRouteIndex) {
            guard isReplayMode, startedReplay?.request == packetReplayStore.requestID else {
                // A new request's own route selection: the request starts it.
                queuePacketReplay()
                return
            }
            guard startedReplay != currentReplay else { return }
            // Only re-fit the camera when the newly selected route would
            // actually fall outside the current viewport — switching between
            // routes that share the same on-screen area shouldn't jump the map.
            let isOffScreen = !isRouteFullyVisible(currentRouteCoordinates())
            replayPacketRoute(recenter: isOffScreen)
        }
    }

    private func keepRegionLoaderVisible() async {
        try? await Task.sleep(for: .milliseconds(700))
    }

    private func retryMapLoad() {
        guard !isRefreshingMap else { return }
        liveFeed.retryMetadataRecovery()
        isRefreshingMap = true
        Task {
            defer { isRefreshingMap = false }
            await viewModel.loadNodes(region: regionFilter.selectedRegion, forceRefresh: true)
            applyBeaconNodeUpdates()
            await viewModel.loadPackets(region: regionFilter.selectedRegion, forceRefresh: true)
            processHistoricalPackets()
            processIncomingEvents()
        }
    }

    private func applyBeaconNodeUpdates() {
        let sessionID = settings.sourceSession.id
        guard let states = liveFeed.beaconNodeStates(sourceSessionID: sessionID) else { return }
        viewModel.applyBeaconNodes(
            states, region: regionFilter.selectedRegion, sourceSessionID: sessionID,
            catalog: liveFeed.metadataNeedsRecovery ? nil : liveFeed.beaconNodeCatalog,
            catalogID: liveFeed.beaconNodeCatalogID
        )
    }

    private func toggleMapSearch() {
        isSearchPresented = true
    }

    private func focusOnSearchedNode(_ node: MeshNode) {
        guard let coordinate = validCoordinate(node.coordinate) else { return }
        highlightedNodeID = node.id
        withAnimation(.easeInOut(duration: 0.35)) {
            cameraPosition = .region(
                MKCoordinateRegion(
                    center: coordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.08, longitudeDelta: 0.08)
                )
            )
        }
    }

    private func validateSelectedObserver() {
        guard let selectedObserverID = nodeFilters.selectedObserverID else { return }
        let availableObserverIDs = Set(mapObserverOptions.map(\.id))
        if !availableObserverIDs.contains(selectedObserverID) {
            nodeFilters.selectedObserverID = nil
        }
    }

    private func handleNavigationRequest() {
        guard lastHandledNavigationRequestID != appNavigationStore.requestID else { return }
        if case .activeNodes = appNavigationStore.destination {
            lastHandledNavigationRequestID = appNavigationStore.requestID
            nodeFilters.activityFilter = .fifteenMinutes
            nodeFilters.selectedRoles = []
            nodeFilters.selectedObserverID = nil
            return
        }
        guard case .mapNode(let node) = appNavigationStore.destination else { return }
        if regionFilter.selectedRegion != nil,
           !viewModel.nodes.contains(where: { $0.id == node.id }) {
            regionFilter.selectedRegion = nil
            return
        }
        lastHandledNavigationRequestID = appNavigationStore.requestID
        selectedNode = nil
        focusOnSearchedNode(node)
    }

    @ViewBuilder
    private func mapContent(at date: Date) -> some View {
        // Replay keeps its tuned MapKit rendering path. Live traffic is drawn
        // entirely by the lightweight canvas overlay below, so its animation
        // clock never invalidates the Map or its annotations.
        let currentPings = isReplayMode ? pingsForDisplay.filter {
            $0.hasStarted(at: date) && !$0.isExpired(at: date)
        } : []
        let completedPings = currentPings.filter {
            !$0.isPulse && $0.hasCompletedTravel(at: date)
        }
        let anchoredPings = isReplayMode ? completedPings : []
        let routeCoordinates = Set(currentPings.flatMap {
            [CoordinateKey($0.start), CoordinateKey($0.end)]
        })
        // During a replay a node with an observer on it is drawn as that observer, ringed in the
        // node's colour, rather than as a dot on top of the observer marker.
        let observedNodeIDs: Set<String> = isReplayMode ? replayHiddenNodeIDs : []
        // Replay labels step in with zoom: observer names once the map spans about a city
        // (0.25° of latitude), then the route's node names closer still (0.08°).
        let latitudeSpan = visibleRegion?.span.latitudeDelta ?? .infinity
        let showsObserverNames = latitudeSpan <= 0.25
        let baseNodes = isReplayMode && showsReplayRouteOnly ? replayRouteNodes : displayedNodes
        let mapNodes = baseNodes
        let mapClusters = isReplayMode && showsReplayRouteOnly
            ? []
            : nodeClusters
        // MapKit's built-in annotation titles become expensive when hundreds
        // appear at once. Only render our lightweight labels when the map is
        // genuinely close and the visible marker count is bounded.
        let showsNodeNames = latitudeSpan <= 0.08
            && mapNodes.count <= 60
        // A replay's own nodes are few, so they're named at that zoom however many others show.
        let namedRouteNodeIDs: Set<String> = isReplayMode && latitudeSpan <= 0.08
            ? Set(replayRouteNodes.map(\.id))
            : []

        // Close in, overlapping nodes fan out from where they really are. A faint line back to a dot
        // at the true position (as CoreScope's node map does) shows the marker has been moved.
        // The lines meet at one hub per group, where the group really is: its nodes are within a
        // marker's width of each other, so separate true-position dots only crossed and crowded.
        let fannedNodes: [FannedNode] = mapNodes.compactMap { node in
            guard !observedNodeIDs.contains(node.id),
                  let shown = displayCoordinatesByNodeID[node.id],
                  let hub = fanOriginsByNodeID[node.id] else { return nil }
            return FannedNode(id: node.id, truePosition: hub, shownPosition: shown,
                              color: NodeRoleStyle.color(for: node.role))
        }
        let truePositionDots = Dictionary(fannedNodes.map { (CoordinateKey($0.truePosition), $0) },
                                          uniquingKeysWith: { first, _ in first }).values.sorted { $0.id < $1.id }
        // Each fanned marker draws its own short line back to the true position, in screen points
        // for the settled camera: a MapKit polyline this short didn't reliably render.
        let pointsPerMapPoint = replayScreenPointsPerMapPoint()
        let leaderOffsets: [String: CGSize] = pointsPerMapPoint.map { scale in
            Dictionary(uniqueKeysWithValues: fannedNodes.map { node in
                let shown = MKMapPoint(node.shownPosition), truth = MKMapPoint(node.truePosition)
                return (node.id, CGSize(width: (truth.x - shown.x) * scale, height: (truth.y - shown.y) * scale))
            })
        } ?? [:]
        // A fanned node's name goes on the side facing away from its hub, so a ring of names
        // spreads out instead of stacking under the markers.
        let labelSides: [String: LabelSide] = leaderOffsets.mapValues { towardHub in
            LabelSide(awayFrom: towardHub)
        }
        // Of the names allowed at this zoom, only those that don't overlap a name already placed:
        // observers first, then the replay's nodes, then the rest.
        let labeledIDs = placedLabels(
            observers: isReplayMode && showsObserverNames ? replayReceivers : [],
            nodes: mapNodes.filter {
                !observedNodeIDs.contains($0.id) && (showsNodeNames || namedRouteNodeIDs.contains($0.id))
            },
            routeNodeIDs: namedRouteNodeIDs,
            markers: mapNodes.filter { !observedNodeIDs.contains($0.id) },
            observerMarkers: isReplayMode ? replayReceivers : [],
            labelSides: labelSides
        )

        MapReader { proxy in
            Map(position: $cameraPosition, scope: mapScope) {
                ForEach(truePositionDots) { node in
                    Annotation("", coordinate: node.truePosition, anchor: .center) {
                        // Neutral, since several nodes can share it.
                        Circle()
                            .fill(.white)
                            .frame(width: 6, height: 6)
                            .overlay { Circle().stroke(Color(white: 0.25).opacity(0.85), lineWidth: 1.25) }
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }

                ForEach(anchoredPings) { ping in
                    let opacity = ping.completedPathOpacity(at: date)
                    MapPolyline(coordinates: ping.pathCoordinates())
                        .stroke(
                            ping.pathColor.opacity(opacity * 0.18),
                            style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round)
                        )
                    MapPolyline(coordinates: ping.pathCoordinates())
                        .stroke(
                            ping.pathColor.opacity(opacity * 0.85),
                            style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
                        )
                }

                ForEach(mapNodes) { node in
                    if let coordinate = node.coordinate, !observedNodeIDs.contains(node.id) {
                        let displayCoordinate = displayCoordinatesByNodeID[node.id] ?? coordinate
                        Annotation("", coordinate: displayCoordinate, anchor: .center) {
                            let isHighlighted = node.id == highlightedNodeID
                            let isRouteHop = routeCoordinates.contains(CoordinateKey(coordinate))
                            MapNodeIndicator(
                                color: NodeRoleStyle.color(for: node.role),
                                isHighlighted: isHighlighted,
                                isRouteHop: isRouteHop
                            )
                                    .frame(width: 32, height: 32)
                                    .background {
                                        if let offset = leaderOffsets[node.id] {
                                            LeaderLine(offset: offset)
                                                .stroke(NodeRoleStyle.color(for: node.role).opacity(0.85),
                                                        style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [3, 2.5]))
                                                .allowsHitTesting(false)
                                        }
                                    }
                                    .contentShape(Circle())
                                    .overlay(alignment: (labelSides[node.id] ?? .below).alignment) {
                                        if labeledIDs.contains(Self.nodeLabelID(node.id)) {
                                            MapNodeNameLabel(
                                                name: node.name ?? shortKey(node.publicKey),
                                                side: labelSides[node.id] ?? .below,
                                                colorScheme: colorScheme
                                            )
                                        }
                                    }
                            .allowsHitTesting(false)
                            .accessibilityElement()
                            .accessibilityAddTraits(.isButton)
                            .accessibilityLabel(node.name ?? shortKey(node.publicKey))
                            .accessibilityValue(node.role.capitalized)
                            .accessibilityHint(isRouteHop ? "Opens details for this route hop" : "Opens node details")
                            .accessibilityAction { selectedNode = node }
                        }
                    }
                }

                ForEach(mapClusters) { cluster in
                    Annotation("", coordinate: cluster.coordinate, anchor: .center) {
                        Text("\(cluster.count)")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 20, height: 20)
                            .lineLimit(1)
                            .minimumScaleFactor(0.65)
                            .background(.blue, in: Circle())
                            .overlay {
                                Circle()
                                    .stroke(.white.opacity(0.8), lineWidth: 1)
                            }
                            .accessibilityLabel("\(cluster.count) nearby nodes")
                    }
                }

                if isReplayMode {
                    ForEach(replayReceivers) { receiver in
                        Annotation("", coordinate: receiver.coordinate, anchor: .center) {
                            ReplayReceiverMarker(
                                name: receiver.name,
                                nodeColor: receiver.nodeRole.map { NodeRoleStyle.color(for: $0) },
                                showsName: labeledIDs.contains(Self.observerLabelID(receiver.id)),
                                colorScheme: colorScheme
                            )
                        }
                    }
                }

                // Float the user indicator above its exact coordinate. MapKit
                // may place a node above the annotation's anchor point, so the
                // marker itself stays entirely outside a co-located node.
                if let userLocation {
                    Annotation("Your location", coordinate: userLocation, anchor: .bottom) {
                        VStack(spacing: 4) {
                            Text("You")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.primary)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background(.thinMaterial, in: Capsule())
                            ZStack {
                                Circle()
                                    .fill(Color.accentColor.opacity(0.22))
                                    .frame(width: 42, height: 42)
                                Circle()
                                    .fill(.white)
                                    .frame(width: 28, height: 28)
                                    .shadow(color: .black.opacity(0.25), radius: 2)
                                Circle()
                                    .fill(Color.accentColor)
                                    .frame(width: 20, height: 20)
                                Circle()
                                    .fill(.white)
                                    .frame(width: 6, height: 6)
                            }
                        }
                        .accessibilityLabel("Your location")
                    }
                }
            }
            .mapStyle(mapDisplayStyle.style)
            .mapControls {
                MapCompass()
            }
            .onMapCameraChange(frequency: .continuous) { _ in
                noteCameraMovement()
            }
            .onMapCameraChange(frequency: .onEnd) { context in
                isMapCameraMoving = false
                resumeReplayClock()
                visibleRegion = context.region
                updateDisplayedNodes(in: context.region)
                refreshReplayHiddenNodes()
                startReplayAfterCamera()
            }
            .onTapGesture { screenPoint in
                scheduleMapTap(at: screenPoint, using: proxy, date: .now, nodes: mapNodes)
            }
            .simultaneousGesture(
                SpatialTapGesture(count: 2)
                    .onEnded { _ in cancelPendingMapTap() }
            )
            .overlay {
                if !isMapCameraMoving && isReplayMode {
                    MapTrafficCanvas(
                        pings: transientPings(from: currentPings, at: date),
                        date: date,
                        proxy: proxy,
                        retainsCompletedPaths: true,
                        showsRouteAnchors: false
                    )
                    .allowsHitTesting(false)
                } else if !isMapCameraMoving {
                    LiveMapTrafficLayer(
                        pings: pingsForDisplay,
                        proxy: proxy,
                        minimumInterval: mapUpdateInterval,
                        isPaused: !isTabActive || !pingsForDisplay.contains { !$0.isExpired() }
                    )
                    .allowsHitTesting(false)
                }
            }
        }
    }

    private func updateDisplayedNodes(in region: MKCoordinateRegion? = nil) {
        let nodes = viewModel.nodes
        let displayRegion = (region ?? visibleRegion).map(DisplayRegion.init)
        let selectedRoles = nodeFilters.selectedRoles
        let groupsNodes = groupsNodes
        let activityCutoff = nodeFilters.activityFilter.maximumAge.map {
            Date.now.addingTimeInterval(-$0)
        }
        let updateID = UUID()
        displayUpdateID = updateID
        displayUpdateTask?.cancel()
        displayUpdateTask = Task {
            let worker = Task.detached(priority: .userInitiated) {
                Self.makeDisplayResult(
                    nodes: nodes,
                    region: displayRegion,
                    selectedRoles: selectedRoles,
                    activityCutoff: activityCutoff,
                    groupsNodes: groupsNodes
                )
            }
            let result = await withTaskCancellationHandler {
                await worker.value
            } onCancel: {
                worker.cancel()
            }
            guard !Task.isCancelled, displayUpdateID == updateID else { return }
            guard let result else { return }
            displayedNodes = result.nodes
            nodeClusters = result.clusters
            displayCoordinatesByNodeID = result.displayCoordinatesByNodeID
            fanOriginsByNodeID = result.fanOriginsByNodeID
            bubbleByNodeLocation = result.bubbleByNodeLocation
            filteredNodeCount = result.filteredCount
        }
    }

    private func noteCameraMovement() {
        if !isMapCameraMoving {
            isMapCameraMoving = true
            if isReplayMode, !replayPings.isEmpty, replayPausedAt == nil {
                replayPausedAt = .now
            }
        }
    }

    /// The replay's time for a wall-clock `date`: frozen while paused, minus earlier pauses.
    private func replayClock(_ date: Date = .now) -> Date {
        (replayPausedAt ?? date).addingTimeInterval(-replayClockOffset)
    }

    private func resumeReplayClock() {
        guard let pausedAt = replayPausedAt else { return }
        replayClockOffset += Date.now.timeIntervalSince(pausedAt)
        replayPausedAt = nil
    }

    private func resetReplayClock() {
        replayPausedAt = nil
        replayClockOffset = 0
    }

    private func transientPings(from pings: [ActivePing], at date: Date) -> [ActivePing] {
        // Keep every started replay segment in the canvas until its fade ends.
        // MapKit also owns an anchored copy after travel completes so the route
        // remains attached to the map during camera movement. Drawing the same
        // completed segment in the canvas avoids a visible canvas-to-MapKit
        // handoff when switching between alternate routes.
        if isReplayMode {
            return pings
        }

        let transient = pings.filter {
            $0.isPulse
                || !$0.hasCompletedTravel(at: date)
                || $0.arrivalPulseProgress(at: date) != nil
        }
        guard !isReplayMode, transient.count > 20 else { return transient }
        return Array(transient.suffix(20))
    }

    nonisolated private static func makeDisplayResult(
        nodes: [MeshNode],
        region: DisplayRegion?,
        selectedRoles: Set<String>,
        activityCutoff: Date?,
        groupsNodes: Bool = true
    ) -> DisplayResult? {
        let validNodes = nodes.filter { node in
            guard !Task.isCancelled, let latitude = node.lat, let longitude = node.lon else { return false }
            let matchesRole = selectedRoles.isEmpty || selectedRoles.contains(node.role.lowercased())
            let matchesActivity = activityCutoff.map { cutoff in
                node.latestActivity.map { $0 >= cutoff } ?? false
            } ?? true
            return (latitude != 0 || longitude != 0) && matchesRole && matchesActivity
        }
        guard !Task.isCancelled else { return nil }
        guard let region else {
            return DisplayResult(
                nodes: validNodes,
                clusters: [],
                displayCoordinatesByNodeID: Dictionary(
                    uniqueKeysWithValues: validNodes.compactMap { node in
                        node.coordinate.map { (node.id, $0) }
                    }
                ),
                filteredCount: validNodes.count
            )
        }

        // Keep a small off-screen buffer to avoid marker churn at the edge.
        let latitudeLimit = region.latitudeDelta * 0.7
        let longitudeLimit = region.longitudeDelta * 0.7
        let visibleNodes = validNodes.filter { node in
            guard !Task.isCancelled, let latitude = node.lat, let longitude = node.lon else { return false }
            let longitudeDifference = abs(
                (longitude - region.centerLongitude + 540)
                    .truncatingRemainder(dividingBy: 360) - 180
            )
            return abs(latitude - region.centerLatitude) <= latitudeLimit
                && longitudeDifference <= longitudeLimit
        }
        guard !Task.isCancelled else { return nil }

        // Settings → Display → Group Nearby Nodes off: every node on its own, as CoreScope's live
        // map draws them. Zoomed out they stay at their real positions (fanning them out there
        // would move them miles); close in, overlapping ones fan out as usual so each can be tapped.
        if !groupsNodes, max(region.latitudeDelta, region.longitudeDelta) > 0.25 {
            return DisplayResult(
                nodes: visibleNodes,
                clusters: [],
                displayCoordinatesByNodeID: Dictionary(
                    visibleNodes.compactMap { node in node.coordinate.map { (node.id, $0) } },
                    uniquingKeysWith: { first, _ in first }
                ),
                filteredCount: validNodes.count
            )
        }

        // Keep dense groups clustered until the map is close enough that
        // separating their markers doesn't misrepresent their locations.
        guard max(region.latitudeDelta, region.longitudeDelta) > 0.25 else {
            var fanOrigins: [String: CLLocationCoordinate2D] = [:]
            let coordinates = spreadNearbyCoordinates(for: visibleNodes, region: region, fanOrigins: &fanOrigins)
            return DisplayResult(
                nodes: visibleNodes,
                clusters: [],
                displayCoordinatesByNodeID: coordinates,
                filteredCount: validNodes.count,
                fanOriginsByNodeID: fanOrigins
            )
        }

        // The compact 10-point markers allow a tighter grid while nearby
        // pairs still cluster until the map reaches close range.
        let latitudeCellSize = region.latitudeDelta / 18
        let longitudeCellSize = region.longitudeDelta / 18
        var nodesByCell: [String: [MeshNode]] = [:]
        for node in visibleNodes {
            guard !Task.isCancelled, let latitude = node.lat, let longitude = node.lon else { return nil }
            let latitudeCell = Int(floor((latitude + 90) / latitudeCellSize))
            let longitudeCell = Int(floor((longitude + 180) / longitudeCellSize))
            nodesByCell["\(latitudeCell)-\(longitudeCell)", default: []].append(node)
        }

        var individualNodes: [MeshNode] = []
        var clusters: [NodeCluster] = []
        var bubbleByNodeLocation: [CoordinateKey: CLLocationCoordinate2D] = [:]
        for (key, cellNodes) in nodesByCell {
            guard !Task.isCancelled else { return nil }
            guard cellNodes.count >= 2 else {
                individualNodes.append(contentsOf: cellNodes)
                continue
            }

            var latitudeTotal = 0.0
            var longitudeTotal = 0.0
            for node in cellNodes {
                latitudeTotal += node.lat ?? 0
                longitudeTotal += node.lon ?? 0
            }
            let bubble = CLLocationCoordinate2D(
                latitude: latitudeTotal / Double(cellNodes.count),
                longitude: longitudeTotal / Double(cellNodes.count)
            )
            for node in cellNodes {
                if let coordinate = node.coordinate { bubbleByNodeLocation[CoordinateKey(coordinate)] = bubble }
            }
            clusters.append(
                NodeCluster(
                    id: "cluster-\(key)",
                    coordinate: bubble,
                    count: cellNodes.count
                )
            )
        }

        return DisplayResult(
            nodes: individualNodes,
            clusters: clusters.sorted { $0.id < $1.id },
            displayCoordinatesByNodeID: Dictionary(
                uniqueKeysWithValues: individualNodes.compactMap { node in
                    node.coordinate.map { (node.id, $0) }
                }
            ),
            filteredCount: validNodes.count,
            bubbleByNodeLocation: bubbleByNodeLocation
        )
    }

    nonisolated private static func spreadNearbyCoordinates(
        for nodes: [MeshNode],
        region: DisplayRegion,
        fanOrigins: inout [String: CLLocationCoordinate2D]
    ) -> [String: CLLocationCoordinate2D] {
        // Shares of the visible span, so they stay about the same on screen at every zoom: nodes
        // within ~10 pt (one marker width) of each other are fanned out, to a ~19 pt radius, far
        // enough apart to tell the markers apart and to see the line back to the true position.
        // Longitude is scaled from latitude so the threshold and fan are circles on screen (a share
        // of the longitude span would follow the much narrower screen width).
        let longitudeScale = 1 / max(cos(region.centerLatitude * .pi / 180), 0.2)
        let latitudeThreshold = region.latitudeDelta * 0.012
        let longitudeThreshold = latitudeThreshold * longitudeScale
        var groupedNodes: [[MeshNode]] = []
        for node in nodes.sorted(by: { $0.publicKey < $1.publicKey }) {
            guard let coordinate = node.coordinate else { continue }
            if let groupIndex = groupedNodes.firstIndex(where: { group in
                guard let anchor = group.first?.coordinate else { return false }
                return abs(anchor.latitude - coordinate.latitude) <= latitudeThreshold
                    && abs(anchor.longitude - coordinate.longitude) <= longitudeThreshold
            }) {
                groupedNodes[groupIndex].append(node)
            } else {
                groupedNodes.append([node])
            }
        }
        var coordinates: [String: CLLocationCoordinate2D] = [:]
        coordinates.reserveCapacity(nodes.count)

        for group in groupedNodes {
            guard let firstCoordinate = group.first?.coordinate else { continue }
            guard group.count > 1 else {
                coordinates[group[0].id] = firstCoordinate
                continue
            }

            let groupCoordinates = group.compactMap(\.coordinate)
            let origin = CLLocationCoordinate2D(
                latitude: groupCoordinates.reduce(0) { $0 + $1.latitude } / Double(groupCoordinates.count),
                longitude: groupCoordinates.reduce(0) { $0 + $1.longitude } / Double(groupCoordinates.count)
            )
            // Scale with the viewport so nearby markers fan out by roughly
            // the same number of screen points at every zoom level.
            let radiusMultiplier = max(1, Double(group.count) / 6)
            let latitudeRadius = region.latitudeDelta * 0.022 * radiusMultiplier
            let longitudeRadius = latitudeRadius * longitudeScale
            for (index, node) in group.enumerated() {
                let angle = (Double(index) / Double(group.count)) * 2 * Double.pi - Double.pi / 2
                coordinates[node.id] = CLLocationCoordinate2D(
                    latitude: origin.latitude + cos(angle) * latitudeRadius,
                    longitude: origin.longitude + sin(angle) * longitudeRadius
                )
                fanOrigins[node.id] = origin
            }
        }

        return coordinates
    }

    private var mapObserverOptions: [MapObserverOption] {
        return observerRegionLookup.observers
            .filter { observer in
                regionFilter.includes(observer.iata)
            }
            .map { observer in
                MapObserverOption(
                    id: observer.id,
                    title: observer.name ?? observer.id,
                    iata: observer.iata
                )
            }
            .sorted { lhs, rhs in
                lhs.title.localizedCaseInsensitiveCompare(rhs.title) == .orderedAscending
            }
    }

    private var isShowingMapLoadingIndicator: Bool {
        (viewModel.isLoading && !isReplayMode)
            || (isChangingRegion && !isReplayMode)
            || isLocatingUser
    }

    private var mapLoadingTitle: String {
        if isLocatingUser {
            return "Locating you…"
        }
        if let selection = regionFilter.selectionTitle {
            return "Loading \(selection)…"
        }
        return "Loading all regions…"
    }

    private var pingsForDisplay: [ActivePing] {
        isReplayMode ? replayPings : activePings
    }

    private var replayRouteNodes: [MeshNode] {
        let routeKeys = Set(packetReplayStore.resolvedPath.map { $0.lowercased() })
        return viewModel.nodes.filter { routeKeys.contains($0.publicKey.lowercased()) }
    }

    private var isReplayPlaying: Bool {
        // The button reflects packet travel, not the slower line fade-out.
        isReplayAwaitingCamera || replayPings.contains { $0.travelProgress(at: replayClock()) < 1.0 }
    }

    private var replayControl: some View {
        HStack(spacing: 4) {
            Button {
                if isReplayMode {
                    replayPacketRoute(recenter: false)
                } else {
                    queuePacketReplay()
                }
            } label: {
                Label(
                    !isReplayMode ? "Retry Replay" : isReplayPlaying ? "Replay" : "Play Again",
                    systemImage: isReplayPlaying ? "play.fill" : "arrow.counterclockwise"
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.accentColor, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(!isReplayMode ? "Retry route replay" : isReplayPlaying ? "Replay in progress" : "Play replay again")

            Button("Live") {
                packetReplayStore.stopReplay()
                stopPacketReplay()
            }
            .accessibilityLabel("Resume live traffic")
            .font(.caption.weight(.semibold))
            .foregroundStyle(mapControlAccentColor)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .padding(4)
        .nodeScopeFloatingGlass(cornerRadius: 20)
        .accessibilityElement(children: .contain)
    }

    private var routeOptionsControl: some View {
        HStack(spacing: 4) {
            if packetReplayStore.routes.count > 1 {
                RoutePicker(
                    options: packetReplayStore.routes,
                    selection: Binding(
                        get: { packetReplayStore.selectedRouteIndex },
                        set: { packetReplayStore.selectRoute(at: $0) }
                    ),
                    compact: true,
                    allowsAll: true
                )
                .layoutPriority(-1)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .tint(mapControlAccentColor)

                Divider()
                    .frame(height: 16)
            }

            Button {
                showsReplayRouteOnly.toggle()
            } label: {
                Label("Route Only", systemImage: showsReplayRouteOnly ? "checkmark.circle.fill" : "circle")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(showsReplayRouteOnly ? mapControlAccentColor : .primary)
                    .fixedSize()
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Show route nodes only")
            .accessibilityValue(showsReplayRouteOnly ? "On" : "Off")
        }
        .padding(4)
        .nodeScopeFloatingGlass(cornerRadius: 20)
        .accessibilityElement(children: .contain)
    }

    private var mapControlAccentColor: Color {
        colorScheme == .dark ? .white : .accentColor
    }

    private var mapUpdateInterval: TimeInterval {
        // Replays stay especially fluid, while dense live traffic uses a
        // slightly lower cadence to leave MapKit enough time to render its map.
        let now = Date.now
        let hasActiveMotion = pingsForDisplay.contains {
            guard !$0.isExpired(at: now) else { return false }
            return $0.isPulse
                || !$0.hasCompletedTravel(at: now)
                || $0.arrivalPulseProgress(at: now) != nil
                || $0.progress(at: now) > 0
        }
        guard hasActiveMotion else {
            return 1.0
        }
        if isReplayMode {
            return 1.0 / 30.0
        }
        return pingsForDisplay.count > 40 ? 1.0 / 15.0 : 1.0 / 24.0
    }

    private var zoomControl: some View {
        VStack(spacing: 0) {
            Button {
                zoom(by: 0.5)
            } label: {
                Image(systemName: "plus")
                    .frame(width: 36, height: 36)
            }
            Divider().frame(width: 28)
            Button {
                zoom(by: 2)
            } label: {
                Image(systemName: "minus")
                    .frame(width: 36, height: 36)
            }
        }
        .foregroundStyle(colorScheme == .dark ? Color.white : Color.accentColor)
        .nodeScopeFloatingGlass(cornerRadius: 10)
    }

    private func shortKey(_ key: String) -> String {
        String(key.prefix(8)).uppercased()
    }

    nonisolated private static func nodeLabelID(_ id: String) -> String { "node:\(id)" }
    nonisolated private static func observerLabelID(_ id: String) -> String { "observer:\(id)" }

    /// The labels that fit: placed in priority order, each skipped if its box would overlap one
    /// already placed. Boxes are estimated from the text length and each label's offset below its
    /// marker, projected with the settled camera (`visibleRegion`), so this runs cheaply per frame.
    private func placedLabels(
        observers: [ReplayReceiver],
        nodes: [MeshNode],
        routeNodeIDs: Set<String>,
        markers: [MeshNode] = [],
        observerMarkers: [ReplayReceiver] = [],
        labelSides: [String: LabelSide] = [:]
    ) -> Set<String> {
        struct Candidate {
            let id: String; let coordinate: CLLocationCoordinate2D; let width: CGFloat; let top: CGFloat
            var side: LabelSide = .below
        }
        var candidates: [Candidate] = observers.map { observer in
            // caption2 semibold in a capsule, its top 10.5 pt below the observer's centre.
            Candidate(id: Self.observerLabelID(observer.id), coordinate: observer.coordinate,
                      width: CGFloat(observer.name.count) * 6.2 + 10, top: observer.nodeRole == nil ? 10.5 : 11.5)
        }
        let orderedNodes = nodes.filter { routeNodeIDs.contains($0.id) } + nodes.filter { !routeNodeIDs.contains($0.id) }
        for node in orderedNodes {
            guard let coordinate = displayCoordinatesByNodeID[node.id] ?? node.coordinate else { continue }
            // Monaspace caption2 (about 6.6 pt a character), its top 9 pt below the node.
            let name = node.name ?? shortKey(node.publicKey)
            candidates.append(Candidate(id: Self.nodeLabelID(node.id), coordinate: coordinate,
                                        width: CGFloat(name.count) * 6.6 + 8, top: 9,
                                        side: labelSides[node.id] ?? .below))
        }
        guard !candidates.isEmpty else { return [] }
        guard let region = visibleRegion, mapViewportSize.width > 0 else { return Set(candidates.map(\.id)) }

        let topLeft = MKMapPoint(CLLocationCoordinate2D(
            latitude: min(region.center.latitude + region.span.latitudeDelta / 2, 85),
            longitude: region.center.longitude - region.span.longitudeDelta / 2
        ))
        let bottomRight = MKMapPoint(CLLocationCoordinate2D(
            latitude: max(region.center.latitude - region.span.latitudeDelta / 2, -85),
            longitude: region.center.longitude + region.span.longitudeDelta / 2
        ))
        let width = bottomRight.x - topLeft.x
        guard width > 0 else { return Set(candidates.map(\.id)) }
        let scale = Double(mapViewportSize.width) / width

        func screenPoint(_ coordinate: CLLocationCoordinate2D) -> CGPoint {
            let point = MKMapPoint(coordinate)
            return CGPoint(x: (point.x - topLeft.x) * scale, y: (point.y - topLeft.y) * scale)
        }
        // Markers are obstacles too, so a name never lies under another node.
        var placed: [CGRect] = markers.compactMap { node in
            guard let coordinate = displayCoordinatesByNodeID[node.id] ?? node.coordinate else { return nil }
            let center = screenPoint(coordinate)
            return CGRect(x: center.x - 6, y: center.y - 6, width: 12, height: 12)
        } + observerMarkers.map { observer in
            let center = screenPoint(observer.coordinate)
            return CGRect(x: center.x - 9, y: center.y - 9, width: 18, height: 18)
        }
        var labeled = Set<String>()
        for candidate in candidates {
            let center = screenPoint(candidate.coordinate)
            let x = center.x, y = center.y
            let gap = candidate.top, w = candidate.width
            let box: CGRect = switch candidate.side {
            case .below: CGRect(x: x - w / 2, y: y + gap, width: w, height: 16)
            case .above: CGRect(x: x - w / 2, y: y - gap - 16, width: w, height: 16)
            case .left: CGRect(x: x - gap - w, y: y - 8, width: w, height: 16)
            case .right: CGRect(x: x + gap, y: y - 8, width: w, height: 16)
            }
            let paddedBox = box.insetBy(dx: -2, dy: -1)
            guard !placed.contains(where: { $0.intersects(paddedBox) }) else { continue }
            placed.append(paddedBox)
            labeled.insert(candidate.id)
        }
        return labeled
    }

    /// Nodes drawn on top of one another where the user tapped, while zoomed out (closer in they
    /// fan out and can be tapped one by one). Empty when the tap isn't on a stack.
    private func stackedNodes(at screenPoint: CGPoint, using proxy: MapProxy, nodes: [MeshNode]) -> [MeshNode] {
        guard let region = visibleRegion, max(region.span.latitudeDelta, region.span.longitudeDelta) > 0.25,
              let tapped = nearestNode(to: screenPoint, using: proxy, nodes: nodes),
              let tappedCoordinate = displayCoordinatesByNodeID[tapped.id] ?? tapped.coordinate,
              let tappedPoint = proxy.convert(tappedCoordinate, to: .local) else { return [] }
        let stack = nodes.filter { node in
            guard let coordinate = displayCoordinatesByNodeID[node.id] ?? node.coordinate,
                  let point = proxy.convert(coordinate, to: .local) else { return false }
            return hypot(point.x - tappedPoint.x, point.y - tappedPoint.y) <= 10
        }
        return stack.count > 1 ? stack : []
    }

    /// Zooms in on a stack of nodes far enough that they fan out to be told apart.
    private func zoomToNodes(_ nodes: [MeshNode]) {
        let coordinates = nodes.compactMap(\.coordinate)
        guard !coordinates.isEmpty else { return }
        let latitudes = coordinates.map(\.latitude), longitudes = coordinates.map(\.longitude)
        let center = CLLocationCoordinate2D(
            latitude: (latitudes.min()! + latitudes.max()!) / 2,
            longitude: (longitudes.min()! + longitudes.max()!) / 2
        )
        // Close enough for fanning (0.25° or less) unless the stack itself is wider than that.
        let span = MKCoordinateSpan(
            latitudeDelta: max((latitudes.max()! - latitudes.min()!) * 2.5, 0.05),
            longitudeDelta: max((longitudes.max()! - longitudes.min()!) * 2.5, 0.05)
        )
        let region = MKCoordinateRegion(center: center, span: span)
        withAnimation {
            cameraPosition = .region(region)
        }
        visibleRegion = region
    }

    private func nearestNode(
        to screenPoint: CGPoint,
        using proxy: MapProxy,
        nodes: [MeshNode]
    ) -> MeshNode? {
        let maxTapDistance: CGFloat = 44
        var closestNode: MeshNode?
        var closestDistance = maxTapDistance
        for node in nodes {
            guard let coordinate = displayCoordinatesByNodeID[node.id] ?? node.coordinate,
                  let nodeScreenPoint = proxy.convert(coordinate, to: .local) else { continue }
            let distance = hypot(nodeScreenPoint.x - screenPoint.x, nodeScreenPoint.y - screenPoint.y)
            if distance < closestDistance {
                closestDistance = distance
                closestNode = node
            }
        }
        return closestNode
    }

    private func scheduleMapTap(
        at screenPoint: CGPoint,
        using proxy: MapProxy,
        date: Date,
        nodes: [MeshNode]
    ) {
        pendingMapTapTask?.cancel()
        pendingMapTapTask = Task { @MainActor in
            do {
                try await Task.sleep(for: .milliseconds(250))
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            if let cluster = nearestCluster(to: screenPoint, using: proxy) {
                zoomToCluster(cluster)
            } else if let route = nearestRoute(to: screenPoint, using: proxy, at: date) {
                selectedRouteDetails = route
            } else if case let stack = stackedNodes(at: screenPoint, using: proxy, nodes: nodes), !stack.isEmpty {
                zoomToNodes(stack)
            } else if let node = nearestNode(to: screenPoint, using: proxy, nodes: nodes) {
                selectedNode = node
            }
            pendingMapTapTask = nil
        }
    }

    private func cancelPendingMapTap() {
        pendingMapTapTask?.cancel()
        pendingMapTapTask = nil
    }

    private func nearestRoute(to screenPoint: CGPoint, using proxy: MapProxy, at date: Date) -> MapRouteDetails? {
        let visibleSegments = pingsForDisplay.filter {
            !$0.isPulse && $0.hasStarted(at: date) && !$0.isExpired(at: date)
        }
        // Keep the rendered route narrow, but give every segment a generous
        // invisible hit corridor so it remains practical to select by touch.
        let maximumDistance: CGFloat = 36
        var closestSegment: ActivePing?
        var closestDistance = maximumDistance

        for segment in visibleSegments {
            guard let start = proxy.convert(segment.start, to: .local),
                  let end = proxy.convert(segment.end, to: .local) else { continue }
            let distance = distance(from: screenPoint, toSegmentFrom: start, to: end)
            if distance < closestDistance {
                closestDistance = distance
                closestSegment = segment
            }
        }

        guard let closestSegment else { return nil }
        let routeSegments: [ActivePing]
        if let routeID = closestSegment.routeID {
            routeSegments = visibleSegments
                .filter { $0.routeID == routeID }
                .sorted { $0.segmentIndex < $1.segmentIndex }
        } else {
            routeSegments = [closestSegment]
        }
        guard let firstSegment = routeSegments.first else { return nil }

        let coordinates = [firstSegment.start] + routeSegments.map(\.end)
        let hops = coordinates.enumerated().map { index, coordinate in
            let node = node(at: coordinate)
            let fallbackTitle = index == coordinates.count - 1
                ? (firstSegment.observerName ?? "Unknown node")
                : "Unknown node"
            return MapRouteHop(
                id: "\(firstSegment.routeID ?? firstSegment.id.uuidString)-\(index)",
                position: index + 1,
                title: node?.name ?? fallbackTitle,
                publicKey: node?.publicKey,
                node: node
            )
        }
        return MapRouteDetails(
            id: firstSegment.routeID ?? firstSegment.id.uuidString,
            packetHash: firstSegment.packetHash,
            observerName: firstSegment.observerName,
            observedAt: firstSegment.observedAt,
            snr: firstSegment.snr,
            rssi: firstSegment.rssi,
            sender: firstSegment.sender,
            messageText: firstSegment.messageText,
            hops: hops
        )
    }

    private func node(at coordinate: CLLocationCoordinate2D) -> MeshNode? {
        if let exactNode = viewModel.nodesByPubkey.values.first(where: {
            guard let nodeCoordinate = $0.coordinate else { return false }
            return abs(nodeCoordinate.latitude - coordinate.latitude) < 0.000_001
                && abs(nodeCoordinate.longitude - coordinate.longitude) < 0.000_001
        }) {
            return exactNode
        }
        return nil
    }

    private func distance(from point: CGPoint, toSegmentFrom start: CGPoint, to end: CGPoint) -> CGFloat {
        let deltaX = end.x - start.x
        let deltaY = end.y - start.y
        let lengthSquared = deltaX * deltaX + deltaY * deltaY
        guard lengthSquared > 0 else { return hypot(point.x - start.x, point.y - start.y) }
        let projection = min(max(
            ((point.x - start.x) * deltaX + (point.y - start.y) * deltaY) / lengthSquared,
            0
        ), 1)
        let nearest = CGPoint(x: start.x + projection * deltaX, y: start.y + projection * deltaY)
        return hypot(point.x - nearest.x, point.y - nearest.y)
    }

    private func nearestCluster(to screenPoint: CGPoint, using proxy: MapProxy) -> NodeCluster? {
        let maxTapDistance: CGFloat = 24
        var closestCluster: NodeCluster?
        var closestDistance = maxTapDistance

        for cluster in nodeClusters {
            guard let clusterScreenPoint = proxy.convert(cluster.coordinate, to: .local) else { continue }
            let distance = hypot(
                clusterScreenPoint.x - screenPoint.x,
                clusterScreenPoint.y - screenPoint.y
            )
            if distance < closestDistance {
                closestDistance = distance
                closestCluster = cluster
            }
        }

        return closestCluster
    }

    private func zoomToCluster(_ cluster: NodeCluster) {
        guard let visibleRegion else { return }

        let span = MKCoordinateSpan(
            latitudeDelta: max(visibleRegion.span.latitudeDelta * 0.45, 0.05),
            longitudeDelta: max(visibleRegion.span.longitudeDelta * 0.45, 0.05)
        )
        let region = MKCoordinateRegion(center: cluster.coordinate, span: span)
        withAnimation {
            cameraPosition = .region(region)
        }
        self.visibleRegion = region
    }

    private func queuePacketReplay() {
        guard packetReplayStore.isReplayActive else {
            pendingReplayRequestID = nil
            return
        }
        // Already showing this request on this route: a repeat trigger, not a new replay.
        if isReplayMode, startedReplay == currentReplay { return }
        selectedNode = nil
        pendingReplayRequestID = packetReplayStore.requestID
        guard !isChangingRegion else { return }
        replayPendingPacketIfNeeded()
    }

    private var currentReplay: StartedReplay {
        StartedReplay(request: packetReplayStore.requestID, routeIndex: packetReplayStore.selectedRouteIndex)
    }

    private func stopPacketReplay() {
        startedReplay = nil
        resetReplayClock()
        replayNeedsLeadIn = false
        isReplayAwaitingCamera = false
        replayCameraFallback?.cancel()
        replayCameraFallback = nil
        pendingReplayRequestID = nil
        replayPings = []
        replayReceivers = []
        isReplayMode = false
    }

    private func replayPendingPacketIfNeeded() {
        guard packetReplayStore.isReplayActive else {
            pendingReplayRequestID = nil
            return
        }
        guard pendingReplayRequestID == packetReplayStore.requestID else { return }
        if replayPacketRoute() {
            pendingReplayRequestID = nil
        }
    }

    /// A replayed route on the map: its located nodes and the observers it ends at.
    private struct ReplayRouteGeometry {
        let index: Int
        let coordinates: [CLLocationCoordinate2D]
        let receivers: [ReplayReceiver]
    }

    /// The shown routes that can be drawn (at least one located node and somewhere to go).
    /// With all routes shown, each observer keeps only its shortest route (normally the first copy
    /// it heard): its other routes are neighbours re-broadcasting the packet back to it, which drew
    /// the packet going out and straight back. A route left delivering only such echoes is dropped.
    /// One route on its own, and packet details, still show every route.
    private func replayRouteGeometries() -> [ReplayRouteGeometry] {
        let shown = packetReplayStore.shownRoutes
        var reached = Set<String>()
        var keptReceivers: [Int: [ReplayReceiver]] = [:]
        for (index, route) in shown.sorted(by: { $0.route.keys.count < $1.route.keys.count }) {
            let all = receivers(for: route)
            keptReceivers[index] = packetReplayStore.showsAllRoutes
                ? all.filter { reached.insert($0.id).inserted }
                : all
            if packetReplayStore.showsAllRoutes, !all.isEmpty, keptReceivers[index]?.isEmpty == true {
                keptReceivers[index] = nil
            }
        }
        return shown.compactMap { index, route in
            guard let receivers = keptReceivers[index] else { return nil }
            let coordinates = route.keys.compactMap { publicKey in
                validCoordinate(viewModel.nodesByPubkey[publicKey.lowercased()]?.coordinate)
            }
            guard !coordinates.isEmpty, coordinates.count + receivers.count >= 2 else { return nil }
            return ReplayRouteGeometry(index: index, coordinates: coordinates, receivers: receivers)
        }
    }

    private func currentRouteCoordinates() -> [CLLocationCoordinate2D] {
        replayRouteGeometries().flatMap { $0.coordinates + $0.receivers.map(\.coordinate) }
    }

    /// Nodes to hide behind the replay's observer markers: the ones at each observer's site, and
    /// any other node that would sit under a marker at the current zoom (when zoomed out a node a
    /// few hundred metres away lands right on it). They reappear once zoomed in enough to separate.
    private func refreshReplayHiddenNodes() {
        guard isReplayMode, !replayReceivers.isEmpty else {
            if !replayHiddenNodeIDs.isEmpty { replayHiddenNodeIDs = [] }
            return
        }
        var hidden = Set(replayReceivers.flatMap(\.nodeIDs))
        if let pointsPerMapPoint = replayScreenPointsPerMapPoint() {
            let radius = 9 / pointsPerMapPoint
            let sites = replayReceivers.map { MKMapPoint($0.coordinate) }
            for node in viewModel.nodes {
                guard let coordinate = validCoordinate(node.coordinate) else { continue }
                let point = MKMapPoint(coordinate)
                if sites.contains(where: { hypot(point.x - $0.x, point.y - $0.y) <= radius }) {
                    hidden.insert(node.id)
                }
            }
        }
        if hidden != replayHiddenNodeIDs { replayHiddenNodeIDs = hidden }
    }

    /// Screen points per map point for the map as it's framed now (nil before the first layout).
    private func replayScreenPointsPerMapPoint() -> Double? {
        guard let region = visibleRegion, mapViewportSize.width > 0 else { return nil }
        let halfWidth = region.span.longitudeDelta / 2
        let west = MKMapPoint(CLLocationCoordinate2D(latitude: region.center.latitude, longitude: region.center.longitude - halfWidth))
        let east = MKMapPoint(CLLocationCoordinate2D(latitude: region.center.latitude, longitude: region.center.longitude + halfWidth))
        let width = abs(east.x - west.x)
        return width > 0 ? Double(mapViewportSize.width) / width : nil
    }

    /// One marker per observer location. With all routes shown, an observer that heard several
    /// of them (or observers sharing a site) gave MapKit identical, stacked annotations that it
    /// kept re-placing every frame, so the names jittered.
    private static func mergedReceivers(_ receivers: [ReplayReceiver]) -> [ReplayReceiver] {
        var order: [CoordinateKey] = []
        var namesByLocation: [CoordinateKey: [String]] = [:]
        var coordinateByLocation: [CoordinateKey: CLLocationCoordinate2D] = [:]
        for receiver in receivers {
            let location = CoordinateKey(receiver.coordinate)
            if namesByLocation[location] == nil {
                order.append(location)
                coordinateByLocation[location] = receiver.coordinate
            }
            if namesByLocation[location, default: []].contains(receiver.name) == false {
                namesByLocation[location, default: []].append(receiver.name)
            }
        }
        return order.compactMap { location in
            guard let names = namesByLocation[location], let first = names.first,
                  let coordinate = coordinateByLocation[location] else { return nil }
            return ReplayReceiver(name: names.count > 1 ? "\(first) +\(names.count - 1)" : first, coordinate: coordinate)
        }
    }

    /// Where a replayed route ends: the observers that heard exactly this path, at their
    /// positions. One on the route's last node is kept (it's drawn as that node, ringed); the
    /// replay just has no hop to draw to it.
    private func receivers(for route: RouteOption) -> [ReplayReceiver] {
        route.receivers(
            coordinateById: observerRegionLookup.coordinateById,
            coordinateByName: observerRegionLookup.coordinateByName
        ).compactMap { receiver in
            guard let coordinate = validCoordinate(receiver.coordinate) else { return nil }
            return ReplayReceiver(name: receiver.name, coordinate: coordinate)
        }
    }

    /// Gives each observer the nodes at its site: within 75 m, since an observer's own repeater
    /// (or a room server and repeater on one device) often reports a slightly different position.
    /// The ring takes the most network-relevant role among them.
    private func observersWithNodeRoles(_ receivers: [ReplayReceiver]) -> [ReplayReceiver] {
        let rolePriority = ["repeater", "room", "companion", "sensor"]
        func rank(_ role: String) -> Int { rolePriority.firstIndex(of: role.lowercased()) ?? rolePriority.count }
        let sites = receivers.map { MKMapPoint($0.coordinate) }
        var nodesBySite = Array(repeating: [MeshNode](), count: receivers.count)
        for node in viewModel.nodes {
            guard let coordinate = validCoordinate(node.coordinate) else { continue }
            let point = MKMapPoint(coordinate)
            for (index, site) in sites.enumerated() where site.distance(to: point) <= 75 {
                nodesBySite[index].append(node)
            }
        }
        return receivers.enumerated().map { index, receiver in
            var receiver = receiver
            let nodes = nodesBySite[index]
            receiver.nodeRole = nodes.map(\.role).min { rank($0) < rank($1) }
            receiver.nodeIDs = nodes.map(\.id)
            return receiver
        }
    }

    // Roughly how much of the screen's vertical span the bottom-anchored
    // replay controls (Play/Live + route pills) occupy, used both to keep
    // freshly-framed routes clear of them and to decide whether a route the
    // user just switched to is still usably on screen.
    private let replayControlsClearanceFraction: Double = 0.18

    private func isRouteFullyVisible(_ coordinates: [CLLocationCoordinate2D]) -> Bool {
        guard let visibleRegion else { return coordinates.isEmpty }
        let latitudeLimit = visibleRegion.span.latitudeDelta / 2
        let longitudeLimit = visibleRegion.span.longitudeDelta / 2
        let bottomClearance = visibleRegion.span.latitudeDelta * replayControlsClearanceFraction
        let minimumLatitude = visibleRegion.center.latitude - latitudeLimit + bottomClearance
        let maximumLatitude = visibleRegion.center.latitude + latitudeLimit
        return coordinates.allSatisfy { coordinate in
            let longitudeDifference = abs(
                (coordinate.longitude - visibleRegion.center.longitude + 540)
                    .truncatingRemainder(dividingBy: 360) - 180
            )
            return coordinate.latitude >= minimumLatitude
                && coordinate.latitude <= maximumLatitude
                && longitudeDifference <= longitudeLimit
        }
    }

    @discardableResult
    private func replayPacketRoute(recenter: Bool = true) -> Bool {
        guard packetReplayStore.isReplayActive else { return false }
        let routes = replayRouteGeometries()
        let receivers = observersWithNodeRoles(Self.mergedReceivers(routes.flatMap(\.receivers)))
        let coordinates = routes.flatMap { $0.coordinates + $0.receivers.map(\.coordinate) }
        guard !routes.isEmpty else {
            // Do not leave the previous packet's replay visible when the new
            // route is waiting for locations. Keep this request retryable.
            stopPacketReplay()
            pendingReplayRequestID = packetReplayStore.requestID
            return false
        }

        // The traffic canvas is hidden while the camera moves, but the replay clock keeps running,
        // so starting now would reveal the route mid-replay once the map settles. Clear the old
        // route, frame the new one, and start the replay when the camera comes to rest.
        if recenter, let routeMapRect = replayRouteMapRect(for: coordinates) {
            replayPings = []
            replayReceivers = receivers
            startedReplay = currentReplay
            refreshReplayHiddenNodes()
            isReplayMode = true
            hasCenteredCamera = true
            isReplayAwaitingCamera = true
            withAnimation {
                cameraPosition = .rect(routeMapRect)
            }
            replayCameraFallback?.cancel()
            replayCameraFallback = Task { @MainActor in
                // A camera already showing this framing never moves or reports an end, so start
                // right away; otherwise give a long flight time to settle before starting anyway.
                try? await Task.sleep(for: .milliseconds(350))
                guard !Task.isCancelled, isReplayAwaitingCamera else { return }
                if isMapCameraMoving {
                    try? await Task.sleep(for: .seconds(3))
                    guard !Task.isCancelled, isReplayAwaitingCamera else { return }
                }
                startReplayAfterCamera()
            }
            return true
        }

        let routeColor: Color = colorScheme == .dark
            ? Color(red: 0.35, green: 0.78, blue: 1.0)
            : .orange
        let signalColor: Color = colorScheme == .dark ? .orange : .blue

        // Each hop's time comes from its real length (see `hopTravelTime`): 0.45 s for neighbours
        // up to 1.5 s for long links, the same at any zoom.
        resetReplayClock()
        if isMapCameraMoving { replayPausedAt = .now }
        let replayStart = Date.now.addingTimeInterval(replayNeedsLeadIn ? 0.75 : 0.5)
        replayNeedsLeadIn = false
        let pointsPerMapPoint = replayScreenPointsPerMapPoint()
        func travelTime(_ from: CLLocationCoordinate2D, _ to: CLLocationCoordinate2D) -> TimeInterval {
            Self.hopTravelTime(from: from, to: to, shortest: 0.45, longest: 1.5, pointsPerMapPoint: pointsPerMapPoint)
        }

        // Plan every hop first: when it starts (after the hops before it on its route) and how
        // long it travels. With all routes shown, hops they share are planned once, so the packet
        // spreads out from a common trunk the way it propagated.
        struct PlannedHop {
            let from: CLLocationCoordinate2D, to: CLLocationCoordinate2D
            let start: TimeInterval, travel: TimeInterval
            let routeID: String, segmentIndex: Int, observerName: String?
        }
        let packetHash = packetReplayStore.packetHash
        var plannedSegments = Set<PacketSegmentKey>()
        func isNew(_ from: CLLocationCoordinate2D, _ to: CLLocationCoordinate2D) -> Bool {
            CoordinateKey(from) != CoordinateKey(to) && plannedSegments.insert(
                PacketSegmentKey(packetHash: packetHash, from: CoordinateKey(from), to: CoordinateKey(to))
            ).inserted
        }
        var hops: [PlannedHop] = []
        for route in routes {
            let routeID = "replay-\(packetHash)-\(route.index)"
            var elapsed: TimeInterval = 0
            for (index, pair) in zip(route.coordinates, route.coordinates.dropFirst()).enumerated() {
                let travel = travelTime(pair.0, pair.1)
                if isNew(pair.0, pair.1) {
                    hops.append(PlannedHop(from: pair.0, to: pair.1, start: elapsed, travel: travel,
                                           routeID: routeID, segmentIndex: index, observerName: nil))
                }
                elapsed += travel
            }
            // The final hops, from the route's last node to each observer, travel together.
            guard let last = route.coordinates.last else { continue }
            for (index, receiver) in route.receivers.enumerated() where isNew(last, receiver.coordinate) {
                hops.append(PlannedHop(from: last, to: receiver.coordinate, start: elapsed,
                                       travel: travelTime(last, receiver.coordinate),
                                       routeID: "\(routeID)-observer-\(index)", segmentIndex: 0,
                                       observerName: receiver.name))
            }
        }

        // One route stays drawn until it finishes, then fades as one path, so it can be studied.
        // All routes leave a trail instead, like CoreScope: each hop holds briefly once it
        // arrives and then fades, so the packet is seen spreading rather than as a growing tree.
        let lastTravelEnd = replayStart.addingTimeInterval(hops.map { $0.start + $0.travel }.max() ?? 0)
        let fadeStart = lastTravelEnd.addingTimeInterval(0.75)
        let fadeDuration = 0.65
        let trailHold = 1.0
        let leavesTrail = packetReplayStore.showsAllRoutes
        let pings = hops.map { hop in
            let hopStart = replayStart.addingTimeInterval(hop.start)
            return ActivePing(
                from: hop.from,
                to: hop.to,
                createdAt: hopStart,
                fadeStartsAt: leavesTrail ? hopStart.addingTimeInterval(hop.travel + trailHold) : fadeStart,
                duration: fadeDuration,
                travelDuration: hop.travel,
                color: routeColor,
                signalColor: signalColor,
                routeID: hop.routeID,
                segmentIndex: hop.segmentIndex,
                packetHash: packetHash,
                observerName: hop.observerName,
                observedAt: packetReplayStore.observedAt,
                snr: packetReplayStore.snr,
                rssi: packetReplayStore.rssi,
                sender: packetReplayStore.sender,
                messageText: packetReplayStore.messageText,
                // Each hop bows to its own left, so A→B and B→A sit on opposite sides.
                curvature: 0.07
            )
        }
        // Starting here (Play Again, or a route already on screen) ends any wait for the camera.
        isReplayAwaitingCamera = false
        replayCameraFallback?.cancel()
        replayCameraFallback = nil
        replayPings = pings
        replayReceivers = receivers
        startedReplay = currentReplay
        refreshReplayHiddenNodes()
        isReplayMode = true
        hasCenteredCamera = true
        return true
    }

    /// Starts a replay held by `replayPacketRoute` once the camera has framed its route.
    private func startReplayAfterCamera() {
        guard isReplayAwaitingCamera else { return }
        isReplayAwaitingCamera = false
        replayCameraFallback?.cancel()
        replayCameraFallback = nil
        replayPacketRoute(recenter: false)
    }

    private func replayRouteMapRect(for coordinates: [CLLocationCoordinate2D]) -> MKMapRect? {
        guard let firstCoordinate = coordinates.first else { return nil }

        let points = coordinates.map(MKMapPoint.init)
        let firstPoint = MKMapPoint(firstCoordinate)
        let minimumX = points.map(\.x).min() ?? firstPoint.x
        let maximumX = points.map(\.x).max() ?? firstPoint.x
        let minimumY = points.map(\.y).min() ?? firstPoint.y
        let maximumY = points.map(\.y).max() ?? firstPoint.y

        // Keep short routes from being framed too tightly, then derive the
        // camera rectangle from the actual viewport. The asymmetric vertical
        // margins reserve screen space below the route for replay controls.
        let minimumDimension = MKMapPointsPerMeterAtLatitude(firstCoordinate.latitude) * 5_000
        let routeWidth = max(maximumX - minimumX, minimumDimension)
        let routeHeight = max(maximumY - minimumY, minimumDimension)
        let centerX = (minimumX + maximumX) / 2
        let centerY = (minimumY + maximumY) / 2
        let viewportWidth = max(Double(mapViewportSize.width), 1)
        let viewportHeight = max(Double(mapViewportSize.height), 1)
        let horizontalMarginFraction = 0.12
        let topMarginFraction = 0.12
        let bottomMarginFraction = max(0.32, min(bottomObscuredFraction + 0.08, 0.8))
        let availableWidth = max(viewportWidth * (1 - horizontalMarginFraction * 2), 1)
        let availableHeight = max(viewportHeight * (1 - topMarginFraction - bottomMarginFraction), 1)
        let mapPointsPerPoint = max(routeWidth / availableWidth, routeHeight / availableHeight)
        let fittedWidth = viewportWidth * mapPointsPerPoint
        let fittedHeight = viewportHeight * mapPointsPerPoint

        return MKMapRect(
            x: centerX - fittedWidth / 2,
            y: centerY - routeHeight / 2 - fittedHeight * topMarginFraction,
            width: fittedWidth,
            height: fittedHeight
        )
    }

    private func refitReplayForVisibleMapArea() {
        guard isReplayMode else { return }
        let coordinates = currentRouteCoordinates()
        guard coordinates.count >= 2,
              let routeMapRect = replayRouteMapRect(for: coordinates) else { return }
        withAnimation(.easeInOut(duration: 0.3)) {
            cameraPosition = .rect(routeMapRect)
        }
    }

    private func centerOnUserLocation() {
        isLocatingUser = true
        locationManager.requestCurrentLocation(
            completion: { coordinate in
                userLocation = coordinate
                moveCamera(to: coordinate, radiusKm: 10)
                isLocatingUser = false
            },
            failure: {
                isLocatingUser = false
            }
        )
    }

    private func zoom(by factor: Double) {
        guard let region = visibleRegion else { return }
        let newSpan = MKCoordinateSpan(
            latitudeDelta: min(max(region.span.latitudeDelta * factor, 0.002), 100),
            longitudeDelta: min(max(region.span.longitudeDelta * factor, 0.002), 100)
        )
        withAnimation {
            cameraPosition = .region(MKCoordinateRegion(center: region.center, span: newSpan))
        }
    }

    /// Pans by half the change, alongside the covering panel's slide, so what was centred in the
    /// visible part of the map stays centred: east when the panel opens, back west when it closes.
    /// A camera move only re-groups nodes when it ends, so running it with the slide is smooth.
    private func recenterAfterObscuredWidthChange(by delta: CGFloat) {
        guard delta != 0, let region = visibleRegion, mapViewportSize.width > 0 else { return }
        let degreesPerPoint = region.span.longitudeDelta / Double(mapViewportSize.width)
        var longitude = region.center.longitude + Double(delta / 2) * degreesPerPoint
        if longitude > 180 { longitude -= 360 }
        if longitude < -180 { longitude += 360 }
        let shifted = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: region.center.latitude, longitude: longitude),
            span: region.span
        )
        withAnimation(.smooth(duration: 0.3)) {
            cameraPosition = .region(shifted)
        }
    }

    private func centerCameraIfNeeded() {
        guard !hasCenteredCamera, let defaults = viewModel.mapDefaults else { return }
        let center = CLLocationCoordinate2D(latitude: defaults.latitude, longitude: defaults.longitude)
        let span = MKCoordinateSpan(latitudeDelta: 4, longitudeDelta: 4)
        let region = MKCoordinateRegion(center: center, span: span)
        cameraPosition = .region(region)
        visibleRegion = region
        hasCenteredCamera = true
    }

    private func zoomToRegionSelection() async {
        guard !isReplayMode else { return }
        guard let selectedRegion = regionFilter.selectedRegion else {
            if let viewport = communitySourceViewport {
                moveCamera(to: viewport.center, radiusKm: viewport.radiusKm)
                return
            }
            if let viewport = sourceRegionViewport {
                moveCamera(to: viewport)
                return
            }
            if let defaults = viewModel.mapDefaults {
                moveCamera(
                    to: CLLocationCoordinate2D(latitude: defaults.latitude, longitude: defaults.longitude),
                    radiusKm: 250
                )
            }
            return
        }

        // A quick pick frames all of its regions together.
        if let codes = regionFilter.selectedCodes, codes.count > 1 {
            if let frame = RegionCameraFraming.areaFrame(codes: codes.sorted(), regionFilter: regionFilter) {
                moveCamera(to: frame)
            }
            return
        }

        let code = selectedRegion.uppercased()
        if let coordinate = iataCoordinates[code], !isReplayMode {
            moveCamera(to: coordinate, radiusKm: iataRadiusKm[code] ?? 45)
            return
        }

        // The analyzer's own center (and radius) first, then the bundled
        // airport table, as on Android. Apple's airport search is only a
        // last resort: for some codes it returns the wrong city (SJT and
        // ACT both resolve to San Antonio International).
        if let published = regionFilter.iataCoords.first(where: { $0.key.uppercased() == code })?.value {
            let coordinate = CLLocationCoordinate2D(latitude: published.lat, longitude: published.lon)
            if CLLocationCoordinate2DIsValid(coordinate) {
                iataCoordinates[code] = coordinate
                if let radiusKm = published.radiusKm, radiusKm.isFinite, radiusKm > 0 {
                    iataRadiusKm[code] = min(radiusKm, 2000)
                }
                moveCamera(to: coordinate, radiusKm: iataRadiusKm[code] ?? 45)
                return
            }
        }
        if let airport = AirportCoordinates.coordinate(for: code) {
            iataCoordinates[code] = airport
            moveCamera(to: airport)
            return
        }

        let request = MKLocalSearch.Request(naturalLanguageQuery: "\(code) airport")
        request.pointOfInterestFilter = MKPointOfInterestFilter(including: [.airport])

        guard let response = try? await MKLocalSearch(request: request).start(),
              let coordinate = response.mapItems.first?.placemark.coordinate,
              CLLocationCoordinate2DIsValid(coordinate),
              !Task.isCancelled,
              !isReplayMode,
              regionFilter.selectedRegion?.uppercased() == code else {
            return
        }

        iataCoordinates[code] = coordinate
        moveCamera(to: coordinate)
    }

    private var communitySourceViewport: (center: CLLocationCoordinate2D, radiusKm: Double)? {
        RegionCameraFraming.communityViewport(registry: analyzerSourceRegistry, host: settings.host)
    }

    private var communitySourceViewportSignature: String? {
        guard let viewport = communitySourceViewport else { return nil }
        return "\(settings.host.lowercased())|\(viewport.center.latitude)|\(viewport.center.longitude)|\(viewport.radiusKm)"
    }

    private var sourceRegionViewport: BeaconRegionReader.Frame? {
        RegionCameraFraming.allRegionsViewport(regionFilter: regionFilter)
    }

    private var sourceRegionViewportSignature: String? {
        guard let viewport = sourceRegionViewport else { return nil }
        return [
            settings.sourceSession.id.uuidString,
            String(viewport.latitude),
            String(viewport.longitude),
            String(viewport.latitudeDelta),
            String(viewport.longitudeDelta)
        ].joined(separator: "|")
    }

    private var mapLoadTaskID: String {
        [
            settings.sourceSession.id.uuidString,
            regionFilter.selectedRegion ?? "all",
            communitySourceViewportSignature ?? "server-default"
        ].joined(separator: "|")
    }

    private func moveCamera(
        to center: CLLocationCoordinate2D,
        radiusKm: Double = 45,
        verticalBiasFraction: Double = 0
    ) {
        let span = RegionCameraFraming.span(around: center, radiusKm: radiusKm)
        let latitudeSpan = span.latitudeDelta
        let longitudeSpan = span.longitudeDelta
        // Shifting the framed center south moves the requested coordinate
        // toward the top of the screen, clear of the bottom-anchored replay
        // controls. A fraction of 0 keeps the normal dead-center framing.
        let biasedCenter = CLLocationCoordinate2D(
            latitude: center.latitude - latitudeSpan * verticalBiasFraction,
            longitude: center.longitude
        )
        let region = MKCoordinateRegion(
            center: biasedCenter,
            span: MKCoordinateSpan(
                latitudeDelta: latitudeSpan,
                longitudeDelta: longitudeSpan
            )
        )
        withAnimation {
            cameraPosition = .region(region)
        }
        visibleRegion = region
    }

    private func moveCamera(to frame: BeaconRegionReader.Frame) {
        let region = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: frame.latitude, longitude: frame.longitude),
            span: MKCoordinateSpan(
                latitudeDelta: frame.latitudeDelta,
                longitudeDelta: frame.longitudeDelta
            )
        )
        withAnimation {
            cameraPosition = .region(region)
        }
        visibleRegion = region
        hasCenteredCamera = true
    }

    private var defaultReferenceCoordinate: CLLocationCoordinate2D? {
        if let selectedRegion = regionFilter.selectedRegion,
           let coordinate = iataCoordinates[selectedRegion.uppercased()] {
            return coordinate
        }
        if let mapDefaults = viewModel.mapDefaults {
            return CLLocationCoordinate2D(latitude: mapDefaults.latitude, longitude: mapDefaults.longitude)
        }
        if let firstNode = viewModel.nodes.first(where: { validCoordinate($0.coordinate) != nil }),
           let coord = validCoordinate(firstNode.coordinate) {
            return coord
        }
        return nil
    }

    private func processHistoricalPackets() {
        var historicalPings: [ActivePing] = []
        let now = Date()

        for packet in viewModel.recentPackets {
            let age = now.timeIntervalSince(packet.timestamp)
            guard age < 12.0 else { continue }
            guard observationMatchesSelectedRegion(
                observerId: packet.observerId,
                observerName: packet.observerName
            ), observationMatchesSelectedObserver(observerId: packet.observerId) else {
                continue
            }

            let subchains = resolvedSubchains(for: packet)
            let pathColor = ActivePing.color(forHash: packet.hash, colorScheme: colorScheme)

            var previousEnd: CLLocationCoordinate2D?
            for (subchainIndex, subchain) in subchains.enumerated() {
                // Pieces split by unresolved hops are joined by a dashed bridge, as live.
                if let from = previousEnd, let to = subchain.first, isValidHopDistance(from: from, to: to) {
                    historicalPings.append(ActivePing(
                        from: from, to: to,
                        createdAt: packet.timestamp.addingTimeInterval(-0.66),
                        duration: 12.0,
                        travelDuration: 0.66,
                        color: pathColor,
                        routeID: "historical-\(packet.hash)-\(packet.observerId ?? "")-bridge-\(subchainIndex)",
                        packetHash: packet.hash,
                        observerName: packet.observerName,
                        observedAt: packet.timestamp,
                        curvature: 0.07,
                        isUnknownRelay: true
                    ))
                }
                previousEnd = subchain.last
                if subchain.count >= 2 {
                    for index in 0..<(subchain.count - 1) {
                        let p1 = subchain[index]
                        let p2 = subchain[index + 1]
                        if isValidHopDistance(from: p1, to: p2) {
                            historicalPings.append(
                                ActivePing(
                                    from: p1,
                                    to: p2,
                                    // Historical packets have already arrived;
                                    // show their paths anchored without replaying them.
                                    createdAt: packet.timestamp.addingTimeInterval(-0.66),
                                    duration: 12.0,
                                    travelDuration: 0.66,
                                    color: pathColor,
                                    routeID: "historical-\(packet.hash)-\(packet.observerId ?? "")-\(subchainIndex)",
                                    segmentIndex: index,
                                    packetHash: packet.hash,
                                    observerName: packet.observerName,
                                    observedAt: packet.timestamp,
                                    snr: packet.snr,
                                    rssi: packet.rssi,
                                    curvature: 0.07
                                )
                            )
                        }
                    }
                }
            }
        }

        guard !historicalPings.isEmpty else { return }
        activePings.append(contentsOf: historicalPings)
        if activePings.count > 100 {
            activePings.removeFirst(activePings.count - 100)
        }
    }

    private func liveEventKey(for envelope: LiveEnvelope) -> String {
        // A packet can be received by more than one observer. Its database ID
        // is shared, but each observer/path combination is a distinct route.
        [
            String(envelope.id),
            envelope.data?.observerId ?? "",
            envelope.data?.pathJson ?? "",
            envelope.data?.hash ?? ""
        ].joined(separator: "|")
    }

    private func rebuildPathsAfterLoadingObservers() {
        // Packets can arrive while the observer roster is still loading. Rebuild
        // their route segments now that each observer can anchor the final hop.
        activePings = []
        discardHeldPropagation()
        processedEventIds = []
        processHistoricalPackets()
        processIncomingEvents()
    }

    private func scheduleIncomingEventProcessing() {
        guard incomingEventTask == nil else { return }
        incomingEventTask = Task { @MainActor in
            defer { incomingEventTask = nil }
            try? await Task.sleep(for: .milliseconds(100))
            guard !Task.isCancelled else { return }
            processIncomingEvents()
        }
    }

    private func processIncomingEvents() {
        let now = Date.now
        activePings.removeAll { $0.isExpired(at: now) }
        var drawnSegments = drawnLiveSegments()
        var newPings: [ActivePing] = []
        // Oldest first (the feed keeps newest first), so an observer's first copy is the one kept.
        for event in liveFeed.recentEvents.reversed() {
            guard event.type == "packet" else { continue }
            let eventKey = liveEventKey(for: event)
            if processedEventIds.contains(eventKey) { continue }
            processedEventIds.insert(eventKey)

            guard observationMatchesSelectedRegion(
                observerId: event.data?.observerId,
                observerName: event.data?.observerName
            ), observationMatchesSelectedObserver(observerId: event.data?.observerId) else {
                continue
            }
            guard isFirstCopy(of: event) else { continue }

            // Realistic propagation (CoreScope): observers report a packet over several seconds,
            // so hold its observations for the buffer and then animate them as one transmission.
            if realisticPropagation, let hash = event.data?.hash {
                heldPropagation[hash, default: HeldPropagation(
                    releaseAt: now.addingTimeInterval(viewModel.propagationBuffer)
                )].observations.append(HeldObservation(key: eventKey, event: event))
                continue
            }
            newPings += livePings(for: event, eventKey: eventKey, observedAt: now, drawnSegments: &drawnSegments)
        }

        if processedEventIds.count > 500 {
            processedEventIds.subtract(processedEventIds.prefix(250))
        }

        admitLiveRoutes(newPings)
        scheduleHeldPropagationRelease()
    }

    /// How long a hop's signal dot travels, from the hop's real length rather than its length on
    /// screen: a few hundred metres is quick, long links take longest. Short hops between nearby
    /// nodes no longer leave the dot sitting in place when zoomed out (which looked like the packet
    /// bouncing between them), and a hop's timing never depends on the zoom, so zooming mid-flight
    /// or replaying at another zoom looks the same.
    nonisolated private static func hopTravelTime(
        from start: CLLocationCoordinate2D,
        to end: CLLocationCoordinate2D,
        shortest: TimeInterval,
        longest: TimeInterval,
        pointsPerMapPoint: Double? = nil
    ) -> TimeInterval {
        // A hop too short to see at this zoom (a kilometre or two between neighbours on a
        // zoomed-out map) is passed straight through: given its usual time, the dot sat wiggling
        // in one spot, and a route that steps out to a neighbour and back read as ping-pong.
        if let pointsPerMapPoint {
            let a = MKMapPoint(start), b = MKMapPoint(end)
            if hypot(b.x - a.x, b.y - a.y) * pointsPerMapPoint < 6 { return 0.05 }
        }
        let kilometres = MKMapPoint(start).distance(to: MKMapPoint(end)) / 1000
        // Gentle (logarithmic) growth that reaches `longest` at about 40 km.
        let growth = (longest - shortest) / log2(41)
        return min(shortest + growth * log2(1 + kilometres), longest)
    }

    /// Hops already on screen, by packet. On networks where dozens of observers hear each packet,
    /// drawing a hop per observation kept the map at its 100-segment cap and re-rendering
    /// continuously, so a hop already shown for the same packet is skipped.
    private func drawnLiveSegments() -> Set<PacketSegmentKey> {
        Set(activePings.compactMap { ping -> PacketSegmentKey? in
            guard !ping.isPulse, let hash = ping.packetHash else { return nil }
            return PacketSegmentKey(packetHash: hash, from: CoordinateKey(ping.start), to: CoordinateKey(ping.end))
        })
    }

    /// The animated hops for one observation, starting at `observedAt`.
    private func livePings(
        for event: LiveEnvelope,
        eventKey: String,
        observedAt: Date,
        drawnSegments: inout Set<PacketSegmentKey>
    ) -> [ActivePing] {
        var newPings: [ActivePing] = []
        let subchains = resolvedSubchains(for: event)
        let pathColor = ActivePing.color(forHash: event.data?.hash ?? event.data?.raw, colorScheme: colorScheme)

        // Hops follow one another across the whole path; each takes 0.3 to 1.0 s by its real length.
        // The pieces of a path split by unresolved hops are joined by a quick dashed bridge.
        let pointsPerMapPoint = replayScreenPointsPerMapPoint()
        var elapsed: TimeInterval = 0
        var previousEnd: CLLocationCoordinate2D?
        for (subchainIndex, subchain) in subchains.enumerated() {
            if let from = previousEnd, let to = subchain.first, isValidHopDistance(from: from, to: to),
               !(event.data?.hash.map { hash in
                   !drawnSegments.insert(PacketSegmentKey(packetHash: hash, from: CoordinateKey(from), to: CoordinateKey(to))).inserted
               } ?? false) {
                let bridgeStart = observedAt.addingTimeInterval(elapsed)
                newPings.append(ActivePing(
                    from: from, to: to,
                    createdAt: bridgeStart,
                    fadeStartsAt: bridgeStart.addingTimeInterval(0.2 + 0.45),
                    duration: 0.55,
                    travelDuration: 0.2,
                    color: pathColor,
                    routeID: "live-\(eventKey)-bridge-\(subchainIndex)",
                    packetHash: event.data?.hash,
                    observerName: event.data?.observerName,
                    observedAt: observedAt,
                    curvature: 0.07,
                    isUnknownRelay: true
                ))
                elapsed += 0.2
            }
            previousEnd = subchain.last
            if subchain.count >= 2 {
                for index in 0..<(subchain.count - 1) {
                    let p1 = subchain[index]
                    let p2 = subchain[index + 1]
                    let travelDuration = Self.hopTravelTime(from: p1, to: p2, shortest: 0.3, longest: 1.0,
                                                            pointsPerMapPoint: pointsPerMapPoint)
                    let hopStart = observedAt.addingTimeInterval(elapsed)
                    elapsed += travelDuration
                    if isValidHopDistance(from: p1, to: p2) {
                        if let hash = event.data?.hash,
                           !drawnSegments.insert(PacketSegmentKey(
                               packetHash: hash, from: CoordinateKey(p1), to: CoordinateKey(p2)
                           )).inserted {
                            continue
                        }
                        newPings.append(
                            ActivePing(
                                from: p1,
                                to: p2,
                                createdAt: hopStart,
                                fadeStartsAt: hopStart.addingTimeInterval(travelDuration + 0.45),
                                duration: 0.55,
                                travelDuration: travelDuration,
                                color: pathColor,
                                routeID: "live-\(eventKey)-\(subchainIndex)",
                                segmentIndex: index,
                                packetHash: event.data?.hash,
                                observerName: event.data?.observerName,
                                observedAt: observedAt,
                                snr: event.data?.snr,
                                rssi: event.data?.rssi,
                                // Same gentle bow as replays, so a packet that doubles back between
                                // nearby nodes shows two arcs rather than one line drawn twice.
                                curvature: 0.07
                            )
                        )
                    }
                }
            } else if let onlyPoint = subchain.first {
                newPings.append(
                    ActivePing(pulseAt: onlyPoint, createdAt: observedAt, duration: 3.0, color: pathColor)
                )
            }
        }
        return newPings
    }

    private func admitLiveRoutes(_ newPings: [ActivePing]) {
        guard !newPings.isEmpty else { return }
        // Admit complete routes atomically. Removing the oldest individual
        // segments at the limit amputates routes that are still traveling.
        // Under overload, preserve animations already on screen and skip a
        // whole newly arriving route instead.
        for route in Self.groupedLiveRoutes(newPings) {
            guard activePings.count + route.count <= 100 else { continue }
            activePings.append(contentsOf: route)
        }
    }

    /// Animates every held packet whose buffer has ended (or all of them), all paths together.
    private func releaseHeldPropagation(all: Bool = false) {
        let now = Date.now
        let due = heldPropagation
            .filter { all || $0.value.releaseAt <= now }
            .sorted { $0.value.releaseAt < $1.value.releaseAt }
        if !due.isEmpty {
            activePings.removeAll { $0.isExpired(at: now) }
            var drawnSegments = drawnLiveSegments()
            var newPings: [ActivePing] = []
            for (hash, held) in due {
                heldPropagation[hash] = nil
                for observation in held.observations {
                    newPings += livePings(
                        for: observation.event, eventKey: observation.key,
                        observedAt: now, drawnSegments: &drawnSegments
                    )
                }
            }
            admitLiveRoutes(newPings)
        }
        scheduleHeldPropagationRelease()
    }

    private func scheduleHeldPropagationRelease() {
        heldPropagationRelease?.cancel()
        guard let next = heldPropagation.values.map(\.releaseAt).min() else {
            heldPropagationRelease = nil
            return
        }
        heldPropagationRelease = Task { @MainActor in
            try? await Task.sleep(for: .seconds(max(next.timeIntervalSinceNow, 0)))
            guard !Task.isCancelled else { return }
            releaseHeldPropagation()
        }
    }

    private func discardHeldPropagation() {
        heldPropagationRelease?.cancel()
        heldPropagationRelease = nil
        heldPropagation = [:]
        reachedObserversByHash = [:]
        reachedObserverHashOrder = []
    }

    /// Whether this is the first copy of its packet from its observer (and records it if so).
    /// Observations without a packet hash or an observer are always drawn.
    private func isFirstCopy(of event: LiveEnvelope) -> Bool {
        guard let hash = event.data?.hash,
              let observer = event.data?.observerId ?? event.data?.observerName else { return true }
        if reachedObserversByHash[hash] == nil {
            reachedObserverHashOrder.append(hash)
            if reachedObserverHashOrder.count > 400 {
                for old in reachedObserverHashOrder.prefix(100) { reachedObserversByHash[old] = nil }
                reachedObserverHashOrder.removeFirst(100)
            }
        }
        return reachedObserversByHash[hash, default: []].insert(observer.lowercased()).inserted
    }

    nonisolated private static func groupedLiveRoutes(_ pings: [ActivePing]) -> [[ActivePing]] {
        var groups: [[ActivePing]] = []
        var groupIndexByRouteID: [String: Int] = [:]

        for ping in pings {
            guard let routeID = ping.routeID else {
                groups.append([ping])
                continue
            }
            if let index = groupIndexByRouteID[routeID] {
                groups[index].append(ping)
            } else {
                groupIndexByRouteID[routeID] = groups.count
                groups.append([ping])
            }
        }
        return groups
    }

    private func rebuildPathsForCurrentFilters() {
        activePings = []
        discardHeldPropagation()
        processedEventIds = []
        processHistoricalPackets()
        processIncomingEvents()
    }

    private func observationMatchesSelectedObserver(observerId: String?) -> Bool {
        guard let selectedObserverID = nodeFilters.selectedObserverID?.lowercased() else {
            return true
        }
        return observerId?.lowercased() == selectedObserverID
    }

    private func observationMatchesSelectedRegion(
        observerId: String?,
        observerName: String?
    ) -> Bool {
        guard regionFilter.selectedCodes != nil else {
            return true
        }

        if let observerId {
            let observerRegion = observerRegionLookup.iataById[observerId]
                ?? observerRegionLookup.iataById[observerId.lowercased()]
            if let observerRegion {
                return regionFilter.includes(observerRegion)
            }
        }

        if let observerName {
            let observerRegion = observerRegionLookup.iataByName[observerName]
                ?? observerRegionLookup.iataByName[observerName.lowercased()]
            if let observerRegion {
                return regionFilter.includes(observerRegion)
            }
        }

        // While scoped, unknown observers fail closed. Once the observer
        // roster loads, rebuildPathsAfterLoadingObservers() retries them.
        return false
    }

    private struct DecodedJsonHelper: Decodable {
        let path: LivePath?
        let resolvedPath: [String?]?

        enum CodingKeys: String, CodingKey {
            case path
            case resolvedPath = "resolved_path"
        }
    }

    private func resolvedSubchains(for packet: Packet) -> [[CLLocationCoordinate2D]] {
        var hops: [String] = []
        var resolvedPubkeys: [String?] = []
        var hasResolvedPath = false

        if let decodedJson = packet.decodedJson {
            if let jsonData = decodedJson.data(using: .utf8),
               let decoded = try? JSONDecoder().decode(DecodedJsonHelper.self, from: jsonData) {
                hops = decoded.path?.hops ?? []
                resolvedPubkeys = decoded.resolvedPath ?? []
                hasResolvedPath = decoded.resolvedPath != nil
            }
        }

        if hops.isEmpty, let pathJson = packet.pathJson {
            hops = parseHops(from: pathJson)
        }

        if hops.isEmpty, let rawHex = packet.rawHex {
            hops = parseHops(from: rawHex)
        }
        if !hasResolvedPath, let field = packet.resolvedPath {
            resolvedPubkeys = field.keys
            hasResolvedPath = true
        }

        return resolvedSubchains(
            hops: hops,
            resolvedPubkeys: resolvedPubkeys,
            guessesUnresolvedHops: !hasResolvedPath && Self.hasMultibyteHops(hops),
            observerId: packet.observerId,
            observerName: packet.observerName
        )
    }

    private func resolvedSubchains(for envelope: LiveEnvelope) -> [[CLLocationCoordinate2D]] {
        // Heartbeat envelopes (no data) never reach here — processIncomingEvents
        // already filters to type == "packet" before calling this.
        guard let data = envelope.data else { return [] }
        let hops = extractHops(from: data)
        let resolvedPubkeys = data.resolvedPath ?? []
        return resolvedSubchains(
            hops: hops,
            resolvedPubkeys: resolvedPubkeys,
            guessesUnresolvedHops: data.resolvedPath == nil && Self.hasMultibyteHops(hops),
            observerId: data.observerId,
            observerName: data.observerName
        )
    }

    private func resolvedSubchains(
        hops: [String],
        resolvedPubkeys: [String?],
        guessesUnresolvedHops: Bool,
        observerId: String?,
        observerName: String?
    ) -> [[CLLocationCoordinate2D]] {
        let observerCoord = resolveObserverCoordinate(
            observerId: observerId,
            observerName: observerName
        )

        let maxCount = max(hops.count, resolvedPubkeys.count)
        var resolvedCoords: [CLLocationCoordinate2D?] = Array(repeating: nil, count: maxCount)

        var referenceCoord: CLLocationCoordinate2D? = observerCoord ?? defaultReferenceCoordinate

        for index in stride(from: maxCount - 1, through: 0, by: -1) {
            var coord: CLLocationCoordinate2D? = nil

            if index < resolvedPubkeys.count, let pubkey = resolvedPubkeys[index], !pubkey.isEmpty {
                coord = resolveCoordinate(keyOrPrefix: pubkey, nearCoordinate: referenceCoord)
            }

            // A hop the analyzer left unresolved stays unknown, as in replays: guessing the nearest
            // node with the same short ID (five or six share each 1-byte ID here) drew paths that
            // doubled back between neighbours, and a repeated ID the analyzer had rejected (a node
            // relays a packet once) sent the packet back to where it had already been. Only a source
            // that sends no resolved path at all falls back to guessing.
            if coord == nil && guessesUnresolvedHops && index < hops.count {
                let hop = hops[index]
                coord = resolveCoordinate(keyOrPrefix: hop, nearCoordinate: referenceCoord)
            }

            resolvedCoords[index] = coord
            if let coord {
                referenceCoord = coord
            }
        }

        var optionalChain: [CLLocationCoordinate2D?] = resolvedCoords
        if let observerCoord {
            optionalChain.append(observerCoord)
        }

        var subchains: [[CLLocationCoordinate2D]] = []
        var currentSubchain: [CLLocationCoordinate2D] = []

        for item in optionalChain {
            // A grouped node is drawn as its bubble; consecutive stops in one bubble are one stop.
            if let coord = item.map({ bubbleByNodeLocation[CoordinateKey($0)] ?? $0 }) {
                if let last = currentSubchain.last, CoordinateKey(last) == CoordinateKey(coord) { continue }
                currentSubchain.append(coord)
            } else {
                if !currentSubchain.isEmpty {
                    subchains.append(currentSubchain)
                    currentSubchain = []
                }
            }
        }
        if !currentSubchain.isEmpty {
            subchains.append(currentSubchain)
        }

        return subchains
    }

    /// 2- or 3-byte hop IDs, specific enough to guess a node from when a source sends no resolved
    /// path. 1-byte IDs are shared by five or six nodes each here, so a guess is close to random.
    nonisolated private static func hasMultibyteHops(_ hops: [String]) -> Bool {
        guard let first = hops.first else { return false }
        return first.count > 2
    }

    private func extractHops(from data: LivePacketData) -> [String] {
        if let hops = data.decoded?.path?.hops, !hops.isEmpty {
            return hops
        }
        if let pathJson = data.pathJson {
            let parsed = parseHops(from: pathJson)
            if !parsed.isEmpty { return parsed }
        }
        if let raw = data.raw {
            let parsed = parseHops(from: raw)
            if !parsed.isEmpty { return parsed }
        }
        return []
    }

    private func parseHops(from rawPath: String?) -> [String] {
        guard let rawPath = rawPath?.trimmingCharacters(in: .whitespacesAndNewlines),
              !rawPath.isEmpty else { return [] }

        guard let jsonData = rawPath.data(using: .utf8) else { return [] }

        // Format 1: [ "4f", "a1" ]
        if let stringArray = try? JSONDecoder().decode([String].self, from: jsonData) {
            return stringArray
        }

        // Format 2: [ 79, 1, 163 ] (Integer array of byte hashes)
        if let intArray = try? JSONDecoder().decode([Int].self, from: jsonData) {
            return intArray.map { String(format: "%02x", $0) }
        }

        // Format 3: [ { "pubkey": "..." }, ... ] or [ { "hash": "..." }, ... ]
        struct HopObject: Decodable {
            let pubkey: String?
            let hash: String?
            let key: String?
        }
        if let objectArray = try? JSONDecoder().decode([HopObject].self, from: jsonData) {
            return objectArray.compactMap { $0.pubkey ?? $0.hash ?? $0.key }
        }

        // Format 4: Comma-separated or space-separated string "4f,a1,c3"
        let components = rawPath
            .components(separatedBy: CharacterSet(charactersIn: ",[]\" "))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        return components
    }

    private func resolveObserverCoordinate(observerId: String?, observerName: String?) -> CLLocationCoordinate2D? {
        if let observerId {
            if let coord = observerRegionLookup.coordinateById[observerId] {
                return coord
            }
            if let coord = observerRegionLookup.coordinateById[observerId.lowercased()] {
                return coord
            }
            if let coord = resolveCoordinate(keyOrPrefix: observerId, nearCoordinate: defaultReferenceCoordinate) {
                return coord
            }
        }
        if let observerName {
            if let coord = observerRegionLookup.coordinateByName[observerName] {
                return coord
            }
            if let coord = observerRegionLookup.coordinateByName[observerName.lowercased()] {
                return coord
            }
            if let node = viewModel.nodes.first(where: { $0.name?.lowercased() == observerName.lowercased() }),
               let coord = validCoordinate(node.coordinate) {
                return coord
            }
        }
        return nil
    }

    private func resolveCoordinate(keyOrPrefix: String, nearCoordinate: CLLocationCoordinate2D?) -> CLLocationCoordinate2D? {
        let cleanKey = keyOrPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanKey.isEmpty else { return nil }
        let lowerKey = cleanKey.lowercased()

        // 1. Exact pubkey lookup (O(1))
        if let node = viewModel.nodesByPubkey[lowerKey],
           let coord = validCoordinate(node.coordinate) {
            return coord
        }

        // 2. Exact node name lookup
        if let node = viewModel.nodes.first(where: {
            $0.name?.lowercased() == lowerKey && validCoordinate($0.coordinate) != nil
        }), let coord = validCoordinate(node.coordinate) {
            return coord
        }

        // 3. Prefix match on publicKey
        let upperPrefix = cleanKey.uppercased()
        var matches = viewModel.nodes.compactMap { node -> (MeshNode, CLLocationCoordinate2D)? in
            guard node.publicKey.uppercased().hasPrefix(upperPrefix),
                  let coord = validCoordinate(node.coordinate) else { return nil }
            return (node, coord)
        }

        // 4. Fallback: Prefix match on node name
        if matches.isEmpty {
            matches = viewModel.nodes.compactMap { node -> (MeshNode, CLLocationCoordinate2D)? in
                guard let name = node.name?.uppercased(),
                      name.hasPrefix(upperPrefix),
                      let coord = validCoordinate(node.coordinate) else { return nil }
                return (node, coord)
            }
        }

        guard !matches.isEmpty else { return nil }

        if matches.count == 1 {
            return matches[0].1
        }

        if let reference = nearCoordinate {
            let refLoc = CLLocation(latitude: reference.latitude, longitude: reference.longitude)
            let sorted = matches.sorted { m1, m2 in
                let d1 = refLoc.distance(from: CLLocation(latitude: m1.1.latitude, longitude: m1.1.longitude))
                let d2 = refLoc.distance(from: CLLocation(latitude: m2.1.latitude, longitude: m2.1.longitude))
                return d1 < d2
            }
            return sorted[0].1
        }

        return matches[0].1
    }

    private func validCoordinate(_ coordinate: CLLocationCoordinate2D?) -> CLLocationCoordinate2D? {
        guard let coordinate, coordinate.latitude != 0 || coordinate.longitude != 0 else { return nil }
        return coordinate
    }

    private func isValidHopDistance(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Bool {
        // A MeshCore route can legitimately span more than 150 km. Coordinates
        // reach this point only after a node or observer lookup, so do not apply
        // an arbitrary geographic cap that would hide valid long-distance paths.
        CLLocationCoordinate2DIsValid(from)
            && CLLocationCoordinate2DIsValid(to)
            && (from.latitude != 0 || from.longitude != 0)
            && (to.latitude != 0 || to.longitude != 0)
    }
}

private struct LiveMapTrafficLayer: View {
    let pings: [ActivePing]
    let proxy: MapProxy
    let minimumInterval: TimeInterval
    let isPaused: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: minimumInterval, paused: isPaused)) { context in
            MapTrafficCanvas(
                pings: pingsForFrame(at: context.date),
                date: context.date,
                proxy: proxy,
                retainsCompletedPaths: true,
                showsRouteAnchors: true
            )
        }
    }

    private func pingsForFrame(at date: Date) -> [ActivePing] {
        // Canvas is inexpensive enough to retain every live segment through
        // its normal fade. Segment-count caps can evict one hop while the rest
        // of its route is still visible, which looks like a random mid-route
        // disappearance on dense analyzers.
        pings.filter { $0.hasStarted(at: date) && !$0.isExpired(at: date) }
    }
}

private struct MapTrafficCanvas: View {
    let pings: [ActivePing]
    let date: Date
    let proxy: MapProxy
    let retainsCompletedPaths: Bool
    let showsRouteAnchors: Bool

    var body: some View {
        Canvas { context, _ in
            var routeAnchors: [TrafficCoordinateKey: (CLLocationCoordinate2D, Color)] = [:]
            for ping in pings where ping.hasStarted(at: date) && !ping.isExpired(at: date) {
                guard let start = proxy.convert(ping.start, to: .local) else { continue }

                if ping.isPulse {
                    drawPulse(ping, at: start, in: &context)
                    continue
                }

                if showsRouteAnchors {
                    routeAnchors[TrafficCoordinateKey(ping.start)] = (ping.start, ping.pathColor)
                    routeAnchors[TrafficCoordinateKey(ping.end)] = (ping.end, ping.pathColor)
                }

                if !ping.hasCompletedTravel(at: date) {
                    drawActiveHop(ping, from: start, in: &context)
                } else if retainsCompletedPaths,
                          let end = proxy.convert(ping.end, to: .local) {
                    drawCompletedHop(ping, from: start, to: end, in: &context)
                }
                if !ping.isUnknownRelay, let pulseProgress = ping.arrivalPulseProgress(at: date),
                   let end = proxy.convert(ping.end, to: .local) {
                    drawArrivalPulse(ping, progress: pulseProgress, at: end, in: &context)
                }
            }

            if showsRouteAnchors {
                for (coordinate, color) in routeAnchors.values {
                    guard let point = proxy.convert(coordinate, to: .local) else { continue }
                    drawRouteAnchor(at: point, color: color, in: &context)
                }
            }
        }
    }

    private func drawCompletedHop(
        _ ping: ActivePing,
        from start: CGPoint,
        to end: CGPoint,
        in context: inout GraphicsContext
    ) {
        let opacity = ping.completedPathOpacity(at: date)
        guard opacity > 0 else { return }

        var path = Path()
        if ping.curvature == 0 {
            path.move(to: start)
            path.addLine(to: end)
        } else {
            path.addLines(ping.pathCoordinates().compactMap { proxy.convert($0, to: .local) })
        }
        if ping.isUnknownRelay {
            context.stroke(path, with: .color(ping.pathColor.opacity(opacity * 0.55)),
                           style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [4, 4]))
            return
        }
        context.stroke(
            path,
            with: .color(ping.pathColor.opacity(opacity * 0.18)),
            style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round)
        )
        context.stroke(
            path,
            with: .color(ping.pathColor.opacity(opacity * 0.85)),
            style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
        )
    }

    private func drawActiveHop(
        _ ping: ActivePing,
        from start: CGPoint,
        in context: inout GraphicsContext
    ) {
        let progress = ping.travelProgress(at: date)
        guard let current = proxy.convert(ping.coordinate(atFraction: progress), to: .local) else { return }
        var path = Path()
        if ping.curvature == 0 {
            path.move(to: start)
            path.addLine(to: current)
        } else {
            path.addLines(ping.pathCoordinates(through: progress).compactMap { proxy.convert($0, to: .local) })
        }
        if ping.isUnknownRelay {
            context.stroke(path, with: .color(ping.pathColor.opacity(0.55)),
                           style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [4, 4]))
            return
        }

        context.stroke(
            path,
            with: .color(ping.pathColor.opacity(0.18)),
            style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round)
        )
        context.stroke(
            path,
            with: .color(ping.pathColor.opacity(0.85)),
            style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
        )
        context.fill(
            Path(ellipseIn: circleRect(center: current, diameter: 7)),
            with: .color(.white)
        )
        context.stroke(
            Path(ellipseIn: circleRect(center: current, diameter: 7)),
            with: .color(ping.pathColor),
            lineWidth: 1.5
        )
    }

    private func drawArrivalPulse(
        _ ping: ActivePing,
        progress: Double,
        at point: CGPoint,
        in context: inout GraphicsContext
    ) {
        context.stroke(
            Path(ellipseIn: circleRect(center: point, diameter: 8 + progress * 42)),
            with: .color(ping.pathColor.opacity((1 - progress) * 0.9)),
            lineWidth: max(0.5, 2.5 - progress * 1.5)
        )
    }

    private func drawPulse(_ ping: ActivePing, at point: CGPoint, in context: inout GraphicsContext) {
        let progress = ping.progress(at: date)
        let opacity = 1 - progress
        let outerDiameter = 14 + progress * 42
        let innerDiameter = 10 + progress * 20

        context.stroke(
            Path(ellipseIn: circleRect(center: point, diameter: outerDiameter)),
            with: .color(ping.pathColor.opacity(opacity * 0.8)),
            lineWidth: 2.5
        )
        context.stroke(
            Path(ellipseIn: circleRect(center: point, diameter: innerDiameter)),
            with: .color(ping.pathColor.opacity(opacity * 0.45)),
            lineWidth: 1.5
        )
    }

    private func drawRouteAnchor(
        at point: CGPoint,
        color: Color,
        in context: inout GraphicsContext
    ) {
        let marker = Path(ellipseIn: circleRect(center: point, diameter: 9))
        context.fill(marker, with: .color(.white.opacity(0.95)))
        context.stroke(marker, with: .color(color), lineWidth: 2)
    }

    private func circleRect(center: CGPoint, diameter: CGFloat) -> CGRect {
        CGRect(
            x: center.x - diameter / 2,
            y: center.y - diameter / 2,
            width: diameter,
            height: diameter
        )
    }
}

private struct TrafficCoordinateKey: Hashable {
    let latitude: Double
    let longitude: Double

    init(_ coordinate: CLLocationCoordinate2D) {
        latitude = coordinate.latitude
        longitude = coordinate.longitude
    }
}

private struct MapRouteDetails: Identifiable {
    let id: String
    let packetHash: String?
    let observerName: String?
    let observedAt: Date
    let snr: Double?
    let rssi: Double?
    let sender: String?
    let messageText: String?
    let hops: [MapRouteHop]

    var shareText: String {
        var lines = [
            "NodeScope Route",
            "Hops: \(hops.count)",
            "Observed: \(observedAt.formatted(.relative(presentation: .named)))"
        ]
        if let observerName, !observerName.isEmpty {
            lines.append("Observer: \(observerName)")
        }
        if let snr {
            lines.append("SNR: \(snr.formatted(.number.precision(.fractionLength(1)))) dB")
        }
        if let rssi {
            lines.append("RSSI: \(rssi.formatted(.number.precision(.fractionLength(0)))) dBm")
        }
        if let packetHash, !packetHash.isEmpty {
            lines.append("Packet: \(packetHash.uppercased())")
        }
        if let messageText = messageText?.trimmingCharacters(in: .whitespacesAndNewlines),
           !messageText.isEmpty {
            lines.append("")
            lines.append(sender.map { "Message from \($0):" } ?? "Message:")
            lines.append(messageText)
        }
        lines.append("")
        lines.append("Route:")
        for hop in hops {
            let key = hop.publicKey.map { " (\($0))" } ?? ""
            lines.append("\(hop.position). \(hop.title)\(key)")
        }
        return lines.joined(separator: "\n")
    }
}

private struct MapRegionScopeControl: View {
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let picks = regionFilter.availablePicks
        // Equatable so the map's frequent redraws leave an open menu alone; rebuilding its items
        // scrolled a long region list away from where it was.
        MapRegionScopeMenu(
            title: title,
            isAllSelected: regionFilter.selectedRegion == nil,
            picks: picks.map { MapRegionScopeMenu.Item(id: $0.name, title: $0.name, isSelected: regionFilter.isSelected($0)) },
            regions: regionFilter.options.map {
                MapRegionScopeMenu.Item(id: $0, title: regionFilter.label(for: $0), isSelected: regionFilter.selectedRegion == $0)
            },
            isDark: colorScheme == .dark,
            selectAll: { regionFilter.selectedRegion = nil },
            selectPick: { name in
                if let pick = regionFilter.availablePicks.first(where: { $0.name == name }) {
                    regionFilter.select(pick)
                }
            },
            selectRegion: { regionFilter.selectedRegion = $0 }
        )
        .equatable()
    }

    private var title: String {
        guard let selectedRegion = regionFilter.selectedRegion else {
            return "All Regions"
        }
        if let pick = regionFilter.activePick { return pick.name }
        if selectedRegion.contains(",") { return regionFilter.label(for: selectedRegion) }
        return "\(selectedRegion) · \(regionFilter.label(for: selectedRegion))"
    }
}

/// The region menu itself, built from plain values, laid out as `RegionQuickPickButtons` and
/// `RegionCodeButtons` lay out the shared menu.
private struct MapRegionScopeMenu: View, Equatable {
    struct Item: Hashable {
        let id: String
        let title: String
        let isSelected: Bool
    }

    let title: String
    let isAllSelected: Bool
    let picks: [Item]
    let regions: [Item]
    let isDark: Bool
    let selectAll: () -> Void
    let selectPick: (String) -> Void
    let selectRegion: (String) -> Void

    nonisolated static func == (lhs: MapRegionScopeMenu, rhs: MapRegionScopeMenu) -> Bool {
        lhs.title == rhs.title && lhs.isAllSelected == rhs.isAllSelected && lhs.picks == rhs.picks
            && lhs.regions == rhs.regions && lhs.isDark == rhs.isDark
    }

    var body: some View {
        Menu {
            Button(action: selectAll) {
                if isAllSelected {
                    Label("All Regions", systemImage: "checkmark")
                } else {
                    Text("All Regions")
                }
            }
            if !picks.isEmpty {
                Section("Region groups") {
                    ForEach(picks, id: \.id) { item in
                        button(item) { selectPick(item.id) }
                    }
                }
            }
            if !regions.isEmpty {
                if picks.isEmpty {
                    Divider()
                    regionButtons
                } else {
                    Section("Regions") { regionButtons }
                }
            }
        } label: {
            HStack(spacing: 5) {
                Image(systemName: "line.3.horizontal.decrease.circle.fill")
                Text(title)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 4)
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.semibold))
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(isDark ? Color.white : Color.accentColor)
            .frame(width: 150, alignment: .leading)
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .nodeScopeFloatingGlass(cornerRadius: 20)
        }
        .accessibilityLabel("Region scope: \(title)")
    }

    private var regionButtons: some View {
        ForEach(regions, id: \.id) { item in
            button(item) { selectRegion(item.id) }
        }
    }

    private func button(_ item: Item, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            if item.isSelected {
                Label(item.title, systemImage: "checkmark")
            } else {
                Text(item.title)
            }
        }
    }
}

private struct MapRouteHop: Identifiable {
    let id: String
    let position: Int
    let title: String
    let publicKey: String?
    let node: MeshNode?
}

private struct MapRouteDetailsSheet: View {
    let route: MapRouteDetails

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Summary") {
                    LabeledContent {
                        Text("\(route.hops.count)")
                    } label: {
                        LearnLabel("Hops", term: .hop)
                    }
                    LabeledContent("Age") {
                        Text(route.observedAt, style: .relative)
                    }
                    if let observerName = route.observerName, !observerName.isEmpty {
                        LabeledContent {
                            Text(observerName)
                                .font(.monaspace(.body))
                        } label: {
                            LearnLabel("Observer", term: .observer)
                        }
                    }
                    if let snr = route.snr {
                        LabeledContent {
                            Text("\(snr, format: .number.precision(.fractionLength(1))) dB")
                        } label: {
                            LearnLabel("SNR", term: .snr)
                        }
                    }
                    if let rssi = route.rssi {
                        LabeledContent {
                            Text("\(rssi, format: .number.precision(.fractionLength(0))) dBm")
                        } label: {
                            LearnLabel("RSSI", term: .rssi)
                        }
                    }
                    if let packetHash = route.packetHash, !packetHash.isEmpty {
                        LabeledContent("Packet", value: String(packetHash.prefix(12)).uppercased())
                    }
                }
                .instrumentListRows()

                if let messageText = route.messageText?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !messageText.isEmpty {
                    MapRouteMessageSection(sender: route.sender, messageText: messageText)
                }

                Section {
                    ForEach(route.hops) { hop in
                        if let node = hop.node {
                            NavigationLink {
                                NodeDetailScreen(node: node)
                            } label: {
                                MapRouteHopRow(hop: hop, showsDisclosureIndicator: false)
                            }
                        } else {
                            MapRouteHopRow(hop: hop, showsDisclosureIndicator: false)
                        }
                    }
                } header: {
                    LearnLabel("Route", term: .route)
                }
                .instrumentListRows()
            }
            .meshPageBackground()
            .navigationTitle("Route Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    ShareLink(
                        item: route.shareText,
                        subject: Text("NodeScope Route"),
                        message: Text("Route details from NodeScope")
                    ) {
                        Label("Share Route", systemImage: "square.and.arrow.up")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct MapRouteMessageSection: View {
    let sender: String?
    let messageText: String

    @State private var isExpanded = false

    var body: some View {
        Section("Message") {
            VStack(alignment: .leading, spacing: 6) {
                if let sender, !sender.isEmpty {
                    Text(sender)
                        .font(.monaspace(.subheadline).weight(.semibold))
                }
                Text(messageText)
                    .lineLimit(isExpanded ? nil : 4)
                    .textSelection(.enabled)
                    .contextMenu {
                        Button("Copy Message", systemImage: "doc.on.doc") {
                            UIPasteboard.general.string = messageText
                        }
                    }

                if messageText.count > 180 {
                    Button(isExpanded ? "Show Less" : "Show More") {
                        isExpanded.toggle()
                    }
                    .font(.caption.weight(.semibold))
                }
            }
            .padding(.vertical, 2)
        }
        .instrumentListRows()
    }
}

private struct MapRouteHopRow: View {
    let hop: MapRouteHop
    let showsDisclosureIndicator: Bool

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        HStack(spacing: 12) {
            Text("\(hop.position)")
                .font(.caption.bold())
                .foregroundStyle(.black)
                .frame(width: 26, height: 26)
                .background(NodeScopeStyle.signal, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(hop.title)
                    .foregroundStyle(.primary)
                if let publicKey = hop.publicKey, !dynamicTypeSize.isAccessibilitySize {
                    Text(publicKey)
                        .font(.monaspace(.caption))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            if showsDisclosureIndicator {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Hop \(hop.position), \(hop.title)")
    }
}

private struct MapObserverOption: Identifiable, Hashable {
    let id: String
    let title: String
    let iata: String?
}

private struct MapNodeFilterSelection: Codable, Equatable {
    var selectedRoles: Set<String> = []
    var activityFilter: MapNodeActivityFilter = .all
    var selectedObserverID: String?

    var isFiltering: Bool {
        !selectedRoles.isEmpty || activityFilter != .all || selectedObserverID != nil
    }

    var activeFilterCount: Int {
        (selectedRoles.isEmpty ? 0 : 1)
            + (activityFilter == .all ? 0 : 1)
            + (selectedObserverID == nil ? 0 : 1)
    }

    static func persisted(for analyzerHost: String) -> Self {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey(for: analyzerHost)),
              let selection = try? JSONDecoder().decode(Self.self, from: data) else {
            return Self()
        }
        return selection
    }

    func persist(for analyzerHost: String) {
        let key = Self.defaultsKey(for: analyzerHost)
        guard isFiltering else {
            UserDefaults.standard.removeObject(forKey: key)
            return
        }
        guard let data = try? JSONEncoder().encode(self) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    private static func defaultsKey(for analyzerHost: String) -> String {
        let normalizedHost = analyzerHost.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let hostIdentifier = Data(normalizedHost.utf8).base64EncodedString()
        return "mapNodeFilters.\(hostIdentifier)"
    }
}

private enum MapNodeActivityFilter: String, CaseIterable, Codable, Identifiable {
    case all
    case fifteenMinutes
    case oneHour
    case oneDay
    case oneWeek

    var id: String { rawValue }

    /// Read after "Last Heard" in the Map Filters menu.
    var lastHeardTitle: LocalizedStringKey {
        switch self {
        case .all: "Any Time"
        case .fifteenMinutes: "Within 15 Minutes"
        case .oneHour: "Within 1 Hour"
        case .oneDay: "Within 24 Hours"
        case .oneWeek: "Within 7 Days"
        }
    }

    var maximumAge: TimeInterval? {
        switch self {
        case .all: nil
        case .fifteenMinutes: 15 * 60
        case .oneHour: 60 * 60
        case .oneDay: 24 * 60 * 60
        case .oneWeek: 7 * 24 * 60 * 60
        }
    }
}

private struct MapNodeFilterButton: View {
    let activeFilterCount: Int
    let action: () -> Void

    private var isFiltering: Bool {
        activeFilterCount > 0
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: isFiltering
                    ? "line.3.horizontal.decrease.circle.fill"
                    : "line.3.horizontal.decrease.circle"
                )
                if isFiltering {
                    Text("\(activeFilterCount) active")
                }
            }
            .font(.body.weight(.semibold))
            .foregroundStyle(.primary)
            .frame(minWidth: 34, minHeight: 34)
            .padding(.horizontal, isFiltering ? 8 : 0)
            .nodeScopeFloatingGlass(cornerRadius: 20)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Map filters")
        .accessibilityValue(isFiltering ? "\(activeFilterCount) active" : "None")
    }
}

private struct MapKeyButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "info.circle")
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .frame(minWidth: 34, minHeight: 34)
                .nodeScopeFloatingGlass(cornerRadius: 20)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Map key")
        .accessibilityHint("Explains the markers, lines and colors on the map")
    }
}

/// What the map's markers, lines and colours mean. Opened from the map's ⓘ and from Map Filters,
/// so there's one key however it's reached. The symbols are the map's own, at map size.
private struct MapKeyScreen: View {
    @Environment(\.colorScheme) private var colorScheme

    private static let roles: [(role: String, title: String, summary: String, term: GlossaryTerm)] = [
        ("repeater", "Repeater", "Relays packets so they travel further.", .repeater),
        ("room", "Room", "Hosts a shared message board.", .roomServer),
        ("companion", "Companion", "Someone's radio, paired with an app.", .companion),
        ("sensor", "Sensor", "Reports readings over the mesh.", .sensor)
    ]

    var body: some View {
        List {
            Section("Node Roles") {
                ForEach(Self.roles, id: \.role) { item in
                    MapKeyRow(title: LocalizedStringKey(item.title), summary: LocalizedStringKey(item.summary), term: item.term) {
                        MapNodeIndicator(color: NodeRoleStyle.color(for: item.role), isHighlighted: false, isRouteHop: true)
                    }
                }
            }
            .instrumentListRows()

            Section("On the Map") {
                MapKeyRow(title: "Observer", summary: "Heard the packet in a replay. A ring shows the node it's on.", term: .observer) {
                    ReplayReceiverMarker(name: "", nodeColor: NodeRoleStyle.color(for: "repeater"),
                                         showsName: false, colorScheme: colorScheme)
                }
                MapKeyRow(title: "Grouped Nodes", summary: "Nearby nodes when zoomed out. Tap to zoom in.") {
                    Text("12")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: 20, height: 20)
                        .background(.blue, in: Circle())
                        .overlay { Circle().stroke(.white.opacity(0.8), lineWidth: 1) }
                }
                MapKeyRow(title: "Spread-Out Nodes", summary: "Shown apart so each can be tapped. The lines lead to where they are.") {
                    MapKeySpreadSymbol()
                }
                MapKeyRow(title: "Live Packets", summary: "Each packet has its own color, on every path it took.") {
                    MapKeyLinesSymbol(colors: [
                        ActivePing.color(forHash: "a1", colorScheme: colorScheme),
                        ActivePing.color(forHash: "b7", colorScheme: colorScheme)
                    ], curved: false)
                }
                MapKeyRow(title: "Replayed Route", summary: "A packet's path, played hop by hop.", term: .route) {
                    MapKeyLinesSymbol(colors: [colorScheme == .dark ? Color(red: 0.35, green: 0.78, blue: 1.0) : .orange],
                                      curved: true)
                }
                MapKeyRow(title: "You", summary: "Your location, when you share it.") {
                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: 12, height: 12)
                        .overlay { Circle().stroke(.white, lineWidth: 2) }
                        .background { Circle().fill(Color.accentColor.opacity(0.22)).frame(width: 26, height: 26) }
                }
            }
            .instrumentListRows()
        }
        .meshPageBackground()
        .navigationTitle("Map Key")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct MapKeyRow<Symbol: View>: View {
    let title: LocalizedStringKey
    let summary: LocalizedStringKey
    var term: GlossaryTerm?
    @ViewBuilder let symbol: () -> Symbol

    var body: some View {
        HStack(spacing: 14) {
            symbol()
                .frame(width: 34, height: 30)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(summary)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let term {
                LearnButton(term: term)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

/// Two fanned markers and their dashed lines to the group's hub, as drawn on the map.
private struct MapKeySpreadSymbol: View {
    var body: some View {
        Canvas { context, size in
            let hub = CGPoint(x: size.width / 2, y: size.height * 0.72)
            let left = CGPoint(x: size.width * 0.2, y: size.height * 0.3)
            let right = CGPoint(x: size.width * 0.8, y: size.height * 0.3)
            for (point, color) in [(left, NodeRoleStyle.color(for: "repeater")), (right, NodeRoleStyle.color(for: "room"))] {
                var line = Path()
                line.move(to: point)
                line.addLine(to: hub)
                context.stroke(line, with: .color(color.opacity(0.85)),
                               style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [3, 2.5]))
            }
            let hubDot = Path(ellipseIn: CGRect(x: hub.x - 3, y: hub.y - 3, width: 6, height: 6))
            context.fill(hubDot, with: .color(.white))
            context.stroke(hubDot, with: .color(Color(white: 0.25).opacity(0.85)), lineWidth: 1.25)
            for (point, color) in [(left, NodeRoleStyle.color(for: "repeater")), (right, NodeRoleStyle.color(for: "room"))] {
                let dot = Path(ellipseIn: CGRect(x: point.x - 5, y: point.y - 5, width: 10, height: 10))
                context.fill(dot, with: .color(color))
                context.stroke(dot, with: .color(.white.opacity(0.9)), lineWidth: 1)
            }
        }
    }
}

/// Short packet paths with the travelling signal dot, as drawn on the map.
private struct MapKeyLinesSymbol: View {
    let colors: [Color]
    let curved: Bool

    var body: some View {
        Canvas { context, size in
            for (index, color) in colors.enumerated() {
                let y = colors.count == 1 ? size.height / 2 : size.height * (index == 0 ? 0.32 : 0.72)
                let start = CGPoint(x: 2, y: y + (curved ? 4 : 2))
                let end = CGPoint(x: size.width - 4, y: y - (curved ? 4 : 2))
                var path = Path()
                path.move(to: start)
                if curved {
                    path.addQuadCurve(to: end, control: CGPoint(x: size.width / 2, y: y - 8))
                } else {
                    path.addLine(to: end)
                }
                context.stroke(path, with: .color(color.opacity(0.18)), style: StrokeStyle(lineWidth: 6, lineCap: .round))
                context.stroke(path, with: .color(color.opacity(0.85)), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                let dot = Path(ellipseIn: CGRect(x: end.x - 3.5, y: end.y - 3.5, width: 7, height: 7))
                context.fill(dot, with: .color(.white))
                context.stroke(dot, with: .color(color), lineWidth: 1.5)
            }
        }
    }
}

private struct MapNodeFilterSheet: View {
    @Binding var filters: MapNodeFilterSelection
    let observers: [MapObserverOption]

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var isObserverPickerPresented = false

    private var selectedObserverTitle: String {
        guard let selectedObserverID = filters.selectedObserverID else {
            return "All Observers"
        }
        return observers.first(where: { $0.id == selectedObserverID })?.title ?? "Selected Observer"
    }

    var body: some View {
        NavigationStack {
            // Grouped by what each part changes: which nodes are drawn, then which routes. What the
            // map's symbols mean is on its own Map Key screen, also opened from the map's ⓘ.
            List {
                MapNodesShownSection(selection: $filters.activityFilter)
                MapRoleFilterSection(selectedRoles: $filters.selectedRoles)
                MapObserverFilterSummarySection(
                    selectedObserverTitle: selectedObserverTitle,
                    action: { isObserverPickerPresented = true }
                )

                if filters.isFiltering {
                    Section {
                        Button("Reset All Filters", systemImage: "arrow.counterclockwise") {
                            filters = MapNodeFilterSelection()
                        }
                    }
                    .instrumentListRows()
                }

                Section {
                    NavigationLink {
                        MapKeyScreen()
                    } label: {
                        Label("Map Key", systemImage: "info.circle")
                            .foregroundStyle(.primary)
                    }
                } footer: {
                    Text("What the markers, lines and colors on the map mean.")
                }
                .instrumentListRows()
            }
            .meshPageBackground()
            .navigationTitle("Map Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .sheet(isPresented: $isObserverPickerPresented) {
                MapObserverPickerSheet(
                    selectedObserverID: $filters.selectedObserverID,
                    observers: observers
                )
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
        }
        .tint(colorScheme == .dark ? Color.white : Color.accentColor)
    }
}

/// Which nodes are drawn, by when they were last heard. It filters node markers only, which
/// "Activity" didn't make clear: people took it for a packet filter.
private struct MapNodesShownSection: View {
    @Binding var selection: MapNodeActivityFilter

    var body: some View {
        Section {
            Picker(selection: $selection) {
                ForEach(MapNodeActivityFilter.allCases) { filter in
                    Text(filter.lastHeardTitle).tag(filter)
                }
            } label: {
                Label("Last Heard", systemImage: "clock")
                    .foregroundStyle(.primary)
            }
            .pickerStyle(.menu)
        } header: {
            Text("Nodes Shown")
        } footer: {
            Text("Hides nodes that haven't been heard from in this time. Live traffic and replays aren't affected.")
        }
        .instrumentListRows()
    }
}

/// Node roles to draw, each its own checkmark (no selection stored means all). The colour dots
/// match the map's markers.
private struct MapRoleFilterSection: View {
    @Binding var selectedRoles: Set<String>

    static let roles = ["repeater", "room", "companion", "sensor"]

    private func isShown(_ role: String) -> Bool {
        selectedRoles.isEmpty || selectedRoles.contains(role)
    }

    var body: some View {
        Section {
            ForEach(Self.roles, id: \.self) { role in
                Button {
                    toggle(role)
                } label: {
                    MapRoleFilterRow(title: role.capitalized, role: role, isShown: isShown(role))
                }
                .accessibilityAddTraits(isShown(role) ? .isSelected : [])
            }
        } header: {
            HStack {
                Text("Node Roles")
                Spacer()
                if !selectedRoles.isEmpty {
                    Button("Show All") { selectedRoles = [] }
                        .font(.footnote.weight(.semibold))
                        .textCase(nil)
                }
            }
        } footer: {
            Text("Colors match the dots on the map.")
        }
        .instrumentListRows()
    }

    /// Showing every role is stored as no selection; the last shown role can't be turned off.
    private func toggle(_ role: String) {
        var shown = selectedRoles.isEmpty ? Set(Self.roles) : selectedRoles
        if shown.contains(role) {
            guard shown.count > 1 else { return }
            shown.remove(role)
        } else {
            shown.insert(role)
        }
        selectedRoles = shown == Set(Self.roles) ? [] : shown
    }
}

private struct MapNodeIndicator: View {
    let color: Color
    let isHighlighted: Bool
    let isRouteHop: Bool

    var body: some View {
        Circle()
            .fill(color)
            .frame(
                width: isHighlighted ? 18 : (isRouteHop ? 12 : 10),
                height: isHighlighted ? 18 : (isRouteHop ? 12 : 10)
            )
            .overlay {
                Circle()
                    .stroke(
                        .white.opacity(0.9),
                        lineWidth: isHighlighted ? 3 : (isRouteHop ? 2 : 1)
                    )
            }
            .shadow(
                color: isHighlighted ? NodeScopeStyle.signal.opacity(0.65) : .clear,
                radius: 8
            )
    }
}

private struct MapRoleFilterRow: View {
    let title: String
    let role: String
    let isShown: Bool

    var body: some View {
        HStack(spacing: 12) {
            MapRoleColorSwatch(roles: [role])
            Text(title)
                .foregroundStyle(.primary)
            Spacer()
            Image(systemName: isShown ? "checkmark.circle.fill" : "circle")
                .font(.title3)
                .foregroundStyle(isShown ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                .accessibilityHidden(true)
        }
    }
}

private struct MapRoleColorSwatch: View {
    let roles: [String]

    var body: some View {
        HStack(spacing: -4) {
            ForEach(roles, id: \.self) { role in
                Circle()
                    .fill(NodeRoleStyle.color(for: role))
                    .frame(width: 10, height: 10)
                    .overlay {
                        Circle()
                            .stroke(.white.opacity(0.9), lineWidth: 1)
                    }
            }
        }
        .frame(width: 26, alignment: .leading)
        .accessibilityHidden(true)
    }
}

private struct MapObserverFilterSummarySection: View {
    let selectedObserverTitle: String
    let action: () -> Void

    var body: some View {
        Section {
            Button(action: action) {
                HStack {
                    Label("Heard By", systemImage: "antenna.radiowaves.left.and.right")
                        .foregroundStyle(.primary)
                    Spacer()
                    Text(selectedObserverTitle)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
        } header: {
            Text("Routes")
        } footer: {
            Text("Shows only packets received by the chosen observer. Node markers follow the filters above.")
        }
        .instrumentListRows()
    }
}

private struct MapObserverPickerSheet: View {
    @Binding var selectedObserverID: String?
    let observers: [MapObserverOption]

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var matchingObservers: [MapObserverOption] {
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedQuery.isEmpty else { return observers }
        return observers.filter { observer in
            observer.title.localizedCaseInsensitiveContains(normalizedQuery)
                || observer.id.localizedCaseInsensitiveContains(normalizedQuery)
                || observer.iata?.localizedCaseInsensitiveContains(normalizedQuery) == true
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Button {
                    select(nil)
                } label: {
                    observerRow(
                        title: "All Observers",
                        subtitle: "Show routes received by any observer",
                        isSelected: selectedObserverID == nil
                    )
                }
                .instrumentListRows()

                ForEach(matchingObservers) { observer in
                    Button {
                        select(observer.id)
                    } label: {
                        observerRow(
                            title: observer.title,
                            subtitle: observer.iata,
                            isSelected: selectedObserverID == observer.id
                        )
                    }
                }
                .instrumentListRows()
            }
            .meshPageBackground()
            .navigationTitle("Route Observer")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "Name, ID, or region")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func select(_ observerID: String?) {
        selectedObserverID = observerID
        dismiss()
    }

    private func observerRow(title: String, subtitle: String?, isSelected: Bool) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundStyle(.primary)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if isSelected {
                Image(systemName: "checkmark")
                    .foregroundStyle(.primary)
            }
        }
        .contentShape(Rectangle())
    }
}

private struct MapNodeSearchSheet: View {
    let nodes: [MeshNode]
    let selectNode: (MeshNode) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [MeshNode] = []
    @State private var selectedRoles: Set<String> = []

    private let roles = ["repeater", "room", "companion", "sensor"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                InstrumentSearchField(text: $query, prompt: "Name, key, or role")
                    .padding(.horizontal, 16)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        MapRoleFilterChip(
                            title: "All",
                            symbol: "circle.grid.2x2.fill",
                            color: NodeScopeStyle.signal,
                            isSelected: selectedRoles.isEmpty,
                            action: { selectedRoles = [] }
                        )
                        ForEach(roles, id: \.self) { role in
                            MapRoleFilterChip(
                                title: role.capitalized,
                                symbol: NodeRoleStyle.symbolName(for: role),
                                color: NodeRoleStyle.color(for: role),
                                isSelected: selectedRoles.contains(role),
                                action: { toggleRole(role) }
                            )
                        }
                    }
                    .padding(.horizontal, 16)
                }

                if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && selectedRoles.isEmpty {
                    ContentUnavailableView(
                        "Find a Node",
                        systemImage: "magnifyingglass",
                        description: Text("Search by node name, public key, or role.")
                    )
                } else if results.isEmpty {
                    ContentUnavailableView.search(text: query)
                } else {
                    List(results) { node in
                        Button {
                            dismiss()
                            selectNode(node)
                        } label: {
                            MapNodeSearchRow(
                                name: node.name ?? "Unnamed Node",
                                role: node.role,
                                publicKey: node.publicKey,
                                lastSeen: node.latestActivity
                            )
                        }
                        .buttonStyle(.plain)
                    }
                    .listStyle(.plain)
                }
            }
            .padding(.top, 8)
            .meshPageBackground()
            .navigationTitle("Search Nodes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear(perform: updateResults)
            .onChange(of: query) {
                updateResults()
            }
            .onChange(of: nodes) {
                updateResults()
            }
            .onChange(of: selectedRoles) {
                updateResults()
            }
        }
    }

    private func updateResults() {
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedQuery.isEmpty || !selectedRoles.isEmpty else {
            results = []
            return
        }

        results = nodes.lazy.filter { node in
            guard let coordinate = node.coordinate,
                  coordinate.latitude != 0 || coordinate.longitude != 0 else {
                return false
            }
            return normalizedQuery.isEmpty
                || node.name?.localizedCaseInsensitiveContains(normalizedQuery) == true
                || node.publicKey.localizedCaseInsensitiveContains(normalizedQuery)
                || node.role.localizedCaseInsensitiveContains(normalizedQuery)
        }
        .filter { selectedRoles.isEmpty || selectedRoles.contains($0.role) }
        .sorted { lhs, rhs in
            let lhsName = lhs.name ?? lhs.publicKey
            let rhsName = rhs.name ?? rhs.publicKey
            return lhsName.localizedCaseInsensitiveCompare(rhsName) == .orderedAscending
        }
    }

    private func toggleRole(_ role: String) {
        if selectedRoles.contains(role) {
            selectedRoles.remove(role)
        } else {
            selectedRoles.insert(role)
        }
    }
}

private struct MapRoleFilterChip: View {
    let title: String
    let symbol: String
    let color: Color
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.caption.weight(.semibold))
                .foregroundStyle(isSelected ? .white : color)
                .padding(.horizontal, 11)
                .padding(.vertical, 8)
                .background(isSelected ? color : color.opacity(0.11), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

private struct MapNodeSearchRow: View {
    let name: String
    let role: String
    let publicKey: String
    let lastSeen: Date?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: NodeRoleStyle.symbolName(for: role))
                .font(.body.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 38, height: 38)
                .background(NodeRoleStyle.color(for: role), in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(name)
                    .font(.monaspace(.headline))
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(role.capitalized)
                    Text("·")
                    Text(publicKey.prefix(10).uppercased())
                        .font(.monaspace(.caption))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            if let lastSeen {
                Text(lastSeen, style: .relative)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            } else {
                Text("Unknown")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(Rectangle())
        .padding(.vertical, 4)
    }
}

/// An observer that heard a replayed route, where the replay ends.
private struct ReplayReceiver: Identifiable {
    let name: String
    let coordinate: CLLocationCoordinate2D
    /// The role of a node at the observer's location (usually its own repeater or companion),
    /// drawn as a ring around the observer instead of a separate dot on top of it.
    var nodeRole: String? = nil
    /// The nodes the observer stands for, hidden while it's shown.
    var nodeIDs: [String] = []

    var id: String { "\(name)|\(coordinate.latitude)|\(coordinate.longitude)" }
}

/// The labelled endpoint of a replay: an observer marker with its name always shown.
private struct ReplayReceiverMarker: View {
    let name: String
    /// The role colour of a node at the observer's location, shown as a ring around it.
    let nodeColor: Color?
    /// Hidden when zoomed out, where names crowd the map.
    let showsName: Bool
    let colorScheme: ColorScheme

    var body: some View {
        Image(systemName: "antenna.radiowaves.left.and.right")
            .font(.system(size: 7, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: 15, height: 15)
            .background(NodeScopeStyle.healthy, in: Circle())
            .overlay {
                Circle().stroke(.white.opacity(0.9), lineWidth: 1.2)
            }
            .overlay {
                if let nodeColor {
                    Circle()
                        .stroke(nodeColor, lineWidth: 2)
                        .frame(width: 18.2, height: 18.2)
                }
            }
            .overlay(alignment: .top) {
                if showsName {
                    Text(name)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(
                            colorScheme == .dark ? Color.black.opacity(0.72) : Color.white.opacity(0.85),
                            in: Capsule()
                        )
                        .offset(y: nodeColor == nil ? 18 : 19)
                }
            }
            .allowsHitTesting(false)
            .accessibilityElement()
            .accessibilityLabel("Heard by \(name)")
    }
}

/// How a map opens on the selected region, shared by the live map and Link Profile so both
/// frame a region the same way.
enum RegionCameraFraming {
    /// The span that shows about [radiusKm] around [center], never closer than a small town.
    static func span(around center: CLLocationCoordinate2D, radiusKm: Double) -> MKCoordinateSpan {
        let latitudeSpan = max((radiusKm * 2.4) / 111, 0.15)
        let longitudeSpan = max(latitudeSpan / max(cos(center.latitude * .pi / 180), 0.2), 0.15)
        return MKCoordinateSpan(latitudeDelta: latitudeSpan, longitudeDelta: longitudeSpan)
    }

    /// The community registry's viewport for this analyzer, if it publishes one.
    @MainActor
    static func communityViewport(registry: AnalyzerSourceRegistry, host: String) -> (center: CLLocationCoordinate2D, radiusKm: Double)? {
        guard let source = registry.sources.first(where: { $0.host.caseInsensitiveCompare(host) == .orderedSame }),
              let latitude = source.mapLatitude,
              let longitude = source.mapLongitude else { return nil }
        let center = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        guard CLLocationCoordinate2DIsValid(center) else { return nil }
        return (center, source.mapRadiusKm ?? 250)
    }

    /// Every region the analyzer offers, from published centers or the bundled airport table.
    @MainActor
    static func allRegionsViewport(regionFilter: RegionFilterStore) -> BeaconRegionReader.Frame? {
        BeaconRegionReader.Frame.enclosing(coordinates(codes: regionFilter.options, regionFilter: regionFilter))
    }

    /// A quick pick's area: each region's full view (its published radius, else 45 km, exactly
    /// as when that region is selected alone), combined. Framing only the centers cut off
    /// the parts of a city beyond its airport, such as south San Antonio.
    @MainActor
    static func areaFrame(codes: [String], regionFilter: RegionFilterStore) -> BeaconRegionReader.Frame? {
        let boxes = coordinates(codes: codes, regionFilter: regionFilter).compactMap { point -> (north: Double, south: Double, east: Double, west: Double)? in
            let center = CLLocationCoordinate2D(latitude: point.lat, longitude: point.lon)
            guard CLLocationCoordinate2DIsValid(center) else { return nil }
            let radius = point.radiusKm.flatMap { $0.isFinite && $0 > 0 ? min($0, 2000) : nil } ?? 45
            let span = span(around: center, radiusKm: radius)
            return (center.latitude + span.latitudeDelta / 2, center.latitude - span.latitudeDelta / 2,
                    center.longitude + span.longitudeDelta / 2, center.longitude - span.longitudeDelta / 2)
        }
        guard let north = boxes.map(\.north).max(), let south = boxes.map(\.south).min(),
              let east = boxes.map(\.east).max(), let west = boxes.map(\.west).min() else { return nil }
        return BeaconRegionReader.Frame(
            latitude: (north + south) / 2, longitude: (east + west) / 2,
            latitudeDelta: min(180, north - south), longitudeDelta: min(360, east - west)
        )
    }

    /// Centers for [codes]: the analyzer's published ones, else the bundled airport table.
    @MainActor
    private static func coordinates(codes: [String], regionFilter: RegionFilterStore) -> [IataCoordinate] {
        codes.compactMap { code -> IataCoordinate? in
            if let published = regionFilter.iataCoords.first(where: {
                $0.key.caseInsensitiveCompare(code) == .orderedSame
            })?.value {
                return published
            }
            guard let fallback = AirportCoordinates.coordinate(for: code) else { return nil }
            return IataCoordinate(lat: fallback.latitude, lon: fallback.longitude, radiusKm: nil)
        }
    }

    /// The opening view without network lookups: a selected region's published center (and
    /// radius) or its airport, else the community viewport, else every offered region. Nil
    /// when nothing is known yet.
    @MainActor
    static func openingRegion(regionFilter: RegionFilterStore, registry: AnalyzerSourceRegistry, host: String) -> MKCoordinateRegion? {
        if let codes = regionFilter.selectedCodes, codes.count > 1 {
            return areaFrame(codes: codes.sorted(), regionFilter: regionFilter).map(region(for:))
        }
        if let selected = regionFilter.selectedRegion?.uppercased() {
            if let published = regionFilter.iataCoords.first(where: { $0.key.uppercased() == selected })?.value {
                let center = CLLocationCoordinate2D(latitude: published.lat, longitude: published.lon)
                if CLLocationCoordinate2DIsValid(center) {
                    let radius = published.radiusKm.flatMap { $0.isFinite && $0 > 0 ? min($0, 2000) : nil } ?? 45
                    return MKCoordinateRegion(center: center, span: span(around: center, radiusKm: radius))
                }
            }
            if let airport = AirportCoordinates.coordinate(for: selected) {
                return MKCoordinateRegion(center: airport, span: span(around: airport, radiusKm: 45))
            }
            return nil
        }
        if let viewport = communityViewport(registry: registry, host: host) {
            return MKCoordinateRegion(center: viewport.center, span: span(around: viewport.center, radiusKm: viewport.radiusKm))
        }
        return allRegionsViewport(regionFilter: regionFilter).map(region(for:))
    }

    private static func region(for frame: BeaconRegionReader.Frame) -> MKCoordinateRegion {
        MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: frame.latitude, longitude: frame.longitude),
            span: MKCoordinateSpan(latitudeDelta: frame.latitudeDelta, longitudeDelta: frame.longitudeDelta)
        )
    }
}

/// The map styles offered on the live map and in Link Profile; the choice is shared.
enum MapDisplayStyle: String, CaseIterable, Identifiable {
    case standard
    case imagery
    case hybrid

    static let defaultsKey = "mapDisplayStyle"

    static var persisted: Self {
        guard let rawValue = UserDefaults.standard.string(forKey: defaultsKey),
              let style = Self(rawValue: rawValue) else {
            return .standard
        }
        return style
    }

    var id: String { rawValue }

    var title: String {
        switch self {
        case .standard: "Standard"
        case .imagery: "Satellite"
        case .hybrid: "Hybrid"
        }
    }

    var systemImage: String {
        switch self {
        case .standard: "map"
        case .imagery: "globe.americas.fill"
        case .hybrid: "map.fill"
        }
    }

    /// Link Profile's styles: 3D terrain on the standard map. Imagery stays flat: there MapKit
    /// drapes overlays over the 3D ground and draws them about 2.5 times too thick, which ran the
    /// route's dashes together.
    var realisticStyle: MapStyle {
        switch self {
        case .standard: .standard(elevation: .realistic)
        case .imagery: .imagery
        case .hybrid: .hybrid
        }
    }

    var style: MapStyle {
        switch self {
        case .standard: .standard
        case .imagery: .imagery
        case .hybrid: .hybrid
        }
    }
}
