import SwiftUI
import UIKit
import UniformTypeIdentifiers

extension UTType {
    /// A `.nodescope` export package, declared in Info.plist.
    static let nodescopePackage = UTType(exportedAs: "org.nodescope.saved-plans", conformingTo: .data)
}

/// Importing a `.nodescope` package into the saved library (docs/node-planner/saved-plans-v3-package.md, Importing).
/// Pure: plans the new records; the library commits them in one save. Never overwrites, calculates or fetches.
enum SavedPlansImport {
    struct Library {
        var plans: [DeploymentPlan]
        var studyPlans: [StudyPlan]
        var studies: [SavedStudy]
    }

    enum Kind: Hashable { case plan, study, candidateSites }
    enum Status: Equatable {
        case new
        /// Everything in it is already in the library.
        case alreadyHere
        /// The plan is here; this many of its studies aren't.
        case partlyHere(Int)
    }

    struct Item: Identifiable, Equatable {
        /// The package record's ID.
        var id: String
        var kind: Kind
        var name: String
        var studies: Int
        var status: Status
    }

    struct Preview {
        var package: SavedPlansPackage
        var items: [Item]
        /// Studies whose result can't be imported here: inputs only, if acknowledged.
        var unusable: [String]
        /// Results the exporting device left out, and why.
        var omitted: [(name: String, reason: SavedPlansPackage.OmissionReason)]
        var results: Int
        /// A study or plan import that is already here imports as a copy; a library import skips what's identical.
        var copiesMatches: Bool { package.scope != .library }
        var defaultSelection: Set<String> {
            Set(items.filter { copiesMatches || $0.status != .alreadyHere }.map(\.id))
        }
    }

    struct Records: Equatable {
        var plans: [DeploymentPlan] = []
        var studyPlans: [StudyPlan] = []
        var studies: [SavedStudy] = []
    }

    enum Refusal: Error, Equatable {
        case nothingSelected
        case tooMany(String)
        case tooLarge
    }

