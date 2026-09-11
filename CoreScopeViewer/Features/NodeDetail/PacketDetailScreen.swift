import SwiftUI

struct PacketDetailScreen: View {
    let message: ChannelMessage
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(PacketReplayStore.self) private var replayStore
    @State private var viewModel = PacketDetailViewModel()

    var body: some View {
        List {
            Section("Packet") {
                LabeledContent("Hash", value: message.packetHash.uppercased())
                LabeledContent("Type", value: viewModel.detail?.packet.payloadTypeName ?? "Loading…")
                LabeledContent("Observed", value: "\(viewModel.detail?.observationCount ?? message.repeats) times")
                LabeledContent("Hops", value: "\(message.hops)")
                if let snr = message.snr {
                    LabeledContent("SNR", value: String(format: "%.1f dB", snr))
                }
            }

            if let detail = viewModel.detail {
                Section("Route") {
                    if let route = replayPath(from: detail) {
                        Text("\(route.count) nodes resolved")
                        Button {
                            replayStore.replay(path: route, packetHash: detail.packet.hash)
                        } label: {
                            Label("Replay on Map", systemImage: "play.fill")
                        }
                    } else {
                        Text("No mappable route was recorded for this packet.")
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Observers") {
                    ForEach(detail.observations) { observation in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(observation.observerName ?? "Unknown observer")
                            Text(observation.observerIata ?? "Unknown region")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("Packet")
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if viewModel.isLoading {
                LoadingIndicator(title: "Loading packet…")
            } else if let error = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load packet",
                    systemImage: "wifi.slash",
                    description: Text(error)
                )
            }
        }
        .task {
            viewModel.configure(settings: settings)
            await viewModel.loadPacket(hash: message.packetHash)
        }
    }

    private func replayPath(from detail: PacketDetailResponse) -> [String]? {
        detail.observations
            .compactMap { observation in
                let path = observation.resolvedPath?.compactMap { $0 } ?? []
                return path.count >= 2 ? path : nil
            }
            .max { $0.count < $1.count }
    }
}
