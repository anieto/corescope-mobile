import SwiftUI
import MapKit

struct EstimatedCoverageScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @State private var catalog = FieldToolsViewModel(reference: nil)
    @State private var knownNodes: [MeshNode] = []
    @State private var latitude = ""
    @State private var longitude = ""
    @State private var values: [String: String] = [:]
    @State private var radius = 20_000
    @State private var spacing = 1000
    @State private var showsNodes = false

    private var coordinate: CLLocationCoordinate2D? {
        guard let lat = Double(latitude), let lon = Double(longitude), lat.isFinite, lon.isFinite,
              (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width >= 800
            let layout = wide ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
            layout {
                EstimatedCoverageMap(coordinate: coordinate, nodes: knownNodes) { point in
                    latitude = String(point.latitude)
                    longitude = String(point.longitude)
                }
                .frame(maxWidth: .infinity)
                .frame(height: wide ? nil : max(220, geometry.size.height * 0.38))
                EstimatedCoverageEditor(latitude: $latitude, longitude: $longitude, values: $values,
                    radius: $radius, spacing: $spacing, showsNodes: $showsNodes,
                    nodeCount: knownNodes.count, catalogLoading: catalog.isLoading, catalogError: catalog.errorMessage)
                    .frame(width: wide ? 380 : nil)
            }
        }
        .navigationTitle("Estimated Coverage")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showsNodes) {
            NavigationStack {
                List {
                    Section {
                        Text("Known nodes are not evaluated yet. Showing up to 200 located nodes from the current catalog; this is not a complete inventory of nearby radios.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }.instrumentListRows()
                    Section {
                        ForEach(knownNodes) { node in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(node.name ?? String(node.id.prefix(12))).font(.monaspace(.headline))
                                Label("Not evaluated", systemImage: "questionmark.circle").font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }.instrumentListRows()
                }
                .meshPageBackground()
                .navigationTitle("Known nodes")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showsNodes = false } } }
            }
        }
        .task(id: "\(settings.sourceSession.id)|\(regionFilter.selectedRegion ?? "")") {
            knownNodes = []
            catalog.configure(settings: settings)
            await catalog.load(region: regionFilter.selectedRegion)
            guard !Task.isCancelled else { return }
            var seen = Set<String>()
            knownNodes = Array(catalog.nodes.lazy.filter { $0.coordinate != nil && seen.insert($0.id).inserted }.prefix(200))
        }
    }
}

private struct EstimatedCoverageMap: View {
    let coordinate: CLLocationCoordinate2D?
    let nodes: [MeshNode]
    let select: (CLLocationCoordinate2D) -> Void
    @State private var position: MapCameraPosition = .automatic
    @State private var tentative: CLLocationCoordinate2D?
    @AppStorage(MapDisplayStyle.defaultsKey) private var style = MapDisplayStyle.standard

    var body: some View {
        MapReader { proxy in
            Map(position: $position) {
                ForEach(nodes) { node in
                    if let point = node.coordinate {
                        Marker(node.name ?? String(localized: "Known node"), systemImage: "questionmark", coordinate: point).tint(.gray)
                    }
                }
                if let coordinate {
                    Marker("Transmitter", systemImage: "antenna.radiowaves.left.and.right", coordinate: coordinate).tint(.orange)
                }
                if let tentative { Marker("Proposed location", coordinate: tentative).tint(.blue) }
            }
            .mapStyle(style.style)
            .mapControls { MapCompass(); MapScaleView() }
            .onChange(of: coordinate?.latitude) { centerOnTransmitter() }
            .onChange(of: coordinate?.longitude) { centerOnTransmitter() }
            .gesture(LinkProfileMapLongPress(action: { point in
                if let location = proxy.convert(point, from: .local), CLLocationCoordinate2DIsValid(location) {
                    tentative = location
                }
            }, ended: {}))
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 6) {
                    if let tentative {
                        HStack {
                            Button("Cancel") { self.tentative = nil }
                            Spacer()
                            Button("Use location") { select(tentative); self.tentative = nil }
                                .buttonStyle(.borderedProminent)
                        }
                    } else {
                        Text("Touch and hold to choose a transmitter location.").font(.subheadline)
                    }
                    Text("Gray nodes: not evaluated").font(.caption).foregroundStyle(.secondary)
                }.padding(10).background(.regularMaterial)
            }
        }
    }

    private func centerOnTransmitter() {
        guard let coordinate else { return }
        position = .region(MKCoordinateRegion(center: coordinate,
            latitudinalMeters: 20_000, longitudinalMeters: 20_000))
    }
}

