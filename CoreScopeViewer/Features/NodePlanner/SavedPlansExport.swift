import SwiftUI

/// Builds `.nodescope` packages from the saved library (docs/node-planner/saved-plans-v3-package.md, Exporting).
/// Pure: works on the records as loaded, with their results attached, and never changes the library.
enum SavedPlansExport {
    enum Contents: Hashable, Identifiable {
        case study(String), plan(String), library
        var id: String {
            switch self {
            case .study(let id): "study-\(id)"
            case .plan(let id): "plan-\(id)"
            case .library: "library"
            }
        }
    }

    /// The package for `contents`, or nil if the study or plan no longer exists.
    static func package(_ contents: Contents, plans: [DeploymentPlan], studyPlans: [StudyPlan], studies: [SavedStudy],
                        includesResults: Bool, includesNotes: Bool, exportedAtMilliseconds: Int64,
                        appVersion: String) -> SavedPlansPackage? {
        let scope: SavedPlansPackage.Scope
        var chosenPlans: [StudyPlan] = [], chosenStudies: [SavedStudy], sites: [DeploymentPlan] = []
        switch contents {
        case .study(let id):
            guard var study = studies.first(where: { $0.id == id }) else { return nil }
            study.planID = nil
            scope = .study
            chosenStudies = [study]
        case .plan(let id):
            guard let plan = studyPlans.first(where: { $0.id == id }) else { return nil }
            scope = .plan
            chosenPlans = [plan]
            chosenStudies = studies.filter { $0.planID == id }
        case .library:
            scope = .library
            chosenPlans = studyPlans
            chosenStudies = studies
            sites = plans
        }
        if !includesNotes { chosenPlans = chosenPlans.map { var plan = $0; plan.notes = nil; return plan } }

        var results: [String: Data] = [:], omitted: [SavedPlansPackage.Omission] = []
        for index in chosenStudies.indices {
            let study = chosenStudies[index]
            chosenStudies[index].resultRef = nil
            guard includesResults, let ref = study.resultRef else { continue }
            switch portable(study, ref: ref) {
            case .success(let (ref, data)):
                chosenStudies[index].resultRef = ref
                results[ref.sha256] = data
            case .failure(let reason):
                omitted.append(.init(studyID: study.id, reason: reason))
            }
        }
        return SavedPlansPackage(scope: scope, exportedAtMilliseconds: exportedAtMilliseconds,
            exportedBy: .init(platform: .ios, appVersion: appVersion), includesResults: includesResults,
            includesNotes: includesNotes, candidateSitePlans: sites, studyPlans: chosenPlans, studies: chosenStudies,
            omittedResults: omitted, results: results)
    }

    /// The study's result in a portable format: as saved, or converted from this platform's legacy format.
    private static func portable(_ study: SavedStudy, ref: ResultRef) -> Result<(ResultRef, Data), SavedPlansPackage.OmissionReason> {
        switch study.resultStatus {
        case .missing: return .failure(.notOnDevice)
        case .unverified: return .failure(.couldNotBeVerified)
        case .unsupported: return .failure(ref.format.hasPrefix("legacy.") ? .notReadableHere : .needsNewerVersion)
        case .none, .available: break
        }
        if let data = study.linkProfileResult, ref.format == ResultRef.portableLinkProfile { return .success((ref, data)) }
        guard let coverage = study.coverage, let data = coverage.calculatedResult else { return .failure(.notOnDevice) }
        if ref.format == ResultRef.portableCoverage { return .success((ref, data)) }
        // This platform's legacy result: converted the way B converts it on the next save.
        guard ref.format == ResultRef.iosLegacy, let inputs = try? PortableCoverageResult.inputs(coverage),
              let result = try? SavedCoverageCalculation.decode(data).restore(inputs: inputs,
                  nodes: PortableCoverageResult.snapshots(coverage), catalogComplete: coverage.catalogComplete),
              let converted = try? PortableCoverageResult.encode(result),
              let fingerprint = PortableCoverageResult.fingerprint(of: converted) else { return .failure(.notReadableHere) }
        return .success((ResultRef(format: ResultRef.portableCoverage, version: 1, byteLength: converted.count,
                                   sha256: DeploymentPlanStore.sha256(converted), inputFingerprint: fingerprint), converted))
    }

