@preconcurrency import AVFoundation
import SwiftUI
import UIKit

struct NodeVisionScreen: View {
    private let initialName: String?
    private let initialTarget: FieldCoordinate?
    private let initialRepeaterKey: String?
    private let initialRole: String?
    private let catalog: [MeshNode]
    private let discoveryRegion: String?
    private let catalogIsLoading: Bool
    @State private var regionalCatalogIsLoading = true

    private var showsCatalogLoading: Bool {
        isDiscovery && discoveryCatalog.isEmpty &&
            (discoveryRegion == nil ? catalogIsLoading : regionalCatalogIsLoading)
    }
    @State private var regionalCatalog = MapViewModel()
    private var discoveryCatalog: [MeshNode] {
        discoveryRegion == nil ? catalog : regionalCatalog.nodes
    }
    @State private var focusedNode: MeshNode?
    @State private var inspectedNode: MeshNode?
    @State private var detailedNode: MeshNode?
    @State private var linkProfileSeed: LinkProfileScreen.NodeVisionSeed?
    @State private var showsNearby = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var discoveryNodes: [NearbyNode] = []
    @State private var expandsInspection = false
    @State private var repeaterMetadata = RepeaterMetadataCatalog.empty
    #if DEBUG
    @State private var showsDiagnostics = false
    #endif

    init(name: String? = nil, target: FieldCoordinate? = nil, repeaterKey: String? = nil, role: String? = nil, catalog: [MeshNode] = [], discoveryRegion: String? = nil,
         catalogIsLoading: Bool = false) {
        initialName = name
        initialTarget = target
        initialRepeaterKey = repeaterKey
        initialRole = role
        self.catalog = catalog
        self.discoveryRegion = discoveryRegion
        self.catalogIsLoading = catalogIsLoading
    }

    private var isDiscovery: Bool { initialTarget == nil }
    private var name: String { focusedNode.map { FieldCatalogItem.node($0).name } ?? initialName ?? "" }
    private var target: FieldCoordinate? { focusedNode.flatMap { FieldCatalogItem.node($0).coordinate } ?? initialTarget }
    private var repeaterKey: String? { focusedNode?.publicKey ?? initialRepeaterKey }
    @Environment(AnalyzerSettings.self) private var settings
    @State private var displayedAltitude: Double?
    @State private var ground: [VisionTerrainPoint: Double] = [:]
    @State private var terrain = VisionTerrainCache(provider: OpenMeteoElevationProvider())

    private var spotlightPoint: VisionTerrainPoint? {
        target.flatMap { VisionTerrainPoint(key: repeaterKey ?? "", coordinate: $0) }
    }

    private var terrainPoints: Set<VisionTerrainPoint> {
        var points = Set(nearbyNodes.compactMap { terrainPoint(for: $0.node) })
        if let spotlightPoint { points.insert(spotlightPoint) }
        return points
    }

    private func terrainPoint(for node: MeshNode) -> VisionTerrainPoint? {
        FieldCatalogItem.node(node).coordinate.flatMap { VisionTerrainPoint(key: node.publicKey, coordinate: $0) }
    }

    private var spotlightHeight: VisionAntennaHeight {
        VisionAntennaHeight(role: focusedNode?.role ?? initialRole, reported: installHeight)
    }

    private var antennaElevation: Double? {
        spotlightPoint.flatMap { ground[$0] }.map { $0 + spotlightHeight.meters }
    }
    @State private var installHeight: Double?
    @State private var targetStatus: String?
    @State private var verticalArrowShown = false
    @State private var bottomOverlayHeight: CGFloat = 0
    @Environment(\.isHingedDevice) private var isHingedDevice
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    private var nearbyNodes: ArraySlice<NearbyNode> {
        let radius = distanceUnit.usesMiles ? 16_093.44 : 10_000.0
        return discoveryNodes.prefix { $0.geometry.distanceMeters <= radius }
    }

    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .localeDefault
    @State private var location = FieldLocationSession(tracksCamera: true)
    @State private var camera = NodeVisionCamera()
    @State private var optics: NodeVisionOptics?
    @State private var cameraAvailable = false
    @State private var previousIdleTimerSetting: Bool?
    @State private var cameraFailed = false
    @State private var showsLocationInfo = false
    @State private var arrowShown = false
    @State private var markerNameHeight: CGFloat = 0
    @State private var fullNameHeight: CGFloat = 0

