import SwiftUI

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
        VStack(spacing: 8) {
            ProgressView()
            if let title {
                Text(title)
                    .font(.caption.weight(.medium))
            }
        }
        .padding(20)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .shadow(radius: 2)
    }
}
