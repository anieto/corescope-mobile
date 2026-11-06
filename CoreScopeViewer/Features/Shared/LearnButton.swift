import SwiftUI

/// An ⓘ beside a term. It opens a short explanation from the glossary, with a way on to the full
/// Glossary for more.
struct LearnButton: View {
    let term: GlossaryTerm
    @AppStorage(Glossary.showsExplanationsDefaultsKey) private var showsExplanations = true
    @State private var isShowingSummary = false
    @State private var isShowingGlossary = false
    /// Set from the popover; the Glossary opens once the popover has gone, since a sheet can't be
    /// presented while the popover is still leaving.
    @State private var opensGlossaryAfterSummary = false
    /// Where the ⓘ sits on screen, to open its explanation above or below it.
    @State private var globalMidY: CGFloat = 0
    @State private var arrowEdge: Edge = .bottom

    var body: some View {
        if showsExplanations, let entry = Glossary.entry(term) {
            Button {
                // Above or below, never beside: beside an ⓘ near the screen's edge there wasn't
                // room for the explanation's width and its leading side was cut off.
                let screenHeight = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?
                    .screen.bounds.height ?? 800
                arrowEdge = globalMidY > screenHeight / 2 ? .top : .bottom
                isShowingSummary = true
            } label: {
                Image(systemName: "info.circle")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(.secondary)
                    // A bigger target than the glyph, without moving the text around it.
                    .padding(8)
                    .contentShape(Rectangle())
                    .padding(-8)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("About \(entry.term)")
            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).midY } action: { globalMidY = $0 }
            .popover(isPresented: $isShowingSummary, attachmentAnchor: .rect(.bounds), arrowEdge: arrowEdge) {
                LearnSummary(entry: entry) {
                    opensGlossaryAfterSummary = true
                    isShowingSummary = false
                }
                .onDisappear {
                    guard opensGlossaryAfterSummary else { return }
                    opensGlossaryAfterSummary = false
                    isShowingGlossary = true
                }
                .presentationCompactAdaptation(.popover)
            }
            .sheet(isPresented: $isShowingGlossary) {
                NavigationStack {
                    GlossaryScreen(focus: term)
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Done") { isShowingGlossary = false }
                            }
                        }
                }
            }
        }
    }
}

/// A row title followed by its ⓘ, for LabeledContent and similar rows. An HStack rather than a
/// Label: a Label inside LabeledContent inflated Form rows.
struct LearnLabel: View {
    let title: LocalizedStringKey
    let term: GlossaryTerm

    init(_ title: LocalizedStringKey, term: GlossaryTerm) {
        self.title = title
        self.term = term
    }

    var body: some View {
        HStack(spacing: 4) {
            Text(title)
            LearnButton(term: term)
        }
    }
}

private struct LearnSummary: View {
    let entry: GlossaryEntry
    let openGlossary: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(entry.term)
                .font(.monaspace(.headline))
            Text(entry.summary)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: openGlossary) {
                Label("More in Glossary", systemImage: "book.closed")
                    .font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.borderless)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.top, 2)
        }
        .padding()
        // Up to 300 pt, narrower on a small screen, rather than a fixed width that could overrun
        // the popover and be clipped.
        .frame(idealWidth: 300, maxWidth: 300, alignment: .leading)
    }
}