@MainActor
private struct CoverageInputField: Identifiable {
    let id: String
    let title: LocalizedStringKey
    let term: GlossaryTerm
    static let transmitter: [Self] = [
        .init(id: "frequency", title: "Frequency (MHz)", term: .radioFrequency),
        .init(id: "power", title: "Transmit power (dBm)", term: .transmitPower),
        .init(id: "txHeight", title: "Antenna height (m)", term: .antennaHeight),
        .init(id: "txGain", title: "Antenna gain (dBi)", term: .antennaGain),
        .init(id: "txLoss", title: "Cable / system loss (dB)", term: .cableLoss)
    ]
    static let receiver: [Self] = [
        .init(id: "rxHeight", title: "Antenna height (m)", term: .antennaHeight),
        .init(id: "rxGain", title: "Antenna gain (dBi)", term: .antennaGain),
        .init(id: "rxLoss", title: "Cable / system loss (dB)", term: .cableLoss),
        .init(id: "sensitivity", title: "Sensitivity (dBm)", term: .receiverSensitivity),
        .init(id: "reserve", title: "Planning reserve (dB)", term: .planningReserve)
    ]
}

private struct CoverageRadioFields: View {
    let fields: [CoverageInputField]
    @Binding var values: [String: String]
    var body: some View {
        ForEach(fields) { field in
            VStack(alignment: .leading, spacing: 6) {
                LearnLabel(field.title, term: field.term)
                TextField("Enter value", text: Binding(get: { values[field.id, default: ""] }, set: { values[field.id] = $0 }))
                    .font(.monaspace(.body)).keyboardType(.numbersAndPunctuation)
                    .accessibilityLabel(field.title)
            }
        }
    }
}

private struct EstimatedCoverageEditor: View {
    @Binding var latitude: String
    @Binding var longitude: String
    @Binding var values: [String: String]
    @Binding var radius: Int
    @Binding var spacing: Int
    @Binding var showsNodes: Bool
    let nodeCount: Int
    let catalogLoading: Bool
    let catalogError: String?
    private var grid: CoverageGrid? {
        try? CoverageGrid(radiusMeters: radius, spacingMeters: spacing,
            minEastMeters: -radius, maxEastMeters: radius, minNorthMeters: -radius, maxNorthMeters: radius)
    }
    var body: some View {
        Form {
            Section {
                LearnLabel("Coordinates", term: .planningCoordinates)
                TextField("Latitude", text: $latitude).keyboardType(.numbersAndPunctuation).font(.monaspace(.body))
                TextField("Longitude", text: $longitude).keyboardType(.numbersAndPunctuation).font(.monaspace(.body))
                if catalogLoading { ProgressView("Loading known nodes") }
                if let catalogError { Text(catalogError).font(.caption).foregroundStyle(.secondary) }
                Button("Known nodes (\(nodeCount))") { showsNodes = true }
            } header: { Text("Transmitter location").font(.monaspace(.headline)) }
                .instrumentListRows()
            Section {
                CoverageRadioFields(fields: CoverageInputField.transmitter, values: $values)
            } header: { Text("Transmitter").font(.monaspace(.headline)) }.instrumentListRows()
            Section {
                Text("Use the same assumed receiver for the coverage map and known nodes. These are not the nodes’ reported equipment settings.")
                    .font(.caption).foregroundStyle(.secondary)
                CoverageRadioFields(fields: CoverageInputField.receiver, values: $values)
            } header: { Text("Receiver assumptions").font(.monaspace(.headline)) }.instrumentListRows()
            Section {
                Picker(selection: $radius) {
                    Text("5 km").tag(5000); Text("20 km").tag(20_000); Text("50 km").tag(50_000); Text("100 km").tag(100_000)
                } label: { LearnLabel("Study radius", term: .studyRadius) }
                Picker(selection: $spacing) {
                    Text("100 m").tag(100); Text("200 m").tag(200); Text("500 m").tag(500); Text("1 km").tag(1000); Text("2 km").tag(2000)
                } label: { LearnLabel("Sample spacing", term: .coverageDetail) }
                if let grid {
                    LabeledContent("Map samples") { Text(grid.count.formatted()).font(.monaspace(.body)) }
                } else {
                    Text("This radius and detail exceed the current grid limit. Choose wider spacing or a smaller radius.")
                        .font(.subheadline).foregroundStyle(.orange)
                }
            } header: { Text("Study area").font(.monaspace(.headline)) }.instrumentListRows()
            Section {
                Text("Coverage calculation is not available in this build. You can review the setup, but no coverage or node reception has been predicted.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button("Calculate coverage") {}.disabled(true)
                Text("This setup is not saved when you leave the tool.").font(.caption).foregroundStyle(.secondary)
            }.instrumentListRows()
        }
        .meshPageBackground()
    }
}
