import SwiftUI

struct ObserversListScreen: View {
    let resetID: UUID

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(AppNavigationStore.self) private var appNavigationStore
    @State private var viewModel = ObserversViewModel()
    @State private var navigationPath = NavigationPath()
    @State private var searchText = ""
    @State private var isSearchPresented = false
    @State private var visibleObservers: [MeshObserver] = []
    @State private var availableModels: [String] = []
    @State private var activityFilter = ObserverActivityFilter.all
    @State private var selectedModel: String?
    @State private var sortOption = ObserverSortOption.recent
    @State private var lastHandledNavigationRequestID: UUID?

    var body: some View {
        NavigationStack(path: $navigationPath) {
            List {
                ObserversHeader(
                    isConnected: liveFeed.isConnected,
                    regionName: regionFilter.selectedRegion.map(regionFilter.label(for:)),
                    isSearchPresented: isSearchPresented,
                    toggleSearch: toggleSearch,
                    activityFilter: $activityFilter,
                    selectedModel: $selectedModel,
                    sortOption: $sortOption,
                    availableModels: availableModels
                )
                .iPadWindowControlsClearance()
                .instrumentListRow(top: 18, bottom: 10)

                if isSearchPresented {
                    InstrumentSearchField(text: $searchText, prompt: "Search observer nodes")
                        .instrumentListRow(top: 0, bottom: 8)
                }

                if viewModel.isLoading {
                    LoadingIndicator(title: "Polling observer network")
                        .instrumentListRow(top: 0, bottom: 8)
                }

                if !viewModel.isLoading,
                   viewModel.lastUpdatedAt != nil || viewModel.errorMessage != nil {
                    DataLoadStatusView(
                        lastUpdatedAt: viewModel.lastUpdatedAt,
                        errorMessage: viewModel.errorMessage,
                        hasContent: !viewModel.observers.isEmpty,
                        retry: retryObserverLoad
                    )
                    .instrumentListRow(top: 0, bottom: 8)
                }

                if !visibleObservers.isEmpty {
                    ObserverSummaryGrid(
                        observerCount: visibleObservers.count,
                        packetCount: visibleObservers.reduce(0) { $0 + $1.packetCount },
                        hourlyCount: visibleObservers.reduce(0) { $0 + $1.packetsLastHour }
                    )
                    .instrumentListRow(top: 2, bottom: 12)
                }

                Section {
                    if viewModel.isLoading && viewModel.observers.isEmpty {
                        ForEach(0..<4, id: \.self) { _ in
                            ObserverSkeletonCard()
                                .instrumentListRow()
                        }
                    } else {
                        ForEach(visibleObservers) { observer in
                            NavigationLink(value: observer) {
                                ObserverStatusCard(
                                    name: observer.name ?? observer.id,
                                    iata: observer.iata,
                                    model: observer.model,
                                    lastSeen: observer.lastSeen,
                                    packetCount: observer.packetCount,
                                    packetsLastHour: observer.packetsLastHour,
                                    batteryMv: observer.batteryMv,
                                    noiseFloor: observer.noiseFloor
                                )
                            }
                            .buttonStyle(.plain)
                            .instrumentListRow()
                        }
                    }
                } header: {
                    Text("Observer Nodes")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                }
            }
            .scrollContentBackground(.hidden)
            .background(NodeScopeBackground())
            .listStyle(.plain)
            .adaptiveContentWidth()
            .background(NodeScopeBackground())
            .contentMargins(.bottom, 104, for: .scrollContent)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: MeshObserver.self) { observer in
                ObserverDetailScreen(observer: observer)
            }
            .overlay {
                if visibleObservers.isEmpty && !viewModel.isLoading {
                    ObserverEmptyState()
                }
            }
        }
        .onChange(of: resetID) {
            navigationPath = NavigationPath()
            searchText = ""
            isSearchPresented = false
        }
        .task {
            viewModel.configure(settings: settings)
            await viewModel.loadObservers()
            updateVisibleObservers()
            openRequestedObserverIfAvailable()
        }
        .refreshable {
            await viewModel.loadObservers()
            updateVisibleObservers()
        }
        .onChange(of: viewModel.observers) { updateVisibleObservers() }
        .onChange(of: regionFilter.selectedRegion) { updateVisibleObservers() }
        .onChange(of: searchText) { updateVisibleObservers() }
        .onChange(of: activityFilter) { updateVisibleObservers() }
        .onChange(of: selectedModel) { updateVisibleObservers() }
        .onChange(of: sortOption) { updateVisibleObservers() }
        .onChange(of: appNavigationStore.requestID) { openRequestedObserverIfAvailable() }
    }

    private func retryObserverLoad() {
        Task {
            await viewModel.loadObservers()
            updateVisibleObservers()
        }
    }

    private func openRequestedObserverIfAvailable() {
        guard lastHandledNavigationRequestID != appNavigationStore.requestID else { return }
        if case .activeObservers = appNavigationStore.destination {
            lastHandledNavigationRequestID = appNavigationStore.requestID
            activityFilter = .recent
            updateVisibleObservers()
            return
        }
        guard case .observer(let observerID) = appNavigationStore.destination,
              let observer = viewModel.observers.first(where: { $0.id == observerID }) else { return }
        lastHandledNavigationRequestID = appNavigationStore.requestID
        navigationPath = NavigationPath()
        navigationPath.append(observer)
    }

    private func toggleSearch() {
        withAnimation(.easeInOut(duration: 0.2)) {
            isSearchPresented.toggle()
            if !isSearchPresented {
                searchText = ""
            }
        }
    }

    private func updateVisibleObservers() {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        availableModels = Array(Set(viewModel.observers.compactMap(\.model))).sorted()

        let filtered = viewModel.observers.filter { observer in
            let matchesRegion = regionFilter.selectedRegion == nil || observer.iata == regionFilter.selectedRegion
            let matchesActivity = activityFilter == .all || observer.lastSeen.timeIntervalSinceNow > -900
            let matchesModel = selectedModel == nil || observer.model == selectedModel
            let matchesQuery = query.isEmpty
                || observer.name?.localizedCaseInsensitiveContains(query) == true
                || observer.id.localizedCaseInsensitiveContains(query)
                || observer.iata?.localizedCaseInsensitiveContains(query) == true
                || observer.model?.localizedCaseInsensitiveContains(query) == true
            return matchesRegion && matchesActivity && matchesModel && matchesQuery
        }

        visibleObservers = filtered.sorted { lhs, rhs in
            switch sortOption {
            case .recent:
                lhs.lastSeen > rhs.lastSeen
            case .hourlyPackets:
                lhs.packetsLastHour == rhs.packetsLastHour
                    ? lhs.lastSeen > rhs.lastSeen
                    : lhs.packetsLastHour > rhs.packetsLastHour
            case .totalPackets:
                lhs.packetCount == rhs.packetCount
                    ? lhs.lastSeen > rhs.lastSeen
                    : lhs.packetCount > rhs.packetCount
            case .name:
                (lhs.name ?? lhs.id).localizedCaseInsensitiveCompare(rhs.name ?? rhs.id) == .orderedAscending
            }
        }
    }
}

