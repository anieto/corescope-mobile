import SwiftUI

struct SavedPlansScreen: View {
    var kind: SavedStudy.Kind? = nil
    @State private var model = CandidatePlansModel.shared
    @State private var deleting: SavedStudy?

    private var visibleStudies: [SavedStudy] {
        model.studies.filter { kind == nil || $0.kind == kind }
    }

    var body: some View {
        List {
            Section {
                Text("Saved plans stay on this device. Coverage saves include available calculation results. Terrain files remain in the separate cache.")
                    .foregroundStyle(.secondary)
            }.instrumentListRows()
            Section("Studies") {
                ForEach(visibleStudies) { study in
                    SavedStudyRow(study: study)
                        .swipeActions {
                            Button("Delete", role: .destructive) { deleting = study }
                        }
                }
                if visibleStudies.isEmpty && model.isLoaded {
                    Text("Save a study from Link Profile or Estimated Coverage.")
                        .foregroundStyle(.secondary)
                }
            }.instrumentListRows()
            if kind == nil {
                Section {
                    NavigationLink("Candidate sites") { CandidateSitesScreen() }
                }.instrumentListRows()
            }
            if let error = model.error {
                Section {
                    Text(error).foregroundStyle(.red)
                    Button("Reload library") { Task { await model.reload() } }
                }.instrumentListRows()
            }
            if model.isBusy {
                ProgressView().instrumentListRows()
            }
        }
        .meshPageBackground()
        .navigationTitle("Saved Plans")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
        .confirmationDialog("Delete this saved study?", isPresented: Binding(
            get: { deleting != nil }, set: { if !$0 { deleting = nil } }
        ), titleVisibility: .visible) {
            Button("Delete study", role: .destructive) {
                if let deleting { Task { await model.deleteStudy(deleting) } }
                deleting = nil
            }
        }
    }
}

private struct SavedStudyRow: View {
    let study: SavedStudy

    var body: some View {
        NavigationLink {
            switch study.kind {
            case .linkProfile: LinkProfileScreen(savedStudy: study)
            case .coverage: EstimatedCoverageScreen(savedStudy: study)
            }
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(study.name).font(.monaspace(.headline))
                Text(study.kind == .linkProfile ? "Link Profile" : "Estimated Coverage")
                    .font(.subheadline).foregroundStyle(.secondary)
                Text(study.coverage?.calculatedResult == nil ? "Inputs only · Calculate to see results" : "Includes saved coverage results")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

/// Opening another study pushes a new tool, preserving the current draft underneath it.
struct SavedStudyActions: View {
    let kind: SavedStudy.Kind
    let snapshot: () -> SavedStudy?
    let didSave: (SavedStudy) -> Void
    @State private var draft: SavedStudy?

    var body: some View {
        HStack {
            Button("Save", systemImage: "square.and.arrow.down") { draft = snapshot() }
                .disabled(snapshot() == nil)
            NavigationLink {
                SavedPlansScreen(kind: kind)
            } label: {
                Label("Open", systemImage: "folder")
            }
        }
        .buttonStyle(.bordered)
        .sheet(item: $draft) { study in
            SaveStudySheet(study: study, didSave: didSave)
        }
    }
}

private struct SaveStudySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model = CandidatePlansModel.shared
    @State private var study: SavedStudy
    @State private var hasEditedName = false
    @FocusState private var nameFocused: Bool
    let didSave: (SavedStudy) -> Void

    init(study: SavedStudy, didSave: @escaping (SavedStudy) -> Void) {
        _study = State(initialValue: study)
        self.didSave = didSave
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Study name") {
                    TextField("Name", text: $study.name)
                        .focused($nameFocused)
                        .onChange(of: nameFocused) { _, focused in
                            if focused && !hasEditedName {
                                study.name = ""
                                hasEditedName = true
                            }
                        }
                    Text("Saves the current inputs and captured node locations. Estimated Coverage also saves available results; Link Profile saves inputs only.")
                        .font(.caption).foregroundStyle(.secondary)
                }.instrumentListRows()
                if let error = model.error {
                    Section {
                        Text(error).foregroundStyle(.red)
                        Button("Reload library") { Task { await model.reload() } }
                    }.instrumentListRows()
                }
            }
            .meshPageBackground()
            .navigationTitle("Save study")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(model.isBusy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        study.updatedAtMilliseconds = max(study.createdAtMilliseconds, Int64(Date().timeIntervalSince1970 * 1000))
                        Task {
                            if await model.saveStudy(study) {
                                didSave(study)
                                dismiss()
                            }
                        }
                    }
                    .disabled(!model.isLoaded || model.isBusy || (try? study.validate()) == nil)
                }
            }
            .task { await model.load() }
            .interactiveDismissDisabled(model.isBusy)
        }
    }
}
