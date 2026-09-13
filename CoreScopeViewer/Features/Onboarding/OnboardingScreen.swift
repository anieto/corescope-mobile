import SwiftUI

struct OnboardingScreen: View {
    @Environment(AnalyzerSourceRegistry.self) private var sourceRegistry
    @Environment(AnalyzerSettings.self) private var settings
    let onComplete: () -> Void

    @State private var selectedHost = ""
    @State private var showsSourcePicker = false
    @State private var step: Step = .source

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
                    overview
                }
            }
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
                        selectedHost: $selectedHost,
                        appliesSelectionImmediately: true,
                        onSelection: nil
                    )
                }
            }
            .task {
                if selectedHost.isEmpty {
                    selectedHost = settings.host
                }
                await sourceRegistry.refresh()
                if selectedHost.isEmpty {
                    selectedHost = sourceRegistry.defaultSource?.host ?? ""
                }
            }
        }
    }

    private var sourceSelection: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "antenna.radiowaves.left.and.right.circle.fill")
                .font(.system(size: 76))
                .foregroundStyle(Color.accentColor)

            VStack(spacing: 10) {
                Text("Welcome to NodeScope")
                    .font(.largeTitle.weight(.bold))
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
                            .font(.headline)
                            .foregroundStyle(Color.primary)
                        Text(selectedHost.isEmpty ? "Choose an analyzer" : selectedHost)
                            .font(.caption)
                            .foregroundStyle(Color.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.secondary)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)
            .accessibilityLabel("Analyzer source: \(selectedSourceName)")

            Text("You can change sources anytime in Settings, or add a custom analyzer.")
                .font(.caption)
                .foregroundStyle(Color.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Spacer()

            Button("Continue") {
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
                    .font(.largeTitle.weight(.bold))
                Text("NodeScope makes live activity easier to understand at a glance.")
                    .font(.body)
                    .foregroundStyle(Color.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)
            }

            VStack(alignment: .leading, spacing: 16) {
                OnboardingFeatureRow(
                    icon: "circle.fill",
                    title: "Explore nodes",
                    detail: "Tap a node for its details. Clusters zoom in to reveal nearby nodes."
                )
                OnboardingFeatureRow(
                    icon: "point.topleft.down.curvedto.point.bottomright.up",
                    title: "Watch packet routes",
                    detail: "Live routes animate hop by hop. Open a channel message to replay its path."
                )
                OnboardingFeatureRow(
                    icon: "line.3.horizontal.decrease.circle",
                    title: "Focus your view",
                    detail: "Choose a region on the map to narrow traffic and center the view."
                )
            }
            .padding(18)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(.horizontal, 24)

            Text("Location is optional and is only used when you choose to center the map on yourself.")
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

    private var selectedSourceName: String {
        sourceRegistry.sources.first {
            $0.host.caseInsensitiveCompare(selectedHost) == .orderedSame
        }?.name ?? "Custom Analyzer"
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
