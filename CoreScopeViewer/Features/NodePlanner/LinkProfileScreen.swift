import Observation
import MapKit
import SwiftUI

struct LinkProfileScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var model = LinkProfileModel()
    @State private var catalog = FieldToolsViewModel(reference: nil)
    @State private var endpointPicker: LinkProfileEndpoint? = .pointA
    @Environment(\.isHingedDevice) private var isHingedDevice
    @State private var isWideLayout = false
    /// Shared with the live map: picking a style here changes both.
    @State private var mapDisplayStyle = MapDisplayStyle.persisted

    var body: some View {
        GeometryReader { geometry in
            Group {
                if usesDuoWorkspace {
                    DuoTwoPaneLayout {
                        LinkProfilePersistentMap(
                            model: model,
                            nodes: catalog.nodes,
                            activeEndpoint: $endpointPicker,
                            mapDisplayStyle: $mapDisplayStyle,
                            // The pane runs up under the status bar and the invisible bar, so
                            // the style button and legend start below them.
                            promptTopClearance: geometry.safeAreaInsets.top
                        )
                        .safeAreaPadding(.trailing)
                        .clipped()
                    } secondary: {
                        LinkProfileEditor(
                            model: model,
                            activeEndpoint: $endpointPicker,
                            trailingPadding: 112,
                            usesCompactHeader: true,
                            topInset: 24
                        )
                        .background(Color(uiColor: .systemGroupedBackground))
                        .clipped()
                    }
                    // Both panes run to the top edge under the invisible bar, as the app's other
                    // Duo layouts do; the back button stays in the side column.
                    .ignoresSafeArea(.container, edges: [.top, .bottom])
                } else if geometry.size.width >= 900 {
                    // iPad: both panes run up under the transparent bar, which holds the back
                    // button and the map style menu on the tab bar's row; content starts below it.
                    HStack(spacing: 0) {
                        LinkProfilePersistentMap(
                            model: model,
                            nodes: catalog.nodes,
                            activeEndpoint: $endpointPicker,
                            mapDisplayStyle: $mapDisplayStyle,
                            showsStyleMenu: false,
                            promptTopClearance: geometry.safeAreaInsets.top
                        )
                        LinkProfileEditor(
                            model: model,
                            activeEndpoint: $endpointPicker,
                            usesCompactHeader: true,
                            topInset: geometry.safeAreaInsets.top
                        )
                        .frame(width: min(420, max(340, geometry.size.width * 0.34)))
                    }
                    .ignoresSafeArea(.container, edges: .top)
                } else {
                    LinkProfileCompactWorkspace(
                        model: model,
                        nodes: catalog.nodes,
                        activeEndpoint: $endpointPicker,
                        mapDisplayStyle: $mapDisplayStyle,
                        promptLeadingClearance: showsOverlayBackButton ? 60 : 0
                    )
                    // On a Duo the bar is shown for its back button (in the side column); the
                    // map still runs to the top edge under it.
                    .ignoresSafeArea(.container, edges: isHingedDevice ? .top : [])
                }
            }
            .onGeometryChange(for: Bool.self) { $0.size.width >= 900 } action: { isWideLayout = $0 }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        // iPhone: the map runs edge to edge under a floating back button. iPad and the Duo show
        // the navigation bar so the system places its back button (the tab bar's row on iPad,
        // the side column on the Duo); the bar is otherwise invisible, with no background and
        // no title, since the editor pane carries the heading.
        .navigationTitle(usesSystemBar ? "" : "Link Profile")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(usesSystemBar ? .visible : .hidden, for: .navigationBar)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar {
            // iPad only: every Duo layout keeps the style button on the map.
            if isWideLayout && !isHingedDevice {
                ToolbarItem(placement: .topBarLeading) {
                    LinkProfileMapStyleMenu(style: $mapDisplayStyle, inToolbar: true)
                }
            }
        }
        .overlay(alignment: .topLeading) {
            LinkProfileBackButton(action: dismiss.callAsFunction)
            .padding()
            .opacity(showsOverlayBackButton ? 1 : 0)
            .allowsHitTesting(showsOverlayBackButton)
        }
        .onChange(of: mapDisplayStyle) { _, style in
            UserDefaults.standard.set(style.rawValue, forKey: MapDisplayStyle.defaultsKey)
        }
        .task(id: "\(settings.sourceSession.id)|\(regionFilter.selectedRegion ?? "")") {
            catalog.configure(settings: settings)
            await catalog.load(region: regionFilter.selectedRegion)
        }
    }

    private var usesDuoWorkspace: Bool {
        isHingedDevice && horizontalSizeClass == .regular
    }

    /// iPad and the Duo use the system navigation bar's back button.
    /// Any Duo layout (two panes or one) and iPad; only the iPhone layout floats its own.
    private var usesSystemBar: Bool {
        isHingedDevice || isWideLayout
    }

    private var showsOverlayBackButton: Bool {
        !usesSystemBar
    }

}

/// Standard, Satellite or Hybrid, as on the live map.
private struct LinkProfileMapStyleMenu: View {
    @Binding var style: MapDisplayStyle
    /// In the navigation bar the system draws the button's background.
    var inToolbar = false

    var body: some View {
        Menu {
            ForEach(MapDisplayStyle.allCases) { option in
                Button {
                    style = option
                } label: {
                    if style == option {
                        Label(option.title, systemImage: "checkmark")
                    } else {
                        Label(option.title, systemImage: option.systemImage)
                    }
                }
            }
        } label: {
            if inToolbar {
                Image(systemName: "map")
            } else {
                Image(systemName: "map")
                    .font(.title3)
                    .frame(width: 44, height: 44)
                    .background(.regularMaterial, in: Circle())
            }
        }
        .accessibilityLabel("Map style")
    }
}

private struct LinkProfileBackButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "chevron.backward")
                .font(.title2.weight(.semibold))
                .frame(width: 44, height: 44)
                .background(.regularMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Back")
    }
}

/// iPhone: the map fills the screen under a panel that collapses to its header (the prompt while
/// points are placed, then the verdict) and expands to the points, radio and results. Drag the
/// handle or header, or tap the handle.
private struct LinkProfileCompactWorkspace: View {
    let model: LinkProfileModel
    let nodes: [MeshNode]
    @Binding var activeEndpoint: LinkProfileEndpoint?
    @Binding var mapDisplayStyle: MapDisplayStyle
    let promptLeadingClearance: CGFloat
    @State private var isExpanded = false
    @State private var headerHeight: CGFloat = 0
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        // The panel floats over the map: the map's safe area only clears the collapsed panel, so
        // expanding it covers the map instead of moving the camera and controls.
        GeometryReader { geometry in
            ZStack(alignment: .bottom) {
                LinkProfilePersistentMap(
                    model: model,
                    nodes: nodes,
                    activeEndpoint: $activeEndpoint,
                    mapDisplayStyle: $mapDisplayStyle,
                    promptLeadingClearance: promptLeadingClearance
                )
                .safeAreaPadding(.bottom, headerHeight + 16)
                VStack(spacing: 0) {
                    VStack(spacing: 0) {
                        Button {
                            setExpanded(!isExpanded)
                        } label: {
                            Capsule()
                                .fill(.secondary.opacity(0.45))
                                .frame(width: 36, height: 5)
                                .frame(maxWidth: .infinity, minHeight: 24)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(isExpanded ? "Hide link details" : "Show link details")
                        LinkProfileHeader(model: model, activeEndpoint: $activeEndpoint)
                            .padding(.horizontal)
                            .padding(.bottom, 14)
                    }
                    .contentShape(Rectangle())
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { headerHeight = $0 }
                    .gesture(
                        DragGesture(minimumDistance: 12).onEnded { value in
                            if value.translation.height < -30 { setExpanded(true) }
                            if value.translation.height > 30 { setExpanded(false) }
                        }
                    )
                    if isExpanded {
                        ScrollView {
                            LinkProfileDetails(model: model, activeEndpoint: $activeEndpoint)
                                .padding(.horizontal)
                                .padding(.bottom)
                        }
                        // Open nearly to the top, leaving the back button and a strip of map.
                        .frame(height: max(160, geometry.size.height - headerHeight - 72))
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                // Light mode: a cool grey-blue wash over the mesh, which on its own matches the
                // light map's land and water closely enough for the panel to blend in.
                .background(colorScheme == .light ? Color(red: 0.62, green: 0.70, blue: 0.82).opacity(0.25) : .clear)
                .meshPageBackground()
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                // A hairline edge and a soft shadow lift the panel off a light map, where the
                // subdued mesh is close to the map's own colours.
                .overlay {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.28), radius: 18, y: 6)
                .padding(.horizontal, 8)
                // A gap above the tab bar so the panel reads as floating.
                .padding(.bottom, 8)
            }
        }
        // Choosing a point needs the map.
        .onChange(of: activeEndpoint) { _, endpoint in
            if endpoint != nil { setExpanded(false) }
        }
    }

    private func setExpanded(_ expanded: Bool) {
        withAnimation(.easeInOut) { isExpanded = expanded }
    }
}

/// iPad's side column and the Duo's right screen: the header stays put while the rest scrolls.
private struct LinkProfileEditor: View {
    @Bindable var model: LinkProfileModel
    @Binding var activeEndpoint: LinkProfileEndpoint?
    var trailingPadding: CGFloat = 20
    var usesCompactHeader = false
    var topInset: CGFloat = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if usesCompactHeader {
                Label("Link Profile", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                    .font(.title2.bold())
            }
            LinkProfileHeader(model: model, activeEndpoint: $activeEndpoint)
            ScrollView {
                LinkProfileDetails(model: model, activeEndpoint: $activeEndpoint)
                    .padding(.bottom, 20)
            }
            .scrollIndicators(.hidden)
        }
        .padding(.leading, 20)
        .padding(.trailing, trailingPadding)
        .padding(.top, 20 + topInset)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .meshPageBackground()
    }
}

