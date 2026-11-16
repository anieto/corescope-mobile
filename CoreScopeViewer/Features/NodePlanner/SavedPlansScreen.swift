import SwiftUI

/// Saved Plans home: plans, then standalone studies, then Candidate Sites. Opened from a tool, it
/// starts filtered to that tool's studies and hides Candidate Sites.
struct SavedPlansScreen: View {
    var kind: SavedStudy.Kind? = nil
    @State private var model = CandidatePlansModel.shared
    @State private var filter: StudyFilter
    @State private var query = ""
    @State private var action: StudyAction?
    @State private var creatingPlan = false

    init(kind: SavedStudy.Kind? = nil) {
        self.kind = kind
        _filter = State(initialValue: StudyFilter(kind))
    }

    private var searching: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty }

    private func matches(_ text: String?) -> Bool {
        guard let text else { return false }
        return text.localizedStandardContains(query.trimmingCharacters(in: .whitespaces))
    }

    private var plans: [StudyPlan] {
        CandidatePlansModel.sorted(model.studyPlans, updated: \.updatedAtMilliseconds).filter { plan in
            let contained = model.studies.filter { $0.planID == plan.id && filter.matches($0.kind) }
            if searching { return (filter == .all || !contained.isEmpty) && (matches(plan.name) || matches(plan.notes)) }
            return filter == .all || !contained.isEmpty
        }
    }

    /// Standalone studies; while searching, every matching study, with its plan named on the row.
    private var studies: [SavedStudy] {
        CandidatePlansModel.sorted(model.studies, updated: \.updatedAtMilliseconds).filter { study in
            filter.matches(study.kind) && (searching ? matches(study.name) : study.planID == nil)
        }
    }

    var body: some View {
        List {
            if kind != nil {
                Section {
                    StudyFilterPicker(filter: $filter)
                }.instrumentListRows()
            }
            if model.isLoaded && model.studyPlans.isEmpty && model.studies.isEmpty {
                Section {
                    Text("Group related studies into a plan, such as one for each site you're considering. Link Profile and Estimated Coverage can also save standalone studies.")
                        .foregroundStyle(.secondary)
                    Button("Create plan", systemImage: "folder.badge.plus") { creatingPlan = true }
                }.instrumentListRows()
            }
            if !plans.isEmpty {
                Section("Plans") {
                    ForEach(plans) { plan in
                        NavigationLink {
                            StudyPlanScreen(planID: plan.id, filter: filter)
                        } label: {
                            StudyPlanRow(plan: plan, studies: model.studies.filter { $0.planID == plan.id })
                        }
                    }
                }.instrumentListRows()
            }
            if !studies.isEmpty || (searching && plans.isEmpty) {
                Section(searching ? "Studies" : "Standalone studies") {
                    ForEach(studies) { study in
                        SavedStudyRow(study: study, planName: searching ? model.plan(study.planID)?.name : nil)
                            .studyActions(study, action: $action)
                    }
                    if studies.isEmpty {
                        Text("No plans or studies match “\(query)”.").foregroundStyle(.secondary)
                    }
                }.instrumentListRows()
            }
            if kind == nil && !searching {
                Section {
                    NavigationLink("Candidate sites") { CandidateSitesScreen() }
                } footer: {
                    Text("Stored on this device. Backup details are in Settings › Cache & Storage.")
                }.instrumentListRows()
            }
            LibraryStatus(model: model)
        }
        .meshPageBackground()
        .navigationTitle("Saved Plans")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "Plans, notes and studies")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("New plan", systemImage: "plus") { creatingPlan = true }
                    .disabled(!model.isLoaded)
            }
        }
        .sheet(isPresented: $creatingPlan) { PlanEditorSheet(plan: nil) }
        .studyActionPresenter($action)
        .task { await model.load() }
    }
}

/// One plan: notes, then its studies with a type filter. Add study and plan actions are in the toolbar.
struct StudyPlanScreen: View {
    let planID: String
    @State var filter: StudyFilter = .all
    @State private var model = CandidatePlansModel.shared
    @State private var action: StudyAction?
    @State private var addingExisting = false
    @State private var newTool: SavedStudy.Kind?

