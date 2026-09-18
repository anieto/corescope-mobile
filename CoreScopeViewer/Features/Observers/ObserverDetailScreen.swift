import Charts
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ObserverDetailScreen: View {
    let observer: MeshObserver
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favoritesStore
    @Environment(RecentItemsStore.self) private var recentItemsStore
    @State private var viewModel = ObserversViewModel()
    @State private var isExporting = false
    @State private var exportDocument = TextExportDocument()
    @State private var exportContentType = UTType.json
    @State private var exportFilename = "observer-statistics"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if viewModel.isLoading {
                    LoadingIndicator(title: "Loading observer analytics…")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                DataLoadStatusView(
                    lastUpdatedAt: viewModel.lastUpdatedAt,
                    errorMessage: viewModel.errorMessage,
                    hasContent: viewModel.analytics != nil,
                    retry: retryAnalytics
                )

                ObserverIdentityCard(observer: observer)

                if let analytics = viewModel.analytics {
                    if !analytics.timeline.isEmpty {
                        PacketsTimelineCard(points: analytics.timeline)
                    }
                    if !analytics.packetTypes.isEmpty {
                        PacketTypesCard(counts: analytics.packetTypes)
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
                }
            }
            .padding(16)
            .padding(.bottom, 96)
            .adaptiveContentWidth()
        }
        .background(NodeScopeBackground())
        .navigationTitle(observer.name ?? "Observer")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            recentItemsStore.record(observer: observer, source: favoriteSource)
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                favoriteButton
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        UIPasteboard.general.string = observer.id
                    } label: {
                        Label("Copy Observer ID", systemImage: "doc.on.doc")
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
            }
        }
        .task {
            viewModel.configure(settings: settings)
            await viewModel.loadAnalytics(id: observer.id)
        }
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: exportContentType,
            defaultFilename: exportFilename
        ) { _ in }
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
            await viewModel.loadAnalytics(id: observer.id)
        }
    }

    private func prepareExport(_ format: StatisticsExportFormat) {
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
        var rows = [["section", "label", "value"]]
        rows += [
            ["observer", "id", observer.id],
            ["observer", "name", observer.name ?? ""],
            ["observer", "region", observer.iata ?? ""],
            ["observer", "first_seen", observer.firstSeen.ISO8601Format()],
            ["observer", "last_seen", observer.lastSeen.ISO8601Format()],
            ["observer", "packet_count", "\(observer.packetCount)"],
            ["observer", "packets_last_hour", "\(observer.packetsLastHour)"],
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
                        Text("Seen \(RelativeTime.string(from: observer.lastSeen))")
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
                    ObserverDetailMetric(label: "Packets / Hr", value: observer.packetsLastHour.formatted())
                    ObserverDetailMetric(label: "All Packets", value: observer.packetCount.formatted(.number.notation(.compactName)))
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
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct PacketsTimelineCard: View {
    let points: [LabeledCount]

    var body: some View {
        ObserverAnalyticsCard(title: "Packets Over Time", symbol: "chart.bar.fill") {
            Chart(points) { point in
                BarMark(
                    x: .value("Time", point.label),
                    y: .value("Packets", point.count)
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
            .frame(height: 190)
            .accessibilityLabel("Packets received over time")
        }
    }
}

private struct PacketTypesCard: View {
    let counts: [String: Int]

    private var data: [PacketTypeDatum] {
        counts.compactMap { key, count in
            guard let code = Int(key) else { return nil }
            return PacketTypeDatum(code: code, count: count)
        }
        .sorted { $0.count > $1.count }
    }

    var body: some View {
        ObserverAnalyticsCard(title: "Packet Types", symbol: "chart.pie.fill") {
            Chart(data) { item in
                SectorMark(
                    angle: .value("Packets", item.count),
                    innerRadius: .ratio(0.58),
                    angularInset: 1.5
                )
                .cornerRadius(3)
                .foregroundStyle(by: .value("Type", item.name))
            }
            .chartLegend(position: .bottom, alignment: .leading, spacing: 8)
            .frame(height: 270)
            .accessibilityLabel("Packet type distribution")
        }
    }
}

private struct PacketTypeDatum: Identifiable {
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
            .frame(height: 190)
            .accessibilityLabel("Unique nodes heard over time")
        }
    }
}

private struct SNRDistributionCard: View {
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
            .frame(height: 210)
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
        .instrumentCard()
    }
}
