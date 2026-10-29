import SwiftUI

/// Reports whether the current view hierarchy belongs to a device with an active hinge.
/// Width alone cannot distinguish iPhone Duo from iPad or a resizable window.
private struct DuoHingeObserver: ViewModifier {
    @Binding var isHingedDevice: Bool?
    @Environment(\.accessibilityReduceMotion) private var accessibilityReduceMotion

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 27.1, *) {
            content
                .onHingeChange { _, newContext in
                    let newValue = newContext.hinge != nil
                    // Hinge updates also arrive when nothing this app uses has changed. Only a
                    // real change may touch the state: at the root, an animated write would run
                    // through every screen and stutter continuous animations such as live packets.
                    guard newValue != isHingedDevice else { return }
                    if isHingedDevice == nil {
                        // Resolve the initial posture without animating an incorrect layout.
                        isHingedDevice = newValue
                    } else if accessibilityReduceMotion {
                        isHingedDevice = newValue
                    } else {
                        withAnimation(.easeInOut(duration: 0.32)) {
                            isHingedDevice = newValue
                        }
                    }
                }
                .task {
                    // Devices without a hinge do not necessarily receive an initial
                    // hinge-change callback. Give Duo a moment to report its posture,
                    // then resolve iPad and conventional iPhone layouts normally.
                    try? await Task.sleep(for: .milliseconds(120))
                    guard !Task.isCancelled, isHingedDevice == nil else { return }
                    isHingedDevice = false
                }
        } else {
            content
                .task {
                    isHingedDevice = false
                }
        }
    }
}

extension EnvironmentValues {
    /// Whether this window is on a device with an active hinge. Resolved once at the app's root
    /// (see `resolvesDuoHinge()`) and kept current as the device folds, so every screen knows its
    /// layout on its first frame instead of resolving it, and flashing the wrong one, itself.
    @Entry var isHingedDevice = false
}

/// Holds the window until the hinge is known, then publishes it to every screen.
private struct DuoHingeRoot: ViewModifier {
    @State private var isHingedDevice: Bool?

    func body(content: Content) -> some View {
        // Nothing is drawn for the observer's short launch wait (the window background shows);
        // not an opacity, which would composite the whole app into one layer from then on.
        ZStack {
            if let isHingedDevice {
                content
                    .environment(\.isHingedDevice, isHingedDevice)
            } else {
                Color.clear
            }
        }
        // The listener sits on an empty view behind the app, not around it, so each hinge
        // update re-evaluates that one view rather than the whole app; only a real change
        // reaches the screens, through the environment.
        .background {
            Color.clear
                .modifier(DuoHingeObserver(isHingedDevice: $isHingedDevice))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

extension View {
    /// Apply once, at the window's root.
    func resolvesDuoHinge() -> some View {
        modifier(DuoHingeRoot())
    }
}

/// Uses the system arrangement engine so the split follows an active hinge
/// division even when a vertical control bar makes the safe areas asymmetric.
struct DuoTwoPaneLayout<Primary: View, Secondary: View>: View {
    @ViewBuilder let primary: Primary
    @ViewBuilder let secondary: Secondary

    init(
        @ViewBuilder primary: () -> Primary,
        @ViewBuilder secondary: () -> Secondary
    ) {
        self.primary = primary()
        self.secondary = secondary()
    }

    @ViewBuilder
    var body: some View {
        if #available(iOS 27.1, *) {
            GeometryReader { geometry in
                ArrangementView {
                    primary
                        .padding(.trailing, -geometry.safeAreaInsets.trailing)
                } secondary: {
                    secondary
                }
                .arrangementViewStyle(.split.axes(.horizontal))
                .background {
                    NodeScopeBackground()
                }
                .ignoresSafeArea(.container, edges: .trailing)
            }
        } else {
            HStack(spacing: 0) {
                primary
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                Divider()

                secondary
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}