    /// The file name: the study or plan name, or a dated library backup.
    static func fileName(_ package: SavedPlansPackage, exportedAt: Date) -> String {
        let base: String
        switch package.scope {
        case .study: base = package.studies.first?.name ?? "Study"
        case .plan: base = package.studyPlans.first?.name ?? "Plan"
        case .library: base = "NodeScope saved plans \(exportedAt.formatted(.iso8601.year().month().day()))"
        }
        let safe = base.map { "/\\:?%*|\"<>".contains($0) || $0.isNewline ? "-" : $0 }
        return String(String(safe).prefix(100)).trimmingCharacters(in: .whitespaces) + ".\(SavedPlansPackage.fileExtension)"
    }

    /// What a package reveals, for the preview before sharing.
    struct Preview: Equatable {
        var studies = 0, plans = 0, candidateSitePlans = 0
        /// Precise places: study sites and endpoints, and candidate sites.
        var locations = 0
        /// Distinct captured nodes, by analyzer and ID; a few names for the summary.
        var nodes = 0
        var nodeNames: [String] = []
        /// Analyzer and region of each study's inputs, as recorded.
        var analyzers: [String] = []
        var unknownSource = 0
        var notedPlans = 0
        var results = 0
        var resultBytes = 0
        var omitted: [(name: String, reason: SavedPlansPackage.OmissionReason)] = []

        static func == (a: Preview, b: Preview) -> Bool {
            a.studies == b.studies && a.plans == b.plans && a.locations == b.locations && a.nodes == b.nodes
                && a.analyzers == b.analyzers && a.notedPlans == b.notedPlans && a.results == b.results
                && a.omitted.map(\.name) == b.omitted.map(\.name)
        }

        init() {}

        init(_ package: SavedPlansPackage) {
            studies = package.studies.count
            plans = package.studyPlans.count
            candidateSitePlans = package.candidateSitePlans.count
            locations = package.candidateSitePlans.reduce(0) { $0 + $1.sites.count }
            var seen = Set<String>(), names: [String] = [], places = Set<String>()
            for study in package.studies {
                locations += study.kind == .linkProfile ? 2 : 1
                for node in study.coverage?.nodes ?? [] where seen.insert("\(node.sourceID)/\(node.nodeID)").inserted {
                    if let name = node.name, !names.contains(name) { names.append(name) }
                }
                if let origin = study.origin, let host = origin.host {
                    places.insert(origin.region.map { "\(host) · \($0)" } ?? host)
                } else {
                    unknownSource += 1
                }
            }
            nodes = seen.count
            nodeNames = Array(names.prefix(3))
            analyzers = places.sorted()
            notedPlans = package.studyPlans.filter { $0.notes != nil }.count
            results = package.studies.filter { $0.resultRef != nil }.count
            resultBytes = package.results.values.reduce(0) { $0 + $1.count }
            let byID = Dictionary(package.studies.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
            omitted = package.omittedResults.map { (byID[$0.studyID] ?? "A study", $0.reason) }
        }
    }

    static func describe(_ reason: SavedPlansPackage.OmissionReason) -> String {
        switch reason {
        case .notOnDevice: "Result not on this device"
        case .couldNotBeVerified: "Result could not be verified"
        case .needsNewerVersion: "Result needs a newer version of NodeScope"
        case .notReadableHere: "Result can't be converted on this device"
        }
    }
}

extension SavedPlansPackage.OmissionReason: Error {}

/// Choices, a preview of what the file reveals, then the share sheet (which includes Save to Files).
struct ExportPackageSheet: View {
    let contents: SavedPlansExport.Contents
    @Environment(\.dismiss) private var dismiss
    @State private var model = CandidatePlansModel.shared
    @State private var includesResults = true
    @State private var includesNotes = true
    @State private var prepared = false
    @State private var preview = SavedPlansExport.Preview()
    @State private var file: URL?
    @State private var failed = false
    @State private var exportedAt = Date()

    private var title: String {
        switch contents {
        case .study: "Export study"
        case .plan: "Export plan"
        case .library: "Back up library"
        }
    }

