import Charts
import SwiftUI
import UniformTypeIdentifiers

struct NodeAnalyticsScreen: View {
    let node: MeshNode

    @Environment(AnalyzerSettings.self) private var settings
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @State private var viewModel = NodeAnalyticsViewModel()
    @State private var isExporting = false
    @State private var exportDocument = TextExportDocument()
    @State private var exportContentType = UTType.json
    @State private var exportFilename = "node-analytics"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                NodeAnalyticsRangePicker(selection: $viewModel.selectedRange)

                NodeAnalyticsIdentityHeader(
                    name: viewModel.analytics?.node.name ?? node.name,
                    publicKey: viewModel.analytics?.node.publicKey ?? node.publicKey,
                    role: viewModel.analytics?.node.role ?? node.role
                )

                if viewModel.isLoading {
                    LoadingIndicator(title: "Loading node analytics…")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                if viewModel.isSupported {
                    DataLoadStatusView(
                        lastUpdatedAt: viewModel.lastUpdatedAt,
                        errorMessage: viewModel.errorMessage,
                        hasContent: viewModel.analytics != nil,
                        retry: reload
                    )
                }

                if !viewModel.isSupported {
                    ContentUnavailableView(
                        "Analytics Unavailable",
                        systemImage: "chart.xyaxis.line",
                        description: Text("This analyzer does not currently provide node analytics.")
                    )
                    .padding(.vertical, 48)
                    .frame(maxWidth: .infinity)
                } else if let analytics = viewModel.analytics {
                    NodeAnalyticsSummaryCard(stats: analytics.computedStats)

                    if !analytics.activityTimeline.isEmpty {
                        NodeActivityChart(points: analytics.activityTimeline)
                    }

                    if !analytics.uptimeHeatmap.isEmpty {
                        NodeUptimeHeatmapCard(cells: analytics.uptimeHeatmap)
                    }

                    if !analytics.snrTrend.isEmpty {
                        NodeSignalCard(points: analytics.snrTrend, observers: observerRegionLookup.observers)
                    }

                    if !analytics.packetTypeBreakdown.isEmpty {
                        NodePacketTypesChart(counts: analytics.packetTypeBreakdown)
                    }

                    if !analytics.hopDistribution.isEmpty {
                        NodeHopDistributionChart(counts: analytics.hopDistribution)
                    }

                    if !analytics.observerCoverage.isEmpty {
                        NodeObserverCoverageCard(observers: analytics.observerCoverage)
                    }

                    if !analytics.peerInteractions.isEmpty {
                        NodePeerInteractionsCard(peers: analytics.peerInteractions)
                    }
                }
            }
            .padding(16)
            .padding(.bottom, 104)
            .adaptiveContentWidth()
        }
        .background(NodeScopeBackground())
        .navigationTitle("Node Analytics")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
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
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(viewModel.analytics == nil)
                .accessibilityLabel("Export node analytics")
            }
        }
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: exportContentType,
            defaultFilename: exportFilename
        ) { _ in }
        .refreshable {
            await viewModel.load(pubkey: node.publicKey)
        }
        .task {
            viewModel.configure(settings: settings)
            await viewModel.load(pubkey: node.publicKey)
        }
        .onChange(of: viewModel.selectedRange) { _, range in
            Task {
                await viewModel.load(pubkey: node.publicKey, range: range)
            }
        }
    }

    private func reload() {
        Task {
            await viewModel.load(pubkey: node.publicKey)
        }
    }

    private func prepareExport(_ format: StatisticsExportFormat) {
        guard let analytics = viewModel.analytics else { return }

        let analyzer = AnalyzerSettings.normalizedHost(settings.host)
        switch format {
        case .csv:
            exportDocument = TextExportDocument(
                data: NodeAnalyticsExportBuilder.csv(
                    analyzer: analyzer,
                    analytics: analytics
                )
            )
        case .json:
            exportDocument = TextExportDocument(
                data: NodeAnalyticsExportBuilder.json(
                    analyzer: analyzer,
                    analytics: analytics
                )
            )
        }

        let nodeName = StatisticsExportBuilder.safeFilenameComponent(
            analytics.node.name ?? analytics.node.publicKey
        )
        exportContentType = format.contentType
        exportFilename = "node-\(nodeName)-\(viewModel.selectedRange.title)-analytics"
        isExporting = true
    }
}


