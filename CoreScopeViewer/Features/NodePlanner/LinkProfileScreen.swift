import Observation
import MapKit
import SwiftUI

struct LinkProfileScreen: View {
    let closeAction: (() -> Void)?

    @Environment(TabBarContextActionStore.self) private var contextAction
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var model = LinkProfileModel()
    @State private var endpointPicker: LinkProfileEndpoint?
    @State private var isHingedDevice: Bool?

    init(closeAction: (() -> Void)? = nil) {
        self.closeAction = closeAction
    }

    var body: some View {
        Group {
            if usesDuoWorkspace {
                GeometryReader { geometry in
                    let trailingInset = geometry.safeAreaInsets.trailing
                    let paneWidth = (geometry.size.width - trailingInset) / 2

                    DuoTwoPaneLayout {
                        LinkProfileEditor(
                            model: model,
                            chooseOnMap: { endpointPicker = $0 }
                        )
                        .frame(width: paneWidth, alignment: .leading)
                        .frame(maxHeight: .infinity)
                        .clipped()
                    } secondary: {
                        Group {
                            if let endpointPicker {
                                LinkProfileInlineMapPicker(
                                    endpoint: endpointPicker,
                                    pointA: model.coordinateA,
                                    pointB: model.coordinateB,
                                    cancel: { self.endpointPicker = nil },
                                    select: { coordinate in
                                        model.set(coordinate, for: endpointPicker)
                                        self.endpointPicker = nil
                                    }
                                )
                                .id(endpointPicker.id)
                            } else {
                                LinkProfileWorkspace(model: model)
                            }
                        }
                            .frame(width: paneWidth + trailingInset)
                            .frame(maxHeight: .infinity)
                            .clipped()
                    }
                }
                .ignoresSafeArea(.container, edges: [.bottom, .trailing])
            } else {
                LinkProfileSingleColumn(
                    model: model,
                    chooseOnMap: { endpointPicker = $0 }
                )
                .sheet(item: $endpointPicker) { endpoint in
                    LinkProfileMapPicker(
                        endpoint: endpoint,
                        pointA: model.coordinateA,
                        pointB: model.coordinateB
                    ) { coordinate in
                        model.set(coordinate, for: endpoint)
                    }
                }
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Link Profile")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(usesWideStandardNavigation)
        .toolbar(usesDuoWorkspace ? .hidden : .visible, for: .navigationBar)
        .toolbar {
            if usesWideStandardNavigation {
                ToolbarItem(placement: .navigation) {
                    Button("Node Planner", systemImage: "chevron.backward") {
                        dismiss()
                    }
                    .accessibilityLabel("Back to Node Planner")
                }
            }
        }
        .observesDuoHinge($isHingedDevice)
        .onAppear(perform: updateContextAction)
        .onChange(of: isHingedDevice) { updateContextAction() }
        .onChange(of: endpointPicker) { updateContextAction() }
        .onDisappear(perform: restorePlannerContextAction)
    }

    private var usesDuoWorkspace: Bool {
        isHingedDevice == true && horizontalSizeClass == .regular
    }

    private var usesWideStandardNavigation: Bool {
        isHingedDevice != true && horizontalSizeClass == .regular
    }

    private func updateContextAction() {
        guard usesDuoWorkspace else { return }
        contextAction.set(.back) {
            if endpointPicker != nil {
                endpointPicker = nil
            } else {
                dismiss()
            }
        }
    }

    private func restorePlannerContextAction() {
        guard usesDuoWorkspace, let closeAction else {
            contextAction.clear()
            return
        }
        contextAction.set(.close) {
            contextAction.clear()
            closeAction()
        }
    }
}

private struct LinkProfileSingleColumn: View {
    let model: LinkProfileModel
    let chooseOnMap: (LinkProfileEndpoint) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                LinkProfileIntroduction()
                LinkProfileEndpointLayout(
                    model: model,
                    chooseOnMap: chooseOnMap
                )
                LinkProfileRadioSettings(model: model)
                LinkProfilePrivacyNotice()
                LinkProfileAction(model: model)
                if let result = model.result {
                    LinkProfileResultCard(result: result)
                }
            }
            .frame(maxWidth: 900, alignment: .leading)
            .padding()
            .frame(maxWidth: .infinity)
        }
    }
}