private struct LinkProfilePersistentMap: View {
    let model: LinkProfileModel
    let nodes: [MeshNode]
    @Binding var activeEndpoint: LinkProfileEndpoint?
    @Binding var mapDisplayStyle: MapDisplayStyle
    /// iPad puts the style menu in the navigation bar instead.
    var showsStyleMenu = true
    var promptLeadingClearance: CGFloat = 0
    var promptTopClearance: CGFloat = 0
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(AnalyzerSourceRegistry.self) private var analyzerSourceRegistry
    @Environment(AnalyzerSettings.self) private var settings
    // Opens on the region selected for the live map; until that is known, the known nodes.
    @State private var position: MapCameraPosition = .automatic
    @State private var hasFramedRegion = false
    /// Where the camera last settled, for laying out the route's dashes.
    @State private var routeView: LinkRouteView?
    @Namespace private var mapScope

    var body: some View {
        MapReader { proxy in
            Map(position: $position, scope: mapScope) {
                // Native markers rather than SwiftUI annotations: each annotation was a view of its
                // own that caught fingers, so a pinch starting on a node never reached the map.
                // Choosing a node is handled by the map's tap below.
                // A node that is an endpoint, or about to be, gives up its marker to the endpoint's.
                ForEach(knownNodes.filter { !occupied.contains(where: $0.coordinate.isSame) }, id: \.node.id) { known in
                    Marker(known.node.name ?? String(localized: "Known node"), systemImage: "antenna.radiowaves.left.and.right", coordinate: known.coordinate)
                        .tint(NodeScopeStyle.signal)
                }
                // Lettered in the endpoint's colour and named after the node it sits on. While a
                // new spot is pending, it stands in for the endpoint's old marker.
                if let pointA = model.coordinateA, pendingTentative?.endpoint != .pointA {
                    Marker(model.nameA ?? String(localized: "Point A"), monogram: Text(verbatim: "A"), coordinate: pointA)
                        .tint(LinkProfileEndpoint.pointA.color)
                }
                if let pointB = model.coordinateB, pendingTentative?.endpoint != .pointB {
                    Marker(model.nameB ?? String(localized: "Point B"), monogram: Text(verbatim: "B"), coordinate: pointB)
                        .tint(LinkProfileEndpoint.pointB.color)
                }
                if let result = model.result {
                    analyzedRoute(result)
                } else if let pointA = model.coordinateA, let pointB = model.coordinateB {
                    // A thin solid line until the analysis colours it, so dashes always mean a result.
                    MapPolyline(coordinates: [pointA, pointB])
                        .stroke(.black.opacity(0.25), style: StrokeStyle(lineWidth: 4.5, lineCap: .round))
                    MapPolyline(coordinates: [pointA, pointB])
                        .stroke(NodeScopeStyle.signal.opacity(0.85), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                }
                // The pending choice, exactly where the endpoint will go: on a known node it takes
                // that node's place, so the node visibly changes into Point A or B.
                if let tentative = pendingTentative {
                    Marker(tentative.name ?? String(localized: "Dropped pin"), monogram: Text(verbatim: tentative.endpoint.letter), coordinate: tentative.coordinate)
                        .tint(tentative.endpoint.color)
                }
            }
            .mapStyle(mapDisplayStyle.realisticStyle)
            .mapControls {
                MapScaleView()
            }
            // The style menu, with the compass placed under it so the two never overlap.
            .overlay(alignment: .topTrailing) {
                VStack(spacing: 8) {
                    if showsStyleMenu {
                        LinkProfileMapStyleMenu(style: $mapDisplayStyle)
                    }
                    MapCompass(scope: mapScope)
                }
                .padding()
                .padding(.top, promptTopClearance)
            }
            // Attached only while choosing a point: a SwiftUI gesture on the map can hold back
            // MapKit's own pinch and pan.
            .simultaneousGesture(
                SpatialTapGesture().onEnded { value in
                    guard let activeEndpoint else { return }
                    // A known node's marker under the finger wins over the spot itself.
                    if let known = knownNode(at: value.location, proxy: proxy) {
                        model.tentative = LinkProfileTentative(endpoint: activeEndpoint, coordinate: known.coordinate, name: known.node.name)
                    } else if let coordinate = proxy.convert(value.location, from: .local) {
                        model.tentative = LinkProfileTentative(endpoint: activeEndpoint, coordinate: coordinate, name: nil)
                    }
                },
                including: activeEndpoint == nil ? .subviews : .all
            )
            // Prompts and confirmation live in the header; the map only carries the legend.
            .overlay(alignment: .topLeading) {
                if model.result != nil, activeEndpoint == nil {
                    HStack(spacing: 10) {
                        ForEach(NodePlannerPathClearance.allCases, id: \.self) { clearance in
                            LinkProfileClearanceLegend(clearance: clearance)
                        }
                    }
                    .font(.caption)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.regularMaterial, in: Capsule())
                    .padding()
                    .padding(.leading, promptLeadingClearance)
                    .padding(.top, promptTopClearance)
                }
            }
            // The route's dashes are laid out in points, so they need the scale once each move ends.
            .onMapCameraChange(frequency: .onEnd) { context in
                guard let point = proxy.convert(context.camera.centerCoordinate, to: .local),
                      let east = proxy.convert(CGPoint(x: point.x + 100, y: point.y), from: .local) else { return }
                let center = MKMapPoint(context.camera.centerCoordinate)
                let scale = hypot(MKMapPoint(east).x - center.x, MKMapPoint(east).y - center.y) / 100
                routeView = scale > 0 ? LinkRouteView(visible: context.rect, mapPointsPerPoint: scale) : nil
            }
        }
        .mapScope(mapScope)
        .accessibilityLabel("Map for selecting Point A and Point B")
        .onChange(of: activeEndpoint) { _, _ in model.tentative = nil }
        // Once both endpoints are chosen, show the whole path.
        .onChange(of: fittedEndpoints) { _, endpoints in
            guard endpoints != nil else { return }
            fitEndpoints()
        }
        // Region centers can arrive after the screen opens; frame once, and never over endpoints.
        .task(id: "\(regionFilter.selectedRegion ?? "all")|\(regionFilter.iataCoords.count)|\(regionFilter.options.count)") {
            guard !hasFramedRegion, model.coordinateA == nil, model.coordinateB == nil,
                  let region = RegionCameraFraming.openingRegion(
                    regionFilter: regionFilter, registry: analyzerSourceRegistry, host: settings.host
                  ) else { return }
            position = .region(region)
            hasFramedRegion = true
        }
    }