    /// Whether the study or plan has notes to offer omitting (studies have none).
    private var hasNotes: Bool {
        switch contents {
        case .study: false
        case .plan(let id): model.studyPlans.first { $0.id == id }?.notes != nil
        case .library: model.studyPlans.contains { $0.notes != nil }
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Include available results", isOn: $includesResults)
                    if hasNotes { Toggle("Include plan notes", isOn: $includesNotes) }
                } footer: {
                    Text(includesResults
                         ? "Results open on another device without calculating again."
                         : "Inputs only: the recipient calculates again to see results.")
                }
                Section {
                    LabeledContent("Studies", value: "\(preview.studies)")
                    if preview.plans > 0 { LabeledContent("Plans", value: "\(preview.plans)") }
                    if preview.candidateSitePlans > 0 { LabeledContent("Candidate site lists", value: "\(preview.candidateSitePlans)") }
                    LabeledContent("Precise locations", value: "\(preview.locations)")
                    if preview.nodes > 0 {
                        LabeledContent("Node names and IDs") {
                            Text(nodeSummary).multilineTextAlignment(.trailing)
                        }
                    }
                    if !preview.analyzers.isEmpty || preview.unknownSource > 0 {
                        LabeledContent("Analyzer") {
                            Text(analyzerSummary).multilineTextAlignment(.trailing)
                        }
                    }
                    if preview.notedPlans > 0 { LabeledContent("Plan notes", value: "\(preview.notedPlans)") }
                    if includesResults {
                        LabeledContent("Results", value: preview.results == 0 ? "None"
                            : "\(preview.results) · \(ByteCountFormatter.string(fromByteCount: Int64(preview.resultBytes), countStyle: .file))")
                    }
                } header: {
                    Text("This file shows")
                } footer: {
                    Text("Locations aren't hidden: a study without them couldn't be calculated again. Unfinished studies, caches and settings are never included.")
                }
                if includesResults && !preview.omitted.isEmpty {
                    Section("Results not included") {
                        ForEach(Array(preview.omitted.enumerated()), id: \.offset) { _, item in
                            LabeledContent(item.name) {
                                Text(SavedPlansExport.describe(item.reason)).multilineTextAlignment(.trailing)
                            }
                        }
                    }
                }
                if failed {
                    Section { Text("The file couldn't be prepared. Your saved plans haven't changed.").foregroundStyle(.red) }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if let file {
                        ShareLink(item: file) { Label("Share", systemImage: "square.and.arrow.up") }
                    } else {
                        ProgressView()
                    }
                }
            }
            .task(id: "\(includesResults)-\(includesNotes)-\(model.isLoaded)") { await prepare() }
            .onDisappear { if let file { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) } }
        }
    }

    private var nodeSummary: String {
        let names = preview.nodeNames.joined(separator: ", ")
        let more = preview.nodes - preview.nodeNames.count
        return names.isEmpty ? "\(preview.nodes)" : more > 0 ? "\(names) and \(more) more" : names
    }

    private var analyzerSummary: String {
        var parts = preview.analyzers
        if preview.unknownSource > 0 { parts.append("Unknown source (\(preview.unknownSource))") }
        return parts.joined(separator: "\n")
    }

    /// Builds the file off the main thread; the choices start from the document's defaults the first time.
    private func prepare() async {
        await model.load()
        let previous = file
        file = nil
        failed = false
        if let previous { try? FileManager.default.removeItem(at: previous.deletingLastPathComponent()) }
        let milliseconds = Int64((exportedAt.timeIntervalSince1970 * 1000).rounded())
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        let (contents, plans, studyPlans, studies) = (self.contents, model.plans, model.studyPlans, model.studies)
        if !prepared {
            prepared = true
            // A library backup includes results; a study or plan only when every result can travel.
            if contents != .library, let trial = SavedPlansExport.package(contents, plans: plans, studyPlans: studyPlans,
                studies: studies, includesResults: true, includesNotes: true, exportedAtMilliseconds: milliseconds,
                appVersion: version), !trial.omittedResults.isEmpty {
                includesResults = false
                return  // The changed toggle runs this again.
            }
        }
        let (results, notes, at) = (includesResults, includesNotes, exportedAt)
        let built = await Task.detached(priority: .userInitiated) { () -> (SavedPlansExport.Preview, URL)? in
            guard let package = SavedPlansExport.package(contents, plans: plans, studyPlans: studyPlans, studies: studies,
                    includesResults: results, includesNotes: notes, exportedAtMilliseconds: milliseconds, appVersion: version),
                  let data = try? package.encoded() else { return nil }
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("Export-\(UUID().uuidString)", isDirectory: true)
            let url = folder.appendingPathComponent(SavedPlansExport.fileName(package, exportedAt: at))
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                try data.write(to: url, options: .atomic)
            } catch { return nil }
            return (SavedPlansExport.Preview(package), url)
        }.value
        guard !Task.isCancelled else {
            if let url = built?.1 { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
            return
        }
        if let built {
            preview = built.0
            file = built.1
        } else {
            failed = true
        }
    }
}
