import SwiftUI
import UIKit

/// Native two-column navigation that collapses to a push-style flow in compact widths.
struct AdaptiveListDetailNavigation<Sidebar: View, Detail: View>: View {
    @Binding var preferredCompactColumn: NavigationSplitViewColumn
    let detailNavigationID: AnyHashable?
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.isHingedDevice) private var isHingedDevice
    @ViewBuilder let sidebar: Sidebar
    @ViewBuilder let detail: Detail

    init(
        preferredCompactColumn: Binding<NavigationSplitViewColumn>,
        detailNavigationID: AnyHashable? = nil,
        @ViewBuilder sidebar: () -> Sidebar,
        @ViewBuilder detail: () -> Detail
    ) {
        _preferredCompactColumn = preferredCompactColumn
        self.detailNavigationID = detailNavigationID
        self.sidebar = sidebar()
        self.detail = detail()
    }

    var body: some View {
        Group {
            if usesDuoPaneOrder {
                DuoTwoPaneLayout {
                    NavigationStack {
                        detail
                    }
                    .id(detailNavigationID)
                } secondary: {
                    NavigationStack {
                        sidebar
                    }
                }
                .transition(.opacity)
            } else {
                NavigationSplitView(preferredCompactColumn: $preferredCompactColumn) {
                    sidebar
                        .navigationSplitViewColumnWidth(min: 280, ideal: 340, max: 420)
                        .floatingSidebarColumn(horizontalSizeClass == .regular)
                } detail: {
                    NavigationStack {
                        detail
                    }
                    .id(detailNavigationID)
                }
                .navigationSplitViewStyle(.balanced)
                .safeAreaPadding(.bottom, horizontalSizeClass == .regular && !isHingedDevice ? 12 : 0)
                .transition(.opacity)
            }
        }
        .ignoresSafeArea(.container, edges: .top)
        .background {
            NodeScopeBackground()
                .ignoresSafeArea(.container, edges: .top)
        }
    }

    private var usesDuoPaneOrder: Bool {
        isHingedDevice && horizontalSizeClass == .regular
    }
}

extension EnvironmentValues {
    /// True inside an iPad split view's floating sidebar. That column reports a compact
    /// horizontal size class, so views that need to know they are in it read this instead.
    @Entry var isFloatingSidebar = false
}

extension View {
    /// Marks a split view's sidebar column as the floating iPad sidebar: sets
    /// `isFloatingSidebar` and keeps the column's bar and content below the status bar.
    @ViewBuilder
    func floatingSidebarColumn(_ isFloating: Bool) -> some View {
        if isFloating {
            environment(\.isFloatingSidebar, true)
                .background { FloatingSidebarInsetCorrection().allowsHitTesting(false) }
        } else {
            self
        }
    }
}

/// iPadOS 26 floats the sidebar panel below the top tab bar, yet still gives its column a
/// top inset as if the bar were above it, which left an empty band over the sidebar's title
/// and content. Remove the extra inset while keeping any part of the window's top safe
/// area that actually overlaps the sidebar column.
private struct FloatingSidebarInsetCorrection: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Probe { Probe() }
    func updateUIViewController(_ controller: Probe, context: Context) {}

    /// Fills the column behind its content, so it hears every change to the column's insets.
    final class Probe: UIViewController {
        override func loadView() {
            view = UIView()
            view.isUserInteractionEnabled = false
        }

        override func viewSafeAreaInsetsDidChange() {
            super.viewSafeAreaInsetsDidChange()
            correct()
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            correct()
        }

        private func correct() {
            guard let navigation = navigationController,
                  let window = navigation.view.window else { return }
            let columnTop = navigation.view.convert(navigation.view.bounds, to: window).minY
            let windowSafeTop = window.bounds.minY + window.safeAreaInsets.top
            let requiredInset = max(0, windowSafeTop - columnTop)
            let safeTop = navigation.view.safeAreaInsets.top
            let additional = navigation.additionalSafeAreaInsets.top
            // UIKit clamps the safe area at zero, so a zero here can hide how much the column
            // still inherits (the split view later drops the status bar from it, which left the
            // bar under the clock). Clear the correction and measure again on the next pass.
            if safeTop < 0.5, additional < -0.5, requiredInset > 0.5 {
                navigation.additionalSafeAreaInsets.top = 0
                return
            }
            let inherited = safeTop - additional
            let correction = requiredInset - max(0, inherited)
            if abs(additional - correction) > 0.5 {
                navigation.additionalSafeAreaInsets.top = correction
            }
        }
    }
}
