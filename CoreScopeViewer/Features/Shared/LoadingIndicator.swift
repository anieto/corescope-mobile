import SwiftUI

enum NodeScopeStyle {
    static let signal = Color(red: 0.16, green: 0.62, blue: 1.0)
    static let activity = Color(red: 1.0, green: 0.66, blue: 0.20)
    static let healthy = Color(red: 0.24, green: 0.78, blue: 0.48)
    static let deepNavy = Color(red: 0.035, green: 0.075, blue: 0.13)
    static let slate = Color(red: 0.08, green: 0.13, blue: 0.20)
    /// Card surface in dark mode: a step lighter than `slate` so cards read against the page.
    static let cardSlate = Color(red: 0.115, green: 0.175, blue: 0.255)
    // A darker, far less saturated pair used only for the page background
    // (not the instrument-card fill above), so cards keep contrasting
    // against it instead of nearly matching it near the bottom of the
    // gradient. Sits between the channel screen's plain black background
    // and this gradient's original, more strongly blue-tinted navy.
    static let backgroundTop = Color(red: 0.02, green: 0.025, blue: 0.035)
    static let backgroundBottom = Color(red: 0.045, green: 0.05, blue: 0.065)
    static let cornerRadius: CGFloat = 20
}

struct NodeScopeBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        LinearGradient(
            colors: colorScheme == .dark
                ? [NodeScopeStyle.backgroundTop, NodeScopeStyle.backgroundBottom]
                : [Color(red: 0.94, green: 0.97, blue: 1.0), .white],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }
}

struct MeshNodeMotif: View {
    let color: Color

    var body: some View {
        HStack(spacing: 3) {
            Circle().frame(width: 5, height: 5)
            Capsule().frame(width: 12, height: 1)
            Circle().frame(width: 8, height: 8)
            Capsule().frame(width: 12, height: 1)
            Circle().frame(width: 5, height: 5)
        }
        .foregroundStyle(color)
        .accessibilityHidden(true)
    }
}

/// A signal travels hop-by-hop across a short mesh route. Keeping this in one
/// shared view gives every network-backed loading state the same motion and
/// respects Reduce Motion by showing a stable highlighted route instead.
struct MeshRouteActivityIndicator: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if reduceMotion {
            route(activeNode: 2)
        } else {
            TimelineView(.periodic(from: .now, by: 0.22)) { context in
                route(activeNode: Int(context.date.timeIntervalSinceReferenceDate / 0.22) % 4)
            }
        }
    }

    private func route(activeNode: Int) -> some View {
        HStack(spacing: 0) {
            ForEach(0..<4, id: \.self) { index in
                Circle()
                    .fill(index == activeNode ? NodeScopeStyle.activity : NodeScopeStyle.signal)
                    .frame(width: index == activeNode ? 9 : 6, height: index == activeNode ? 9 : 6)
                    .shadow(
                        color: index == activeNode ? NodeScopeStyle.activity.opacity(0.55) : .clear,
                        radius: 5
                    )

                if index < 3 {
                    Capsule()
                        .fill(NodeScopeStyle.signal.opacity(index < activeNode ? 0.8 : 0.25))
                        .frame(width: 18, height: 2)
                }
            }
        }
        .frame(height: 18)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: activeNode)
        .accessibilityHidden(true)
    }
}

struct InstrumentCardModifier: ViewModifier {
    let isSelected: Bool
    var fillOpacity: Double = 0.80
    var showsShadow = true
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            .background {
                RoundedRectangle(cornerRadius: NodeScopeStyle.cornerRadius, style: .continuous)
                    // Dark cards use a lighter slate than the page; contrast comes mostly from that,
                    // the outline and the shadow, so the background mesh can still show through a little.
                    .fill(colorScheme == .dark
                        ? NodeScopeStyle.cardSlate.opacity(min(1, fillOpacity + 0.02))
                        : Color.white.opacity(min(1, fillOpacity + 0.06)))
                    .overlay {
                        if isSelected {
                            RoundedRectangle(cornerRadius: NodeScopeStyle.cornerRadius, style: .continuous)
                                .fill(NodeScopeStyle.signal.opacity(colorScheme == .dark ? 0.14 : 0.08))
                        }
                    }
            }
            .overlay {
                RoundedRectangle(cornerRadius: NodeScopeStyle.cornerRadius, style: .continuous)
                    .stroke(
                        NodeScopeStyle.signal.opacity(
                            isSelected ? (colorScheme == .dark ? 0.9 : 0.75) : (colorScheme == .dark ? 0.28 : 0.18)
                        ),
                        lineWidth: isSelected ? 2 : 1
                    )
            }
            .shadow(color: .black.opacity(showsShadow ? (colorScheme == .dark ? 0.30 : 0.10) : 0), radius: 14, y: 6)
    }
}

