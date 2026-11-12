import Observation
import MapKit
import SwiftUI

/// Estimated Coverage, laid out like Link Profile: the map fills the screen, and the setup and
/// result live in a panel that collapses to a status header. A touch & hold places the
/// transmitter; presets fill in the radio, antennas and heights so a study takes a few taps.
struct EstimatedCoverageScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.isHingedDevice) private var isHingedDevice
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = EstimatedCoverageModel()
    @State private var catalog = FieldToolsViewModel(reference: nil)
    @State private var isWideLayout = false
    @State private var showsNodes = false
    /// Shared with the live map and Link Profile: picking a style here changes them all.
    @AppStorage(MapDisplayStyle.defaultsKey) private var mapDisplayStyle = MapDisplayStyle.standard

    init(savedStudy: SavedStudy? = nil) {
        let model = EstimatedCoverageModel()
        if let savedStudy { model.restore(savedStudy) }
        _model = State(initialValue: model)
    }

    var body: some View {
        GeometryReader { geometry in
            Group {
                if usesDuoWorkspace {
                    DuoTwoPaneLayout {
                        EstimatedCoverageMapPane(
                            model: model,
                            mapDisplayStyle: $mapDisplayStyle,
                            promptTopClearance: geometry.safeAreaInsets.top,
                            trailingClearance: geometry.safeAreaInsets.trailing
                        )
                        .safeAreaPadding(.trailing)
                        .clipped()
                    } secondary: {
                        EstimatedCoverageEditor(
                            model: model,
                            showsNodes: $showsNodes,
                            // The split extends beneath the side toolbar; restore its live inset for the editor.
                            trailingPadding: 20 + geometry.safeAreaInsets.trailing,
                            topInset: 24
                        )
                            .background(Color(uiColor: .systemGroupedBackground))
                            .clipped()
                    }
                    .ignoresSafeArea(.container, edges: [.top, .bottom])
                } else if geometry.size.width >= 900 {
                    // iPad: both panes run up under the transparent bar, which holds the back
                    // button and the map style menu; content starts below it.
                    HStack(spacing: 0) {
                        EstimatedCoverageMapPane(
                            model: model,
                            mapDisplayStyle: $mapDisplayStyle,
                            showsStyleMenu: false,
                            promptTopClearance: geometry.safeAreaInsets.top
                        )
                        .ignoresSafeArea(.container, edges: .bottom)
                        EstimatedCoverageEditor(model: model, showsNodes: $showsNodes, topInset: geometry.safeAreaInsets.top)
                            .frame(width: min(420, max(340, geometry.size.width * 0.34)))
                    }
                    .ignoresSafeArea(.container, edges: .top)
                } else {
                    EstimatedCoverageCompactWorkspace(
                        model: model,
                        showsNodes: $showsNodes,
                        mapDisplayStyle: $mapDisplayStyle,
                        promptLeadingClearance: showsOverlayBackButton ? 60 : 0
                    )
                    .ignoresSafeArea(.container, edges: isHingedDevice ? .top : [])
                }
            }
            .onGeometryChange(for: Bool.self) { $0.size.width >= 900 } action: { isWideLayout = $0 }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        // iPhone: the map runs edge to edge under a floating back button. iPad and the Duo show
        // the system bar for its back button; it is otherwise invisible, with no title, since the
        // editor pane carries the heading.
        .navigationTitle(usesSystemBar ? "" : "Estimated Coverage")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(usesSystemBar ? .visible : .hidden, for: .navigationBar)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar {
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
        .onChange(of: model.inputSignature) { model.invalidate() }
        .onChange(of: model.coordinateSignature) { model.refreshKnownNodes() }
        .onChange(of: scenePhase) { _, phase in if phase == .background { model.cancel() } }
        .onDisappear { model.cancel() }
        .task { model.diagnosticReport = CoverageRunDiagnostics.readLatest() }
        .task(id: model.activeResult?.executionID) { await model.renderOverlay() }
        .task(id: "\(model.radiusMeters):\(model.spacingMeters)") { await model.checkWork() }
        .sheet(isPresented: $showsNodes) { CoverageKnownNodesSheet(model: model) }
        .task(id: "\(settings.sourceSession.id)|\(regionFilter.selectedRegion ?? "")|\(model.usesSavedCatalog)") {
            guard !model.usesSavedCatalog else { return }
            model.invalidate()
            model.setCatalog([], sourceID: settings.sourceSession.id.description)
            model.catalogLoading = true
            model.catalogError = nil
            catalog.configure(settings: settings)
            await catalog.load(region: regionFilter.selectedRegion)
            guard !Task.isCancelled else { return }
            model.catalogLoading = false
            model.catalogError = catalog.errorMessage
            model.setCatalog(catalog.nodes, sourceID: settings.sourceSession.id.description)
        }
    }

    private var usesDuoWorkspace: Bool {
        isHingedDevice && horizontalSizeClass == .regular
    }

    /// iPad and the Duo use the system navigation bar's back button; only the iPhone floats its own.
    private var usesSystemBar: Bool {
        isHingedDevice || isWideLayout
    }

    private var showsOverlayBackButton: Bool {
        !usesSystemBar
    }
}

// MARK: - Model

/// The last transmitter placement, offered back for a few seconds.
struct CoverageUndo: Identifiable {
    let id = UUID()
    let message: LocalizedStringResource
    let coordinate: CLLocationCoordinate2D?
    let name: String?
}

/// Why Calculate is not available yet.
enum CoverageSetupProblem: Equatable {
    case noTransmitter
    case field(String, CoverageSetupIssue)
    case radio
    case grid
    case checkingWork
    case tooMuchWork
}

/// The setup and the run it produces. Inputs are kept as numbers; presets write into them, and
/// editing a value a preset set makes the radio custom, as in Link Profile.
@MainActor
@Observable
final class EstimatedCoverageModel {
    var savedStudy: SavedStudy?
    var usesSavedCatalog = false
    private var savedCatalog: [SavedCoverageNode] = []
    private var calculatedCatalog: [SavedCoverageNode] = []
    private var savedCatalogComplete = false
    var catalogComplete: Bool { usesSavedCatalog && savedCatalogComplete }

    func savedSnapshot() -> SavedStudy? {
        guard (try? inputs()) != nil, let latitude, let longitude else { return nil }
        let nodes = activeResult == nil ? currentSavedNodes : calculatedCatalog
        let coverage = SavedCoverage(
            transmitter: SavedEndpoint(name: transmitterName, latitude: latitude, longitude: longitude,
                antenna: SavedAntenna(heightMeters: transmitHeightMeters, gainDbi: transmitGainDbi, lossDb: transmitLossDb)),
            receiver: SavedAntenna(heightMeters: receiveHeightMeters, gainDbi: receiveGainDbi, lossDb: receiveLossDb),
            frequencyMHz: frequencyMHz, transmitPowerDbm: transmitPowerDbm,
            sensitivityDbm: sensitivityDbm, reserveDb: reserveDb, radiusMeters: radiusMeters,
            spacingMeters: spacingMeters, nodes: nodes, catalogComplete: catalogComplete,
            calculatedResult: activeResult.flatMap { try? SavedCoverageCalculation($0).encoded() })
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let study = SavedStudy(id: savedStudy?.id ?? UUID().uuidString.lowercased(),
            name: savedStudy?.name ?? "Estimated Coverage", createdAtMilliseconds: savedStudy?.createdAtMilliseconds ?? now,
            updatedAtMilliseconds: now, kind: .coverage, coverage: coverage)
        return (try? study.validate()) != nil ? study : nil
    }

    private var currentSavedNodes: [SavedCoverageNode] {
        if usesSavedCatalog { return savedCatalog }
        return knownNodes.compactMap { node in
            guard let coordinate = node.coordinate else { return nil }
            return SavedCoverageNode(sourceID: sourceID, nodeID: node.id, name: node.name, role: node.role,
                latitude: coordinate.latitude, longitude: coordinate.longitude,
                lastSeenMilliseconds: node.latestActivity.flatMap {
                    let milliseconds = $0.timeIntervalSince1970 * 1000
                    return milliseconds.isFinite && (0...253_402_300_799_999).contains(milliseconds) ? Int64(milliseconds) : nil
                })
        }
    }

    func restore(_ study: SavedStudy) {
        guard (try? study.validate()) != nil, let coverage = study.coverage else { return }
        invalidate()
        savedStudy = study
        latitude = coverage.transmitter.latitude
        longitude = coverage.transmitter.longitude
        transmitterName = coverage.transmitter.name
        transmitHeightMeters = coverage.transmitter.antenna.heightMeters
        transmitGainDbi = coverage.transmitter.antenna.gainDbi
        transmitLossDb = coverage.transmitter.antenna.lossDb
        receiveHeightMeters = coverage.receiver.heightMeters
        receiveGainDbi = coverage.receiver.gainDbi
        receiveLossDb = coverage.receiver.lossDb
        frequencyMHz = coverage.frequencyMHz
        transmitPowerDbm = coverage.transmitPowerDbm
        sensitivityDbm = coverage.sensitivityDbm
        reserveDb = coverage.reserveDb
        radiusMeters = coverage.radiusMeters
        spacingMeters = coverage.spacingMeters
        radioPresetID = nil
        acceptsScenario = false
        savedCatalog = coverage.nodes
        savedCatalogComplete = coverage.catalogComplete
        usesSavedCatalog = true
        knownNodes = coverage.nodes.map { node in
            MeshNode(publicKey: node.nodeID, name: node.name, role: node.role,
                lat: node.latitude, lon: node.longitude,
                lastSeen: node.lastSeenMilliseconds.map { Date(timeIntervalSince1970: Double($0) / 1000) },
                firstSeen: nil, advertCount: nil, hashSize: nil, hashSizeInconsistent: nil,
                lastHeard: nil, defaultScope: nil)
        }
        locatedNodes = knownNodes
        sourceID = coverage.nodes.first?.sourceID ?? "saved"
        calculatedCatalog = coverage.nodes
        if let data = coverage.calculatedResult, let inputs = try? inputs() {
            do {
                checkpoint = try SavedCoverageCalculation.decode(data).restore(inputs: inputs, nodes: nodeSnapshots,
                    catalogComplete: catalogComplete)
            } catch {
                inputError = "The saved calculation could not be restored. Your inputs are available; calculate again to replace the result."
            }
        }
    }

    // Transmitter location.
    var latitude: Double?
    var longitude: Double?
    /// The known node the transmitter was placed on, or "Current location"; nil for a dropped pin.
    var transmitterName: String?
    /// Choose New Point: the panel collapses and the header prompts for a touch & hold.
    var isChoosingLocation = false
    var undo: CoverageUndo?

    // Radio. The preset is nil once a value it sets is edited.
    var radioPresetID: String? = CoverageRadioPreset.standard.id
    var frequencyMHz = CoverageRadioPreset.standard.frequencyMHz
    var transmitPowerDbm = CoverageRadioPreset.standard.transmitPowerDbm
    var sensitivityDbm = CoverageRadioPreset.standard.sensitivityDbm
    var reserveDb = 10.0

    // Transmitter antenna.
    var transmitHeightMeters = CoverageHeightPreset.rooftop.meters
    var transmitGainDbi = 2.15
    var transmitLossDb = 1.0

    // The assumed receiver, used for the footprint and every known node alike.
    var receiveHeightMeters = CoverageHeightPreset.handheld.meters
    var receiveGainDbi = 2.15
    var receiveLossDb = 1.0

    // Study area.
    var radiusMeters: Int

    init(distanceUnit: DistanceUnit = DistanceUnit(rawValue: UserDefaults.standard.string(forKey: DistanceUnit.defaultsKey) ?? "") ?? .imperial) {
        radiusMeters = distanceUnit == .imperial ? 16_093 : 20_000
    }
    var spacingMeters = 1000
    var acceptsScenario = false

    // Execution.
    let runner = CoverageStudyRunner()
    private(set) var isCalculating = false
    @ObservationIgnored private var calculation: Task<Void, Never>?
    var layer: CoverageMapLayer?
    var inputError: LocalizedStringResource?
    var overlayError = false
    private var checkpoint: CoverageStudyResult?
    var diagnosticReport: String?
    var workCheck: (radius: Int, spacing: Int, fits: Bool)?
    var minimumWorkSamples: Int64?

    // Known nodes from the active catalog, nearest first.
    private(set) var locatedNodes: [MeshNode] = []
    private(set) var knownNodes: [MeshNode] = []
    private(set) var sourceID = ""
    var catalogLoading = false
    var catalogError: String?

    static let radiusChoices = [5000, 20_000, 50_000, 100_000]
    static let imperialRadiusChoices = [8047, 16_093, 40_234, 80_467]
    static let spacingChoices = [100, 200, 500, 1000, 2000, 5000, 10_000]

    var coordinate: CLLocationCoordinate2D? {
        guard let latitude, let longitude else { return nil }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var radioPreset: CoverageRadioPreset? { CoverageRadioPreset.preset(id: radioPresetID) }

    /// The editor contract the study inputs are parsed from; heights are already in meters.
    var fields: [String: String] {
        [
            "latitude": latitude.map { String($0) } ?? "",
            "longitude": longitude.map { String($0) } ?? "",
            "frequency": String(frequencyMHz),
            "power": String(transmitPowerDbm),
            "txHeight": String(transmitHeightMeters),
            "txGain": String(transmitGainDbi),
            "txLoss": String(transmitLossDb),
            "rxHeight": String(receiveHeightMeters),
            "rxGain": String(receiveGainDbi),
            "rxLoss": String(receiveLossDb),
            "sensitivity": String(sensitivityDbm),
            "reserve": String(reserveDb)
        ]
    }

    /// Every input a result depends on; a change makes the result stale.
    var inputSignature: String {
        fields.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: "|") + "|\(radiusMeters)|\(spacingMeters)"
    }

    var coordinateSignature: String { "\(latitude ?? .nan),\(longitude ?? .nan)" }

    func issue(for field: String) -> CoverageSetupIssue? {
        CoverageSetupValidation.issue(field: field, text: fields[field, default: ""])
    }

    func inputs() throws -> CoverageStudyInputs {
        try CoverageStudyInputs.fromEditor(fields: fields, radiusMeters: radiusMeters, spacingMeters: spacingMeters)
    }

    var grid: CoverageGrid? {
        try? CoverageGrid.studyArea(radiusMeters: radiusMeters, spacingMeters: spacingMeters)
    }

    var workFits: Bool? {
        guard let workCheck, workCheck.radius == radiusMeters, workCheck.spacing == spacingMeters else { return nil }
        return workCheck.fits
    }

    var setupProblem: CoverageSetupProblem? {
        guard coordinate != nil else { return .noTransmitter }
        do { _ = try inputs() }
        catch CoverageStudyInputFailure.field(let field, let issue) { return .field(field, issue) }
        catch is CoverageGridFailure { return .grid }
        catch { return .radio }
        switch workFits {
        case nil: return .checkingWork
        case false: return .tooMuchWork
        case true: return nil
        }
    }

    var isBusy: Bool { isCalculating || runner.isBusy }

    var canCalculate: Bool {
        setupProblem == nil && acceptsScenario && !catalogLoading && !isBusy
    }

    var nodeSnapshots: [CoverageNodeSnapshot] {
        if usesSavedCatalog {
            return savedCatalog.map { CoverageNodeSnapshot(sourceID: $0.sourceID, nodeID: $0.nodeID, latitude: $0.latitude, longitude: $0.longitude) }
        }
        return knownNodes.compactMap { node in
            guard let point = node.coordinate else { return nil }
            return CoverageNodeSnapshot(sourceID: sourceID, nodeID: node.id, latitude: point.latitude, longitude: point.longitude)
        }
    }

    /// The published result, while the inputs and catalog it was made from are unchanged.
    var activeResult: CoverageStudyResult? {
        let available = runner.phase == .completed ? runner.result : checkpoint
        guard let result = available, let current = try? inputs(),
              result.inputs.matches(current), result.nodes.run.nodesToEvaluate == nodeSnapshots else { return nil }
        return result
    }

    var nodeStatuses: [String: CoverageNodeStatus] {
        guard let result = activeResult else { return [:] }
        return Dictionary(uniqueKeysWithValues: nodeSnapshots.map { node in
            (node.nodeID, result.nodes.run.status(for: node))
        })
    }

    // MARK: Presets

    func applyRadioPreset(_ preset: CoverageRadioPreset) {
        radioPresetID = preset.id
        frequencyMHz = preset.frequencyMHz
        transmitPowerDbm = preset.transmitPowerDbm
        sensitivityDbm = preset.sensitivityDbm
    }

    /// A binding to a radio value a preset sets; editing it makes the radio custom.
    func radioBinding(_ keyPath: ReferenceWritableKeyPath<EstimatedCoverageModel, Double>) -> Binding<Double> {
        Binding(
            get: { self[keyPath: keyPath] },
            set: {
                self[keyPath: keyPath] = $0
                self.radioPresetID = nil
            }
        )
    }

    func binding(_ keyPath: ReferenceWritableKeyPath<EstimatedCoverageModel, Double>) -> Binding<Double> {
        Binding(get: { self[keyPath: keyPath] }, set: { self[keyPath: keyPath] = $0 })
    }

    // MARK: Transmitter

    /// Places or moves the transmitter from the map or the location button, with Undo.
    func place(_ coordinate: CLLocationCoordinate2D, name: String?) {
        guard CLLocationCoordinate2DIsValid(coordinate), abs(coordinate.latitude) <= 85 else {
            inputError = "Choose a transmitter between 85°S and 85°N."
            return
        }
        inputError = nil
        let moved = self.coordinate != nil
        let before = (self.coordinate, transmitterName)
        latitude = coordinate.latitude
        longitude = coordinate.longitude
        transmitterName = name
        isChoosingLocation = false
        undo = CoverageUndo(message: moved ? "Transmitter moved" : "Transmitter placed", coordinate: before.0, name: before.1)
    }

    /// Clears the transmitter and its result, with Undo; the radio and antennas are kept.
    func clearTransmitter() {
        let before = (coordinate, transmitterName)
        latitude = nil
        longitude = nil
        transmitterName = nil
        isChoosingLocation = false
        undo = CoverageUndo(message: "Transmitter removed", coordinate: before.0, name: before.1)
    }

    func undoLastChange() {
        guard let undo else { return }
        latitude = undo.coordinate?.latitude
        longitude = undo.coordinate?.longitude
        transmitterName = undo.name
        isChoosingLocation = false
        self.undo = nil
    }

    /// Typed coordinates are no longer the known node the transmitter was placed on.
    func coordinatesTyped() {
        transmitterName = nil
        undo = nil
    }

    // MARK: Catalog

    func setCatalog(_ nodes: [MeshNode], sourceID: String) {
        self.sourceID = sourceID
        var seen = Set<String>()
        locatedNodes = nodes.filter { node in
            guard let coordinate = node.coordinate, CLLocationCoordinate2DIsValid(coordinate) else { return false }
            return seen.insert(node.id).inserted
        }
        refreshKnownNodes()
    }

    func refreshKnownNodes() {
        guard !usesSavedCatalog else { return }
        guard let coordinate,
              let origin = FieldCoordinate(latitude: coordinate.latitude, longitude: coordinate.longitude) else {
            // Keep a bounded catalog visible so the first transmitter can be placed on a node.
            // Once a transmitter is chosen, rank by distance before applying the same cap.
            knownNodes = Array(locatedNodes.prefix(200))
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

    // MARK: Execution

    func invalidate() {
        checkpoint = nil
        runner.invalidate()
        layer = nil
        inputError = nil
        calculation?.cancel()
    }

    func cancel() {
        runner.cancel()
        calculation?.cancel()
        if activeResult == nil { layer = nil }
    }

    func calculate(continuing: Bool = false) {
        guard calculation == nil, !runner.isBusy else { return }
        let inputs: CoverageStudyInputs
        do { inputs = try self.inputs() }
        catch { inputError = "Review the highlighted inputs and choose a smaller area or wider spacing."; return }
        let previous = continuing ? activeResult : nil
        if continuing && previous == nil { return }
        checkpoint = previous
        let snapshots = nodeSnapshots
        calculatedCatalog = currentSavedNodes
        let catalogComplete = self.catalogComplete
        let root = URL.cachesDirectory.appendingPathComponent("estimated-coverage-terrain-v1", isDirectory: true)
        inputError = nil
        if previous == nil { layer = nil }
        isCalculating = true
        calculation = Task {
            defer {
                calculation = nil
                if runner.phase == .completed { checkpoint = nil }
                isCalculating = false
                diagnosticReport = CoverageRunDiagnostics.readLatest()
            }
            await runner.runWithProgress(inputs: inputs) { id, captured, progress in
                try await CoveragePreparedStudy.evaluate(inputs: captured, nodes: snapshots,
                    catalogComplete: catalogComplete, nodeLimit: 200,
                    limits: CoverageStudyLimits(areaSamples: 120_000_000, areaGeometry: 120_000_000,
                        nodeSamples: 30_000_000, nodeGeometry: 30_000_000, nodeModelCalls: 1000),
                    samplingHaloMeters: 100, cacheRoot: root, cacheQuota: 1_000_000_000,
                    downloadLimit: 500_000_000, offline: false, executionID: id,
                    resuming: previous, diagnosticURL: CoverageRunDiagnostics.applicationURL, progress: progress)
            }
        }
    }

    /// Builds the overlay raster for the active result on a worker.
    func renderOverlay() async {
        layer = nil
        overlayError = false
        guard let result = activeResult else { return }
        let worker = Task.detached {
            try CoverageRaster(grid: result.grid, transmitter: result.transmitter, area: result.area)
        }
        do {
            let raster = try await withTaskCancellationHandler { try await worker.value } onCancel: { worker.cancel() }
            guard !Task.isCancelled, activeResult?.executionID == result.executionID else { return }
            layer = CoverageMapLayer(executionID: result.executionID, raster: raster, budget: result.budget)
        } catch {
            if !Task.isCancelled { overlayError = true }
        }
    }

    /// Checks the study area against the initial work limit, off the main thread.
    func checkWork() async {
        workCheck = nil
        minimumWorkSamples = nil
        let checkedRadius = radiusMeters, checkedSpacing = spacingMeters
        guard let grid else { return }
        let worker = Task.detached(priority: .userInitiated) {
            try? CoverageGridPreflight.estimate(grid: grid, sampleBudget: 120_000_000,
                geometryBudget: 120_000_000, cancelled: { Task.isCancelled })
        }
        let estimate = await withTaskCancellationHandler { await worker.value } onCancel: { worker.cancel() }
        guard !Task.isCancelled else { return }
        workCheck = (checkedRadius, checkedSpacing, estimate != nil)
        minimumWorkSamples = estimate?.minimumSamples
    }
}

// MARK: - Layouts

/// iPhone: the map fills the screen under a panel that collapses to its header (the prompt, the
/// progress, then the verdict) and expands to the transmitter, radio, receiver, area and result.
/// Versioned, value-only checkpoint. Opening it performs no downloads or RF calculations.
struct SavedCoverageCalculation: Codable {
    var version = 1
    let inputValues: [Double]
    let area: CoverageAreaResult
    let limits: CoverageStudyLimits
    let model: CoverageStudyModel?
    let terrain: [Terrain]?
    let nodes: [Node]

    struct Node: Codable {
        let snapshot: CoverageNodeSnapshot
        let distance: Double
        let prediction: CoverageNodePrediction
    }

    struct Terrain: Codable {
        let dataset, revision, tile, url, verticalDatum, attribution, sourceType, originURL: String
        let compressedSHA256, sourceETag: String?
        let packedBytes: Int64
        let side: Int

        init(_ source: CoverageTerrainDescriptor) {
            dataset = source.dataset; revision = source.revision; tile = source.tile
            url = source.url; verticalDatum = source.verticalDatum; attribution = source.attribution
            sourceType = source.sourceType; originURL = source.originURL
            compressedSHA256 = source.compressedSHA256; sourceETag = source.sourceETag
            packedBytes = source.packedBytes; side = source.side
        }

        func restore() throws -> CoverageTerrainDescriptor {
            try CoverageTerrainDescriptor(dataset: dataset, revision: revision, tile: tile,
                compressedSHA256: compressedSHA256, packedBytes: packedBytes, side: side, url: url,
                verticalDatum: verticalDatum, attribution: attribution, sourceType: sourceType,
                originURL: originURL, sourceETag: sourceETag)
        }
    }

    init(_ result: CoverageStudyResult) {
        inputValues = Self.values(result.inputs)
        area = result.area; limits = result.limits; model = result.model
        terrain = result.terrainSources?.descriptors.map(Terrain.init)
        nodes = result.nodes.run.nodesToEvaluate.compactMap { node in
            guard let evaluation = result.nodes.run.evaluation(for: node) else { return nil }
            return Node(snapshot: node, distance: evaluation.distanceMeters, prediction: evaluation.prediction)
        }
    }

    func encoded() throws -> Data {
        let encoder = PropertyListEncoder()
        encoder.outputFormat = .binary
        return try encoder.encode(self)
    }

    static func decode(_ data: Data) throws -> Self {
        guard data.count <= 8 * 1024 * 1024 else { throw DeploymentPlanError.tooLarge }
        return try PropertyListDecoder().decode(Self.self, from: data)
    }

    func restore(inputs: CoverageStudyInputs, nodes snapshots: [CoverageNodeSnapshot],
                 catalogComplete: Bool) throws -> CoverageStudyResult {
        guard version == 1, inputValues == Self.values(inputs), area.cells.count == inputs.grid.count,
              nodes.map(\.snapshot) == snapshots, nodes.count <= 200,
              (terrain?.count ?? 0) <= 64,
              area.chargedSamples >= 0, area.chargedGeometry >= 0 else { throw DeploymentPlanError.invalidData }
        try limits.validate()
        // Reuse the scheduler's mask and link-budget validation without evaluating any paths.
        _ = try CoverageArea.evaluate(grid: inputs.grid, transmitter: inputs.transmitter, budget: inputs.budget,
            sampleBudget: Int64(limits.areaSamples), geometryBudget: Int64(limits.areaGeometry),
            resuming: area, shouldPause: { true },
            profile: { _, _, _, _ in throw DeploymentPlanError.invalidData },
            predictLoss: { _ in throw DeploymentPlanError.invalidData })
        var run = try CoverageNodeRun(nodes: snapshots, catalogComplete: catalogComplete,
            evaluationLimit: 200, radiusMeters: Double(inputs.grid.radiusMeters))
        for node in nodes {
            if case .qualifiedMargin(let margin) = node.prediction, !margin.isFinite {
                throw DeploymentPlanError.invalidData
            }
            guard run.accept(.init(runID: run.id, node: node.snapshot, distanceMeters: node.distance,
                                   prediction: node.prediction)) else { throw DeploymentPlanError.invalidData }
        }
        guard run.complete() else { throw DeploymentPlanError.invalidData }
        let sources = try terrain.map { try CoverageStudyTerrainSources(descriptors: $0.map { try $0.restore() }) }
        return CoverageStudyResult(executionID: UUID(), inputs: inputs, limits: limits, area: area,
            nodes: .init(run: run, chargedTerrainEvaluations: 0, chargedGeometryEvaluations: 0, modelCalls: 0),
            terrainSources: sources, model: model)
    }

    private static func values(_ inputs: CoverageStudyInputs) -> [Double] {
        let b = inputs.budget, g = inputs.grid
        return [inputs.transmitter.latitude, inputs.transmitter.longitude, inputs.frequencyMHz,
            inputs.transmitHeightMeters, inputs.receiveHeightMeters, b.transmitPowerDbm,
            b.transmitGainDbi, b.receiveGainDbi, b.transmitLossDb, b.receiveLossDb, b.sensitivityDbm,
            b.reserveDb, Double(g.radiusMeters), Double(g.spacingMeters), Double(g.minEastMeters),
            Double(g.maxNorthMeters), Double(g.columns), Double(g.rows)]
    }
}

private struct EstimatedCoverageCompactWorkspace: View {
    let model: EstimatedCoverageModel
    @Binding var showsNodes: Bool
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
                EstimatedCoverageMapPane(
                    model: model,
                    mapDisplayStyle: $mapDisplayStyle,
                    promptLeadingClearance: promptLeadingClearance,
                    bottomInset: headerHeight + 16,
                    extendsUnderSafeArea: true
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
                        .accessibilityLabel(isExpanded ? "Hide coverage setup" : "Show coverage setup")
                        CoverageHeader(model: model)
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
                            CoverageDetails(model: model, showsNodes: $showsNodes)
                                .padding(.horizontal)
                                .padding(.bottom)
                        }
                        .scrollDismissesKeyboard(.interactively)
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
                .overlay {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.28), radius: 18, y: 6)
                .padding(.horizontal, 8)
                .padding(.bottom, 8)
            }
        }
        // Choosing a point needs the map.
        .onChange(of: model.isChoosingLocation) { _, choosing in
            if choosing { setExpanded(false) }
        }
    }

    private func setExpanded(_ expanded: Bool) {
        withAnimation(.easeInOut) { isExpanded = expanded }
    }
}

