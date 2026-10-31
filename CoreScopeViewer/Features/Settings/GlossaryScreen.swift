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

/// The opened term's row: the normal row surface with an accent tint and a leading bar on top, so
/// it reads as highlighted in dark mode too rather than letting the page show through.
private struct GlossaryFocusBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack(alignment: .leading) {
            Color(uiColor: .secondarySystemGroupedBackground)
            NodeScopeStyle.signal.opacity(colorScheme == .dark ? 0.20 : 0.10)
            NodeScopeStyle.signal
                .frame(width: 4)
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
