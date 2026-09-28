import SwiftUI

/// Native two-column navigation that collapses to a push-style flow in compact widths.
struct AdaptiveListDetailNavigation<Sidebar: View, Detail: View>: View {
    @Binding var preferredCompactColumn: NavigationSplitViewColumn
    let detailNavigationID: AnyHashable?
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
        NavigationSplitView(preferredCompactColumn: $preferredCompactColumn) {
            sidebar
                .navigationSplitViewColumnWidth(min: 320, ideal: 380, max: 460)
        } detail: {
            NavigationStack {
                detail
            }
            .id(detailNavigationID)
        }
        .navigationSplitViewStyle(.balanced)
        .ignoresSafeArea(.container, edges: .top)
        .background {
            NodeScopeBackground()
                .ignoresSafeArea(.container, edges: .top)
        }
    }
}