    /// Rounded dashes coloured by clearance, each on a dark halo of the same shape. MapKit's own
    /// dash pattern came out larger than asked and its phase couldn't line stretches up, so each
    /// dash is a short round-ended line laid out for where the camera last settled; until it has
    /// settled, the route is solid.
    @MapContentBuilder
    private func analyzedRoute(_ result: NodePlannerLinkAnalysis) -> some MapContent {
        let pieces = LinkRouteDash.pieces(result: result, view: routeView)
        ForEach(pieces) { piece in
            MapPolyline(coordinates: piece.coordinates)
                .stroke(.black.opacity(0.4), style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round))
        }
        ForEach(pieces) { piece in
            MapPolyline(coordinates: piece.coordinates)
                .stroke(piece.clearance.color, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
        }
    }

    /// Both endpoints while no endpoint is being chosen; a change of this pair re-fits the map.
    private var fittedEndpoints: [Double]? {
        guard activeEndpoint == nil, let pointA = model.coordinateA, let pointB = model.coordinateB else { return nil }
        return [pointA.latitude, pointA.longitude, pointB.latitude, pointB.longitude]
    }

    /// Frames A and B with room around them, more above for the prompt, legend and pins. The map
    /// already keeps its camera inside the safe area, so the compact results panel needs nothing extra.
    private func fitEndpoints() {
        guard let pointA = model.coordinateA, let pointB = model.coordinateB else { return }
        let a = MKMapPoint(pointA)
        let b = MKMapPoint(pointB)
        // No tighter than about 400 m across, so two close points do not zoom to street level.
        let minimum = 400 * MKMapPointsPerMeterAtLatitude((pointA.latitude + pointB.latitude) / 2)
        let width = max(abs(a.x - b.x), minimum)
        let height = max(abs(a.y - b.y), minimum)
        let rect = MKMapRect(
            x: min(a.x, b.x) - (width - abs(a.x - b.x)) / 2 - width * 0.2,
            y: min(a.y, b.y) - (height - abs(a.y - b.y)) / 2 - height * 0.45,
            width: width * 1.4,
            height: height * 1.65
        )
        hasFramedRegion = true
        withAnimation(.easeInOut) { position = .rect(rect) }
    }

    private var pendingTentative: LinkProfileTentative? {
        model.tentative.flatMap { $0.endpoint == activeEndpoint ? $0 : nil }
    }

    /// Where endpoint markers stand: the endpoints and any pending choice.
    private var occupied: [CLLocationCoordinate2D] {
        [model.coordinateA, model.coordinateB, pendingTentative?.coordinate].compactMap { $0 }
    }

    private var knownNodes: [(node: MeshNode, coordinate: CLLocationCoordinate2D)] {
        Array(nodes.lazy.compactMap { node in node.coordinate.map { (node, $0) } }.prefix(200))
    }

    /// The node whose marker covers `location`. A marker's balloon stands above its coordinate,
    /// so the hit area runs from a little below the point to the top of the balloon.
    private func knownNode(at location: CGPoint, proxy: MapProxy) -> (node: MeshNode, coordinate: CLLocationCoordinate2D)? {
        knownNodes
            .compactMap { known -> (known: (node: MeshNode, coordinate: CLLocationCoordinate2D), distance: CGFloat)? in
                guard let point = proxy.convert(known.coordinate, to: .local) else { return nil }
                let dx = location.x - point.x
                let dy = location.y - point.y
                guard abs(dx) <= 24, (-48...12).contains(dy) else { return nil }
                return (known, hypot(dx, dy + 20))
            }
            .min { $0.distance < $1.distance }?
            .known
    }
}

private struct LinkProfileTentative {
    let endpoint: LinkProfileEndpoint
    let coordinate: CLLocationCoordinate2D
    /// The known node tapped, if any.
    let name: String?
}

private enum LinkProfileEndpoint: String, Identifiable {
    case pointA
    case pointB

    var id: String { rawValue }

    var title: LocalizedStringResource {
        switch self {
        case .pointA: "Point A"
        case .pointB: "Point B"
        }
    }

    var letter: String { self == .pointA ? "A" : "B" }

    /// The map's marker tints.
    var color: Color { self == .pointA ? .blue : .orange }

}

private enum LinkProfileRadioPreset: String, CaseIterable, Identifiable {
    case unitedStatesNarrow
    case europeNarrow
    case custom

    var id: String { rawValue }

    var title: LocalizedStringResource {
        switch self {
        case .unitedStatesNarrow: "US/Canada Narrow"
        case .europeNarrow: "EU/UK Narrow"
        case .custom: "Custom"
        }
    }

    var frequencyMHz: Double? {
        switch self {
        case .unitedStatesNarrow: 910.525
        case .europeNarrow: 869.618
        case .custom: nil
        }
    }

    var transmitPowerDBm: Double? {
        switch self {
        case .unitedStatesNarrow: 22
        case .europeNarrow: 14
        case .custom: nil
        }
    }

    var receiverSensitivityDBm: Double? {
        switch self {
        case .unitedStatesNarrow: -128
        case .europeNarrow: -130
        case .custom: nil
        }
    }
}

@MainActor
@Observable
private final class LinkProfileModel {
    var latitudeA: Double?
    var longitudeA: Double?
    var latitudeB: Double?
    var longitudeB: Double?
    var heightA = 10.0
    var heightB = 10.0
    /// The known node each endpoint was placed on; nil for a dropped pin or typed coordinates.
    var nameA: String?
    var nameB: String?
    /// An unconfirmed tap while an endpoint is being chosen; confirmed from the header.
    var tentative: LinkProfileTentative?
    var radioPreset = LinkProfileRadioPreset.unitedStatesNarrow
    var frequencyMHz = 910.525
    var transmitPowerDBm = 22.0
    var gainADB = 2.15
    var gainBDB = 2.15
    var lossADB = 1.0
    var lossBDB = 1.0
    var receiverSensitivityDBm = -128.0
    var groundElevationA: Double?
    var groundElevationB: Double?
    var isLoadingElevationA = false
    var isLoadingElevationB = false
    var isAnalyzing = false
    var result: NodePlannerLinkAnalysis?
    var errorMessage: LocalizedStringResource?

    private let analyzer: NodePlannerLinkAnalyzer
    private let elevationProvider: any NodePlannerElevationProviding
    @ObservationIgnored private var elevationTaskA: Task<Void, Never>?
    @ObservationIgnored private var elevationTaskB: Task<Void, Never>?
    @ObservationIgnored private var analysisTask: Task<Void, Never>?

    var coordinateA: CLLocationCoordinate2D? {
        guard let latitudeA, let longitudeA else { return nil }
        return CLLocationCoordinate2D(latitude: latitudeA, longitude: longitudeA)
    }

    var coordinateB: CLLocationCoordinate2D? {
        guard let latitudeB, let longitudeB else { return nil }
        return CLLocationCoordinate2D(latitude: latitudeB, longitude: longitudeB)
    }

    func name(for endpoint: LinkProfileEndpoint) -> String? {
        endpoint == .pointA ? nameA : nameB
    }

    func coordinate(for endpoint: LinkProfileEndpoint) -> CLLocationCoordinate2D? {
        endpoint == .pointA ? coordinateA : coordinateB
    }

    func groundElevation(for endpoint: LinkProfileEndpoint) -> Double? {
        endpoint == .pointA ? groundElevationA : groundElevationB
    }

    func isLoadingElevation(for endpoint: LinkProfileEndpoint) -> Bool {
        endpoint == .pointA ? isLoadingElevationA : isLoadingElevationB
    }

