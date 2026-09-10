import SwiftUI

struct ObserverDetailScreen: View {
    let observer: MeshObserver
    @Environment(AnalyzerSettings.self) private var settings
    @State private var viewModel = ObserversViewModel()

    var body: some View {
        List {
            Section("Device") {
                LabeledContent("Model", value: observer.model ?? "Unknown")
                LabeledContent("Firmware", value: observer.firmware ?? "Unknown")
                if let batteryMv = observer.batteryMv {
                    LabeledContent("Battery", value: "\(batteryMv) mV")
                }
                LabeledContent("Packets (all time)", value: "\(observer.packetCount)")
                LabeledContent("Packets (last hour)", value: "\(observer.packetsLastHour)")
            }

            if let analytics = viewModel.analytics, !analytics.snrDistribution.isEmpty {
                Section("SNR Distribution") {
                    ForEach(analytics.snrDistribution) { bucket in
                        LabeledContent(bucket.range, value: "\(bucket.count)")
                    }
                }
            }
        }
        .navigationTitle(observer.name ?? observer.id)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            viewModel.configure(settings: settings)
            await viewModel.loadAnalytics(id: observer.id)
        }
    }
}