private struct NodeAnalyticsExportEnvelope: Encodable {
    let analyzer: String
    let exportedAt: Date
    let analytics: NodeAnalyticsResponse
}

private enum NodeAnalyticsExportBuilder {
    static func json(analyzer: String, analytics: NodeAnalyticsResponse) -> Data {
        StatisticsExportBuilder.jsonData(
            NodeAnalyticsExportEnvelope(
                analyzer: analyzer,
                exportedAt: .now,
                analytics: analytics
            )
        )
    }

    static func csv(analyzer: String, analytics: NodeAnalyticsResponse) -> Data {
        StatisticsExportBuilder.csv(
            rows: metadataRows(analyzer: analyzer, analytics: analytics)
                + statisticRows(analytics.computedStats)
                + analytics.activityTimeline.map {
                    ["activity", "", $0.bucket.ISO8601Format(), String($0.count), "", ""]
                }
                + analytics.snrTrend.map {
                    [
                        "signal",
                        $0.observerID ?? "",
                        $0.timestamp.ISO8601Format(),
                        "",
                        String($0.snr),
                        $0.rssi.map { String($0) } ?? ""
                    ]
                }
                + analytics.packetTypeBreakdown.map {
                    ["packet_type", String($0.payloadType), "", String($0.count), $0.name, ""]
                }
                + analytics.observerCoverage.map {
                    [
                        "observer",
                        $0.observerID,
                        $0.lastSeen.ISO8601Format(),
                        String($0.packetCount),
                        $0.avgSnr.map { String($0) } ?? "",
                        $0.observerName ?? ""
                    ]
                }
                + analytics.hopDistribution.map {
                    ["hop_count", $0.hops, "", String($0.count), "", ""]
                }
                + analytics.peerInteractions.map {
                    [
                        "peer",
                        $0.peerKey,
                        $0.lastContact.ISO8601Format(),
                        String($0.messageCount),
                        "",
                        $0.peerName
                    ]
                }
                + analytics.uptimeHeatmap.map {
                    [
                        "activity_heatmap",
                        "day_\($0.dayOfWeek)_hour_\($0.hour)",
                        "",
                        String($0.count),
                        "",
                        "UTC"
                    ]
                }
        )
    }

    private static func metadataRows(
        analyzer: String,
        analytics: NodeAnalyticsResponse
    ) -> [[String]] {
        [
            ["section", "key", "timestamp", "count", "value", "detail"],
            ["export", "analyzer", Date.now.ISO8601Format(), "", analyzer, ""],
            ["node", "public_key", "", "", analytics.node.publicKey, ""],
            ["node", "name", "", "", analytics.node.name ?? "", ""],
            ["node", "role", "", "", analytics.node.role, ""],
            ["time_range", "from", analytics.timeRange.from.ISO8601Format(), "", "", ""],
            ["time_range", "to", analytics.timeRange.to.ISO8601Format(), "", "", ""],
            ["time_range", "days", "", "", String(analytics.timeRange.days), ""]
        ]
    }

    private static func statisticRows(_ stats: NodeComputedStats) -> [[String]] {
        [
            ["statistic", "availability_percent", "", "", String(stats.availabilityPct), ""],
            ["statistic", "longest_silence_ms", "", "", String(stats.longestSilenceMs), ""],
            [
                "statistic",
                "longest_silence_start",
                stats.longestSilenceStart?.ISO8601Format() ?? "",
                "",
                "",
                ""
            ],
            ["statistic", "signal_grade", "", "", stats.signalGrade, ""],
            ["statistic", "snr_mean", "", "", String(stats.snrMean), ""],
            ["statistic", "snr_standard_deviation", "", "", String(stats.snrStdDev), ""],
            ["statistic", "relay_percent", "", "", String(stats.relayPct), ""],
            ["statistic", "total_packets", "", "", String(stats.totalPackets), ""],
            ["statistic", "unique_observers", "", "", String(stats.uniqueObservers), ""],
            ["statistic", "unique_peers", "", "", String(stats.uniquePeers), ""],
            ["statistic", "average_packets_per_day", "", "", String(stats.avgPacketsPerDay), ""]
        ]
    }
}

private struct NodeAnalyticsIdentityHeader: View {
    let name: String?
    let publicKey: String
    let role: String