    init(elevationProvider: any NodePlannerElevationProviding = OpenMeteoElevationProvider()) {
        self.elevationProvider = elevationProvider
        analyzer = NodePlannerLinkAnalyzer(elevationProvider: elevationProvider)
        // A typical rooftop mast: 20 ft in imperial, 6 m in metric.
        let unit = UserDefaults.standard.string(forKey: DistanceUnit.defaultsKey).flatMap(DistanceUnit.init(rawValue:)) ?? .imperial
        heightA = unit == .imperial ? 6.096 : 6
        heightB = heightA
    }

    /// Any change clears the result; once both endpoints are set the link is analyzed on its own,
    /// shortly after the last change, so there is no Analyze button.
    func scheduleAnalysis(after delay: Duration = .milliseconds(600)) {
        analysisTask?.cancel()
        result = nil
        errorMessage = nil
        guard coordinateA != nil, coordinateB != nil else {
            isAnalyzing = false
            return
        }
        isAnalyzing = true
        analysisTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.runAnalysis()
        }
    }

    /// Try Again after a failure: analyzes at once.
    func analyze() {
        scheduleAnalysis(after: .zero)
    }

    private func runAnalysis() async {
        guard let latitudeA, let longitudeA, let latitudeB, let longitudeB else {
            isAnalyzing = false
            return
        }
        // A newer change cancels this run; only the current run may report.
        defer { if !Task.isCancelled { isAnalyzing = false } }

        do {
            let pointA = try NodePlannerCoordinate(latitude: latitudeA, longitude: longitudeA)
            let pointB = try NodePlannerCoordinate(latitude: latitudeB, longitude: longitudeB)
            let analysis = try await analyzer.analyze(
                NodePlannerLinkAnalysisRequest(
                    pointA: pointA,
                    pointB: pointB,
                    heightAMeters: heightA,
                    heightBMeters: heightB,
                    frequencyMHz: frequencyMHz,
                    refractionK: 4.0 / 3.0,
                    linkBudget: NodePlannerLinkBudget(
                        transmitPowerDBm: transmitPowerDBm,
                        gainADB: gainADB,
                        gainBDB: gainBDB,
                        lossADB: lossADB,
                        lossBDB: lossBDB,
                        receiverSensitivityDBm: receiverSensitivityDBm
                    ),
                    sampleCount: nil
                )
            )
            guard !Task.isCancelled else { return }
            result = analysis
            groundElevationA = analysis.terrain.samples.first?.elevationMeters
            groundElevationB = analysis.terrain.samples.last?.elevationMeters
        } catch {
            guard !Task.isCancelled, !(error is CancellationError) else { return }
            switch error {
            case NodePlannerElevationError.invalidCoordinate:
                errorMessage = "Enter valid latitude and longitude values."
            case NodePlannerElevationError.unavailable:
                errorMessage = "Elevation data is unavailable. Try again when you have a connection."
            default:
                errorMessage = "The link could not be analyzed with these settings."
            }
        }
    }

    /// Places [endpoint], named after the known node it was chosen on, if any.
    func set(_ coordinate: CLLocationCoordinate2D, for endpoint: LinkProfileEndpoint, name: String? = nil) {
        switch endpoint {
        case .pointA:
            latitudeA = coordinate.latitude
            longitudeA = coordinate.longitude
            nameA = name
        case .pointB:
            latitudeB = coordinate.latitude
            longitudeB = coordinate.longitude
            nameB = name
        }
        scheduleElevationLookup(for: endpoint)
        scheduleAnalysis()
    }

    func clear(_ endpoint: LinkProfileEndpoint) {
        switch endpoint {
        case .pointA:
            elevationTaskA?.cancel()
            latitudeA = nil
            longitudeA = nil
            nameA = nil
            groundElevationA = nil
            isLoadingElevationA = false
        case .pointB:
            elevationTaskB?.cancel()
            latitudeB = nil
            longitudeB = nil
            nameB = nil
            groundElevationB = nil
            isLoadingElevationB = false
        }
        scheduleAnalysis()
    }

    /// Clears both endpoints and the result; heights and radio settings are kept.
    func startOver() {
        tentative = nil
        clear(.pointA)
        clear(.pointB)
    }

    /// Typed coordinates are no longer the known node they were placed on.
    func endpointCoordinatesChanged(_ endpoint: LinkProfileEndpoint) {
        switch endpoint {
        case .pointA: nameA = nil
        case .pointB: nameB = nil
        }
        scheduleElevationLookup(for: endpoint)
        scheduleAnalysis()
    }

    func configurationChanged() {
        scheduleAnalysis()
    }

    func applyRadioPreset(_ preset: LinkProfileRadioPreset) {
        radioPreset = preset
        if let value = preset.frequencyMHz { frequencyMHz = value }
        if let value = preset.transmitPowerDBm { transmitPowerDBm = value }
        if let value = preset.receiverSensitivityDBm { receiverSensitivityDBm = value }
        configurationChanged()
    }

    private func scheduleElevationLookup(for endpoint: LinkProfileEndpoint) {
        let coordinate = endpoint == .pointA ? coordinateA : coordinateB
        guard let coordinate,
              let plannerCoordinate = try? NodePlannerCoordinate(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude
              ) else {
            switch endpoint {
            case .pointA:
                elevationTaskA?.cancel()
                groundElevationA = nil
            case .pointB:
                elevationTaskB?.cancel()
                groundElevationB = nil
            }
            setElevationLoading(false, for: endpoint)
            return
        }

        setElevationLoading(true, for: endpoint)
        let task = Task { [weak self, elevationProvider] in
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            do {
                let profile = try await elevationProvider.elevations(
                    for: [plannerCoordinate, plannerCoordinate]
                )
                guard !Task.isCancelled, let elevation = profile.samples.first?.elevationMeters else { return }
                self?.applyElevation(elevation, coordinate: coordinate, for: endpoint)
            } catch {
                guard !Task.isCancelled else { return }
                self?.setElevationLoading(false, for: endpoint)
            }
        }
        switch endpoint {
        case .pointA:
            elevationTaskA?.cancel()
            elevationTaskA = task
        case .pointB:
            elevationTaskB?.cancel()
            elevationTaskB = task
        }
    }

    private func applyElevation(
        _ elevation: Double,
        coordinate: CLLocationCoordinate2D,
        for endpoint: LinkProfileEndpoint
    ) {
        let current = endpoint == .pointA ? coordinateA : coordinateB
        guard current?.latitude == coordinate.latitude,
              current?.longitude == coordinate.longitude else { return }
        switch endpoint {
        case .pointA:
            groundElevationA = elevation
        case .pointB:
            groundElevationB = elevation
        }
        setElevationLoading(false, for: endpoint)
    }

    private func setElevationLoading(_ isLoading: Bool, for endpoint: LinkProfileEndpoint) {
        switch endpoint {
        case .pointA: isLoadingElevationA = isLoading
        case .pointB: isLoadingElevationB = isLoading
        }
    }
}