    static func preview(_ package: SavedPlansPackage, library: Library) -> Preview {
        let copies = package.scope != .library
        var items: [Item] = []
        for site in package.candidateSitePlans {
            items.append(Item(id: site.id, kind: .candidateSites, name: site.name, studies: 0,
                              status: !copies && library.plans.contains { sameSites($0, site) } ? .alreadyHere : .new))
        }
        for plan in package.studyPlans {
            let members = package.studies.filter { $0.planID == plan.id }
            let status: Status
            if let local = matchingPlan(plan, library) {
                let fresh = members.filter { study in !library.studies.contains { $0.planID == local.id && same(study, $0, package) } }.count
                status = fresh == 0 ? .alreadyHere : .partlyHere(fresh)
            } else {
                status = .new
            }
            items.append(Item(id: plan.id, kind: .plan, name: plan.name, studies: members.count, status: status))
        }
        for study in package.studies where study.planID == nil {
            // A study exported on its own left its plan behind, so it matches wherever it is here.
            let here = library.studies.contains { (package.scope == .study || $0.planID == nil) && same(study, $0, package) }
            items.append(Item(id: study.id, kind: .study, name: study.name, studies: 1, status: here ? .alreadyHere : .new))
        }
        let names = Dictionary(package.studies.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
        return Preview(package: package, items: items,
                       unusable: package.studies.filter { package.unusableResults.contains($0.id) }.map(\.name),
                       omitted: package.omittedResults.map { (names[$0.studyID] ?? "A study", $0.reason) },
                       results: package.studies.filter { $0.resultRef != nil }.count)
    }

    /// The new records for the selected items: copies with new IDs, memberships remapped, and each study's
    /// `importOrigin` naming the package record. Throws if nothing would be imported or the library can't hold it.
    static func records(_ preview: Preview, library: Library, selected: Set<String>, inputsOnly: Bool) throws -> Records {
        let package = preview.package
        let copies = preview.copiesMatches
        var records = Records()
        var studyNames = Set(library.studies.map(\.name)), planNames = Set(library.studyPlans.map(\.name))
        // Result files by content, with their sizes: the package's verified references, so nothing is hashed again.
        var held = Dictionary(library.studies.compactMap { $0.resultRef.map { ($0.sha256, $0.byteLength) } }, uniquingKeysWith: { first, _ in first })

        func copy(_ study: SavedStudy, into planID: String?) {
            let unusable = package.unusableResults.contains(study.id)
            guard !unusable || inputsOnly else { return }
            var next = study
            next.id = newID()
            next.planID = planID
            next.name = unique(study.name, &studyNames)
            next.revision = nil
            next.importOrigin = ImportOrigin(studyID: study.id, revision: study.revision ?? 1)
            next.resultRef = nil
            next.resultStatus = .none
            next.coverage?.calculatedResult = nil
            next.linkProfileResult = nil
            // The bytes travel in memory; the store installs them as result files before the manifest.
            if let ref = study.resultRef, let data = package.results[ref.sha256] {
                if next.kind == .coverage { next.coverage?.calculatedResult = data } else { next.linkProfileResult = data }
                held[ref.sha256] = ref.byteLength
            }
            records.studies.append(next)
        }

        for site in package.candidateSitePlans where selected.contains(site.id) {
            if !copies, library.plans.contains(where: { sameSites($0, site) }) { continue }
            var next = site
            next.id = newID()
            next.revision = nil
            records.plans.append(next)
        }
        for plan in package.studyPlans where selected.contains(plan.id) {
            let members = package.studies.filter { $0.planID == plan.id }
            if !copies, let local = matchingPlan(plan, library) {
                for study in members where !library.studies.contains(where: { $0.planID == local.id && same(study, $0, package) }) {
                    copy(study, into: local.id)
                }
                continue
            }
            var next = plan
            next.id = newID()
            next.name = unique(plan.name, &planNames)
            next.revision = nil
            records.studyPlans.append(next)
            for study in members { copy(study, into: next.id) }
        }
        for study in package.studies where study.planID == nil && selected.contains(study.id) {
            if !copies, library.studies.contains(where: { $0.planID == nil && same(study, $0, package) }) { continue }
            copy(study, into: nil)
        }

        guard !records.plans.isEmpty || !records.studyPlans.isEmpty || !records.studies.isEmpty else {
            throw Refusal.nothingSelected
        }
        if library.studies.count + records.studies.count > 100 { throw Refusal.tooMany("studies") }
        if library.studyPlans.count + records.studyPlans.count > 100 { throw Refusal.tooMany("plans") }
        if library.plans.count + records.plans.count > 100 { throw Refusal.tooMany("candidate site lists") }
        // Result files are shared by content, so only bytes the library doesn't hold yet count.
        if held.values.reduce(0, +) > DeploymentPlanStore.maximumResultStorage { throw Refusal.tooLarge }
        return records
    }

    // MARK: Identity

    /// A plan is identical when its name, notes and dates are.
    private static func matchingPlan(_ plan: StudyPlan, _ library: Library) -> StudyPlan? {
        library.studyPlans.first {
            $0.name == plan.name && $0.notes == plan.notes && $0.createdAtMilliseconds == plan.createdAtMilliseconds
                && $0.updatedAtMilliseconds == plan.updatedAtMilliseconds
        }
    }

    /// Kind, name, inputs, historical dates and source all equal, and the file brings no result this copy lacks:
    /// it has none, or the local study's result shows here. Result bytes aren't compared: export converts older
    /// results, so a backup of this very library carries different bytes for them. Membership is checked by the caller.
    private static func same(_ imported: SavedStudy, _ local: SavedStudy, _ package: SavedPlansPackage) -> Bool {
        var a = imported.coverage, b = local.coverage
        a?.calculatedResult = nil
        b?.calculatedResult = nil
        return imported.kind == local.kind && imported.name == local.name && imported.linkProfile == local.linkProfile && a == b
            && imported.createdAtMilliseconds == local.createdAtMilliseconds && imported.updatedAtMilliseconds == local.updatedAtMilliseconds
            && imported.origin == local.origin && (imported.resultRef == nil || local.resultStatus == .available)
    }

    private static func sameSites(_ a: DeploymentPlan, _ b: DeploymentPlan) -> Bool {
        a.name == b.name && a.sites == b.sites && a.selectedSiteID == b.selectedSiteID
            && a.createdAtMilliseconds == b.createdAtMilliseconds && a.updatedAtMilliseconds == b.updatedAtMilliseconds
    }

    /// The library's own naming: `name`, or `name 2`, `name 3`… within the 160-byte limit.
    private static func unique(_ name: String, _ taken: inout Set<String>) -> String {
        let candidate = CandidatePlansModel.uniqueName(name, taken: taken)
        taken.insert(candidate)
        return candidate
    }

    private static func newID() -> String { UUID().uuidString.lowercased() }

    static func describe(_ refusal: Refusal) -> String {
        switch refusal {
        case .nothingSelected: "Everything selected is already in your library."
        case .tooMany(let what): "Saved Plans can hold 100 \(what). Choose fewer items to import, or remove some first."
        case .tooLarge: "There isn't room for these results. Choose fewer items, or import inputs only."
        }
    }
}

/// A file to import, from Files, the share sheet, or Import in Saved Plans.
struct PackageImportRequest: Identifiable {
    let id = UUID()
    let url: URL
    /// A copy the system made for this app (an opened attachment): removed once read.
    var removesFile = false
}

extension CandidatePlansModel {
    /// Adds imported records in one save; nothing changes if it fails.
    func importRecords(_ records: SavedPlansImport.Records) async -> Bool {
        await addRecords(plans: records.plans, studyPlans: records.studyPlans, studies: records.studies)
    }
}

/// Shows the import screen for a file opened from another app, over whatever is on screen, including another
/// sheet (a SwiftUI sheet at the root can't appear while one is up, and the file would wait unseen).
@MainActor
enum PackageImportPresenter {
    static func present(_ request: PackageImportRequest, attempts: Int = 10) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        guard var top = scene?.keyWindow?.rootViewController ?? scene?.windows.first?.rootViewController, top.view.window != nil else {
            // Opened at launch: the window isn't up yet.
            if attempts > 0 { DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { present(request, attempts: attempts - 1) } }
            return
        }
        while let presented = top.presentedViewController, !presented.isBeingDismissed { top = presented }
        weak var shown: UIViewController?
        let controller = UIHostingController(rootView: ImportPackageSheet(request: request, onClose: { shown?.dismiss(animated: true) }))
        shown = controller
        top.present(controller, animated: true)
    }
}

