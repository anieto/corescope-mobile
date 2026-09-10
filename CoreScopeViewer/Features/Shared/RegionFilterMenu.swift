import SwiftUI

/// Toolbar control shared by every list/live screen so region filtering
/// looks and behaves identically everywhere, backed by the single
/// `RegionFilterStore` selection.
struct RegionFilterMenu: View {
    @Environment(RegionFilterStore.self) private var regionFilter

    var body: some View {
        Menu {
            Button("All Regions") {
                regionFilter.selectedRegion = nil
            }
            if !regionFilter.options.isEmpty {
                Divider()
                ForEach(regionFilter.options, id: \.self) { code in
                    Button {
                        regionFilter.selectedRegion = code
                    } label: {
                        if regionFilter.selectedRegion == code {
                            Label(regionFilter.label(for: code), systemImage: "checkmark")
                        } else {
                            Text(regionFilter.label(for: code))
                        }
                    }
                }
            }
        } label: {
            // A badge dot rather than relying on the outline/.fill SF Symbol
            // variants alone — those read as nearly identical at toolbar
            // size once the system applies its own tint, making it hard to
            // tell at a glance whether a filter is active.
            Image(systemName: "line.3.horizontal.decrease.circle")
                .overlay(alignment: .topTrailing) {
                    if regionFilter.selectedRegion != nil {
                        Circle()
                            .fill(Color.accentColor)
                            .frame(width: 9, height: 9)
                            .offset(x: 2, y: -2)
                    }
                }
        }
    }
}