    var body: some View {
        let tint = NodeRoleStyle.color(for: role)

        HStack(spacing: 12) {
            Image(systemName: NodeRoleStyle.symbolName(for: role))
                .font(.title3)
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(tint, in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                if let name, !name.isEmpty {
                    Text(name)
                        .font(.headline)
                } else {
                    Text("Unnamed Node")
                        .font(.headline)
                }

                HStack(spacing: 6) {
                    Text(roleLabel)
                    Text("•")
                    Text(publicKey)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .instrumentCard()
        .accessibilityElement(children: .combine)
    }

    private var roleLabel: LocalizedStringResource {
        switch role {
        case "repeater": "Repeater"
        case "room": "Room"
        case "companion": "Companion"
        case "sensor": "Sensor"
        default: "Unknown role"
        }
    }
}

private struct NodeAnalyticsRangePicker: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Binding var selection: NodeAnalyticsRange

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Time Range")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)

            if dynamicTypeSize.isAccessibilitySize {
                Picker("Time Range", selection: $selection) {
                    rangeOptions
                }
                .pickerStyle(.menu)
            } else {
                Picker("Time Range", selection: $selection) {
                    rangeOptions
                }
                .pickerStyle(.segmented)
            }
        }
        .padding(16)
        .instrumentCard()
    }

    @ViewBuilder
    private var rangeOptions: some View {
        ForEach(NodeAnalyticsRange.allCases) { range in
            Text(range.title).tag(range)
        }
    }
}

private struct NodeAnalyticsSummaryCard: View {
    let stats: NodeComputedStats

    private let columns = [
        GridItem(.adaptive(minimum: 132), spacing: 12)
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("At a Glance", systemImage: "gauge.with.dots.needle.67percent")
                .font(.headline)
                .foregroundStyle(.primary)
                .accessibilityAddTraits(.isHeader)

            LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                NodeAnalyticsMetric(
                    value: stats.availabilityPct.formatted(.number.precision(.fractionLength(0))) + "%",
                    label: "Availability",
                    tint: NodeScopeStyle.healthy
                )
                NodeAnalyticsMetric(
                    value: stats.signalGrade,
                    label: "Signal grade",
                    tint: NodeScopeStyle.signal
                )
                NodeAnalyticsMetric(
                    value: stats.avgPacketsPerDay.formatted(.number.precision(.fractionLength(0))),
                    label: "Packets / day",
                    tint: NodeScopeStyle.signal
                )
                NodeAnalyticsMetric(
                    value: stats.relayPct.formatted(.number.precision(.fractionLength(0))) + "%",
                    label: "Relayed",
                    tint: .orange
                )
                NodeAnalyticsMetric(
                    value: stats.uniqueObservers.formatted(),
                    label: "Observers",
                    tint: .blue
                )
                NodeAnalyticsMetric(
                    value: Self.duration(stats.longestSilenceMs),
                    label: "Longest silence",
                    tint: .secondary
                )
            }
        }
        .padding(16)
        .instrumentCard()
    }

    private static func duration(_ milliseconds: Double) -> String {
        let seconds = max(0, milliseconds / 1_000)
        if seconds >= 86_400 {
            return (seconds / 86_400).formatted(.number.precision(.fractionLength(1))) + "d"
        }
        if seconds >= 3_600 {
            return (seconds / 3_600).formatted(.number.precision(.fractionLength(1))) + "h"
        }
        if seconds >= 60 {
            return (seconds / 60).formatted(.number.precision(.fractionLength(0))) + "m"
        }
        return seconds.formatted(.number.precision(.fractionLength(0))) + "s"
    }
}

private struct NodeAnalyticsMetric: View {
    let value: String
    let label: LocalizedStringKey
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(value)
                .font(.title2.bold())
                .foregroundStyle(.primary)
            HStack(spacing: 5) {
                Circle()
                    .fill(tint)
                    .frame(width: 6, height: 6)
                    .accessibilityHidden(true)
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 54, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityValue(value)
    }
}

private struct NodeActivityChart: View {
    let points: [NodeActivityPoint]