/// Reads the file completely, shows what it holds, and imports the chosen items as copies.
struct ImportPackageSheet: View {
    let request: PackageImportRequest
    /// Set when presented by `PackageImportPresenter` rather than as a SwiftUI sheet.
    var onClose: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismissSheet
    private func dismiss() { if let onClose { onClose() } else { dismissSheet() } }
    @State private var model = CandidatePlansModel.shared
    @State private var state: LoadState = .loading
    @State private var selected: Set<String> = []
    @State private var inputsOnly = false
    @State private var problem: String?
    @State private var importing = false

    private enum LoadState {
        case loading
        case failed(String)
        case ready(SavedPlansImport.Preview)
    }

    var body: some View {
        NavigationStack {
            Group {
                switch state {
                case .loading:
                    ProgressView("Reading file…").frame(maxWidth: .infinity, maxHeight: .infinity)
                case .failed(let message):
                    ContentUnavailableView("Can't import this file", systemImage: "doc.questionmark", description: Text(message))
                case .ready(let preview):
                    form(preview)
                }
            }
            .navigationTitle("Import saved plans")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(importing) }
                if case .ready(let preview) = state {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(importLabel(preview)) { Task { await commit(preview) } }
                            .disabled(importing || selected.isEmpty || !model.isLoaded)
                    }
                }
            }
            .task { await load() }
            .interactiveDismissDisabled(importing)
        }
    }

    private func importLabel(_ preview: SavedPlansImport.Preview) -> String {
        preview.copiesMatches && preview.items.allSatisfy({ $0.status == .alreadyHere }) ? "Import copy" : "Import"
    }

    @ViewBuilder private func form(_ preview: SavedPlansImport.Preview) -> some View {
        let package = preview.package
        Form {
            Section {
                LabeledContent("From", value: "\(package.exportedBy.platform == .ios ? "iPhone or iPad" : "Android") · NodeScope \(package.exportedBy.appVersion)")
                LabeledContent("Exported", value: Date(timeIntervalSince1970: Double(package.exportedAtMilliseconds) / 1000)
                    .formatted(date: .abbreviated, time: .shortened))
                LabeledContent("Results", value: package.includesResults ? "\(preview.results) included" : "Inputs only")
            }
            Section {
                ForEach(preview.items) { item in
                    if preview.package.scope == .library {
                        Toggle(isOn: Binding(get: { selected.contains(item.id) },
                                             set: { if $0 { selected.insert(item.id) } else { selected.remove(item.id) } })) {
                            row(item)
                        }
                    } else {
                        row(item)
                    }
                }
            } header: {
                Text("Contents")
            } footer: {
                if preview.copiesMatches, preview.items.contains(where: { $0.status != .new }) {
                    Text("Already in your library. Importing adds a separate copy; nothing here is replaced.")
                } else {
                    Text("Imported items are added as copies. Nothing in your library is replaced, and nothing is calculated.")
                }
            }
            if !preview.unusable.isEmpty {
                Section {
                    ForEach(preview.unusable, id: \.self) { Text($0) }
                    Toggle("Import these as inputs only", isOn: $inputsOnly)
                } header: {
                    Text("Results that can't be imported")
                } footer: {
                    Text(inputsOnly ? "Their inputs are imported; calculate to see results."
                         : "These studies are left out unless you import their inputs only.")
                }
            }
            if !preview.omitted.isEmpty {
                Section("Left out when exported") {
                    ForEach(Array(preview.omitted.enumerated()), id: \.offset) { _, item in
                        LabeledContent(item.name) { Text(SavedPlansExport.describe(item.reason)).multilineTextAlignment(.trailing) }
                    }
                }
            }
            if let problem {
                Section { Text(problem).foregroundStyle(.red) }
            }
        }
    }

    private func row(_ item: SavedPlansImport.Item) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Label(item.name, systemImage: item.kind == .plan ? "folder" : item.kind == .candidateSites ? "mappin.and.ellipse" : "doc")
            Text(detail(item)).font(.footnote).foregroundStyle(.secondary)
        }
    }

    private func detail(_ item: SavedPlansImport.Item) -> String {
        let status = switch item.status {
        case .new: String(localized: "New")
        case .alreadyHere: String(localized: "Already in your library")
        case .partlyHere(let fresh): String(localized: "\(fresh) not in your library yet")
        }
        guard item.kind == .plan else { return status }
        return String(localized: "\(item.studies) studies") + " · " + status
    }

    private func load() async {
        await model.load()
        let request = self.request
        let outcome = await Task.detached(priority: .userInitiated) { () -> Result<SavedPlansPackage, SavedPlansPackage.Failure> in
            let scoped = request.url.startAccessingSecurityScopedResource()
            defer {
                if scoped { request.url.stopAccessingSecurityScopedResource() }
                if request.removesFile { try? FileManager.default.removeItem(at: request.url) }
            }
            guard let size = try? request.url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  size <= SavedPlansPackage.maximumPackageBytes,
                  let data = try? Data(contentsOf: request.url, options: .mappedIfSafe) else {
                return .failure(.invalid("unreadable"))
            }
            return Result { try SavedPlansPackage.read(data) }.mapError { $0 as? SavedPlansPackage.Failure ?? .invalid("unreadable") }
        }.value
        switch outcome {
        case .success(let package):
            let preview = SavedPlansImport.preview(package, library: library)
            selected = preview.defaultSelection
            state = .ready(preview)
        case .failure(.needsNewerVersion):
            state = .failed("This file needs a newer version of NodeScope. Nothing was imported.")
        case .failure:
            state = .failed("This isn't a NodeScope saved-plans file, or it's damaged or too large. Nothing was imported.")
        }
    }

    private var library: SavedPlansImport.Library {
        .init(plans: model.plans, studyPlans: model.studyPlans, studies: model.studies)
    }

    private func commit(_ preview: SavedPlansImport.Preview) async {
        problem = nil
        let records: SavedPlansImport.Records
        do {
            records = try SavedPlansImport.records(preview, library: library, selected: selected, inputsOnly: inputsOnly)
        } catch let refusal as SavedPlansImport.Refusal {
            problem = SavedPlansImport.describe(refusal)
            return
        } catch {
            return
        }
        importing = true
        defer { importing = false }
        if await model.importRecords(records) {
            dismiss()
        } else {
            problem = model.error.map { String(localized: $0) } ?? "The import couldn't be saved. Your library hasn't changed."
        }
    }
}
