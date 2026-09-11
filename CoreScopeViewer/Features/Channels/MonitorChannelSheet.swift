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
            Form {
                Section {
                    Picker("Channel type", selection: $mode) {
                        ForEach(Mode.allCases) { mode in
                            Text(mode.title).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                switch mode {
                case .hashtag:
                    Section("Monitor Hashtag") {
                        TextField("Channel name", text: $hashtag)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Text("NodeScope derives the key for this public hashtag channel and decrypts matching traffic locally.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                case .privateKey:
                    Section("Add Private Channel") {
                        TextField("32-character hexadecimal key", text: $keyHex)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .fontDesign(.monospaced)
                        TextField("Display name (optional)", text: $displayName)
                    }
                case .generate:
                    Section("Generate Private Channel") {
                        TextField("Display name (optional)", text: $displayName)
                        Text("Share the generated key only with people who should be able to read this channel. NodeScope can monitor traffic but cannot transmit over RF.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                if let generatedChannel {
                    Section("Generated Key") {
                        Text(generatedChannel.keyHex)
                            .font(.system(.body, design: .monospaced))
                            .textSelection(.enabled)
                        ShareLink(
                            item: generatedChannel.keyHex,
                            subject: Text("NodeScope channel key"),
                            message: Text("Private MeshCore channel key")
                        ) {
                            Label("Share Key", systemImage: "square.and.arrow.up")
                        }
                    }
                }

                Section {
                    Text("Channel keys are stored only in this device's Keychain and are never sent to an analyzer.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Add Channel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(actionTitle) {
                        saveChannel()
                    }
                    .disabled(!canSave)
                }
            }
            .alert("Couldn't Add Channel", isPresented: .constant(errorMessage != nil)) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private var actionTitle: LocalizedStringKey {
        mode == .generate ? "Generate" : "Add"
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

    private enum Mode: String, CaseIterable, Identifiable {
        case hashtag
        case privateKey
        case generate

        var id: Self { self }

        var title: LocalizedStringKey {
            switch self {
            case .hashtag: "Hashtag"
            case .privateKey: "Private Key"
            case .generate: "Generate"
            }
        }
    }
}
