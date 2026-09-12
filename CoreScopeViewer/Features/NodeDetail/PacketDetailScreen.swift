import SwiftUI

struct PacketDetailScreen: View {
    let message: ChannelMessage
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(PacketReplayStore.self) private var replayStore
    @State private var viewModel = PacketDetailViewModel()
    @State private var selectedRouteIndex = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !message.text.isEmpty {
                    PacketMessageCard(sender: message.sender, text: message.text)
                }

                PacketIdentityCard(
                    hash: message.packetHash,
                    type: viewModel.detail?.packet.payloadTypeName,
                    observationCount: viewModel.detail?.observationCount ?? message.repeats,
                    hops: message.hops,
                    snr: message.snr
                )

                if let detail = viewModel.detail {
                    let routes = distinctRoutes(from: detail)
                    PacketRouteCard(
                        routes: routes,
                        selection: $selectedRouteIndex,
                        replay: {
                            replayStore.replay(
                                routes: routes,
                                selectedIndex: selectedRouteIndex,
                                packetHash: detail.packet.hash
                            )
                        }
                    )

                    PacketObserversCard(observations: detail.observations)
                    PacketTechnicalCard(packet: detail.packet)
                }
            }
            .padding(16)
            .padding(.bottom, 104)
        }
        .background(NodeScopeBackground())
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
            selectedRouteIndex = 0
        }
    }

    private func distinctRoutes(from detail: PacketDetailResponse) -> [[String]] {
        var seen = Set<[String]>()
        var routes: [[String]] = []
        for observation in detail.observations {
            let path = observation.resolvedPath?.compactMap { $0 } ?? []
            let normalizedPath = path.map { $0.lowercased() }
            guard path.count >= 2, seen.insert(normalizedPath).inserted else { continue }
            routes.append(path)
        }

        let longestFirst = routes.sorted { $0.count > $1.count }
        return longestFirst.filter { candidate in
            !longestFirst.contains { route in
                route.count > candidate.count && routeContains(route, candidate)
            }
        }
    }

    private func routeContains(_ route: [String], _ candidate: [String]) -> Bool {
        guard candidate.count <= route.count else { return false }
        let normalizedRoute = route.map { $0.lowercased() }
        let normalizedCandidate = candidate.map { $0.lowercased() }
        let lastStartIndex = normalizedRoute.count - normalizedCandidate.count

        return (0...lastStartIndex).contains { startIndex in
            normalizedRoute[startIndex..<(startIndex + normalizedCandidate.count)]
                .elementsEqual(normalizedCandidate)
        }
    }
}

private struct PacketMessageCard: View {
    let sender: String
    let text: String
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        PacketDetailCard(title: "Message", symbol: "message.fill") {
            VStack(alignment: .center, spacing: 6) {
                Text(sender)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(SenderColor.color(for: sender, colorScheme: colorScheme))
                Text(text)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity)
            .padding(14)
            .background(
                SenderColor.bubbleFill(for: sender, colorScheme: colorScheme),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(SenderColor.bubbleStroke(for: sender, colorScheme: colorScheme), lineWidth: 1)
            }
        }
    }
}

private struct PacketIdentityCard: View {
    let hash: String
    let type: String?
    let observationCount: Int
    let hops: Int
    let snr: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "waveform.path.ecg.rectangle.fill")
                    .font(.title2)
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
                    .background(NodeScopeStyle.signal, in: RoundedRectangle(cornerRadius: 14))
                VStack(alignment: .leading, spacing: 3) {
                    Text(type ?? "Loading packet")
                        .font(.title2.bold())
                    Text(hash.uppercased())
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .textSelection(.enabled)
                }
            }

            HStack(spacing: 8) {
                PacketMetricTile(value: observationCount.formatted(), label: "Observations", symbol: "ear.fill")
                PacketMetricTile(value: hops.formatted(), label: "Hops", symbol: "point.3.connected.trianglepath.dotted")
                PacketMetricTile(
                    value: snr.map { String(format: "%.1f", $0) } ?? "—",
                    label: "SNR dB",
                    symbol: "waveform"
                )
            }
        }
        .padding(16)
        .instrumentCard()
    }
}

