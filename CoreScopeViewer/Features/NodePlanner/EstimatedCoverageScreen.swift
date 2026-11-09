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
    @State private var runner = CoverageStudyRunner()
    @State private var calculation: Task<Void, Never>?
    @State private var layer: CoverageMapLayer?
    @State private var inputError: String?
    @State private var overlayError = false
    @Environment(\.scenePhase) private var scenePhase

    private var fields: [String: String] { values.merging(["latitude": latitude, "longitude": longitude]) { _, new in new } }
    private var nodeSnapshots: [CoverageNodeSnapshot] {
        knownNodes.compactMap { node in
            guard let point = node.coordinate else { return nil }
            return CoverageNodeSnapshot(sourceID: settings.sourceSession.id.description, nodeID: node.id,
                latitude: point.latitude, longitude: point.longitude)
        }
    }
    private var activeResult: CoverageStudyResult? {
        guard runner.phase == .completed, let result = runner.result,
              let current = try? CoverageStudyInputs.fromEditor(fields: fields, radiusMeters: radius, spacingMeters: spacing, heightInFeet: heightInFeet),
              result.inputs.matches(current), result.nodes.run.nodesToEvaluate == nodeSnapshots else { return nil }
        return result
    }
    private var nodeStatuses: [String: CoverageNodeStatus] {
        guard let result = activeResult else { return [:] }
        return Dictionary(uniqueKeysWithValues: knownNodes.compactMap { node in
            guard let point = node.coordinate else { return nil }
            return (node.id, result.nodes.run.status(for: CoverageNodeSnapshot(sourceID: settings.sourceSession.id.description,
                nodeID: node.id, latitude: point.latitude, longitude: point.longitude)))
        })
    }
    private func invalidate() {
        runner.invalidate()
        layer = nil
        inputError = nil
        calculation?.cancel()
    }
    private func calculate() {
        guard calculation == nil, !runner.isBusy else { return }
        let inputs: CoverageStudyInputs
        do { inputs = try CoverageStudyInputs.fromEditor(fields: fields, radiusMeters: radius, spacingMeters: spacing, heightInFeet: heightInFeet) }
        catch { inputError = String(localized: "Review the highlighted inputs and choose a smaller area or wider spacing."); return }
        let snapshots = nodeSnapshots
        let root = URL.cachesDirectory.appendingPathComponent("estimated-coverage-terrain-v1", isDirectory: true)
        inputError = nil
        layer = nil
        calculation = Task {
            defer { calculation = nil }
            await runner.runWithProgress(inputs: inputs) { id, captured, progress in
                try await CoveragePreparedStudy.evaluate(inputs: captured, nodes: snapshots,
                    catalogComplete: false, nodeLimit: 200,
                    limits: CoverageStudyLimits(areaSamples: 120_000_000, areaGeometry: 120_000_000,
                        nodeSamples: 30_000_000, nodeGeometry: 30_000_000, nodeModelCalls: 1000),
                    samplingHaloMeters: 100, cacheRoot: root, cacheQuota: 1_000_000_000,
                    downloadLimit: 500_000_000, offline: false, executionID: id, progress: progress)
            }
        }
    }
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
                EstimatedCoverageMap(coordinate: coordinate, locationInput: [latitude, longitude], nodes: knownNodes, layer: activeResult == nil ? nil : layer, statuses: nodeStatuses) { point in
                    latitude = String(point.latitude)
                    longitude = String(point.longitude)
                }
                .frame(maxWidth: .infinity)
                .frame(height: wide ? nil : max(220, geometry.size.height * 0.38))
                EstimatedCoverageEditor(latitude: $latitude, longitude: $longitude, values: $values,
                    radius: $radius, spacing: $spacing, showsNodes: $showsNodes,
                    runner: runner, result: activeResult, busy: calculation != nil || runner.isBusy,
                    inputError: inputError, overlayError: overlayError, rendering: activeResult != nil && layer == nil,
                    calculate: calculate, cancel: { runner.cancel(); calculation?.cancel(); layer = nil },
                    heightInFeet: heightInFeet, nodeCount: knownNodes.count, catalogLoading: catalog.isLoading, catalogError: catalog.errorMessage)
                    .frame(width: wide ? 380 : nil)
            }
        }
        .onChange(of: latitude) { refreshKnownNodes() }
        .onChange(of: fields) { invalidate() }
        .onChange(of: radius) { invalidate() }
        .onChange(of: spacing) { invalidate() }
        .onChange(of: scenePhase) { _, phase in if phase == .background { runner.cancel(); calculation?.cancel() } }
        .onDisappear { runner.cancel(); calculation?.cancel() }
        .task(id: activeResult?.executionID) {
            layer = nil
            overlayError = false
            guard let result = activeResult else { return }
            let worker = Task.detached {
                try CoverageRaster(grid: result.grid, transmitter: result.transmitter, area: result.area)
            }
            do {
                let raster = try await withTaskCancellationHandler { try await worker.value } onCancel: { worker.cancel() }
                guard !Task.isCancelled, activeResult?.executionID == result.executionID else { return }
                layer = CoverageMapLayer(executionID: result.executionID, raster: raster)
            } catch { if !Task.isCancelled { overlayError = true } }
        }
        .onChange(of: longitude) { refreshKnownNodes() }
        .navigationTitle("Estimated Coverage")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if calculation != nil || runner.isBusy {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel calculation") { runner.cancel(); calculation?.cancel(); layer = nil }
                }
            }
        }
        .sheet(isPresented: $showsNodes) {
            NavigationStack {
                List {
                    Section {
                        Text("This list shows the nearest 200 located nodes available in the current catalog. Predictions use your receiver assumptions, not reported node equipment. It is not a complete inventory of nearby radios.")
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
                                Text(nodeStatuses[node.id]?.coverageLabel ?? String(localized: "Not evaluated")).font(.subheadline)
                                if let margin = nodeStatuses[node.id]?.adjustedMarginDb {
                                    Text("Margin after reserve: \(margin, specifier: "%.1f") dB").font(.monaspace(.caption))
                                }
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
            invalidate()
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
    let layer: CoverageMapLayer?
    let statuses: [String: CoverageNodeStatus]
    let select: (CLLocationCoordinate2D) -> Void
    @State private var proposal: (coordinate: CLLocationCoordinate2D, input: [String])?
    private var tentative: CLLocationCoordinate2D? {
        proposal?.input == locationInput ? proposal?.coordinate : nil
    }
    @AppStorage(MapDisplayStyle.defaultsKey) private var style = MapDisplayStyle.standard

    var body: some View {
        CoverageMapHost(coordinate: coordinate, tentative: tentative, nodes: nodes, style: style, layer: layer, nodeStatuses: statuses) {
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
                Text(layer == nil ? "Nodes: not evaluated" : "Tap nodes for predictions; green shading is estimated coverage").font(.caption).foregroundStyle(.secondary)
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
    let runner: CoverageStudyRunner
    let result: CoverageStudyResult?
    let busy: Bool
    let inputError: String?
    let overlayError: Bool
    let rendering: Bool
    let calculate: () -> Void
    let cancel: () -> Void
    @State private var acceptsScenario = false
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
                Text("Beta estimate: terrain-only, inland paths, vertical polarization and median atmosphere. No building or tree clutter model; terrain surfaces may still contain vegetation. Not suitable for coastal or sea paths without a different model configuration.")
                    .font(.subheadline)
                Toggle("Use these model assumptions", isOn: $acceptsScenario)
                Text("Downloads terrain on demand (up to 500 MB per run; 1 GB cache). Start with a small area. Keep this screen open; leaving or backgrounding cancels active work.")
                    .font(.caption).foregroundStyle(.secondary)
                if busy {
                    ProgressView(runner.preparationStage == .preparingTerrain ? "Downloading and preparing terrain…" : runner.preparationStage == .calculating ? "Calculating paths…" : "Validating and cleaning up…")
                    Button("Cancel calculation", action: cancel)
                } else {
                    Button("Calculate coverage") { showsRequired = true; calculate() }
                        .disabled(!acceptsScenario || workFits != true || catalogLoading)
                }
                if showsRequired && result == nil { Text(reviewMessage).font(.caption) }
                if let inputError { Text(inputError).foregroundStyle(.red) }
                if let failure = runner.failure {
                    Text(failure.coverageMessage).foregroundStyle(.red)
                }
                if runner.phase == .cancelled { Text("Calculation cancelled. No partial result was published.").font(.caption) }
                if runner.phase == .stale { Text("Inputs or catalog changed. Calculate again to update the result.").font(.caption) }
                if rendering && !overlayError { ProgressView("Preparing coverage overlay…") }
                if overlayError { Text("The result could not be drawn. Retry the calculation.").foregroundStyle(.red) }
                if let result { CoverageResultsView(result: result) }
                Text("This setup is not saved when you leave the tool.").font(.caption).foregroundStyle(.secondary)
            }.instrumentListRows()
        }
        .meshPageBackground()
        .scrollDismissesKeyboard(.interactively)
        .task(id: "\(radius):\(spacing)") {
            workCheck = nil
            let checkedRadius = radius, checkedSpacing = spacing
            guard let grid else { return }
            let worker = Task.detached(priority: .userInitiated) {
                (try? CoverageGridPreflight.estimate(grid: grid, sampleBudget: 120_000_000,
                    geometryBudget: 120_000_000, cancelled: { Task.isCancelled })) != nil
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