    var body: some View {
        NodeAnalyticsChartCard(
            title: "Activity",
            subtitle: "\(points.reduce(0) { $0 + $1.count }.formatted()) packets",
            symbol: "waveform.path.ecg"
        ) {
            Chart(points) { point in
                AreaMark(
                    x: .value("Time", point.bucket),
                    y: .value("Packets", point.count)
                )
                .foregroundStyle(
                    .linearGradient(
                        colors: [Color.blue.opacity(0.42), Color.blue.opacity(0.04)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )

                LineMark(
                    x: .value("Time", point.bucket),
                    y: .value("Packets", point.count)
                )
                .foregroundStyle(.blue)
                .interpolationMethod(.catmullRom)
            }
            .chartYAxis {
                AxisMarks(position: .leading)
            }
            .frame(height: 190)
            .accessibilityLabel("Packet activity chart")
        }
    }
}


private struct NodeUptimeHeatmapCard: View {
    let cells: [NodeUptimeCell]

    private var rows: [NodeUptimeRow] {
        let counts = Dictionary(
            cells.map { (NodeUptimeSlot.ID(day: $0.dayOfWeek, hour: $0.hour), $0.count) },
            uniquingKeysWith: +
        )
        return (0..<7).map { day in
            NodeUptimeRow(
                day: day,
                slots: (0..<24).map { hour in
                    let id = NodeUptimeSlot.ID(day: day, hour: hour)
                    return NodeUptimeSlot(id: id, count: counts[id, default: 0])
                }
            )
        }
    }

    private var maximumCount: Int {
        max(cells.map(\.count).max() ?? 0, 1)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Label("Activity by Time", systemImage: "calendar.day.timeline.leading")
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .accessibilityAddTraits(.isHeader)
                Text("Packet activity by weekday and hour, shown in UTC")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            ViewThatFits(in: .horizontal) {
                NodeUptimeHeatmapGrid(
                    rows: rows,
                    maximumCount: maximumCount,
                    expandsToFillWidth: true
                )

                ScrollView(.horizontal) {
                    NodeUptimeHeatmapGrid(
                        rows: rows,
                        maximumCount: maximumCount,
                        expandsToFillWidth: false
                    )
                }
                .scrollIndicators(.visible)
            }
        }
        .padding(16)
        .instrumentCard()
    }

}


private struct NodeUptimeHeatmapGrid: View {
    let rows: [NodeUptimeRow]
    let maximumCount: Int
    let expandsToFillWidth: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            NodeUptimeHourLabels(expandsToFillWidth: expandsToFillWidth)

            ForEach(rows) { row in
                HStack(spacing: 4) {
                    Text(dayName(for: row.day))
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 30, alignment: .trailing)

                    ForEach(row.slots) { slot in
                        NodeUptimeHeatmapCell(
                            day: slot.id.day,
                            hour: slot.id.hour,
                            count: slot.count,
                            maximumCount: maximumCount,
                            expandsToFillWidth: expandsToFillWidth
                        )
                    }
                }
                .frame(maxWidth: expandsToFillWidth ? .infinity : nil)
            }

            NodeUptimeHeatmapLegend()
                .padding(.leading, 34)
                .padding(.top, 4)
        }
        .padding(.vertical, 2)
        .frame(maxWidth: expandsToFillWidth ? .infinity : nil)
    }

    private func dayName(for day: Int) -> LocalizedStringResource {
        switch day {
        case 0: "Sun"
        case 1: "Mon"
        case 2: "Tue"
        case 3: "Wed"
        case 4: "Thu"
        case 5: "Fri"
        default: "Sat"
        }
    }
}

private struct NodeUptimeRow: Identifiable {
    let day: Int
    let slots: [NodeUptimeSlot]

    var id: Int { day }
}

private struct NodeUptimeSlot: Identifiable {
    struct ID: Hashable {
        let day: Int
        let hour: Int
    }

    let id: ID
    let count: Int
}

private struct NodeUptimeHourLabels: View {
    let expandsToFillWidth: Bool

    var body: some View {
        HStack(spacing: 4) {
            Color.clear
                .frame(width: 30, height: 12)

            ForEach(0..<24, id: \.self) { hour in
                Text(hour.isMultiple(of: 3) ? String(format: "%02d", hour) : "")
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .frame(
                        minWidth: 18,
                        maxWidth: expandsToFillWidth ? .infinity : 18,
                        minHeight: 12,
                        maxHeight: 12
                    )
                    .accessibilityHidden(true)
            }
        }
    }
}

