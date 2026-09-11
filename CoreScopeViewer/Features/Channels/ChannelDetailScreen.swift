import SwiftUI

struct ChannelDetailScreen: View {
    let channel: MeshChannel
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @State private var viewModel = ChannelsViewModel()

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    ForEach(orderedMessages) { message in
                        ChatBubbleRow(message: message)
                            .id(message.id)
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 12)
            }
            .onChange(of: viewModel.messages.count) {
                scrollToBottom(proxy: proxy)
            }
            .onAppear {
                scrollToBottom(proxy: proxy, animated: false)
            }
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(channel.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                RegionFilterMenu()
            }
        }
        .overlay {
            if orderedMessages.isEmpty && !viewModel.isLoading {
                ContentUnavailableView(
                    regionFilter.selectedRegion == nil ? "No messages yet" : "No messages from this region",
                    systemImage: "message"
                )
            }
        }
        .overlay {
            if viewModel.isLoading {
                LoadingIndicator()
            }
        }
        .task {
            viewModel.configure(settings: settings)
            await viewModel.loadMessages(hash: channel.hash)
        }
        .navigationDestination(for: ChannelMessage.self) { message in
            PacketDetailScreen(message: message)
        }
        .refreshable {
            await viewModel.loadMessages(hash: channel.hash)
        }
    }

    /// /api/channels/:hash/messages has no region query parameter, unlike
    /// nodes/channels/packets — so this filters client-side using each
    /// message's `observers` (names) cross-referenced against
    /// ObserverRegionLookup, built from /api/observers. The API's sort
    /// order for this endpoint isn't documented either, so timestamps are
    /// sorted defensively to guarantee the chat reads oldest-to-newest
    /// top-to-bottom regardless of what the server returns.
    private var orderedMessages: [ChannelMessage] {
        var messages = viewModel.messages
        if let selectedRegion = regionFilter.selectedRegion {
            messages = messages.filter { message in
                message.observers.contains { observerRegionLookup.iataByName[$0] == selectedRegion }
            }
        }
        return messages.sorted { $0.timestamp < $1.timestamp }
    }

    private func scrollToBottom(proxy: ScrollViewProxy, animated: Bool = true) {
        guard let last = orderedMessages.last else { return }
        if animated {
            withAnimation {
                proxy.scrollTo(last.id, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(last.id, anchor: .bottom)
        }
    }
}

private struct ChatBubbleRow: View {
    let message: ChannelMessage
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(message.sender)
                .font(.caption.bold())
                .foregroundStyle(senderColor)
                .padding(.leading, 4)

            VStack(alignment: .leading, spacing: 4) {
                Text(message.text)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 6) {
                    Text(message.timestamp.formatted(date: .omitted, time: .shortened))
                    if message.repeats > 1 {
                        Text("· heard \(message.repeats)×")
                    }
                    Text("· \(message.hops) hop\(message.hops == 1 ? "" : "s")")
                    if let snr = message.snr {
                        Text("· \(String(format: "%.1f dB", snr))")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)

                NavigationLink(value: message) {
                    Label("View Packet", systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                        .font(.caption.weight(.semibold))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(bubbleFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(senderColor.opacity(colorScheme == .dark ? 0.55 : 0.25), lineWidth: 1)
            }
        }
        .frame(maxWidth: 300, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// A consistent, deterministic color per sender (not per message) so a
    /// conversation with multiple senders reads visually like a group chat.
    private var senderColor: Color {
        let palette: [Color] = [.blue, .green, .orange, .purple, .pink, .teal, .indigo, .brown]
        let index = abs(message.sender.hashValue) % palette.count
        return palette[index]
    }

    private var bubbleFill: Color {
        senderColor.opacity(colorScheme == .dark ? 0.34 : 0.16)
    }
}
