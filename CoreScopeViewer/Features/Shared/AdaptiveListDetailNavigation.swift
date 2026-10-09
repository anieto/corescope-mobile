import SwiftUI

/// Native two-column navigation that collapses to a push-style flow in compact widths.
struct AdaptiveListDetailNavigation<Sidebar: View, Detail: View>: View {
    @Binding var preferredCompactColumn: NavigationSplitViewColumn
    let detailNavigationID: AnyHashable?
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var isHingedDevice: Bool?
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
                } detail: {
                    NavigationStack {
                        detail
                    }
                    .id(detailNavigationID)
                }
                .navigationSplitViewStyle(.balanced)
                .opacity(isHingedDevice == nil ? 0 : 1)
                .accessibilityHidden(isHingedDevice == nil)
                .transition(.opacity)
            }
        }
        .ignoresSafeArea(.container, edges: .top)
        .background {
            NodeScopeBackground()
                .ignoresSafeArea(.container, edges: .top)
        }
        .observesDuoHinge($isHingedDevice)
    }

    private var usesDuoPaneOrder: Bool {
        isHingedDevice == true && horizontalSizeClass == .regular
    }
}
