import SwiftUI

struct AnalyzerSourcePickerScreen: View {
    @Environment(AnalyzerSourceRegistry.self) private var sourceRegistry
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selectedHost: String
    let appliesSelectionImmediately: Bool
    let onSelection: ((String) -> Void)?

    @State private var customHost = ""
    @FocusState private var isCustomHostFocused: Bool

    private enum ScrollTarget: Hashable {
        case customAnalyzer
    }

    var body: some View {
        ScrollViewReader { proxy in
            List {
                Section {
                    ForEach(sourceRegistry.sources) { source in
                        Button {
                            select(source.host)
                        } label: {
                            HStack(spacing: 12) {
                                AnalyzerSourceLogo(source: source)

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
                    .submitLabel(.go)
                    .focused($isCustomHostFocused)
                    .onSubmit {
                        select(customHost)
                    }

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
                    .id(ScrollTarget.customAnalyzer)
                } header: {
                    Text("Custom Analyzer")
                } footer: {
                    Text("Enter the hostname for a compatible public analyzer. NodeScope connects directly to it.")
                        .foregroundStyle(metadataColor)
                }

                Section {
                    if let communitySourceRequestURL {
                        Link(destination: communitySourceRequestURL) {
                            Label("Request a Community Source", systemImage: "envelope")
                        }
                        .accessibilityHint("Opens a prefilled email to the NodeScope team")
                    }
                } footer: {
                    Text("Suggest a public analyzer for the community list. Please confirm that its owner or operator permits it to be listed and used in NodeScope.")
                        .foregroundStyle(metadataColor)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: isCustomHostFocused) { _, isFocused in
                guard isFocused else { return }
                Task { @MainActor in
                    // Wait for the keyboard-adjusted viewport before bringing
                    // the field's action row above the floating app dock.
                    try? await Task.sleep(for: .milliseconds(250))
                    guard isCustomHostFocused else { return }
                    withAnimation(.easeOut(duration: 0.25)) {
                        proxy.scrollTo(ScrollTarget.customAnalyzer, anchor: .center)
                    }
                }
            }
            .adaptiveContentWidth()
            .floatingDockScrollClearance()
            .refreshable {
                await sourceRegistry.refresh()
            }
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Analyzer Source")
            .navigationBarTitleDisplayMode(.inline)
            .task {
                await sourceRegistry.refresh()
            }
        }
    }

    private var metadataColor: Color {
        if colorScheme == .dark {
            return Color(red: 0.73, green: 0.82, blue: 0.95)
        }
        return Color(uiColor: .secondaryLabel)
    }

    private var communitySourceRequestURL: URL? {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = "betweenthieves@protonmail.com"
        components.queryItems = [
            URLQueryItem(name: "subject", value: "NodeScope community source request"),
            URLQueryItem(
                name: "body",
                value: communitySourceRequestBody
            )
        ]
        return components.url
    }

    private var communitySourceRequestBody: String {
        [
            "Hello NodeScope team,",
            "",
            "I’d like to request that the following community source be added.",
            "",
            "SOURCE DETAILS",
            "Source name:",
            "Analyzer hostname or URL:",
            "Region or community:",
            "Owner or operator contact:",
            "",
            "PERMISSION",
            "Do you have permission from the source owner or operator for this source to be listed and used in NodeScope?",
            "Answer: Yes / No / Not yet",
            "",
            "ADDITIONAL DETAILS",
            "",
            "Thank you."
        ].joined(separator: "\r\n")
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
