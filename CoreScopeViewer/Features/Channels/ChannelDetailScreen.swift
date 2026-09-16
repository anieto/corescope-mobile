import SafariServices
import SwiftUI

struct ChannelDetailScreen: View {
    let channel: MeshChannel
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(RegionFilterStore.self) private var regionFilter
    @Environment(ObserverRegionLookup.self) private var observerRegionLookup
    @Environment(ChannelMonitorStore.self) private var monitorStore
    @Environment(LiveFeedService.self) private var liveFeed
    @State private var viewModel = ChannelsViewModel()
    @State private var lastProcessedLiveEventID: Int?
    @State private var liveRefreshTask: Task<Void, Never>?
    @State private var browserLink: BrowserLink?

    private let scrollBottomID = "channel-conversation-bottom"

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    ForEach(orderedMessages) { message in
                        ChatBubbleRow(
                            message: message,
                            isTrailing: messageSides[message.id] ?? false,
                            openURL: { browserLink = BrowserLink(url: $0) }
                        )
                            .id(message.id)
                    }

                    Color.clear
                        .frame(height: 92)
                        .id(scrollBottomID)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal)
                .padding(.vertical, 12)
                .adaptiveContentWidth(760)
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
        .safeAreaInset(edge: .top) {
            DataLoadStatusView(
                lastUpdatedAt: viewModel.lastUpdatedAt,
                errorMessage: viewModel.errorMessage,
                hasContent: !orderedMessages.isEmpty,
                retry: { Task { await loadMessages(forceRefresh: true) } }
            )
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .overlay {
            if orderedMessages.isEmpty && !viewModel.isLoading && viewModel.errorMessage == nil {
                ContentUnavailableView(
                    regionFilter.selectedRegion == nil ? "No messages yet" : "No messages from this region",
                    systemImage: "message"
                )
            }
        }
        .overlay(alignment: .top) {
            if viewModel.isLoading {
                LoadingIndicator(title: "Loading messages…")
                    .padding(.top, 8)
            }
        }
        .task(id: "\(channel.hash)|\(regionFilter.selectedRegion ?? "")") {
            viewModel.configure(settings: settings)
            await loadMessages()
        }
        .navigationDestination(for: ChannelMessage.self) { message in
            PacketDetailScreen(message: message)
        }
        .refreshable {
            await loadMessages(forceRefresh: true)
        }
        .sheet(item: $browserLink) { link in
            InAppBrowser(url: link.url)
                .ignoresSafeArea()
        }
        .onChange(of: liveFeed.recentEvents.first?.id) {
            scheduleLiveRefreshIfNeeded()
        }
        .onDisappear {
            liveRefreshTask?.cancel()
        }
    }

    /// New GRP_TXT packets arrive continuously over the shared WebSocket feed
    /// while this screen is open. A single new message often shows up as
    /// several back-to-back events (once per observer), so this waits for a
    /// short quiet period before re-fetching rather than refreshing on every
    /// individual event.
    ///
    /// The relevance check below is deliberately permissive rather than a
    /// strict `type == "packet" && payloadType == grpTxt` match: the feed can
    /// also emit `type == "message"` events (undocumented shape, never
    /// modeled here), and a "packet" event can legitimately arrive with
    /// `decoded` entirely absent (decode can happen asynchronously
    /// server-side). Treating both of those as "something may be relevant,
    /// go check" — rather than silently dropping them — trades a few extra
    /// refreshes for actually catching new messages.
    private func scheduleLiveRefreshIfNeeded() {
        guard let latestEvent = liveFeed.recentEvents.first,
              latestEvent.id != lastProcessedLiveEventID else { return }
        lastProcessedLiveEventID = latestEvent.id
        guard isPossiblyChannelRelevant(latestEvent) else { return }

        liveRefreshTask?.cancel()
        liveRefreshTask = Task {
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            await loadMessages(forceRefresh: true)
        }
    }

    private func isPossiblyChannelRelevant(_ event: LiveEnvelope) -> Bool {
        guard event.type == "packet" else { return true }
        let payloadType = event.data?.decoded?.header?.payloadType
        return payloadType == nil || payloadType == PayloadType.grpTxt.rawValue
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
        if monitorStore.channel(matching: channel) == nil,
           let selectedRegion = regionFilter.selectedRegion {
            messages = messages.filter { message in
                message.observers.contains { observerRegionLookup.iataByName[$0] == selectedRegion }
            }
        }
        return messages.sorted { $0.timestamp < $1.timestamp }
    }

    /// Alternates speakers across the conversation, while preserving a
    /// sender's side for an uninterrupted run of their own messages.
    private var messageSides: [Int: Bool] {
        var sides: [Int: Bool] = [:]
        var previousSender: String?
        var isTrailing = false

        for message in orderedMessages {
            if let previousSender, previousSender != message.sender {
                isTrailing.toggle()
            }
            sides[message.id] = isTrailing
            previousSender = message.sender
        }
        return sides
    }

    private func loadMessages(forceRefresh: Bool = false) async {
        if let monitoredChannel = monitorStore.channel(matching: channel) {
            await viewModel.loadMonitoredMessages(
                channel: monitoredChannel,
                region: regionFilter.selectedRegion,
                forceRefresh: forceRefresh
            )
            monitorStore.updateSummary(for: monitoredChannel.channelName, messages: viewModel.messages)
        } else {
            await viewModel.loadMessages(hash: channel.hash, forceRefresh: forceRefresh)
        }
    }

    private func scrollToBottom(proxy: ScrollViewProxy, animated: Bool = true) {
        guard !orderedMessages.isEmpty else { return }
        if animated {
            withAnimation {
                proxy.scrollTo(scrollBottomID, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(scrollBottomID, anchor: .bottom)
        }
    }
}

private struct ChatBubbleRow: View {
    let message: ChannelMessage
    let isTrailing: Bool
    let openURL: (URL) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 0) {
            if isTrailing {
                Spacer(minLength: 0)
            }

            VStack(alignment: isTrailing ? .trailing : .leading, spacing: 3) {
                Text(message.sender)
                    .font(.caption.bold())
                    .foregroundStyle(SenderColor.color(for: message.sender, colorScheme: colorScheme))
                    .padding(isTrailing ? .trailing : .leading, 4)

                VStack(alignment: .leading, spacing: 4) {
                    MessageLinkText(text: message.text, openURL: openURL)
                        .font(.body)
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)

                    Text(message.timestamp.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption2)
                        .foregroundStyle(.secondary)

                    HStack(spacing: 6) {
                        if message.repeats > 1 {
                            MessageMetricChip(
                                text: "Heard \(message.repeats)×",
                                symbol: "ear.fill",
                                color: NodeScopeStyle.signal
                            )
                        }
                        MessageMetricChip(
                            text: "\(message.hops) hop\(message.hops == 1 ? "" : "s")",
                            symbol: "point.3.connected.trianglepath.dotted",
                            color: NodeScopeStyle.activity
                        )
                        if let snr = message.snr {
                            MessageMetricChip(
                                text: String(format: "%.1f dB", snr),
                                symbol: "waveform",
                                color: .secondary
                            )
                        }
                    }

                    NavigationLink(value: message) {
                        Label("View Packet", systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                            .font(.caption.weight(.semibold))
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    SenderColor.bubbleFill(for: message.sender, colorScheme: colorScheme),
                    in: RoundedRectangle(cornerRadius: 16, style: .continuous)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(SenderColor.bubbleStroke(for: message.sender, colorScheme: colorScheme), lineWidth: 1)
                }
            }

            if !isTrailing {
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

private struct MessageLinkText: View {
    let text: String
    let openURL: (URL) -> Void

    var body: some View {
        Text(linkifiedText)
            .environment(\.openURL, OpenURLAction { url in
                guard Self.isWebURL(url) else { return .systemAction }
                openURL(url)
                return .handled
            })
    }

    private var linkifiedText: AttributedString {
        var attributed = AttributedString(text)
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
            return attributed
        }

        let fullRange = NSRange(text.startIndex..<text.endIndex, in: text)
        for match in detector.matches(in: text, range: fullRange) {
            guard let url = match.url,
                  Self.isWebURL(url),
                  let stringRange = Range(match.range, in: text),
                  let lower = AttributedString.Index(stringRange.lowerBound, within: attributed),
                  let upper = AttributedString.Index(stringRange.upperBound, within: attributed) else {
                continue
            }
            attributed[lower..<upper].link = url
            attributed[lower..<upper].foregroundColor = NodeScopeStyle.signal
            attributed[lower..<upper].underlineStyle = .single
        }
        return attributed
    }

    private static func isWebURL(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased() else { return false }
        return scheme == "http" || scheme == "https"
    }
}

private struct BrowserLink: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

private struct InAppBrowser: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}

private struct MessageMetricChip: View {
    let text: String
    let symbol: String
    let color: Color
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        // NodeScopeStyle.activity (and any similarly bright accent color) is
        // too light to read as text against this bubble's light-mode
        // background at full saturation — readableForeground keeps its
        // orange identity but pulls the brightness into a legible range.
        let readableColor = color.readableForeground(for: colorScheme)
        Label(text, systemImage: symbol)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(readableColor)
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .background(readableColor.opacity(0.11), in: Capsule())
    }
}
