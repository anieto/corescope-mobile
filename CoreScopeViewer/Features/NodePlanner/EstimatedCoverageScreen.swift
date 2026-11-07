import SwiftUI
import MapKit

struct EstimatedCoverageScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @State private var catalog = FieldToolsViewModel(reference: nil)
    @State private var knownNodes: [MeshNode] = []
    @State private var locatedNodes: [MeshNode] = []
    @State private var latitude = ""
    @State private var longitude = ""
    @State private var values: [String: String] = [:]
    @State private var radius = 20_000
    @State private var spacing = 1000
    @State private var showsNodes = false
    // Freeze the display unit for this draft so entered heights never change meaning.
    @State private var heightInFeet = (UserDefaults.standard.string(forKey: DistanceUnit.defaultsKey)
        .flatMap(DistanceUnit.init(rawValue:)) ?? .imperial) == .imperial

    private var coordinate: CLLocationCoordinate2D? {
        guard let lat = CoverageSetupValidation.number(latitude), let lon = CoverageSetupValidation.number(longitude),
              CoverageSetupValidation.supportsCoordinate(latitude: lat, longitude: lon) else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width >= 800
            let layout = wide ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
            layout {
                EstimatedCoverageMap(coordinate: coordinate, locationInput: [latitude, longitude], nodes: knownNodes) { point in
                    latitude = String(point.latitude)
                    longitude = String(point.longitude)
                }
                .frame(maxWidth: .infinity)
                .frame(height: wide ? nil : max(220, geometry.size.height * 0.38))
                EstimatedCoverageEditor(latitude: $latitude, longitude: $longitude, values: $values,
                    radius: $radius, spacing: $spacing, showsNodes: $showsNodes,
                    heightInFeet: heightInFeet, nodeCount: knownNodes.count, catalogLoading: catalog.isLoading, catalogError: catalog.errorMessage)
                    .frame(width: wide ? 380 : nil)
            }
        }
        .onChange(of: latitude) { refreshKnownNodes() }
        .onChange(of: longitude) { refreshKnownNodes() }
        .navigationTitle("Estimated Coverage")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showsNodes) {
            NavigationStack {
                List {
                    Section {
                        Text("Known nodes are not evaluated yet. After you select a transmitter, this list shows the nearest 200 located nodes available in the current catalog. It is not a complete inventory of nearby radios.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }.instrumentListRows()
                    Section {
                        if knownNodes.isEmpty {
                            Text(coordinate == nil ? "Select a transmitter location to find nearby known nodes." : "No located nodes are available in the current catalog.")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
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
            locatedNodes = []
            catalog.configure(settings: settings)
            await catalog.load(region: regionFilter.selectedRegion)
            guard !Task.isCancelled else { return }
            var seen = Set<String>()
            locatedNodes = catalog.nodes.filter { $0.coordinate != nil && seen.insert($0.id).inserted }
            refreshKnownNodes()
        }
    }

    private func refreshKnownNodes() {
        guard let coordinate,
              let origin = FieldCoordinate(latitude: coordinate.latitude, longitude: coordinate.longitude) else {
            knownNodes = []
            return
        }
        // Rank the full available catalog before capping markers; API order is not proximity.
        let ranked = locatedNodes.compactMap { node -> (node: MeshNode, distance: Double)? in
            guard let point = node.coordinate,
                  let destination = FieldCoordinate(latitude: point.latitude, longitude: point.longitude) else { return nil }
            return (node, FieldGeometry.geometry(from: origin, to: destination).distanceMeters)
        }.sorted { left, right in
            left.distance == right.distance ? left.node.id < right.node.id : left.distance < right.distance
        }
        knownNodes = ranked.prefix(200).map(\.node)
    }
}

private struct EstimatedCoverageMap: View {
    let coordinate: CLLocationCoordinate2D?
    let locationInput: [String]
    let nodes: [MeshNode]
    let select: (CLLocationCoordinate2D) -> Void
    @State private var proposal: (coordinate: CLLocationCoordinate2D, input: [String])?
    private var tentative: CLLocationCoordinate2D? {
        proposal?.input == locationInput ? proposal?.coordinate : nil
    }
    @AppStorage(MapDisplayStyle.defaultsKey) private var style = MapDisplayStyle.standard

    var body: some View {
        CoverageMapHost(coordinate: coordinate, tentative: tentative, nodes: nodes, style: style) {
            proposal = ($0, locationInput)
        }
        .onChange(of: locationInput) { proposal = nil }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 6) {
                if let tentative {
                    let supported = CoverageSetupValidation.supportsCoordinate(latitude: tentative.latitude, longitude: tentative.longitude)
                    if !supported {
                        Text("Choose a location between −85° and 85° latitude.")
                            .font(.subheadline).foregroundStyle(.red)
                    }
                    HStack {
                        Button("Cancel") { proposal = nil }
                        Spacer()
                        Button("Use location") { select(tentative); proposal = nil }
                            .buttonStyle(.borderedProminent)
                            .disabled(!supported)
                    }
                } else {
                    Text("Touch and hold to choose a transmitter location.").font(.subheadline)
                }
                Text("Gray nodes: not evaluated").font(.caption).foregroundStyle(.secondary)
            }.padding(10).background(.regularMaterial)
        }
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
    let showsRequired: Bool
    var heightInFeet = false
    var body: some View {
        ForEach(fields) { field in
            let title: LocalizedStringKey = heightInFeet && ["txHeight", "rxHeight"].contains(field.id) ? "Antenna height (ft)" : field.title
            VStack(alignment: .leading, spacing: 6) {
                LearnLabel(title, term: field.term)
                TextField("Enter value", text: Binding(get: { values[field.id, default: ""] }, set: { values[field.id] = $0 }))
                    .font(.monaspace(.body)).keyboardType(.numbersAndPunctuation)
                    .accessibilityLabel(title)
                CoverageFieldIssue(field: field.id, text: values[field.id, default: ""], showsRequired: showsRequired, heightInFeet: heightInFeet)
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
    let heightInFeet: Bool
    let nodeCount: Int
    let catalogLoading: Bool
    let catalogError: String?
    @State private var showsRequired = false
    @State private var workCheck: (radius: Int, spacing: Int, fits: Bool)?
    private var workFits: Bool? {
        guard let workCheck, workCheck.radius == radius, workCheck.spacing == spacing else { return nil }
        return workCheck.fits
    }
    private var reviewMessage: LocalizedStringKey {
        let fields = values.merging(["latitude": latitude, "longitude": longitude]) { _, new in new }
        do {
            _ = try CoverageStudyInputs.fromEditor(fields: fields, radiusMeters: radius, spacingMeters: spacing, heightInFeet: heightInFeet)
        } catch is CoverageStudyInputFailure {
            return "Review the highlighted fields above. All inputs are required."
        } catch is CoverageGridFailure {
            return "Review the study area above. Choose wider spacing or a smaller radius."
        } catch {
            return "The combined radio values exceed calculation limits. Review transmit power, antenna gains, losses, sensitivity and reserve."
        }
        switch workFits {
        case nil: return "Checking calculation limits for this study area."
        case false: return "Review the study area above. Choose wider spacing or a smaller radius."
        case true: return "Setup passes input and initial work checks. Terrain availability and model suitability have not been checked. No coverage has been calculated."
        }
    }
    private var grid: CoverageGrid? {
        try? CoverageGrid(radiusMeters: radius, spacingMeters: spacing,
            minEastMeters: -radius, maxEastMeters: radius, minNorthMeters: -radius, maxNorthMeters: radius)
    }
    var body: some View {
        Form {
            Section {
                CoverageCurrentLocation(latitude: $latitude, longitude: $longitude)
                LearnLabel("Coordinates", term: .planningCoordinates)
                TextField("Latitude", text: $latitude).keyboardType(.numbersAndPunctuation).font(.monaspace(.body))
                CoverageFieldIssue(field: "latitude", text: latitude, showsRequired: showsRequired)
                TextField("Longitude", text: $longitude).keyboardType(.numbersAndPunctuation).font(.monaspace(.body))
                CoverageFieldIssue(field: "longitude", text: longitude, showsRequired: showsRequired)
                if catalogLoading { ProgressView("Loading known nodes") }
                if let catalogError { Text(catalogError).font(.caption).foregroundStyle(.secondary) }
                Button("Known nodes (\(nodeCount))") { showsNodes = true }
            } header: { Text("Transmitter location").font(.monaspace(.headline)) }
                .instrumentListRows()
            Section {
                CoverageRadioFields(fields: CoverageInputField.transmitter, values: $values, showsRequired: showsRequired, heightInFeet: heightInFeet)
            } header: { Text("Transmitter").font(.monaspace(.headline)) }.instrumentListRows()
            Section {
                Text("Use the same assumed receiver for the coverage map and known nodes. These are not the nodes’ reported equipment settings.")
                    .font(.caption).foregroundStyle(.secondary)
                CoverageRadioFields(fields: CoverageInputField.receiver, values: $values, showsRequired: showsRequired, heightInFeet: heightInFeet)
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
                    if workFits == nil {
                        ProgressView("Checking calculation limits")
                    } else if workFits == false {
                        Text("This area needs more calculation work than the current limit allows. Choose wider spacing or a smaller radius.")
                            .font(.subheadline).foregroundStyle(.red)
                    } else {
                        Text("Within the initial work limit. Terrain complexity may still prevent a complete result.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } else {
                    Text("This radius and detail exceed the current grid limit. Choose wider spacing or a smaller radius.")
                        .font(.subheadline).foregroundStyle(.orange)
                }
            } header: { Text("Study area").font(.monaspace(.headline)) }.instrumentListRows()
            Section {
                Text("Coverage calculation is not available in this build. You can review the setup, but no coverage or node reception has been predicted.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button("Review inputs") { showsRequired = true }
                if showsRequired {
                    Text(reviewMessage)
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Button("Calculate coverage") {}.disabled(true)
                Text("This setup is not saved when you leave the tool.").font(.caption).foregroundStyle(.secondary)
            }.instrumentListRows()
        }
        .meshPageBackground()
        .task(id: "\(radius):\(spacing)") {
            workCheck = nil
            let checkedRadius = radius, checkedSpacing = spacing
            guard let grid else { return }
            let worker = Task.detached(priority: .userInitiated) {
                (try? CoverageGridPreflight.estimate(grid: grid, sampleBudget: 150_000_000,
                    geometryBudget: 150_000_000, cancelled: { Task.isCancelled })) != nil
            }
            let result = await withTaskCancellationHandler { await worker.value } onCancel: { worker.cancel() }
            guard !Task.isCancelled else { return }
            workCheck = (checkedRadius, checkedSpacing, result)
        }
    }
}

private struct CoverageFieldIssue: View {
    let field: String
    let text: String
    let showsRequired: Bool
    var heightInFeet = false
    var body: some View {
        if let issue = CoverageSetupValidation.issue(field: field, text: text, heightInFeet: heightInFeet), issue != .required || showsRequired {
            Text(message(issue)).font(.caption).foregroundStyle(.red)
        }
    }
    private func message(_ issue: CoverageSetupIssue) -> LocalizedStringKey {
        switch issue {
        case .required: "Enter a value."
        case .number: "Enter a finite number using a decimal point."
        case .latitude: "Coverage calculations support latitude from −85° to 85°."
        case .longitude: "Longitude must be between −180° and 180°."
        case .frequency: "Frequency must be between 30 and 6000 MHz."
        case .height: heightInFeet ? "Antenna height must be between 1 and 3000 meters above ground (about 3.28 to 9842.52 feet)." : "Antenna height must be between 1 and 3000 m above ground."
        case .nonnegative: "Enter zero or a positive value."
        case .unknownField: "This field is not supported."
        }
    }
}

/// Requests location only after a tap; edits or navigation cancel pending delivery.
private struct CoverageCurrentLocation: View {
    @Binding var latitude: String
    @Binding var longitude: String
    @Environment(\.scenePhase) private var scenePhase
    @State private var manager = MapLocationManager()
    @State private var locating = false
    @State private var message: LocalizedStringResource?

    var body: some View {
        Button("Use current location", systemImage: "location") {
            locating = true
            message = nil
            manager.requestCurrentLocation { coordinate in
                locating = false
                guard CoverageSetupValidation.supportsCoordinate(latitude: coordinate.latitude, longitude: coordinate.longitude) else {
                    message = "Your location is outside the supported latitude range of −85° to 85°. Choose a point on the map."
                    return
                }
                latitude = String(coordinate.latitude)
                longitude = String(coordinate.longitude)
                message = "Location added to this setup. Check the pin on the map, especially when using approximate location."
            } failure: {
                locating = false
                message = "Couldn't get your location. Check location permission and services in Settings, or choose a point on the map."
            }
        }
        .disabled(locating)
        .onChange(of: latitude) { cancel() }
        .onChange(of: longitude) { cancel() }
        .onChange(of: scenePhase) { _, phase in if phase == .background { cancel() } }
        .onDisappear { cancel() }
        if locating {
            ProgressView("Finding location…")
            Button("Cancel location request") { cancel() }
        }
        if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
    }

    private func cancel() {
        manager.cancelRequest()
        locating = false
    }
}