/// The always-visible line: what to do next while points are placed (choosing one is confirmed
/// here, so the map stays clear), then analyzing, an error, or the verdict. Reset sits beside it
/// once a point is placed, so starting over never needs the panel opened.
private struct LinkProfileHeader: View {
    @Bindable var model: LinkProfileModel
    @Binding var activeEndpoint: LinkProfileEndpoint?
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    var body: some View {
        HStack(spacing: 12) {
            status
            if activeEndpoint == nil && (model.coordinateA != nil || model.coordinateB != nil) {
                Button {
                    model.startOver()
                    activeEndpoint = .pointA
                } label: {
                    Label("Reset", systemImage: "arrow.counterclockwise")
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var status: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let endpoint = activeEndpoint {
                let tentative = model.tentative.flatMap { $0.endpoint == endpoint ? $0 : nil }
                Text("Choose Point \(endpoint.letter)")
                    .font(.headline)
                Text(tentative.map { $0.name ?? String(localized: "Dropped pin") } ?? String(localized: "Tap the map or a known node."))
                    .foregroundStyle(.secondary)
                Text("Confirming sends its coordinates to Open-Meteo for elevation.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack {
                    Button("Cancel") {
                        model.tentative = nil
                        activeEndpoint = nil
                    }
                    .buttonStyle(.bordered)
                    Button("Use Point \(endpoint.letter)") {
                        guard let tentative else { return }
                        model.set(tentative.coordinate, for: endpoint, name: tentative.name)
                        model.tentative = nil
                        activeEndpoint = endpoint == .pointA && model.coordinateB == nil ? .pointB : nil
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(tentative == nil)
                }
                .padding(.top, 4)
            } else if model.coordinateA == nil || model.coordinateB == nil {
                let next: LinkProfileEndpoint = model.coordinateA == nil ? .pointA : .pointB
                Text("Place Point A and Point B to analyze the link.")
                    .font(.headline)
                Button("Choose Point \(next.letter)") { activeEndpoint = next }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 4)
            } else if let errorMessage = model.errorMessage {
                Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                Button("Try Again") { model.analyze() }
                    .buttonStyle(.bordered)
            } else if let result = model.result, !model.isAnalyzing {
                let status = result.result.clearanceStatus
                Label {
                    Text(status.title)
                } icon: {
                    Image(systemName: status.symbol)
                        .foregroundStyle(status.pathClearance.color)
                }
                .font(.headline)
                Text(summary(result.result))
                    .foregroundStyle(.secondary)
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Analyzing link…")
                        .font(.headline)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
    }

    private func summary(_ result: NodePlannerPathResult) -> String {
        let distance = distanceUnit.format(kilometers: result.distanceMeters / 1_000)
        guard let margin = result.linkMarginDB else { return distance }
        return String(localized: "Link margin \(margin.formatted(.number.precision(.fractionLength(1)))) dB · \(distance)")
    }
}

extension NodePlannerClearanceStatus {
    fileprivate var title: LocalizedStringResource {
        switch self {
        case .clear: "Clear terrain path"
        case .marginal: "Marginal clearance"
        case .partialObstruction: "Partially obstructed"
        case .blocked: "Blocked by terrain"
        }
    }

    fileprivate var symbol: String {
        switch self {
        case .clear: "checkmark.circle.fill"
        case .marginal: "exclamationmark.circle.fill"
        case .partialObstruction: "circle.lefthalf.filled"
        case .blocked: "xmark.octagon.fill"
        }
    }

    /// The verdict's colour, matching the path: both obstructed statuses read as obstructed.
    fileprivate var pathClearance: NodePlannerPathClearance {
        switch self {
        case .clear: .clear
        case .marginal: .marginal
        case .partialObstruction, .blocked: .obstructed
        }
    }
}

/// Everything under the header: the two points, the radio and the full result.
private struct LinkProfileDetails: View {
    @Bindable var model: LinkProfileModel
    @Binding var activeEndpoint: LinkProfileEndpoint?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            LinkProfilePointCard(model: model, endpoint: .pointA) { activeEndpoint = .pointA }
            LinkProfilePointCard(model: model, endpoint: .pointB) { activeEndpoint = .pointB }
            LinkProfileRadioSettings(model: model)
            if let result = model.result {
                LinkProfileResultCard(result: result)
            }
            Text("Planning estimates only: terrain does not account for buildings, foliage or interference. Placing points sends their coordinates to Open-Meteo for elevation; results are cached on this device.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

/// One endpoint: its name (the known node, or a dropped pin), ground elevation and antenna
/// height. Tapping opens the antenna settings, moving the point, and typed coordinates.
private struct LinkProfilePointCard: View {
    @Bindable var model: LinkProfileModel
    let endpoint: LinkProfileEndpoint
    let choose: () -> Void
    @State private var isExpanded = false
    @State private var showsCoordinates = false
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                withAnimation(.easeInOut) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 12) {
                    Text(endpoint.letter)
                        .font(.headline.bold())
                        .foregroundStyle(.white)
                        .frame(width: 30, height: 30)
                        .background(endpoint.color, in: Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.headline)
                            .foregroundStyle(.primary)
                        Text(summary)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.down")
                        .rotationEffect(.degrees(isExpanded ? 180 : 0))
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(isExpanded ? "Expanded" : "Collapsed")

            if isExpanded {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("Additional antenna height")
                        Spacer()
                        Text(distanceUnit.formatHeight(meters: height.wrappedValue))
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                    }
                    Slider(value: displayedHeight, in: distanceUnit.antennaHeightRange, step: 1)
                        .accessibilityLabel("Additional antenna height")
                        .accessibilityValue(distanceUnit.formatHeight(meters: height.wrappedValue))
                }
                LabeledContent("Total height") {
                    if let ground = model.groundElevation(for: endpoint) {
                        Text(distanceUnit.formatHeight(meters: ground + height.wrappedValue))
                            .monospacedDigit()
                    } else {
                        Text("—")
                    }
                }
                LinkProfileNumericSetting(title: "Antenna gain", value: budgetBinding(\.gainADB, \.gainBDB), unit: "dBi")
                LinkProfileNumericSetting(title: "System loss", value: budgetBinding(\.lossADB, \.lossBDB), unit: "dB")
                ViewThatFits(in: .horizontal) {
                    HStack { actions }
                    VStack(alignment: .leading) { actions }
                }
                .buttonStyle(.bordered)
                if showsCoordinates {
                    LabeledContent("Latitude") {
                        TextField("Latitude", value: latitude, format: .number.precision(.fractionLength(0...6)))
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numbersAndPunctuation)
                    }
                    LabeledContent("Longitude") {
                        TextField("Longitude", value: longitude, format: .number.precision(.fractionLength(0...6)))
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numbersAndPunctuation)
                    }
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .instrumentCard()
    }

    @ViewBuilder
    private var actions: some View {
        Button(hasCoordinate ? "Move on Map" : "Choose on Map", systemImage: "mappin.and.ellipse", action: choose)
        Button(showsCoordinates ? "Hide Coordinates" : "Enter Coordinates", systemImage: "number") {
            showsCoordinates.toggle()
        }
    }

    private var hasCoordinate: Bool { model.coordinate(for: endpoint) != nil }

    private var title: String {
        guard hasCoordinate else { return String(localized: "Point \(endpoint.letter) not placed") }
        return model.name(for: endpoint) ?? String(localized: "Dropped pin")
    }

    private var summary: String {
        let antenna = String(localized: "Antenna \(distanceUnit.formatHeight(meters: height.wrappedValue))")
        let ground: String? = if model.isLoadingElevation(for: endpoint) {
            String(localized: "Ground elevation: Loading…")
        } else if let meters = model.groundElevation(for: endpoint) {
            String(localized: "Ground elevation: \(distanceUnit.formatHeight(meters: meters))")
        } else if hasCoordinate {
            String(localized: "Ground elevation: Unavailable")
        } else {
            nil
        }
        return [ground, antenna].compactMap { $0 }.joined(separator: " · ")
    }

    private var height: Binding<Double> {
        Binding(
            get: { endpoint == .pointA ? model.heightA : model.heightB },
            set: {
                if endpoint == .pointA { model.heightA = $0 } else { model.heightB = $0 }
                model.configurationChanged()
            }
        )
    }

    private var displayedHeight: Binding<Double> {
        Binding(
            get: { distanceUnit.displayHeight(meters: height.wrappedValue) },
            set: { height.wrappedValue = distanceUnit.meters(fromDisplayedHeight: $0) }
        )
    }

    /// Gain and loss belong to this end; editing them does not make the radio custom.
    private func budgetBinding(
        _ a: ReferenceWritableKeyPath<LinkProfileModel, Double>,
        _ b: ReferenceWritableKeyPath<LinkProfileModel, Double>
    ) -> Binding<Double> {
        let keyPath = endpoint == .pointA ? a : b
        return Binding(
            get: { model[keyPath: keyPath] },
            set: {
                model[keyPath: keyPath] = $0
                model.configurationChanged()
            }
        )
    }

    private var latitude: Binding<Double?> {
        Binding(
            get: { endpoint == .pointA ? model.latitudeA : model.latitudeB },
            set: {
                if endpoint == .pointA { model.latitudeA = $0 } else { model.latitudeB = $0 }
                model.endpointCoordinatesChanged(endpoint)
            }
        )
    }

    private var longitude: Binding<Double?> {
        Binding(
            get: { endpoint == .pointA ? model.longitudeA : model.longitudeB },
            set: {
                if endpoint == .pointA { model.longitudeA = $0 } else { model.longitudeB = $0 }
                model.endpointCoordinatesChanged(endpoint)
            }
        )
    }
}

/// The two presets, then the three values a preset sets. Editing one makes the radio custom,
/// and neither preset is then selected.
private struct LinkProfileRadioSettings: View {
    @Bindable var model: LinkProfileModel
    @State private var isCustomizing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Radio settings")
                .font(.headline)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { presets }
                VStack(alignment: .leading, spacing: 8) { presets }
            }
            Button {
                withAnimation(.easeInOut) { isCustomizing.toggle() }
            } label: {
                HStack {
                    Text(customizeTitle)
                    Spacer()
                    Image(systemName: "chevron.down")
                        .rotationEffect(.degrees(isCustomizing ? 180 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.tint)
            if isCustomizing {
                LinkProfileNumericSetting(title: "Frequency", value: customBinding(\.frequencyMHz), unit: "MHz")
                LinkProfileNumericSetting(title: "Transmit power", value: customBinding(\.transmitPowerDBm), unit: "dBm")
                LinkProfileNumericSetting(title: "Receiver sensitivity", value: customBinding(\.receiverSensitivityDBm), unit: "dBm")
            }
        }
        .padding()
        .instrumentCard()
    }

    @ViewBuilder
    private var presets: some View {
        ForEach([LinkProfileRadioPreset.unitedStatesNarrow, .europeNarrow]) { preset in
            if model.radioPreset == preset {
                Button { model.applyRadioPreset(preset) } label: { Text(preset.title) }
                    .buttonStyle(.borderedProminent)
            } else {
                Button { model.applyRadioPreset(preset) } label: { Text(preset.title) }
                    .buttonStyle(.bordered)
            }
        }
    }

    private var customizeTitle: LocalizedStringResource {
        if isCustomizing { return "Hide radio values" }
        return model.radioPreset == .custom ? "Customize radio · edited" : "Customize radio"
    }

    private func customBinding(_ keyPath: ReferenceWritableKeyPath<LinkProfileModel, Double>) -> Binding<Double> {
        Binding(
            get: { model[keyPath: keyPath] },
            set: {
                model[keyPath: keyPath] = $0
                model.radioPreset = .custom
                model.configurationChanged()
            }
        )
    }
}

private struct LinkProfileNumericSetting: View {
    let title: LocalizedStringResource
    @Binding var value: Double
    let unit: String

    var body: some View {
        LabeledContent {
            HStack(spacing: 4) {
                TextField(title, value: $value, format: .number.precision(.fractionLength(0...3)))
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.numbersAndPunctuation)
                Text(unit)
                    .foregroundStyle(.secondary)
            }
        } label: {
            Text(title)
        }
    }
}

private struct LinkProfileResultCard: View {
    let result: NodePlannerLinkAnalysis
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            LinkProfileTerrainCanvas(analysis: result)
            Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 8) {
                LinkProfileMetricRow(
                    label: "Distance",
                    value: distanceUnit.format(kilometers: result.result.distanceMeters / 1_000)
                )
                LinkProfileMetricRow(label: "Initial bearing", value: bearingText)
                LinkProfileMetricRow(
                    label: "Free-space path loss",
                    value: result.result.freeSpacePathLossDB.formatted(.number.precision(.fractionLength(1))) + " dB"
                )
                LinkProfileMetricRow(
                    label: "Diffraction loss",
                    value: result.result.peakDiffractionLossDB.formatted(.number.precision(.fractionLength(1))) + " dB"
                )
                LinkProfileMetricRow(
                    label: "Total path loss",
                    value: result.result.totalPathLossDB.formatted(.number.precision(.fractionLength(1))) + " dB"
                )
                if let receivePower = result.result.receivePowerDBm {
                    LinkProfileMetricRow(
                        label: "Predicted receive level",
                        value: receivePower.formatted(.number.precision(.fractionLength(1))) + " dBm"
                    )
                }
                if let linkMargin = result.result.linkMarginDB {
                    LinkProfileMetricRow(
                        label: "Link margin",
                        value: linkMargin.formatted(.number.precision(.fractionLength(1))) + " dB"
                    )
                }
                LinkProfileMetricRow(
                    label: "Worst Fresnel clearance",
                    value: result.result.worstClearancePercent.formatted(.number.precision(.fractionLength(0))) + "%"
                )
                LinkProfileMetricRow(
                    label: "Terrain source",
                    value: "Copernicus GLO-90 · Open-Meteo"
                )
                LinkProfileMetricRow(label: "Calculated", value: result.calculatedAt.formatted(date: .abbreviated, time: .shortened))
            }

            Divider()
            Text("Assumptions")
                .font(.subheadline.weight(.semibold))
            Text(assumptionsSummary)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .instrumentCard(fillOpacity: 1)
        .background(Color(uiColor: .systemBackground), in: RoundedRectangle(cornerRadius: NodeScopeStyle.cornerRadius))
    }

