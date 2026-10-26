import Observation
import MapKit
import SwiftUI

struct LinkProfileScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(TabBarContextActionStore.self) private var tabBarContextAction
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var model = LinkProfileModel()
    @State private var catalog = FieldToolsViewModel(reference: nil)
    @State private var endpointPicker: LinkProfileEndpoint? = .pointA
    @State private var isHingedDevice: Bool?

    var body: some View {
        GeometryReader { geometry in
            Group {
                if usesDuoWorkspace {
                    DuoTwoPaneLayout {
                        LinkProfilePersistentMap(
                            model: model,
                            nodes: catalog.nodes,
                            activeEndpoint: $endpointPicker,
                            selectionTrailingClearance: 48
                        )
                        .safeAreaPadding(.trailing)
                        .clipped()
                    } secondary: {
                        LinkProfileEditor(
                            model: model,
                            chooseOnMap: { endpointPicker = $0 },
                            trailingPadding: 112,
                            usesCompactHeader: true
                        )
                        .background(Color(uiColor: .systemGroupedBackground))
                        .clipped()
                    }
                    .ignoresSafeArea(.container, edges: .bottom)
                } else if geometry.size.width >= 900 {
                    HStack(spacing: 0) {
                        LinkProfilePersistentMap(
                            model: model,
                            nodes: catalog.nodes,
                            activeEndpoint: $endpointPicker,
                            promptLeadingClearance: 60,
                            promptTopClearance: 56
                        )
                        LinkProfileEditor(
                            model: model,
                            chooseOnMap: { endpointPicker = $0 },
                            topInset: 56
                        )
                        .frame(width: min(420, max(340, geometry.size.width * 0.34)))
                    }
                } else {
                    LinkProfileCompactWorkspace(
                        model: model,
                        nodes: catalog.nodes,
                        activeEndpoint: $endpointPicker
                    )
                }
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Link Profile")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(usesWideWorkspace ? .hidden : .visible, for: .navigationBar)
        .toolbarBackground(Color(uiColor: .systemGroupedBackground), for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .overlay(alignment: .topLeading) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.backward")
                    .font(.title2.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .background(.regularMaterial, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Back")
            .padding()
            .padding(.top, showsOverlayBackButton ? 56 : 0)
            .opacity(showsOverlayBackButton ? 1 : 0)
            .allowsHitTesting(showsOverlayBackButton)
        }
        .observesDuoHinge($isHingedDevice)
        .onChange(of: isHingedDevice, initial: true) { _, isHingedDevice in
            if isHingedDevice == true && horizontalSizeClass == .regular {
                tabBarContextAction.set(.back) { dismiss() }
            } else {
                tabBarContextAction.clear()
            }
        }
        .onDisappear {
            tabBarContextAction.clear()
        }
        .task(id: "\(settings.sourceSession.id)|\(regionFilter.selectedRegion ?? "")") {
            catalog.configure(settings: settings)
            await catalog.load(region: regionFilter.selectedRegion)
        }
    }

    private var usesDuoWorkspace: Bool {
        isHingedDevice == true && horizontalSizeClass == .regular
    }

    private var usesWideWorkspace: Bool {
        horizontalSizeClass == .regular
    }

    private var showsOverlayBackButton: Bool {
        usesWideWorkspace && isHingedDevice == false
    }

}

private struct LinkProfileCompactWorkspace: View {
    let model: LinkProfileModel
    let nodes: [MeshNode]
    @Binding var activeEndpoint: LinkProfileEndpoint?

    var body: some View {
        LinkProfilePersistentMap(model: model, nodes: nodes, activeEndpoint: $activeEndpoint)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if activeEndpoint == nil {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 12) {
                            LinkProfileEndpointLayout(
                                model: model,
                                chooseOnMap: { activeEndpoint = $0 }
                            )
                            LinkProfileRadioSettings(model: model)
                            LinkProfileAction(model: model)
                            if let result = model.result {
                                LinkProfileResultCard(result: result)
                            }
                        }
                        .padding()
                    }
                    .frame(maxHeight: 330)
                    .meshPageBackground()
                    .clipShape(UnevenRoundedRectangle(topLeadingRadius: 22, topTrailingRadius: 22))
                }
            }
    }
}

