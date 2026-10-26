import SwiftUI

struct NodePlannerScreen: View {
    let closeAction: (() -> Void)?

    @Environment(TabBarContextActionStore.self) private var contextAction
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var isHingedDevice: Bool?

    init(closeAction: (() -> Void)? = nil) {
        self.closeAction = closeAction
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    NodePlannerWelcome()
                    NodePlannerToolGrid(closeAction: closeAction)
                    NodePlannerLimitations()
                }
                .frame(maxWidth: 900, alignment: .leading)
                .padding()
                .frame(maxWidth: .infinity)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Node Planner")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !usesDuoLayout, let closeAction {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close", action: closeAction)
                        .accessibilityLabel("Close Node Planner")
                    }
                }
            }
        }
        .observesDuoHinge($isHingedDevice)
        .onAppear(perform: updateContextAction)
        .onChange(of: isHingedDevice) { updateContextAction() }
        .onDisappear {
            if usesDuoLayout {
                contextAction.clear()
            }
        }
    }

    private var usesDuoLayout: Bool {
        isHingedDevice == true && horizontalSizeClass == .regular
    }

    private func updateContextAction() {
        guard usesDuoLayout, let closeAction else {
            contextAction.clear()
            return
        }
        contextAction.set(.close) {
            contextAction.clear()
            closeAction()
        }
    }
}

private struct NodePlannerWelcome: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Plan before you deploy", systemImage: "antenna.radiowaves.left.and.right")
                .font(.title2.bold())
            Text("Evaluate proposed locations, terrain paths, and radio assumptions before installing a node.")
                .foregroundStyle(.secondary)
        }
    }
}

private struct NodePlannerToolGrid: View {
    let closeAction: (() -> Void)?

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 16) {
                LinkProfileToolCard(closeAction: closeAction)
                CandidateSitesToolCard()
            }
            VStack(spacing: 16) {
                LinkProfileToolCard(closeAction: closeAction)
                CandidateSitesToolCard()
            }
        }
    }
}

private struct LinkProfileToolCard: View {
    let closeAction: (() -> Void)?

    var body: some View {
        NavigationLink {
            LinkProfileScreen(closeAction: closeAction)
        } label: {
            NodePlannerToolCard(
                icon: "point.topleft.down.to.point.bottomright.curvepath",
                title: "Link Profile",
                detail: "Estimate terrain clearance and modeled path loss between two locations.",
                availability: "Available",
                isEnabled: true
            )
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens the point-to-point terrain analysis tool")
    }
}

private struct CandidateSitesToolCard: View {
    var body: some View {
        NodePlannerToolCard(
            icon: "mappin.and.ellipse",
            title: "Candidate Sites",
            detail: "Compare proposed node locations, potential links, and estimated coverage.",
            availability: "Coming next",
            isEnabled: false
        )
        .accessibilityElement(children: .combine)
    }
}

private struct NodePlannerToolCard: View {
    let icon: String
    let title: LocalizedStringResource
    let detail: LocalizedStringResource
    let availability: LocalizedStringResource
    let isEnabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                Image(systemName: icon)
                    .font(.title)
                    .foregroundStyle(isEnabled ? NodeScopeStyle.signal : .secondary)
                    .accessibilityHidden(true)
                Spacer()
                Text(availability)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(isEnabled ? NodeScopeStyle.signal : .secondary)
            }
            Text(title)
                .font(.headline)
                .foregroundStyle(.primary)
            Text(detail)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if isEnabled {
                Label("Open Tool", systemImage: "chevron.forward")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(NodeScopeStyle.signal)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, minHeight: 190, alignment: .topLeading)
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
        .contentShape(RoundedRectangle(cornerRadius: 16))
    }
}

private struct NodePlannerLimitations: View {
    var body: some View {
        Label {
            Text("Planner results are estimates based on terrain data and the assumptions you enter. Buildings, vegetation, interference, hardware variation, and weather can change real-world performance.")
        } icon: {
            Image(systemName: "info.circle")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
    }
}
