import SwiftUI

struct CoverageDiagnosticsView: View {
    let report: String?

    var body: some View {
        if let report {
            DisclosureGroup("Last calculation report") {
                Text("Includes coordinates, radio assumptions, work counters and available-memory observations. A run left in progress does not identify why the app stopped.")
                    .font(.caption).foregroundStyle(.secondary)
                ShareLink("Share calculation report", item: report)
                Text(report).font(.monaspace(.caption2)).textSelection(.enabled)
            }
            .padding()
            .instrumentCard(showsShadow: false)
        }
    }
}
