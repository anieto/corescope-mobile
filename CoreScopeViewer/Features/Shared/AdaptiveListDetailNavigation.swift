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
    /// `isFloatingSidebar` and starts the column's bar and content at the top of the panel.
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
/// and content. This removes that inset from the column's navigation controller, so its
/// bar, list and any screen pushed in the sidebar start at the top of the panel.
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
            guard let navigation = navigationController else { return }
            let inherited = navigation.view.safeAreaInsets.top - navigation.additionalSafeAreaInsets.top
            let correction = -max(0, inherited)
            if abs(navigation.additionalSafeAreaInsets.top - correction) > 0.5 {
                navigation.additionalSafeAreaInsets.top = correction
            }
        }
    }
}