private struct LinkProfileEditor: View {
    @Bindable var model: LinkProfileModel
    let chooseOnMap: (LinkProfileEndpoint) -> Void
    var trailingPadding: CGFloat = 16
    var usesCompactHeader = false
    var topInset: CGFloat = 0
    private let resultAnchor = "link-profile-result"

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if usesCompactHeader {
                        Label("Link Profile", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                            .font(.title2.bold())
                    } else {
                        LinkProfileIntroduction()
                    }
                    LinkProfileEndpointLayout(
                        model: model,
                        chooseOnMap: chooseOnMap
                    )
                    LinkProfileRadioSettings(model: model)
                    LinkProfilePrivacyNotice()
                    LinkProfileAction(model: model)
                    if let result = model.result {
                        LinkProfileResultCard(result: result)
                            .id(resultAnchor)
                    }
                }
                .padding(.leading, 20)
                .padding(.vertical, 20)
                .padding(.trailing, trailingPadding)
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                Color.clear.frame(height: topInset)
            }
            .onChange(of: model.result) { _, result in
                guard result != nil else { return }
                withAnimation(.easeInOut) {
                    proxy.scrollTo(resultAnchor, anchor: .top)
                }
            }
        }
        .meshPageBackground()
        .clipped()
    }
}

private struct LinkProfilePersistentMap: View {
    let model: LinkProfileModel
    let nodes: [MeshNode]
    @Binding var activeEndpoint: LinkProfileEndpoint?
    var selectionTrailingClearance: CGFloat = 0
    var promptLeadingClearance: CGFloat = 0
    var promptTopClearance: CGFloat = 0
    @State private var tentativeCoordinate: CLLocationCoordinate2D?
    @State private var position: MapCameraPosition = .userLocation(fallback: .automatic)

