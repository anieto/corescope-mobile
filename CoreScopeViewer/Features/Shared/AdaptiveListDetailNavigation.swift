import SwiftUI

/// Native two-column navigation that collapses to a push-style flow in compact widths.
struct AdaptiveListDetailNavigation<Sidebar: View, Detail: View>: View {
    @Binding var preferredCompactColumn: NavigationSplitViewColumn
    @ViewBuilder let sidebar: Sidebar
    @ViewBuilder let detail: Detail

    init(
        preferredCompactColumn: Binding<NavigationSplitViewColumn>,
        @ViewBuilder sidebar: () -> Sidebar,
        @ViewBuilder detail: () -> Detail
    ) {
        _preferredCompactColumn = preferredCompactColumn
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
        }
        .navigationSplitViewStyle(.balanced)
    }
}