    var body: some View {
        Group {
            if #available(iOS 27.1, *), isHingedDevice {
                NavigationStack {
                    cameraContent(usesSystemToolbar: true)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                closeButton
                            }
                            .visibilityPriority(.high)
                            ToolbarItem(placement: .topBarPinnedTrailing) {
                                infoButton
                            }
                            .visibilityPriority(.high)
                        }
                        .tint(NodeScopeStyle.signal)
                }
            } else {
                cameraContent(usesSystemToolbar: false)
            }
        }
        .sheet(isPresented: $showsLocationInfo) {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("The compass shows where the camera is pointing. Markers show node names and distances from you. Smaller markers follow the node’s direction and may stagger vertically to keep labels readable.")
                        Text("Tap a marker to preview a node. Choose Spotlight node to highlight it as you pan. Choosing a node from Nearby spotlights it immediately. Edge arrows show which way to turn or tilt when the highlighted node is outside the camera view.")
                        Text("When your phone’s altitude is missing or too uncertain, markers show which way to turn. Their height on screen doesn’t indicate whether a node is above or below you.")
                        Text("When the phone’s altitude is accurate enough, NodeVision places markers by estimated antenna elevation. Without a reported height, it assumes a typical rooftop or handheld height. A dashed outline marks an approximate placement based on a default height or a less precise phone altitude.")
                        Divider()
                        VStack(alignment: .leading, spacing: 8) {
                            Label("Location privacy", systemImage: "info.circle")
                                .font(.monaspace(.headline).weight(.semibold))
                                .accessibilityAddTraits(.isHeader)
                            Text("Some node owners intentionally set the reported location a short distance from the actual installation to keep their home or another private location from being pinpointed. NodeVision points toward the reported location.")
                        }
                        #if DEBUG
                        Button(showsDiagnostics ? "Hide diagnostics" : "Show diagnostics") {
                            showsDiagnostics.toggle()
                            showsLocationInfo = false
                        }
                        #endif
                    }
                    .frame(maxWidth: 540, alignment: .leading)
                    .padding()
                    .frame(maxWidth: .infinity)
                }
                .meshPageBackground()
                .navigationTitle("About NodeVision")
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { showsLocationInfo = false }
                    }
                }
            }
        }
        .sheet(isPresented: $showsNearby) {
            NavigationStack {
                List {
                    Section {
                        if showsCatalogLoading {
                            LoadingIndicator(title: String(localized: "Loading nodes…"))
                        } else if nearbyNodes.isEmpty {
                            Text(location.sample == nil ? "Waiting for location…" : "No nearby nodes found.")
                                .foregroundStyle(.secondary)
                        }
                        ForEach(nearbyNodes, id: \.node.publicKey) { item in
                            Button {
                                focusedNode = item.node
                                inspectedNode = item.node
                                expandsInspection = true
                                showsNearby = false
                            } label: {
                                HStack {
                                    Text(FieldCatalogItem.node(item.node).name).font(.monaspace(.subheadline))
                                    Spacer()
                                    distance(item.geometry, style: .caption).foregroundStyle(.secondary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    } header: {
                        Text(distanceUnit.usesMiles ? "Nodes within 10 mi of you" : "Nodes within 10 km of you")
                    }
                    .instrumentListRows()
                }
                .meshPageBackground()
                .navigationTitle("Nearby")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showsNearby = false } } }
            }
        }
        .sheet(item: $linkProfileSeed) { seed in
            NavigationStack {
                LinkProfileScreen(nodeVisionSeed: seed)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { linkProfileSeed = nil }
                        }
                    }
            }
        }
        .sheet(item: $detailedNode) { node in
            NavigationStack {
                NodeDetailScreen(node: node, openedFromNodeVision: true)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Done") { detailedNode = nil } }
                    }
            }
        }
        .task(id: settings.sourceSession.id) {
            repeaterMetadata = .empty
            guard isDiscovery, settings.sourceIsReady else { return }
            let backend = AnalyzerBackendFactory.make(settings: settings)
            let metadata = await FieldCatalogRepository.shared.repeaterMetadata(for: backend)
            if backend.sourceSession.isCurrent, !Task.isCancelled { repeaterMetadata = metadata }
        }
        .task(id: "\(settings.sourceSession.id)|\(repeaterKey ?? "")") {
            installHeight = nil
            targetStatus = nil
            guard let repeaterKey, settings.sourceIsReady else { return }
            let backend = AnalyzerBackendFactory.make(settings: settings)
            let metadata = await FieldCatalogRepository.shared.repeaterMetadata(for: backend)
            guard backend.sourceSession.isCurrent, !Task.isCancelled else { return }
            let reported = metadata[repeaterKey]
            targetStatus = reported?.status
            installHeight = reported?.applicableHeightMeters()
        }
        .task(id: terrainPoints) {
            let resolved = await terrain.resolve(terrainPoints)
            guard !Task.isCancelled else { return }
            ground = resolved
        }
        .task(id: needsLocation) {
            if needsLocation { location.start() } else { location.stop() }
        }
        .task(id: showsLiveCamera) {
            guard showsLiveCamera else {
                optics = nil
                cameraAvailable = false
                camera.stop()
                return
            }
            let allowed = await AVCaptureDevice.requestAccess(for: .video)
            guard !Task.isCancelled else { return }
            cameraFailed = !allowed
            if allowed {
                let started = await camera.start()
                guard !Task.isCancelled else { return }
                cameraAvailable = started
                cameraFailed = !started
            }
        }
        .onChange(of: location.sample, initial: true) { refreshDiscoveryNodes() }
        .task(id: settings.sourceSession.id) {
            guard isDiscovery, let discoveryRegion else { return }
            regionalCatalogIsLoading = true
            defer { if !Task.isCancelled { regionalCatalogIsLoading = false } }
            regionalCatalog.configure(settings: settings)
            await regionalCatalog.loadNodes(region: discoveryRegion)
        }
        .onChange(of: discoveryCatalog) {
            if let key = focusedNode?.publicKey,
               let updated = discoveryCatalog.first(where: { $0.publicKey == key }) {
                focusedNode = updated
            }
            refreshDiscoveryNodes()
        }
        .onChange(of: repeaterMetadata.reportedStatuses) { refreshDiscoveryNodes() }
        .onChange(of: showsLiveCamera && cameraAvailable, initial: true) { _, keepsScreenAwake in
            if keepsScreenAwake {
                if previousIdleTimerSetting == nil {
                    previousIdleTimerSetting = UIApplication.shared.isIdleTimerDisabled
                    UIApplication.shared.isIdleTimerDisabled = true
                }
            } else {
                restoreIdleTimer()
            }
        }
        .onDisappear {
            restoreIdleTimer()
            camera.stop()
            location.stop()
        }
    }

    private var showsLiveCamera: Bool {
        scenePhase == .active && !showsNearby && !showsLocationInfo &&
            detailedNode == nil && linkProfileSeed == nil
    }

    private var needsLocation: Bool {
        scenePhase == .active && (showsLiveCamera || showsNearby)
    }

    private func preparationTitle(fix: FieldLocationSample?, hasCameraProjection: Bool, hasHeading: Bool) -> String? {
        if showsCatalogLoading { return String(localized: "Loading nodes…") }
        if !cameraFailed && !hasCameraProjection { return String(localized: "Starting camera…") }
        if fix != nil {
            if cameraAvailable, !hasHeading, location.headingStatus != .unavailable,
               location.cameraPointsTowardHorizon != false {
                return String(localized: "Waiting for compass…")
            }
            return nil
        }
        switch location.status {
        case .idle, .requestingPermission, .locating, .active:
            return String(localized: "Waiting for location…")
        case .denied, .unavailable, .failed:
            return nil
        }
    }

    private func restoreIdleTimer() {
        guard let previousIdleTimerSetting else { return }
        UIApplication.shared.isIdleTimerDisabled = previousIdleTimerSetting
        self.previousIdleTimerSetting = nil
    }

    private func cameraContent(usesSystemToolbar: Bool) -> some View {
        GeometryReader { safeArea in
            viewfinder(insets: safeArea.safeAreaInsets, usesSystemToolbar: usesSystemToolbar)
                .ignoresSafeArea()
        }
    }

    private var closeButton: some View {
        Button("Close NodeVision", systemImage: "xmark") { dismiss() }
            .labelStyle(.iconOnly)
    }

    private var infoButton: some View {
        Button("About NodeVision", systemImage: "info.circle") { showsLocationInfo = true }
            .labelStyle(.iconOnly)
    }

    private func viewfinder(insets: EdgeInsets, usesSystemToolbar: Bool) -> some View {
        GeometryReader { pane in
            TimelineView(.periodic(from: .now, by: 1)) { timeline in
                let fix = location.sample.flatMap { timeline.date.timeIntervalSince($0.timestamp) <= 15 ? $0 : nil }
                let geometry = target.flatMap { target in fix.map { FieldGeometry.geometry(from: $0.coordinate, to: target) } }
                let near = geometry.map { $0.distanceMeters <= max(5, fix?.horizontalAccuracyMeters ?? 0) } ?? false
                let heading = location.cameraHeadingSample.flatMap {
                    $0.accuracyDegrees <= 25 && timeline.date.timeIntervalSince($0.timestamp) <= 2 ? $0 : nil
                }
                let tangent = optics?.halfWidthTangent(pane.size)
                let offset = geometry?.initialBearingDegrees.flatMap { bearing in
                    heading.flatMap { heading in
                        tangent.flatMap {
                            FieldGeometry.cameraHorizontalOffset(bearing: bearing, heading: heading.trueHeadingDegrees, halfWidthTangent: $0)
                        }
                    }
                }
                let markerWidth = min(192, max(0, pane.size.width - 32))
                let edge = max(0, pane.size.width / 2 - markerWidth / 2 - 8)
                let offscreen = offset.map { abs($0 * pane.size.width / 2) > max(0, edge + (arrowShown ? -6 : 6)) } ?? false
                let showSettings = location.status == .denied || AVCaptureDevice.authorizationStatus(for: .video) == .denied
                let truncatedName = fullNameHeight > markerNameHeight + 1
                let nearDistance = max(5, fix?.horizontalAccuracyMeters ?? 0)
                let distances = nearbyNodes.map(\.geometry.distanceMeters) + [geometry?.distanceMeters].compactMap { $0 }
                let closestDistance = distances.filter { $0 > nearDistance }.min()
                let altitude = stableVisionAltitude(previous: displayedAltitude, current: fix?.altitudeMeters,
                                                     distance: closestDistance)
                let mode = VisionElevationMode(altitude: altitude, accuracy: fix?.verticalAccuracyMeters,
                                               distances: distances, nearDistance: nearDistance)
                let placement = markerHeight(size: pane.size, insets: insets, distance: geometry?.distanceMeters,
                                           mode: mode, pitch: heading?.pitchDegrees)
                let verticalEdge = placement.edge
                let useVerticalArrow = verticalEdge != nil &&
                    (!offscreen || abs(placement.offset ?? 0) > abs(offset ?? 0))
                let showsBottomArrow = cameraAvailable && !near && offset != nil &&
                    useVerticalArrow && verticalEdge == .below
                let markerVisible = placement.y != nil && cameraAvailable && !near && offset != nil && !offscreen && verticalEdge == nil
                let discoveryMarkers = discoveryMarkers(size: pane.size, insets: insets, fix: fix,
                                                        heading: heading, tangent: tangent, mode: mode,
                                                        focusedX: markerVisible ? offset.map { pane.size.width * (1 + $0) / 2 } : nil,
                                                        focusedY: placement.y ?? 0, focusedWidth: markerWidth)
                ZStack {
                    Rectangle().fill(.background)
                    if cameraAvailable {
                        NodeVisionPreview(camera: camera) { value in
                            if optics != value { optics = value }
                        }
                        .accessibilityHidden(true)
                    }
                    ZStack {
                        ForEach(discoveryMarkers) { marker in
                            discoveryMarker(marker)
                                .transition(.opacity)
                        }
                    }
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.24),
                               value: Set(discoveryMarkers.map(\.id)))
                    .id(mode == .guide)
                    if cameraAvailable, !near, !showsBottomArrow, let offset, placement.y != nil || offscreen {
                        direction(offset, offscreen: offscreen, verticalEdge: useVerticalArrow ? verticalEdge : nil,
                                  markerWidth: markerWidth, markerY: placement.y ?? pane.size.height * 0.43, estimated: mode != .guide,
                                  approximate: placement.approximate, size: pane.size, geometry: geometry)
                    }
                    VStack(spacing: 0) {
                        VStack(spacing: 0) {
                            HStack {
                                Text("NodeVision")
                                    .font(.monaspace(.title3).weight(.semibold))
                                Spacer()
                                if !usesSystemToolbar {
                                    Button { showsLocationInfo = true } label: {
                                        Label("About NodeVision", systemImage: "info.circle")
                                            .labelStyle(.iconOnly)
                                            .frame(minWidth: 44, minHeight: 44)
                                            .contentShape(Rectangle())
                                    }
                                        .buttonStyle(.plain)
                                        .foregroundStyle(NodeScopeStyle.signal)
                                    Button { dismiss() } label: {
                                        Label("Close NodeVision", systemImage: "xmark")
                                            .labelStyle(.iconOnly)
                                            .font(.subheadline.weight(.semibold))
                                            .frame(minWidth: 44, minHeight: 44)
                                            .contentShape(Rectangle())
                                    }
                                        .buttonStyle(.plain)
                                        .foregroundStyle(NodeScopeStyle.signal)
                                }
                            }
                            .padding(.leading, max(16, insets.leading))
                            .padding(.trailing, max(8, insets.trailing))
                            if cameraAvailable, let heading, let tangent {
                                compass(heading: heading.trueHeadingDegrees, tangent: tangent, width: pane.size.width)
                            }
                            if cameraAvailable, heading != nil, tangent != nil, fix != nil, mode == .guide,
                               target != nil || !discoveryMarkers.isEmpty {
                                Text("Phone altitude uncertain")
                                    .font(.caption)
                                    .foregroundStyle(supportingTextColor)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 4)
                            }
                        }
                        .padding(.top, insets.top)
                        .background {
                            (colorScheme == .dark ? NodeScopeStyle.cardSlate : Color(uiColor: .systemBackground))
                                .opacity(0.88)
                                .overlay { overlayGradient.opacity(0.65) }
                        }
                        .overlay(alignment: .bottom) {
                            Rectangle().fill(NodeScopeStyle.signal.opacity(0.25)).frame(height: 1)
                        }
                        #if DEBUG
                        if showsDiagnostics {
                            Text(verbatim: elevationReadout(fix: fix, decision: placement.decision))
                            .font(.monaspace(.caption2))
                            .padding(5)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                            .padding(.top, 4)
                        }
                        #endif
                        if showsLiveCamera {
                            if let title = preparationTitle(fix: fix, hasCameraProjection: tangent != nil, hasHeading: heading != nil) {
                                LoadingIndicator(title: title)
                                    .padding(.top, 8)
                                    .allowsHitTesting(false)
                            } else if isDiscovery, target == nil, inspectedNode == nil,
                                      cameraAvailable, fix != nil, tangent != nil, discoveryMarkers.isEmpty {
                                Text(location.cameraPointsTowardHorizon == false
                                     ? "Hold the phone upright and point the camera toward the horizon."
                                     : heading == nil ? "Compass heading unavailable."
                                     : discoveryNodes.isEmpty ? "No nodes with locations found."
                                     : "No nodes in view. Pan or tilt the camera, or open Nearby.")
                                    .font(.caption)
                                    .multilineTextAlignment(.center)
                                    .padding(8)
                                    .background { overlaySurface(RoundedRectangle(cornerRadius: 10)) }
                                    .padding(.horizontal, 16)
                                    .padding(.top, 8)
                                    .allowsHitTesting(false)
                            }
                        }
                        Spacer()
                        if showsBottomArrow, let offset {
                            let center = verticalArrowCenter(offset: offset, width: pane.size.width)
                            verticalArrow(.below)
                                .offset(x: center - pane.size.width / 2)
                                .padding(.bottom, 8)
                        }
                        VStack(spacing: 0) {
                            if isDiscovery, target == nil, inspectedNode == nil {
                                if cameraFailed {
                                    Text("Camera unavailable. You can still browse Nearby.")
                                        .font(.subheadline).padding(8)
                                        .background { overlaySurface(RoundedRectangle(cornerRadius: 10)) }
                                }
                                if location.status == .denied {
                                    Text("Location access is needed to find nearby nodes.")
                                        .font(.subheadline).padding(8)
                                        .background { overlaySurface(RoundedRectangle(cornerRadius: 10)) }
                                }
                            }
                            if let inspectedNode, isDiscovery {
                                inspectionCard(inspectedNode, fix: fix,
                                               hidesDistance: (markerVisible && focusedNode?.publicKey == inspectedNode.publicKey)
                                                   || discoveryMarkers.contains { $0.id == inspectedNode.publicKey },
                                               maxBodyHeight: pane.size.height * 0.34)
                            }
                            if inspectedNode == nil, target != nil,
                               !markerVisible || truncatedName || cameraFailed || showSettings || installHeight != nil
                                    || targetStatus == "maintenance" || targetStatus == "decommissioned" {
                                VStack(spacing: 6) {
                                    if !markerVisible || truncatedName {
                                        Text(name)
                                            .font(.monaspace(.subheadline).weight(.semibold))
                                            .foregroundStyle(nodeNameColor)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }
                                    if !markerVisible, let geometry { distance(geometry, style: .caption) }
                                    if let installHeight {
                                        HStack(spacing: 8) {
                                            Image(systemName: "arrow.up.and.down")
                                                .foregroundStyle(nodeNameColor)
                                                .accessibilityHidden(true)
                                            Text("Reported install height")
                                                .foregroundStyle(supportingTextColor)
                                            Text(distanceUnit.formatHeight(meters: installHeight))
                                                .font(.monaspace(.caption).weight(.semibold))
                                        }
                                        .font(.caption)
                                        .accessibilityElement(children: .combine)
                                    }
                                    if targetStatus == "maintenance" {
                                        Text("Owner reported: Maintenance").font(.caption)
                                    } else if targetStatus == "decommissioned" {
                                        Text("Owner reported: Decommissioned").font(.caption)
                                    }
                                    if !markerVisible, placement.approximate {
                                        Text("Approx. elevation").font(.caption)
                                    }
                                    if cameraFailed {
                                        Text("Camera unavailable. You can return to the guide.")
                                    } else if near {
                                        Text("Near reported location")
                                    } else if fix == nil {
                                        Text(location.status == .denied ? "Location access is needed for guidance." : "Waiting for location…")
                                    } else if heading == nil, location.cameraPointsTowardHorizon == false {
                                        Text("Hold the phone upright and point the camera toward the horizon.")
                                    }
                                    if showSettings {
                                        Button("Open Settings") {
                                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                                        }
                                    }
                                }
                                .font(.subheadline)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .multilineTextAlignment(.center)
                                .background { overlaySurface(RoundedRectangle(cornerRadius: 14)) }
                                .frame(maxWidth: 340)
                                .padding(.horizontal, max(16, insets.leading, insets.trailing))
                                .padding(.bottom, insets.bottom + 8)
                            }
                            if isDiscovery {
                                HStack(spacing: 10) {
                                    cameraControl(action: { showsNearby = true }) {
                                        Label("Nearby", systemImage: "list.bullet")
                                    }
                                    if focusedNode != nil {
                                        cameraControl(action: { focusedNode = nil }) {
                                            Text("Clear spotlight")
                                        }
                                    }
                                    if showSettings, target == nil {
                                        Button("Open Settings") {
                                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                                        }
                                    }
                                }
                                .buttonStyle(.bordered)
                                .tint(nodeNameColor)
                                .padding(.bottom, insets.bottom + 8)
                            }
                        }
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: {
                            bottomOverlayHeight = $0
                        }
                    }
                }
                .clipped()
                .onChange(of: verticalEdge != nil, initial: true) { _, value in verticalArrowShown = value }
                .onChange(of: altitude, initial: true) { displayedAltitude = altitude }
            }
        }
    }

    private struct NearbyNode {
        let node: MeshNode
        let geometry: FieldGeometryResult
    }

    private func refreshDiscoveryNodes() {
        guard isDiscovery, let fix = location.sample else {
            discoveryNodes = []
            return
        }
        discoveryNodes = discoveryCatalog.compactMap { node in
            guard repeaterMetadata[node.publicKey]?.status != "decommissioned",
                  let coordinate = FieldCatalogItem.node(node).coordinate else { return nil }
            let geometry = FieldGeometry.geometry(from: fix.coordinate, to: coordinate)
            return NearbyNode(node: node, geometry: geometry)
        }.sorted { ($0.geometry.distanceMeters, $0.node.publicKey) < ($1.geometry.distanceMeters, $1.node.publicKey) }
    }

    private struct DiscoveryMarker: Identifiable {
        let candidate: NearbyNode
        let x: CGFloat
        let y: CGFloat
        let approximate: Bool
        let rowOffset: CGFloat
        let width: CGFloat
        let tier: Int

        var id: String { candidate.node.publicKey }
    }

    private func discoveryMarkers(size: CGSize, insets: EdgeInsets, fix: FieldLocationSample?,
                                  heading: FieldHeadingSample?, tangent: Double?, mode: VisionElevationMode,
                                  focusedX: CGFloat?, focusedY: CGFloat, focusedWidth: CGFloat) -> [DiscoveryMarker] {
        guard isDiscovery, cameraAvailable, let fix, let heading, let tangent, tangent > 0,
              let pitch = heading.pitchDegrees, let verticalTangent = optics?.halfHeightTangent(size),
              verticalTangent > 0 else { return [] }
        let minY = insets.top + 166
        let maxY = size.height - insets.bottom - 100
        guard minY < maxY else { return [] }
        let selectedID = inspectedNode?.publicKey
        var occupied: [CGRect] = []
        if let focusedX {
            occupied.append(CGRect(x: focusedX - focusedWidth / 2, y: focusedY - 88,
                                   width: focusedWidth, height: 88))
        }
        var markers: [DiscoveryMarker] = []
        func append(_ candidate: NearbyNode) {
            guard markers.count + (focusedX == nil ? 0 : 1) < 5,
                  candidate.node.publicKey != focusedNode?.publicKey,
                  candidate.geometry.distanceMeters > max(5, fix.horizontalAccuracyMeters),
                  let bearing = candidate.geometry.initialBearingDegrees,
                  let offset = FieldGeometry.cameraHorizontalOffset(bearing: bearing,
                    heading: heading.trueHeadingDegrees, halfWidthTangent: tangent),
                  abs(offset) < 1 else { return }
            let tier = candidate.geometry.distanceMeters < 500 ? 0 : candidate.geometry.distanceMeters < 2_000 ? 1 : 2
            let width: CGFloat = [132, 112, 96][tier]
            let x = size.width * CGFloat(1 + offset) / 2
            let left = x - width / 2 - 5
            let paddedWidth = width + 10
            guard left >= insets.leading, left + paddedWidth <= size.width - insets.trailing else { return }
            let height = VisionAntennaHeight(role: candidate.node.role,
                reported: repeaterMetadata[candidate.node.publicKey]?.applicableHeightMeters())
            let antenna = terrainPoint(for: candidate.node).flatMap { ground[$0] }.map { $0 + height.meters }
            let distance = candidate.geometry.distanceMeters
            guard let vertical = mode.offset(distance: distance, antenna: antenna, pitch: pitch,
                                             tangent: verticalTangent) else { return }
            let projectedY = size.height * CGFloat(1 - vertical) / 2
            guard let y = discoveryMarkerY(projectedY: projectedY, paneHeight: size.height,
                                           minimumY: minY, maximumY: maxY) else { return }
            let approximate = mode.isApproximate(distance: distance, height: height)
            let nudges: [CGFloat] = mode == .guide ? [0, y + 92 <= maxY ? 92 : -92] : [0, 24, -24, 48, -48, 72, -72, 92, -92]
            for nudge in nudges {
                let rowY = min(max(y + nudge, minY), maxY)
                let bounds = CGRect(x: left, y: rowY - 84, width: paddedWidth, height: 88)
                guard !occupied.contains(where: { $0.intersects(bounds) }) else { continue }
                occupied.append(bounds)
                markers.append(DiscoveryMarker(candidate: candidate, x: x, y: y, approximate: approximate,
                                               rowOffset: rowY - y, width: width, tier: tier))
                break
            }
        }
        if let selected = discoveryNodes.first(where: { $0.node.publicKey == selectedID }) { append(selected) }
        for candidate in discoveryNodes where candidate.node.publicKey != selectedID {
            append(candidate)
            if markers.count + (focusedX == nil ? 0 : 1) >= 5 { break }
        }
        return markers
    }

    private func discoveryMarker(_ marker: DiscoveryMarker) -> some View {
        let node = marker.candidate.node
        let selected = inspectedNode?.publicKey == node.publicKey
        return Button {
            inspectedNode = node
            expandsInspection = false
        } label: {
            VStack(spacing: 2) {
                Text(FieldCatalogItem.node(node).name)
                    .font(.monaspace(marker.tier == 0 ? .subheadline : .caption).weight(.semibold))
                    .foregroundStyle(nodeNameColor)
                    .lineLimit(2)
                distance(marker.candidate.geometry, style: .caption2)
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 6)
            .padding(.vertical, 4)
            .frame(width: marker.width)
            .frame(minHeight: 44)
            .padding(.bottom, 9)
            .background { markerSurface(approximate: marker.approximate, emphasized: selected) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .frame(width: marker.width, height: 80, alignment: .bottom)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18)) { label in
            label.offset(y: marker.rowOffset)
        }
        .position(x: marker.x, y: marker.y - 40)
        // Membership fades should leave live camera tracking at its current position.
        .animation(nil, value: marker.x)
        .animation(nil, value: marker.y)
    }

    private func inspectionCard(_ node: MeshNode, fix: FieldLocationSample?, hidesDistance: Bool, maxBodyHeight: CGFloat) -> some View {
        let item = FieldCatalogItem.node(node)
        let metadata = repeaterMetadata[node.publicKey]
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button {
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.22)) {
                        expandsInspection.toggle()
                    }
                } label: {
                    HStack {
                        Text(item.name).font(.monaspace(.subheadline).weight(.semibold))
                        Spacer(minLength: 10)
                        Image(systemName: expandsInspection ? "chevron.down" : "chevron.up")
                    }
                    .foregroundStyle(nodeNameColor)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Button { inspectedNode = nil } label: {
                    Image(systemName: "xmark").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close node preview")
            }
            .geometryGroup()
            if !hidesDistance, let fix, let coordinate = item.coordinate {
                distance(FieldGeometry.geometry(from: fix.coordinate, to: coordinate), style: .caption)
            }
            if metadata?.status == "maintenance" {
                Label("Owner reported: Maintenance", systemImage: "wrench.and.screwdriver")
                    .foregroundStyle(supportingTextColor)
            }
            if focusedNode?.publicKey != node.publicKey {
                Button("Spotlight node") {
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.22)) {
                        focusedNode = node
                        expandsInspection = true
                    }
                }
                    .buttonStyle(.borderedProminent)
            }
            if expandsInspection {
                ViewThatFits(in: .vertical) {
                    inspectionDetails(node, item: item, metadata: metadata, fix: fix).fixedSize(horizontal: false, vertical: true)
                    ScrollView { inspectionDetails(node, item: item, metadata: metadata, fix: fix) }
                }
                .frame(maxHeight: max(95, maxBodyHeight))
                .transition(.opacity)
            }
        }
        .font(.subheadline)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: 340)
        .fixedSize(horizontal: false, vertical: true)
        .background { overlaySurface(RoundedRectangle(cornerRadius: 14)) }
        .geometryGroup()
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private func inspectionDetails(_ node: MeshNode, item: FieldCatalogItem, metadata: RepeaterMetadata?,
                                   fix: FieldLocationSample?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(item.subtitle)
            if let seen = node.latestActivity {
                Text("Last heard: \(seen, format: .relative(presentation: .named))")
            }
            if let metadata, metadata.hasDetails {
                Text("Owner reported").font(.monaspace(.caption).weight(.semibold))
                if let height = metadata.heightM {
                    Text("Install height: \(distanceUnit.formatHeight(meters: height))")
                }
                if let gain = metadata.applicableGainDbi {
                    Text("Antenna gain: \(gain.formatted()) dBi")
                }
                if let power = metadata.power {
                    switch power {
                    case "mains": Text("Power: Mains")
                    case "battery": Text("Power: Battery")
                    case "solar": Text("Power: Solar")
                    default: EmptyView()
                    }
                }
            }
            if let fix, let coordinate = item.coordinate,
               FieldGeometry.geometry(from: fix.coordinate, to: coordinate).distanceMeters > max(5, fix.horizontalAccuracyMeters) {
                Button("Link Profile") {
                    linkProfileSeed = .init(origin: fix.coordinate, target: coordinate, name: item.name,
                                            selection: .init(publicKey: node.publicKey, sourceID: settings.sourceSession.id))
                }
                .buttonStyle(.borderedProminent)
            }
            Button("Open node details") { detailedNode = node }
                .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var nodeNameColor: Color {
        colorScheme == .dark
            ? NodeScopeStyle.signal
            : NodeScopeStyle.signal.mix(with: NodeScopeStyle.deepNavy, by: 0.45)
    }

    private var supportingTextColor: Color {
        colorScheme == .dark ? Color.primary.opacity(0.78) : NodeScopeStyle.deepNavy.opacity(0.86)
    }

    private var overlayGradient: LinearGradient {
        if colorScheme == .dark {
            LinearGradient(colors: [NodeScopeStyle.signal.opacity(0.23), .clear],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
        } else {
            LinearGradient(colors: [NodeScopeStyle.signal.opacity(0.16), NodeScopeStyle.signal.opacity(0.07)],
                           startPoint: .top, endPoint: .bottom)
        }
    }

    private func cameraControl<Content: View>(
        action: @escaping () -> Void, @ViewBuilder label: () -> Content
    ) -> some View {
        Button(action: action) {
            label()
                .padding(.horizontal, 16)
                .frame(minHeight: 44)
                .background { overlaySurface(Capsule()) }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(nodeNameColor)
    }

    private func markerSurface(approximate: Bool, emphasized: Bool, spotlight: Bool = false) -> some View {
        let shape = NodeVisionMarkerShape()
        let width: CGFloat = emphasized ? 3 : 2
        let borderColor = spotlight ? NodeScopeStyle.activity : nodeNameColor
        let backing = colorScheme == .dark ? NodeScopeStyle.deepNavy : Color(uiColor: .systemBackground)
        return overlaySurface(shape, drawsBorder: false)
            .overlay { shape.stroke(backing, lineWidth: width + 2) }
            .overlay {
                shape.stroke(borderColor, style: StrokeStyle(
                    lineWidth: width, lineCap: .round, lineJoin: .round, dash: approximate ? [7, 6] : []))
            }
    }

    private func overlaySurface<S: Shape>(_ shape: S, drawsBorder: Bool = true) -> some View {
        return shape.fill(.regularMaterial)
            .overlay {
                shape.fill(colorScheme == .light ? Color(uiColor: .systemBackground).opacity(0.85) : .clear)
            }
            .overlay { shape.fill(overlayGradient) }
            .overlay {
                if drawsBorder {
                    shape.stroke(NodeScopeStyle.signal.opacity(0.4), lineWidth: 1)
                }
            }
            .shadow(color: .black.opacity(0.24), radius: 5, x: 0, y: 2)
    }

    private func distance(_ geometry: FieldGeometryResult, style: Font.TextStyle = .subheadline) -> some View {
        let value = Text(distanceUnit.format(kilometers: geometry.distanceMeters / 1000))
            .font(.monaspace(style).weight(.medium))
        return Text("\(value) away").font(.system(style))
    }

    private func compass(heading: Double, tangent: Double, width: CGFloat) -> some View {
        ZStack {
            ForEach(0..<80) { tick in
                let bearing = Double(tick) * 4.5
                let major = tick.isMultiple(of: 5)
                if let offset = FieldGeometry.cameraHorizontalOffset(bearing: bearing, heading: heading, halfWidthTangent: tangent),
                   abs(offset) < 0.95 {
                    let x = width * (1 + offset) / 2
                    Capsule().fill(Color.primary.opacity(major ? 0.85 : 0.52))
                        .frame(width: major ? 2 : 1.5, height: major ? 12 : 7)
                        .position(x: x, y: major ? 26 : 28.5)
                    if major, abs(offset) < 0.87 {
                        Text(FieldGeometry.cardinalDirection(for: bearing).label)
                            .font(.monaspace(.caption).weight(.medium))
                            .position(x: x, y: 11)
                    }
                }
            }
            Capsule().fill(NodeScopeStyle.activity)
                .frame(width: 3, height: 15)
                .position(x: width / 2, y: 24.5)
        }
        .frame(width: width, height: 35)
        .accessibilityHidden(true)
    }

    private func markerHeight(size: CGSize, insets: EdgeInsets, distance: Double?,
                              mode: VisionElevationMode, pitch: Double?) -> (y: CGFloat?, approximate: Bool, decision: String, offset: Double?, edge: VerticalEdge?) {
        let fallback = size.height * 0.43
        let modeName = mode == .guide ? "All markers: pitch guide" : "All markers: elevation"
        guard let pitch, let tangent = optics?.halfHeightTangent(size), tangent > 0 else {
            return (nil, false, modeName + " · waiting for pitch or camera", nil, nil)
        }
        guard let distance, let offset = mode.offset(distance: distance, antenna: antennaElevation,
                                                     pitch: pitch, tangent: tangent) else {
            return (nil, false, modeName + " · spotlight unplaced", nil, nil)
        }
        let approximate = mode.isApproximate(distance: distance, height: spotlightHeight)
        let estimatedOffset = mode == .guide ? nil : offset
        let minimumY = insets.top + 166
        let maximumY = size.height - insets.bottom - 100
        guard maximumY >= minimumY else { return (fallback, approximate, modeName + " · short viewfinder", nil, nil) }
        let projectedY = size.height * (1 - offset) / 2
        let buffer: CGFloat = verticalArrowShown ? -6 : 6
        let edge: VerticalEdge?
        if estimatedOffset == nil { edge = nil }
        else if projectedY < minimumY - buffer { edge = .above }
        else if projectedY > maximumY + buffer { edge = .below }
        else { edge = nil }
        return (min(max(projectedY, minimumY), maximumY), approximate, modeName, estimatedOffset, edge)
    }

    #if DEBUG
    private func elevationReadout(fix: FieldLocationSample?, decision: String) -> String {
        func meters(_ value: Double?) -> String {
            value.map { $0.formatted(.number.precision(.fractionLength(1))) + " m" } ?? "—"
        }
        return "Phone \(meters(fix?.altitudeMeters)) ±\(meters(fix?.verticalAccuracyMeters)) · Antenna \(meters(antennaElevation))\n\(decision)"
    }
    #endif

    private enum VerticalEdge { case above, below }

    private func verticalArrowCenter(offset: Double, width: CGFloat) -> CGFloat {
        min(max(width * (1 + offset) / 2, 32), max(32, width - 32))
    }

    private func verticalArrow(_ edge: VerticalEdge) -> some View {
        Image(systemName: edge == .above ? "chevron.up" : "chevron.down")
            .font(.title2.bold())
            .foregroundStyle(NodeScopeStyle.activity)
            .frame(width: 44, height: 44)
            .instrumentCard(fillOpacity: 0.98)
            .accessibilityLabel(edge == .above ? "Target above the view" : "Target below the view")
    }

    private func direction(_ offset: Double, offscreen: Bool, verticalEdge: VerticalEdge?, markerWidth: CGFloat,
                           markerY: CGFloat, estimated: Bool, approximate: Bool, size: CGSize,
                           geometry: FieldGeometryResult?) -> some View {
        let pixel = offset * size.width / 2
        return Group {
            if let verticalEdge {
                verticalArrow(verticalEdge)
                    .position(x: verticalArrowCenter(offset: offset, width: size.width), y: markerY - 34)
            } else if offscreen {
                Image(systemName: offset < 0 ? "chevron.left" : "chevron.right")
                    .font(.title.bold())
                    .foregroundStyle(NodeScopeStyle.activity)
                    .padding(12)
                    .instrumentCard(fillOpacity: 0.98)
                    .position(x: offset < 0 ? 34 : size.width - 34,
                              y: min(estimated ? markerY : size.height * 0.43,
                                     size.height - bottomOverlayHeight - 36))
                    .accessibilityLabel(offset < 0 ? "Target to the left" : "Target to the right")
            } else {
                Group {
                    VStack(spacing: 2) {
                        Text(name)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { markerNameHeight = $0 }
                            .overlay {
                                Text(name)
                                    .lineLimit(nil)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .hidden()
                                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { fullNameHeight = $0 }
                            }
                            .font(.monaspace(.subheadline).weight(.semibold))
                            .foregroundStyle(nodeNameColor)
                        if let geometry { distance(geometry, style: .caption) }
                        if approximate {
                            Text("Approx. elevation").font(.caption2).foregroundStyle(supportingTextColor)
                        }
                    }
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .frame(maxWidth: markerWidth)
                    .padding(.bottom, 9)
                    .background { markerSurface(approximate: approximate, emphasized: true, spotlight: true) }
                }
                .frame(width: markerWidth, height: 88, alignment: .bottom)
                .position(x: size.width / 2 + pixel, y: markerY - 44)
            }
        }
        .onChange(of: offscreen, initial: true) { _, value in arrowShown = value }
    }
}

private struct NodeVisionMarkerShape: Shape {
    func path(in rect: CGRect) -> Path {
        let base = rect.maxY - 9
        let radius = max(0, min(10, rect.width / 4, (base - rect.minY) / 2))
        let mid = rect.midX
        return Path { path in
            path.move(to: CGPoint(x: rect.minX + radius, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX - radius, y: rect.minY))
            path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + radius), control: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: base - radius))
            path.addQuadCurve(to: CGPoint(x: rect.maxX - radius, y: base), control: CGPoint(x: rect.maxX, y: base))
            path.addLine(to: CGPoint(x: mid + 8, y: base))
            path.addQuadCurve(to: CGPoint(x: mid + 1, y: rect.maxY - 1), control: CGPoint(x: mid + 5, y: base + 6))
            path.addQuadCurve(to: CGPoint(x: mid - 1, y: rect.maxY - 1), control: CGPoint(x: mid, y: rect.maxY + 1))
            path.addQuadCurve(to: CGPoint(x: mid - 8, y: base), control: CGPoint(x: mid - 5, y: base + 6))
            path.addLine(to: CGPoint(x: rect.minX + radius, y: base))
            path.addQuadCurve(to: CGPoint(x: rect.minX, y: base - radius), control: CGPoint(x: rect.minX, y: base))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
            path.addQuadCurve(to: CGPoint(x: rect.minX + radius, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
            path.closeSubpath()
        }
    }
}

private struct NodeVisionOptics: Equatable {
    let horizontalTangent: Double
    let aspect: Double
    let quarterTurn: Bool

    func halfHeightTangent(_ pane: CGSize) -> Double {
        let orientedAspect = quarterTurn ? 1 / aspect : aspect
        let tangent = quarterTurn ? horizontalTangent : horizontalTangent / aspect
        return tangent * min(1, orientedAspect / (pane.width / max(1, pane.height)))
    }

    func halfWidthTangent(_ pane: CGSize) -> Double {
        let orientedAspect = quarterTurn ? 1 / aspect : aspect
        let tangent = quarterTurn ? horizontalTangent / aspect : horizontalTangent
        return tangent * min(1, (pane.width / max(1, pane.height)) / orientedAspect)
    }
}

/// AVCaptureSession configuration and blocking operations are confined to the camera queue.
private final class NodeVisionCamera: @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "org.nodescope.nodevision.camera")
    private var configured = false

    func start() async -> Bool {
        await withCheckedContinuation { continuation in
            queue.async {
                if !self.configured {
                    guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                          let input = try? AVCaptureDeviceInput(device: device) else {
                        continuation.resume(returning: false)
                        return
                    }
                    self.session.beginConfiguration()
                    if self.session.canSetSessionPreset(.photo) { self.session.sessionPreset = .photo }
                    if self.session.canAddInput(input) {
                        self.session.addInput(input)
                        self.configured = true
                    }
                    self.session.commitConfiguration()
                }
                if self.configured && !self.session.isRunning { self.session.startRunning() }
                continuation.resume(returning: self.session.isRunning)
            }
        }
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }
}

