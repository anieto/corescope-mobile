import MapKit
import Observation
import SwiftUI

enum FieldToolsActivity: String, CaseIterable, Identifiable {
    case nearby
    case plan

    var id: String { rawValue }

    var title: LocalizedStringResource {
        switch self {
        case .nearby: "Nearby"
        case .plan: "Plan"
        }
    }

    var symbol: String {
        switch self {
        case .nearby: "location.viewfinder"
        case .plan: "antenna.radiowaves.left.and.right"
        }
    }
}

enum FieldCatalogItem: Identifiable, Hashable {
    case node(MeshNode)
    case observer(MeshObserver)

    var id: String {
        switch self {
        case .node(let node): "node:\(node.publicKey.lowercased())"
        case .observer(let observer): "observer:\(observer.id)"
        }
    }

    var name: String {
        switch self {
        case .node(let node): node.name.flatMap { $0.isEmpty ? nil : $0 } ?? String(node.publicKey.prefix(12))
        case .observer(let observer): observer.name.flatMap { $0.isEmpty ? nil : $0 } ?? observer.id
        }
    }

    var subtitle: String {
        switch self {
        case .node(let node): node.role.capitalized
        case .observer: String(localized: "Observer")
        }
    }

    var symbol: String {
        switch self {
        case .node: "dot.radiowaves.left.and.right"
        case .observer: "antenna.radiowaves.left.and.right"
        }
    }

    var coordinate: FieldCoordinate? {
        switch self {
        case .node(let node): FieldCoordinate.analyzerCoordinate(latitude: node.lat, longitude: node.lon)
        case .observer(let observer):
            FieldCoordinate.analyzerCoordinate(latitude: observer.lat, longitude: observer.lon)
        }
    }
}

enum FieldCatalogFilter: String, CaseIterable, Identifiable {
    case all
    case nodes
    case observers

    var id: String { rawValue }

    var title: LocalizedStringResource {
        switch self {
        case .all: "All"
        case .nodes: "Nodes"
        case .observers: "Observers"
        }
    }
}

struct FieldGuidanceLinkCard: View {
    let entityName: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "location.north.circle.fill")
                .font(.monaspace(.title2))
                .foregroundStyle(NodeScopeStyle.signal)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text("Guide to \(entityName)")
                    .font(.monaspace(.headline))
                    .foregroundStyle(.primary)
                Text("See distance, direction, and recent network evidence")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .padding(16)
        .instrumentCard()
        .contentShape(Rectangle())
    }
}

struct FieldTargetEvidence: Equatable {
    enum FactKind: String, Equatable {
        case advertisements
        case observerStatus
        case packetsLastHour
        case noiseFloor
    }

    struct Fact: Equatable, Identifiable {
        let kind: FactKind
        let value: String

        var id: FactKind { kind }
    }

    let activityAt: Date?
    let catalogFetchedAt: Date?
    let sourceHost: String
    let facts: [Fact]

    init(item: FieldCatalogItem, catalogFetchedAt: Date?, sourceHost: String) {
        self.catalogFetchedAt = catalogFetchedAt
        self.sourceHost = sourceHost
        switch item {
        case .node(let node):
            activityAt = node.latestActivity
            facts = node.advertCount.map {
                [Fact(kind: .advertisements, value: $0.formatted())]
            } ?? []
        case .observer(let observer):
            activityAt = observer.lastSeen
            facts = [
                observer.online.map {
                    Fact(kind: .observerStatus, value: String(localized: $0 ? "Online" : "Offline"))
                },
                observer.packetsLastHour.map {
                    Fact(kind: .packetsLastHour, value: $0.formatted())
                },
                observer.noiseFloor.map {
                    Fact(
                        kind: .noiseFloor,
                        value: "\($0.formatted(.number.precision(.fractionLength(0)))) dB"
                    )
                }
            ].compactMap { $0 }
        }
    }
}

@MainActor
@Observable
final class FieldToolsViewModel {
    var nodes: [MeshNode] = [] { didSet { recomputeVisibleItems() } }
    var observers: [MeshObserver] = [] { didSet { recomputeVisibleItems() } }
    var searchText = "" { didSet { recomputeVisibleItems() } }
    var filter = FieldCatalogFilter.all { didSet { recomputeVisibleItems() } }
    var reference: FieldCoordinate? { didSet { recomputeVisibleItems() } }
    private(set) var visibleItems: [FieldCatalogItem] = []
    var isLoading = false
    var errorMessage: String?
    var lastUpdatedAt: Date?
    private(set) var targetEvidence: FieldEvidenceValue?
    private(set) var targetEvidenceItemID: String?
    private(set) var isLoadingTargetEvidence = false
    private(set) var targetEvidenceError: String?
    private(set) var capabilities: Set<BackendCapability> = []

    private var backend: (any AnalyzerBackend)?
    private var loadID = UUID()
    private var evidenceLoadID = UUID()

    init(reference: FieldCoordinate?) {
        self.reference = reference
    }

    private var locatedItems: [FieldCatalogItem] {
        let nodeItems = nodes.map(FieldCatalogItem.node)
        let observerItems = observers.map(FieldCatalogItem.observer)
        return (nodeItems + observerItems)
            .filter { $0.coordinate != nil }
    }

    func configure(settings: AnalyzerSettings) {
        let next = AnalyzerBackendFactory.make(settings: settings)
        if backend?.sourceSession.id != next.sourceSession.id {
            loadID = UUID()
            nodes = []
            observers = []
            errorMessage = nil
            lastUpdatedAt = nil
            clearTargetEvidence()
        }
        backend = next
        capabilities = next.capabilities
    }