private struct PacketMetricTile: View {
    let value: String
    let label: LocalizedStringKey
    let symbol: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Image(systemName: symbol)
                .font(.caption.weight(.bold))
                .foregroundStyle(NodeScopeStyle.signal)
            Text(value)
                .font(.headline.monospacedDigit())
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(NodeScopeStyle.signal.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct PacketRouteCard: View {
    let routes: [[String]]
    @Binding var selection: Int
    let replay: () -> Void

    var body: some View {
        PacketDetailCard(title: "Route", symbol: "point.topleft.down.curvedto.point.bottomright.up") {
            if routes.isEmpty {
                Text("No mappable route was recorded for this packet.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                if routes.count > 1 {
                    Picker("Selected Route", selection: $selection) {
                        ForEach(routes.indices, id: \.self) { index in
                            Text("Route \(index + 1) · \(routes[index].count) hops").tag(index)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(NodeScopeStyle.signal)
                } else {
                    HStack {
                        Text("Resolved Path")
                        Spacer()
                        Text("\(routes[0].count) hops")
                            .foregroundStyle(.secondary)
                    }
                    .font(.subheadline.weight(.semibold))
                }

                if routes.indices.contains(selection) {
                    RouteHopPreview(hopCount: routes[selection].count)
                }

                Button(action: replay) {
                    Label("Replay on Map", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(NodeScopeStyle.activity)
            }
        }
    }
}

private struct RouteHopPreview: View {
    let hopCount: Int

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<min(hopCount, 7), id: \.self) { index in
                Circle()
                    .fill(index == 0 || index == min(hopCount, 7) - 1 ? NodeScopeStyle.activity : NodeScopeStyle.signal)
                    .frame(width: 8, height: 8)
                if index < min(hopCount, 7) - 1 {
                    Capsule()
                        .fill(NodeScopeStyle.signal.opacity(0.45))
                        .frame(maxWidth: .infinity, minHeight: 2, maxHeight: 2)
                }
            }
        }
        .frame(height: 20)
        .accessibilityLabel("Route with \(hopCount) hops")
    }
}

private struct PacketObserversCard: View {
    let observations: [PacketObservation]
    @State private var isExpanded = false

    private var visibleObservations: ArraySlice<PacketObservation> {
        observations.prefix(isExpanded ? observations.count : 3)
    }

    var body: some View {
        PacketDetailCard(title: "Observers", symbol: "antenna.radiowaves.left.and.right") {
            VStack(spacing: 0) {
                ForEach(visibleObservations) { observation in
                    HStack(spacing: 10) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.caption)
                            .foregroundStyle(NodeScopeStyle.healthy)
                            .frame(width: 32, height: 32)
                            .background(NodeScopeStyle.healthy.opacity(0.13), in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(observation.observerName ?? "Unknown observer")
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)
                            HStack(spacing: 8) {
                                Text(observation.observerIata ?? "Unknown region")
                                if let rssi = observation.rssi {
                                    Text("\(rssi, specifier: "%.0f") RSSI")
                                }
                            }
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if let snr = observation.snr {
                            Text("\(snr, specifier: "%.1f") dB")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(NodeScopeStyle.signal)
                        }
                    }
                    .padding(.vertical, 9)
                    if observation.id != visibleObservations.last?.id {
                        Divider().opacity(0.3)
                    }
                }
            }

            if observations.count > 3 {
                Button {
                    withAnimation(.easeInOut(duration: 0.22)) {
                        isExpanded.toggle()
                    }
                } label: {
                    HStack {
                        Text(isExpanded ? "Show less" : "Show all \(observations.count) observations")
                        Spacer()
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    }
                    .font(.caption.weight(.bold))
                    .foregroundStyle(NodeScopeStyle.signal)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(NodeScopeStyle.signal.opacity(0.09), in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct PacketTechnicalCard: View {
    let packet: Packet
    @State private var isExpanded = false

    var body: some View {
        PacketDetailCard(title: "Technical Data", symbol: "chevron.left.forwardslash.chevron.right") {
            Button {
                withAnimation(.easeInOut(duration: 0.22)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack {
                    Text(isExpanded ? "Hide raw packet" : "Show raw packet")
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(NodeScopeStyle.signal)
            }
            .buttonStyle(.plain)

            if isExpanded {
                if let decodedJson = packet.decodedJson {
                    Text(decodedJson)
                        .font(.caption2.monospaced())
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let rawHex = packet.rawHex {
                    Text(rawHex.uppercased())
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

private struct PacketDetailCard<Content: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    @ViewBuilder let content: Content

    init(title: LocalizedStringKey, symbol: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.symbol = symbol
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(title, systemImage: symbol)
                .font(.headline)
                .foregroundStyle(NodeScopeStyle.signal)
            content
        }
        .padding(16)
        .instrumentCard()
    }
}