private struct NodeVisionPreview: UIViewRepresentable {
    let camera: NodeVisionCamera
    let update: (NodeVisionOptics) -> Void

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.preview.session = camera.session
        view.preview.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        let needsInitialLayout = view.update == nil
        view.update = update
        if needsInitialLayout { view.setNeedsLayout() }
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        var update: ((NodeVisionOptics) -> Void)?

        override func layoutSubviews() {
            super.layoutSubviews()
            guard let connection = preview.connection,
                  let device = (connection.inputPorts.first?.input as? AVCaptureDeviceInput)?.device else { return }
            let angle: CGFloat
            switch window?.windowScene?.interfaceOrientation {
            case .landscapeLeft: angle = 180
            case .landscapeRight: angle = 0
            case .portraitUpsideDown: angle = 270
            default: angle = 90
            }
            if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
            let format = device.activeFormat
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            guard dimensions.width > 0, dimensions.height > 0, format.videoFieldOfView > 0 else { return }
            let optics = NodeVisionOptics(
                horizontalTangent: tan(Double(format.videoFieldOfView) * .pi / 360),
                aspect: Double(dimensions.width) / Double(dimensions.height),
                quarterTurn: angle == 90 || angle == 270
            )
            Task { @MainActor [weak self] in self?.update?(optics) }
        }
    }
}