private struct NodeUptimeHeatmapCell: View {
    let day: Int
    let hour: Int
    let count: Int
    let maximumCount: Int
    let expandsToFillWidth: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 4, style: .continuous)
            .fill(fillColor)
            .frame(
                minWidth: 18,
                maxWidth: expandsToFillWidth ? .infinity : 18,
                minHeight: 18,
                maxHeight: 18
            )
            .overlay {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .stroke(Color.secondary.opacity(0.12), lineWidth: 0.5)
            }
            .accessibilityLabel(dayName)
            .accessibilityValue("\(count) packets at UTC hour \(hour)")
    }

    private var fillColor: Color {
        guard count > 0 else {
            return Color.secondary.opacity(0.08)
        }
        let intensity = 0.22 + (0.78 * Double(count) / Double(maximumCount))
        return NodeScopeStyle.healthy.opacity(intensity)
    }

    private var dayName: LocalizedStringResource {
        switch day {
        case 0: "Sunday"
        case 1: "Monday"
        case 2: "Tuesday"
        case 3: "Wednesday"
        case 4: "Thursday"
        case 5: "Friday"
        default: "Saturday"
        }
    }
}

private struct NodeUptimeHeatmapLegend: View {
    var body: some View {
        HStack(spacing: 6) {
            Text("Less")
            ForEach(1...4, id: \.self) { level in
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(NodeScopeStyle.healthy.opacity(Double(level) * 0.25))
                    .frame(width: 18, height: 10)
                    .accessibilityHidden(true)
            }
            Text("More")
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Activity intensity ranges from less to more")
    }
}

extension SignalQuality {
    var color: Color {
        switch self {
        case .strong: NodeScopeStyle.healthy
        case .good: Color(red: 0.49, green: 0.77, blue: 0.46)
        case .weak: NodeScopeStyle.activity
        case .nearLimit: Color(red: 0.90, green: 0.28, blue: 0.30)
        }
    }
}

private func formattedDB(_ value: Double) -> String {
    value.formatted(.number.precision(.fractionLength(1))) + " dB"
}

/// Plain-language link quality first, with measured numbers always beside it and
/// full statistics plus each observer's readings on tap. A trend mixed across
/// observers is deliberately not shown: the set of listeners changes over time.
private struct NodeSignalCard: View {
    let points: [NodeSignalPoint]
    let observers: [MeshObserver]

    @State private var showsAllObservers = false
    @State private var expandedObserverID: String?

    private var summaries: [ObserverSignalSummary] {
        ObserverSignalSummary.summaries(points: points, observers: observers)
    }

    var body: some View {
        let summaries = summaries
        if let best = summaries.first {
            let scale = Self.scale(for: summaries)
            NodeAnalyticsChartCard(
                title: "Signal",
                subtitle: String(localized: "How well observers hear this node (SNR)"),
                symbol: "wave.3.right"
            ) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(headline(for: summaries))
                        .font(.subheadline.weight(.semibold))
                    Text(bestLine(for: best))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                VStack(spacing: 0) {
                    ForEach(summaries.prefix(showsAllObservers ? summaries.count : 5)) { summary in
                        ObserverSignalRow(
                            summary: summary,
                            readings: summary.readings(in: points),
                            scale: scale,
                            isExpanded: expandedObserverID == summary.id
                        ) {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                expandedObserverID = expandedObserverID == summary.id ? nil : summary.id
                            }
                        }
                        if summary.id != summaries.prefix(showsAllObservers ? summaries.count : 5).last?.id {
                            Divider().opacity(0.3)
                        }
                    }
                }

                if summaries.count > 5 {
                    Button(showsAllObservers ? "Show less" : "Show all \(summaries.count) observers") {
                        withAnimation(.easeInOut(duration: 0.2)) { showsAllObservers.toggle() }
                    }
                    .font(.caption.weight(.bold))
                }

                SignalQualityLegend()

                Text(explanation(for: summaries))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// One scale for every row, always including the decode limit so each link's
    /// margin is visible.
    static func scale(for summaries: [ObserverSignalSummary]) -> ClosedRange<Double> {
        let floor = summaries.compactMap(\.floor).max()
        let low = min(summaries.map(\.low).min() ?? 0, floor ?? .greatestFiniteMagnitude) - 2
        let high = max(summaries.map(\.high).max() ?? 0, (floor ?? 0) + 16) + 2
        return low...max(high, low + 1)
    }

    private func headline(for summaries: [ObserverSignalSummary]) -> String {
        let counts = Dictionary(grouping: summaries.compactMap(\.quality), by: { $0 }).mapValues(\.count)
        let parts = SignalQuality.allCases.compactMap { quality in
            counts[quality].map { "\($0) \(quality.label.lowercased())" }
        }
        let heard = summaries.count == 1
            ? String(localized: "Heard by 1 observer")
            : String(localized: "Heard by \(summaries.count) observers")
        return ([heard] + parts).joined(separator: " · ")
    }

    private func bestLine(for best: ObserverSignalSummary) -> String {
        var line = String(localized: "Best: \(best.observer), \(formattedDB(best.median))")
        if let margin = best.margin {
            line += String(localized: " (\(formattedDB(margin)) above the decode limit)")
        }
        return line
    }

    private func explanation(for summaries: [ObserverSignalSummary]) -> String {
        let factors = Set(summaries.compactMap(\.spreadingFactor))
        if factors.count == 1, let factor = factors.first {
            let floor = formattedDB(SignalQualityMath.snrFloor(spreadingFactor: factor))
            return String(localized: "Quality is the margin above the SF\(factor) decode limit (\(floor)): near limit under 5 dB, weak 5–10, good 10–15, strong 15+. Bars show each observer's typical range; the tick is its median.")
        }
        if factors.isEmpty {
            return String(localized: "Radio settings are unavailable, so only measured values are shown. Bars show each observer's typical range; the tick is its median.")
        }
        return String(localized: "Quality is the margin above each observer's own decode limit, which depends on its spreading factor. Bars show typical range; the tick is the median.")
    }
}

private struct ObserverSignalRow: View {
    let summary: ObserverSignalSummary
    let readings: [NodeSignalPoint]
    let scale: ClosedRange<Double>
    let isExpanded: Bool
    let toggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button(action: toggle) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(summary.observer)
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)
                            Text(typicalLine)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 6)
                        if let quality = summary.quality {
                            Text(quality.label)
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(quality.color.opacity(0.18), in: Capsule())
                        }
                        Text(formattedDB(summary.median))
                            .font(.subheadline.weight(.semibold).monospacedDigit())
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    SignalRangeBar(summary: summary, scale: scale)
                        .frame(height: 8)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(summary.observer)
            .accessibilityValue(accessibilitySummary)
            .accessibilityHint(isExpanded ? "Hides details" : "Shows details")

