import SwiftUI
import Observation

private extension View {
    @ViewBuilder
    func workspaceBackgroundExtensionEffect() -> some View {
        if #available(iOS 26.0, *) {
            backgroundExtensionEffect()
        } else {
            self
        }
    }
}

struct MapPacketWorkspaceScreen: View {
    let isTabActive: Bool
    let resetID: UUID

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var accessibilityReduceMotion
    @Environment(PacketReplayStore.self) private var replayStore
    @AppStorage("mapPacketSidebarVisible") private var prefersPacketSidebar = true
    @State private var columnVisibility = NavigationSplitViewVisibility.detailOnly
    @State private var preferredCompactColumn = NavigationSplitViewColumn.detail
    @State private var isShowingCompactPackets = false
    @State private var compactPacketDetent: PresentationDetent = .medium
    @State private var selectedLivePacketGroupID: LiveObservationGroup.ID?
    @State private var replayingLivePacketGroupID: LiveObservationGroup.ID?
    @Environment(\.isHingedDevice) private var isHingedDevice
    @State private var isDuoPacketSidebarVisible = false
    @State private var isDuoPacketDetailsPresented = false
    @State private var duoPrimaryWidth: CGFloat = 0
    @State private var duoWorkspaceWidth: CGFloat = 0
    @State private var duoTrailingInset: CGFloat = 0
    @State private var isDuoSidebarToolbarShown = false

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                if isHingedDevice {
                    duoWorkspace
                        .transition(.opacity)
                } else {
                    regularWorkspace
                        .transition(.opacity)
                }
            } else {
                MapScreen(
                    isTabActive: isTabActive,
                    resetID: resetID,
                    showLivePackets: {
                        compactPacketDetent = .medium
                        isShowingCompactPackets = true
                    },
                    bottomObscuredFraction: isShowingCompactPackets
                        ? (compactPacketDetent == .medium ? 0.5 : 0.85)
                        : 0
                )
                .sheet(isPresented: $isShowingCompactPackets, onDismiss: compactPacketSheetDismissed) {
                    NavigationStack {
                        MapLivePacketSidebar(
                            selectedGroupID: $selectedLivePacketGroupID,
                            close: { isShowingCompactPackets = false },
                            routeReplayStarted: { groupID in
                                replayingLivePacketGroupID = groupID
                                isShowingCompactPackets = false
                            }
                        )
                    }
                    .presentationDetents([.medium, .large], selection: $compactPacketDetent)
                    .presentationDragIndicator(.visible)
                    .presentationBackgroundInteraction(.enabled(upThrough: .medium))
                }
            }
        }
        .onChange(of: horizontalSizeClass) { _, sizeClass in
            guard sizeClass == .regular else { return }
            isShowingCompactPackets = false
            updateRegularColumns()
        }
        .onChange(of: resetID) {
            isShowingCompactPackets = false
            selectedLivePacketGroupID = nil
            replayingLivePacketGroupID = nil
            updateRegularColumns()
        }
        .onChange(of: replayStore.isReplayActive) { _, isActive in
            if !isActive {
                if selectedLivePacketGroupID == replayingLivePacketGroupID {
                    selectedLivePacketGroupID = nil
                }
                replayingLivePacketGroupID = nil
            }
        }
    }

    /// One map throughout, never resized: the sidebar slides in over the right screen and the
    /// map is told how much is covered, so its controls move clear while its content and node
    /// groups stay still, then it re-centres once the slide is done. Swapping to a second map flashed white; narrowing the
    /// map re-grouped nodes on every frame. An invisible two-pane layout measures where the hinge
    /// split falls, so the sidebar covers exactly the right screen.
    private var duoWorkspace: some View {
        ZStack(alignment: .topTrailing) {
            DuoTwoPaneLayout {
                Color.clear
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { duoPrimaryWidth = $0 }
            } secondary: {
                Color.clear
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)

            MapScreen(
                isTabActive: isTabActive && !(isDuoPacketSidebarVisible && isDuoPacketDetailsPresented),
                resetID: resetID,
                togglePacketSidebar: { setDuoPacketSidebarVisible(!isDuoPacketSidebarVisible) },
                pinsPacketSidebarToggle: true,
                centersBottomControlsInLeadingPane: !isDuoPacketSidebarVisible,
                trailingObscuredWidth: isDuoPacketSidebarVisible ? duoSidebarWidth : 0
            )

            if isDuoPacketSidebarVisible {
                NavigationStack {
                    MapLivePacketSidebar(
                        selectedGroupID: $selectedLivePacketGroupID,
                        routeReplayStarted: { replayingLivePacketGroupID = $0 },
                        detailPresentationChanged: { isDuoPacketDetailsPresented = $0 }
                    )
                    .toolbar(removing: .sidebarToggle)
                    // Its buttons sit in the side column, which does not slide; they show once
                    // the sidebar has arrived and go before it leaves. Only the items go: hiding
                    // the whole bar moved the content by the bar's height.
                    .environment(\.showsPacketSidebarToolbarItems, isDuoSidebarToolbarShown)
                }
                .frame(width: duoSidebarWidth)
                .frame(maxHeight: .infinity)
                // Opaque, since the map runs on underneath.
                .background {
                    NodeScopeBackground()
                        .ignoresSafeArea()
                }
                // Its background runs on under the side tab column, so it slides by that much
                // more than its width; a plain move stopped short with that strip still showing.
                .transition(.offset(x: duoSidebarWidth + duoTrailingInset))
                // Above the map while leaving too, or the slide-out runs underneath it.
                .zIndex(1)
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { duoWorkspaceWidth = $0 }
        .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.trailing } action: { duoTrailingInset = $0 }
    }

    /// The right screen: everything past the hinge split.
    private var duoSidebarWidth: CGFloat {
        max(0, duoWorkspaceWidth - duoPrimaryWidth)
    }

    private var regularWorkspace: some View {
        NavigationSplitView(
            columnVisibility: $columnVisibility,
            preferredCompactColumn: $preferredCompactColumn
        ) {
            MapLivePacketSidebar(
                selectedGroupID: $selectedLivePacketGroupID,
                toggleSidebar: toggleRegularSidebar,
                routeReplayStarted: { replayingLivePacketGroupID = $0 }
            )
            .toolbar(removing: .sidebarToggle)
            .navigationSplitViewColumnWidth(min: 280, ideal: 340, max: 420)
        } detail: {
            MapScreen(
                isTabActive: isTabActive,
                resetID: resetID,
                togglePacketSidebar: columnVisibility == .detailOnly
                    ? { toggleRegularSidebar() }
                    : nil
            )
        }
        .navigationSplitViewStyle(.balanced)
        .workspaceBackgroundExtensionEffect()
        .onAppear { updateRegularColumns() }
        .onChange(of: columnVisibility) { _, visibility in
            prefersPacketSidebar = visibility != .detailOnly
        }
    }

    private func setDuoPacketSidebarVisible(_ isVisible: Bool) {
        if accessibilityReduceMotion {
            isDuoPacketSidebarVisible = isVisible
            isDuoSidebarToolbarShown = isVisible
        } else if isVisible {
            isDuoSidebarToolbarShown = false
            withAnimation(.smooth(duration: 0.3)) {
                isDuoPacketSidebarVisible = true
            } completion: {
                if isDuoPacketSidebarVisible { isDuoSidebarToolbarShown = true }
            }
        } else {
            isDuoSidebarToolbarShown = false
            withAnimation(.smooth(duration: 0.3)) {
                isDuoPacketSidebarVisible = false
            }
        }
    }

    private func updateRegularColumns() {
        columnVisibility = prefersPacketSidebar ? .all : .detailOnly
    }

    private func toggleRegularSidebar() {
        columnVisibility = columnVisibility == .detailOnly ? .all : .detailOnly
    }

    private func compactPacketSheetDismissed() {
        if !replayStore.isReplayActive {
            selectedLivePacketGroupID = nil
        }
    }
}

