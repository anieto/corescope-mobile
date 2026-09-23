import MapKit
import SwiftUI
import UIKit

struct MapScreen: View {
    let isTabActive: Bool
    let resetID: UUID

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @Environment(PacketReplayStore.self) private var packetReplayStore
    @Environment(AppNavigationStore.self) private var appNavigationStore
    @Environment(AnalyzerSourceRegistry.self) private var analyzerSourceRegistry
    @Environment(\.colorScheme) private var colorScheme
    @State private var viewModel = MapViewModel()
    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var visibleRegion: MKCoordinateRegion?
    @State private var selectedNode: MeshNode?
    @State private var displayedNodes: [MeshNode] = []
    @State private var nodeClusters: [NodeCluster] = []
    @State private var visibleNodesByCoordinate: [CoordinateKey: MeshNode] = [:]
    @State private var iataCoordinates: [String: CLLocationCoordinate2D] = [:]
    @State private var mapDisplayStyle = MapDisplayStyle.persisted
    @State private var nodeFilters = MapNodeFilterSelection()
    @State private var filteredNodeCount = 0
    @State private var locationManager = MapLocationManager()
    @State private var userLocation: CLLocationCoordinate2D?
    @Namespace private var mapScope
    @State private var hasCenteredCamera = false
    @State private var activePings: [ActivePing] = []
    @State private var replayPings: [ActivePing] = []
    @State private var isReplayMode = false
    @State private var showsReplayRouteOnly = true
    @State private var processedEventIds: Set<String> = []
    @State private var isInitialLoadComplete = false
    @State private var isChangingRegion = false
    @State private var isLocatingUser = false
    @State private var isRefreshingMap = false
    @State private var pendingReplayRequestID: UUID?
    @State private var loadedAnalyzerHost = ""
    @State private var displayUpdateTask: Task<Void, Never>?
    @State private var displayUpdateID = UUID()
    @State private var isUpdatingDisplayedNodes = false
    @State private var isSearchPresented = false
    @State private var isMapFiltersPresented = false
    @State private var selectedRouteDetails: MapRouteDetails?
    @State private var highlightedNodeID: String?
    @State private var lastHandledNavigationRequestID: UUID?

    // The map remains edge-to-edge, while interactive controls sit above the
    // app-level floating dock rendered by RootTabView.
    private let floatingDockClearance: CGFloat = 96

    private struct NodeCluster: Identifiable, Sendable {
        let id: String
        let coordinate: CLLocationCoordinate2D
        let count: Int
        let memberCoordinates: Set<CoordinateKey>
    }

    private struct CoordinateKey: Hashable, Sendable {
        let latitude: Double
        let longitude: Double

        init(_ coordinate: CLLocationCoordinate2D) {
            latitude = coordinate.latitude
            longitude = coordinate.longitude
        }
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
        let nodesByCoordinate: [CoordinateKey: MeshNode]
        let filteredCount: Int
    }

    private enum MapDisplayStyle: String, CaseIterable, Identifiable {
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

        var style: MapStyle {
            switch self {
            case .standard: .standard
            case .imagery: .imagery
            case .hybrid: .hybrid
            }
        }
    }

