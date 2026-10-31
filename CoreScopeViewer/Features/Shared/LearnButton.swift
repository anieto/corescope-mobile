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

    var body: some View {
        if showsExplanations, let entry = Glossary.entry(term) {
            Button {
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
            .popover(isPresented: $isShowingSummary) {
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
                .font(.headline)
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
        .frame(width: 300, alignment: .leading)
    }
}