private enum ObserverActivityFilter: String, CaseIterable, Identifiable {
    case all
    case recent

    var id: String { rawValue }
    var title: LocalizedStringResource {
        switch self {
        case .all: "All Activity"
        case .recent: "Active in 15 Minutes"
        }
    }
}

private enum ObserverSortOption: String, CaseIterable, Identifiable {
    case recent
    case hourlyPackets
    case totalPackets
    case name

    var id: String { rawValue }
    var title: LocalizedStringResource {
        switch self {
        case .recent: "Recently Seen"
        case .hourlyPackets: "Packets per Hour"
        case .totalPackets: "Total Packets"
        case .name: "Name"
        }
    }
}

private struct ObserversHeader: View {
    let isConnected: Bool
    let regionName: String?
    let isSearchPresented: Bool
    let toggleSearch: () -> Void
    @Binding var activityFilter: ObserverActivityFilter
    @Binding var selectedModel: String?
    @Binding var sortOption: ObserverSortOption
    let availableModels: [String]

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 5) {
                Text("Observers")
                    .font(.largeTitle.bold())
                HStack(spacing: 6) {
                    Circle()
                        .fill(isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                        .frame(width: 7, height: 7)
                    Text(isConnected ? "Network telemetry live" : "Reconnecting")
                    if let regionName {
                        Text("· \(regionName)")
                    }
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            }

            Spacer()

            HStack(spacing: 10) {
                ObserverFilterMenu(
                    activityFilter: $activityFilter,
                    selectedModel: $selectedModel,
                    sortOption: $sortOption,
                    availableModels: availableModels
                )

                Button(action: toggleSearch) {
                    Image(systemName: isSearchPresented ? "xmark" : "magnifyingglass")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(NodeScopeStyle.signal)
                        .frame(width: 40, height: 40)
                        .background(.thinMaterial, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isSearchPresented ? "Close observer search" : "Search observers")

                RegionFilterMenu()
                    .foregroundStyle(NodeScopeStyle.signal)
                    .padding(.horizontal, 10)
                    .frame(minWidth: 40, minHeight: 40)
                    .background(.thinMaterial, in: Capsule())
                    .accessibilityLabel("Filter observers by region")
            }
        }
    }
}

private struct ObserverFilterMenu: View {
    @Binding var activityFilter: ObserverActivityFilter
    @Binding var selectedModel: String?
    @Binding var sortOption: ObserverSortOption
    let availableModels: [String]

    private var hasActiveFilter: Bool {
        activityFilter != .all || selectedModel != nil || sortOption != .recent
    }

