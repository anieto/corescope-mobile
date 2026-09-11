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
            // The region code text itself (e.g. "AUS") is enough to show a
            // filter is active, so no separate badge dot is needed on top
            // of it.
            HStack(spacing: 4) {
                Image(systemName: "line.3.horizontal.decrease.circle")
                if let selectedRegion = regionFilter.selectedRegion {
                    Text(selectedRegion)
                        .font(.caption.weight(.semibold))
                }
            }
        }
    }
}