struct VisionAntennaHeight {
    let meters: Double
    let approximate: Bool

    init(role: String?, reported: Double?) {
        let role = role == "room-server" ? GlossaryTerm.roomServer : role.flatMap { GlossaryTerm(role: $0) }
        let reported = role == .repeater ? reported : nil
        let preset: CoverageHeightPreset = role == .repeater || role == .roomServer ? .rooftop : .handheld
        meters = reported ?? preset.meters
        approximate = reported == nil
    }
}

enum VisionElevationMode: Equatable {
    case guide
    case elevation(altitude: Double, accuracy: Double)

    init(altitude: Double?, accuracy: Double?, distances: [Double], nearDistance: Double) {
        guard let altitude, altitude.isFinite, let accuracy, accuracy.isFinite, accuracy > 0,
              distances.filter({ $0 > nearDistance }).allSatisfy({
                  FieldGeometry.cameraElevationUncertainty(distance: $0, phoneAccuracy: accuracy) <= 30
              }) else {
            self = .guide
            return
        }
        self = .elevation(altitude: altitude, accuracy: accuracy)
    }

    func offset(distance: Double, antenna: Double?, pitch: Double, tangent: Double) -> Double? {
        switch self {
        case .guide:
            FieldGeometry.cameraBearingGuideOffset(pitch: pitch, halfHeightTangent: tangent)
        case let .elevation(altitude, accuracy):
            antenna.flatMap {
                FieldGeometry.cameraVerticalOffset(distance: distance, antennaElevation: $0, phoneElevation: altitude,
                    phoneAccuracy: accuracy, pitch: pitch, halfHeightTangent: tangent, clampAngle: true)
            }
        }
    }

