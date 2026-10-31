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
                                .listRowBackground(focus?.rawValue == entry.id ? Color.accentColor.opacity(0.14) : nil)
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

private struct GlossaryRow: View {
    let entry: GlossaryEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.term)
                .font(.headline)
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
