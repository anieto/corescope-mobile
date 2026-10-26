import Charts
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ObserverDetailScreen: View {
    let observer: MeshObserver
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favoritesStore
    @Environment(RecentItemsStore.self) private var recentItemsStore
    @Environment(LiveFeedService.self) private var liveFeed
    @State private var viewModel = ObserversViewModel()
    @State private var isExporting = false
    @State private var exportDocument = TextExportDocument()
    @State private var exportContentType = UTType.json
    @State private var exportFilename = "observer-statistics"
    @State private var showsFieldTools = false

    private var usesBeaconDetail: Bool { viewModel.beaconDetailSource != nil || liveFeed.usesBrowseSnapshots }
    private var isCurrentSource: Bool {
        viewModel.beaconDetailSource.map { $0 == settings.sourceSession.id } ?? true
    }
    private var displayedObserver: MeshObserver {
        guard usesBeaconDetail, isCurrentSource else { return observer }
        var detail = viewModel.beaconDetail ?? observer
        detail.online = observer.online
        let statuses = liveFeed.beaconObserverStatuses(sourceSessionID: settings.sourceSession.id)
        return statuses[observer.id.lowercased()]?.applying(to: detail) ?? detail
    }
    private var hasFieldCoordinate: Bool {
        guard let latitude = displayedObserver.lat, let longitude = displayedObserver.lon else { return false }
        return !(latitude == 0 && longitude == 0)
            && FieldCoordinate(latitude: latitude, longitude: longitude) != nil
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if viewModel.isLoading {
                    LoadingIndicator(title: usesBeaconDetail ? "Loading observer details…" : "Loading observer analytics…")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                DataLoadStatusView(
                    lastUpdatedAt: viewModel.lastUpdatedAt,
                    errorMessage: viewModel.errorMessage,
                    hasContent: usesBeaconDetail ? viewModel.beaconDetail != nil : viewModel.analytics != nil,
                    retry: retryAnalytics
                )

                if isCurrentSource {
                    ObserverIdentityCard(observer: displayedObserver)
                }

                if let analytics = viewModel.analytics {
                    if let explanation = analytics.context?.explanation {
                        BeaconAnalyticsNotice(message: explanation)
                    }
                    if analytics.timeline.isEmpty,
                       analytics.packetTypes.isEmpty,
                       analytics.nodesTimeline.isEmpty,
                       analytics.snrDistribution.isEmpty,
                       analytics.recentPackets.isEmpty,
                       analytics.telemetry?.isEmpty != false {
                        ObserverAnalyticsEmptyHistoryCard()
                    }
                    if !analytics.timeline.isEmpty {
                        PacketsTimelineCard(
                            points: analytics.timeline,
                            countUnit: analytics.context?.countUnit ?? .packets
                        )
                    }
                    if !analytics.packetTypes.isEmpty {
                        PacketTypesCard(
                            counts: analytics.packetTypes,
                            countUnit: analytics.context?.countUnit ?? .packets
                        )
                    }
                    if !analytics.nodesTimeline.isEmpty {
                        NodesTimelineCard(points: analytics.nodesTimeline)
                    }
                    if !analytics.snrDistribution.isEmpty {
                        SNRDistributionCard(buckets: analytics.snrDistribution)
                    }
                    if !analytics.recentPackets.isEmpty {
                        RecentObserverPacketsCard(packets: analytics.recentPackets)
                    }
                    if let telemetry = analytics.telemetry, !telemetry.isEmpty {
                        ObserverTelemetrySummaryCard(points: telemetry)
                        ObserverBatteryCard(points: telemetry)
                        ObserverNoiseFloorCard(points: telemetry)
                    }
                }
            }
            .padding(16)
            .adaptiveContentWidth()
        }
        .background(MeshBackground(subdued: true).ignoresSafeArea())
        .navigationTitle(isCurrentSource ? displayedObserver.name ?? "Observer" : "Observer")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            recentItemsStore.record(observer: observer, source: favoriteSource)
        }
        .toolbar { observerToolbar }
        .navigationDestination(isPresented: $showsFieldTools) {
            FieldToolsScreen(initialObserver: displayedObserver)
        }
        .task(id: "\(settings.sourceSession.id)|\(observer.id)") {
            await loadDetails()
        }
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: exportContentType,
            defaultFilename: exportFilename
        ) { _ in }
    }

    @ToolbarContentBuilder
    private var observerToolbar: some ToolbarContent {
        if hasFieldCoordinate {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showsFieldTools = true
                } label: {
                    Image(systemName: "location.viewfinder")
                }
                .accessibilityLabel("Guide to Observer in Field Tools")
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            favoriteButton
                .disabled(!isCurrentSource)
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button {
                    UIPasteboard.general.string = observer.id
                } label: {
                    Label("Copy Observer ID", systemImage: "doc.on.doc")
                }
                if let url = NodeScopeDeepLink.observer(observer.id).url {
                    ShareLink(item: url) {
                        Label("Share Observer Link", systemImage: "square.and.arrow.up")
                    }
                }
                Divider()
                Button {
                    prepareExport(.csv)
                } label: {
                    Label("Export CSV", systemImage: "tablecells")
                }
                Button {
                    prepareExport(.json)
                } label: {
                    Label("Export JSON", systemImage: "curlybraces")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel("Observer actions")
            .disabled(!isCurrentSource)
        }
    }

    private var favoriteButton: some View {
        let isFavorite = favoritesStore.contains(
            kind: .observer,
            entityID: observer.id,
            source: favoriteSource
        )
        return Button {
            favoritesStore.toggle(observer: observer, source: favoriteSource)
        } label: {
            Image(systemName: isFavorite ? "star.fill" : "star")
        }
        .tint(NodeScopeStyle.signal)
        .accessibilityLabel(isFavorite ? "Remove observer from favorites" : "Add observer to favorites")
    }

    private var favoriteSource: String {
        AnalyzerSettings.normalizedHost(settings.host)
    }

    private func retryAnalytics() {
        Task {
            await loadDetails()
        }
    }

    private func loadDetails() async {
        if usesBeaconDetail {
            await viewModel.loadBeaconDetail(id: observer.id, sourceSessionID: settings.sourceSession.id, service: liveFeed)
        }
        viewModel.configure(settings: settings)
        await viewModel.loadAnalytics(id: observer.id)
    }

    private func prepareExport(_ format: StatisticsExportFormat) {
        guard isCurrentSource else { return }
        let observer = displayedObserver
        let data: Data
        switch format {
        case .csv:
            data = StatisticsExportBuilder.csv(rows: observerCSVRows)
        case .json:
            data = StatisticsExportBuilder.jsonData(
                ObserverStatisticsExport(
                    analyzer: AnalyzerSettings.normalizedHost(settings.host),
                    exportedAt: .now,
                    observer: observer,
                    analytics: viewModel.analytics
                )
            )
        }

        let observerName = StatisticsExportBuilder.safeFilenameComponent(observer.name ?? observer.id)
        exportDocument = TextExportDocument(data: data)
        exportContentType = format.contentType
        exportFilename = "observer-\(observerName)"
        isExporting = true
    }

    private var observerCSVRows: [[String]] {
        let observer = displayedObserver
        var rows = [["section", "label", "value"]]
        rows += [
            ["observer", "id", observer.id],
            ["observer", "name", observer.name ?? ""],
            ["observer", "region", observer.iata ?? ""],
            ["observer", "first_seen", observer.firstSeen?.ISO8601Format() ?? "unknown"],
            ["observer", "last_seen", observer.lastSeen?.ISO8601Format() ?? "unknown"],
            ["observer", "packet_count", observer.packetCount.map(String.init) ?? "unknown"],
            ["observer", "packets_last_hour", observer.packetsLastHour.map(String.init) ?? "unknown"],
            ["observer", "model", observer.model ?? ""],
            ["observer", "firmware", observer.firmware ?? ""],
            ["observer", "battery_mv", observer.batteryMv.map { String($0) } ?? ""],
            ["observer", "noise_floor", observer.noiseFloor.map { String($0) } ?? ""]
        ]

        if let analytics = viewModel.analytics {
            rows += analytics.timeline.map { ["packet_timeline", $0.label, "\($0.count)"] }
            rows += analytics.nodesTimeline.map { ["nodes_timeline", $0.label, "\($0.count)"] }
            rows += analytics.snrDistribution.map { ["snr_distribution", $0.range, "\($0.count)"] }
            rows += analytics.packetTypes.sorted { $0.key < $1.key }.map {
                ["packet_types", PayloadType.name(for: Int($0.key) ?? -1), "\($0.value)"]
            }
        }
        return rows
    }
}