            if isExpanded {
                SignalDetailGrid(summary: summary)
                if !readings.isEmpty {
                    ObserverReadingsChart(summary: summary, readings: readings, scale: scale)
                    Text("Each dot is one reading, on the same scale as the bars. Shading marks the quality zones; the red line is the decode limit.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.vertical, 9)
    }

    private var typicalLine: String {
        let range = String(localized: "typical \(summary.low.formatted(.number.precision(.fractionLength(1)))) to \(formattedDB(summary.high))")
        let readings = summary.count == 1
            ? String(localized: "1 reading")
            : String(localized: "\(summary.count) readings")
        var line = "\(range) · \(readings)"
        if summary.hasFewReadings {
            line += " · " + String(localized: "few readings")
        }
        return line
    }

    private var accessibilitySummary: String {
        let quality = summary.quality?.label ?? String(localized: "quality unknown")
        return String(localized: "\(quality), median \(formattedDB(summary.median)), typically \(formattedDB(summary.low)) to \(formattedDB(summary.high)), \(summary.count) readings")
    }
}

/// Typical range (10th–90th percentile) on the shared scale, with a median tick
/// and the decode limit marked.
private struct SignalRangeBar: View {
    let summary: ObserverSignalSummary
    let scale: ClosedRange<Double>

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let height = geometry.size.height
            let tone = summary.quality?.color ?? NodeScopeStyle.signal
            ZStack(alignment: .leading) {
                Capsule().fill(Color.secondary.opacity(0.14))
                if let floor = summary.floor {
                    Rectangle()
                        .fill(SignalQuality.nearLimit.color.opacity(0.45))
                        .frame(width: 2, height: height)
                        .offset(x: x(floor, width) - 1)
                }
                Capsule()
                    .fill(tone.opacity(0.7))
                    .frame(width: max(x(summary.high, width) - x(summary.low, width), height), height: height)
                    .offset(x: x(summary.low, width))
                Rectangle()
                    .fill(Color.primary)
                    .frame(width: 2, height: height)
                    .offset(x: x(summary.median, width) - 1)
            }
        }
        .accessibilityHidden(true)
    }

    private func x(_ value: Double, _ width: CGFloat) -> CGFloat {
        let fraction = (value - scale.lowerBound) / (scale.upperBound - scale.lowerBound)
        return width * CGFloat(min(max(fraction, 0), 1))
    }
}

private struct SignalDetailGrid: View {
    let summary: ObserverSignalSummary