private struct MapLivePacketSidebar: View {
    @Binding var selectedGroupID: LiveObservationGroup.ID?
    var close: (() -> Void)?
    var toggleSidebar: (() -> Void)?
    var routeReplayStarted: ((LiveObservationGroup.ID) -> Void)?
    var detailPresentationChanged: ((Bool) -> Void)?

    @Environment(LiveFeedService.self) private var liveFeed

    var body: some View {
        if liveFeed.usesBrowseSnapshots {
            BeaconPacketSidebar(
                close: close, toggleSidebar: toggleSidebar,
                routeReplayStarted: routeReplayStarted,
                detailPresentationChanged: detailPresentationChanged
            )
        } else {
            CoreScopeMapLivePacketSidebar(
                selectedGroupID: $selectedGroupID, close: close, toggleSidebar: toggleSidebar,
                routeReplayStarted: routeReplayStarted, detailPresentationChanged: detailPresentationChanged
            )
        }
    }
}

private struct BeaconPacketSidebar: View {
    var close: (() -> Void)?
    var toggleSidebar: (() -> Void)?
    var routeReplayStarted: ((LiveObservationGroup.ID) -> Void)?
    var detailPresentationChanged: ((Bool) -> Void)?
    @Environment(\.showsPacketSidebarToolbarItems) private var showsToolbarItems

    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(AnalyzerSettings.self) private var settings
    @State private var consumerID = UUID()
    @State private var filterType: Int?
    @State private var showsFilters = false
    @State private var detailRow: BeaconLiveRecovery.Row?

    private var packetDetailsPresented: Binding<Bool> {
        Binding(
            get: { detailRow != nil },
            set: { isPresented in
                if !isPresented { detailRow = nil }
                detailPresentationChanged?(isPresented)
            }
        )
    }

    private struct DemandKey: Hashable {
        let sessionID: UUID
        let filter: LiveBrowseFilter
    }

    private var filter: LiveBrowseFilter {
        LiveBrowseFilter(region: regionFilter.selectedRegion?.uppercased(), payloadType: filterType)
    }