private struct ObserverStatisticsExport: Encodable {
    let analyzer: String
    let exportedAt: Date
    let observer: MeshObserver
    let analytics: ObserverAnalyticsResponse?
}

private struct ObserverIdentityCard: View {
    let observer: MeshObserver

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.title2)
                    .foregroundStyle(NodeScopeStyle.healthy)
                    .frame(width: 48, height: 48)
                    .background(NodeScopeStyle.healthy.opacity(0.13), in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text(observer.name ?? observer.id)
                        .font(.title2.bold())
                    HStack(spacing: 6) {
                        if let iata = observer.iata {
                            Label(iata, systemImage: "mappin.and.ellipse")
                        }
                        if let lastSeen = observer.lastSeen {
                            Text("Seen \(RelativeTime.string(from: lastSeen))")
                        } else {
                            Text("Last seen unknown")
                        }
                        if let online = observer.online {
                            if online { Text("Online") } else { Text("Offline") }
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }

            Divider().opacity(0.35)

            Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 10) {
                GridRow {
                    ObserverDetailMetric(label: "Model", value: observer.model ?? "Unknown")
                    ObserverDetailMetric(label: "Firmware", value: observer.firmware ?? "Unknown")
                }
                GridRow {
                    ObserverDetailMetric(label: "Packets / Hr", value: observer.packetsLastHour?.formatted() ?? String(localized: "Unknown"))
                    ObserverDetailMetric(label: "All Packets", value: observer.packetCount?.formatted(.number.notation(.compactName)) ?? String(localized: "Unknown"))
                }
                if let batteryMv = observer.batteryMv {
                    GridRow {
                        ObserverDetailMetric(label: "Battery", value: "\(batteryMv) mV")
                        ObserverDetailMetric(
                            label: "Noise Floor",
                            value: observer.noiseFloor.map { String(format: "%.0f dB", $0) } ?? "Unknown"
                        )
                    }
                }
            }
        }
        .padding(16)
        .instrumentCard()
    }
}

