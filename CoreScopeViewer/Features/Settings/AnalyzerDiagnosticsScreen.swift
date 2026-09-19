import SwiftUI

struct AnalyzerDiagnosticsScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(LiveFeedService.self) private var liveFeed

    @State private var report: AnalyzerDiagnosticReport?
    @State private var isRunning = false

    var body: some View {
        List {
            AnalyzerDiagnosticSummarySection(
                host: settings.host,
                report: report,
                isRunning: isRunning
            )

            AnalyzerLiveUpdatesSection(
                isConnected: liveFeed.isConnected,
                error: liveFeed.lastError
            )

            AnalyzerCapabilitiesSection(
                report: report,
                isRunning: isRunning
            )

            Section {
                Button {
                    Task { await runDiagnostics() }
                } label: {
                    Label(
                        isRunning ? "Running Diagnostics…" : "Run Diagnostics Again",
                        systemImage: "arrow.clockwise"
                    )
                }
                .disabled(isRunning)
            } footer: {
                Text("Diagnostics make read-only requests to the selected analyzer. No settings or analyzer data are changed.")
            }
        }
        .floatingDockScrollClearance()
        .navigationTitle("Analyzer Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: settings.host) {
            await runDiagnostics()
        }
        .refreshable {
            await runDiagnostics()
        }
    }

    private func runDiagnostics() async {
        guard !isRunning else { return }
        isRunning = true
        let service = AnalyzerDiagnosticsService(settings: settings)
        report = await service.run()
        isRunning = false
    }
}

private struct AnalyzerDiagnosticSummarySection: View {
    let host: String
    let report: AnalyzerDiagnosticReport?
    let isRunning: Bool

    var body: some View {
        Section("Connection") {
            LabeledContent("Analyzer") {
                Text(host)
                    .font(.caption.monospaced())
                    .multilineTextAlignment(.trailing)
            }

            if let report {
                AnalyzerDiagnosticRow(
                    title: "HTTPS connection",
                    detail: report.connectionDetail,
                    systemImage: "lock.shield",
                    level: report.connectionLevel
                )
                AnalyzerDiagnosticRow(
                    title: "API compatibility",
                    detail: report.compatibilityDetail,
                    systemImage: "checkmark.seal",
                    level: report.compatibilityLevel
                )
                LabeledContent("Last checked") {
                    Text(report.checkedAt, format: .relative(presentation: .named))
                        .foregroundStyle(.secondary)
                }
            } else if isRunning {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Checking analyzer…")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct AnalyzerLiveUpdatesSection: View {
    let isConnected: Bool
    let error: String?

    var body: some View {
        Section {
            AnalyzerDiagnosticRow(
                title: "Live packet stream",
                detail: detail,
                systemImage: "wave.3.right",
                level: isConnected ? .success : .warning
            )
        } header: {
            Text("Live Updates")
        } footer: {
            Text("The live stream uses a separate WebSocket connection, so it can reconnect independently of the read-only API.")
        }
    }

    private var detail: String {
        if isConnected {
            return "Connected"
        }
        if let error, !error.isEmpty {
            return "Disconnected · \(error)"
        }
        return "Connecting or temporarily unavailable"
    }
}

private struct AnalyzerCapabilitiesSection: View {
    let report: AnalyzerDiagnosticReport?
    let isRunning: Bool

    var body: some View {
        Section("Capabilities") {
            if let report {
                ForEach(report.capabilities) { check in
                    AnalyzerDiagnosticRow(
                        title: check.capability.title,
                        detail: check.detail,
                        systemImage: check.capability.systemImage,
                        level: check.level
                    )
                }
            } else if isRunning {
                ForEach(AnalyzerCapability.allCases) { capability in
                    HStack(spacing: 12) {
                        Image(systemName: capability.systemImage)
                            .foregroundStyle(.secondary)
                            .frame(width: 24)
                        Text(capability.title)
                        Spacer()
                        ProgressView()
                    }
                }
            } else {
                ContentUnavailableView(
                    "No Diagnostic Results",
                    systemImage: "stethoscope",
                    description: Text("Run diagnostics to check this analyzer.")
                )
            }
        }
    }
}

private struct AnalyzerDiagnosticRow: View {
    let title: String
    let detail: String
    let systemImage: String
    let level: AnalyzerDiagnosticLevel

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage)
                .foregroundStyle(level.color)
                .frame(width: 24)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 8)

            Image(systemName: level.systemImage)
                .foregroundStyle(level.color)
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title), \(level.accessibilityLabel)")
        .accessibilityValue(detail)
    }
}

private extension AnalyzerDiagnosticLevel {
    var color: Color {
        switch self {
        case .success: NodeScopeStyle.healthy
        case .warning: NodeScopeStyle.activity
        case .failure: Color.red
        }
    }

    var systemImage: String {
        switch self {
        case .success: "checkmark.circle.fill"
        case .warning: "exclamationmark.triangle.fill"
        case .failure: "xmark.circle.fill"
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .success: "available"
        case .warning: "limited"
        case .failure: "unavailable"
        }
    }
}