    private var bearingText: String {
        let bearing = NodePlannerLinkAnalyzer.initialBearing(
            from: result.request.pointA,
            to: result.request.pointB
        )
        return bearing.formatted(.number.precision(.fractionLength(0))) + "° true"
    }

    private var assumptionsSummary: String {
        guard let budget = result.request.linkBudget else {
            return String(localized: "No complete link budget was supplied.")
        }
        return String(
            localized: "TX power \(budget.transmitPowerDBm.formatted(.number.precision(.fractionLength(1)))) dBm; antenna gains \(budget.gainADB.formatted(.number.precision(.fractionLength(1))))/\(budget.gainBDB.formatted(.number.precision(.fractionLength(1)))) dBi; system losses \(budget.lossADB.formatted(.number.precision(.fractionLength(1))))/\(budget.lossBDB.formatted(.number.precision(.fractionLength(1)))) dB; receiver sensitivity \(budget.receiverSensitivityDBm.formatted(.number.precision(.fractionLength(1)))) dBm; standard atmospheric refraction (k = 4/3)."
        )
    }
}

// This terrain-profile renderer is adapted from MeshCore One by Avi0n:
// https://github.com/Avi0n/MeshCoreOne
// Upstream commit: 9fba8e45ca14c38f4ed20e5ede157944fa1c246d
// Source: MC1/Views/Tools/LineOfSight/TerrainProfileCanvas.swift and
// MC1/Views/Tools/LineOfSight/ChartCoordinateSpace.swift
// Licensed under GNU GPL v3.0. Modified for NodeScope's analysis types,
// styling, accessibility, endpoint-only workflow, and adaptive layouts.
private struct LinkProfileTerrainCanvas: View {
    let analysis: NodePlannerLinkAnalysis
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    private struct Sample {
        let distanceMeters: Double
        let terrainMeters: Double
        let lineOfSightMeters: Double
        let fresnelRadiusMeters: Double

        var fresnelTop: Double { lineOfSightMeters + fresnelRadiusMeters }
        var fresnelBottom: Double { lineOfSightMeters - fresnelRadiusMeters }
        var fresnelTop60: Double { lineOfSightMeters + fresnelRadiusMeters * 0.6 }
        var fresnelBottom60: Double { lineOfSightMeters - fresnelRadiusMeters * 0.6 }
        var visibleFresnelBottom: Double { min(max(terrainMeters, fresnelBottom), fresnelTop) }
        var visibleFresnelBottom60: Double { min(max(terrainMeters, fresnelBottom60), fresnelTop60) }
        var isObstructed: Bool { terrainMeters > fresnelBottom60 }
    }

    private struct Coordinates {
        let size: CGSize
        let insets = EdgeInsets(top: 22, leading: 42, bottom: 28, trailing: 14)
        let xRange: ClosedRange<Double>
        let yRange: ClosedRange<Double>