/// iPad's side column and the Duo's right screen: the header stays put while the rest scrolls.
private struct EstimatedCoverageEditor: View {
    let model: EstimatedCoverageModel
    @Binding var showsNodes: Bool
    var trailingPadding: CGFloat = 20
    var topInset: CGFloat = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Estimated Coverage", systemImage: "antenna.radiowaves.left.and.right")
                .font(.monaspace(.title2).bold())
            CoverageHeader(model: model)
            ScrollView {
                CoverageDetails(model: model, showsNodes: $showsNodes)
                    .padding(.bottom, 20)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
        }
        .padding(.leading, 20)
        .padding(.trailing, trailingPadding)
        .padding(.top, 20 + topInset)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .meshPageBackground()
    }
}

// MARK: - Map

private struct EstimatedCoverageMapPane: View {
    let model: EstimatedCoverageModel
    @Binding var mapDisplayStyle: MapDisplayStyle
    /// iPad puts the style menu in the navigation bar instead.
    var showsStyleMenu = true
    var promptLeadingClearance: CGFloat = 0
    var promptTopClearance: CGFloat = 0
    /// How far the map runs on under another pane at its trailing edge.
    var trailingClearance: CGFloat = 0
    var bottomInset: CGFloat = 0
    var extendsUnderSafeArea = false
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(AnalyzerSourceRegistry.self) private var analyzerSourceRegistry
    @Environment(AnalyzerSettings.self) private var settings
    /// Ticks on every touch & hold the map acts on, for the haptic.
    @State private var holds = 0

