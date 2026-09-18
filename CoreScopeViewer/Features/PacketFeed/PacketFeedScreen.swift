import SwiftUI
import Observation

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
        .background(NodeScopeBackground())
        .listStyle(.plain)
        .adaptiveContentWidth()
        .contentMargins(.bottom, 104, for: .scrollContent)
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
                    Text(liveFeed.isConnected
                        ? "New mesh traffic will appear here as the analyzer receives it."
                        : "NodeScope will resume the live feed when the analyzer reconnects.")
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
            if let selectedRegion, region != selectedRegion { continue }

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

    init(id: String, event: LiveEnvelope, region: String?) {
        self.id = id
        firstReceivedAt = event.receivedAt
        latestReceivedAt = event.receivedAt
        observations = [event]
        self.region = region
    }

    mutating func append(_ event: LiveEnvelope, region: String?) {
        observations.append(event)
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
    let transmissionCount: Int
    let observationCount: Int
    let scope: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 7) {
                Circle()
                    .fill(isConnected ? NodeScopeStyle.healthy : NodeScopeStyle.activity)
                    .frame(width: 8, height: 8)
                Text(isConnected ? "Listening for live traffic" : "Reconnecting to analyzer")
                    .font(.subheadline.weight(.semibold))
            }
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
        .padding(14)
        .instrumentCard()
    }

    @ViewBuilder
    private var feedSummary: some View {
        Label(scope, systemImage: "globe.americas.fill")
        Label("\(transmissionCount) transmissions", systemImage: "waveform.path.ecg")
        Label("\(observationCount) observations", systemImage: "eye")
    }
}

private struct LivePacketRow: View {
    let group: LiveObservationGroup

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.title3.weight(.bold))
                .foregroundStyle(color)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 9) {
                HStack(spacing: 8) {
                    Text(group.payloadTypeName)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(color)
                        .lineLimit(1)

                    if group.hopCount > 0 {
                        Label("\(group.hopCount)", systemImage: "arrow.right")
                            .liveObservationBadge()
                    }

                    if group.observationCount > 1 {
                        Label("\(group.observationCount)", systemImage: "eye.fill")
                            .liveObservationBadge(color: .purple)
                    }

                    if let region = group.region {
                        Text(region)
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Color.indigo, in: RoundedRectangle(cornerRadius: 5))
                    }

                    Spacer(minLength: 0)
                }

                HStack(spacing: 8) {
                    Text(group.preview)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Text(group.latestReceivedAt, style: .relative)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 14)
        .frame(minHeight: 82)
        .overlay(alignment: .leading) {
            Capsule()
                .fill(color)
                .frame(width: 4)
                .padding(.vertical, 5)
        }
        .instrumentCard()
        .accessibilityElement(children: .combine)
    }

    private var symbol: String {
        switch group.payloadTypeName {
        case "ADVERT": "antenna.radiowaves.left.and.right"
        case "GRP_TXT", "TXT_MSG": "message"
        case "TRACE", "PATH": "point.3.connected.trianglepath.dotted"
        default: "shippingbox"
        }
    }

    private var color: Color {
        switch group.payloadTypeName {
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
                Section("Region") {
                    regionButton(code: nil, title: "Entire Network")
                    ForEach(regionFilter.options, id: \.self) { code in
                        regionButton(code: code, title: regionFilter.label(for: code))
                    }
                }

                Section("Packet Type") {
                    packetTypeButton(type: nil, title: "All Packet Types")
                    ForEach(PayloadType.allCases, id: \.rawValue) { type in
                        packetTypeButton(type: type.rawValue, title: type.name)
                    }
                }
            }
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
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(NodeScopeStyle.signal)
                }
            }
        }
    }
}

private struct LivePacketDetailScreen: View {
    let group: LiveObservationGroup
    @Environment(PacketReplayStore.self) private var replayStore
    @State private var selectedRouteIndex = 0

    private var data: LivePacketData { group.latestData }

    var body: some View {
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
                            Text(observation.observerName ?? observation.observerId ?? "Unknown observer")
                                .font(.body.weight(.semibold))
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
                        Picker("Selected Route", selection: $selectedRouteIndex) {
                            ForEach(replayRoutes.indices, id: \.self) { index in
                                Text("Route \(index + 1) · \(replayRoutes[index].count) hops")
                                    .tag(index)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(NodeScopeStyle.signal)
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
            }

            if let raw = data.raw, !raw.isEmpty {
                Section("Raw Packet") {
                    Text(raw)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                }
            }
        }
        .navigationTitle("Packet Details")
        .navigationBarTitleDisplayMode(.inline)
        .contentMargins(.bottom, 104, for: .scrollContent)
    }

    private var routeHops: [LiveRouteHop] {
        group.longestPath.enumerated().map { index, value in
            LiveRouteHop(id: "\(index)|\(value)", position: index + 1, value: value)
        }
    }

    private var replayRoutes: [[String]] {
        var seen = Set<[String]>()
        let routes = group.observations
            .compactMap { $0.data?.resolvedPath?.compactMap { $0 } }
            .filter { route in
                guard route.count >= 2 else { return false }
                return seen.insert(route.map { $0.lowercased() }).inserted
            }
            .sorted { $0.count > $1.count }

        return routes.filter { candidate in
            !routes.contains { route in
                route.count > candidate.count && routeContains(route, candidate)
            }
        }
    }

    private func routeContains(_ route: [String], _ candidate: [String]) -> Bool {
        guard candidate.count <= route.count else { return false }
        let route = route.map { $0.lowercased() }
        let candidate = candidate.map { $0.lowercased() }
        for start in 0...(route.count - candidate.count) {
            if Array(route[start..<(start + candidate.count)]) == candidate { return true }
        }
        return false
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
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(monospaced ? .body.monospaced() : .body)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        }
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