    var body: some View {
        MapReader { proxy in
            Map(position: $position) {
                ForEach(Array(nodesWithCoordinates.prefix(200)), id: \.id) { node in
                    if let coordinate = node.coordinate {
                        Annotation(node.name ?? "Known node", coordinate: coordinate) {
                            Button {
                                guard activeEndpoint != nil else { return }
                                tentativeCoordinate = coordinate
                            } label: {
                                Image(systemName: "antenna.radiowaves.left.and.right.circle.fill")
                                    .font(.title2)
                                    .foregroundStyle(NodeScopeStyle.signal)
                                    .background(.background, in: Circle())
                            }
                            .buttonStyle(.plain)
                            .frame(minWidth: 44, minHeight: 44)
                            .accessibilityLabel(node.name ?? String(localized: "Known node"))
                            .accessibilityHint("Use as the selected link endpoint")
                        }
                    }
                }
                if let pointA = model.coordinateA {
                    Marker("Point A", coordinate: pointA).tint(.blue)
                }
                if let pointB = model.coordinateB {
                    Marker("Point B", coordinate: pointB).tint(.orange)
                }
                if let pointA = model.coordinateA, let pointB = model.coordinateB {
                    MapPolyline(coordinates: [pointA, pointB])
                        .stroke(NodeScopeStyle.signal, lineWidth: 3)
                }
                if let tentativeCoordinate {
                    Annotation("Tentative location", coordinate: tentativeCoordinate) {
                        Image(systemName: "mappin.circle.fill")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                            .background(.background, in: Circle())
                    }
                }
            }
            .mapStyle(.standard(elevation: .realistic))
            .mapControls {
                MapCompass()
                MapScaleView()
            }
            .simultaneousGesture(
                SpatialTapGesture().onEnded { value in
                    guard activeEndpoint != nil,
                          let coordinate = proxy.convert(value.location, from: .local) else { return }
                    tentativeCoordinate = coordinate
                }
            )
            .overlay(alignment: .topLeading) {
                Label(promptTitle, systemImage: activeEndpoint == nil ? "checkmark.circle" : "mappin.and.ellipse")
                    .font(.headline)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 44)
                    .background(.regularMaterial, in: Capsule())
                    .padding()
                    .padding(.leading, promptLeadingClearance)
                    .padding(.top, promptTopClearance)
            }
            .overlay(alignment: .bottom) {
                if let activeEndpoint {
                    HStack(spacing: 0) {
                        Spacer(minLength: 16)
                        LinkProfileMapSelectionBar(
                            endpointTitle: activeEndpoint.title,
                            coordinate: tentativeCoordinate,
                            cancel: {
                                tentativeCoordinate = nil
                                self.activeEndpoint = nil
                            },
                            confirm: { commit(tentativeCoordinate, for: activeEndpoint) }
                        )
                        .frame(maxWidth: 360)
                        Spacer(minLength: 16 + selectionTrailingClearance)
                    }
                    .padding(.vertical, 16)
                }
            }
        }
        .accessibilityLabel("Map for selecting Point A and Point B")
        .onChange(of: activeEndpoint) { _, _ in tentativeCoordinate = nil }
    }

    private var nodesWithCoordinates: [MeshNode] {
        nodes.filter { $0.coordinate != nil }
    }

    private var promptTitle: LocalizedStringResource {
        if let activeEndpoint { return "Choose \(activeEndpoint.title)" }
        if model.coordinateA != nil && model.coordinateB != nil { return "Endpoints selected" }
        if model.coordinateA == nil { return "Choose Point A" }
        return "Choose Point B"
    }

    private func commit(_ coordinate: CLLocationCoordinate2D?, for endpoint: LinkProfileEndpoint) {
        guard let coordinate else { return }
        model.set(coordinate, for: endpoint)
        tentativeCoordinate = nil
        activeEndpoint = endpoint == .pointA && model.coordinateB == nil ? .pointB : nil
    }
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

    var bandwidthKHz: Double? {
        switch self {
        case .unitedStatesNarrow, .europeNarrow: 62.5
        case .custom: nil
        }
    }

    var spreadingFactor: Int? {
        switch self {
        case .unitedStatesNarrow: 7
        case .europeNarrow: 8
        case .custom: nil
        }
    }

    var codingRate: Int? {
        self == .custom ? nil : 5
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
    var radioPreset = LinkProfileRadioPreset.unitedStatesNarrow
    var frequencyMHz = 910.525
    var bandwidthKHz = 62.5
    var spreadingFactor = 7
    var codingRate = 5
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

    var coordinateA: CLLocationCoordinate2D? {
        guard let latitudeA, let longitudeA else { return nil }
        return CLLocationCoordinate2D(latitude: latitudeA, longitude: longitudeA)
    }

    var coordinateB: CLLocationCoordinate2D? {
        guard let latitudeB, let longitudeB else { return nil }
        return CLLocationCoordinate2D(latitude: latitudeB, longitude: longitudeB)
    }

    var canAnalyze: Bool {
        coordinateA != nil && coordinateB != nil && !isAnalyzing
    }

    init(elevationProvider: any NodePlannerElevationProviding = OpenMeteoElevationProvider()) {
        self.elevationProvider = elevationProvider
        analyzer = NodePlannerLinkAnalyzer(elevationProvider: elevationProvider)
    }

    func analyze() async {
        guard !isAnalyzing,
              let latitudeA, let longitudeA,
              let latitudeB, let longitudeB else {
            errorMessage = "Choose Point A and Point B before analyzing the link."
            return
        }
        isAnalyzing = true
        result = nil
        errorMessage = nil
        defer { isAnalyzing = false }

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
            result = analysis
            groundElevationA = analysis.terrain.samples.first?.elevationMeters
            groundElevationB = analysis.terrain.samples.last?.elevationMeters
        } catch is CancellationError {
            errorMessage = "Analysis was cancelled."
        } catch NodePlannerElevationError.invalidCoordinate {
            errorMessage = "Enter valid latitude and longitude values."
        } catch NodePlannerElevationError.unavailable {
            errorMessage = "Elevation data is unavailable. Try again when you have a connection."
        } catch {
            errorMessage = "The link could not be analyzed with these settings."
        }
    }

    func set(_ coordinate: CLLocationCoordinate2D, for endpoint: LinkProfileEndpoint) {
        switch endpoint {
        case .pointA:
            latitudeA = coordinate.latitude
            longitudeA = coordinate.longitude
        case .pointB:
            latitudeB = coordinate.latitude
            longitudeB = coordinate.longitude
        }
        result = nil
        errorMessage = nil
        scheduleElevationLookup(for: endpoint)
    }

    func clear(_ endpoint: LinkProfileEndpoint) {
        switch endpoint {
        case .pointA:
            elevationTaskA?.cancel()
            latitudeA = nil
            longitudeA = nil
            groundElevationA = nil
            isLoadingElevationA = false
        case .pointB:
            elevationTaskB?.cancel()
            latitudeB = nil
            longitudeB = nil
            groundElevationB = nil
            isLoadingElevationB = false
        }
        result = nil
        errorMessage = nil
    }

    func swapEndpoints() {
        (latitudeA, latitudeB) = (latitudeB, latitudeA)
        (longitudeA, longitudeB) = (longitudeB, longitudeA)
        (heightA, heightB) = (heightB, heightA)
        (groundElevationA, groundElevationB) = (groundElevationB, groundElevationA)
        result = nil
        errorMessage = nil
    }

    func endpointCoordinatesChanged(_ endpoint: LinkProfileEndpoint) {
        result = nil
        errorMessage = nil
        scheduleElevationLookup(for: endpoint)
    }

    func configurationChanged() {
        result = nil
        errorMessage = nil
    }

    func applyRadioPreset(_ preset: LinkProfileRadioPreset) {
        radioPreset = preset
        if let value = preset.frequencyMHz { frequencyMHz = value }
        if let value = preset.bandwidthKHz { bandwidthKHz = value }
        if let value = preset.spreadingFactor { spreadingFactor = value }
        if let value = preset.codingRate { codingRate = value }
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

private struct LinkProfileIntroduction: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Estimate a path before deployment", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                .font(.title2.bold())
            Text("Compare terrain clearance and modeled path loss between any two locations. Results are planning estimates, not proof that a radio link will work.")
                .foregroundStyle(.secondary)
        }
    }
}