    var body: some View {
        NavigationStack {
            TimelineView(
                .animation(
                    minimumInterval: mapUpdateInterval,
                    paused: pingsForDisplay.isEmpty || !isTabActive
                )
            ) { context in
                mapContent(at: context.date)
            }
                .mapScope(mapScope)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(liveFeed.isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                                .frame(width: 7, height: 7)
                            Text("Live Map")
                                .font(.headline)
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
                .navigationDestination(item: $selectedNode) { node in
                    NodeDetailScreen(node: node)
                }
                .overlay(alignment: .topLeading) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) {
                            MapRegionScopeControl()
                            MapNodeFilterButton(
                                activeFilterCount: nodeFilters.activeFilterCount,
                                action: { isMapFiltersPresented = true }
                            )
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
                    .padding(.leading, 12)
                    .padding(.top, 12)
                }
                .overlay(alignment: .bottomLeading) {
                    Button(action: centerOnUserLocation) {
                        Image(systemName: "location.fill")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(colorScheme == .dark ? Color.white : Color.accentColor)
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .nodeScopeFloatingGlass(cornerRadius: 10)
                    .padding(.leading, 12)
                    .padding(.bottom, floatingDockClearance)
                    .accessibilityLabel("Center on my location")
                }
                .overlay(alignment: .bottomTrailing) {
                    zoomControl
                        .padding(.trailing, 12)
                        .padding(.bottom, floatingDockClearance)
                }
                .overlay(alignment: .bottom) {
                    VStack(spacing: 8) {
                        if isReplayMode {
                            VStack(spacing: 8) {
                                replayControl
                                routeOptionsControl
                            }
                        }
                        if !viewModel.nodes.isEmpty {
                            Text("\(filteredNodeCount) nodes")
                                .font(.caption)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(.thinMaterial, in: Capsule())
                        }
                    }
                    .padding(.bottom, floatingDockClearance)
                }
                .overlay {
                    if let errorMessage = viewModel.errorMessage, viewModel.nodes.isEmpty {
                        ContentUnavailableView(
                            "Couldn't load nodes",
                            systemImage: "wifi.slash",
                            description: Text(errorMessage)
                        )
                    }
                }
        }
        .sheet(isPresented: $isSearchPresented) {
            MapNodeSearchSheet(nodes: viewModel.nodes, selectNode: focusOnSearchedNode)
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
            selectedNode = nil
            pendingReplayRequestID = nil
            replayPings = []
            isReplayMode = false
            showsReplayRouteOnly = true
            isSearchPresented = false
            isMapFiltersPresented = false
            selectedRouteDetails = nil
            highlightedNodeID = nil
        }
        .onDisappear {
            displayUpdateTask?.cancel()
            isUpdatingDisplayedNodes = false
        }
        .task {
            processedEventIds = Set(liveFeed.recentEvents.map { liveEventKey(for: $0) })
            isInitialLoadComplete = true
        }
        .task(id: "\(settings.host.lowercased())-\(regionFilter.selectedRegion ?? "all")") {
            let sourceHost = settings.host
            let selectedRegion = regionFilter.selectedRegion
            isChangingRegion = true
            let analyzerChanged = loadedAnalyzerHost != sourceHost

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
            processedEventIds = []

            async let minimumLoaderDuration: Void = keepRegionLoaderVisible()
            defer {
                isChangingRegion = false
                replayPendingPacketIfNeeded()
            }

            if analyzerChanged {
                loadedAnalyzerHost = sourceHost
                viewModel.resetForAnalyzerSource()
                iataCoordinates = [:]
                activePings = []
                replayPings = []
                isReplayMode = false
                hasCenteredCamera = false
                cameraPosition = .automatic
            }

            viewModel.configure(settings: settings)
            await viewModel.loadMapDefaults()

            guard !Task.isCancelled else { return }

            // A newer host or region selection started loading while this
            // request was suspended. Only the current request may move the
            // camera or replace the displayed data.
            guard sourceHost == settings.host,
                  selectedRegion == regionFilter.selectedRegion else {
                return
            }

            // These requests are independent. Starting the IATA lookup now
            // avoids making users wait for a full node refresh before the map
            // can move to their selected region.
            async let nodes: Void = viewModel.loadNodes(region: regionFilter.selectedRegion)
            async let packets: Void = viewModel.loadPackets(region: regionFilter.selectedRegion)
            async let regionZoom: Void = zoomToRegionSelection()

            // Once the camera has reached the selected IATA area, cached node
            // data is ready to use. Don't hold the map behind a loader while
            // the freshness check continues in the background.
            _ = await (regionZoom, minimumLoaderDuration)
            isChangingRegion = false
            _ = await (nodes, packets)

            // Loading a new analyzer can replace the map's annotations after
            // the first camera move. Reapply the analyzer/region camera once
            // that data settles so MapKit's automatic content fit cannot use
            // distant observed traffic as the final landing viewport.
            guard !Task.isCancelled,
                  sourceHost == settings.host,
                  selectedRegion == regionFilter.selectedRegion else {
                return
            }
            await zoomToRegionSelection()

            processHistoricalPackets()
            processIncomingEvents()
            handleNavigationRequest()
        }
        .onChange(of: liveFeed.recentEvents.first?.id) {
            if isInitialLoadComplete {
                processIncomingEvents()
            }
        }
        .onChange(of: liveFeed.recentEvents.count) {
            if isInitialLoadComplete {
                processIncomingEvents()
            }
        }
        .onChange(of: observerRegionLookup.isLoaded) { _, isLoaded in
            guard isLoaded else { return }
            validateSelectedObserver()
            rebuildPathsAfterLoadingObservers()
        }
        .onChange(of: viewModel.nodes) {
            updateDisplayedNodes()
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
            queuePacketReplay()
        }
        .onChange(of: appNavigationStore.requestID) {
            handleNavigationRequest()
        }
        .onChange(of: packetReplayStore.selectedRouteIndex) {
            guard isReplayMode else { return }
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
        isRefreshingMap = true
        Task {
            defer { isRefreshingMap = false }
            await viewModel.loadNodes(region: regionFilter.selectedRegion, forceRefresh: true)
            await viewModel.loadPackets(region: regionFilter.selectedRegion, forceRefresh: true)
            processHistoricalPackets()
            processIncomingEvents()
        }
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
        let currentPings = pingsForDisplay.filter {
            $0.hasStarted(at: date) && !$0.isExpired(at: date)
        }
        let routeCoordinates = Set(currentPings.flatMap {
            [CoordinateKey($0.start), CoordinateKey($0.end)]
        })
        let routeNodes = routeCoordinates.compactMap { visibleNodesByCoordinate[$0] }
        let baseNodes = isReplayMode && showsReplayRouteOnly ? replayRouteNodes : displayedNodes
        let mapNodes = Array(Dictionary(
            baseNodes.map { ($0.id, $0) } + routeNodes.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        ).values)
        let mapClusters = isReplayMode && showsReplayRouteOnly
            ? []
            : nodeClusters.filter { $0.memberCoordinates.isDisjoint(with: routeCoordinates) }

        MapReader { proxy in
            Map(position: $cameraPosition, scope: mapScope) {
                ForEach(mapNodes) { node in
                    if let coordinate = node.coordinate {
                        Annotation(node.name ?? shortKey(node.publicKey), coordinate: coordinate, anchor: .center) {
                            let isHighlighted = node.id == highlightedNodeID
                            let isRouteHop = routeCoordinates.contains(CoordinateKey(coordinate))
                            Button {
                                selectedNode = node
                            } label: {
                                Image(systemName: NodeRoleStyle.symbolName(for: node.role))
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .frame(
                                        width: isHighlighted ? 26 : 16,
                                        height: isHighlighted ? 26 : 16
                                    )
                                    .background(NodeRoleStyle.color(for: node.role), in: Circle())
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
                                    .frame(width: 32, height: 32)
                                    .contentShape(Circle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(node.name ?? shortKey(node.publicKey))
                            .accessibilityHint(isRouteHop ? "Opens details for this route hop" : "Opens node details")
                        }
                    }
                }

                ForEach(mapClusters) { cluster in
                    Annotation("\(cluster.count) nodes", coordinate: cluster.coordinate, anchor: .center) {
                        Text("\(cluster.count)")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(minWidth: 28, minHeight: 28)
                            .background(.blue, in: Circle())
                            .overlay {
                                Circle()
                                    .stroke(.white.opacity(0.8), lineWidth: 1)
                            }
                            .accessibilityLabel("\(cluster.count) nearby nodes")
                    }
                }

                // Keep the route visible while its packet signal travels across it.
                ForEach(currentPings.filter { !$0.isPulse }) { ping in
                    let progress = ping.progress(at: date)
                    let fadeOpacity = max(0.15, (1.0 - progress) * 0.85)
                    MapPolyline(coordinates: [ping.start, ping.end])
                        .stroke(
                            ping.pathColor.opacity(fadeOpacity),
                            style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                        )
                }

                // Stationary Breadcrumbs along active routes during travel phase
                ForEach(trailDots(at: date)) { dot in
                    Annotation("", coordinate: dot.coordinate) {
                        Circle()
                            .fill(dot.color.opacity(dot.opacity))
                            .frame(width: 6, height: 6)
                    }
                }

                // Traveling Packet Signal Dots & Single-Node Pulse Rings
                ForEach(currentPings) { ping in
                    let travelProgress = ping.travelProgress(at: date)
                    if ping.isPulse {
                        let progress = ping.progress(at: date)
                        Annotation("", coordinate: ping.start) {
                            Circle()
                                .stroke(ping.pathColor, lineWidth: 2.5)
                                .frame(width: 12 + progress * 32, height: 12 + progress * 32)
                                .opacity(1.0 - progress)
                        }
                    } else if travelProgress < 1.0 {
                        Annotation("", coordinate: ping.currentCoordinate(at: date)) {
                            ZStack {
                                Circle()
                                    .fill(ping.signalColor.opacity(0.35))
                                    .frame(width: 30, height: 30)
                                Circle()
                                    .fill(ping.signalColor)
                                    .frame(width: 16, height: 16)
                                    .shadow(color: ping.signalColor.opacity(0.95), radius: 8)
                                Circle()
                                    .fill(.white)
                                    .frame(width: 5, height: 5)
                            }
                            .opacity(1.0 - travelProgress * 0.5)
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
            .onMapCameraChange(frequency: .onEnd) { context in
                visibleRegion = context.region
                updateDisplayedNodes(in: context.region)
            }
            .onTapGesture { screenPoint in
                if let cluster = nearestCluster(to: screenPoint, using: proxy) {
                    zoomToCluster(cluster)
                } else if let route = nearestRoute(to: screenPoint, using: proxy, at: date) {
                    selectedRouteDetails = route
                } else if let node = nearestNode(to: screenPoint, using: proxy) {
                    selectedNode = node
                }
            }
        }
    }

    private func updateDisplayedNodes(in region: MKCoordinateRegion? = nil) {
        let nodes = viewModel.nodes
        let displayRegion = (region ?? visibleRegion).map(DisplayRegion.init)
        let selectedRoles = nodeFilters.selectedRoles
        let activityCutoff = nodeFilters.activityFilter.maximumAge.map {
            Date.now.addingTimeInterval(-$0)
        }
        let updateID = UUID()
        displayUpdateID = updateID
        isUpdatingDisplayedNodes = true
        displayUpdateTask?.cancel()
        displayUpdateTask = Task {
            let worker = Task.detached(priority: .userInitiated) {
                Self.makeDisplayResult(
                    nodes: nodes,
                    region: displayRegion,
                    selectedRoles: selectedRoles,
                    activityCutoff: activityCutoff
                )
            }
            let result = await withTaskCancellationHandler {
                await worker.value
            } onCancel: {
                worker.cancel()
            }
            guard !Task.isCancelled, displayUpdateID == updateID else { return }
            guard let result else {
                isUpdatingDisplayedNodes = false
                return
            }
            displayedNodes = result.nodes
            nodeClusters = result.clusters
            visibleNodesByCoordinate = result.nodesByCoordinate
            filteredNodeCount = result.filteredCount
            isUpdatingDisplayedNodes = false
        }
    }

    nonisolated private static func makeDisplayResult(
        nodes: [MeshNode],
        region: DisplayRegion?,
        selectedRoles: Set<String>,
        activityCutoff: Date?
    ) -> DisplayResult? {
        let validNodes = nodes.filter { node in
            guard !Task.isCancelled, let latitude = node.lat, let longitude = node.lon else { return false }
            let matchesRole = selectedRoles.isEmpty || selectedRoles.contains(node.role.lowercased())
            let matchesActivity = activityCutoff.map { node.lastSeen >= $0 } ?? true
            return (latitude != 0 || longitude != 0) && matchesRole && matchesActivity
        }
        guard !Task.isCancelled else { return nil }
        guard let region else {
            return DisplayResult(
                nodes: validNodes,
                clusters: [],
                nodesByCoordinate: Dictionary(
                    validNodes.compactMap { node in
                        node.coordinate.map { (CoordinateKey($0), node) }
                    },
                    uniquingKeysWith: { first, _ in first }
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

        // At close zoom levels, every node stays individually selectable.
        guard max(region.latitudeDelta, region.longitudeDelta) > 0.8 else {
            return DisplayResult(
                nodes: visibleNodes,
                clusters: [],
                nodesByCoordinate: Dictionary(
                    visibleNodes.compactMap { node in
                        node.coordinate.map { (CoordinateKey($0), node) }
                    },
                    uniquingKeysWith: { first, _ in first }
                ),
                filteredCount: validNodes.count
            )
        }

        let latitudeCellSize = region.latitudeDelta / 12
        let longitudeCellSize = region.longitudeDelta / 12
        var nodesByCell: [String: [MeshNode]] = [:]
        for node in visibleNodes {
            guard !Task.isCancelled, let latitude = node.lat, let longitude = node.lon else { return nil }
            let latitudeCell = Int(floor((latitude + 90) / latitudeCellSize))
            let longitudeCell = Int(floor((longitude + 180) / longitudeCellSize))
            nodesByCell["\(latitudeCell)-\(longitudeCell)", default: []].append(node)
        }

        var individualNodes: [MeshNode] = []
        var clusters: [NodeCluster] = []
        for (key, cellNodes) in nodesByCell {
            guard !Task.isCancelled else { return nil }
            guard cellNodes.count >= 3 else {
                individualNodes.append(contentsOf: cellNodes)
                continue
            }

            var latitudeTotal = 0.0
            var longitudeTotal = 0.0
            for node in cellNodes {
                latitudeTotal += node.lat ?? 0
                longitudeTotal += node.lon ?? 0
            }
            clusters.append(
                NodeCluster(
                    id: "cluster-\(key)",
                    coordinate: CLLocationCoordinate2D(
                        latitude: latitudeTotal / Double(cellNodes.count),
                        longitude: longitudeTotal / Double(cellNodes.count)
                    ),
                    count: cellNodes.count,
                    memberCoordinates: Set(cellNodes.compactMap { node in
                        node.coordinate.map(CoordinateKey.init)
                    })
                )
            )
        }

        return DisplayResult(
            nodes: individualNodes,
            clusters: clusters.sorted { $0.id < $1.id },
            nodesByCoordinate: Dictionary(
                visibleNodes.compactMap { node in
                    node.coordinate.map { (CoordinateKey($0), node) }
                },
                uniquingKeysWith: { first, _ in first }
            ),
            filteredCount: validNodes.count
        )
    }

    private var mapObserverOptions: [MapObserverOption] {
        let selectedRegion = regionFilter.selectedRegion?.uppercased()
        return observerRegionLookup.observers
            .filter { observer in
                selectedRegion == nil || observer.iata?.uppercased() == selectedRegion
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
            || isUpdatingDisplayedNodes
            || isLocatingUser
    }

    private var mapLoadingTitle: String {
        if isLocatingUser {
            return "Locating you…"
        }
        if isUpdatingDisplayedNodes && !viewModel.isLoading && !isChangingRegion {
            return "Updating node markers…"
        }
        if let selectedRegion = regionFilter.selectedRegion {
            return "Loading \(selectedRegion)…"
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
        replayPings.contains { $0.travelProgress() < 1.0 }
    }

    private var replayControl: some View {
        HStack(spacing: 4) {
            Button {
                replayPacketRoute(recenter: false)
            } label: {
                Label(
                    isReplayPlaying ? "Replay" : "Play Again",
                    systemImage: isReplayPlaying ? "play.fill" : "arrow.counterclockwise"
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.accentColor, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isReplayPlaying ? "Replay in progress" : "Play replay again")

            Button("Live") {
                isReplayMode = false
                replayPings = []
            }
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
                Menu {
                    ForEach(packetReplayStore.routes.indices, id: \.self) { index in
                        Button {
                            packetReplayStore.selectRoute(at: index)
                        } label: {
                            let hopCount = packetReplayStore.routes[index].count
                            let title = "Route \(index + 1) · \(hopCount) hops"
                            if index == packetReplayStore.selectedRouteIndex {
                                Label(title, systemImage: "checkmark")
                            } else {
                                Text(title)
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "point.topleft.down.curvedto.point.bottomright.up")
                        Text("Route \(packetReplayStore.selectedRouteIndex + 1) of \(packetReplayStore.routes.count)")
                        Image(systemName: "chevron.down")
                            .font(.caption2.weight(.semibold))
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                }
                .accessibilityLabel("Choose route")

                Divider()
                    .frame(height: 16)
            }

            Button {
                showsReplayRouteOnly.toggle()
            } label: {
                Label("Route Only", systemImage: showsReplayRouteOnly ? "checkmark.circle.fill" : "circle")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(showsReplayRouteOnly ? mapControlAccentColor : .primary)
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
        Color.accentColor.readableForeground(for: colorScheme)
    }

    private var mapUpdateInterval: TimeInterval {
        // Only redraw rapidly while a packet marker or pulse is moving. Once
        // routes are static, a one-second refresh is enough for their fade-out.
        pingsForDisplay.contains { $0.isPulse || $0.travelProgress() < 1.0 }
            ? 1.0 / 12.0
            : 1.0
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

    private struct TrailDot: Identifiable {
        let id: String
        let coordinate: CLLocationCoordinate2D
        let opacity: Double
        let color: Color
    }

    private func trailDots(at date: Date) -> [TrailDot] {
        pingsForDisplay
            .filter { !$0.isExpired(at: date) && !$0.isPulse && $0.travelProgress(at: date) < 1.0 }
            .flatMap { ping in
                let travelProgress = ping.travelProgress(at: date)
                let dotOpacity = (1.0 - travelProgress) * 0.75
                return ActivePing.trailFractions
                    .filter { $0 <= travelProgress }
                    .map { fraction in
                        TrailDot(
                            id: "\(ping.id)-\(fraction)",
                            coordinate: ping.coordinate(atFraction: fraction),
                            opacity: dotOpacity,
                            color: ping.pathColor
                        )
                    }
            }
    }

    private func nearestNode(to screenPoint: CGPoint, using proxy: MapProxy) -> MeshNode? {
        let maxTapDistance: CGFloat = 44
        var closestNode: MeshNode?
        var closestDistance = maxTapDistance
        for node in viewModel.nodes {
            guard let coordinate = node.coordinate,
                  let nodeScreenPoint = proxy.convert(coordinate, to: .local) else { continue }
            let distance = hypot(nodeScreenPoint.x - screenPoint.x, nodeScreenPoint.y - screenPoint.y)
            if distance < closestDistance {
                closestDistance = distance
                closestNode = node
            }
        }
        return closestNode
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
        selectedNode = nil
        pendingReplayRequestID = packetReplayStore.requestID
        guard !isChangingRegion else { return }
        replayPendingPacketIfNeeded()
    }

    private func replayPendingPacketIfNeeded() {
        guard pendingReplayRequestID == packetReplayStore.requestID else { return }
        pendingReplayRequestID = nil
        replayPacketRoute()
    }

    private func currentRouteCoordinates() -> [CLLocationCoordinate2D] {
        packetReplayStore.resolvedPath.compactMap { publicKey in
            validCoordinate(viewModel.nodesByPubkey[publicKey.lowercased()]?.coordinate)
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

    private func replayPacketRoute(recenter: Bool = true) {
        let coordinates = currentRouteCoordinates()
        guard coordinates.count >= 2 else { return }

        let routeColor: Color = colorScheme == .dark
            ? Color(red: 0.35, green: 0.78, blue: 1.0)
            : .orange
        let signalColor: Color = colorScheme == .dark ? .orange : .blue

        // Travel: each hop's signal dot starts moving `travelStagger` after
        // the previous one, so the dot visibly hops down the route in order.
        let travelStagger = 0.30
        let travelDuration = 1.35
        let replayStart = Date.now.addingTimeInterval(0.25)
        let hopCount = coordinates.count - 1

        // Fade: rather than every segment decaying on the same clock (which
        // reads as the whole route dissolving at once regardless of how the
        // travel was staggered), each hop's fade only begins once the
        // previous hop has fully dissolved — so the route disappears in the
        // same first-hop-to-last-hop order it was drawn in. `holdAfterTravel`
        // keeps the completed route fully visible briefly once the signal
        // reaches the end, before the sequential dissolve starts.
        let holdAfterTravel = 1.5
        let fadeDurationPerSegment = 1.0
        let lastTravelEnd = replayStart.addingTimeInterval(Double(max(hopCount - 1, 0)) * travelStagger + travelDuration)
        let fadeSequenceStart = lastTravelEnd.addingTimeInterval(holdAfterTravel)

        let segments = zip(coordinates, coordinates.dropFirst()).enumerated().map { index, pair in
            ActivePing(
                from: pair.0,
                to: pair.1,
                createdAt: replayStart.addingTimeInterval(Double(index) * travelStagger),
                fadeStartsAt: fadeSequenceStart.addingTimeInterval(Double(index) * fadeDurationPerSegment),
                duration: fadeDurationPerSegment,
                travelDuration: travelDuration,
                color: routeColor,
                signalColor: signalColor,
                routeID: "replay-\(packetReplayStore.packetHash)-\(packetReplayStore.selectedRouteIndex)",
                segmentIndex: index,
                packetHash: packetReplayStore.packetHash,
                observedAt: packetReplayStore.observedAt,
                snr: packetReplayStore.snr,
                rssi: packetReplayStore.rssi,
                sender: packetReplayStore.sender,
                messageText: packetReplayStore.messageText
            )
        }
        replayPings = segments
        isReplayMode = true
        hasCenteredCamera = true

        guard recenter else { return }

        let latitudeRange = coordinates.map(\.latitude)
        let longitudeRange = coordinates.map(\.longitude)
        guard let minimumLatitude = latitudeRange.min(),
              let maximumLatitude = latitudeRange.max(),
              let minimumLongitude = longitudeRange.min(),
              let maximumLongitude = longitudeRange.max() else {
            return
        }

        let center = CLLocationCoordinate2D(
            latitude: (minimumLatitude + maximumLatitude) / 2,
            longitude: (minimumLongitude + maximumLongitude) / 2
        )
        let radiusKm = max(
            max(maximumLatitude - minimumLatitude, maximumLongitude - minimumLongitude) * 42,
            5
        )
        moveCamera(to: center, radiusKm: radiusKm, verticalBiasFraction: replayControlsClearanceFraction)
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
            if let defaults = viewModel.mapDefaults {
                moveCamera(
                    to: CLLocationCoordinate2D(latitude: defaults.latitude, longitude: defaults.longitude),
                    radiusKm: 250
                )
            }
            return
        }

        let code = selectedRegion.uppercased()
        if let coordinate = iataCoordinates[code], !isReplayMode {
            moveCamera(to: coordinate)
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
        guard let source = analyzerSourceRegistry.sources.first(where: {
            $0.host.caseInsensitiveCompare(settings.host) == .orderedSame
        }),
        let latitude = source.mapLatitude,
        let longitude = source.mapLongitude else {
            return nil
        }

        let center = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        guard CLLocationCoordinate2DIsValid(center) else { return nil }
        return (center, source.mapRadiusKm ?? 250)
    }

    private func moveCamera(
        to center: CLLocationCoordinate2D,
        radiusKm: Double = 45,
        verticalBiasFraction: Double = 0
    ) {
        let latitudeSpan = max((radiusKm * 2.4) / 111, 0.15)
        let longitudeSpan = max(
            latitudeSpan / max(cos(center.latitude * .pi / 180), 0.2),
            0.15
        )
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

            for (subchainIndex, subchain) in subchains.enumerated() {
                if subchain.count >= 2 {
                    for index in 0..<(subchain.count - 1) {
                        let p1 = subchain[index]
                        let p2 = subchain[index + 1]
                        if isValidHopDistance(from: p1, to: p2) {
                            historicalPings.append(
                                ActivePing(
                                    from: p1,
                                    to: p2,
                                    createdAt: packet.timestamp,
                                    duration: 12.0,
                                    color: pathColor,
                                    routeID: "historical-\(packet.hash)-\(packet.observerId ?? "")-\(subchainIndex)",
                                    segmentIndex: index,
                                    packetHash: packet.hash,
                                    observerName: packet.observerName,
                                    observedAt: packet.timestamp,
                                    snr: packet.snr,
                                    rssi: packet.rssi
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
        processedEventIds = []
        processHistoricalPackets()
        processIncomingEvents()
    }

    private func processIncomingEvents() {
        activePings.removeAll { $0.isExpired(at: .now) }

        var newPings: [ActivePing] = []
        for event in liveFeed.recentEvents {
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

            let subchains = resolvedSubchains(for: event)
            let pathColor = ActivePing.color(forHash: event.data?.hash ?? event.data?.raw, colorScheme: colorScheme)
            let observedAt = Date.now

            for (subchainIndex, subchain) in subchains.enumerated() {
                if subchain.count >= 2 {
                    for index in 0..<(subchain.count - 1) {
                        let p1 = subchain[index]
                        let p2 = subchain[index + 1]
                        if isValidHopDistance(from: p1, to: p2) {
                            // Stagger each segment so a live route unfolds hop
                            // by hop instead of every line appearing at once.
                            let hopStart = Date.now.addingTimeInterval(Double(index) * 0.45)
                            newPings.append(
                                ActivePing(
                                    from: p1,
                                    to: p2,
                                    createdAt: hopStart,
                                    duration: 12.0,
                                    color: pathColor,
                                    routeID: "live-\(eventKey)-\(subchainIndex)",
                                    segmentIndex: index,
                                    packetHash: event.data?.hash,
                                    observerName: event.data?.observerName,
                                    observedAt: observedAt,
                                    snr: event.data?.snr,
                                    rssi: event.data?.rssi
                                )
                            )
                        }
                    }
                } else if let onlyPoint = subchain.first {
                    newPings.append(
                        ActivePing(pulseAt: onlyPoint, createdAt: .now, duration: 3.0, color: pathColor)
                    )
                }
            }
        }

        if processedEventIds.count > 500 {
            processedEventIds.subtract(processedEventIds.prefix(250))
        }

        guard !newPings.isEmpty else { return }
        activePings.append(contentsOf: newPings)
        if activePings.count > 100 {
            activePings.removeFirst(activePings.count - 100)
        }
    }

    private func rebuildPathsForCurrentFilters() {
        activePings = []
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
        guard let selectedRegion = regionFilter.selectedRegion?.uppercased() else {
            return true
        }

        if let observerId {
            let observerRegion = observerRegionLookup.iataById[observerId]
                ?? observerRegionLookup.iataById[observerId.lowercased()]
            if let observerRegion {
                return observerRegion.uppercased() == selectedRegion
            }
        }

        if let observerName {
            let observerRegion = observerRegionLookup.iataByName[observerName]
                ?? observerRegionLookup.iataByName[observerName.lowercased()]
            if let observerRegion {
                return observerRegion.uppercased() == selectedRegion
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

        if let decodedJson = packet.decodedJson {
            if let jsonData = decodedJson.data(using: .utf8),
               let decoded = try? JSONDecoder().decode(DecodedJsonHelper.self, from: jsonData) {
                hops = decoded.path?.hops ?? []
                resolvedPubkeys = decoded.resolvedPath ?? []
            }
        }

        if hops.isEmpty, let pathJson = packet.pathJson {
            hops = parseHops(from: pathJson)
        }

        if hops.isEmpty, let rawHex = packet.rawHex {
            hops = parseHops(from: rawHex)
        }

        return resolvedSubchains(
            hops: hops,
            resolvedPubkeys: resolvedPubkeys,
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
            observerId: data.observerId,
            observerName: data.observerName
        )
    }

    private func resolvedSubchains(
        hops: [String],
        resolvedPubkeys: [String?],
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

            if coord == nil && index < hops.count {
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
            if let coord = item {
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
        Menu {
            Button {
                regionFilter.selectedRegion = nil
            } label: {
                if regionFilter.selectedRegion == nil {
                    Label("All Regions", systemImage: "checkmark")
                } else {
                    Text("All Regions")
                }
            }

            if !regionFilter.options.isEmpty {
                Divider()
                ForEach(regionFilter.options, id: \.self) { code in
                    Button {
                        regionFilter.selectedRegion = code
                    } label: {
                        if regionFilter.selectedRegion == code {
                            Label(regionFilter.label(for: code), systemImage: "checkmark")
                        } else {
                            Text(regionFilter.label(for: code))
                        }
                    }
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
            .foregroundStyle(colorScheme == .dark ? Color.white : Color.accentColor)
            .frame(width: 150, alignment: .leading)
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .nodeScopeFloatingGlass(cornerRadius: 20)
        }
        .accessibilityLabel("Region scope: \(title)")
    }

    private var title: String {
        guard let selectedRegion = regionFilter.selectedRegion else {
            return "All Regions"
        }
        return "\(selectedRegion) · \(regionFilter.label(for: selectedRegion))"
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
                    LabeledContent("Hops", value: "\(route.hops.count)")
                    LabeledContent("Age") {
                        Text(route.observedAt, style: .relative)
                    }
                    if let observerName = route.observerName, !observerName.isEmpty {
                        LabeledContent("Observer", value: observerName)
                    }
                    if let snr = route.snr {
                        LabeledContent("SNR") {
                            Text("\(snr, format: .number.precision(.fractionLength(1))) dB")
                        }
                    }
                    if let rssi = route.rssi {
                        LabeledContent("RSSI") {
                            Text("\(rssi, format: .number.precision(.fractionLength(0))) dBm")
                        }
                    }
                    if let packetHash = route.packetHash, !packetHash.isEmpty {
                        LabeledContent("Packet", value: String(packetHash.prefix(12)).uppercased())
                    }
                }

                if let messageText = route.messageText?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !messageText.isEmpty {
                    MapRouteMessageSection(sender: route.sender, messageText: messageText)
                }

                Section("Route") {
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
                }
            }
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
                        .font(.subheadline.weight(.semibold))
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
                        .font(.caption.monospaced())
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

    var title: LocalizedStringKey {
        switch self {
        case .all: "Any Activity"
        case .fifteenMinutes: "Last 15 Minutes"
        case .oneHour: "Last Hour"
        case .oneDay: "Last 24 Hours"
        case .oneWeek: "Last 7 Days"
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
            .foregroundStyle(isFiltering ? NodeScopeStyle.signal : Color.primary)
            .frame(minWidth: 34, minHeight: 34)
            .padding(.horizontal, isFiltering ? 8 : 0)
            .nodeScopeFloatingGlass(cornerRadius: 20)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Map filters")
        .accessibilityValue(isFiltering ? "\(activeFilterCount) active" : "None")
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
            List {
                MapActivityFilterSection(selection: $filters.activityFilter)
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
                }
            }
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

private struct MapActivityFilterSection: View {
    @Binding var selection: MapNodeActivityFilter

    var body: some View {
        Section("Activity") {
            ForEach(MapNodeActivityFilter.allCases) { filter in
                Button {
                    selection = filter
                } label: {
                    HStack {
                        Text(filter.title)
                            .foregroundStyle(.primary)
                        Spacer()
                        if selection == filter {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.primary)
                        }
                    }
                }
            }
        }
    }
}

private struct MapRoleFilterSection: View {
    @Binding var selectedRoles: Set<String>

    private let roles = ["repeater", "room", "companion", "sensor"]

    var body: some View {
        Section("Node Roles") {
            Button {
                selectedRoles = []
            } label: {
                filterRow(title: "All Roles", symbol: "circle.grid.2x2", isSelected: selectedRoles.isEmpty)
            }

            ForEach(roles, id: \.self) { role in
                Button {
                    toggleRole(role)
                } label: {
                    filterRow(
                        title: role.capitalized,
                        symbol: NodeRoleStyle.symbolName(for: role),
                        isSelected: selectedRoles.contains(role)
                    )
                }
            }
        }
    }

    private func filterRow(title: String, symbol: String, isSelected: Bool) -> some View {
        HStack {
            Label(title, systemImage: symbol)
                .foregroundStyle(.primary)
            Spacer()
            if isSelected {
                Image(systemName: "checkmark")
                    .foregroundStyle(.primary)
            }
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

private struct MapObserverFilterSummarySection: View {
    let selectedObserverTitle: String
    let action: () -> Void

    var body: some View {
        Section {
            Button(action: action) {
                HStack {
                    Label("Choose Observer", systemImage: "antenna.radiowaves.left.and.right")
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
            Text("Route Observer")
        } footer: {
            Text("Limits live and recent routes to packets received by the selected observer. Node markers are still controlled by the region, activity, and role filters.")
        }
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
            }
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
                                lastSeen: node.lastSeen
                            )
                        }
                        .buttonStyle(.plain)
                    }
                    .listStyle(.plain)
                }
            }
            .padding(.top, 8)
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
    let lastSeen: Date

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: NodeRoleStyle.symbolName(for: role))
                .font(.body.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 38, height: 38)
                .background(NodeRoleStyle.color(for: role), in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(name)
                    .font(.headline)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(role.capitalized)
                    Text("·")
                    Text(publicKey.prefix(10).uppercased())
                        .font(.caption.monospaced())
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            Text(lastSeen, style: .relative)
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
        .padding(.vertical, 4)
    }
}
