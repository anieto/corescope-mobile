import SwiftUI

/// Every term NodeScope explains, grouped as in glossary.json. Opened from Settings, or from an ⓘ
/// popover with that term scrolled into view and highlighted.
struct GlossaryScreen: View {
    var focus: GlossaryTerm?

    var body: some View {
        ScrollViewReader { proxy in
            List {
                Section {
                    Text("Short explanations of the words NodeScope uses. Tap \(Image(systemName: "info.circle")) beside a term anywhere in the app to see its entry.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                ForEach(Glossary.sections) { section in
                    Section(section.title) {
                        ForEach(section.entries) { entry in
                            GlossaryRow(entry: entry)
                                .id(entry.id)
                                .listRowBackground(focus?.rawValue == entry.id ? GlossaryFocusBackground() : nil)
                        }
                    }
                }
            }
            .meshPageBackground()
            .navigationTitle("Glossary")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                guard let focus else { return }
                proxy.scrollTo(focus.rawValue, anchor: .top)
            }
        }
    }
}

/// The opened term's row: the normal row surface with a light accent tint and an accent border
/// inset from the edges, so the section's rounded corners don't clip it and it reads as highlighted
/// in dark mode too.
private struct GlossaryFocusBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            Color(uiColor: .secondarySystemGroupedBackground)
            NodeScopeStyle.signal.opacity(colorScheme == .dark ? 0.14 : 0.07)
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(NodeScopeStyle.signal, lineWidth: 2)
                .padding(3)
        }
    }
}

private struct GlossaryRow: View {
    let entry: GlossaryEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.term)
                .font(.monaspace(.headline))
            Text(entry.summary)
                .font(.subheadline)
            if let detail = entry.detail {
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
