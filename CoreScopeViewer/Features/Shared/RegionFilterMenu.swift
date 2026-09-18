import SwiftUI

/// Toolbar control shared by every list/live screen so region filtering
/// looks and behaves identically everywhere, backed by the single
/// `RegionFilterStore` selection.
struct RegionFilterMenu: View {
    var showsSelection = true

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
                if showsSelection, let selectedRegion = regionFilter.selectedRegion {
                    Text(selectedRegion)
                        .font(.caption.weight(.semibold))
                }
            }
        }
    }
}

struct RegionFilterSelectionScreen: View {
    @Environment(RegionFilterStore.self) private var regionFilter

    var body: some View {
        List {
            Button {
                regionFilter.selectedRegion = nil
            } label: {
                selectionLabel(title: "Entire Network", isSelected: regionFilter.selectedRegion == nil)
            }

            ForEach(regionFilter.options, id: \.self) { code in
                Button {
                    regionFilter.selectedRegion = code
                } label: {
                    selectionLabel(
                        title: regionFilter.label(for: code),
                        isSelected: regionFilter.selectedRegion == code
                    )
                }
            }
        }
        .navigationTitle("Region")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func selectionLabel(title: String, isSelected: Bool) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(.primary)
            Spacer()
            if isSelected {
                Image(systemName: "checkmark")
                    .fontWeight(.semibold)
                    .foregroundStyle(Color.accentColor)
            }
        }
        .contentShape(Rectangle())
    }
}