    private var plan: StudyPlan? { model.plan(planID) }
    private var members: [SavedStudy] {
        CandidatePlansModel.sorted(model.studies.filter { $0.planID == planID }, updated: \.updatedAtMilliseconds)
    }

    var body: some View {
        Group {
            if let plan {
                content(plan)
            } else if model.isLoaded {
                ContentUnavailableView("Plan not found", systemImage: "folder.badge.questionmark",
                    description: Text("It may have been deleted. Its studies, if any, are under Standalone studies."))
                    .meshPageBackground()
            } else {
                ProgressView().meshPageBackground()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }

    private func content(_ plan: StudyPlan) -> some View {
        let shown = members.filter { filter.matches($0.kind) }
        return List {
            if let notes = plan.notes {
                Section { Text(notes) }.instrumentListRows()
            }
            Section {
                StudyFilterPicker(filter: $filter)
            }.instrumentListRows()
            Section {
                ForEach(shown) { study in
                    SavedStudyRow(study: study, planName: nil).studyActions(study, action: $action)
                }
                if members.isEmpty {
                    Text("No studies yet. Use + to start a Link Profile or Estimated Coverage here, or add a study you've already saved.")
                        .foregroundStyle(.secondary)
                } else if shown.isEmpty {
                    Text("No \(filter.title.lowercased()) in this plan.").foregroundStyle(.secondary)
                }
            } header: {
                Text(filter == .all ? "Studies (\(members.count))" : "\(filter.title) (\(shown.count) of \(members.count))")
            }.instrumentListRows()
            LibraryStatus(model: model)
        }
        .meshPageBackground()
        .navigationTitle(plan.name)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Menu("Add study", systemImage: "plus") {
                    Button("New Link Profile", systemImage: "point.topleft.down.to.point.bottomright.curvepath") { newTool = .linkProfile }
                    Button("New Estimated Coverage", systemImage: "antenna.radiowaves.left.and.right") { newTool = .coverage }
                    Button("Add existing study", systemImage: "tray.and.arrow.down") { addingExisting = true }
                }
                PlanActionsMenu(plan: plan, count: members.count)
            }
        }
        .navigationDestination(item: $newTool) { kind in
            switch kind {
            case .linkProfile: LinkProfileScreen(planID: planID)
            case .coverage: EstimatedCoverageScreen(planID: planID)
            }
        }
        .sheet(isPresented: $addingExisting) { AddExistingStudySheet(planID: planID) }
        .studyActionPresenter($action)
    }
}

/// Edit and Delete for a plan. Holds the dismiss action itself, so the plan screen doesn't redraw
/// on every navigation change.
private struct PlanActionsMenu: View {
    let plan: StudyPlan
    let count: Int
    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var confirmingDelete = false

    var body: some View {
        Menu("Plan actions", systemImage: "ellipsis.circle") {
            Button("Edit name and notes", systemImage: "pencil") { editing = true }
            Button("Delete plan", systemImage: "trash", role: .destructive) { confirmingDelete = true }
        }
        .sheet(isPresented: $editing) { PlanEditorSheet(plan: plan) }
        .confirmationDialog("Delete “\(plan.name)”?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete plan", role: .destructive) {
                Task { if await CandidatePlansModel.shared.deletePlan(plan.id) { dismiss() } }
            }
        } message: {
            Text(count == 0 ? "This plan is empty."
                : "Its \(count) \(count == 1 ? "study stays" : "studies stay") in Saved Plans as standalone studies.")
        }
    }
}

enum StudyFilter: Hashable, CaseIterable {
    case all, linkProfile, coverage

    init(_ kind: SavedStudy.Kind?) {
        switch kind {
        case nil: self = .all
        case .linkProfile: self = .linkProfile
        case .coverage: self = .coverage
        }
    }

    func matches(_ kind: SavedStudy.Kind) -> Bool { self == .all || self == StudyFilter(kind) }

    var title: String {
        switch self {
        case .all: "All"
        case .linkProfile: "Link Profiles"
        case .coverage: "Coverage"
        }
    }
}

