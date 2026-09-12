import SwiftUI

struct MonitorChannelSheet: View {
    @Environment(ChannelMonitorStore.self) private var monitorStore
    @Environment(\.dismiss) private var dismiss

    @State private var mode: Mode = .hashtag
    @State private var hashtag = ""
    @State private var keyHex = ""
    @State private var displayName = ""
    @State private var generatedChannel: MonitoredChannel?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    AddChannelHeader()
                    ChannelModeSelector(selection: $mode)
                    ChannelInputCard(
                        mode: mode,
                        hashtag: $hashtag,
                        keyHex: $keyHex,
                        displayName: $displayName
                    )

                    if let generatedChannel {
                        GeneratedKeyCard(channel: generatedChannel)
                    }

                    ChannelSecurityCard()

                    Button(actionTitle) {
                        saveChannel()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(NodeScopeStyle.signal)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(!canSave)
                }
                .padding(20)
            }
            .background(NodeScopeBackground())
            .navigationTitle("Add Channel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .alert(
                "Couldn't Add Channel",
                isPresented: Binding(
                    get: { errorMessage != nil },
                    set: { if !$0 { errorMessage = nil } }
                )
            ) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private var actionTitle: LocalizedStringKey {
        mode == .generate ? "Generate Secure Channel" : "Monitor Channel"
    }

    private var canSave: Bool {
        switch mode {
        case .hashtag:
            !hashtag.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .privateKey:
            !keyHex.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .generate:
            true
        }
    }

    private func saveChannel() {
        do {
            switch mode {
            case .hashtag:
                _ = try monitorStore.monitorHashtag(hashtag)
                dismiss()
            case .privateKey:
                _ = try monitorStore.monitorPSK(keyHex, displayName: displayName)
                dismiss()
            case .generate:
                generatedChannel = try monitorStore.generatePrivateChannel(displayName: displayName)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct AddChannelHeader: View {
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "lock.shield.fill")
                .font(.title2)
                .foregroundStyle(NodeScopeStyle.signal)
                .frame(width: 48, height: 48)
                .background(NodeScopeStyle.signal.opacity(0.13), in: RoundedRectangle(cornerRadius: 15))

            VStack(alignment: .leading, spacing: 3) {
                Text("Listen to a Channel")
                    .font(.title2.bold())
                Text("Keys are processed and stored on this device.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct ChannelModeSelector: View {
    @Binding var selection: MonitorChannelSheet.Mode

    var body: some View {
        HStack(spacing: 8) {
            ForEach(MonitorChannelSheet.Mode.allCases) { mode in
                Button {
                    selection = mode
                } label: {
                    VStack(spacing: 7) {
                        Image(systemName: mode.symbol)
                            .font(.title3.weight(.semibold))
                        Text(mode.shortTitle)
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                    }
                    .foregroundStyle(selection == mode ? Color.white : Color.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(selection == mode ? NodeScopeStyle.signal : Color.clear, in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selection == mode ? .isSelected : [])
            }
        }
        .padding(5)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct ChannelInputCard: View {
    let mode: MonitorChannelSheet.Mode
    @Binding var hashtag: String
    @Binding var keyHex: String
    @Binding var displayName: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(mode.title, systemImage: mode.symbol)
                .font(.headline)
                .foregroundStyle(NodeScopeStyle.signal)

            switch mode {
            case .hashtag:
                InstrumentTextField(title: "Channel name", text: $hashtag, isMonospaced: false)
                Text("NodeScope derives the public hashtag key and decrypts matching traffic locally.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            case .privateKey:
                InstrumentTextField(title: "32-character hexadecimal key", text: $keyHex, isMonospaced: true)
                InstrumentTextField(title: "Display name (optional)", text: $displayName, isMonospaced: false)
            case .generate:
                InstrumentTextField(title: "Display name (optional)", text: $displayName, isMonospaced: false)
                Text("A cryptographically secure key will be generated locally. Share it only with trusted participants.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .instrumentCard()
    }
}

private struct InstrumentTextField: View {
    let title: LocalizedStringKey
    @Binding var text: String
    let isMonospaced: Bool

    var body: some View {
        TextField(title, text: $text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .fontDesign(isMonospaced ? .monospaced : .default)
            .padding(.horizontal, 12)
            .frame(minHeight: 46)
            .background(Color.secondary.opacity(0.09), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct GeneratedKeyCard: View {
    let channel: MonitoredChannel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Generated Key", systemImage: "key.fill")
                .font(.headline)
                .foregroundStyle(NodeScopeStyle.activity)
            Text(channel.keyHex)
                .font(.system(.subheadline, design: .monospaced, weight: .semibold))
                .textSelection(.enabled)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.secondary.opacity(0.09), in: RoundedRectangle(cornerRadius: 12))
            ShareLink(
                item: channel.keyHex,
                subject: Text("NodeScope channel key"),
                message: Text("Private MeshCore channel key")
            ) {
                Label("Share Securely", systemImage: "square.and.arrow.up")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(NodeScopeStyle.activity)
        }
        .padding(16)
        .instrumentCard()
    }
}

private struct ChannelSecurityCard: View {
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "iphone.and.arrow.forward")
                .foregroundStyle(NodeScopeStyle.healthy)
            Text("Channel keys stay in this device's Keychain and are never sent to an analyzer. NodeScope monitors traffic but cannot transmit over RF.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(14)
        .background(NodeScopeStyle.healthy.opacity(0.09), in: RoundedRectangle(cornerRadius: 16))
    }
}

extension MonitorChannelSheet {
    fileprivate enum Mode: String, CaseIterable, Identifiable {
        case hashtag
        case privateKey
        case generate

        var id: Self { self }

        var title: LocalizedStringKey {
            switch self {
            case .hashtag: "Monitor Hashtag"
            case .privateKey: "Add Private Key"
            case .generate: "Generate Private Channel"
            }
        }

        var shortTitle: LocalizedStringKey {
            switch self {
            case .hashtag: "Hashtag"
            case .privateKey: "Private"
            case .generate: "Generate"
            }
        }

        var symbol: String {
            switch self {
            case .hashtag: "number"
            case .privateKey: "key.fill"
            case .generate: "sparkles"
            }
        }
    }
}