    var body: some View {
        CoverageMapHost(
            coordinate: model.coordinate,
            nodes: model.knownNodes,
            style: mapDisplayStyle,
            layer: model.activeResult == nil ? nil : model.layer,
            nodeStatuses: model.nodeStatuses,
            openingRegion: RegionCameraFraming.openingRegion(regionFilter: regionFilter, registry: analyzerSourceRegistry, host: settings.host),
            bottomInset: bottomInset
        ) { coordinate, node in
            holds += 1
            model.place(coordinate, name: node?.name)
        }
        // Extend the map beneath the status bar, floating panel and tab bar while
        // its overlays continue to respect the safe area reserved for controls.
        .ignoresSafeArea(.container, edges: extendsUnderSafeArea ? .all : [])
        .sensoryFeedback(.impact(weight: .medium), trigger: holds)
        .overlay(alignment: .topTrailing) {
            if showsStyleMenu {
                LinkProfileMapStyleMenu(style: $mapDisplayStyle)
                    .padding()
                    .padding(.top, promptTopClearance)
                    .padding(.trailing, trailingClearance)
            }
        }
        // Prompts live in the header; the map only carries the legend once there is a result.
        .overlay(alignment: .topLeading) {
            if model.activeResult != nil, model.layer != nil {
                CoverageMarginLegendButton()
                .padding()
                .padding(.leading, promptLeadingClearance)
                .padding(.trailing, 60 + trailingClearance)
                .padding(.top, promptTopClearance)
            }
        }
        // A few seconds to take back a placement, move or removal.
        .overlay(alignment: .bottom) {
            if let undo = model.undo {
                HStack(spacing: 12) {
                    Text(undo.message)
                    Button("Undo") { model.undoLastChange() }
                        .fontWeight(.semibold)
                }
                .font(.subheadline)
                .padding(.leading, 16)
                .padding(.trailing, 12)
                .padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule())
                .padding(.bottom, 12)
                .padding(.trailing, trailingClearance)
                .transition(.opacity)
                .task(id: undo.id) {
                    try? await Task.sleep(for: .seconds(4))
                    guard !Task.isCancelled, model.undo?.id == undo.id else { return }
                    withAnimation { model.undo = nil }
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.undo?.id)
        .accessibilityLabel("Map for choosing the transmitter location")
    }
}

private struct CoverageLegendSwatch: View {
    let color: Color
    let title: LocalizedStringKey