private struct StudyFilterPicker: View {
    @Binding var filter: StudyFilter

    var body: some View {
        Picker("Show", selection: $filter) {
            ForEach(StudyFilter.allCases, id: \.self) { Text($0.title).tag($0) }
        }
        .pickerStyle(.segmented)
    }
}

private struct LibraryStatus: View {
    let model: CandidatePlansModel

    var body: some View {
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
}

private struct StudyPlanRow: View {
    let plan: StudyPlan
    let studies: [SavedStudy]

    private var counts: String {
        let links = studies.filter { $0.kind == .linkProfile }.count
        let coverage = studies.count - links
        var parts: [String] = []
        if links > 0 { parts.append(links == 1 ? "1 Link Profile" : "\(links) Link Profiles") }
        if coverage > 0 { parts.append(coverage == 1 ? "1 Coverage study" : "\(coverage) Coverage studies") }
        return parts.isEmpty ? "No studies" : parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Image(systemName: "folder").foregroundStyle(.tint).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(plan.name).font(.monaspace(.headline))
                Text(counts).font(.subheadline).foregroundStyle(.secondary)
                Text("Updated \(savedDate(plan.updatedAtMilliseconds))").font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Plan, \(plan.name), \(counts)")
    }
}

struct SavedStudyRow: View {
    let study: SavedStudy
    let planName: String?

    static func resultText(_ status: SavedResultStatus) -> LocalizedStringKey {
        switch status {
        case .available: "Includes saved coverage results"
        case .missing: "Saved result not on this device"
        case .unverified: "Saved result could not be verified"
        case .unsupported: "Saved result needs another version of NodeScope"
        case .none: "Inputs only · Calculate to see results"
        }
    }