    var body: some View {
        let snapshot = liveFeed.browseSnapshots[filter]
        List {
            LivePacketFeedHeader(
                isConnected: liveFeed.isConnected,
                isPaused: false,
                isCompact: true,
                transmissionCount: snapshot?.rows.count ?? 0,
                observationCount: snapshot?.rows.reduce(0) { $0 + $1.observationCount } ?? 0,
                scope: regionFilter.selectedRegion.map(regionFilter.label(for:)) ?? String(localized: "Entire network")
            )
            .packetFeedListRow(top: 8, bottom: 8)
            Section {
                if let error = liveFeed.recoveryErrors[filter] {
                    Label("History refresh failed", systemImage: "exclamationmark.triangle")
                        .packetFeedListRow()
                    Text(error).font(.caption).foregroundStyle(.secondary)
                        .packetFeedListRow()
                }
                if snapshot?.possiblyIncomplete == true {
                    Text("Recent history may be incomplete.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .packetFeedListRow()
                }
            }

            Section("Recent Packets") {
                if let snapshot, !snapshot.rows.isEmpty {
                    ForEach(snapshot.rows, id: \.hash) { row in
                        Button {
                            detailPresentationChanged?(true)
                            detailRow = row
                        } label: {
                            BeaconPacketSummaryRow(row: row)
                        }
                        .buttonStyle(.plain)
                        .packetFeedListRow()
                        .accessibilityHint("Show packet details")
                    }
                } else {
                    ContentUnavailableView(
                        liveFeed.isConnected ? "Waiting for Packets" : "Not Connected",
                        systemImage: "dot.radiowaves.left.and.right"
                    )
                }
            }
        }
        .adaptiveSidebarListStyle(meshBackground: true)
        .navigationTitle("Live Packets")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: packetDetailsPresented) {
            if let detailRow {
                BeaconPacketSummary(row: detailRow, replayStarted: {
                    detailPresentationChanged?(false)
                    routeReplayStarted?(detailRow.hash)
                })
            }
        }
        .toolbar {
            if let close {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: close) { Image(systemName: "xmark") }
                        .accessibilityLabel("Close live packets")
                }
            }
            if showsToolbarItems { ToolbarItem(placement: .topBarTrailing) {
                Button { showsFilters = true } label: {
                    Image(systemName: filterType == nil
                          ? "line.3.horizontal.decrease.circle"
                          : "line.3.horizontal.decrease.circle.fill")
                }
                .accessibilityLabel("Filter live packets")
            } }
            if let toggleSidebar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: toggleSidebar) { Image(systemName: "list.bullet.rectangle") }
                        .accessibilityLabel("Hide live packets")
                }
            }
        }
        .sheet(isPresented: $showsFilters) {
            LivePacketFiltersSheet(filterType: $filterType)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .task(id: DemandKey(sessionID: settings.sourceSession.id, filter: filter)) {
            let regions = filter.region.map { Set([$0]) } ?? []
            let payloads = filter.payloadType.map { Set([$0]) } ?? []
            liveFeed.setDemand(.init(subscription: .init(regions: regions, payloadTypes: payloads)), for: consumerID)
        }
        .onDisappear {
            liveFeed.setDemand(nil, for: consumerID)
        }
    }
}

private struct BeaconPacketSummaryRow: View {
    let row: BeaconLiveRecovery.Row

    var body: some View {
        LivePacketCard(
            payloadTypeName: PayloadType.name(for: row.payloadType),
            hopCount: 0,
            observationCount: row.observationCount,
            region: nil,
            preview: row.hash,
            receivedAt: row.lastHeard
        )
    }
}

private struct BeaconPacketSummary: View {
    let row: BeaconLiveRecovery.Row
    var replayStarted: (() -> Void)?
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(PacketReplayStore.self) private var replayStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel = BeaconPacketDetailViewModel()
    @State private var selectedRouteIndex = 0
    @State private var retryID = UUID()

    private struct LoadKey: Hashable {
        let sourceSessionID: UUID
        let retryID: UUID
    }

    var body: some View {
        List {
            if viewModel.isLoading {
                ProgressView("Loading packet…")
            }
            if let error = viewModel.errorMessage {
                Section {
                    Text(error).foregroundStyle(.secondary)
                    Button("Retry") { retryID = UUID() }
                }
            }
            Section("Packet") {
                PacketDetailField(title: "Type", value: PayloadType.name(for: row.payloadType))
                PacketDetailField(title: "Hash", value: row.hash, monospaced: true)
                PacketDetailField(title: "Observations", value: max(viewModel.detail?.observationCount ?? 0, row.observationCount).formatted())
            }
            Section("Heard") {
                LabeledContent("First seen") {
                    if let date = viewModel.detail?.packet.firstSeen ?? row.firstSeen {
                        Text(date, format: .dateTime)
                    } else {
                        Text("Unknown")
                    }
                }
                LabeledContent("Last heard") {
                    Text(max(viewModel.detail?.packet.timestamp ?? row.lastHeard, row.lastHeard), format: .dateTime)
                }
            }
            if let detail = viewModel.detail {
                BeaconPacketObservations(observations: detail.observations)
                Section("Route") {
                    if viewModel.routes.isEmpty {
                        Text("No fully resolved route is available. Uncertain hops are not replayed.")
                            .foregroundStyle(.secondary)
                    } else {
                        if viewModel.routes.count > 1 {
                            RoutePicker(options: viewModel.routes, selection: $selectedRouteIndex)
                                .tint(NodeScopeStyle.signal)
                        }
                        if viewModel.routes.indices.contains(selectedRouteIndex) {
                            RouteHearersView(option: viewModel.routes[selectedRouteIndex])
                            ForEach(viewModel.routes[selectedRouteIndex].keys.indices, id: \.self) { index in
                                PacketDetailField(
                                    title: "Hop \(index + 1)",
                                    value: viewModel.routes[selectedRouteIndex].keys[index],
                                    monospaced: true
                                )
                            }
                        }
                        Button {
                            guard viewModel.belongsToSource(settings.sourceSession.id) else { return }
                            replayStore.replay(
                                routes: viewModel.routes, selectedIndex: selectedRouteIndex,
                                packetHash: detail.packet.hash, observedAt: detail.packet.timestamp,
                                snr: detail.packet.snr, rssi: detail.packet.rssi, sender: nil, messageText: nil
                            )
                            dismiss()
                            replayStarted?()
                        } label: {
                            Label("Replay on Map", systemImage: "play.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(NodeScopeStyle.activity)
                        .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
                        .disabled(!viewModel.belongsToSource(settings.sourceSession.id))
                    }
                }
            }
        }
        .meshPageBackground()
        .navigationTitle("Packet Details")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if horizontalSizeClass == .regular {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Close packet details")
                }
            }
        }
        .task(id: LoadKey(sourceSessionID: settings.sourceSession.id, retryID: retryID)) {
            selectedRouteIndex = 0
            await viewModel.load(hash: row.hash, sourceSessionID: settings.sourceSession.id, service: liveFeed)
        }
    }
}