    var body: some View {
        Label {
            Text(title)
        } icon: {
            RoundedRectangle(cornerRadius: 3)
                .fill(color)
                .frame(width: 18, height: 10)
        }
    }
}

// MARK: - Header

/// The always-visible line: what to do next, the run's progress, a failure, or the verdict.
/// Calculate and Reset sit beside it, so a study never needs the panel opened once it is set up.
private struct CoverageHeader: View {
    let model: EstimatedCoverageModel
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            status
            if !model.isChoosingLocation && model.coordinate != nil && !model.isBusy {
                Button {
                    model.clearTransmitter()
                } label: {
                    Label("Reset", systemImage: "arrow.counterclockwise")
                }
                .buttonStyle(.bordered)
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        VStack(alignment: .leading, spacing: 6) {
            if model.isChoosingLocation {
                Text("Choose a new transmitter location")
                    .font(.headline)
                Text("Touch & hold the map to place it.")
                    .foregroundStyle(.secondary)
                Button("Cancel") { model.isChoosingLocation = false }
                    .buttonStyle(.bordered)
                    .padding(.top, 4)
            } else if model.coordinate == nil {
                Text("Place your transmitter to estimate coverage.")
                    .font(.headline)
                Text("Touch & hold the map, on a known node or anywhere, or use your location.")
                    .foregroundStyle(.secondary)
                CoverageCurrentLocationButton(model: model)
                    .padding(.top, 4)
            } else if model.isBusy {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(stageText)
                        .font(.headline)
                }
                Text("Keep this screen open; leaving cancels the calculation.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("Cancel calculation") { model.cancel() }
                    .buttonStyle(.bordered)
                    .padding(.top, 4)
            } else if let failure = model.runner.failure {
                Label(failure.coverageMessage, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                Button("Try Again") { model.calculate(continuing: model.activeResult != nil) }
                    .buttonStyle(.bordered)
                    .disabled(!model.canCalculate)
            } else if let result = model.activeResult {
                let counts = result.sampleCounts
                Label {
                    Text(counts.unresolved > 0 ? "Partial coverage estimate" : "Estimated coverage")
                } icon: {
                    Image(systemName: counts.unresolved > 0 ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                        .foregroundStyle(counts.unresolved > 0 ? Color.orange : Color.green)
                }
                .font(.monaspace(.headline))
                Text("\(counts.above) above threshold · \(counts.below) below · \(counts.unresolved) unresolved")
                    .font(.monaspace(.subheadline))
                    .foregroundStyle(.secondary)
                if result.hasPendingWork {
                    Text("This batch left samples unfinished. Continue to calculate them using the same inputs and terrain.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button("Continue calculation") { model.calculate(continuing: true) }
                        .buttonStyle(.borderedProminent)
                        .disabled(!model.canCalculate)
                } else if result.area.workLimited {
                    Text(result.area.chargedSamples >= Int64(result.limits.areaSamples)
                        || result.area.chargedGeometry >= Int64(result.limits.areaGeometry)
                        ? "The study exhausted its work budget. Some samples were not calculated. Try a smaller radius or wider sample spacing."
                        : "Some individual paths exceeded their work limits. Wider grid spacing may not fix these paths; try a smaller radius.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("Hatched areas have no prediction.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if model.overlayError {
                    Text("The result could not be drawn. Calculate again.")
                        .font(.caption)
                        .foregroundStyle(.red)
                } else if model.layer == nil {
                    ProgressView("Preparing coverage overlay…")
                        .font(.caption)
                }
            } else {
                Text(readyTitle)
                    .font(.headline)
                Text(summary)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let hint = readyHint {
                    Text(hint)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button("Calculate coverage") { model.calculate() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!model.canCalculate)
                    .padding(.top, 4)
            }
            if let inputError = model.inputError {
                Text(inputError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
    }

    private var stageText: LocalizedStringKey {
        switch model.runner.preparationStage {
        case .preparingTerrain: "Downloading and preparing terrain…"
        case .calculating: "Calculating paths…"
        default: "Validating and cleaning up…"
        }
    }

    private var readyTitle: LocalizedStringKey {
        switch model.runner.phase {
        case .stale: "Inputs changed. Calculate again."
        case .cancelled: "Calculation cancelled."
        default: model.setupProblem == nil ? "Ready to calculate." : "Review the setup."
        }
    }

    /// The transmitter and area in a line: "USA/Canada · 22 dBm · Antenna 20 ft · 12.4 mi / 0.6 mi".
    private var summary: String {
        let radio = model.radioPreset?.title ?? String(localized: "Custom radio")
        let power = "\(model.transmitPowerDbm.formatted(.number.precision(.fractionLength(0...1)))) dBm"
        let antenna = String(localized: "Antenna \(distanceUnit.formatHeight(meters: model.transmitHeightMeters))")
        let area = "\(distanceUnit.format(kilometers: Double(model.radiusMeters) / 1000)) / \(distanceUnit.format(kilometers: Double(model.spacingMeters) / 1000))"
        return [radio, power, antenna, area].joined(separator: " · ")
    }

    private var readyHint: LocalizedStringKey? {
        switch model.setupProblem {
        case .noTransmitter: nil
        case .field: "A value is out of range. Open the setup to fix it."
        case .radio: "The combined radio values exceed calculation limits."
        case .grid, .tooMuchWork: "This area needs more work than the limit allows. Choose wider spacing or a smaller radius."
        case .checkingWork: "Checking calculation limits for this study area."
        case nil:
            if model.catalogLoading { "Loading known nodes…" }
            else if !model.acceptsScenario { "Accept the model assumptions in the setup to calculate." }
            else if model.runner.phase == .cancelled { "No partial result was published." }
            else { nil }
        }
    }
}

// MARK: - Details

/// Everything under the header: the transmitter, radio, receiver, study area and result.
private struct CoverageDetails: View {
    let model: EstimatedCoverageModel
    @Binding var showsNodes: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SavedStudyActions(kind: .coverage, snapshot: model.savedSnapshot) { model.savedStudy = $0 }
            if model.usesSavedCatalog {
                Text(model.savedStudy?.coverage?.calculatedResult == nil
                    ? "Saved node locations · Inputs-only save" : "Saved node locations · Saved coverage results").font(.caption).foregroundStyle(.secondary)
                Button("Refresh nodes from current source") { model.usesSavedCatalog = false }
            }
            CoverageDiagnosticsView(report: model.diagnosticReport)
            CoverageTransmitterCard(model: model)
            CoverageRadioCard(model: model)
            CoverageReceiverCard(model: model)
            CoverageStudyAreaCard(model: model)
            CoverageCalculationCard(model: model, showsNodes: $showsNodes)
            Text("Planning estimates only: terrain does not account for buildings, foliage or interference. Terrain is downloaded from AWS Skadi and cached on this device. Use Save to keep this setup before leaving the tool.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

/// The transmitter: where it is and its antenna. Tapping opens the height, antenna and loss,
/// moving the point, and typed coordinates.
private struct CoverageTransmitterCard: View {
    @Bindable var model: EstimatedCoverageModel
    @State private var isExpanded = false
    @State private var showsCoordinates = false
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                withAnimation(.easeInOut) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.monaspace(.subheadline).bold())
                        .foregroundStyle(.white)
                        .frame(width: 30, height: 30)
                        .background(.orange, in: Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Transmitter settings")
                            .font(.monaspace(.headline))
                            .foregroundStyle(.primary)
                        Text(title)
                            .font(.monaspace(.subheadline))
                            .foregroundStyle(.secondary)
                        Text(isExpanded ? "Hide location & antenna settings" : "Edit location & antenna")
                            .font(.subheadline)
                            .foregroundStyle(.tint)
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
            .accessibilityHint("Shows or hides transmitter location, antenna height, gain, and system loss.")

            if isExpanded {
                CoverageHeightSetting(title: "Antenna height", meters: model.binding(\.transmitHeightMeters),
                    presets: CoverageHeightPreset.transmitter, issue: model.issue(for: "txHeight"))
                CoverageAntennaSetting(gainDbi: model.binding(\.transmitGainDbi))
                CoverageNumericSetting(title: "System loss", term: .cableLoss, value: model.binding(\.transmitLossDb), unit: "dB",
                    issue: model.issue(for: "txLoss"))
                ViewThatFits(in: .horizontal) {
                    HStack { actions }
                    VStack(alignment: .leading) { actions }
                }
                .buttonStyle(.bordered)
                CoverageCurrentLocationButton(model: model)
                if showsCoordinates {
                    LabeledContent {
                        TextField("Latitude", value: latitude, format: .number.precision(.fractionLength(0...6)))
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numbersAndPunctuation)
                            .font(.monaspace(.body))
                    } label: {
                        LearnLabel("Latitude", term: .planningCoordinates)
                    }
                    CoverageIssueText(issue: model.issue(for: "latitude"))
                    LabeledContent("Longitude") {
                        TextField("Longitude", value: longitude, format: .number.precision(.fractionLength(0...6)))
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numbersAndPunctuation)
                            .font(.monaspace(.body))
                    }
                    CoverageIssueText(issue: model.issue(for: "longitude"))
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .instrumentCard(showsShadow: false)
    }

    @ViewBuilder
    private var actions: some View {
        Button(hasCoordinate ? "Choose New Point" : "Choose on Map", systemImage: "mappin.and.ellipse") {
            model.isChoosingLocation = true
        }
        Button(showsCoordinates ? "Hide Coordinates" : "Enter Coordinates", systemImage: "number") {
            showsCoordinates.toggle()
        }
    }

    private var hasCoordinate: Bool { model.coordinate != nil }

    private var title: String {
        guard hasCoordinate else { return String(localized: "Transmitter not placed") }
        return model.transmitterName ?? String(localized: "Dropped pin")
    }

    private var summary: String {
        let antenna = String(localized: "Antenna \(distanceUnit.formatHeight(meters: model.transmitHeightMeters)) · \(model.transmitGainDbi.formatted(.number.precision(.fractionLength(0...2)))) dBi")
        guard let coordinate = model.coordinate else { return antenna }
        let place = "\(coordinate.latitude.formatted(.number.precision(.fractionLength(4)))), \(coordinate.longitude.formatted(.number.precision(.fractionLength(4))))"
        return [place, antenna].joined(separator: " · ")
    }

    private var latitude: Binding<Double?> {
        Binding(get: { model.latitude }, set: { model.latitude = $0; model.coordinatesTyped() })
    }

    private var longitude: Binding<Double?> {
        Binding(get: { model.longitude }, set: { model.longitude = $0; model.coordinatesTyped() })
    }
}

/// Region and modem presets, then the values a preset sets. Editing one makes the radio custom.
private struct CoverageRadioCard: View {
    @Bindable var model: EstimatedCoverageModel
    @State private var isCustomizing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Radio settings")
                .font(.monaspace(.headline))
            Picker(selection: presetSelection) {
                ForEach(CoverageRadioPreset.Family.allCases) { family in
                    Section(family.title) {
                        ForEach(CoverageRadioPreset.presets(in: family)) { preset in
                            Text(preset.title).tag(preset.id)
                        }
                    }
                }
                if model.radioPreset == nil {
                    Text("Custom").tag("custom")
                }
            } label: {
                Text("Preset")
            }
            .pickerStyle(.menu)
            Text(summary)
                .font(.monaspace(.caption))
                .foregroundStyle(.secondary)
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
                CoverageNumericSetting(title: "Frequency", term: .radioFrequency, value: model.radioBinding(\.frequencyMHz), unit: "MHz",
                    issue: model.issue(for: "frequency"))
                CoverageNumericSetting(title: "Transmit power", term: .transmitPower, value: model.radioBinding(\.transmitPowerDbm), unit: "dBm",
                    issue: model.issue(for: "power"))
                CoverageNumericSetting(title: "Receiver sensitivity", term: .receiverSensitivity, value: model.radioBinding(\.sensitivityDbm), unit: "dBm",
                    issue: model.issue(for: "sensitivity"))
            }
            VStack(alignment: .leading, spacing: 6) {
                LearnLabel("Planning reserve", term: .planningReserve)
                CoveragePresetChips(
                    titles: CoverageReservePreset.choicesDb.map { $0 == 0 ? String(localized: "None") : "\($0.formatted()) dB" },
                    selected: CoverageReservePreset.choicesDb.firstIndex { abs($0 - model.reserveDb) < 0.01 }
                ) { model.reserveDb = CoverageReservePreset.choicesDb[$0] }
                CoverageNumericSetting(title: "Reserve", term: nil, value: model.binding(\.reserveDb), unit: "dB",
                    issue: model.issue(for: "reserve"))
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .instrumentCard(showsShadow: false)
    }

    private var presetSelection: Binding<String> {
        Binding(
            get: { model.radioPresetID ?? "custom" },
            set: { id in
                if let preset = CoverageRadioPreset.preset(id: id) { model.applyRadioPreset(preset) }
            }
        )
    }

    /// "910.525 MHz · SF7 · 62.5 kHz · 22 dBm · −127.5 dBm"
    private var summary: String {
        var parts = ["\(model.frequencyMHz.formatted(.number.precision(.fractionLength(0...3)))) MHz"]
        if let preset = model.radioPreset { parts.append(preset.modemSummary) }
        parts.append("\(model.transmitPowerDbm.formatted(.number.precision(.fractionLength(0...1)))) dBm")
        parts.append(String(localized: "sensitivity \(model.sensitivityDbm.formatted(.number.precision(.fractionLength(0...1)))) dBm"))
        return parts.joined(separator: " · ")
    }

    private var customizeTitle: LocalizedStringKey {
        if isCustomizing { return "Hide radio values" }
        return model.radioPreset == nil ? "Customize radio · edited" : "Customize radio"
    }
}

/// The assumed receiver: the same for the map and every known node.
private struct CoverageReceiverCard: View {
    @Bindable var model: EstimatedCoverageModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Receiver assumptions")
                .font(.monaspace(.headline))
            Text("One assumed receiver for the coverage map and the known nodes. These are not the nodes’ reported equipment.")
                .font(.caption)
                .foregroundStyle(.secondary)
            CoverageHeightSetting(title: "Antenna height", meters: model.binding(\.receiveHeightMeters),
                presets: CoverageHeightPreset.receiver, issue: model.issue(for: "rxHeight"))
            CoverageAntennaSetting(gainDbi: model.binding(\.receiveGainDbi))
            CoverageNumericSetting(title: "System loss", term: .cableLoss, value: model.binding(\.receiveLossDb), unit: "dB",
                issue: model.issue(for: "rxLoss"))
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .instrumentCard(showsShadow: false)
    }
}

private struct CoverageStudyAreaCard: View {
    private var radiusChoices: [Int] {
        let choices = distanceUnit == .imperial
            ? EstimatedCoverageModel.imperialRadiusChoices : EstimatedCoverageModel.radiusChoices
        // A unit change changes presentation, never the active study's radius.
        return choices.contains(model.radiusMeters) ? choices : (choices + [model.radiusMeters]).sorted()
    }

    @Bindable var model: EstimatedCoverageModel
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Study area")
                .font(.monaspace(.headline))
            VStack(alignment: .leading, spacing: 6) {
                LearnLabel("Study radius", term: .studyRadius)
                CoveragePresetChips(
                    titles: radiusChoices.map { distanceUnit.format(kilometers: Double($0) / 1000) },
                    selected: radiusChoices.firstIndex(of: model.radiusMeters)
                ) { model.radiusMeters = radiusChoices[$0] }
            }
            LabeledContent {
                Picker("Sample spacing", selection: $model.spacingMeters) {
                    ForEach(EstimatedCoverageModel.spacingChoices, id: \.self) { meters in
                        Text(distanceUnit.format(kilometers: Double(meters) / 1000)).tag(meters)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            } label: {
                LearnLabel("Sample spacing", term: .coverageDetail)
            }
            if let grid = model.grid {
                LabeledContent("Map samples") {
                    Text(grid.count.formatted()).font(.monaspace(.body))
                }
                if let minimum = model.minimumWorkSamples {
                    LabeledContent("Minimum terrain samples") {
                        Text(minimum.formatted()).font(.monaspace(.caption))
                    }
                    Text("Lower bound only; extra terrain detail and refinement add work. The area budget is 120 million samples per batch.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                switch model.workFits {
                case nil:
                    ProgressView("Checking calculation limits")
                        .font(.caption)
                case false:
                    Text("This area needs more calculation work than the current limit allows. Choose wider spacing or a smaller radius.")
                        .font(.subheadline)
                        .foregroundStyle(.red)
                case true:
                    Text("Minimum work fits one batch; complex terrain can require more. Each Continue action gets a new bounded work budget without recalculating completed samples.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("This radius and detail exceed the current grid limit. Choose wider spacing or a smaller radius.")
                    .font(.subheadline)
                    .foregroundStyle(.orange)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .instrumentCard(showsShadow: false)
    }
}

/// The model's assumptions to accept, the run's messages, and the result.
private struct CoverageCalculationCard: View {
    @Bindable var model: EstimatedCoverageModel
    @Binding var showsNodes: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Calculation")
                .font(.monaspace(.headline))
            Text("Beta estimate: terrain-only, inland paths, vertical polarization and median atmosphere. No building or tree clutter model; terrain surfaces may still contain vegetation. Not suitable for coastal or sea paths without a different model configuration.")
                .font(.subheadline)
            Toggle("Use these model assumptions", isOn: $model.acceptsScenario)
            Text("Downloads terrain on demand (up to 500 MB per run; 1 GB cache). Start with a small area.")
                .font(.caption)
                .foregroundStyle(.secondary)
            if model.catalogLoading { ProgressView("Loading known nodes") }
            if let error = model.catalogError {
                Text(error).font(.caption).foregroundStyle(.secondary)
            }
            if model.coordinate != nil {
                Button("Known nodes (\(model.knownNodes.count))", systemImage: "list.bullet") { showsNodes = true }
                    .buttonStyle(.bordered)
            }
            if model.runner.phase == .cancelled {
                Text(model.activeResult == nil ? "Calculation cancelled. No partial result was published." : "This batch was cancelled. The previous completed batch is still available.").font(.caption)
            }
            if model.runner.phase == .stale {
                Text("Inputs or catalog changed. Calculate again to update the result.").font(.caption)
            }
            if let result = model.activeResult {
                Divider()
                CoverageResultsView(result: result)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .instrumentCard(showsShadow: false)
    }
}

// MARK: - Settings controls

/// Typical heights as chips, then the exact value in the preferred unit.
private struct CoverageHeightSetting: View {
    let title: LocalizedStringKey
    @Binding var meters: Double
    let presets: [CoverageHeightPreset]
    let issue: CoverageSetupIssue?
    @AppStorage(DistanceUnit.defaultsKey) private var distanceUnit: DistanceUnit = .imperial

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            LearnLabel(title, term: .antennaHeight)
            CoveragePresetChips(
                titles: presets.map { "\($0.title) · \(distanceUnit.formatHeight(meters: $0.meters))" },
                selected: CoverageHeightPreset.matching(meters: meters, in: presets).flatMap { match in presets.firstIndex(of: match) }
            ) { index in
                let presetMeters = presets[index].meters
                // Imperial chips advertise whole feet; apply that exact displayed height.
                meters = distanceUnit == .imperial
                    ? distanceUnit.meters(fromDisplayedHeight: distanceUnit.displayHeight(meters: presetMeters).rounded())
                    : presetMeters
            }
            LabeledContent {
                HStack(spacing: 4) {
                    TextField(title, value: displayed, format: .number.precision(.fractionLength(0...2)))
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.decimalPad)
                        .font(.monaspace(.body))
                    Text(distanceUnit.heightSymbol)
                        .foregroundStyle(.secondary)
                }
            } label: {
                Text("Above ground")
            }
            CoverageIssueText(issue: issue)
        }
    }

    private var displayed: Binding<Double> {
        Binding(
            get: { distanceUnit.displayHeight(meters: meters) },
            set: { meters = distanceUnit.meters(fromDisplayedHeight: $0) }
        )
    }
}

/// An antenna type whose gain fills the field, or a custom gain.
private struct CoverageAntennaSetting: View {
    @Binding var gainDbi: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LearnLabel("Antenna", term: .antennaGain)
            Menu {
                Picker("Antenna", selection: selection) {
                    ForEach(CoverageAntennaPreset.all) { preset in
                        Text("\(preset.title) · \(preset.gainDbi.formatted(.number.precision(.fractionLength(0...2)))) dBi").tag(preset.id)
                    }
                    if CoverageAntennaPreset.matching(gainDbi: gainDbi) == nil {
                        Text("Custom").tag("custom")
                    }
                }
            } label: {
                HStack(alignment: .firstTextBaseline) {
                    Text(CoverageAntennaPreset.matching(gainDbi: gainDbi)?.title ?? String(localized: "Custom"))
                        .fixedSize(horizontal: false, vertical: true)
                        .multilineTextAlignment(.leading)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.up.chevron.down")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityLabel("Antenna")
            .accessibilityValue(CoverageAntennaPreset.matching(gainDbi: gainDbi)?.title ?? String(localized: "Custom"))
        }
        CoverageNumericSetting(title: "Antenna gain", term: nil, value: $gainDbi, unit: "dBi", issue: nil)
    }

    private var selection: Binding<String> {
        Binding(
            get: { CoverageAntennaPreset.matching(gainDbi: gainDbi)?.id ?? "custom" },
            set: { id in
                if let preset = CoverageAntennaPreset.all.first(where: { $0.id == id }) { gainDbi = preset.gainDbi }
            }
        )
    }
}

private struct CoverageNumericSetting: View {
    let title: LocalizedStringKey
    let term: GlossaryTerm?
    @Binding var value: Double
    let unit: String
    let issue: CoverageSetupIssue?

    var body: some View {
        LabeledContent {
            HStack(spacing: 4) {
                TextField(title, value: $value, format: .number.precision(.fractionLength(0...3)))
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.numbersAndPunctuation)
                    .font(.monaspace(.body))
                Text(unit)
                    .foregroundStyle(.secondary)
            }
        } label: {
            if let term {
                LearnLabel(title, term: term)
            } else {
                Text(title)
            }
        }
        CoverageIssueText(issue: issue)
    }
}

/// Choices stack when their full labels cannot fit across the available card width.
private struct CoveragePresetChips: View {
    let titles: [String]
    let selected: Int?
    let choose: (Int) -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                CoveragePresetButtons(titles: titles, selected: selected, choose: choose)
            }
            .fixedSize(horizontal: true, vertical: false)
            VStack(alignment: .leading, spacing: 8) {
                CoveragePresetButtons(titles: titles, selected: selected, choose: choose)
            }
        }
        .font(.subheadline)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct CoveragePresetButtons: View {
    let titles: [String]
    let selected: Int?
    let choose: (Int) -> Void

    var body: some View {
        ForEach(Array(titles.enumerated()), id: \.offset) { index, title in
            Button { choose(index) } label: {
                Text(title)
                    .fixedSize(horizontal: false, vertical: true)
                    .multilineTextAlignment(.leading)
            }
            .buttonStyle(.bordered)
            .tint(selected == index ? Color.accentColor : Color.secondary)
            .accessibilityAddTraits(selected == index ? .isSelected : [])
        }
    }
}

private struct CoverageIssueText: View {
    let issue: CoverageSetupIssue?

    var body: some View {
        if let issue, issue != .required {
            Text(message(issue))
                .font(.caption)
                .foregroundStyle(.red)
        }
    }

    private func message(_ issue: CoverageSetupIssue) -> LocalizedStringKey {
        switch issue {
        case .required: "Enter a value."
        case .number: "Enter a finite number."
        case .latitude: "Coverage calculations support latitude from −85° to 85°."
        case .longitude: "Longitude must be between −180° and 180°."
        case .frequency: "Frequency must be between 30 and 6000 MHz."
        case .height: "Antenna height must be between 1 and 3000 m above ground (about 3 to 9843 ft)."
        case .nonnegative: "Enter zero or a positive value."
        case .unknownField: "This field is not supported."
        }
    }
}

/// Requests location only after a tap; edits or navigation cancel pending delivery.
private struct CoverageCurrentLocationButton: View {
    let model: EstimatedCoverageModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var manager = MapLocationManager()
    @State private var locating = false
    @State private var message: LocalizedStringResource?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Button("Use Current Location", systemImage: "location") {
                    locating = true
                    message = nil
                    manager.requestCurrentLocation { coordinate in
                        locating = false
                        guard CoverageSetupValidation.supportsCoordinate(latitude: coordinate.latitude, longitude: coordinate.longitude) else {
                            message = "Your location is outside the supported latitude range of −85° to 85°. Choose a point on the map."
                            return
                        }
                        model.place(coordinate, name: String(localized: "Current location"))
                        message = "Check the pin on the map, especially when using approximate location."
                    } failure: {
                        locating = false
                        message = "Couldn't get your location. Check location permission and services in Settings, or choose a point on the map."
                    }
                }
                .buttonStyle(.bordered)
                .disabled(locating)
                if locating {
                    ProgressView()
                    Button("Cancel") { cancel() }
                        .buttonStyle(.bordered)
                }
            }
            if let message {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .onChange(of: model.coordinateSignature) { cancel() }
        .onChange(of: scenePhase) { _, phase in if phase == .background { cancel() } }
        .onDisappear { cancel() }
    }

    private func cancel() {
        manager.cancelRequest()
        locating = false
    }
}

// MARK: - Known nodes

private struct CoverageKnownNodesSheet: View {
    let model: EstimatedCoverageModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("This list shows the nearest 200 located nodes available in the current catalog. Predictions use your receiver assumptions, not reported node equipment. It is not a complete inventory of nearby radios.")
                        .font(.subheadline).foregroundStyle(.secondary)
                }.instrumentListRows()
                Section {
                    if model.knownNodes.isEmpty {
                        Text(model.coordinate == nil ? "Place a transmitter to find nearby known nodes." : "No located nodes are available in the current catalog.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    let statuses = model.nodeStatuses
                    ForEach(model.knownNodes) { node in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(node.name ?? String(node.id.prefix(12))).font(.monaspace(.headline))
                            Text(statuses[node.id]?.coverageLabel ?? String(localized: "Not evaluated")).font(.subheadline)
                            if let margin = statuses[node.id]?.adjustedMarginDb {
                                Text("Margin after reserve: \(margin, specifier: "%.1f") dB").font(.monaspace(.caption))
                                if let result = model.activeResult {
                                    Text("Predicted receive level: \(margin + result.budget.sensitivityDbm + result.budget.reserveDb, specifier: "%.1f") dBm")
                                        .font(.monaspace(.caption))
                                    Text("Receiver sensitivity: \(result.budget.sensitivityDbm, specifier: "%.1f") dBm · Reserve: \(result.budget.reserveDb, specifier: "%.1f") dB")
                                        .font(.monaspace(.caption2)).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }.instrumentListRows()
            }
            .meshPageBackground()
            .navigationTitle("Known nodes")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