private struct LinkProfileEndpointLayout: View {
    @Bindable var model: LinkProfileModel
    let chooseOnMap: (LinkProfileEndpoint) -> Void

    var body: some View {
        VStack(spacing: 12) {
            endpointCards

            if model.coordinateA != nil && model.coordinateB != nil {
                Button {
                    model.swapEndpoints()
                } label: {
                    Label("Swap A and B", systemImage: "arrow.up.arrow.down")
                }
                .buttonStyle(.bordered)
            }
        }
    }

    @ViewBuilder
    private var endpointCards: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 12) { cards }
            VStack(spacing: 12) { cards }
        }
    }

    @ViewBuilder
    private var cards: some View {
        LinkProfileEndpointCard(
            endpoint: .pointA,
            latitude: $model.latitudeA,
            longitude: $model.longitudeA,
            height: $model.heightA,
            groundElevationMeters: model.groundElevationA,
            isLoadingElevation: model.isLoadingElevationA,
            relocate: { chooseOnMap(.pointA) },
            clear: { model.clear(.pointA) },
            coordinatesChanged: { model.endpointCoordinatesChanged(.pointA) },
            heightChanged: model.configurationChanged
        )
        LinkProfileEndpointCard(
            endpoint: .pointB,
            latitude: $model.latitudeB,
            longitude: $model.longitudeB,
            height: $model.heightB,
            groundElevationMeters: model.groundElevationB,
            isLoadingElevation: model.isLoadingElevationB,
            relocate: { chooseOnMap(.pointB) },
            clear: { model.clear(.pointB) },
            coordinatesChanged: { model.endpointCoordinatesChanged(.pointB) },
            heightChanged: model.configurationChanged
        )
    }
}

