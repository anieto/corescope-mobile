import SwiftUI

/// Reports whether the current view hierarchy belongs to a device with an active hinge.
/// Width alone cannot distinguish iPhone Duo from iPad or a resizable window.
private struct DuoHingeObserver: ViewModifier {
    @Binding var isHingedDevice: Bool?

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 27.1, *) {
            content
                .onHingeChange { _, newContext in
                    let newValue = newContext.hinge != nil
                    if isHingedDevice == nil {
                        // Resolve the initial posture without animating an incorrect layout.
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

extension View {
    func observesDuoHinge(_ isHingedDevice: Binding<Bool?>) -> some View {
        modifier(DuoHingeObserver(isHingedDevice: isHingedDevice))
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
