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

private enum FieldCatalogItem: Identifiable, Hashable {
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

@MainActor
@Observable
private final class FieldToolsViewModel {
    var nodes: [MeshNode] = []
    var observers: [MeshObserver] = []
    var isLoading = false
    var errorMessage: String?
    var lastUpdatedAt: Date?

    private var backend: (any AnalyzerBackend)?
    private var loadID = UUID()

    var locatedItems: [FieldCatalogItem] {
        let nodeItems = nodes.map(FieldCatalogItem.node)
        let observerItems = observers.map(FieldCatalogItem.observer)
        return (nodeItems + observerItems)
            .filter { $0.coordinate != nil }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
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

    func load(forceRefresh: Bool = false) async {
        guard let backend, backend.sourceSession.isCurrent else { return }
        let currentLoadID = UUID()
        loadID = currentLoadID
        isLoading = nodes.isEmpty && observers.isEmpty
        defer {
            if loadID == currentLoadID { isLoading = false }
        }

        let cachedNodes = await FieldCatalogRepository.shared.cachedNodes(for: backend, region: nil)
        let cachedObservers = await FieldCatalogRepository.shared.cachedObservers(for: backend)
        guard isCurrent(currentLoadID, backend: backend) else { return }
        if let cachedNodes {
            nodes = cachedNodes.value.nodes
            lastUpdatedAt = cachedNodes.savedAt
        }
        if let cachedObservers {
            observers = cachedObservers.value.observers
            lastUpdatedAt = [lastUpdatedAt, cachedObservers.savedAt].compactMap { $0 }.max()
        }

        do {
            async let refreshedNodes: FieldCatalogValue<NodesResponse>? =
                refreshNodesIfNeeded(backend: backend, force: forceRefresh)
            async let refreshedObservers: FieldCatalogValue<ObserversResponse>? =
                refreshObserversIfNeeded(backend: backend, force: forceRefresh)
            let (nodeCatalog, observerCatalog) = try await (refreshedNodes, refreshedObservers)
            guard isCurrent(currentLoadID, backend: backend) else { return }
            if let nodeCatalog {
                nodes = nodeCatalog.value.nodes
                lastUpdatedAt = nodeCatalog.savedAt
            }
            if let observerCatalog {
                observers = observerCatalog.value.observers
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
        force: Bool
    ) async throws -> FieldCatalogValue<NodesResponse>? {
        guard await FieldCatalogRepository.shared.nodesNeedRefresh(
            for: backend, region: nil, force: force
        ) else { return nil }
        return try await FieldCatalogRepository.shared.refreshNodes(for: backend, region: nil)
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
}

struct FieldToolsScreen: View {
    let initialReference: FieldCoordinate?

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @State private var model = FieldToolsViewModel()
    @State private var activity = FieldToolsActivity.nearby
    @State private var selectedItem: FieldCatalogItem?
    @State private var reference: FieldCoordinate?
    @State private var preferredCompactColumn = NavigationSplitViewColumn.sidebar
    @State private var position: MapCameraPosition = .automatic

    init(initialReference: FieldCoordinate? = nil) {
        self.initialReference = initialReference
        _reference = State(initialValue: initialReference)
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
        .task(id: settings.sourceSession.id) {
            model.configure(settings: settings)
            await model.load()
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
            await model.load(forceRefresh: true)
        }
    }

    @ViewBuilder
    private var contextSection: some View {
        Section("Context") {
            Label(settings.host, systemImage: "server.rack")
            Label(regionFilter.selectedRegion ?? String(localized: "All regions"), systemImage: "scope")
            if reference == nil {
                Button {
                    preferredCompactColumn = .detail
                } label: {
                    Label("Choose Reference on Map", systemImage: "mappin.and.ellipse")
                }
            } else {
                Label("Manual map reference", systemImage: "location")
                    .foregroundStyle(NodeScopeStyle.signal)
                Button("Clear Reference", role: .destructive) {
                    reference = nil
                }
            }
        }
    }

    @ViewBuilder
    private var nearbySection: some View {
        Section {
            if model.isLoading && model.locatedItems.isEmpty {
                HStack {
                    Spacer()
                    LoadingIndicator(title: "Loading nearby equipment")
                    Spacer()
                }
                .listRowBackground(Color.clear)
            } else if model.locatedItems.isEmpty {
                ContentUnavailableView {
                    Label("No Located Equipment", systemImage: "location.slash")
                } description: {
                    Text("This analyzer has no nodes or observers with usable reported coordinates.")
                }
                .listRowBackground(Color.clear)
            } else {
                ForEach(model.locatedItems) { item in
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
                    if let reference {
                        Annotation("Reference", coordinate: reference.clLocationCoordinate) {
                            FieldMapMarker(symbol: "scope", color: NodeScopeStyle.activity)
                        }
                    }

                    ForEach(model.locatedItems) { item in
                        if let coordinate = item.coordinate {
                            Annotation(item.name, coordinate: coordinate.clLocationCoordinate) {
                                Button {
                                    selectedItem = item
                                } label: {
                                    FieldMapMarker(
                                        symbol: item.symbol,
                                        color: item.id == selectedItem?.id ? NodeScopeStyle.activity : NodeScopeStyle.signal
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
                    reference = fieldCoordinate
                }
            }

            FieldCanvasHeader(
                activity: activity,
                referenceIsSet: reference != nil,
                selectedName: selectedItem?.name
            )
            .padding(12)
        }
        .navigationTitle(selectedItem?.name ?? String(localized: "Field Map"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func geometry(for item: FieldCatalogItem) -> FieldGeometryResult? {
        guard let reference, let coordinate = item.coordinate else { return nil }
        return FieldGeometry.geometry(from: reference, to: coordinate)
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