private struct LinkProfileEndpointCard: View {
    let endpoint: LinkProfileEndpoint
    @Binding var latitude: Double?
    @Binding var longitude: Double?
    @Binding var height: Double
    let groundElevationMeters: Double?
    let isLoadingElevation: Bool
    let relocate: () -> Void
    let clear: () -> Void
    let coordinatesChanged: () -> Void
    let heightChanged: () -> Void
    @State private var showsCoordinateEntry = false
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(endpoint.title, systemImage: endpoint == .pointA ? "a.circle.fill" : "b.circle.fill")
                    .font(.headline)
                    .foregroundStyle(endpoint == .pointA ? .blue : .orange)
                Spacer()
                Menu("Endpoint actions", systemImage: "ellipsis.circle") {
                    Button("Relocate on Map", systemImage: "mappin.and.ellipse", action: relocate)
                    Button("Enter Coordinates", systemImage: "number") {
                        showsCoordinateEntry.toggle()
                    }
                    if hasCoordinate {
                        Button("Clear Endpoint", systemImage: "trash", role: .destructive, action: clear)
                    }
                }
                .labelStyle(.iconOnly)
            }

            if hasCoordinate {
                Text(coordinateSummary)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            } else {
                Text("Not selected")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            elevationSummary

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Additional antenna height")
                    Spacer()
                    Text(distanceUnit.formatHeight(meters: height))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                Slider(value: displayedHeight, in: distanceUnit.antennaHeightRange, step: 1)
                    .accessibilityLabel("Additional antenna height")
                    .accessibilityValue(distanceUnit.formatHeight(meters: height))
                    .onChange(of: height) { _, _ in heightChanged() }
            }

            if showsCoordinateEntry {
                Divider()
                LabeledContent("Latitude") {
                    TextField("Latitude", value: $latitude, format: .number.precision(.fractionLength(0...6)))
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numbersAndPunctuation)
                }
                LabeledContent("Longitude") {
                    TextField("Longitude", value: $longitude, format: .number.precision(.fractionLength(0...6)))
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numbersAndPunctuation)
                }
                .onChange(of: latitude) { _, _ in coordinatesChanged() }
                .onChange(of: longitude) { _, _ in coordinatesChanged() }
            } else if !hasCoordinate {
                Button("Select on Map", systemImage: "mappin.and.ellipse", action: relocate)
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .instrumentCard()
        .contentShape(
            RoundedRectangle(cornerRadius: NodeScopeStyle.cornerRadius, style: .continuous)
        )
    }

    private var hasCoordinate: Bool {
        latitude != nil && longitude != nil
    }

    private var coordinateSummary: String {
        guard let latitude, let longitude else { return String(localized: "Not selected") }
        return "\(latitude.formatted(.number.precision(.fractionLength(5)))), \(longitude.formatted(.number.precision(.fractionLength(5))))"
    }

    @ViewBuilder
    private var elevationSummary: some View {
        Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 5) {
            GridRow {
                Text("Ground elevation")
                    .foregroundStyle(.secondary)
                Spacer()
                if isLoadingElevation {
                    ProgressView().controlSize(.small)
                } else if let groundElevationMeters {
                    Text(distanceUnit.formatHeight(meters: groundElevationMeters))
                        .monospacedDigit()
                } else {
                    Text("Unavailable").foregroundStyle(.secondary)
                }
            }
            GridRow {
                Text("Total height")
                    .foregroundStyle(.secondary)
                Spacer()
                if let groundElevationMeters {
                    Text(distanceUnit.formatHeight(meters: groundElevationMeters + height))
                        .monospacedDigit()
                } else {
                    Text("—").foregroundStyle(.secondary)
                }
            }
        }
        .font(.subheadline)
    }

    private var displayedHeight: Binding<Double> {
        Binding(
            get: { distanceUnit.displayHeight(meters: height) },
            set: { height = distanceUnit.meters(fromDisplayedHeight: $0) }
        )
    }
}

private struct LinkProfileMapSelectionBar: View {
    let endpointTitle: LocalizedStringResource
    let coordinate: CLLocationCoordinate2D?
    let cancel: () -> Void
    let confirm: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Tap the map to position \(endpointTitle).")
                .font(.subheadline)
            if let coordinate {
                Text("\(coordinate.latitude, format: .number.precision(.fractionLength(5))), \(coordinate.longitude, format: .number.precision(.fractionLength(5)))")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            } else {
                Text("No location selected")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            ViewThatFits(in: .horizontal) {
                HStack { actions }
                VStack { actions }
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    @ViewBuilder
    private var actions: some View {
        Button("Cancel", action: cancel)
            .buttonStyle(.bordered)
            .frame(maxWidth: .infinity)
        Button("Use This Location", action: confirm)
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: .infinity)
            .disabled(coordinate == nil)
    }
}

private struct LinkProfileRadioSettings: View {
    @Bindable var model: LinkProfileModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Radio settings")
                .font(.headline)

            Picker("Preset", selection: presetSelection) {
                ForEach(LinkProfileRadioPreset.allCases) { preset in
                    Text(preset.title).tag(preset)
                }
            }

            LabeledContent("Frequency") {
                HStack(spacing: 4) {
                    TextField(
                        "Frequency",
                        value: numericBinding(\.frequencyMHz),
                        format: .number.precision(.fractionLength(0...3))
                    )
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.decimalPad)
                    Text("MHz")
                        .foregroundStyle(.secondary)
                }
            }

            DisclosureGroup("Advanced assumptions") {
                VStack(spacing: 10) {
                    LinkProfileNumericSetting(title: "Bandwidth", value: numericBinding(\.bandwidthKHz), unit: "kHz")
                    LinkProfileIntegerSetting(title: "Spreading factor", value: integerBinding(\.spreadingFactor))
                    LinkProfileIntegerSetting(title: "Coding rate", value: integerBinding(\.codingRate), prefix: "4/")
                    LinkProfileNumericSetting(title: "Transmit power", value: numericBinding(\.transmitPowerDBm), unit: "dBm")
                    LinkProfileNumericSetting(title: "Point A antenna gain", value: numericBinding(\.gainADB), unit: "dBi")
                    LinkProfileNumericSetting(title: "Point A system loss", value: numericBinding(\.lossADB), unit: "dB")
                    LinkProfileNumericSetting(title: "Point B antenna gain", value: numericBinding(\.gainBDB), unit: "dBi")
                    LinkProfileNumericSetting(title: "Point B system loss", value: numericBinding(\.lossBDB), unit: "dB")
                    LinkProfileNumericSetting(
                        title: "Receiver sensitivity",
                        value: numericBinding(\.receiverSensitivityDBm),
                        unit: "dBm"
                    )
                }
                .padding(.top, 8)
            }

