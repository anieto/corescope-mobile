import SwiftUI

struct OnboardingScreen: View {
    @Environment(AnalyzerSourceRegistry.self) private var sourceRegistry
    @Environment(AnalyzerSettings.self) private var settings
    let onComplete: () -> Void

    @Environment(\.locale) private var locale
    @State private var chosenHost: String?

    private var choices: AnalyzerSourceChoices {
        AnalyzerSourceChoices(sources: sourceRegistry.sources, displayLocale: locale)
    }

    private var selectedHost: String {
        chosenHost ?? choices.onboardingHost(savedHost: settings.savedHost, isOnboarded: false) ?? ""
    }
    @State private var showsSourcePicker = false
    @State private var step: Step = .source
    @State private var accountSupportedHost: String?

    private enum Step {
        case source
        case overview
    }

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .source:
                    sourceSelection
                case .overview:
                    GeometryReader { viewport in
                        ScrollView {
                            overview.frame(minHeight: viewport.size.height)
                        }
                    }
                    .task(id: selectedHost) { await checkAccountSupport(host: selectedHost) }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .meshPageBackground()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if step == .overview {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Back") {
                            withAnimation {
                                step = .source
                            }
                        }
                    }
                }
            }
            .sheet(isPresented: $showsSourcePicker) {
                NavigationStack {
                    AnalyzerSourcePickerScreen(
                        selectedHost: Binding(get: { selectedHost }, set: { chosenHost = $0 }),
                        appliesSelectionImmediately: true,
                        onSelection: nil
                    )
                }
            }
            .task {
                await sourceRegistry.refresh()
            }
        }
    }

    private var sourceSelection: some View {
        VStack(spacing: 24) {
            Spacer()

            if let appIcon = UIImage.appIcon {
                Image(uiImage: appIcon)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 96, height: 96)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .stroke(.white.opacity(0.15), lineWidth: 1)
                    }
                    .shadow(color: .black.opacity(0.15), radius: 8, y: 4)
                    .accessibilityHidden(true)
            } else {
                Image(systemName: "antenna.radiowaves.left.and.right.circle.fill")
                    .font(.system(size: 76))
                    .foregroundStyle(Color.accentColor)
            }

            VStack(spacing: 10) {
                Text("Welcome to NodeScope")
                    .font(.monaspace(.largeTitle).weight(.bold))
                Text("Choose a community analyzer to explore its live nodes, routes, channels, and observers.")
                    .font(.body)
                    .foregroundStyle(Color.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)
            }

            Button {
                showsSourcePicker = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .foregroundStyle(Color.accentColor)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(selectedSourceName)
                            .font(.monaspace(.headline))
                            .foregroundStyle(Color.primary)
                        if !selectedHost.isEmpty {
                            Text(selectedHost)
                                .font(.monaspace(.caption))
                                .foregroundStyle(Color.secondary)
                        }
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.accentColor)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .instrumentCard(fillOpacity: 1)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)
            .accessibilityLabel("Analyzer source: \(selectedSourceName)")

            Group {
                if selectedHost.isEmpty, let country = choices.userCountryName {
                    Text("No community is listed for \(country) yet. Pick one to look around, enter your own analyzer, or request a listing for yours.")
                } else if selectedHost.isEmpty {
                    Text("No community is listed for your region yet. Pick one to look around, enter your own analyzer, or request a listing for yours.")
                } else {
                    Text("You can change sources anytime in Settings, or add a custom analyzer.")
                }
            }
            .font(.caption)
            .foregroundStyle(Color.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 32)

            Spacer()

            Button("Continue") {
                chosenHost = selectedHost
                withAnimation {
                    step = .overview
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(selectedHost.isEmpty)
            .padding(.bottom, 28)
        }
        .adaptiveContentWidth(720)
    }

    private var overview: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "map.fill")
                .font(.system(size: 64))
                .foregroundStyle(Color.accentColor)

            VStack(spacing: 8) {
                Text("Follow the Mesh")
                    .font(.monaspace(.largeTitle).weight(.bold))
                Text("NodeScope makes live activity easier to understand at a glance.")
                    .font(.body)
                    .foregroundStyle(Color.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)
            }

            VStack(alignment: .leading, spacing: 16) {
                OnboardingFeatureRow(
                    icon: "circle.fill",
                    title: String(localized: "Explore nodes"),
                    detail: String(localized: "Tap a node for its details. Clusters zoom in to reveal nearby nodes.")
                )
                OnboardingFeatureRow(
                    icon: "point.topleft.down.curvedto.point.bottomright.up",
                    title: String(localized: "Watch packet routes"),
                    detail: String(localized: "Live routes animate hop by hop. Open a channel message to replay its path.")
                )
                OnboardingFeatureRow(
                    icon: "line.3.horizontal.decrease.circle",
                    title: String(localized: "Focus your view"),
                    detail: String(localized: "Choose a region on the map to narrow traffic and center the view.")
                )
            }
            .padding(18)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(.horizontal, 24)

            if accountSupportedHost == selectedHost {
                Text("You can browse without an account. To sync saved nodes, sign in under Settings → CoreScope Account.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)
            }

            Text("Location is optional. It lets you center the map on yourself and sort analyzer sources by distance.")
                .font(.caption)
                .foregroundStyle(Color.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Spacer()

            Button("Start Exploring") {
                settings.host = selectedHost
                onComplete()
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.bottom, 28)
        }
        .adaptiveContentWidth(720)
    }

    private func checkAccountSupport(host: String) async {
        accountSupportedHost = nil
        let normalized = AnalyzerSettings.normalizedHost(host)
        guard !normalized.isEmpty,
              !sourceRegistry.sources.contains(where: {
                  AnalyzerSettings.normalizedHost($0.host).caseInsensitiveCompare(normalized) == .orderedSame
                      && $0.backendType == .beacon
              }),
              let origin = URL(string: "https://\(normalized)"),
              let client = try? CoreScopeAccountClient(origin: origin),
              (try? await client.supportsAccounts()) == true,
              !Task.isCancelled, host == selectedHost else { return }
        accountSupportedHost = host
    }

    private var selectedSourceName: String {
        guard !selectedHost.isEmpty else { return String(localized: "Choose an analyzer") }
        return sourceRegistry.sources.first {
            $0.host.caseInsensitiveCompare(selectedHost) == .orderedSame
        }?.name ?? String(localized: "Custom Analyzer")
    }
}

private struct OnboardingFeatureRow: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.body)
                .foregroundStyle(Color.accentColor)
                .frame(width: 22)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.primary)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(Color.secondary)
            }
        }
    }
}