    var body: some View {
        NavigationLink {
            switch study.kind {
            case .linkProfile: LinkProfileScreen(savedStudy: study)
            case .coverage: EstimatedCoverageScreen(savedStudy: study)
            }
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Image(systemName: study.kind == .linkProfile ? "point.topleft.down.to.point.bottomright.curvepath"
                      : "antenna.radiowaves.left.and.right")
                    .foregroundStyle(.secondary).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(study.name).font(.monaspace(.headline))
                    Text(study.kind == .linkProfile ? "Link Profile" : "Estimated Coverage")
                        .font(.subheadline).foregroundStyle(.secondary)
                    if let planName {
                        Label(planName, systemImage: "folder").font(.subheadline).foregroundStyle(.secondary)
                    }
                    Text(Self.resultText(study.resultStatus)).font(.caption).foregroundStyle(.secondary)
                    Text("Updated \(savedDate(study.updatedAtMilliseconds))").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

private func savedDate(_ milliseconds: Int64) -> String {
    Date(timeIntervalSince1970: Double(milliseconds) / 1000).formatted(date: .abbreviated, time: .omitted)
}

// MARK: - Study actions

enum StudyAction: Identifiable {
    case rename(SavedStudy), move(SavedStudy), delete(SavedStudy)

    var study: SavedStudy {
        switch self { case .rename(let study), .move(let study), .delete(let study): study }
    }
    var id: String {
        switch self {
        case .rename(let study): "rename-\(study.id)"
        case .move(let study): "move-\(study.id)"
        case .delete(let study): "delete-\(study.id)"
        }
    }
}

private struct StudyActionsModifier: ViewModifier {
    let study: SavedStudy
    @Binding var action: StudyAction?

    @ViewBuilder private var buttons: some View {
        Button("Rename", systemImage: "pencil") { action = .rename(study) }
        Button("Move to plan", systemImage: "folder") { action = .move(study) }
        Button("Duplicate", systemImage: "plus.square.on.square") {
            Task { _ = await CandidatePlansModel.shared.duplicateStudy(study.id) }
        }
        Button("Delete study", systemImage: "trash", role: .destructive) { action = .delete(study) }
    }

    func body(content: Content) -> some View {
        content
            .contextMenu { buttons }
            .swipeActions {
                Button("Delete", systemImage: "trash", role: .destructive) { action = .delete(study) }
                Button("Move", systemImage: "folder") { action = .move(study) }.tint(.indigo)
            }
    }
}

private struct StudyActionPresenter: ViewModifier {
    @Binding var action: StudyAction?
    @State private var renaming: SavedStudy?
    @State private var newName = ""
    @State private var deleting: SavedStudy?

    func body(content: Content) -> some View {
        content
            .onChange(of: action?.id) { _, _ in
                guard let action else { return }
                switch action {
                case .rename(let study): newName = study.name; renaming = study; self.action = nil
                case .delete(let study): deleting = study; self.action = nil
                case .move: break
                }
            }
            .sheet(item: Binding(
                get: { if case .move(let study)? = action { study } else { nil } },
                set: { if $0 == nil { action = nil } }
            )) { study in
                MoveStudySheet(study: study)
            }
            .alert("Rename study", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Name", text: $newName)
                Button("Cancel", role: .cancel) { renaming = nil }
                Button("Rename") {
                    if let renaming, PlanValidation.name(newName) {
                        let name = newName
                        Task { _ = await CandidatePlansModel.shared.renameStudy(renaming.id, to: name) }
                    }
                    renaming = nil
                }
            } message: {
                Text("Renaming doesn't change the study's inputs or results.")
            }
            .confirmationDialog("Delete “\(deleting?.name ?? "")”?", isPresented: Binding(
                get: { deleting != nil }, set: { if !$0 { deleting = nil } }
            ), titleVisibility: .visible) {
                Button("Delete study", role: .destructive) {
                    if let deleting { Task { _ = await CandidatePlansModel.shared.deleteStudy(deleting.id) } }
                    deleting = nil
                }
            } message: {
                Text("This removes the study and its saved result from this device.")
            }
    }
}

extension View {
    fileprivate func studyActions(_ study: SavedStudy, action: Binding<StudyAction?>) -> some View {
        modifier(StudyActionsModifier(study: study, action: action))
    }

    fileprivate func studyActionPresenter(_ action: Binding<StudyAction?>) -> some View {
        modifier(StudyActionPresenter(action: action))
    }
}

/// Chooses Standalone or a plan; shows where the study is now. Moving never changes inputs or results.
private struct MoveStudySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model = CandidatePlansModel.shared
    let study: SavedStudy

    private var current: String? { model.studies.first { $0.id == study.id }?.planID }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    destination(nil, title: "Standalone", icon: "doc")
                    ForEach(CandidatePlansModel.sorted(model.studyPlans, updated: \.updatedAtMilliseconds)) { plan in
                        destination(plan.id, title: plan.name, icon: "folder")
                    }
                } footer: {
                    Text("Now in \(model.plan(current)?.name ?? "Standalone").")
                }.instrumentListRows()
                LibraryStatus(model: model)
            }
            .meshPageBackground()
            .navigationTitle("Move “\(study.name)”")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .interactiveDismissDisabled(model.isBusy)
        }
    }

    private func destination(_ id: String?, title: String, icon: String) -> some View {
        Button {
            guard id != current else { return dismiss() }
            Task { if await model.moveStudy(study.id, to: id) { dismiss() } }
        } label: {
            HStack {
                Label(title, systemImage: icon).font(.monaspace(.body))
                Spacer()
                if id == current { Image(systemName: "checkmark").foregroundStyle(.tint).accessibilityLabel("Current") }
            }
        }
        .disabled(model.isBusy)
    }
}