private struct LinkProfileEditor: View {
    @Bindable var model: LinkProfileModel
    let chooseOnMap: (LinkProfileEndpoint) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Link Profile")
                    .font(.headline)
                LinkProfileIntroduction()
                LinkProfileEndpointCard(
                    title: "Point A",
                    latitude: $model.latitudeA,
                    longitude: $model.longitudeA,
                    height: $model.heightA,
                    chooseOnMap: { chooseOnMap(.pointA) }
                )
                LinkProfileEndpointCard(
                    title: "Point B",
                    latitude: $model.latitudeB,
                    longitude: $model.longitudeB,
                    height: $model.heightB,
                    chooseOnMap: { chooseOnMap(.pointB) }
                )
                LinkProfileRadioSettings(model: model)
                LinkProfilePrivacyNotice()
            }
            .padding(.leading)
            .padding(.vertical)
            .padding(.trailing, 32)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            LinkProfilePinnedAction(model: model)
        }
        .clipped()
    }
}

private struct LinkProfileWorkspace: View {
    let model: LinkProfileModel

    var body: some View {
        ZStack(alignment: .bottom) {
            LinkProfileRouteMap(
                pointA: model.coordinateA,
                pointB: model.coordinateB
            )
            .ignoresSafeArea(.container, edges: .bottom)

            VStack {
                Spacer()
                if let result = model.result {
                    ScrollView {
                        LinkProfileResultCard(result: result)
                    }
                    .frame(maxHeight: 300)
                } else {
                    LinkProfileReadyOverlay()
                }
            }
            .frame(maxWidth: 480)
            .padding(.leading, 20)
            .padding(.trailing, 96)
            .padding(.bottom, 20)
        }
    }
}

private struct LinkProfileReadyOverlay: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Ready to analyze", systemImage: "chart.xyaxis.line")
                .font(.headline)
            Text("Choose both endpoints, then analyze the link to estimate terrain clearance and path loss.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct LinkProfileRouteMap: View {
    let pointA: CLLocationCoordinate2D?
    let pointB: CLLocationCoordinate2D?

    var body: some View {
        Map {
            if let pointA {
                Marker("Point A", coordinate: pointA)
                    .tint(.blue)
            }
            if let pointB {
                Marker("Point B", coordinate: pointB)
                    .tint(.orange)
            }
            if let pointA, let pointB {
                MapPolyline(coordinates: [pointA, pointB])
                    .stroke(NodeScopeStyle.signal, lineWidth: 3)
            }
        }
        .mapStyle(.standard(elevation: .realistic))
        .mapControls {
            MapCompass()
            MapScaleView()
        }
        .accessibilityLabel("Map of the proposed link from Point A to Point B")
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

    static func initialPickerPosition(
        endpoint: LinkProfileEndpoint,
        pointA: CLLocationCoordinate2D?,
        pointB: CLLocationCoordinate2D?
    ) -> MapCameraPosition {
        let otherEndpoint = endpoint == .pointA ? pointB : pointA
        guard let otherEndpoint else {
            return .userLocation(fallback: .automatic)
        }
        return .region(MKCoordinateRegion(
            center: otherEndpoint,
            span: MKCoordinateSpan(latitudeDelta: 0.5, longitudeDelta: 0.5)
        ))
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
    var frequencyMHz = 915.0
    var isAnalyzing = false
    var result: NodePlannerLinkAnalysis?
    var errorMessage: LocalizedStringResource?

    private let analyzer: NodePlannerLinkAnalyzer

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

    init(analyzer: NodePlannerLinkAnalyzer = NodePlannerLinkAnalyzer(
        elevationProvider: OpenMeteoElevationProvider()
    )) {
        self.analyzer = analyzer
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
            result = try await analyzer.analyze(
                NodePlannerLinkAnalysisRequest(
                    pointA: pointA,
                    pointB: pointB,
                    heightAMeters: heightA,
                    heightBMeters: heightB,
                    frequencyMHz: frequencyMHz,
                    refractionK: 4.0 / 3.0,
                    linkBudget: nil,
                    sampleCount: nil
                )
            )
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
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 16) {
                LinkProfileEndpointCard(
                    title: "Point A",
                    latitude: $model.latitudeA,
                    longitude: $model.longitudeA,
                    height: $model.heightA,
                    chooseOnMap: { chooseOnMap(.pointA) }
                )
                LinkProfileEndpointCard(
                    title: "Point B",
                    latitude: $model.latitudeB,
                    longitude: $model.longitudeB,
                    height: $model.heightB,
                    chooseOnMap: { chooseOnMap(.pointB) }
                )
            }
            VStack(spacing: 16) {
                LinkProfileEndpointCard(
                    title: "Point A",
                    latitude: $model.latitudeA,
                    longitude: $model.longitudeA,
                    height: $model.heightA,
                    chooseOnMap: { chooseOnMap(.pointA) }
                )
                LinkProfileEndpointCard(
                    title: "Point B",
                    latitude: $model.latitudeB,
                    longitude: $model.longitudeB,
                    height: $model.heightB,
                    chooseOnMap: { chooseOnMap(.pointB) }
                )
            }
        }
    }
}