private struct ObserverDetailMetric: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct PacketsTimelineCard: View {
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    let points: [LabeledCount]
    let countUnit: AnalyticsCountUnit

    private var title: LocalizedStringKey {
        countUnit == .observations ? "Observations Over Time" : "Packets Over Time"
    }

    private var valueLabel: String {
        countUnit == .observations ? String(localized: "Observations") : String(localized: "Packets")
    }

    var body: some View {
        ObserverAnalyticsCard(title: title, symbol: "chart.bar.fill") {
            Chart(points) { point in
                BarMark(
                    x: .value("Time", point.label),
                    y: .value(valueLabel, point.count)
                )
                .foregroundStyle(NodeScopeStyle.signal.gradient)
                .cornerRadius(3)
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) {
                    AxisGridLine().foregroundStyle(.clear)
                    AxisValueLabel(collisionResolution: .greedy)
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading)
            }
            .frame(height: verticalSizeClass == .compact ? 140 : 190)
            .accessibilityLabel(
                countUnit == .observations
                    ? "Observations received over time"
                    : "Packets received over time"
            )
        }
    }
}

private struct PacketTypesCard: View {
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    let counts: [String: Int]
    let countUnit: AnalyticsCountUnit

    private var title: LocalizedStringKey {
        countUnit == .observations ? "Observation Types" : "Packet Types"
    }

    private var valueLabel: String {
        countUnit == .observations ? String(localized: "Observations") : String(localized: "Packets")
    }

    private var data: [PacketTypeDatum] {
        counts.compactMap { key, count in
            guard let code = Int(key) else { return nil }
            return PacketTypeDatum(code: code, count: count)
        }
        .sorted { $0.count > $1.count }
    }

    var body: some View {
        ObserverAnalyticsCard(title: title, symbol: "chart.pie.fill") {
            Chart(data) { item in
                SectorMark(
                    angle: .value(valueLabel, item.count),
                    innerRadius: .ratio(0.58),
                    angularInset: 1.5
                )
                .cornerRadius(3)
                .foregroundStyle(by: .value("Type", item.name))
            }
            .chartLegend(position: .bottom, alignment: .leading, spacing: 8)
            .frame(height: verticalSizeClass == .compact ? 210 : 270)
            .accessibilityLabel(
                countUnit == .observations
                    ? "Observation type distribution"
                    : "Packet type distribution"
            )
        }
    }
}