    private var items: [(label: LocalizedStringKey, value: String)] {
        var items: [(LocalizedStringKey, String)] = [
            ("Median SNR", formattedDB(summary.median)),
            ("Typical range", "\(summary.low.formatted(.number.precision(.fractionLength(1)))) to \(formattedDB(summary.high))"),
            ("Min / max", "\(summary.minimum.formatted(.number.precision(.fractionLength(1)))) / \(formattedDB(summary.maximum))")
        ]
        if let margin = summary.margin, let floor = summary.floor, let factor = summary.spreadingFactor {
            items.append(("Above decode limit", "\(formattedDB(margin)) (SF\(factor) limit \(formattedDB(floor)))"))
        }
        if let rssi = summary.medianRSSI {
            items.append(("Median RSSI", rssi.formatted(.number.precision(.fractionLength(0))) + " dBm"))
        }
        items.append(("Readings", summary.count.formatted()))
        if let lastHeard = summary.lastHeard {
            items.append(("Last heard", RelativeTime.string(from: lastHeard)))
        }
        return items
    }

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)],
                  alignment: .leading, spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.label)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(item.value)
                        .font(.caption)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(.top, 4)
    }
}

/// Every reading as a dot on the card's shared scale, over quality zones and the
/// decode limit, so a steady link reads as steady and its distance from failure
/// stays visible.
private struct ObserverReadingsChart: View {
    let summary: ObserverSignalSummary
    let readings: [NodeSignalPoint]
    let scale: ClosedRange<Double>

    private struct Zone: Identifiable {
        let quality: SignalQuality
        let low: Double
        let high: Double
        var id: SignalQuality { quality }
    }

    private var zones: [Zone] {
        guard let floor = summary.floor else { return [] }
        let edges: [(SignalQuality, Double, Double)] = [
            (.nearLimit, scale.lowerBound, floor + 5),
            (.weak, floor + 5, floor + 10),
            (.good, floor + 10, floor + 15),
            (.strong, floor + 15, scale.upperBound)
        ]
        return edges.compactMap { quality, low, high in
            let clampedLow = max(low, scale.lowerBound)
            let clampedHigh = min(high, scale.upperBound)
            return clampedHigh > clampedLow ? Zone(quality: quality, low: clampedLow, high: clampedHigh) : nil
        }
    }

    var body: some View {
        Chart {
            ForEach(zones) { zone in
                RectangleMark(yStart: .value("Zone start", zone.low), yEnd: .value("Zone end", zone.high))
                    .foregroundStyle(zone.quality.color.opacity(0.10))
            }
            if let floor = summary.floor {
                RuleMark(y: .value("Decode limit", floor))
                    .foregroundStyle(SignalQuality.nearLimit.color)
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
            }
            ForEach(Array(readings.enumerated()), id: \.offset) { _, reading in
                PointMark(x: .value("Time", reading.timestamp), y: .value("SNR", reading.snr))
                    .symbolSize(readings.count > 150 ? 12 : 24)
                    .foregroundStyle(color(for: reading.snr))
            }
        }
        .chartYScale(domain: scale)
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { value in
                AxisGridLine().foregroundStyle(.clear)
                AxisValueLabel {
                    if let db = value.as(Double.self) {
                        Text(db.formatted(.number.precision(.fractionLength(0))) + " dB")
                    }
                }
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 3)) {
                AxisGridLine().foregroundStyle(.clear)
                AxisValueLabel()
            }
        }
        .frame(height: 140)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(summary.observer) readings")
        .accessibilityValue(accessibilityValue)
    }

    private func color(for snr: Double) -> Color {
        guard let floor = summary.floor else { return NodeScopeStyle.signal }
        return SignalQuality(margin: snr - floor).color
    }

    private var accessibilityValue: String {
        var value = String(localized: "\(readings.count) readings from \(formattedDB(summary.minimum)) to \(formattedDB(summary.maximum))")
        if let floor = summary.floor {
            value += String(localized: ", decode limit \(formattedDB(floor))")
        }
        return value
    }
}

