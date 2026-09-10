import SwiftUI

struct NodeDetailScreen: View {
    let node: MeshNode
    @Environment(AnalyzerSettings.self) private var settings
    @State private var viewModel = NodeDetailViewModel()

    var body: some View {
        List {
            Section("Overview") {
                LabeledContent("Name", value: node.name ?? "Unnamed")
                LabeledContent("Role", value: node.role.capitalized)
                LabeledContent("Public Key", value: String(node.publicKey.prefix(16)) + "…")
                LabeledContent("First Seen", value: node.firstSeen.formatted())
                LabeledContent("Last Seen", value: node.lastSeen.formatted())
            }

            if let health = viewModel.health {
                Section("Health") {
                    LabeledContent("Total Transmissions", value: "\(health.stats.totalTransmissions)")
                    LabeledContent("Packets Today", value: "\(health.stats.packetsToday)")
                    if let avgSnr = health.stats.avgSnr {
                        LabeledContent("Avg SNR", value: String(format: "%.1f dB", avgSnr))
                    }
                }

                if !health.observers.isEmpty {
                    Section("Heard By") {
                        ForEach(health.observers) { observer in
                            LabeledContent(
                                observer.observerName ?? observer.observerId,
                                value: "\(observer.packetCount) pkts"
                            )
                        }
                    }
                }
            }

            if let reach = viewModel.reach, !reach.links.isEmpty {
                Section("Links") {
                    ForEach(reach.links) { link in
                        LabeledContent(link.name, value: link.bidir ? "bidirectional" : "one-way")
                    }
                }
            }

            if let paths = viewModel.paths, !paths.paths.isEmpty {
                Section("Known Paths (\(paths.totalPaths))") {
                    ForEach(paths.paths) { path in
                        LabeledContent(
                            path.hops.map(\.name).joined(separator: " → "),
                            value: "seen \(path.count)×"
                        )
                    }
                }
            }

            if viewModel.isLoading {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            }
        }
        .navigationTitle(node.name ?? "Node")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            viewModel.configure(settings: settings)
            await viewModel.load(pubkey: node.publicKey)
        }
    }
}
