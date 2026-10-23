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

    private var backend: (any AnalyzerBackend)?
    private var loadID = UUID()

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
        }
        backend = next
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
    let initialReference: FieldCoordinate?

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(\.scenePhase) private var scenePhase
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

    init(
        initialReference: FieldCoordinate? = nil,
        initialNode: MeshNode? = nil,
        initialObserver: MeshObserver? = nil
    ) {
        self.initialReference = initialReference
        _model = State(initialValue: FieldToolsViewModel(reference: initialReference))
        let initialItem = initialNode.map(FieldCatalogItem.node) ?? initialObserver.map(FieldCatalogItem.observer)
        _selectedItem = State(initialValue: initialItem)
        _pinnedItem = State(initialValue: initialItem)
    }

    var body: some View {
        AdaptiveListDetailNavigation(
            preferredCompactColumn: $preferredCompactColumn,
            detailNavigationID: selectedItem?.id
        ) {
            toolsPanel
        } detail: {
            canvas
        }
        .navigationTitle("Field Tools")
        .task(id: "\(settings.sourceSession.id)|\(regionFilter.selectedRegion ?? "")") {
            model.configure(settings: settings)
            await model.load(region: regionFilter.selectedRegion)
        }
        .onChange(of: locationSession.sample) { _, sample in
            guard usesDeviceReference, let sample else { return }
            model.reference = sample.coordinate
            position = .region(MKCoordinateRegion(
                center: sample.coordinate.clLocationCoordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
            ))
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
    }

    private var toolsPanel: some View {
        List {
            Section {
                Picker("Activity", selection: $activity) {
                    ForEach(FieldToolsActivity.allCases) { activity in
                        Label(activity.title, systemImage: activity.symbol)
                            .tag(activity)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
            }

            contextSection

            switch activity {
            case .nearby:
                nearbySection
            case .plan:
                planSection
            }
        }
        .navigationTitle("Field Tools")
        .adaptiveSidebarListStyle()
        .refreshable {
            await model.load(region: regionFilter.selectedRegion, forceRefresh: true)
        }
    }

    @ViewBuilder
    private var contextSection: some View {
        Section("Context") {
            Label(settings.host, systemImage: "server.rack")
            Label(regionFilter.selectedRegion ?? String(localized: "All regions"), systemImage: "scope")
            if model.reference == nil {
                Button {
                    preferredCompactColumn = .detail
                } label: {
                    Label("Choose Reference on Map", systemImage: "mappin.and.ellipse")
                }
            } else {
                Label(referenceDescription, systemImage: usesDeviceReference ? "location.fill" : "location")
                    .foregroundStyle(NodeScopeStyle.signal)
                Button("Clear Reference", role: .destructive) {
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
                        if let coordinate = item.coordinate {
                            position = .region(MKCoordinateRegion(
                                center: coordinate.clLocationCoordinate,
                                span: MKCoordinateSpan(latitudeDelta: 0.2, longitudeDelta: 0.2)
                            ))
                        }
                        preferredCompactColumn = .detail
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
            Text("Located Equipment")
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
            ContentUnavailableView {
                Label("Deployment Planning", systemImage: "antenna.radiowaves.left.and.right")
            } description: {
                Text("Choose a proposed site on the map. Radio, antenna, terrain, and receiver assumptions are added in the next planning slice.")
            } actions: {
                Button("Choose Proposed Site") {
                    preferredCompactColumn = .detail
                }
                .buttonStyle(.borderedProminent)
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

                    ForEach(model.visibleItems) { item in
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
                                .accessibilityLabel(item.name)
                            }
                        }
                    }
                }
                .mapStyle(.standard(elevation: .realistic))
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

            FieldCanvasHeader(
                activity: activity,
                referenceIsSet: model.reference != nil,
                selectedName: selectedItem?.name
            )
            .padding(12)

            if let target = pinnedItem ?? selectedItem {
                VStack {
                    Spacer()
                    FieldTargetSummary(
                        name: target.name,
                        subtitle: target.subtitle,
                        geometry: geometry(for: target),
                        isPinned: pinnedItem?.id == target.id,
                        pin: { pinnedItem = target },
                        unpin: { pinnedItem = nil }
                    )
                    .padding(12)
                }
            }
        }
        .navigationTitle(selectedItem?.name ?? String(localized: "Field Map"))
        .navigationBarTitleDisplayMode(.inline)
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
        position = .region(MKCoordinateRegion(
            center: coordinate.clLocationCoordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.2, longitudeDelta: 0.2)
        ))
    }
}

private struct FieldNearbyControls: View {
    @Bindable var model: FieldToolsViewModel

    var body: some View {
        VStack(spacing: 10) {
            TextField("Search located equipment", text: $model.searchText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Picker("Equipment", selection: $model.filter) {
                ForEach(FieldCatalogFilter.allCases) { filter in
                    Text(filter.title).tag(filter)
                }
            }
            .pickerStyle(.segmented)
        }
        .padding(.vertical, 4)
    }
}

private struct FieldTargetSummary: View {
    let name: String
    let subtitle: String
    let geometry: FieldGeometryResult?
    let isPinned: Bool
    let pin: () -> Void
    let unpin: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                identity
                Spacer(minLength: 8)
                distance
                pinButton
            }

            VStack(alignment: .leading, spacing: 10) {
                identity
                HStack {
                    distance
                    Spacer()
                    pinButton
                }
            }
        }
        .padding(14)
        .instrumentCard(isSelected: isPinned)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
    }

    private var identity: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(name)
                .font(.headline)
                .lineLimit(1)
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var distance: some View {
        if let geometry {
            VStack(alignment: .trailing, spacing: 2) {
                Text(Measurement(value: geometry.distanceMeters, unit: UnitLength.meters),
                     format: .measurement(width: .abbreviated, usage: .road))
                    .font(.subheadline.weight(.semibold))
                if let direction = geometry.cardinalDirection {
                    Text("True bearing · \(direction.rawValue)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } else {
            Text("Set a reference for distance")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var pinButton: some View {
        Button(action: isPinned ? unpin : pin) {
            Label(isPinned ? "Unpin" : "Pin Target", systemImage: isPinned ? "pin.slash" : "pin")
        }
        .buttonStyle(.borderedProminent)
        .tint(isPinned ? NodeScopeStyle.activity : NodeScopeStyle.signal)
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

                Section {
                    Text("Latitude must be between −90 and 90. Longitude must be between −180 and 180.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
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
                .foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(NodeScopeStyle.signal, in: Circle())
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.headline)
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
            .foregroundStyle(.white)
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
            .font(.headline)
        Text(referenceIsSet ? "Reference selected" : "Tap the map to set a reference")
            .font(.caption)
            .foregroundStyle(.secondary)
        if let selectedName {
            Text(selectedName)
                .font(.caption.weight(.semibold))
                .foregroundStyle(NodeScopeStyle.signal)
                .lineLimit(1)
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