private struct SignalQualityLegend: View {
    var body: some View {
        HStack(spacing: 10) {
            ForEach(SignalQuality.allCases, id: \.self) { quality in
                HStack(spacing: 4) {
                    Circle().fill(quality.color).frame(width: 8, height: 8)
                    Text(quality.label)
                }
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
    }
}

private struct NodePacketTypesChart: View {
    let counts: [NodePacketTypeCount]

    private var sortedCounts: [NodePacketTypeCount] {
        counts.sorted { $0.count > $1.count }
    }

    var body: some View {
        NodeAnalyticsChartCard(
            title: "Packet Types",
            subtitle: "Traffic reported by payload",
            symbol: "shippingbox"
        ) {
            Chart(sortedCounts) { item in
                BarMark(
                    x: .value("Packets", item.count),
                    y: .value("Type", item.name)
                )
                .foregroundStyle(Color.blue.gradient)
                .annotation(position: .trailing) {
                    Text(item.count.formatted())
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .chartXAxis(.hidden)
            .frame(height: max(150, CGFloat(sortedCounts.count) * 34))
            .accessibilityLabel("Packet type distribution chart")
        }
    }
}

private struct NodeHopDistributionChart: View {
    let counts: [NodeHopCount]

    var body: some View {
        NodeAnalyticsChartCard(
            title: "Hop Count",
            subtitle: "How far packets traveled",
            symbol: "point.3.connected.trianglepath.dotted"
        ) {
            Chart(counts) { item in
                BarMark(
                    x: .value("Hops", item.hops),
                    y: .value("Packets", item.count)
                )
                .foregroundStyle(.primary)
            }
            .chartYAxis {
                AxisMarks(position: .leading)
            }
            .frame(height: 170)
            .accessibilityLabel("Hop count distribution chart")
        }
    }
}

private struct NodeAnalyticsChartCard<Content: View>: View {
    let title: LocalizedStringKey
    let subtitle: String
    let symbol: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: symbol)
                    .foregroundStyle(.primary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                        .accessibilityAddTraits(.isHeader)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            content
        }
        .padding(16)
        .instrumentCard()
    }
}

private struct NodeObserverCoverageCard: View {
    let observers: [NodeObserverCoverage]

    private var sortedObservers: [NodeObserverCoverage] {
        observers.sorted { $0.packetCount > $1.packetCount }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Label("Observer Coverage", systemImage: "eye")
                .font(.headline)
                .foregroundStyle(.primary)
                .accessibilityAddTraits(.isHeader)
                .padding(.bottom, 8)

            ForEach(Array(sortedObservers.enumerated()), id: \.element.id) { index, observer in
                if index > 0 {
                    Divider().opacity(0.35)
                }

                HStack(spacing: 12) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .foregroundStyle(.blue)
                        .frame(width: 28)

                    VStack(alignment: .leading, spacing: 3) {
                        Text(observer.observerName ?? observer.observerID)
                            .font(.subheadline.weight(.semibold))
                        Text("Last heard \(RelativeTime.string(from: observer.lastSeen))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    VStack(alignment: .trailing, spacing: 3) {
                        Text(observer.packetCount.formatted())
                            .font(.subheadline.weight(.semibold))
                        Text(observer.avgSnr.map { String(format: "%.1f dB", $0) } ?? "No SNR")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 10)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(observer.observerName ?? observer.observerID)
                .accessibilityValue(
                    "\(observer.packetCount.formatted()) packets, "
                        + (observer.avgSnr.map { String(format: "%.1f dB average SNR", $0) } ?? "no SNR")
                        + ", last heard \(RelativeTime.string(from: observer.lastSeen))"
                )
            }
        }
        .padding(16)
        .instrumentCard()
    }
}

private struct NodePeerInteractionsCard: View {
    let peers: [NodePeerInteraction]

    private var sortedPeers: [NodePeerInteraction] {
        peers.sorted { $0.messageCount > $1.messageCount }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Label("Peer Interactions", systemImage: "person.2")
                .font(.headline)
                .foregroundStyle(.primary)
                .accessibilityAddTraits(.isHeader)
                .padding(.bottom, 8)

            ForEach(Array(sortedPeers.enumerated()), id: \.element.id) { index, peer in
                if index > 0 {
                    Divider().opacity(0.35)
                }

                HStack(spacing: 12) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .foregroundStyle(.primary)
                        .frame(width: 28)

                    VStack(alignment: .leading, spacing: 3) {
                        Text(peer.peerName)
                            .font(.subheadline.weight(.semibold))
                        Text("Last contact \(RelativeTime.string(from: peer.lastContact))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Text("\(peer.messageCount.formatted()) messages")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 10)
                .accessibilityElement(children: .combine)
            }
        }
        .padding(16)
        .instrumentCard()
    }
}
