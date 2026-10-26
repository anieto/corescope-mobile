import SwiftUI

struct NodePlannerScreen: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                NodePlannerWelcome()
                NodePlannerToolGrid()
                NodePlannerLimitations()
            }
            .frame(maxWidth: 900, alignment: .leading)
            .padding()
            .frame(maxWidth: .infinity)
        }
        .meshPageBackground()
        .navigationTitle("Node Planner")
        .navigationBarTitleDisplayMode(.inline)
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
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 16) {
                LinkProfileToolCard()
                CandidateSitesToolCard()
            }
            VStack(spacing: 16) {
                LinkProfileToolCard()
                CandidateSitesToolCard()
            }
        }
    }
}

private struct LinkProfileToolCard: View {
    var body: some View {
        NavigationLink {
            LinkProfileScreen()
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
        .instrumentCard()
        .contentShape(RoundedRectangle(cornerRadius: NodeScopeStyle.cornerRadius, style: .continuous))
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