        func point(x: Double, y: Double) -> CGPoint {
            let width = max(1, size.width - insets.leading - insets.trailing)
            let height = max(1, size.height - insets.top - insets.bottom)
            let xFraction = (x - xRange.lowerBound) / max(1, xRange.upperBound - xRange.lowerBound)
            let yFraction = (y - yRange.lowerBound) / max(1, yRange.upperBound - yRange.lowerBound)
            return CGPoint(
                x: insets.leading + width * xFraction,
                y: size.height - insets.bottom - height * yFraction
            )
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Terrain profile")
                .font(.subheadline.weight(.semibold))

            Canvas { context, size in
                let coordinates = Coordinates(size: size, xRange: xRange, yRange: yRange)
                drawGrid(context: context, coordinates: coordinates)
                drawFresnel(context: context, coordinates: coordinates)
                drawTerrain(context: context, coordinates: coordinates)
                drawObstructions(context: context, coordinates: coordinates)
                drawLineOfSight(context: context, coordinates: coordinates)
                drawEndpoints(context: context, coordinates: coordinates)
            }
            .frame(height: 240)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Terrain elevation, line of sight, first Fresnel zone, and obstructed regions")
            .accessibilityValue(accessibilitySummary)

            HStack(spacing: 16) {
                LinkProfileChartLegend(color: .brown, title: "Terrain")
                LinkProfileChartLegend(color: .teal.opacity(0.55), title: "Fresnel zone")
            }
            .font(.caption)
            // The line of sight's three clearances, matching the map.
            HStack(spacing: 16) {
                ForEach(NodePlannerPathClearance.allCases, id: \.self) { clearance in
                    LinkProfileClearanceLegend(clearance: clearance)
                }
            }
            .font(.caption)
        }
    }

    private var samples: [Sample] {
        let terrain = analysis.terrain.samples
        guard let first = terrain.first, let last = terrain.last, terrain.count > 1 else { return [] }
        let totalDistance = analysis.result.distanceMeters
        let startHeight = first.elevationMeters + analysis.request.heightAMeters
        let endHeight = last.elevationMeters + analysis.request.heightBMeters
        var distance = 0.0

        return terrain.enumerated().map { index, point in
            if index > 0 {
                distance += NodePlannerLinkAnalyzer.distance(from: terrain[index - 1].coordinate, to: point.coordinate)
            }
            let distanceToEnd = max(0, totalDistance - distance)
            let fraction = totalDistance > 0 ? distance / totalDistance : 0
            let lineOfSight = startHeight + fraction * (endHeight - startHeight)
            let radius = NodePlannerRFEngine.fresnelRadius(
                frequencyMHz: analysis.request.frequencyMHz,
                distanceToAMeters: distance,
                distanceToBMeters: distanceToEnd
            )
            let terrainHeight = point.elevationMeters + NodePlannerRFEngine.earthBulge(
                distanceToAMeters: distance,
                distanceToBMeters: distanceToEnd,
                refractionK: analysis.request.refractionK
            )
            return Sample(
                distanceMeters: distance,
                terrainMeters: terrainHeight,
                lineOfSightMeters: lineOfSight,
                fresnelRadiusMeters: radius
            )
        }
    }

    private var xRange: ClosedRange<Double> {
        0...max(1, samples.last?.distanceMeters ?? 1)
    }

    private var yRange: ClosedRange<Double> {
        guard !samples.isEmpty else { return 0...100 }
        let minimum = samples.map(\.terrainMeters).min() ?? 0
        let maximum = samples.map(\.fresnelTop).max() ?? minimum + 100
        let span = max(10, maximum - minimum)
        return (minimum - span * 0.12)...(maximum + span * 0.2)
    }

    private func drawGrid(context: GraphicsContext, coordinates: Coordinates) {
        for index in 0...4 {
            let fraction = Double(index) / 4
            let y = yRange.lowerBound + (yRange.upperBound - yRange.lowerBound) * fraction
            var path = Path()
            path.move(to: coordinates.point(x: xRange.lowerBound, y: y))
            path.addLine(to: coordinates.point(x: xRange.upperBound, y: y))
            context.stroke(path, with: .color(.secondary.opacity(0.22)), style: StrokeStyle(lineWidth: 0.5, dash: [4, 4]))
            let displayElevation = distanceUnit.displayHeight(meters: y)
            context.draw(
                Text(index == 4 ? "\(Int(displayElevation)) \(distanceUnit.heightSymbol)" : "\(Int(displayElevation))")
                    .font(.system(size: 9))
                    .foregroundStyle(.secondary),
                at: coordinates.point(x: xRange.lowerBound, y: y),
                anchor: .trailing
            )
        }
        for index in 0...4 {
            let fraction = Double(index) / 4
            let x = xRange.upperBound * fraction
            let displayDistance = distanceUnit == .imperial ? x / 1_609.344 : x / 1_000
            let unit = distanceUnit == .imperial ? "mi" : "km"
            let label = displayDistance.formatted(.number.precision(.fractionLength(xRange.upperBound < 10_000 ? 1 : 0)))
            context.draw(
                Text(index == 4 ? "\(label) \(unit)" : label).font(.system(size: 9)).foregroundStyle(.secondary),
                at: coordinates.point(x: x, y: yRange.lowerBound),
                anchor: .top
            )
        }
    }

    private func drawFresnel(context: GraphicsContext, coordinates: Coordinates) {
        fillBand(upper: \.fresnelTop, lower: \.visibleFresnelBottom, color: .teal.opacity(0.2), context: context, coordinates: coordinates)
        fillBand(upper: \.fresnelTop60, lower: \.visibleFresnelBottom60, color: .teal.opacity(0.42), context: context, coordinates: coordinates)
        strokeLine(value: \.fresnelTop, color: .teal.opacity(0.65), dashed: true, context: context, coordinates: coordinates)
        strokeLine(value: \.fresnelBottom, color: .teal.opacity(0.65), dashed: true, context: context, coordinates: coordinates)
    }

    private func drawTerrain(context: GraphicsContext, coordinates: Coordinates) {
        guard let first = samples.first, let last = samples.last else { return }
        var fill = Path()
        fill.move(to: coordinates.point(x: first.distanceMeters, y: yRange.lowerBound))
        for sample in samples { fill.addLine(to: coordinates.point(x: sample.distanceMeters, y: sample.terrainMeters)) }
        fill.addLine(to: coordinates.point(x: last.distanceMeters, y: yRange.lowerBound))
        fill.closeSubpath()
        context.fill(fill, with: .color(.brown.opacity(0.35)))
        strokeLine(value: \.terrainMeters, color: .brown, context: context, coordinates: coordinates)
    }

    private func drawObstructions(context: GraphicsContext, coordinates: Coordinates) {
        for sample in samples where sample.isObstructed {
            let point = coordinates.point(x: sample.distanceMeters, y: sample.terrainMeters)
            context.fill(Path(ellipseIn: CGRect(x: point.x - 2.5, y: point.y - 2.5, width: 5, height: 5)), with: .color(.red))
        }
    }

    /// Dashed and coloured by clearance, as on the map; one dash phase runs across colour changes.
    private func drawLineOfSight(context: GraphicsContext, coordinates: Coordinates) {
        let samples = samples
        guard let first = samples.first else { return }
        let origin = coordinates.point(x: first.distanceMeters, y: first.lineOfSightMeters)
        for stretch in analysis.clearanceStretches where stretch.endIndex < samples.count {
            let start = coordinates.point(x: samples[stretch.startIndex].distanceMeters, y: samples[stretch.startIndex].lineOfSightMeters)
            var path = Path()
            path.move(to: start)
            path.addLine(to: coordinates.point(x: samples[stretch.endIndex].distanceMeters, y: samples[stretch.endIndex].lineOfSightMeters))
            context.stroke(
                path,
                with: .color(stretch.clearance.color),
                style: StrokeStyle(lineWidth: 2.5, dash: [5, 3], dashPhase: hypot(start.x - origin.x, start.y - origin.y))
            )
        }
    }

    private func drawEndpoints(context: GraphicsContext, coordinates: Coordinates) {
        guard let first = samples.first, let last = samples.last else { return }
        drawEndpoint("A", sample: first, color: .blue, context: context, coordinates: coordinates)
        drawEndpoint("B", sample: last, color: .orange, context: context, coordinates: coordinates)
    }

