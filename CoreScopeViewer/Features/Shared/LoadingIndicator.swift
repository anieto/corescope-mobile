import SwiftUI
import UIKit

enum NodeScopeStyle {
    static let signal = Color(red: 0.16, green: 0.62, blue: 1.0)
    static let activity = Color(red: 1.0, green: 0.66, blue: 0.20)
    static let healthy = Color(red: 0.24, green: 0.78, blue: 0.48)
    static let deepNavy = Color(red: 0.035, green: 0.075, blue: 0.13)
    static let slate = Color(red: 0.08, green: 0.13, blue: 0.20)
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
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            .background(
                colorScheme == .dark ? NodeScopeStyle.slate.opacity(0.94) : Color.white.opacity(0.9),
                in: RoundedRectangle(cornerRadius: NodeScopeStyle.cornerRadius, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: NodeScopeStyle.cornerRadius, style: .continuous)
                    .stroke(NodeScopeStyle.signal.opacity(colorScheme == .dark ? 0.18 : 0.1), lineWidth: 1)
            }
            .shadow(color: .black.opacity(colorScheme == .dark ? 0.22 : 0.07), radius: 14, y: 6)
    }
}

extension View {
    func instrumentCard() -> some View {
        modifier(InstrumentCardModifier())
    }

    /// Keeps information-dense phone layouts readable on iPad while naturally
    /// filling compact windows used by Split View and Stage Manager.
    func adaptiveContentWidth(_ maxWidth: CGFloat = 840) -> some View {
        frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
    }

    /// iPadOS window controls can occupy the upper-leading corner without
    /// contributing to SwiftUI's safe-area inset. Keep custom page headers
    /// below that chrome while leaving the compact iPhone layout unchanged.
    func iPadWindowControlsClearance() -> some View {
        padding(.top, UIDevice.current.userInterfaceIdiom == .pad ? 34 : 0)
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