    func isApproximate(distance: Double, height: VisionAntennaHeight) -> Bool {
        guard case let .elevation(_, accuracy) = self else { return false }
        return height.approximate || FieldGeometry.cameraElevationUncertainty(distance: distance, phoneAccuracy: accuracy) > 8
    }
}

struct VisionTerrainPoint: Hashable, Sendable {
    let key: String
    let coordinate: NodePlannerCoordinate

    init?(key: String, coordinate: FieldCoordinate) {
        guard let point = try? NodePlannerCoordinate(latitude: coordinate.latitude, longitude: coordinate.longitude)
        else { return nil }
        self.key = key.lowercased()
        self.coordinate = point
    }
}

actor VisionTerrainCache {
    private let provider: any NodePlannerElevationProviding
    private var elevations: [VisionTerrainPoint: Double] = [:]
    private var attempted: Set<VisionTerrainPoint> = []

    init(provider: any NodePlannerElevationProviding) {
        self.provider = provider
    }

    func resolve(_ points: Set<VisionTerrainPoint>) async -> [VisionTerrainPoint: Double] {
        let missing = points.subtracting(attempted).sorted {
            ($0.key, $0.coordinate.latitude, $0.coordinate.longitude) <
                ($1.key, $1.coordinate.latitude, $1.coordinate.longitude)
        }
        for start in stride(from: 0, to: missing.count, by: OpenMeteoElevationProvider.maximumBatchSize) {
            guard !Task.isCancelled else { break }
            let batch = missing[start..<min(start + OpenMeteoElevationProvider.maximumBatchSize, missing.count)]
            var seen: Set<NodePlannerCoordinate> = []
            let coordinates = batch.map(\.coordinate).filter { seen.insert($0).inserted }
            let request = coordinates.count == 1 ? coordinates + coordinates : coordinates
            let profile = try? await provider.elevations(for: request)
            guard !Task.isCancelled else { break }
            attempted.formUnion(batch)
            guard let profile else { continue }
            var ground: [NodePlannerCoordinate: Double] = [:]
            for sample in profile.samples where sample.elevationMeters.isFinite {
                ground[sample.coordinate] = sample.elevationMeters
            }
            for point in batch {
                elevations[point] = ground[point.coordinate]
            }
        }
        return elevations
    }
}

/// Ignore altitude changes that move even the closest eligible marker less than 0.15°.
func stableVisionAltitude(previous: Double?, current: Double?, distance: Double?) -> Double? {
    guard let current, current.isFinite else { return nil }
    guard let previous, let distance else { return current }
    return atan2(abs(current - previous), distance) < 0.15 * .pi / 180 ? previous : current
}

/// Clamp to the label-safe area while the node is in or just outside the viewfinder.
func discoveryMarkerY(projectedY: CGFloat, paneHeight: CGFloat, minimumY: CGFloat, maximumY: CGFloat) -> CGFloat? {
    guard (-24...(paneHeight + 24)).contains(projectedY) else { return nil }
    return min(max(projectedY, minimumY), maximumY)
}