            Text("Preset values are planning assumptions, not detected hardware settings. Standard atmospheric refraction (k = 4/3) is used.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .instrumentCard()
    }

    private var presetSelection: Binding<LinkProfileRadioPreset> {
        Binding(
            get: { model.radioPreset },
            set: { model.applyRadioPreset($0) }
        )
    }

    private func numericBinding(_ keyPath: ReferenceWritableKeyPath<LinkProfileModel, Double>) -> Binding<Double> {
        Binding(
            get: { model[keyPath: keyPath] },
            set: {
                model[keyPath: keyPath] = $0
                model.radioPreset = .custom
                model.configurationChanged()
            }
        )
    }

    private func integerBinding(_ keyPath: ReferenceWritableKeyPath<LinkProfileModel, Int>) -> Binding<Int> {
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
                TextField(title, value: $value, format: .number.precision(.fractionLength(0...2)))
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

private struct LinkProfileIntegerSetting: View {
    let title: LocalizedStringResource
    @Binding var value: Int
    var prefix = ""

    var body: some View {
        LabeledContent {
            HStack(spacing: 0) {
                Text(prefix)
                    .foregroundStyle(.secondary)
                TextField(title, value: $value, format: .number)
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.numberPad)
                    .frame(minWidth: 40)
            }
        } label: {
            Text(title)
        }
    }
}

private struct LinkProfilePrivacyNotice: View {
    var body: some View {
        Label {
            Text("Analyzing sends sampled path coordinates to Open-Meteo for Copernicus elevation lookup. Successful results are cached on this device.")
        } icon: {
            Image(systemName: "hand.raised.fill")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
    }
}

private struct LinkProfileAction: View {
    @Bindable var model: LinkProfileModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                Task { await model.analyze() }
            } label: {
                HStack {
                    if model.isAnalyzing {
                        ProgressView()
                    }
                    Text(model.isAnalyzing ? "Analyzing…" : "Analyze Link")
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!model.canAnalyze)

            if let errorMessage = model.errorMessage {
                Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(.red)
            }
        }
    }
}

private struct LinkProfileResultCard: View {
    let result: NodePlannerLinkAnalysis
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(statusTitle, systemImage: statusSymbol)
                    .font(.headline)
                Spacer()
                Text("Estimate")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
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

    private var statusTitle: LocalizedStringResource {
        switch result.result.clearanceStatus {
        case .clear: "Clear terrain path"
        case .marginal: "Marginal clearance"
        case .partialObstruction: "Partially obstructed"
        case .blocked: "Blocked by terrain"
        }
    }

    private var statusSymbol: String {
        switch result.result.clearanceStatus {
        case .clear: "checkmark.circle.fill"
        case .marginal: "exclamationmark.circle.fill"
        case .partialObstruction: "circle.lefthalf.filled"
        case .blocked: "xmark.octagon.fill"
        }
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
            localized: "TX power \(budget.transmitPowerDBm.formatted(.number.precision(.fractionLength(1)))) dBm; antenna gains \(budget.gainADB.formatted(.number.precision(.fractionLength(1))))/\(budget.gainBDB.formatted(.number.precision(.fractionLength(1)))) dBi; system losses \(budget.lossADB.formatted(.number.precision(.fractionLength(1))))/\(budget.lossBDB.formatted(.number.precision(.fractionLength(1)))) dB; receiver sensitivity \(budget.receiverSensitivityDBm.formatted(.number.precision(.fractionLength(1)))) dBm; k = \(result.request.refractionK.formatted(.number.precision(.fractionLength(2))))."
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
                LinkProfileChartLegend(color: .primary, title: "Line of sight")
                LinkProfileChartLegend(color: .teal.opacity(0.55), title: "Fresnel zone")
                LinkProfileChartLegend(color: .red.opacity(0.7), title: "Obstructed")
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

    private func drawLineOfSight(context: GraphicsContext, coordinates: Coordinates) {
        strokeLine(value: \.lineOfSightMeters, color: .primary, lineWidth: 2, context: context, coordinates: coordinates)
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