/// Moves a study from Standalone or another plan into this one; never copies it.
private struct AddExistingStudySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model = CandidatePlansModel.shared
    let planID: String

    var body: some View {
        NavigationStack {
            List {
                let candidates = CandidatePlansModel.sorted(model.studies.filter { $0.planID != planID },
                                                            updated: \.updatedAtMilliseconds)
                Section {
                    ForEach(candidates) { study in
                        Button {
                            Task { if await model.moveStudy(study.id, to: planID) { dismiss() } }
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(study.name).font(.monaspace(.headline))
                                Text(study.kind == .linkProfile ? "Link Profile" : "Estimated Coverage")
                                    .font(.subheadline).foregroundStyle(.secondary)
                                Label(model.plan(study.planID)?.name ?? "Standalone",
                                      systemImage: study.planID == nil ? "doc" : "folder")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .disabled(model.isBusy)
                    }
                    if candidates.isEmpty {
                        Text("Every saved study is already in this plan.").foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("Adding moves the study here from where it is now.")
                }.instrumentListRows()
                LibraryStatus(model: model)
            }
            .meshPageBackground()
            .navigationTitle("Add existing study")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .interactiveDismissDisabled(model.isBusy)
        }
    }
}

/// Creates a plan (`plan` nil) or edits one's name and notes.
private struct PlanEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model = CandidatePlansModel.shared
    let plan: StudyPlan?
    @State private var name: String
    @State private var notes: String

    init(plan: StudyPlan?) {
        self.plan = plan
        _name = State(initialValue: plan?.name ?? "")
        _notes = State(initialValue: plan?.notes ?? "")
    }

    private var valid: Bool { PlanValidation.name(name) && notes.utf8.count <= 4096 }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Plan name", text: $name).font(.monaspace(.body))
                }.instrumentListRows()
                Section {
                    TextField("Optional", text: $notes, axis: .vertical).lineLimit(3...8)
                } header: {
                    Text("Notes")
                } footer: {
                    if notes.utf8.count > 4096 { Text("Notes are too long.").foregroundStyle(.red) }
                }.instrumentListRows()
                LibraryStatus(model: model)
            }
            .meshPageBackground()
            .navigationTitle(plan == nil ? "New plan" : "Edit plan")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(model.isBusy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(plan == nil ? "Create" : "Save") {
                        Task {
                            var saved = false
                            if let plan { saved = await model.editPlan(plan.id, name: name, notes: notes) }
                            else { saved = await model.createPlan(name: name, notes: notes) != nil }
                            if saved { dismiss() }
                        }
                    }
                    .disabled(!model.isLoaded || model.isBusy || !valid)
                }
            }
            .task { await model.load() }
            .interactiveDismissDisabled(model.isBusy)
        }
    }
}

// MARK: - Saving from a tool

/// Save and Open for a tool. Opening another study pushes a new tool, preserving the current draft underneath it.
struct SavedStudyActions: View {
    let kind: SavedStudy.Kind
    var suggestedPlanID: String? = nil
    let snapshot: () -> SavedStudy?
    let didSave: (SavedStudy) -> Void
    @State private var draft: SavedStudy?

    var body: some View {
        HStack {
            Button { draft = snapshot() } label: {
                Label("Save", systemImage: "square.and.arrow.down")
                    .frame(maxWidth: .infinity)
            }
            .disabled(snapshot() == nil)
            NavigationLink {
                SavedPlansScreen(kind: kind)
            } label: {
                Label("Open", systemImage: "folder")
                    .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.borderedProminent)
        .tint(.blue)
        .sheet(item: $draft) { study in
            SaveStudySheet(study: study, suggestedPlanID: suggestedPlanID, didSave: didSave)
        }
    }
}

/// Name and destination for a save. A study already in the library offers Update existing (the
/// default) or Save as new; the sheet closes only once the save has succeeded.
private struct SaveStudySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model = CandidatePlansModel.shared
    @State private var study: SavedStudy
    @State private var mode = CandidatePlansModel.SaveMode.update
    @State private var destination = CandidatePlansModel.Destination.standalone
    @State private var newPlanName = ""
    @State private var prepared = false
    @State private var submitting = false
    /// The library's record of this study when the sheet opened; nil for a new or deleted study.
    @State private var existing: SavedStudy?
    let suggestedPlanID: String?
    let didSave: (SavedStudy) -> Void

    init(study: SavedStudy, suggestedPlanID: String?, didSave: @escaping (SavedStudy) -> Void) {
        _study = State(initialValue: study)
        self.suggestedPlanID = suggestedPlanID
        self.didSave = didSave
    }

    /// Previously saved, but no longer in the library.
    private var wasDeleted: Bool { prepared && study.revision != nil && existing == nil }

