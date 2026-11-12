import SwiftUI

struct CoverageMarginLegendButton: View {
    @State private var showsLegend = false

    var body: some View {
        Button { showsLegend = true } label: {
            VStack(alignment: .leading, spacing: 4) {
                Label("Predicted margin", systemImage: "info.circle")
                    .font(.caption.weight(.semibold))
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        CoverageMarginSwatch(color: .red, title: "<0")
                        CoverageMarginSwatch(color: .orange, title: "0–10")
                        CoverageMarginSwatch(color: .green, title: ">10 dB")
                        CoverageMarginSwatch(color: .gray, title: "?")
                    }
                    Text("Tap for color bands").font(.caption)
                }
            }
            .padding(10)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Predicted margin after reserve. Show legend and limitations.")
        .sheet(isPresented: $showsLegend) { CoverageMarginLegendSheet() }
    }
}

private struct CoverageMarginSwatch: View {
    let color: Color
    let title: LocalizedStringKey

    var body: some View {
        Label { Text(title).font(.caption2) } icon: {
            RoundedRectangle(cornerRadius: 2).fill(color.opacity(0.7)).frame(width: 10, height: 10)
        }
    }
}

private struct CoverageMarginLegendSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Margin after planning reserve") {
                    Label("Red · Below 0 dB: predicted below threshold", systemImage: "minus.circle")
                    Label("Amber · 0–10 dB: limited predicted headroom", systemImage: "exclamationmark.circle")
                    Label("Green · Above 10 dB: greater predicted headroom", systemImage: "plus.circle")
                    Label("Gray hatching · No usable prediction", systemImage: "questionmark.circle")
                    Text("Outside the study radius is unshaded. The area within 250 m of the transmitter is unsupported and hatched.")
                }.instrumentListRows()
                Section("What this means") {
                    Text("Margin is the predicted received signal minus receiver sensitivity and your planning reserve. Colors use unrounded values.")
                    Text("Green does not mean reachable. This is a one-way terrain prediction, not a test of packet delivery, return-link strength, modem compatibility or interference.")
                    Text("Map cells use the nearest calculated sample. Known nodes are calculated at their exact coordinates and can differ from the surrounding cell.")
                }.instrumentListRows()
            }
            .meshPageBackground()
            .navigationTitle("Coverage colors")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