private struct LinkProfileEndpointCard: View {
    let title: LocalizedStringResource
    @Binding var latitude: Double?
    @Binding var longitude: Double?
    @Binding var height: Double
    let chooseOnMap: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)
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
            LabeledContent("Antenna height") {
                HStack(spacing: 4) {
                    TextField("Height", value: $height, format: .number.precision(.fractionLength(0...1)))
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.decimalPad)
                    Text("m")
                        .foregroundStyle(.secondary)
                }
            }
            Button(action: chooseOnMap) {
                Label("Choose on Map", systemImage: "map")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct LinkProfileMapPicker: View {
    let endpoint: LinkProfileEndpoint
    let pointA: CLLocationCoordinate2D?
    let pointB: CLLocationCoordinate2D?
    let select: (CLLocationCoordinate2D) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedCoordinate: CLLocationCoordinate2D?
    @State private var position: MapCameraPosition = .automatic

    init(
        endpoint: LinkProfileEndpoint,
        pointA: CLLocationCoordinate2D?,
        pointB: CLLocationCoordinate2D?,
        select: @escaping (CLLocationCoordinate2D) -> Void
    ) {
        self.endpoint = endpoint
        self.pointA = pointA
        self.pointB = pointB
        self.select = select
        _selectedCoordinate = State(initialValue: nil)
        _position = State(initialValue: LinkProfileEndpoint.initialPickerPosition(
            endpoint: endpoint,
            pointA: pointA,
            pointB: pointB
        ))
    }

    var body: some View {
        NavigationStack {
            MapReader { proxy in
                Map(position: $position) {
                    if endpoint != .pointA, let pointA {
                        Marker("Point A", coordinate: pointA)
                            .tint(.blue)
                    }
                    if endpoint != .pointB, let pointB {
                        Marker("Point B", coordinate: pointB)
                            .tint(.orange)
                    }
                    if let selectedCoordinate {
                        Annotation("Selected location", coordinate: selectedCoordinate) {
                            Image(systemName: "mappin.circle.fill")
                                .font(.largeTitle)
                                .foregroundStyle(NodeScopeStyle.signal)
                                .background(.background, in: Circle())
                                .accessibilityLabel("Selected location")
                        }
                    }
                }
                .mapStyle(.standard(elevation: .realistic))
                .mapControls {
                    MapCompass()
                    MapScaleView()
                }
                .simultaneousGesture(
                    SpatialTapGesture()
                        .onEnded { value in
                            if let coordinate = proxy.convert(value.location, from: .local) {
                                selectedCoordinate = coordinate
                            }
                        }
                )
                .safeAreaInset(edge: .bottom) {
                    LinkProfileMapSelectionBar(
                        endpointTitle: endpoint.title,
                        coordinate: selectedCoordinate,
                        cancel: { dismiss() },
                        confirm: {
                            if let selectedCoordinate {
                                select(selectedCoordinate)
                                dismiss()
                            }
                        }
                    )
                }
            }
            .navigationTitle("Choose \(endpoint.title)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

private struct LinkProfileInlineMapPicker: View {
    let endpoint: LinkProfileEndpoint
    let pointA: CLLocationCoordinate2D?
    let pointB: CLLocationCoordinate2D?
    let cancel: () -> Void
    let select: (CLLocationCoordinate2D) -> Void

    @State private var selectedCoordinate: CLLocationCoordinate2D?
    @State private var position: MapCameraPosition = .automatic

    init(
        endpoint: LinkProfileEndpoint,
        pointA: CLLocationCoordinate2D?,
        pointB: CLLocationCoordinate2D?,
        cancel: @escaping () -> Void,
        select: @escaping (CLLocationCoordinate2D) -> Void
    ) {
        self.endpoint = endpoint
        self.pointA = pointA
        self.pointB = pointB
        self.cancel = cancel
        self.select = select
        _selectedCoordinate = State(initialValue: nil)
        _position = State(initialValue: LinkProfileEndpoint.initialPickerPosition(
            endpoint: endpoint,
            pointA: pointA,
            pointB: pointB
        ))
    }

    var body: some View {
        MapReader { proxy in
            Map(position: $position) {
                if endpoint != .pointA, let pointA {
                    Marker("Point A", coordinate: pointA)
                        .tint(.blue)
                }
                if endpoint != .pointB, let pointB {
                    Marker("Point B", coordinate: pointB)
                        .tint(.orange)
                }
                if let selectedCoordinate {
                    Annotation("Selected location", coordinate: selectedCoordinate) {
                        Image(systemName: "mappin.circle.fill")
                            .font(.largeTitle)
                            .foregroundStyle(NodeScopeStyle.signal)
                            .background(.background, in: Circle())
                            .accessibilityLabel("Selected location")
                    }
                }
            }
            .mapStyle(.standard(elevation: .realistic))
            .mapControls {
                MapCompass()
                MapScaleView()
            }
            .simultaneousGesture(
                SpatialTapGesture()
                    .onEnded { value in
                        if let coordinate = proxy.convert(value.location, from: .local) {
                            selectedCoordinate = coordinate
                        }
                    }
            )
            .overlay(alignment: .topLeading) {
                Label(endpoint.title, systemImage: "mappin.and.ellipse")
                    .font(.headline)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule())
                    .padding()
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                LinkProfileMapSelectionBar(
                    endpointTitle: endpoint.title,
                    coordinate: selectedCoordinate,
                    cancel: cancel,
                    confirm: {
                        if let selectedCoordinate {
                            select(selectedCoordinate)
                        }
                    }
                )
                .padding(.trailing, 96)
            }
        }
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
        .background(.regularMaterial)
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
            LabeledContent("Frequency") {
                HStack(spacing: 4) {
                    TextField("Frequency", value: $model.frequencyMHz, format: .number.precision(.fractionLength(0...3)))
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.decimalPad)
                    Text("MHz")
                        .foregroundStyle(.secondary)
                }
            }
            Text("Standard atmospheric refraction (k = 4/3) is used for this first version.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
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

private struct LinkProfilePinnedAction: View {
    let model: LinkProfileModel

    var body: some View {
        LinkProfileAction(model: model)
            .padding(.leading)
            .padding(.trailing, 32)
            .padding(.vertical, 12)
            .background(.bar)
            .overlay(alignment: .top) {
                Divider()
            }
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
                    value: Measurement(value: result.result.distanceMeters, unit: UnitLength.meters)
                        .formatted(.measurement(width: .abbreviated, usage: .road))
                )
                LinkProfileMetricRow(
                    label: "Path loss",
                    value: result.result.totalPathLossDB.formatted(.number.precision(.fractionLength(1))) + " dB"
                )
                LinkProfileMetricRow(
                    label: "Worst Fresnel clearance",
                    value: result.result.worstClearancePercent.formatted(.number.precision(.fractionLength(0))) + "%"
                )
                LinkProfileMetricRow(
                    label: "Terrain source",
                    value: "Copernicus GLO-90 · Open-Meteo"
                )
            }
        }
        .padding()
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
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
            context.draw(
                Text("\(Int(y))").font(.system(size: 9)).foregroundStyle(.secondary),
                at: coordinates.point(x: xRange.lowerBound, y: y),
                anchor: .trailing
            )
        }
        for index in 0...4 {
            let fraction = Double(index) / 4
            let x = xRange.upperBound * fraction
            let label = (x / 1_000).formatted(.number.precision(.fractionLength(xRange.upperBound < 10_000 ? 1 : 0)))
            context.draw(
                Text(index == 4 ? "\(label) km" : label).font(.system(size: 9)).foregroundStyle(.secondary),
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
        let distance = Measurement(value: analysis.result.distanceMeters, unit: UnitLength.meters)
            .formatted(.measurement(width: .abbreviated, usage: .road))
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