/// Row surface for grouped Lists and Forms. It uses the same fill as `instrumentCard()`, so
/// settings-style lists match the app's cards instead of the opaque system grey.
struct InstrumentRowBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        colorScheme == .dark
            ? NodeScopeStyle.cardSlate.opacity(0.82)
            : Color.white.opacity(0.86)
    }
}

private struct AdaptiveScrollContentWidthModifier: ViewModifier {
    let maxWidth: CGFloat

    func body(content: Content) -> some View {
        GeometryReader { proxy in
            content.contentMargins(
                .horizontal,
                max(0, (proxy.size.width - maxWidth) / 2),
                for: .scrollContent
            )
        }
    }
}

private struct AdaptiveSidebarListModifier: ViewModifier {
    var meshBackground = false

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.isHingedDevice) private var isHingedDevice
    @Environment(\.isFloatingSidebar) private var isFloatingSidebar

    @ViewBuilder
    func body(content: Content) -> some View {
        // The floating sidebar reports a compact size class but takes the regular layout,
        // whose section headers scroll with the list rather than pinning under the panel's top.
        if horizontalSizeClass == .regular || isFloatingSidebar {
            content
                .scrollContentBackground(.hidden)
                .listStyle(.insetGrouped)
                .contentMargins(.top, 0, for: .scrollContent)
                // Rows set their own insets; keep the grouped style's side margins out of the panel.
                .contentMargins(.horizontal, isFloatingSidebar ? 0 : nil, for: .scrollContent)
                .background {
                    if meshBackground {
                        MeshBackground(subdued: true)
                            .ignoresSafeArea(.container, edges: isHingedDevice ? .all : .top)
                    } else {
                        Rectangle()
                            .fill(.thinMaterial)
                            .ignoresSafeArea(.container, edges: .top)
                    }
                }
        } else {
            content
                .scrollContentBackground(.hidden)
                .listStyle(.plain)
                .background {
                    if meshBackground {
                        MeshBackground(subdued: true).ignoresSafeArea()
                    } else {
                        NodeScopeBackground()
                    }
                }
        }
    }
}

extension View {
    func instrumentCard(isSelected: Bool = false, fillOpacity: Double = 0.80, showsShadow: Bool = true) -> some View {
        modifier(InstrumentCardModifier(isSelected: isSelected, fillOpacity: fillOpacity, showsShadow: showsShadow))
    }

    /// Gives grouped List or Form rows the card surface. Apply to each Section.
    func instrumentListRows() -> some View {
        listRowBackground(InstrumentRowBackground())
    }

    /// Keeps information-dense phone layouts readable on iPad while naturally
    /// filling compact windows used by Split View and Stage Manager.
    func adaptiveContentWidth(_ maxWidth: CGFloat = 840) -> some View {
        frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
    }

    /// Keeps a List or ScrollView full-width so its scroll indicator remains
    /// at the window edge while centering only the scrollable content.
    func adaptiveScrollContentWidth(_ maxWidth: CGFloat = 840) -> some View {
        modifier(AdaptiveScrollContentWidthModifier(maxWidth: maxWidth))
    }

    /// Uses native inset grouping and material when horizontal space is regular,
    /// while preserving the edge-to-edge presentation in compact layouts.
    func adaptiveSidebarListStyle(meshBackground: Bool = false) -> some View {
        modifier(AdaptiveSidebarListModifier(meshBackground: meshBackground))
    }


}

/// A small centered "loading" card, shown while a screen is fetching or
/// refetching (e.g. after a region filter change) so the change gets
/// visible feedback instead of the list quietly updating a moment later
/// with no indication anything was happening in between.
struct LoadingIndicator: View {
    let title: String?

    init(title: String? = nil) {
        self.title = title
    }

    var body: some View {
        HStack(spacing: 9) {
            MeshRouteActivityIndicator()
            if let title {
                Text(title)
                    .font(.caption.weight(.medium))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay {
            Capsule()
                .stroke(NodeScopeStyle.signal.opacity(0.16), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
    }
}

/// Floating iPad panels use scrolling labels instead of sticky section headers,
/// which otherwise reserve a translucent region above the first visible row.
struct AdaptiveSidebarSection<Content: View, Header: View>: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.isHingedDevice) private var isHingedDevice
    @ViewBuilder let content: Content
    @ViewBuilder let header: Header

    var body: some View {
        if horizontalSizeClass == .regular && !isHingedDevice {
            Section {
                header
                    .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 8, trailing: 16))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                content
            }
        } else {
            Section { content } header: { header }
        }
    }
}