/// A packet type with its friendly name; shared with Node Analytics so both
/// packet-type donuts label and order types the same way.
struct PacketTypeDatum: Identifiable {
    let code: Int
    let count: Int
    var id: Int { code }

    var name: String {
        switch PayloadType(rawValue: code) {
        case .req: "Request"
        case .response: "Response"
        case .txtMsg: "Direct Msg"
        case .ack: "ACK"
        case .advert: "Advert"
        case .grpTxt: "Channel Msg"
        case .anonReq: "Anon Req"
        case .path: "Path"
        case .trace: "Trace"
        case .control: "Control"
        case nil: "Type \(code)"
        }
    }
}

private struct NodesTimelineCard: View {
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    let points: [LabeledCount]

    var body: some View {
        ObserverAnalyticsCard(title: "Unique Nodes Heard", symbol: "point.3.connected.trianglepath.dotted") {
            Chart(points) { point in
                AreaMark(
                    x: .value("Time", point.label),
                    y: .value("Nodes", point.count)
                )
                .foregroundStyle(NodeScopeStyle.healthy.opacity(0.16).gradient)
                LineMark(
                    x: .value("Time", point.label),
                    y: .value("Nodes", point.count)
                )
                .foregroundStyle(NodeScopeStyle.healthy)
                .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                PointMark(
                    x: .value("Time", point.label),
                    y: .value("Nodes", point.count)
                )
                .foregroundStyle(NodeScopeStyle.healthy)
                .symbolSize(18)
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) {
                    AxisGridLine().foregroundStyle(.clear)
                    AxisValueLabel(collisionResolution: .greedy)
                }
            }
            .chartYAxis { AxisMarks(position: .leading) }
            .frame(height: verticalSizeClass == .compact ? 140 : 190)
            .accessibilityLabel("Unique nodes heard over time")
        }
    }
}

private struct SNRDistributionCard: View {
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    let buckets: [SnrBucket]

    var body: some View {
        ObserverAnalyticsCard(title: "SNR Distribution", symbol: "waveform.path") {
            Chart(buckets) { bucket in
                BarMark(
                    x: .value("SNR range", bucket.range),
                    y: .value("Packets", bucket.count)
                )
                .foregroundStyle(NodeScopeStyle.activity.gradient)
                .cornerRadius(3)
            }
            .chartXAxis {
                AxisMarks { value in
                    AxisGridLine().foregroundStyle(.clear)
                    AxisValueLabel {
                        if let label = value.as(String.self) {
                            Text(label)
                                .font(.caption2)
                                .rotationEffect(.degrees(-35))
                        }
                    }
                }
            }
            .chartYAxis { AxisMarks(position: .leading) }
            .frame(height: verticalSizeClass == .compact ? 150 : 210)
            .accessibilityLabel("Signal-to-noise ratio distribution")
        }
    }
}

private struct RecentObserverPacketsCard: View {
    let packets: [ObserverRecentPacket]

    var body: some View {
        ObserverAnalyticsCard(title: "Recent Packets", symbol: "waveform.badge.magnifyingglass") {
            VStack(spacing: 0) {
                ForEach(packets.prefix(8)) { packet in
                    HStack(spacing: 10) {
                        Text(packet.payloadTypeName)
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(NodeScopeStyle.signal)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 4)
                            .background(NodeScopeStyle.signal.opacity(0.11), in: Capsule())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(packet.hash.uppercased())
                                .font(.caption.monospaced())
                                .lineLimit(1)
                            Text(packet.timestamp, style: .relative)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if let snr = packet.snr {
                            Text("\(snr, specifier: "%.1f") dB")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 9)
                    if packet.id != packets.prefix(8).last?.id {
                        Divider().opacity(0.3)
                    }
                }
            }
        }
    }
}

private struct BeaconAnalyticsNotice: View {
    let message: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "info.circle.fill")
                .foregroundStyle(NodeScopeStyle.signal)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .instrumentCard()
    }
}

