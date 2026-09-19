import SwiftUI

extension View {
    func nodeScopeFloatingGlass(cornerRadius: CGFloat) -> some View {
        modifier(NodeScopeFloatingGlassModifier(cornerRadius: cornerRadius))
    }

    func nodeScopeSelectedGlass<ID: Hashable & Sendable>(
        isSelected: Bool,
        cornerRadius: CGFloat,
        usesMaterializedTransition: Bool,
        id: ID,
        in namespace: Namespace.ID
    ) -> some View {
        modifier(
            NodeScopeSelectedGlassModifier(
                isSelected: isSelected,
                cornerRadius: cornerRadius,
                usesMaterializedTransition: usesMaterializedTransition,
                id: id,
                namespace: namespace
            )
        )
    }
}

private struct NodeScopeFloatingGlassModifier: ViewModifier {
    let cornerRadius: CGFloat

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
        } else {
            content.background(
                .ultraThinMaterial,
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
        }
    }
}

private struct NodeScopeSelectedGlassModifier<ID: Hashable & Sendable>: ViewModifier {
    let isSelected: Bool
    let cornerRadius: CGFloat
    let usesMaterializedTransition: Bool
    let id: ID
    let namespace: Namespace.ID

    @ViewBuilder
    func body(content: Content) -> some View {
        if isSelected {
            if #available(iOS 26.0, *) {
                content.glassEffect(
                    .regular.tint(NodeScopeStyle.signal).interactive(),
                    in: .rect(cornerRadius: cornerRadius)
                )
                .glassEffectID(id, in: namespace)
                .glassEffectTransition(usesMaterializedTransition ? .materialize : .matchedGeometry)
            } else {
                content.background(
                    NodeScopeStyle.signal,
                    in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                )
            }
        } else {
            content
        }
    }
}