    private func drawEndpoint(_ label: String, sample: Sample, color: Color, context: GraphicsContext, coordinates: Coordinates) {
        let ground = coordinates.point(x: sample.distanceMeters, y: sample.terrainMeters)
        let antenna = coordinates.point(x: sample.distanceMeters, y: sample.lineOfSightMeters)
        var tower = Path()
        tower.move(to: ground)
        tower.addLine(to: antenna)
        context.stroke(tower, with: .color(color), lineWidth: 2)
        context.fill(Path(ellipseIn: CGRect(x: antenna.x - 7, y: antenna.y - 7, width: 14, height: 14)), with: .color(color))
        context.draw(Text(label).font(.system(size: 8, weight: .bold)).foregroundStyle(.white), at: antenna)
    }

    private func fillBand(
        upper: KeyPath<Sample, Double>,
        lower: KeyPath<Sample, Double>,
        color: Color,
        context: GraphicsContext,
        coordinates: Coordinates
    ) {
        guard let first = samples.first else { return }
        var path = Path()
        path.move(to: coordinates.point(x: first.distanceMeters, y: first[keyPath: upper]))
        for sample in samples.dropFirst() { path.addLine(to: coordinates.point(x: sample.distanceMeters, y: sample[keyPath: upper])) }
        for sample in samples.reversed() { path.addLine(to: coordinates.point(x: sample.distanceMeters, y: sample[keyPath: lower])) }
        path.closeSubpath()
        context.fill(path, with: .color(color))
    }

    private func strokeLine(
        value: KeyPath<Sample, Double>,
        color: Color,
        lineWidth: CGFloat = 1,
        dashed: Bool = false,
        context: GraphicsContext,
        coordinates: Coordinates
    ) {
        guard let first = samples.first else { return }
        var path = Path()
        path.move(to: coordinates.point(x: first.distanceMeters, y: first[keyPath: value]))
        for sample in samples.dropFirst() { path.addLine(to: coordinates.point(x: sample.distanceMeters, y: sample[keyPath: value])) }
        context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: lineWidth, dash: dashed ? [4, 3] : []))
    }

    private var accessibilitySummary: String {
        let distance = distanceUnit.format(kilometers: analysis.result.distanceMeters / 1_000)
        let clearance = analysis.result.worstClearancePercent.formatted(.number.precision(.fractionLength(0)))
        return "Distance \(distance). Worst Fresnel clearance \(clearance) percent."
    }
}

private extension CLLocationCoordinate2D {
    func isSame(_ other: CLLocationCoordinate2D) -> Bool {
        latitude == other.latitude && longitude == other.longitude
    }
}

private extension NodePlannerTerrainSample {
    var mapCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: coordinate.latitude, longitude: coordinate.longitude)
    }
}

extension NodePlannerPathClearance {
    var color: Color {
        switch self {
        case .clear: .green
        case .marginal: .orange
        case .obstructed: .red
        }
    }

    var title: LocalizedStringResource {
        switch self {
        case .clear: "Clear"
        case .marginal: "Marginal"
        case .obstructed: "Obstructed"
        }
    }
}

/// The visible map and its scale when the camera last settled.
private struct LinkRouteView {
    let visible: MKMapRect
    let mapPointsPerPoint: Double
}

/// The analyzed route's dashes, as on Android: 8 pt pills with 5 pt gaps on the 4 pt line. Each
/// dash is its own line with round ends, so the line between them is the pill less the width.
private enum LinkRouteDash {
    static let dash = 4.0
    static let period = 13.0

    struct Piece: Identifiable {
        let id: Int
        let coordinates: [CLLocationCoordinate2D]
        let clearance: NodePlannerPathClearance
    }

    /// The route's pieces: dashes within half a screen of the view, counted from A so they stay
    /// put as the camera moves; whole stretches until the camera has settled. Samples are evenly
    /// spaced, so a sample's index gives its fraction of the way from A to B. A stretch shorter
    /// than a gap still shows, as a dot at its middle.
    static func pieces(result: NodePlannerLinkAnalysis, view: LinkRouteView?) -> [Piece] {
        let samples = result.terrain.samples
        guard let first = samples.first, let last = samples.last, samples.count > 1 else { return [] }
        let a = MKMapPoint(first.mapCoordinate)
        let b = MKMapPoint(last.mapCoordinate)
        func coordinate(_ fraction: Double) -> CLLocationCoordinate2D {
            MKMapPoint(x: a.x + (b.x - a.x) * fraction, y: a.y + (b.y - a.y) * fraction).coordinate
        }
        let stretches = result.clearanceStretches.map {
            (from: Double($0.startIndex) / Double(samples.count - 1), to: Double($0.endIndex) / Double(samples.count - 1), clearance: $0.clearance)
        }
        var pieces: [Piece] = []
        func add(_ from: Double, _ to: Double, _ clearance: NodePlannerPathClearance) {
            pieces.append(Piece(id: pieces.count, coordinates: [coordinate(from), coordinate(to)], clearance: clearance))
        }
        guard let view, let dashes = dashes(from: a, to: b, view: view) else {
            stretches.forEach { add($0.from, $0.to, $0.clearance) }
            return pieces
        }
        for stretch in stretches {
            let before = pieces.count
            for (start, end) in dashes where min(end, stretch.to) > max(start, stretch.from) {
                add(max(start, stretch.from), min(end, stretch.to), stretch.clearance)
            }
            if pieces.count == before, stretch.to > stretch.from, let (start, end) = dashes.first {
                let middle = (stretch.from + stretch.to) / 2
                let half = (end - start) / 4
                add(middle - half, middle + half, stretch.clearance)
            }
        }
        return pieces
    }

    /// The dashes, as fractions from A to B, where the route passes within half a screen of the
    /// view (Liang–Barsky clipping); nil when the route is under a point long on screen.
    private static func dashes(from a: MKMapPoint, to b: MKMapPoint, view: LinkRouteView) -> [(Double, Double)]? {
        let dx = b.x - a.x
        let dy = b.y - a.y
        let length = hypot(dx, dy) / view.mapPointsPerPoint
        guard length >= 1 else { return nil }
        let area = view.visible.insetBy(dx: -view.visible.width / 2, dy: -view.visible.height / 2)
        var t0 = 0.0
        var t1 = 1.0
        for (p, q) in [(-dx, a.x - area.minX), (dx, area.maxX - a.x), (-dy, a.y - area.minY), (dy, area.maxY - a.y)] {
            if p == 0 {
                if q < 0 { return [] }
                continue
            }
            let r = q / p
            if p < 0 {
                if r > t1 { return [] }
                t0 = max(t0, r)
            } else {
                if r < t0 { return [] }
                t1 = min(t1, r)
            }
        }
        let step = period / length
        let span = dash / length
        let firstIndex = Int((t0 / step).rounded(.down))
        let lastIndex = Int((t1 / step).rounded(.up))
        guard firstIndex <= lastIndex else { return [] }
        return (firstIndex...lastIndex).compactMap { index in
            let start = Double(index) * step
            return start >= 0 && start < 1 ? (start, min(start + span, 1)) : nil
        }
    }
}

/// Two rounded dashes in the clearance's colour, as on the map, then its name.
private struct LinkProfileClearanceLegend: View {
    let clearance: NodePlannerPathClearance

    var body: some View {
        Label {
            Text(clearance.title)
        } icon: {
            Canvas { context, size in
                var path = Path()
                path.move(to: CGPoint(x: size.height / 2, y: size.height / 2))
                path.addLine(to: CGPoint(x: size.width - size.height / 2, y: size.height / 2))
                context.stroke(path, with: .color(clearance.color), style: StrokeStyle(lineWidth: size.height, lineCap: .round, dash: [3, 8]))
            }
            .frame(width: 18, height: 4)
        }
    }
}

private struct LinkProfileChartLegend: View {
    let color: Color
    let title: LocalizedStringResource

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Capsule()
                .fill(color)
                .frame(width: 18, height: 4)
        }
    }
}

private struct LinkProfileMetricRow: View {
    let label: LocalizedStringResource
    let value: String

    var body: some View {
        GridRow {
            Text(label)
                .foregroundStyle(.secondary)
            Text(value)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }
}
