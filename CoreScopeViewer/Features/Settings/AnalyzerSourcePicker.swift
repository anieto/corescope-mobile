import SwiftUI

struct AnalyzerSourcePickerScreen: View {
    @Environment(AnalyzerSourceRegistry.self) private var sourceRegistry
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selectedHost: String
    let appliesSelectionImmediately: Bool
    let onSelection: ((String) -> Void)?

    @State private var customHost = ""

    var body: some View {
        List {
            Section {
                ForEach(sourceRegistry.sources) { source in
                    Button {
                        select(source.host)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: source.isDefault ? "star.circle.fill" : "antenna.radiowaves.left.and.right.circle.fill")
                                .font(.title3)
                                .foregroundStyle(source.isDefault ? .yellow : Color.accentColor)
                                .frame(width: 30)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(source.name)
                                    .foregroundStyle(Color.primary)
                                Text(source.subtitle)
                                    .font(.caption)
                                    .foregroundStyle(metadataColor)
                                Text(source.host)
                                    .font(.caption2)
                                    .foregroundStyle(metadataColor)
                            }

                            Spacer()
                            if source.host.caseInsensitiveCompare(selectedHost) == .orderedSame {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(Color.accentColor)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel("Use \(source.name), \(source.subtitle)")
                    .accessibilityValue(
                        source.host.caseInsensitiveCompare(selectedHost) == .orderedSame ? "Selected" : ""
                    )
                    .buttonStyle(.plain)
                }
            } header: {
                Text("US Community Sources")
            } footer: {
                Text("Community sources are fetched from NodeScope’s source registry when available.")
                    .foregroundStyle(metadataColor)
            }

            Section {
                TextField(
                    text: $customHost,
                    prompt: Text("analyzer.example.org").foregroundStyle(metadataColor)
                ) {
                    Text("Analyzer hostname")
                        .foregroundStyle(Color.primary)
                }
                .foregroundStyle(Color.primary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)

                Button("Use Custom Analyzer") {
                    select(customHost)
                }
                .buttonStyle(.plain)
                .foregroundStyle(
                    customHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        ? metadataColor
                        : Color.accentColor
                )
                .disabled(customHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } header: {
                Text("Custom Analyzer")
            } footer: {
                Text("Enter the hostname for a compatible public analyzer. NodeScope connects directly to it.")
                    .foregroundStyle(metadataColor)
            }
        }
        .adaptiveContentWidth()
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Analyzer Source")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await sourceRegistry.refresh()
        }
    }

    private var metadataColor: Color {
        if colorScheme == .dark {
            return Color(red: 0.73, green: 0.82, blue: 0.95)
        }
        return Color(uiColor: .secondaryLabel)
    }

    private func select(_ host: String) {
        let normalizedHost = AnalyzerSettings.normalizedHost(host)
        guard !normalizedHost.isEmpty else { return }
        selectedHost = normalizedHost
        onSelection?(normalizedHost)
        if appliesSelectionImmediately {
            dismiss()
        }
    }
}
