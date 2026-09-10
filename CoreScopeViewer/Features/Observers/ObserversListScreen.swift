import SwiftUI

struct ObserversListScreen: View {
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @State private var viewModel = ObserversViewModel()

    /// /api/observers has no server-side region filter, unlike nodes,
    /// channels, and packets — so this is filtered client-side by the
    /// `iata` field each observer already carries.
    private var filteredObservers: [MeshObserver] {
        guard let selectedRegion = regionFilter.selectedRegion else { return viewModel.observers }
        return viewModel.observers.filter { $0.iata == selectedRegion }
    }

    var body: some View {
        NavigationStack {
            List(filteredObservers) { observer in
                NavigationLink(value: observer) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(observer.name ?? observer.id).font(.headline)
                        HStack {
                            if let iata = observer.iata {
                                Text(iata)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("\(observer.packetCount) packets")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Observers")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    RegionFilterMenu()
                }
            }
            .navigationDestination(for: MeshObserver.self) { observer in
                ObserverDetailScreen(observer: observer)
            }
            .overlay {
                if filteredObservers.isEmpty && !viewModel.isLoading {
                    ContentUnavailableView("No observers yet", systemImage: "antenna.radiowaves.left.and.right")
                }
            }
            .overlay {
                if viewModel.isLoading {
                    LoadingIndicator()
                }
            }
        }
        .task {
            viewModel.configure(settings: settings)
            await viewModel.loadObservers()
        }
        .refreshable {
            await viewModel.loadObservers()
        }
    }
}
