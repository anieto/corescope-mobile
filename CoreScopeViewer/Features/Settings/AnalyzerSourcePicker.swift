import CoreLocation
import SwiftUI

struct AnalyzerSourcePickerScreen: View {
    @Environment(AnalyzerSourceRegistry.self) private var sourceRegistry
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale
    @Binding var selectedHost: String
    let appliesSelectionImmediately: Bool
    let onSelection: ((String) -> Void)?

    @State private var customHost = ""
    @State private var locationManager = MapLocationManager()
    @State private var location: CLLocationCoordinate2D?
    @State private var isLocating = false

    private var choices: AnalyzerSourceChoices {
        AnalyzerSourceChoices(sources: sourceRegistry.sources, displayLocale: locale)
    }
    @FocusState private var isCustomHostFocused: Bool

    private enum ScrollTarget: Hashable {
        case customAnalyzer
    }

    var body: some View {
        ScrollViewReader { proxy in
            List {
                if choices.customFirst {
                    customSection
                    requestSection
                }
                communitySections
                if !choices.customFirst {
                    customSection
                    requestSection
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
            .refreshable {
                await sourceRegistry.refresh()
            }
            .meshPageBackground()
            .navigationTitle("Analyzer Source")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        if location == nil {
                            sortByNearest()
                        } else {
                            location = nil
                        }
                    } label: {
                        if location == nil {
                            Label("Sort by nearest", systemImage: "location")
                        } else {
                            Label("Group by country", systemImage: "globe")
                        }
                    }
                    .disabled(isLocating)
                }
            }
            .onDisappear {
                locationManager.cancelRequest()
                isLocating = false
                location = nil
            }
            .task {
                await sourceRegistry.refresh()
            }
        }
    }

    @ViewBuilder
    private var communitySections: some View {
        if let location {
            communitySection(choices.nearest(to: location), title: String(localized: "Nearest to you"),
                             showsFooter: true)
        } else {
            let sections = choices.countrySections
            ForEach(sections) { section in
                communitySection(section.sources, title: section.name ?? String(localized: "Other"),
                                 showsFooter: section.id == sections.last?.id)
            }
        }
    }

    private func communitySection(_ sources: [AnalyzerSource], title: String, showsFooter: Bool) -> some View {
        Section {
            ForEach(sources, content: sourceRow)
        } header: {
            Text(title).font(.monaspace(.footnote))
        } footer: {
            if showsFooter {
                Text("Community sources are fetched from NodeScope’s source registry when available.")
                    .foregroundStyle(metadataColor)
            }
        }
        // Resolve the row fill from the picker so reused list backgrounds share the text's
        // appearance when UIKit reattaches them after foregrounding.
        .instrumentListRows(colorScheme: colorScheme)
    }

    private func sourceRow(_ source: AnalyzerSource) -> some View {
        let area = serviceAreaLabel(for: source)
        return Button {
            select(source.host)
        } label: {
            HStack(spacing: 12) {
                AnalyzerSourceLogo(source: source)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(source.name)
                            .font(.monaspace(.body))
                            .foregroundStyle(Color.primary)
                        AnalyzerSourceTypeLabel(type: source.backendType, color: metadataColor)
                    }
                    if !source.displaySubtitle.isEmpty {
                        Text(source.displaySubtitle)
                            .font(.caption)
                            .italic()
                            .foregroundStyle(metadataColor)
                    }
                    if let area {
                        Text(area)
                            .font(.caption)
                            .foregroundStyle(Color.primary)
                    } else {
                        Text(source.host)
                            .font(.monaspace(.caption2))
                            .foregroundStyle(metadataColor)
                    }
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
        .accessibilityLabel(
            source.backendType == .beacon
                ? "Use \(source.name), Beacon beta server, \(source.displaySubtitle), \(area ?? source.host)"
                : "Use \(source.name), CoreScope server, \(source.displaySubtitle), \(area ?? source.host)"
        )
        .accessibilityValue(
            source.host.caseInsensitiveCompare(selectedHost) == .orderedSame ? "Selected" : ""
        )
        .buttonStyle(.plain)
    }

    private func serviceAreaLabel(for source: AnalyzerSource) -> String? {
        let areas = source.serviceAreas ?? []
        let countries = Array(Set(areas.map(\.country))).sorted()
        let labels = countries.map { country in
            let labels = areas.filter { $0.country == country }.compactMap { area -> String? in
                let region = area.subdivisionName ?? area.subdivision
                if let metros = area.metros, metros.count == 1, let metro = metros.first {
                    return [metro.name, region].compactMap { $0 }.joined(separator: ", ")
                }
                return region
            }
            let countryName = locale.localizedString(forRegionCode: country) ?? country
            if labels.isEmpty { return countryName }
            let regions = ListFormatter.localizedString(byJoining: labels)
            return country == "US" && countries.count == 1 ? regions : "\(regions), \(countryName)"
        }
        return labels.isEmpty ? nil : labels.joined(separator: " · ")
    }

    private var customSection: some View {
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

            Button {
                select(customHost)
            } label: {
                Text("Use Custom Analyzer")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding(.vertical, 4)
            .disabled(customHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .id(ScrollTarget.customAnalyzer)
        } header: {
            Text("Custom Analyzer").font(.monaspace(.footnote))
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("Enter the hostname for a compatible public analyzer. NodeScope connects directly to it.")
                if choices.customFirst {
                    Text("Ask your local mesh community for their analyzer’s address.")
                }
            }
            .foregroundStyle(metadataColor)
        }
        .instrumentListRows(colorScheme: colorScheme)
    }

    private var requestSection: some View {
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
        .instrumentListRows(colorScheme: colorScheme)
    }

    private func sortByNearest() {
        guard !isLocating, location == nil else { return }
        isLocating = true
        locationManager.requestCurrentLocation {
            location = $0
            isLocating = false
        } failure: {
            isLocating = false
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
        let country = choices.userCountryName ?? ""
        return [
            String(localized: "Hello NodeScope team,"),
            "",
            String(localized: "I’d like to request that the following community source be added."),
            "",
            String(localized: "SOURCE DETAILS"),
            String(localized: "Source name:"),
            String(localized: "Analyzer hostname or URL:"),
            String(localized: "Country: \(country)"),
            String(localized: "Home city or area:"),
            String(localized: "Short description (one line, shown under the name):"),
            String(localized: "Region code(s), if you use any (e.g. LFT):"),
            String(localized: "Owner or operator contact:"),
            "",
            String(localized: "LOGO"),
            String(localized: "Please attach a square logo (PNG, at least 256×256), or paste a link to one."),
            String(localized: "If you don’t have one, we’ll use your analyzer’s site icon."),
            "",
            String(localized: "PERMISSION"),
            String(localized: "Do you have permission from the source owner or operator for this source to be listed and used in NodeScope?"),
            String(localized: "Answer: Yes / No / Not yet"),
            "",
            String(localized: "ADDITIONAL DETAILS"),
            "",
            String(localized: "Thank you.")
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

/// Which server software a listed source runs, so Beacon sources are recognizable before choosing.
private struct AnalyzerSourceTypeLabel: View {
    let type: AnalyzerSourceType
    let color: Color

    var body: some View {
        Text(type == .beacon ? "Beacon · Beta" : "CoreScope")
            .font(.caption2.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .overlay(Capsule().strokeBorder(color.opacity(0.5)))
            .accessibilityHidden(true)
    }
}

extension AnalyzerSourceType {
    var displayName: String {
        switch self {
        case .coreScope: String(localized: "CoreScope")
        case .beacon: String(localized: "Beacon")
        }
    }
}