private struct BeaconPacketObservations: View {
    let observations: [PacketObservation]

    var body: some View {
        Section("Observations") {
            ForEach(observations) { observation in
                VStack(alignment: .leading, spacing: 5) {
                    Text(observation.observerName ?? String(localized: "Unknown observer"))
                        .font(.body.weight(.semibold))
                    if let region = observation.observerIata {
                        Label(region, systemImage: "globe.americas.fill")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(observation.timestamp, format: .dateTime)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 12) {
                            PacketObservationSignal(snr: observation.snr, rssi: observation.rssi)
                        }
                        VStack(alignment: .leading, spacing: 5) {
                            PacketObservationSignal(snr: observation.snr, rssi: observation.rssi)
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct PacketObservationSignal: View {
    let snr: Double?
    let rssi: Double?

    var body: some View {
        if let snr {
            Label("\(snr.formatted(.number.precision(.fractionLength(1)))) dB", systemImage: "waveform")
                .accessibilityLabel("SNR: \(snr.formatted()) decibels")
        }
        if let rssi {
            Label("\(rssi.formatted(.number.precision(.fractionLength(1)))) dBm", systemImage: "antenna.radiowaves.left.and.right")
                .accessibilityLabel("RSSI: \(rssi.formatted()) dBm")
        }
    }
}

private struct PacketDetailField: View {
    let title: LocalizedStringKey
    let value: String
    var monospaced = false

    var body: some View {
        LabeledContent {
            Text(value)
                .font(monospaced ? .body.monospaced() : .body)
                .foregroundStyle(.primary)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        } label: {
            Text(title).foregroundStyle(.secondary)
        }
    }
}

private struct CoreScopeMapLivePacketSidebar: View {
    @Binding var selectedGroupID: LiveObservationGroup.ID?
    var close: (() -> Void)?
    var toggleSidebar: (() -> Void)?
    var routeReplayStarted: ((LiveObservationGroup.ID) -> Void)?
    var detailPresentationChanged: ((Bool) -> Void)?
    @Environment(\.showsPacketSidebarToolbarItems) private var showsToolbarItems

    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @Environment(PacketReplayStore.self) private var replayStore
    @State private var feedModel = LiveObservationFeedModel()
    @State private var filterType: Int?
    @State private var isFiltersPresented = false
    @State private var keepsSelectionWhenReplayStops = false
    @State private var detailGroup: LiveObservationGroup?

    var body: some View {
        List {
            LivePacketFeedHeader(
                isConnected: liveFeed.isConnected,
                isPaused: isFeedPaused,
                isCompact: true,
                transmissionCount: feedModel.groups.count,
                observationCount: feedModel.groups.reduce(0) { $0 + $1.observationCount },
                scope: replayStore.isReplayActive
                    ? String(localized: "Route replay")
                    : selectedGroupID != nil
                        ? String(localized: "Packet selected")
                        : regionFilter.selectedRegion.map(regionFilter.label(for:)) ?? String(localized: "Entire network")
            )
            .packetFeedListRow(top: 14, bottom: 10)

            if isFeedPaused {
                Button(action: resumeLiveFeed) {
                    Label("Resume Live", systemImage: "play.fill")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.borderless)
                .packetFeedListRow()
            }

            Section {
                ForEach(feedModel.groups) { group in
                    VStack(spacing: 6) {
                        Button {
                            select(group)
                        } label: {
                            LivePacketRow(group: group, isSelected: selectedGroupID == group.id)
                        }
                        .buttonStyle(.plain)

                        if selectedGroupID == group.id {
                            HStack(spacing: 8) {
                                if !group.replayRoutes.isEmpty {
                                    Button {
                                        replay(group)
                                    } label: {
                                        Label("Replay", systemImage: "play.fill")
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(.primary)
                                            .frame(maxWidth: .infinity)
                                            .padding(.vertical, 10)
                                            .instrumentCard(isSelected: true)
                                    }
                                    .buttonStyle(.plain)
                                }

                                Button {
                                    detailGroup = group
                                    detailPresentationChanged?(true)
                                } label: {
                                    Label("Show Details", systemImage: "info.circle")
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(.primary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .instrumentCard(isSelected: true)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Show selected packet details")
                            }
                            .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    .packetFeedListRow()
                    .accessibilityHint(accessibilityHint(for: group))
                }
            } header: {
                Text(isFeedPaused ? "Paused Traffic" : "Incoming Traffic")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
            }
        }
        .adaptiveSidebarListStyle(meshBackground: true)
        .navigationTitle("Live Packets")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: packetDetailsPresented) {
            if let detailGroup {
                LivePacketDetailScreen(group: detailGroup)
            }
        }
        .toolbar {
            if let close {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: close) {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Close live packets")
                }
            }
            if showsToolbarItems { ToolbarItem(placement: .topBarTrailing) {
                Button { isFiltersPresented = true } label: {
                    Image(systemName: filterType == nil
                        ? "line.3.horizontal.decrease.circle"
                        : "line.3.horizontal.decrease.circle.fill")
                        .foregroundStyle(.primary)
                }
                .accessibilityLabel("Filter live packets")
            } }
            if let toggleSidebar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: toggleSidebar) {
                        Image(systemName: "list.bullet.rectangle")
                    }
                    .accessibilityLabel("Hide live packets")
                }
            }
        }
        .overlay {
            if !isFeedPaused && feedModel.groups.isEmpty && (regionFilter.selectedRegion == nil || observerRegionLookup.isLoaded) {
                ContentUnavailableView(
                    liveFeed.isConnected ? "Waiting for Packets" : "Not Connected",
                    systemImage: "dot.radiowaves.left.and.right"
                )
            }
        }
        .sheet(isPresented: $isFiltersPresented) {
            LivePacketFiltersSheet(filterType: $filterType)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .task { rebuildGroups() }
        .onChange(of: liveFeed.eventSequence) { rebuildGroupsUnlessPaused() }
        .onChange(of: regionFilter.selectedRegion) { resumeLiveFeed() }
        .onChange(of: filterType) { resumeLiveFeed() }
        .onChange(of: observerRegionLookup.isLoaded) { rebuildGroupsUnlessPaused() }
        .onChange(of: replayStore.isReplayActive) { _, isActive in
            if !isActive {
                if keepsSelectionWhenReplayStops {
                    keepsSelectionWhenReplayStops = false
                    return
                }
                selectedGroupID = nil
                rebuildGroups()
            }
        }
    }

    private func select(_ group: LiveObservationGroup) {
        if selectedGroupID == group.id {
            replayStore.stopReplay()
            selectedGroupID = nil
            rebuildGroups()
            return
        }

        selectedGroupID = group.id
        if replayStore.isReplayActive {
            keepsSelectionWhenReplayStops = true
            replayStore.stopReplay()
        }
    }

    private func replay(_ group: LiveObservationGroup) {
        guard !group.replayRoutes.isEmpty else { return }
        detailGroup = nil
        let data = group.latestData
        replayStore.replay(
            routes: group.replayRoutes,
            packetHash: data.hash ?? group.id,
            observedAt: group.latestReceivedAt,
            snr: data.snr,
            rssi: data.rssi,
            sender: data.decoded?.payload?.name,
            messageText: data.decoded?.payload?.text
        )
        routeReplayStarted?(group.id)
    }

    private var packetDetailsPresented: Binding<Bool> {
        Binding(
            get: { detailGroup != nil },
            set: { isPresented in
                if !isPresented {
                    detailGroup = nil
                    detailPresentationChanged?(false)
                }
            }
        )
    }

    private var isFeedPaused: Bool {
        selectedGroupID != nil || replayStore.isReplayActive
    }

    private func rebuildGroupsUnlessPaused() {
        guard !isFeedPaused else { return }
        rebuildGroups()
    }

    private func accessibilityHint(for group: LiveObservationGroup) -> LocalizedStringKey {
        if selectedGroupID == group.id {
            return "Returns to live traffic"
        }
        if group.replayRoutes.isEmpty {
            return "Pauses the feed and makes packet details available"
        }
        return "Pauses the feed and reveals replay and packet detail actions"
    }

    private func resumeLiveFeed() {
        keepsSelectionWhenReplayStops = false
        replayStore.stopReplay()
        selectedGroupID = nil
        rebuildGroups()
    }

    private func rebuildGroups() {
        feedModel.rebuild(
            events: liveFeed.recentEvents,
            filterType: filterType,
            selectedRegion: regionFilter.selectedRegion,
            regionByObserverID: observerRegionLookup.iataById
        )
    }

    private var selectedGroup: LiveObservationGroup? {
        guard let selectedGroupID else { return nil }
        return feedModel.groups.first { $0.id == selectedGroupID }
    }
}

struct PacketFeedScreen: View {
    @Environment(LiveFeedService.self) private var liveFeed
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @State private var feedModel = LiveObservationFeedModel()
    @State private var filterType: Int?
    @State private var isFiltersPresented = false

    var body: some View {
        List {
            LivePacketFeedHeader(
                isConnected: liveFeed.isConnected,
                isPaused: false,
                isCompact: false,
                transmissionCount: feedModel.groups.count,
                observationCount: feedModel.groups.reduce(0) { $0 + $1.observationCount },
                scope: regionFilter.selectedRegion.map(regionFilter.label(for:)) ?? String(localized: "Entire network")
            )
            .packetFeedListRow(top: 14, bottom: 10)

            if regionFilter.selectedRegion != nil && !observerRegionLookup.isLoaded {
                LoadingIndicator(title: "Loading region…")
                    .packetFeedListRow(top: 0, bottom: 8)
            }

            Section {
                ForEach(feedModel.groups) { group in
                    NavigationLink {
                        LivePacketDetailScreen(group: group)
                    } label: {
                        LivePacketRow(group: group)
                    }
                    .buttonStyle(.plain)
                    .packetFeedListRow()
                }
            } header: {
                Text("Incoming Traffic")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
            }
        }
        .scrollContentBackground(.hidden)
        .background(MeshBackground(subdued: true).ignoresSafeArea())
        .listStyle(.plain)
        .adaptiveContentWidth()
        .navigationTitle("Live Packets")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    isFiltersPresented = true
                } label: {
                    Image(systemName: activeFilterCount == 0
                        ? "line.3.horizontal.decrease.circle"
                        : "line.3.horizontal.decrease.circle.fill")
                        .foregroundStyle(.primary)
                }
                .accessibilityLabel("Filter live packets")
                .accessibilityValue(activeFilterCount == 0 ? "No filters active" : "\(activeFilterCount) active")
            }
        }
        .overlay {
            if feedModel.groups.isEmpty && (regionFilter.selectedRegion == nil || observerRegionLookup.isLoaded) {
                ContentUnavailableView {
                    Label(
                        liveFeed.isConnected ? "Waiting for Packets" : "Not Connected",
                        systemImage: "dot.radiowaves.left.and.right"
                    )
                } description: {
                    if !liveFeed.isConnected {
                        Text("NodeScope will resume the live feed when the analyzer reconnects.")
                    } else if liveFeed.usesBrowseSnapshots {
                        Text("New mesh traffic will appear here as the analyzer receives it. Beacon retains up to seven days of packet history, and quiet regions may have no recent traffic.")
                    } else {
                        Text("New mesh traffic will appear here as the analyzer receives it.")
                    }
                }
            }
        }
        .sheet(isPresented: $isFiltersPresented) {
            LivePacketFiltersSheet(filterType: $filterType)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .task { rebuildGroups() }
        .onChange(of: liveFeed.eventSequence) { rebuildGroups() }
        .onChange(of: regionFilter.selectedRegion) { rebuildGroups() }
        .onChange(of: filterType) { rebuildGroups() }
        .onChange(of: observerRegionLookup.isLoaded) { rebuildGroups() }
    }

    private func rebuildGroups() {
        feedModel.rebuild(
            events: liveFeed.recentEvents,
            filterType: filterType,
            selectedRegion: regionFilter.selectedRegion,
            regionByObserverID: observerRegionLookup.iataById
        )
    }

    private var activeFilterCount: Int {
        (regionFilter.selectedRegion == nil ? 0 : 1) + (filterType == nil ? 0 : 1)
    }
}

@MainActor
@Observable
private final class LiveObservationFeedModel {
    private(set) var groups: [LiveObservationGroup] = []

    func rebuild(
        events: [LiveEnvelope],
        filterType: Int?,
        selectedRegion: String?,
        regionByObserverID: [String: String]
    ) {
        var rebuilt: [LiveObservationGroup] = []

        for event in events.reversed() {
            guard event.type == "packet", let data = event.data else { continue }
            if let filterType, data.decoded?.header?.payloadType != filterType { continue }

            let region = data.observerId.flatMap { regionByObserverID[$0] }
            // A quick pick selects several comma-separated codes.
            if let selectedRegion, !selectedRegion.split(separator: ",").contains(where: { $0.caseInsensitiveCompare(region ?? "") == .orderedSame }) { continue }

            let hash = data.hash?.trimmingCharacters(in: .whitespacesAndNewlines)
            let hasHash = !(hash?.isEmpty ?? true)
            let key = hasHash ? hash! : "event-\(data.id)-\(event.receivedAt.timeIntervalSinceReferenceDate)"

            if hasHash,
               let index = rebuilt.lastIndex(where: {
                   $0.id == key && event.receivedAt.timeIntervalSince($0.firstReceivedAt) <= 30
               }) {
                rebuilt[index].append(event, region: region)
            } else {
                rebuilt.append(LiveObservationGroup(id: key, event: event, region: region))
            }
        }

        groups = Array(rebuilt.sorted { $0.latestReceivedAt > $1.latestReceivedAt }.prefix(25))
    }
}

private struct LiveObservationGroup: Identifiable {
    let id: String
    let firstReceivedAt: Date
    private(set) var latestReceivedAt: Date
    private(set) var observations: [LiveEnvelope]
    private(set) var region: String?
    /// Each observation's observer region, aligned with `observations`.
    private(set) var observationRegions: [String?]

    init(id: String, event: LiveEnvelope, region: String?) {
        self.id = id
        firstReceivedAt = event.receivedAt
        latestReceivedAt = event.receivedAt
        observations = [event]
        observationRegions = [region]
        self.region = region
    }

    mutating func append(_ event: LiveEnvelope, region: String?) {
        observations.append(event)
        observationRegions.append(region)
        latestReceivedAt = max(latestReceivedAt, event.receivedAt)
        self.region = region ?? self.region
    }

    var latestData: LivePacketData {
        observations.last!.data!
    }

    var observationCount: Int {
        max(observations.count, observations.compactMap { $0.data?.observationCount }.max() ?? 0)
    }

    var payloadTypeName: String {
        latestData.decoded?.header?.payloadTypeName ?? "UNKNOWN"
    }

    var longestPath: [String] {
        observations.compactMap { $0.data }.map(Self.path).max { $0.count < $1.count } ?? []
    }

    /// The packet's distinct routes, each named by the observers that heard it.
    var replayRoutes: [RouteOption] {
        RouteOptions.make(from: zip(observations, observationRegions).compactMap { event, region in
            guard let data = event.data else { return nil }
            let name = data.observerName ?? data.observerId
            return HeardPath(
                path: data.resolvedPath ?? [],
                hearing: name.map {
                    RouteHearing(observer: $0, region: region, snr: data.snr, rssi: data.rssi, observerId: data.observerId)
                }
            )
        })
    }

    var hopCount: Int { longestPath.count }

    var preview: String {
        let payload = latestData.decoded?.payload
        return [payload?.text, payload?.name, payload?.channel, latestData.observerName]
            .compactMap { value in
                guard let value, !value.isEmpty else { return nil }
                return value
            }
            .first ?? String(id.prefix(12))
    }

    static func path(for data: LivePacketData) -> [String] {
        if let hops = data.decoded?.path?.hops, !hops.isEmpty { return hops }
        guard let pathJson = data.pathJson,
              let jsonData = pathJson.data(using: .utf8),
              let hops = try? JSONDecoder().decode([String].self, from: jsonData) else { return [] }
        return hops
    }

}

private struct LivePacketFeedHeader: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let isConnected: Bool
    let isPaused: Bool
    let isCompact: Bool
    let transmissionCount: Int
    let observationCount: Int
    let scope: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 7) {
                Circle()
                    .fill(isPaused ? NodeScopeStyle.activity : isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                    .frame(width: 8, height: 8)
                Text(isPaused
                    ? "Live feed paused"
                    : isConnected ? "Listening for live traffic" : "Reconnecting to analyzer")
                    .font(.subheadline.weight(.semibold))
            }
            if isCompact {
                LivePacketCompactSummary(
                    transmissionCount: transmissionCount,
                    observationCount: observationCount,
                    scope: scope
                )
            } else {
                Group {
                    if dynamicTypeSize.isAccessibilitySize {
                        VStack(alignment: .leading, spacing: 8) {
                            feedSummary
                        }
                    } else {
                        HStack(spacing: 10) {
                            feedSummary
                        }
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .padding(isCompact ? 10 : 14)
        .instrumentCard()
    }

    @ViewBuilder
    private var feedSummary: some View {
        Label(scope, systemImage: "globe.americas.fill")
        Label("\(transmissionCount) transmissions", systemImage: "waveform.path.ecg")
        Label("\(observationCount) observations", systemImage: "eye")
    }
}

private struct LivePacketCompactSummary: View {
    let transmissionCount: Int
    let observationCount: Int
    let scope: String

    var body: some View {
        HStack(spacing: 10) {
            Label(scope, systemImage: "globe.americas.fill")
                .lineLimit(1)
            Spacer(minLength: 0)
            Label("\(transmissionCount) TX", systemImage: "waveform.path.ecg")
                .accessibilityLabel("\(transmissionCount) transmissions")
            Label("\(observationCount) OBS", systemImage: "eye")
                .accessibilityLabel("\(observationCount) observations")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}

private struct LivePacketRow: View {
    let group: LiveObservationGroup
    var isSelected = false

    var body: some View {
        LivePacketCard(
            payloadTypeName: group.payloadTypeName,
            hopCount: group.hopCount,
            observationCount: group.observationCount,
            region: group.region,
            preview: group.preview,
            receivedAt: group.latestReceivedAt,
            isSelected: isSelected
        )
    }
}

private struct LivePacketCard: View {
    let payloadTypeName: String
    let hopCount: Int
    let observationCount: Int
    let region: String?
    let preview: String
    let receivedAt: Date
    var isSelected = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.title3.weight(.bold))
                .foregroundStyle(color)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 9) {
                HStack(spacing: 8) {
                    Text(payloadTypeName)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(color)
                        .lineLimit(1)

                    if hopCount > 0 {
                        Label("\(hopCount)", systemImage: "arrow.right")
                            .liveObservationBadge()
                    }

                    if observationCount > 1 {
                        Label("\(observationCount)", systemImage: "eye.fill")
                            .liveObservationBadge(color: .purple)
                    }

                    if let region {
                        Text(region)
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Color.indigo, in: RoundedRectangle(cornerRadius: 5))
                    }

                    Spacer(minLength: 0)
                }

                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        packetPreview
                        packetTime
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        packetPreview
                        packetTime
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 14)
        .frame(minHeight: 82)
        .overlay {
            RoundedRectangle(cornerRadius: NodeScopeStyle.cornerRadius, style: .continuous)
                .fill(color)
                .mask(alignment: .leading) {
                    Rectangle()
                        .frame(width: 4)
                }
                .allowsHitTesting(false)
        }
        .instrumentCard(isSelected: isSelected)
        .accessibilityElement(children: .combine)
    }

    private var packetPreview: some View {
        Text(preview)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .lineLimit(2)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var packetTime: some View {
        Text(receivedAt, style: .relative)
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var symbol: String {
        switch payloadTypeName {
        case "ADVERT": "antenna.radiowaves.left.and.right"
        case "GRP_TXT", "TXT_MSG": "message"
        case "TRACE", "PATH": "point.3.connected.trianglepath.dotted"
        default: "shippingbox"
        }
    }

    private var color: Color {
        switch payloadTypeName {
        case "ADVERT": .green
        case "GRP_TXT", "TXT_MSG": NodeScopeStyle.signal
        case "TRACE", "PATH": .orange
        default: .secondary
        }
    }
}

private extension View {
    func liveObservationBadge(color: Color = NodeScopeStyle.signal) -> some View {
        font(.caption2.weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(color.opacity(0.12), in: Capsule())
    }
}

private struct LivePacketFiltersSheet: View {
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(\.dismiss) private var dismiss
    @Binding var filterType: Int?

    var body: some View {
        NavigationStack {
            Form {
                if regionFilter.availablePicks.isEmpty {
                    Section("Region") {
                        regionButton(code: nil, title: "Entire Network")
                        ForEach(regionFilter.options, id: \.self) { code in
                            regionButton(code: code, title: regionFilter.label(for: code))
                        }
                    }
                } else {
                    Section("Region") {
                        regionButton(code: nil, title: "Entire Network")
                    }
                    Section("Region groups") {
                        ForEach(regionFilter.availablePicks, id: \.self) { pick in
                            selectionButton(title: pick.name, isSelected: regionFilter.isSelected(pick)) {
                                regionFilter.select(pick)
                            }
                        }
                    }
                    Section("Regions") {
                        ForEach(regionFilter.options, id: \.self) { code in
                            regionButton(code: code, title: regionFilter.label(for: code))
                        }
                    }
                }

                Section("Packet Type") {
                    packetTypeButton(type: nil, title: "All Packet Types")
                    ForEach(PayloadType.allCases, id: \.rawValue) { type in
                        packetTypeButton(type: type.rawValue, title: type.name)
                    }
                }
            }
            .meshPageBackground()
            .navigationTitle("Live Packet Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Reset") {
                        regionFilter.selectedRegion = nil
                        filterType = nil
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
    }

    private func regionButton(code: String?, title: String) -> some View {
        selectionButton(title: title, isSelected: regionFilter.selectedRegion == code) {
            regionFilter.selectedRegion = code
        }
    }

    private func packetTypeButton(type: Int?, title: String) -> some View {
        selectionButton(title: title, isSelected: filterType == type) {
            filterType = type
        }
    }

    private func selectionButton(title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                    .foregroundStyle(.primary)
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(NodeScopeStyle.signal)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct LivePacketDetailScreen: View {
    let group: LiveObservationGroup
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(PacketReplayStore.self) private var replayStore
    @State private var selectedRouteIndex = 0

    private var data: LivePacketData { group.latestData }

    var body: some View {
        ScrollViewReader { scroller in
        List {
            Section("Packet") {
                detailRow("Type", value: data.decoded?.header?.payloadTypeName ?? "Unknown")
                detailRow("Hash", value: data.hash ?? "Unavailable", monospaced: true)
                if let version = data.decoded?.header?.payloadVersion {
                    detailRow("Payload Version", value: "\(version)")
                }
                detailRow("Observations", value: "\(group.observationCount)")
                detailRow("Received", value: group.latestReceivedAt.formatted(date: .omitted, time: .standard))
            }

            if let preview = data.decoded?.payload?.text ?? data.decoded?.payload?.name, !preview.isEmpty {
                Section("Payload") {
                    Text(preview)
                        .textSelection(.enabled)
                }
            }

            Section("Observations") {
                ForEach(Array(group.observations.enumerated()), id: \.offset) { _, event in
                    if let observation = event.data {
                        VStack(alignment: .leading, spacing: 5) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(observation.observerName ?? observation.observerId ?? "Unknown observer")
                                    .font(.body.weight(.semibold))
                                Spacer(minLength: 8)
                                if let index = RouteOptions.index(for: observation.resolvedPath ?? [], in: replayRoutes) {
                                    RouteTag(isShown: index == selectedRouteIndex, shownText: "Shown below") {
                                        selectedRouteIndex = index
                                        withAnimation(.easeInOut(duration: 0.3)) {
                                            scroller.scrollTo(routeSectionID, anchor: .top)
                                        }
                                    }
                                }
                            }
                            HStack(spacing: 12) {
                                if let snr = observation.snr {
                                    Label("\(snr.formatted(.number.precision(.fractionLength(1)))) dB", systemImage: "waveform")
                                }
                                if let rssi = observation.rssi {
                                    Label("\(rssi.formatted(.number.precision(.fractionLength(1)))) dBm", systemImage: "antenna.radiowaves.left.and.right")
                                }
                                let hops = LiveObservationGroup.path(for: observation).count
                                if hops > 0 {
                                    Label("\(hops) hops", systemImage: "arrow.right")
                                }
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            if !routeHops.isEmpty {
                Section("Route") {
                    if replayRoutes.count > 1 {
                        RoutePicker(options: replayRoutes, selection: $selectedRouteIndex)
                            .tint(NodeScopeStyle.signal)
                    }
                    if replayRoutes.indices.contains(selectedRouteIndex) {
                        RouteHearersView(option: replayRoutes[selectedRouteIndex])
                    }

                    ForEach(routeHops) { hop in
                        HStack {
                            Text("Hop \(hop.position)")
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text(hop.value)
                                .monospaced()
                        }
                    }

                    if !replayRoutes.isEmpty {
                        Button(action: replayOnMap) {
                            Label("Replay on Map", systemImage: "play.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(NodeScopeStyle.activity)
                        .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
                    }
                }
                .id(routeSectionID)
            }

            if let raw = data.raw, !raw.isEmpty {
                Section("Raw Packet") {
                    Text(raw)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                }
            }
        }
        }
        .meshPageBackground()
        .navigationTitle("Packet Details")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if horizontalSizeClass == .regular {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Close packet details")
                }
            }
        }
    }

    private var routeHops: [LiveRouteHop] {
        group.longestPath.enumerated().map { index, value in
            LiveRouteHop(id: "\(index)|\(value)", position: index + 1, value: value)
        }
    }

    private let routeSectionID = "live-packet-route"

    private var replayRoutes: [RouteOption] {
        group.replayRoutes
    }

    private func replayOnMap() {
        replayStore.replay(
            routes: replayRoutes,
            selectedIndex: selectedRouteIndex,
            packetHash: data.hash ?? group.id,
            observedAt: group.latestReceivedAt,
            snr: data.snr,
            rssi: data.rssi,
            sender: data.decoded?.payload?.name,
            messageText: data.decoded?.payload?.text
        )
    }

    private func detailRow(_ label: LocalizedStringKey, value: String, monospaced: Bool = false) -> some View {
        PacketDetailField(title: label, value: value, monospaced: monospaced)
    }
}

private struct LiveRouteHop: Identifiable {
    let id: String
    let position: Int
    let value: String
}

private extension View {
    func packetFeedListRow(top: CGFloat = 6, bottom: CGFloat = 6) -> some View {
        listRowInsets(EdgeInsets(top: top, leading: 20, bottom: bottom, trailing: 20))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

extension EnvironmentValues {
    /// Off while the Duo's packet sidebar slides: its toolbar buttons sit in the side column,
    /// which does not move with it.
    @Entry var showsPacketSidebarToolbarItems = true
}
