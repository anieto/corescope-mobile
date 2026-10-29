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
        VStack(spacing: 12) {
            LinkProfileToolCard()
            CandidateSitesToolCard()
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
            availability: "Coming soon",
            isEnabled: false
        )
        .accessibilityElement(children: .combine)
    }
}

private struct NodePlannerToolCard: View {
    let icon: String
    let title: LocalizedStringResource
    let detail: LocalizedStringResource
    /// Shown only for a tool that is not ready; an available tool's chevron says enough.
    var availability: LocalizedStringResource? = nil
    let isEnabled: Bool

    /// Explore's row: a filled-circle icon, title and description, and a chevron on a tool that
    /// opens. A tool that is not ready yet is greyed and says so instead of showing a chevron.
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.headline)
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(isEnabled ? NodeScopeStyle.signal : Color.secondary.opacity(0.5), in: Circle())
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(.primary)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if isEnabled {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            } else if let availability {
                Text(availability)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .instrumentCard()
        .contentShape(Rectangle())
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
