import SwiftUI

struct StorageSettingsScreen: View {
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup

    @State private var usage: CacheStorageUsage?
    @State private var isLoading = false
    @State private var isClearing = false
    @State private var showsClearConfirmation = false
    @State private var clearedAt: Date?

    var body: some View {
        List {
            StorageUsageSection(
                usage: usage,
                isLoading: isLoading || isClearing,
                clearedAt: clearedAt
            )

            StorageContentsSection()

            Section {
                Button("Clear Cached Data", role: .destructive) {
                    showsClearConfirmation = true
                }
                .disabled(isClearing || (usage?.bytes == 0 && usage?.fileCount == 0))
            } footer: {
                Text("Current content can remain visible while NodeScope fetches fresh data. New requests will no longer use cached responses.")
            }
        }
        .floatingDockScrollClearance()
        .navigationTitle("Storage")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadUsage()
        }
        .refreshable {
            await loadUsage()
        }
        .confirmationDialog(
            "Clear Cached Data?",
            isPresented: $showsClearConfirmation,
            titleVisibility: .visible
        ) {
            Button("Clear Cached Data", role: .destructive) {
                Task { await clearCache() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Downloaded analyzer responses will be removed. Favorites, monitored channels and keys, recent items, appearance, and analyzer settings will be kept.")
        }
    }

    private func loadUsage() async {
        guard !isClearing else { return }
        isLoading = true
        usage = await CacheStorageService.shared.usage()
        isLoading = false
    }

    private func clearCache() async {
        guard !isClearing else { return }
        isClearing = true
        await CacheStorageService.shared.clear()

        async let regions: Void = regionFilter.loadRegions()
        async let coordinates: Void = regionFilter.loadIataCoords()
        async let observerRegions: Void = observerRegionLookup.load()
        _ = await (regions, coordinates, observerRegions)

        usage = await CacheStorageService.shared.usage()
        clearedAt = .now
        isClearing = false
    }
}

private struct StorageUsageSection: View {
    let usage: CacheStorageUsage?
    let isLoading: Bool
    let clearedAt: Date?

    var body: some View {
        Section("Cache Usage") {
            LabeledContent("Downloaded data") {
                if isLoading {
                    ProgressView()
                } else {
                    Text(formattedSize)
                }
            }

            LabeledContent("Cached files") {
                Text("\(usage?.fileCount ?? 0)")
            }

            if let clearedAt {
                Label {
                    Text("Cleared \(clearedAt, format: .relative(presentation: .named))")
                } icon: {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(NodeScopeStyle.healthy)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }

    private var formattedSize: String {
        ByteCountFormatter.string(
            fromByteCount: usage?.bytes ?? 0,
            countStyle: .file
        )
    }
}

private struct StorageContentsSection: View {
    var body: some View {
        Section {
            StorageContentRow(
                title: "Analyzer responses",
                detail: "Nodes, routes, packets, channels, observers, regions, and map configuration",
                systemImage: "externaldrive.fill"
            )
            StorageContentRow(
                title: "Temporary network data",
                detail: "Short-lived responses used to make revisiting screens faster",
                systemImage: "arrow.triangle.2.circlepath"
            )
        } header: {
            Text("What Is Cached")
        } footer: {
            Text("Favorites, monitored-channel keys, recent items, app preferences, and your selected analyzer are stored separately and are never removed here.")
        }
    }
}

private struct StorageContentRow: View {
    let title: LocalizedStringKey
    let detail: LocalizedStringKey
    let systemImage: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage)
                .foregroundStyle(NodeScopeStyle.signal)
                .frame(width: 24)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