    func loadEvidence(for item: FieldCatalogItem, forceRefresh: Bool = false) async {
        guard let backend, backend.sourceSession.isCurrent else { return }
        let currentLoadID = UUID()
        evidenceLoadID = currentLoadID
        if targetEvidenceItemID != item.id {
            targetEvidence = nil
            targetEvidenceError = nil
        }
        targetEvidenceItemID = item.id
        isLoadingTargetEvidence = targetEvidence == nil

        do {
            let value = try await FieldEvidenceRepository.shared.load(
                item: item,
                from: backend,
                forceRefresh: forceRefresh
            )
            guard evidenceLoadID == currentLoadID,
                  backend.sourceSession.isCurrent,
                  targetEvidenceItemID == item.id,
                  !Task.isCancelled else { return }
            targetEvidence = value
            targetEvidenceError = fallbackMessage(value.fallbackReason)
            isLoadingTargetEvidence = false
        } catch is CancellationError {
            if evidenceLoadID == currentLoadID { isLoadingTargetEvidence = false }
        } catch {
            guard evidenceLoadID == currentLoadID, targetEvidenceItemID == item.id else { return }
            targetEvidenceError = error.localizedDescription
            isLoadingTargetEvidence = false
        }
    }

    func clearTargetEvidence() {
        evidenceLoadID = UUID()
        targetEvidence = nil
        targetEvidenceItemID = nil
        targetEvidenceError = nil
        isLoadingTargetEvidence = false
    }

    private func fallbackMessage(_ reason: FieldEvidenceValue.FallbackReason?) -> String? {
        switch reason {
        case .none: nil
        case .refreshFailed: String(localized: "Refresh failed. Showing saved target evidence.")
        case .rateLimited(let until):
            if let until {
                String(localized: "The analyzer is rate limiting requests until \(until.formatted(date: .omitted, time: .standard)). Showing saved target evidence.")
            } else {
                String(localized: "The analyzer is rate limiting requests. Showing saved target evidence.")
            }
        }
    }

    func load(region: String?, forceRefresh: Bool = false) async {
        guard let backend, backend.sourceSession.isCurrent else { return }
        let currentLoadID = UUID()
        loadID = currentLoadID
        isLoading = nodes.isEmpty && observers.isEmpty
        defer {
            if loadID == currentLoadID { isLoading = false }
        }

        let cachedNodes = await FieldCatalogRepository.shared.cachedNodes(for: backend, region: region)
        let cachedObservers = await FieldCatalogRepository.shared.cachedObservers(for: backend)
        guard isCurrent(currentLoadID, backend: backend) else { return }
        if let cachedNodes {
            nodes = cachedNodes.value.nodes
            lastUpdatedAt = cachedNodes.savedAt
        }
        if let cachedObservers {
            observers = Self.filteredObservers(cachedObservers.value.observers, region: region)
            lastUpdatedAt = [lastUpdatedAt, cachedObservers.savedAt].compactMap { $0 }.max()
        }

        do {
            async let refreshedNodes: FieldCatalogValue<NodesResponse>? =
                refreshNodesIfNeeded(backend: backend, region: region, force: forceRefresh)
            async let refreshedObservers: FieldCatalogValue<ObserversResponse>? =
                refreshObserversIfNeeded(backend: backend, force: forceRefresh)
            let (nodeCatalog, observerCatalog) = try await (refreshedNodes, refreshedObservers)
            guard isCurrent(currentLoadID, backend: backend) else { return }
            if let nodeCatalog {
                nodes = nodeCatalog.value.nodes
                lastUpdatedAt = nodeCatalog.savedAt
            }
            if let observerCatalog {
                observers = Self.filteredObservers(observerCatalog.value.observers, region: region)
                lastUpdatedAt = [lastUpdatedAt, observerCatalog.savedAt].compactMap { $0 }.max()
            }
            errorMessage = nil
        } catch {
            guard isCurrent(currentLoadID, backend: backend), !(error is CancellationError) else { return }
            errorMessage = error.localizedDescription
        }
    }

    private func refreshNodesIfNeeded(
        backend: any AnalyzerBackend,
        region: String?,
        force: Bool
    ) async throws -> FieldCatalogValue<NodesResponse>? {
        guard await FieldCatalogRepository.shared.nodesNeedRefresh(
            for: backend, region: region, force: force
        ) else { return nil }
        return try await FieldCatalogRepository.shared.refreshNodes(for: backend, region: region)
    }

    private func refreshObserversIfNeeded(
        backend: any AnalyzerBackend,
        force: Bool
    ) async throws -> FieldCatalogValue<ObserversResponse>? {
        guard await FieldCatalogRepository.shared.observersNeedRefresh(
            for: backend, force: force
        ) else { return nil }
        return try await FieldCatalogRepository.shared.refreshObservers(for: backend)
    }

    private func isCurrent(_ id: UUID, backend: any AnalyzerBackend) -> Bool {
        loadID == id && backend.sourceSession.isCurrent && !Task.isCancelled
    }

    private static func filteredObservers(_ observers: [MeshObserver], region: String?) -> [MeshObserver] {
        let selected = Set(region?.split(separator: ",").map {
            String($0).trimmingCharacters(in: .whitespaces).uppercased()
        } ?? [])
        guard !selected.isEmpty else { return observers }
        return observers.filter { observer in
            observer.iata.map { selected.contains($0.uppercased()) } == true
        }
    }

    static func mapItems(
        from items: [FieldCatalogItem],
        visibleMapRect: MKMapRect?,
        limit: Int,
        retainingIDs: Set<String>
    ) -> [FieldCatalogItem] {
        let candidates = if let visibleMapRect {
            items.filter { item in
                item.coordinate.map {
                    visibleMapRect.contains(MKMapPoint($0.clLocationCoordinate))
                } == true
            }
        } else {
            items
        }

        var result = Array(candidates.prefix(limit))
        let includedIDs = Set(result.map(\.id))
        result.append(contentsOf: items.filter { item in
            retainingIDs.contains(item.id) && !includedIDs.contains(item.id)
        })
        return result
    }

    private func recomputeVisibleItems() {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let items = locatedItems
        let distancesByID: [String: Double]
        if let reference {
            distancesByID = Dictionary(uniqueKeysWithValues: items.compactMap { item in
                item.coordinate.map {
                    (item.id, FieldGeometry.geometry(from: reference, to: $0).distanceMeters)
                }
            })
        } else {
            distancesByID = [:]
        }
        visibleItems = items
            .filter { item in
                switch (filter, item) {
                case (.all, _), (.nodes, .node), (.observers, .observer): true
                default: false
                }
            }
            .filter { item in
                query.isEmpty || item.name.localizedCaseInsensitiveContains(query)
                    || item.subtitle.localizedCaseInsensitiveContains(query)
            }
            .sorted { lhs, rhs in
                if let lhsDistance = distancesByID[lhs.id], let rhsDistance = distancesByID[rhs.id] {
                    if lhsDistance != rhsDistance { return lhsDistance < rhsDistance }
                }
                let nameOrder = lhs.name.localizedStandardCompare(rhs.name)
                return nameOrder == .orderedSame ? lhs.id < rhs.id : nameOrder == .orderedAscending
            }
    }
}