private struct ObserverAnalyticsEmptyHistoryCard: View {
    var body: some View {
        ContentUnavailableView(
            "No Activity in This Range",
            systemImage: "waveform.path.ecg",
            description: Text("No observer activity or telemetry was recorded during the selected time range.")
        )
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity)
        .instrumentCard()
    }
}

private struct ObserverTelemetrySummaryCard: View {
    let points: [ObserverTelemetryPoint]

    private var latest: ObserverTelemetryPoint? { points.max { $0.timestamp < $1.timestamp } }

    var body: some View {
        ObserverAnalyticsCard(title: "Observer Telemetry", symbol: "sensor.fill") {
            if let latest {
                Grid(alignment: .leading, horizontalSpacing: 18, verticalSpacing: 12) {
                    GridRow {
                        telemetryValue("Battery", value: latest.batteryMillivolts.map {
                            Measurement(value: Double($0) / 1_000, unit: UnitElectricPotentialDifference.volts)
                                .formatted(.measurement(width: .abbreviated, usage: .asProvided))
                        })
                        telemetryValue("Noise Floor", value: latest.noiseFloorDecibels.map {
                            String(localized: "\($0.formatted(.number.precision(.fractionLength(0)))) dB")
                        })
                    }
                    GridRow {
                        telemetryValue("TX Airtime", value: duration(latest.transmitAirtimeSeconds))
                        telemetryValue("RX Airtime", value: duration(latest.receiveAirtimeSeconds))
                    }
                    GridRow {
                        telemetryValue("Uptime", value: duration(latest.uptimeSeconds))
                        telemetryValue("Receive Errors", value: latest.receiveErrors?.formatted())
                    }
                }
                Text("Latest telemetry sample ")
                    + Text(latest.timestamp, style: .relative)
                    + Text(".")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func telemetryValue(_ label: LocalizedStringKey, value: String?) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value ?? "Unknown")
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func duration(_ seconds: Int?) -> String? {
        guard let seconds else { return nil }
        let days = seconds / 86_400
        let hours = seconds % 86_400 / 3_600
        if days > 0 { return String(localized: "\(days) d \(hours) h") }
        let minutes = seconds % 3_600 / 60
        return String(localized: "\(hours) h \(minutes) min")
    }
}

private struct ObserverBatteryCard: View {
    let points: [ObserverTelemetryPoint]

    private var values: [ObserverTelemetryPoint] { points.filter { $0.batteryMillivolts != nil } }

    var body: some View {
        if !values.isEmpty {
            ObserverAnalyticsCard(title: "Battery History", symbol: "battery.100percent") {
                Chart(values) { point in
                    LineMark(
                        x: .value("Time", point.timestamp),
                        y: .value("Volts", Double(point.batteryMillivolts ?? 0) / 1_000)
                    )
                    .foregroundStyle(NodeScopeStyle.healthy)
                    .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                }
                .chartXAxis { AxisMarks(values: .automatic(desiredCount: 4)) }
                .chartYAxis { AxisMarks(position: .leading) }
                .frame(height: 180)
                .accessibilityLabel("Observer battery voltage over time")
            }
        }
    }
}

private struct ObserverNoiseFloorCard: View {
    let points: [ObserverTelemetryPoint]

    private var values: [ObserverTelemetryPoint] { points.filter { $0.noiseFloorDecibels != nil } }

    var body: some View {
        if !values.isEmpty {
            ObserverAnalyticsCard(title: "Noise Floor History", symbol: "waveform") {
                Chart(values) { point in
                    LineMark(
                        x: .value("Time", point.timestamp),
                        y: .value("Decibels", point.noiseFloorDecibels ?? 0)
                    )
                    .foregroundStyle(NodeScopeStyle.activity)
                    .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                }
                .chartXAxis { AxisMarks(values: .automatic(desiredCount: 4)) }
                .chartYAxis { AxisMarks(position: .leading) }
                .frame(height: 180)
                .accessibilityLabel("Observer noise floor over time")
            }
        }
    }
}

private struct ObserverAnalyticsCard<Content: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    @ViewBuilder let content: Content

    init(title: LocalizedStringKey, symbol: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.symbol = symbol
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(title, systemImage: symbol)
                .font(.headline)
                .foregroundStyle(NodeScopeStyle.signal)
            content
        }
        .padding(16)
        .instrumentCard(fillOpacity: 1)
    }
}