    var body: some View {
        Menu {
            Section("Activity") {
                ForEach(ObserverActivityFilter.allCases) { option in
                    Button {
                        activityFilter = option
                    } label: {
                        Label(option.title, systemImage: activityFilter == option ? "checkmark" : "clock")
                    }
                }
            }

            Section("Hardware") {
                Button {
                    selectedModel = nil
                } label: {
                    Label("All Models", systemImage: selectedModel == nil ? "checkmark" : "cpu")
                }
                ForEach(availableModels, id: \.self) { model in
                    Button {
                        selectedModel = model
                    } label: {
                        Label(model, systemImage: selectedModel == model ? "checkmark" : "cpu")
                    }
                }
            }

            Section("Sort By") {
                ForEach(ObserverSortOption.allCases) { option in
                    Button {
                        sortOption = option
                    } label: {
                        Label(option.title, systemImage: sortOption == option ? "checkmark" : "arrow.up.arrow.down")
                    }
                }
            }
        } label: {
            Image(systemName: hasActiveFilter ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                .font(.body.weight(.semibold))
                .foregroundStyle(NodeScopeStyle.signal)
                .frame(width: 40, height: 40)
                .background(.thinMaterial, in: Circle())
        }
        .accessibilityLabel("Filter and sort observers")
    }
}

private struct ObserverSummaryGrid: View {
    let observerCount: Int
    let packetCount: Int
    let hourlyCount: Int

    var body: some View {
        HStack(spacing: 8) {
            ObserverMetric(value: observerCount.formatted(), label: "Nodes", symbol: "antenna.radiowaves.left.and.right")
            ObserverMetric(value: hourlyCount.formatted(), label: "Packets / Hr", symbol: "waveform.path.ecg")
            ObserverMetric(value: packetCount.formatted(.number.notation(.compactName)), label: "Packets", symbol: "shippingbox.fill")
        }
    }
}

private struct ObserverMetric: View {
    let value: String
    let label: LocalizedStringKey
    let symbol: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: symbol)
                .font(.caption.weight(.bold))
                .foregroundStyle(NodeScopeStyle.signal)
            Text(value)
                .font(.headline.monospacedDigit())
            Text(label)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .instrumentCard()
    }
}

private struct ObserverStatusCard: View {
    let name: String
    let iata: String?
    let model: String?
    let lastSeen: Date
    let packetCount: Int
    let packetsLastHour: Int
    let batteryMv: Int?
    let noiseFloor: Double?

    private var isRecent: Bool {
        lastSeen.timeIntervalSinceNow > -900
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle()
                    .fill((isRecent ? NodeScopeStyle.healthy : NodeScopeStyle.activity).opacity(0.14))
                    .frame(width: 42, height: 42)
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .foregroundStyle(isRecent ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
            }

            VStack(alignment: .leading, spacing: 9) {
                HStack(alignment: .firstTextBaseline) {
                    Text(name)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer()
                    Text(lastSeen, style: .relative)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }

                HStack(spacing: 6) {
                    if let iata {
                        ObserverChip(text: iata, symbol: "mappin.and.ellipse", color: NodeScopeStyle.signal)
                    }
                    if let model {
                        ObserverChip(text: model, symbol: "cpu", color: .secondary)
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 16) {
                        Label("\(packetsLastHour)/hr", systemImage: "waveform")
                        Label(packetCount.formatted(.number.notation(.compactName)), systemImage: "shippingbox")
                    }
                    HStack(spacing: 16) {
                        if let batteryMv {
                            Label("\(batteryMv) mV", systemImage: "battery.75percent")
                        }
                        if let noiseFloor {
                            Label("\(noiseFloor, specifier: "%.0f") dB", systemImage: "speaker.wave.1")
                        }
                    }
                }
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .instrumentCard()
    }
}

private struct ObserverChip: View {
    let text: String
    let symbol: String
    let color: Color

    var body: some View {
        Label(text, systemImage: symbol)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.1), in: Capsule())
            .lineLimit(1)
    }
}

private struct ObserverSkeletonCard: View {
    var body: some View {
        HStack(spacing: 12) {
            Circle().fill(NodeScopeStyle.signal.opacity(0.12)).frame(width: 42, height: 42)
            VStack(alignment: .leading, spacing: 9) {
                Capsule().fill(.quaternary).frame(width: 150, height: 12)
                Capsule().fill(.quaternary).frame(width: 100, height: 9)
                Capsule().fill(.quaternary).frame(height: 8)
            }
        }
        .padding(14)
        .instrumentCard()
        .redacted(reason: .placeholder)
        .accessibilityLabel("Loading observer")
    }
}

private struct ObserverEmptyState: View {
    var body: some View {
        VStack(spacing: 12) {
            MeshNodeMotif(color: NodeScopeStyle.signal)
            Text("No observers in range")
                .font(.headline)
            Text("Try another region or pull to refresh the network.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
    }
}

private extension View {
    func instrumentListRow(top: CGFloat = 6, bottom: CGFloat = 6) -> some View {
        listRowInsets(EdgeInsets(top: top, leading: 20, bottom: bottom, trailing: 20))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}