struct FieldToolsScreen: View {
    private static let maximumMapAnnotations = 150

    let initialReference: FieldCoordinate?
    let closeAction: (() -> Void)?

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityVoiceOverEnabled) private var accessibilityVoiceOverEnabled
    @State private var model: FieldToolsViewModel
    @State private var locationSession = FieldLocationSession()
    @State private var activity = FieldToolsActivity.nearby
    @State private var selectedItem: FieldCatalogItem?
    @State private var pinnedItem: FieldCatalogItem?
    @State private var preferredCompactColumn = NavigationSplitViewColumn.sidebar
    @State private var position: MapCameraPosition = .automatic
    @State private var usesDeviceReference = false
    @State private var showsLocationExplanation = false
    @State private var showsCoordinateEntry = false
    @State private var activeSourceSessionID: UUID?
    @State private var showsNearbySheet = false
    @State private var showsTargetDetails = false
    @State private var displayedMapItems: [FieldCatalogItem] = []
    @State private var visibleMapRect: MKMapRect?
    @State private var lastFramedReference: FieldCoordinate?

    init(
        initialReference: FieldCoordinate? = nil,
        initialNode: MeshNode? = nil,
        initialObserver: MeshObserver? = nil,
        closeAction: (() -> Void)? = nil
    ) {
        self.initialReference = initialReference
        self.closeAction = closeAction
        _model = State(initialValue: FieldToolsViewModel(reference: initialReference))
        let initialItem = initialNode.map(FieldCatalogItem.node) ?? initialObserver.map(FieldCatalogItem.observer)
        _selectedItem = State(initialValue: initialItem)
        _pinnedItem = State(initialValue: initialItem)
        // Without a starting point there is nothing else to frame, so open on the target itself
        // instead of letting the map fall back to a whole-continent view.
        if let coordinate = initialItem?.coordinate {
            if let initialReference {
                _position = State(initialValue: .rect(Self.guidanceRect(from: initialReference, to: coordinate)))
                _lastFramedReference = State(initialValue: initialReference)
            } else {
                _position = State(initialValue: .region(MKCoordinateRegion(
                    center: coordinate.clLocationCoordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.03, longitudeDelta: 0.03)
                )))
            }
        }
    }

    var body: some View {
        NavigationStack {
            canvas
        }
        .navigationTitle("Field Tools")
        .task(id: "\(settings.sourceSession.id)|\(regionFilter.selectedRegion ?? "")") {
            if let activeSourceSessionID, activeSourceSessionID != settings.sourceSession.id {
                selectedItem = nil
                pinnedItem = nil
                model.clearTargetEvidence()
            }
            activeSourceSessionID = settings.sourceSession.id
            model.configure(settings: settings)
            await model.load(region: regionFilter.selectedRegion)
        }
        .task(id: "\(settings.sourceSession.id)|\(pinnedItem?.id ?? "none")") {
            model.configure(settings: settings)
            guard let pinnedItem else {
                model.clearTargetEvidence()
                return
            }
            await model.loadEvidence(for: pinnedItem)
        }
        .onChange(of: locationSession.sample) { _, sample in
            guard usesDeviceReference, let sample else { return }
            model.reference = sample.coordinate
            if let target = pinnedItem?.coordinate {
                // Reframe only after a real move, so GPS jitter doesn't keep nudging the map.
                if let lastFramedReference,
                   FieldGeometry.geometry(from: lastFramedReference, to: sample.coordinate).distanceMeters < 40 {
                    return
                }
                frameGuidance(from: sample.coordinate, to: target)
            } else {
                position = .region(MKCoordinateRegion(
                    center: sample.coordinate.clLocationCoordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
                ))
            }
        }
        .onChange(of: model.visibleItems) { _, items in
            if let pinnedItem,
               let refreshed = items.first(where: { $0.id == pinnedItem.id }) {
                self.pinnedItem = refreshed
                if selectedItem?.id == refreshed.id { selectedItem = refreshed }
            }
            updateDisplayedMapItems(from: items)
        }
        .onChange(of: selectedItem?.id) {
            updateDisplayedMapItems(from: model.visibleItems)
        }
        .onChange(of: pinnedItem?.id) {
            updateDisplayedMapItems(from: model.visibleItems)
            if let reference = model.reference, let target = pinnedItem?.coordinate {
                frameGuidance(from: reference, to: target)
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active, usesDeviceReference {
                locationSession.start()
            } else if phase != .active {
                locationSession.stop()
            }
        }
        .onDisappear {
            locationSession.stop()
        }
        .alert("Use Your Location?", isPresented: $showsLocationExplanation) {
            Button("Not Now", role: .cancel) {}
            Button("Continue") {
                usesDeviceReference = true
                locationSession.start()
            }
        } message: {
            Text("Field Tools uses your location only while this workspace is open and active. It does not record a trail or run in the background.")
        }
        .sheet(isPresented: $showsCoordinateEntry) {
            FieldCoordinateEntrySheet(initialCoordinate: usesDeviceReference ? nil : model.reference) { coordinate in
                useManualReference(coordinate)
            }
        }
        .sheet(isPresented: $showsNearbySheet) {
            NavigationStack {
                toolsPanel
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showsNearbySheet = false }
                        }
                    }
            }
        }
        .sheet(isPresented: $showsTargetDetails) {
            NavigationStack {
                ScrollView {
                    if let target = pinnedItem ?? selectedItem {
                        VStack(spacing: 12) {
                            FieldTargetEvidenceCard(
                                evidence: FieldTargetEvidence(
                                    item: target,
                                    catalogFetchedAt: model.lastUpdatedAt,
                                    sourceHost: settings.host
                                ),
                                analytics: model.targetEvidenceItemID == target.id
                                    ? model.targetEvidence : nil,
                                isLoadingAnalytics: model.targetEvidenceItemID == target.id
                                    && model.isLoadingTargetEvidence,
                                analyticsError: model.targetEvidenceItemID == target.id
                                    ? model.targetEvidenceError : nil
                            )
                            FieldTargetQuickActions(
                                item: target,
                                capabilities: model.capabilities,
                                analytics: model.targetEvidenceItemID == target.id
                                    ? model.targetEvidence?.evidence : nil
                            )
                        }
                        .padding()
                    }
                }
                .meshPageBackground()
                .navigationTitle("Target Details")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { showsTargetDetails = false }
                    }
                }
            }
        }
    }

    private var toolsPanel: some View {
        List {
            contextSection
            nearbySection
            planSection
            scopeSection
        }
        .navigationTitle("Field Tools")
        .adaptiveSidebarListStyle(meshBackground: true)
        .refreshable {
            await model.load(region: regionFilter.selectedRegion, forceRefresh: true)
            if let pinnedItem {
                await model.loadEvidence(for: pinnedItem, forceRefresh: true)
            }
        }
    }

    @ViewBuilder
    private var contextSection: some View {
        Section("Starting Point") {
            if model.reference == nil {
                Button {
                    showCanvas()
                } label: {
                    Label("Choose on Map", systemImage: "mappin.and.ellipse")
                }
            } else {
                Label(referenceDescription, systemImage: usesDeviceReference ? "location.fill" : "location")
                    .foregroundStyle(NodeScopeStyle.signal)
                Button("Change Starting Point", role: .destructive) {
                    model.reference = nil
                    usesDeviceReference = false
                    locationSession.stop()
                }
            }

            Button {
                showsLocationExplanation = true
            } label: {
                Label("Use My Location", systemImage: "location.circle")
            }

            Button {
                showsCoordinateEntry = true
            } label: {
                Label("Enter Coordinates", systemImage: "number")
            }

            if usesDeviceReference {
                FieldLocationStatusLabel(status: locationSession.status)
                if let sample = locationSession.sample {
                    Text("Location updated \(sample.timestamp, format: .relative(presentation: .named))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var scopeSection: some View {
        Section("Analyzer Scope") {
            LabeledContent("Analyzer", value: settings.host)
            LabeledContent("Region", value: regionFilter.selectionTitle ?? String(localized: "All regions"))
        }
    }

    @ViewBuilder
    private var nearbySection: some View {
        Section {
            FieldNearbyControls(model: model)

            if model.isLoading && model.visibleItems.isEmpty {
                HStack {
                    Spacer()
                    LoadingIndicator(title: "Loading nearby equipment")
                    Spacer()
                }
                .listRowBackground(Color.clear)
            } else if model.visibleItems.isEmpty {
                ContentUnavailableView {
                    Label("No Located Equipment", systemImage: "location.slash")
                } description: {
                    Text("This analyzer has no nodes or observers with usable reported coordinates.")
                }
                .listRowBackground(Color.clear)
            } else {
                ForEach(model.visibleItems) { item in
                    Button {
                        selectedItem = item
                        pinnedItem = item
                        if let coordinate = item.coordinate {
                            position = .region(MKCoordinateRegion(
                                center: coordinate.clLocationCoordinate,
                                span: MKCoordinateSpan(latitudeDelta: 0.2, longitudeDelta: 0.2)
                            ))
                        }
                        showCanvas()
                    } label: {
                        FieldCatalogRow(
                            item: item,
                            geometry: geometry(for: item),
                            isSelected: item.id == selectedItem?.id
                        )
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                }
            }
        } header: {
            Text("Nearby Equipment")
        } footer: {
            if let lastUpdatedAt = model.lastUpdatedAt {
                Text("Analyzer catalog updated \(lastUpdatedAt, format: .relative(presentation: .named))")
            }
        }

        if let errorMessage = model.errorMessage {
            Section {
                Label(errorMessage, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(NodeScopeStyle.activity)
            }
        }
    }

    private var planSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                Label("Deployment Planning", systemImage: "antenna.radiowaves.left.and.right")
                    .font(.monaspace(.headline))
                Text("Coverage modeling and proposed-site comparisons are still being built.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Label("Coming Soon", systemImage: "hammer")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            .listRowBackground(Color.clear)
        }
    }

    private var canvas: some View {
        ZStack(alignment: .top) {
            MapReader { proxy in
                Map(position: $position) {
                    if let reference = model.reference {
                        Annotation("Reference", coordinate: reference.clLocationCoordinate) {
                            FieldMapMarker(symbol: "scope", color: NodeScopeStyle.activity)
                        }
                    }

                    if let reference = model.reference,
                       let pinnedItem,
                       let target = pinnedItem.coordinate {
                        MapPolyline(coordinates: [reference.clLocationCoordinate, target.clLocationCoordinate])
                            .stroke(NodeScopeStyle.activity, style: StrokeStyle(lineWidth: 3, dash: [7, 5]))
                    }

                    // Always show the chosen target: before a starting point is set the other markers
                    // are hidden, and a target missing from the current catalog wouldn't be drawn.
                    if let target = pinnedItem ?? selectedItem,
                       model.reference == nil || accessibilityVoiceOverEnabled || !displayedMapItems.contains(target),
                       let coordinate = target.coordinate {
                        Annotation(target.name, coordinate: coordinate.clLocationCoordinate) {
                            FieldMapMarker(symbol: target.symbol, color: NodeScopeStyle.activity)
                        }
                    }

                    if model.reference != nil && !accessibilityVoiceOverEnabled {
                        ForEach(displayedMapItems) { item in
                            if let coordinate = item.coordinate {
                                Annotation(item.name, coordinate: coordinate.clLocationCoordinate) {
                                    Button {
                                        selectedItem = item
                                    } label: {
                                        FieldMapMarker(
                                            symbol: item.symbol,
                                            color: item.id == pinnedItem?.id || item.id == selectedItem?.id
                                                ? NodeScopeStyle.activity : NodeScopeStyle.signal
                                        )
                                    }
                                    .buttonStyle(.plain)
                                    .frame(minWidth: 44, minHeight: 44)
                                    .accessibilityLabel(item.name)
                                }
                            }
                        }
                    }
                }
                .mapStyle(.standard(elevation: .realistic))
                .accessibilityHidden(true)
                .onMapCameraChange(frequency: .onEnd) { context in
                    visibleMapRect = context.rect
                    updateDisplayedMapItems(from: model.visibleItems)
                }
                .onTapGesture { point in
                    guard activity == .nearby,
                          let coordinate = proxy.convert(point, from: .local),
                          let fieldCoordinate = FieldCoordinate(
                            latitude: coordinate.latitude,
                            longitude: coordinate.longitude
                          ) else { return }
                    useManualReference(fieldCoordinate)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(horizontalSizeClass == .compact
                        ? String(localized: "Field Map")
                        : selectedItem?.name ?? String(localized: "Field Map"))
                        .font(.monaspace(.headline))
                        .padding(.horizontal, 14)
                        .frame(minHeight: 44)
                        .nodeScopeFloatingGlass(cornerRadius: 18)

                    Spacer()

                    if let closeAction {
                        Button(action: closeAction) {
                            Image(systemName: "xmark")
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain)
                        .nodeScopeFloatingGlass(cornerRadius: 18)
                        .accessibilityLabel("Close Field Tools")
                    }
                }

                if horizontalSizeClass == .compact {
                    Button {
                        showsNearbySheet = true
                    } label: {
                        Label("Nearby", systemImage: "list.bullet")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 12)
                            .frame(minHeight: 44)
                            .nodeScopeFloatingGlass(cornerRadius: 16)
                    }
                    .buttonStyle(.plain)
                } else {
                    FieldCanvasHeader(
                        activity: activity,
                        referenceIsSet: model.reference != nil,
                        selectedName: selectedItem?.name
                    )
                }

                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.top, 32)
            .padding(.bottom, 12)

            if let target = pinnedItem ?? selectedItem {
                VStack {
                    Spacer()
                    FieldGuidancePanel(
                        name: target.name,
                        subtitle: target.subtitle,
                        geometry: geometry(for: target),
                        headingSample: usesDeviceReference && pinnedItem?.id == target.id
                            ? locationSession.headingSample
                            : nil,
                        headingStatus: usesDeviceReference && pinnedItem?.id == target.id
                            ? locationSession.headingStatus
                            : .idle,
                        isPinned: pinnedItem?.id == target.id,
                        pin: { pinnedItem = target },
                        unpin: { pinnedItem = nil },
                        useMyLocation: { showsLocationExplanation = true },
                        enterCoordinates: { showsCoordinateEntry = true },
                        showDetails: { showsTargetDetails = true }
                    )
                    .padding(12)
                    .padding(.bottom, 24)
                }
            } else {
                VStack {
                    Spacer()
                    FieldGettingStartedCard(
                        hasStartingPoint: model.reference != nil,
                        nearbyCount: model.visibleItems.count,
                        useMyLocation: { showsLocationExplanation = true },
                        enterCoordinates: { showsCoordinateEntry = true },
                        showNearby: { showsNearbySheet = true }
                    )
                    .padding(12)
                    .padding(.bottom, 24)
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
    }

    private func geometry(for item: FieldCatalogItem) -> FieldGeometryResult? {
        guard let reference = model.reference, let coordinate = item.coordinate else { return nil }
        return FieldGeometry.geometry(from: reference, to: coordinate)
    }

    private var referenceDescription: String {
        guard usesDeviceReference, let sample = locationSession.sample else {
            return String(localized: "Manual reference")
        }
        let accuracy = Measurement(value: sample.horizontalAccuracyMeters, unit: UnitLength.meters)
            .formatted(.measurement(width: .abbreviated))
        return String(localized: "Device location · ±\(accuracy)")
    }

    private func useManualReference(_ coordinate: FieldCoordinate) {
        locationSession.stop()
        usesDeviceReference = false
        model.reference = coordinate
        if let target = pinnedItem?.coordinate {
            frameGuidance(from: coordinate, to: target)
        } else {
            position = .region(MKCoordinateRegion(
                center: coordinate.clLocationCoordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.2, longitudeDelta: 0.2)
            ))
        }
    }

    /// Fits the starting point and the target on screen, leaving room for the controls at the top
    /// and most of all for the guidance panel at the bottom.
    private func frameGuidance(from reference: FieldCoordinate, to target: FieldCoordinate) {
        lastFramedReference = reference
        withAnimation(.easeInOut(duration: 0.4)) {
            position = .rect(Self.guidanceRect(from: reference, to: target))
        }
    }

    private static func guidanceRect(from reference: FieldCoordinate, to target: FieldCoordinate) -> MKMapRect {
        let start = MKMapPoint(reference.clLocationCoordinate)
        let end = MKMapPoint(target.clLocationCoordinate)
        var rect = MKMapRect(
            x: min(start.x, end.x),
            y: min(start.y, end.y),
            width: abs(start.x - end.x),
            height: abs(start.y - end.y)
        )
        let minimumSide = 800 * MKMapPointsPerMeterAtLatitude(reference.latitude)
        rect = rect.insetBy(
            dx: -max(0, (minimumSide - rect.width) / 2),
            dy: -max(0, (minimumSide - rect.height) / 2)
        )
        let width = rect.width
        let height = rect.height
        return MKMapRect(
            x: rect.minX - width * 0.2,
            y: rect.minY - height * 0.4,
            width: width * 1.4,
            height: height * 2.4
        )
    }

    private func showCanvas() {
        if horizontalSizeClass == .compact {
            showsNearbySheet = false
        } else {
            preferredCompactColumn = .detail
        }
    }

    private func updateDisplayedMapItems(from items: [FieldCatalogItem]) {
        let retainedIDs = Set([selectedItem?.id, pinnedItem?.id].compactMap { $0 })
        let nextItems = FieldToolsViewModel.mapItems(
            from: items,
            visibleMapRect: visibleMapRect,
            limit: Self.maximumMapAnnotations,
            retainingIDs: retainedIDs
        )

        if displayedMapItems != nextItems {
            displayedMapItems = nextItems
        }
    }
}

private struct FieldNearbyControls: View {
    @Bindable var model: FieldToolsViewModel

    var body: some View {
        VStack(spacing: 10) {
            TextField("Search located equipment", text: $model.searchText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.vertical, 11)

            HStack(spacing: 4) {
                ForEach(FieldCatalogFilter.allCases) { filter in
                    Button {
                        model.filter = filter
                    } label: {
                        Text(filter.title)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .foregroundStyle(model.filter == filter ? NodeScopeStyle.deepNavy : .primary)
                            .background(
                                model.filter == filter ? NodeScopeStyle.signal : Color.clear,
                                in: RoundedRectangle(cornerRadius: 9)
                            )
                    }
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(filter.title)
                    .accessibilityAddTraits(model.filter == filter ? .isSelected : [])
                }
            }
            .padding(3)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
        }
        .padding(.vertical, 4)
    }
}

private struct FieldGettingStartedCard: View {
    let hasStartingPoint: Bool
    let nearbyCount: Int
    let useMyLocation: () -> Void
    let enterCoordinates: () -> Void
    let showNearby: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(
                hasStartingPoint ? "Choose Nearby Equipment" : "Choose Your Starting Point",
                systemImage: hasStartingPoint ? "dot.radiowaves.left.and.right" : "location.circle"
            )
            .font(.monaspace(.headline))

            Text(hasStartingPoint
                ? "Select a node or observer to see its distance and direction."
                : "Use your location, tap anywhere on the map, or enter coordinates.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if hasStartingPoint {
                Button(action: showNearby) {
                    Label("View \(nearbyCount) Nearby", systemImage: "list.bullet")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            } else {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { startingPointActions }
                    VStack(spacing: 8) { startingPointActions }
                }
                Label("Or tap the map to choose another place", systemImage: "hand.tap")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .instrumentCard()
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var startingPointActions: some View {
        Button(action: useMyLocation) {
            Label("Use My Location", systemImage: "location.fill")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)

        Button(action: enterCoordinates) {
            Label("Enter Coordinates", systemImage: "number")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
    }
}

/// The bottom panel for a chosen node or observer: its name, then the direction dial and distance
/// once a starting point is set, or the ways to set one before that.
private struct FieldGuidancePanel: View {
    let name: String
    let subtitle: String
    let geometry: FieldGeometryResult?
    let headingSample: FieldHeadingSample?
    let headingStatus: FieldHeadingStatus
    let isPinned: Bool
    let pin: () -> Void
    let unpin: () -> Void
    let useMyLocation: () -> Void
    let enterCoordinates: () -> Void
    let showDetails: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(.monaspace(.headline))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if isPinned {
                    Button("Stop", systemImage: "xmark", action: unpin)
                        .font(.subheadline.weight(.semibold))
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .accessibilityLabel("Stop Guidance")
                }
            }

            if let geometry {
                HStack(spacing: 16) {
                    FieldDirectionDial(
                        trueBearingDegrees: geometry.initialBearingDegrees,
                        headingSample: headingSample,
                        status: headingStatus
                    )
                    VStack(alignment: .leading, spacing: 4) {
                        Text(Measurement(value: geometry.distanceMeters, unit: UnitLength.meters),
                             format: .measurement(width: .abbreviated, usage: .road))
                            .font(.monaspace(.title).weight(.bold))
                            .minimumScaleFactor(0.7)
                            .lineLimit(1)
                        Text(bearingLine(for: geometry))
                            .font(.monaspace(.footnote))
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Choose where you're starting from to see distance and direction.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) { startingPointActions }
                        VStack(spacing: 8) { startingPointActions }
                    }
                    Label("Or tap the map to choose a place", systemImage: "hand.tap")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if isPinned {
                Divider()
                Button(action: showDetails) {
                    HStack {
                        Label("Network Evidence & Actions", systemImage: "waveform.path.ecg")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(NodeScopeStyle.signal)
            } else {
                Button(action: pin) {
                    Label("Guide Me", systemImage: "location.north")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(NodeScopeStyle.signal)
                .foregroundStyle(NodeScopeStyle.deepNavy)
            }
        }
        .padding(16)
        // Nearly opaque: this sits over a busy map, and street labels must not show through.
        .instrumentCard(fillOpacity: 0.96)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
    }

    private func bearingLine(for geometry: FieldGeometryResult) -> String {
        var parts: [String] = []
        if let bearing = geometry.initialBearingDegrees {
            let degrees = bearing.formatted(.number.precision(.fractionLength(0)))
            if let direction = geometry.cardinalDirection {
                parts.append("\(degrees)° \(direction.rawValue)")
            } else {
                parts.append("\(degrees)°")
            }
        }
        if let headingSample {
            parts.append("±\(headingSample.accuracyDegrees.formatted(.number.precision(.fractionLength(0))))°")
        } else if headingStatus == .calibrating {
            parts.append(String(localized: "Calibrating"))
        } else {
            parts.append(String(localized: "North up"))
        }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private var startingPointActions: some View {
        Button(action: useMyLocation) {
            Label("Use My Location", systemImage: "location.fill")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(NodeScopeStyle.signal)
        .foregroundStyle(NodeScopeStyle.deepNavy)

        Button(action: enterCoordinates) {
            Label("Enter Coordinates", systemImage: "number")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
    }
}

/// A compass dial pointing at the target. With a device heading, the arrow is relative to the top of
/// the phone and the N mark turns with it; without one the dial is north-up, like the map.
private struct FieldDirectionDial: View {
    let trueBearingDegrees: Double?
    let headingSample: FieldHeadingSample?
    let status: FieldHeadingStatus
    @Environment(\.colorScheme) private var colorScheme
    /// Running angles that keep turning past 360° instead of wrapping, so crossing north animates a
    /// few degrees rather than spinning most of the way around.
    @State private var displayedArrowAngle: Double?
    @State private var displayedNorthAngle: Double = 0

    private var deviceHeading: Double { headingSample?.trueHeadingDegrees ?? 0 }

    private var arrowRotation: Double? {
        guard let trueBearingDegrees else { return nil }
        return FieldGeometry.relativeHeadingRotation(
            trueBearingDegrees: trueBearingDegrees,
            deviceTrueHeadingDegrees: deviceHeading
        )
    }

    var body: some View {
        let accent = NodeScopeStyle.signal.readableForeground(for: colorScheme)
        ZStack {
            Circle()
                .fill(NodeScopeStyle.signal.opacity(colorScheme == .dark ? 0.14 : 0.10))
            Circle()
                .strokeBorder(NodeScopeStyle.signal.opacity(0.45), lineWidth: 1.5)
            Text("N")
                .font(.monaspace(.caption2).weight(.bold))
                .foregroundStyle(.secondary)
                .offset(y: -27)
                .rotationEffect(.degrees(displayedNorthAngle))
            if let displayedArrowAngle {
                Image(systemName: "location.north.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(accent)
                    .rotationEffect(.degrees(displayedArrowAngle))
            } else if status == .unavailable {
                Image(systemName: "location.slash")
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 72, height: 72)
        .onAppear {
            displayedArrowAngle = arrowRotation
            displayedNorthAngle = -deviceHeading
        }
        .onChange(of: arrowRotation) { _, target in
            guard let target else {
                displayedArrowAngle = nil
                return
            }
            withAnimation(.easeOut(duration: 0.25)) {
                displayedArrowAngle = FieldGeometry.nearestEquivalentAngle(to: target, from: displayedArrowAngle)
            }
        }
        .onChange(of: deviceHeading) { _, heading in
            withAnimation(.easeOut(duration: 0.25)) {
                displayedNorthAngle = FieldGeometry.nearestEquivalentAngle(to: -heading, from: displayedNorthAngle)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Target direction")
        .accessibilityValue(accessibilityValue)
    }

    private var accessibilityValue: String {
        guard let arrowRotation else { return String(localized: "Unknown") }
        let degrees = arrowRotation.formatted(.number.precision(.fractionLength(0)))
        if let headingSample {
            let accuracy = headingSample.accuracyDegrees.formatted(.number.precision(.fractionLength(0)))
            return String(localized: "\(degrees) degrees clockwise from the top of the device, accuracy plus or minus \(accuracy) degrees")
        }
        return String(localized: "\(degrees) degrees clockwise from north")
    }
}

private struct FieldTargetEvidenceCard: View {
    let evidence: FieldTargetEvidence
    let analytics: FieldEvidenceValue?
    let isLoadingAnalytics: Bool
    let analyticsError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Observed Evidence", systemImage: "waveform.path.ecg")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if let activityAt = evidence.activityAt {
                    Text(activityAt, format: .relative(presentation: .named))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Activity unknown")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if !evidence.facts.isEmpty {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 16) {
                        ForEach(evidence.facts) { fact in
                            FieldEvidenceFactView(kind: fact.kind, value: fact.value)
                        }
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(evidence.facts) { fact in
                            FieldEvidenceFactView(kind: fact.kind, value: fact.value)
                        }
                    }
                }
            }

            Label("Signal and receiver coverage aren’t included in this catalog record.", systemImage: "info.circle")
                .font(.caption)
                .foregroundStyle(.secondary)

            FieldAnalyticsEvidenceSection(
                value: analytics,
                isLoading: isLoadingAnalytics,
                errorMessage: analyticsError
            )

            if let fetchedAt = evidence.catalogFetchedAt {
                Text("Analyzer catalog · \(evidence.sourceHost) · fetched \(fetchedAt, format: .relative(presentation: .named))")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            } else {
                Text("Analyzer catalog · \(evidence.sourceHost) · fetch time unknown")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(14)
        .instrumentCard()
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
    }
}

private struct FieldAnalyticsEvidenceSection: View {
    let value: FieldEvidenceValue?
    let isLoading: Bool
    let errorMessage: String?

    var body: some View {
        if let value {
            Divider()
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(title(value.evidence.kind))
                        .font(.caption.weight(.semibold))
                    Spacer()
                    Text(freshnessText(value.freshness))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Text(summary(value.evidence))
                    .font(.caption)
                if let receiver = value.evidence.strongestReceiverName,
                   let snr = value.evidence.strongestReceiverSnr {
                    Text("Strongest average receiver: \(receiver) · \(snr, format: .number.precision(.fractionLength(1))) dB SNR")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let latestObservedAt = value.evidence.latestObservedAt {
                    Text("Latest observation \(latestObservedAt, format: .relative(presentation: .named))")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        } else if isLoading {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Loading observed signal and receiver evidence…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }

        if let errorMessage {
            Label(errorMessage, systemImage: "exclamationmark.triangle")
                .font(.caption)
                .foregroundStyle(NodeScopeStyle.activity)
        }
    }

    private func summary(_ evidence: FieldTargetAnalyticsEvidence) -> String {
        var parts = [String(localized: "\(evidence.activityCount) recorded packets")]
        if let receiverCount = evidence.receiverCount {
            parts.append(String(localized: "\(receiverCount) receivers"))
        }
        if let snr = evidence.latestSnr {
            parts.append(String(localized: "latest \(snr.formatted(.number.precision(.fractionLength(1)))) dB SNR"))
        }
        if let rssi = evidence.latestRssi {
            parts.append(String(localized: "\(rssi.formatted(.number.precision(.fractionLength(0)))) dBm RSSI"))
        }
        return parts.formatted()
    }

    private func title(_ kind: FieldTargetAnalyticsEvidence.Kind) -> LocalizedStringResource {
        switch kind {
        case .node: "Observed over the last 24 hours"
        case .observer: "Recent observer evidence"
        }
    }

    private func freshnessText(_ freshness: FieldEvidenceValue.Freshness) -> LocalizedStringResource {
        switch freshness {
        case .fetched: "Updated now"
        case .cached: "Saved evidence"
        case .staleFallback: "Saved fallback"
        }
    }
}

private struct FieldTargetQuickActions: View {
    let item: FieldCatalogItem
    let capabilities: Set<BackendCapability>
    let analytics: FieldTargetAnalyticsEvidence?

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { actions }
            VStack(spacing: 8) { actions }
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var actions: some View {
        switch item {
        case .node(let node):
            NavigationLink {
                NodeDetailScreen(node: node)
            } label: {
                Label("Node Details", systemImage: "info.circle")
            }
            if capabilities.contains(.nodeAnalytics) {
                NavigationLink {
                    NodeAnalyticsScreen(node: node)
                } label: {
                    Label("Analytics", systemImage: "chart.xyaxis.line")
                }
            }
        case .observer(let observer):
            NavigationLink {
                ObserverDetailScreen(observer: observer)
            } label: {
                Label(observerActionTitle, systemImage: "info.circle")
            }
            if let hash = analytics?.latestPacketHash,
               let timestamp = analytics?.latestPacketTimestamp {
                NavigationLink {
                    PacketDetailScreen(packetHash: hash, timestamp: timestamp, snr: analytics?.latestSnr)
                } label: {
                    Label("Latest Packet & Route", systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                }
            }
        }
    }

    private var observerActionTitle: LocalizedStringResource {
        capabilities.contains(.observerAnalytics) ? "Details & Analytics" : "Observer Details"
    }
}

private struct FieldEvidenceFactView: View {
    let kind: FieldTargetEvidence.FactKind
    let value: String
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Label {
            Text("\(title): \(value)")
                .font(.caption)
        } icon: {
            Image(systemName: symbol)
                .foregroundStyle(NodeScopeStyle.signal.readableForeground(for: colorScheme))
        }
    }

    private var title: LocalizedStringResource {
        switch kind {
        case .advertisements: "Advertisements"
        case .observerStatus: "Status"
        case .packetsLastHour: "Packets last hour"
        case .noiseFloor: "Noise floor"
        }
    }

    private var symbol: String {
        switch kind {
        case .advertisements: "megaphone"
        case .observerStatus: "dot.radiowaves.left.and.right"
        case .packetsLastHour: "envelope"
        case .noiseFloor: "waveform"
        }
    }
}

private struct FieldLocationStatusLabel: View {
    let status: FieldLocationStatus

    var body: some View {
        switch status {
        case .idle, .requestingPermission, .locating:
            Label("Finding your location…", systemImage: "location.magnifyingglass")
                .foregroundStyle(.secondary)
        case .active:
            Label("Foreground location active", systemImage: "location.fill")
                .foregroundStyle(NodeScopeStyle.healthy)
        case .denied:
            Label("Location access is denied. Use a manual reference instead.", systemImage: "location.slash")
                .foregroundStyle(NodeScopeStyle.activity)
        case .unavailable:
            Label("Location services are unavailable.", systemImage: "location.slash")
                .foregroundStyle(NodeScopeStyle.activity)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle")
                .foregroundStyle(NodeScopeStyle.activity)
        }
    }
}

private struct FieldCoordinateEntrySheet: View {
    let save: (FieldCoordinate) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var latitude: String
    @State private var longitude: String

    init(initialCoordinate: FieldCoordinate?, save: @escaping (FieldCoordinate) -> Void) {
        self.save = save
        _latitude = State(initialValue: initialCoordinate.map { String($0.latitude) } ?? "")
        _longitude = State(initialValue: initialCoordinate.map { String($0.longitude) } ?? "")
    }

    private var coordinate: FieldCoordinate? {
        guard let latitude = Double(latitude.trimmingCharacters(in: .whitespacesAndNewlines)),
              let longitude = Double(longitude.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            return nil
        }
        return FieldCoordinate(latitude: latitude, longitude: longitude)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Reference Coordinate") {
                    TextField("Latitude", text: $latitude)
                        .keyboardType(.numbersAndPunctuation)
                    TextField("Longitude", text: $longitude)
                        .keyboardType(.numbersAndPunctuation)
                }
                .instrumentListRows()

                Section {
                    Text("Latitude must be between −90 and 90. Longitude must be between −180 and 180.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .instrumentListRows()
            }
            .meshPageBackground()
            .navigationTitle("Enter Coordinates")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use Coordinate") {
                        guard let coordinate else { return }
                        save(coordinate)
                        dismiss()
                    }
                    .disabled(coordinate == nil)
                }
            }
        }
    }
}

private struct FieldCatalogRow: View {
    let item: FieldCatalogItem
    let geometry: FieldGeometryResult?
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(NodeScopeStyle.deepNavy)
                .frame(width: 40, height: 40)
                .background(NodeScopeStyle.signal, in: Circle())
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.monaspace(.headline))
                Text(item.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if let geometry {
                VStack(alignment: .trailing, spacing: 3) {
                    Text(Measurement(value: geometry.distanceMeters, unit: UnitLength.meters),
                         format: .measurement(width: .abbreviated, usage: .road))
                        .font(.subheadline.weight(.semibold))
                    if let direction = geometry.cardinalDirection {
                        Text(direction.rawValue)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(12)
        .instrumentCard(isSelected: isSelected)
        .contentShape(Rectangle())
    }
}

private struct FieldMapMarker: View {
    let symbol: String
    let color: Color

    var body: some View {
        Image(systemName: symbol)
            .font(.caption.weight(.bold))
            .foregroundStyle(NodeScopeStyle.deepNavy)
            .frame(width: 34, height: 34)
            .background(color, in: Circle())
            .overlay {
                Circle().stroke(.white.opacity(0.85), lineWidth: 2)
            }
            .shadow(color: .black.opacity(0.24), radius: 6, y: 3)
    }
}

private struct FieldCanvasHeader: View {
    let activity: FieldToolsActivity
    let referenceIsSet: Bool
    let selectedName: String?
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) {
                content
            }
            VStack(alignment: .leading, spacing: 6) {
                content
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .nodeScopeFloatingGlass(cornerRadius: 18)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var content: some View {
        Label(activity.title, systemImage: activity.symbol)
            .font(.monaspace(.headline))
        Text(referenceIsSet ? "Reference selected" : "Tap the map to set a reference")
            .font(.caption)
            .foregroundStyle(.secondary)
        if let selectedName {
            Text(selectedName)
                .font(.monaspace(.caption).weight(.semibold))
                .foregroundStyle(NodeScopeStyle.signal.readableForeground(for: colorScheme))
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private extension FieldCoordinate {
    static func analyzerCoordinate(latitude: Double?, longitude: Double?) -> FieldCoordinate? {
        guard let latitude, let longitude, !(latitude == 0 && longitude == 0) else { return nil }
        return FieldCoordinate(latitude: latitude, longitude: longitude)
    }

    var clLocationCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}