    private var valid: Bool {
        guard (try? study.validate()) != nil else { return false }
        if case .newPlan = destination { return PlanValidation.name(newPlanName) }
        return true
    }

    /// Where the study is now, and what choosing somewhere else does.
    private var saveInFooter: String {
        let current = existing.map { model.plan($0.planID)?.name ?? "Standalone" }
        if let current, mode == .update {
            return "Now in \(current). Choose another plan to move it there, or Standalone to keep it outside any plan."
        }
        return "A plan groups related studies, such as every study for one site. Standalone keeps this study outside any plan. You can move it later."
    }

    private var confirmTitle: String {
        if existing == nil { return "Save study" }
        return mode == .update ? "Update" : "Save as new"
    }

    var body: some View {
        NavigationStack {
            Form {
                if let existing {
                    Section {
                        Picker("Save", selection: $mode) {
                            Text("Update existing").tag(CandidatePlansModel.SaveMode.update)
                            Text("Save as new").tag(CandidatePlansModel.SaveMode.saveAsNew)
                        }
                        .pickerStyle(.segmented)
                    } footer: {
                        Text(mode == .update
                            ? "Replaces the saved “\(existing.name)” with what's open now."
                            : "Keeps “\(existing.name)” as it is and saves a separate study.")
                    }.instrumentListRows()
                } else if wasDeleted {
                    Section {
                        Text("The study you opened was deleted. This saves it as a new study.").foregroundStyle(.secondary)
                    }.instrumentListRows()
                }
                Section {
                    TextField("Name", text: $study.name).font(.monaspace(.body))
                } header: {
                    Text("Study name")
                } footer: {
                    Text("Saves the current inputs and captured node locations. Estimated Coverage also saves available results; Link Profile saves inputs only.")
                }.instrumentListRows()
                Section {
                    Picker("Save in", selection: $destination) {
                        Label("Standalone", systemImage: "doc").tag(CandidatePlansModel.Destination.standalone)
                        ForEach(CandidatePlansModel.sorted(model.studyPlans, updated: \.updatedAtMilliseconds)) { plan in
                            Label(plan.name, systemImage: "folder").tag(CandidatePlansModel.Destination.plan(plan.id))
                        }
                        Label("New plan…", systemImage: "folder.badge.plus").tag(CandidatePlansModel.Destination.newPlan)
                    }
                    .pickerStyle(.navigationLink)
                    if case .newPlan = destination {
                        TextField("New plan name", text: $newPlanName).font(.monaspace(.body))
                    }
                } header: {
                    Text("Save in")
                } footer: {
                    Text(saveInFooter)
                }.instrumentListRows()
                LibraryStatus(model: model)
            }
            .meshPageBackground()
            .navigationTitle(existing == nil ? "Save study" : "Save changes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(submitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirmTitle) { submit() }
                        .disabled(!prepared || !model.isLoaded || model.isBusy || submitting || !valid)
                }
            }
            .onChange(of: mode) { _, mode in
                guard let existing else { return }
                switch mode {
                case .saveAsNew where study.name == existing.name: study.name = model.uniqueName(CandidatePlansModel.copyName(existing.name))
                case .update where study.name == model.uniqueName(CandidatePlansModel.copyName(existing.name)): study.name = existing.name
                default: break
                }
            }
            .task {
                await model.load()
                guard !prepared else { return }
                model.error = nil
                existing = model.studies.first { $0.id == study.id }
                if existing == nil { mode = .saveAsNew }
                // Current membership wins; then the plan this tool came from; then Standalone.
                let planID = existing.map(\.planID) ?? suggestedPlanID
                destination = model.plan(planID).map { .plan($0.id) } ?? .standalone
                prepared = true
            }
            .interactiveDismissDisabled(submitting)
        }
    }

    private func submit() {
        guard !submitting else { return }
        submitting = true
        Task {
            defer { submitting = false }
            if let saved = await model.saveStudy(study, mode: existing == nil ? .saveAsNew : mode,
                                                 destination: destination, newPlanName: newPlanName) {
                didSave(saved)
                dismiss()
            }
        }
    }
}
