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
            RegionQuickPickButtons()
            RegionCodeButtons()
        } label: {
            // The region code text itself (e.g. "AUS") is enough to show a
            // filter is active, so no separate badge dot is needed on top
            // of it.
            HStack(spacing: 4) {
                Image(systemName: "line.3.horizontal.decrease.circle")
                if showsSelection, let selection = regionFilter.selectionTitle {
                    Text(selection)
                        .font(.caption.weight(.semibold))
                }
            }
            .foregroundStyle(.primary)
        }
    }
}

/// The host's quick picks ("Central Texas", …) as a "Region groups" menu section, between All and
/// the single regions, ticked while that pick is the selection.
struct RegionQuickPickButtons: View {
    @Environment(RegionFilterStore.self) private var regionFilter

    var body: some View {
        let picks = regionFilter.availablePicks
        if !picks.isEmpty {
            Section("Region groups") {
                ForEach(picks, id: \.self) { pick in
                    Button {
                        regionFilter.select(pick)
                    } label: {
                        if regionFilter.isSelected(pick) {
                            Label(pick.name, systemImage: "checkmark")
                        } else {
                            Text(pick.name)
                        }
                    }
                }
            }
        }
    }
}

/// The single regions as menu buttons: a "Regions" section beside Region groups, otherwise after a divider.
struct RegionCodeButtons: View {
    @Environment(RegionFilterStore.self) private var regionFilter

    var body: some View {
        if !regionFilter.options.isEmpty {
            if regionFilter.availablePicks.isEmpty {
                Divider()
                buttons
            } else {
                Section("Regions") { buttons }
            }
        }
    }

    private var buttons: some View {
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

            if !regionFilter.availablePicks.isEmpty {
                Section("Region groups") {
                    ForEach(regionFilter.availablePicks, id: \.self) { pick in
                        Button {
                            regionFilter.select(pick)
                        } label: {
                            selectionLabel(title: pick.name, isSelected: regionFilter.isSelected(pick))
                        }
                    }
                }
            }

            if regionFilter.availablePicks.isEmpty {
                regionRows
            } else {
                Section("Regions") { regionRows }
            }
        }
        .meshPageBackground()
        .navigationTitle("Region")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var regionRows: some View {
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
