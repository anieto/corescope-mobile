import Foundation
import Observation

@MainActor @Observable
final class CandidatePlansModel {
    static let shared = CandidatePlansModel()
    private(set) var plans: [DeploymentPlan] = []
    private(set) var studies: [SavedStudy] = []
    /// Named study plans (version 3). Membership lives only in each study's `planID`.
    private(set) var studyPlans: [StudyPlan] = []
    private(set) var isLoaded = false
    private(set) var isBusy = false
    var error: LocalizedStringResource?
    private var store: DeploymentPlanStore?

    /// Tests pass their own store; the app uses `shared`, which opens Application Support on first load.
    init(store: DeploymentPlanStore? = nil) {
        self.store = store
    }

    func load() async {
        guard !isBusy, !isLoaded else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            if store == nil { store = try DeploymentPlanStore.applicationStore() }
            guard let store else { return }
            let document = try await store.load()
            plans = document.plans
            apply(document)
            isLoaded = true
            error = nil
        } catch {
            self.error = "Saved plans could not be opened. Your files have not been changed. Try again when storage is available."
        }
    }

    func save(_ plan: DeploymentPlan) async -> Bool {
        var next = plans
        if let index = next.firstIndex(where: { $0.id == plan.id }) { next[index] = plan }
        else { next.append(plan) }
        return await commit(next) != nil
    }

    func delete(_ plan: DeploymentPlan) async {
        _ = await commit(plans.filter { $0.id != plan.id })
    }

    enum SaveMode: Hashable { case update, saveAsNew }
    enum Destination: Hashable { case standalone, plan(String), newPlan }

    /// Saves `study` and returns it as stored, with its revision; nil if it couldn't be saved.
    /// Update refuses a study changed or deleted since it was opened (`study.revision` is the one opened);
    /// Save as new always creates a new record. A new plan and its first study are one commit.
    /// `inputsOnly` acknowledges that Update drops a saved result this device can't show (see `dropsUnreadableResult`).
    func saveStudy(_ study: SavedStudy, mode: SaveMode, destination: Destination, newPlanName: String = "",
                   inputsOnly: Bool = false) async -> SavedStudy? {
        let now = Self.now()
        var containers = studyPlans
        let target: String?
        switch destination {
        case .standalone: target = nil
        case .plan(let id):
            guard containers.contains(where: { $0.id == id }) else {
                error = "That plan no longer exists. Choose another place to save."
                return nil
            }
            target = id
        case .newPlan:
            let plan = StudyPlan(id: Self.newID(), name: newPlanName, notes: nil,
                                 createdAtMilliseconds: now, updatedAtMilliseconds: now, revision: nil)
            containers.append(plan)
            target = plan.id
        }
        var saving = study
        saving.resultRef = nil
        var touched: Set<String?> = [target]
        switch mode {
        case .update:
            guard let current = studies.first(where: { $0.id == study.id }) else {
                error = "This study was deleted since you opened it. Save it as new to keep it."
                return nil
            }
            guard study.revision == nil || current.revision == study.revision else {
                error = "This study was changed elsewhere since you opened it. Save as new to keep your version."
                return nil
            }
            guard inputsOnly || !dropsUnreadableResult(study) else {
                error = "The saved result can't be shown on this device and doesn't match the changed inputs. Confirm saving inputs only, or save as new."
                return nil
            }
            // The tool says where its inputs came from; it keeps the saved study's source unless they were captured again.
            saving.importOrigin = current.importOrigin
            touched.insert(current.planID)
        case .saveAsNew:
            saving.id = Self.newID()
            saving.createdAtMilliseconds = now
            saving.revision = nil
            saving.importOrigin = nil
        }
        saving.planID = target
        saving.updatedAtMilliseconds = max(saving.createdAtMilliseconds, now)
        var next = studies
        if let index = next.firstIndex(where: { $0.id == saving.id }) { next[index] = saving } else { next.append(saving) }
        guard let saved = await commit(plans, studies: next, studyPlans: Self.touch(containers, touched, now)) else { return nil }
        return saved.studies.first { $0.id == saving.id }
    }

    /// Whether updating `study` would detach a saved result this device can't show (missing, unverified or
    /// another format): its calculation inputs changed and it brings no new result. A matching result
    /// can't stay attached to different inputs, so Update then saves inputs only, once confirmed.
    func dropsUnreadableResult(_ study: SavedStudy) -> Bool {
        guard let current = studies.first(where: { $0.id == study.id }), current.resultRef != nil,
              current.resultStatus != .available, study.coverage?.calculatedResult == nil,
              study.linkProfileResult == nil else { return false }
        return !DeploymentPlanStore.sameInputs(current, study)
    }

    /// What moving every candidate-site plan to Saved Plans would create, and what can't carry over exactly.
    struct CandidateConversion {
        var plans: [StudyPlan] = []
        var studies: [SavedStudy] = []
        var notes: [String] = []
    }

    /// Each candidate-site plan becomes a study plan of the same name; each site an inputs-only Estimated
    /// Coverage study at its location with its equipment, and the tool's defaults for what a site doesn't
    /// hold. Nothing is calculated. Sites Coverage can't place (beyond 85° latitude) are listed, not moved.
    func candidateConversion(unit: DistanceUnit) -> CandidateConversion {
        let defaults = EstimatedCoverageModel(distanceUnit: unit)
        let now = Self.now()
        var conversion = CandidateConversion()
        for (planIndex, plan) in plans.enumerated() {
            let created = now - Int64(planIndex)
            let container = StudyPlan(id: Self.newID(), name: plan.name, notes: nil,
                                      createdAtMilliseconds: created, updatedAtMilliseconds: created, revision: nil)
            conversion.plans.append(container)
            for (siteIndex, site) in plan.sites.enumerated() {
                let label = "\(plan.name) › \(site.name)"
                guard (-85...85).contains(site.latitude) else {
                    conversion.notes.append("\(label): too close to a pole for Coverage, so it isn't moved.")
                    continue
                }
                let assumptions = site.assumptions
                func value(_ saved: Double?, _ fallback: Double, _ range: ClosedRange<Double>, _ what: String) -> Double {
                    guard let saved else { return fallback }
                    if range.contains(saved) { return saved }
                    conversion.notes.append("\(label): \(what) is outside what Coverage accepts; the default is used.")
                    return fallback
                }
                let loss: Double = assumptions.cableLossDB == nil && assumptions.additionalLossDB == nil
                    ? defaults.transmitLossDb : (assumptions.cableLossDB ?? 0) + (assumptions.additionalLossDB ?? 0)
                let coverage = SavedCoverage(
                    transmitter: SavedEndpoint(name: site.name, latitude: site.latitude, longitude: site.longitude,
                        antenna: SavedAntenna(
                            heightMeters: value(assumptions.antennaHeightMeters, defaults.transmitHeightMeters, 1...3000, "antenna height"),
                            gainDbi: assumptions.antennaGainDBi ?? defaults.transmitGainDbi, lossDb: loss)),
                    receiver: SavedAntenna(heightMeters: defaults.receiveHeightMeters, gainDbi: defaults.receiveGainDbi,
                                           lossDb: defaults.receiveLossDb),
                    frequencyMHz: value(assumptions.frequencyMHz, defaults.frequencyMHz, 30...6000, "frequency"),
                    transmitPowerDbm: assumptions.transmitPowerDBm ?? defaults.transmitPowerDbm,
                    sensitivityDbm: defaults.sensitivityDbm, reserveDb: defaults.reserveDb,
                    radiusMeters: defaults.radiusMeters, spacingMeters: defaults.spacingMeters,
                    nodes: [], catalogComplete: false)
                // Later sites slightly older, so the plan lists them in their original order.
                let time = created - Int64(siteIndex)
                let study = SavedStudy(id: Self.newID(), name: site.name, createdAtMilliseconds: time,
                                       updatedAtMilliseconds: time, kind: .coverage, coverage: coverage, planID: container.id)
                guard (try? study.validate()) != nil else {
                    conversion.notes.append("\(label): its values can't be used for Coverage, so it isn't moved.")
                    continue
                }
                conversion.studies.append(study)
            }
        }
        return conversion
    }

    /// Adds the conversion's plans and studies and removes every candidate-site plan, in one save.
    func moveCandidateSites(_ conversion: CandidateConversion) async -> Bool {
        await commit([], studies: studies + conversion.studies, studyPlans: studyPlans + conversion.plans) != nil
    }

    func createPlan(name: String, notes: String?) async -> StudyPlan? {
        let now = Self.now()
        let plan = StudyPlan(id: Self.newID(), name: name, notes: Self.cleanNotes(notes),
                             createdAtMilliseconds: now, updatedAtMilliseconds: now, revision: nil)
        guard let saved = await commit(plans, studyPlans: studyPlans + [plan]) else { return nil }
        return saved.studyPlans.first { $0.id == plan.id }
    }

    func editPlan(_ id: String, name: String, notes: String?) async -> Bool {
        guard let index = studyPlans.firstIndex(where: { $0.id == id }) else { return missingPlan() }
        var next = studyPlans
        next[index].name = name
        next[index].notes = Self.cleanNotes(notes)
        next[index].updatedAtMilliseconds = max(next[index].createdAtMilliseconds, Self.now())
        return await commit(plans, studyPlans: next) != nil
    }

    /// Removes the plan and keeps its studies as standalone studies, in one commit.
    func deletePlan(_ id: String) async -> Bool {
        let next = studies.map { study in
            var study = study
            if study.planID == id { study.planID = nil }
            return study
        }
        return await commit(plans, studies: next, studyPlans: studyPlans.filter { $0.id != id }) != nil
    }

    func renameStudy(_ id: String, to name: String) async -> Bool {
        await changeStudy(id) { $0.name = name }
    }

    /// Changes membership only; never touches inputs or results.
    func moveStudy(_ id: String, to planID: String?) async -> Bool {
        if let planID, !studyPlans.contains(where: { $0.id == planID }) { return missingPlan() }
        return await changeStudy(id) { $0.planID = planID }
    }

    /// A new independent record with the same inputs and result, in the same place.
    func duplicateStudy(_ id: String) async -> SavedStudy? {
        guard let original = studies.first(where: { $0.id == id }) else { _ = missingStudy(); return nil }
        let now = Self.now()
        var copy = original
        copy.id = Self.newID()
        copy.name = uniqueName(Self.copyName(original.name))
        copy.createdAtMilliseconds = now
        copy.updatedAtMilliseconds = now
        copy.revision = nil
        copy.importOrigin = nil
        copy.resultStatus = .none
        guard let saved = await commit(plans, studies: studies + [copy],
                                       studyPlans: Self.touch(studyPlans, [copy.planID], now)) else { return nil }
        return saved.studies.first { $0.id == copy.id }
    }

    /// Appends whole records in one save (an import). Nothing changes if it fails.
    func addRecords(plans newPlans: [DeploymentPlan], studyPlans newContainers: [StudyPlan], studies newStudies: [SavedStudy]) async -> Bool {
        await commit(plans + newPlans, studies: studies + newStudies, studyPlans: studyPlans + newContainers) != nil
    }

    func deleteStudy(_ id: String) async -> Bool {
        let planID = studies.first { $0.id == id }?.planID
        return await commit(plans, studies: studies.filter { $0.id != id },
                            studyPlans: Self.touch(studyPlans, [planID], Self.now())) != nil
    }

    func plan(_ id: String?) -> StudyPlan? { id.flatMap { id in studyPlans.first { $0.id == id } } }

    /// `base`, or `base 2`, `base 3`… if a study already has that name.
    func uniqueName(_ base: String) -> String {
        let names = Set(studies.map(\.name))
        guard names.contains(base) else { return base }
        for number in 2... {
            let candidate = Self.fit(base, suffix: " \(number)")
            if !names.contains(candidate) { return candidate }
        }
        return base
    }

    static func copyName(_ name: String) -> String { fit(name, suffix: " copy") }

    /// Most recently updated first; the ID breaks ties so the order is stable.
    static func sorted<T: Identifiable>(_ items: [T], updated: (T) -> Int64) -> [T] where T.ID == String {
        items.sorted { updated($0) != updated($1) ? updated($0) > updated($1) : $0.id < $1.id }
    }

    private func changeStudy(_ id: String, _ change: (inout SavedStudy) -> Void) async -> Bool {
        guard let index = studies.firstIndex(where: { $0.id == id }) else { return missingStudy() }
        let now = Self.now()
        var next = studies
        let before = next[index].planID
        change(&next[index])
        next[index].updatedAtMilliseconds = max(next[index].createdAtMilliseconds, now)
        return await commit(plans, studies: next, studyPlans: Self.touch(studyPlans, [before, next[index].planID], now)) != nil
    }

    private func missingStudy() -> Bool {
        error = "That study is no longer in Saved Plans."
        return false
    }

    private func missingPlan() -> Bool {
        error = "That plan no longer exists."
        return false
    }

    private static func touch(_ containers: [StudyPlan], _ ids: Set<String?>, _ now: Int64) -> [StudyPlan] {
        containers.map { plan in
            var plan = plan
            if ids.contains(plan.id) { plan.updatedAtMilliseconds = max(plan.createdAtMilliseconds, now) }
            return plan
        }
    }

    private static func cleanNotes(_ notes: String?) -> String? {
        guard let notes, !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return notes
    }

    /// Appends `suffix`, shortening `base` by whole characters to stay within the 160-byte name limit.
    private static func fit(_ base: String, suffix: String) -> String {
        var trimmed = base
        while (trimmed + suffix).utf8.count > 160, !trimmed.isEmpty { trimmed.removeLast() }
        return trimmed + suffix
    }

    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private static func newID() -> String { UUID().uuidString.lowercased() }

    private func apply(_ document: DeploymentPlanDocument) {
        plans = document.plans
        studies = document.studies
        studyPlans = document.studyPlans
    }

    @discardableResult
    private func commit(_ next: [DeploymentPlan], studies nextStudies: [SavedStudy]? = nil,
                        studyPlans nextContainers: [StudyPlan]? = nil) async -> DeploymentPlanDocument? {
        guard isLoaded, !isBusy, let store else { return nil }
        isBusy = true
        defer { isBusy = false }
        do {
            let saved = try await store.save(DeploymentPlanDocument(schemaVersion: 3, plans: next,
                studies: nextStudies ?? studies, studyPlans: nextContainers ?? studyPlans))
            apply(saved)
            error = nil
            return saved
        } catch DeploymentPlanStoreError.changedOnDisk {
            error = "Saved plans changed outside this editor. Your draft is still open. Close it before reloading the library."
            return nil
        } catch DeploymentPlanStoreError.storageFull {
            error = "There isn’t enough storage to save this result. Your previous save is unchanged. Remove an older study, or save inputs only."
            return nil
        } catch DeploymentPlanError.invalidData {
            self.error = "This change couldn’t be saved: a name may be empty or too long, or the library is full (100 plans or 100 studies)."
            return nil
        } catch {
            self.error = "The plan could not be saved. Your draft is still open and the previous save is unchanged."
            return nil
        }
    }

    func reload() async {
        guard !isBusy else { return }
        isLoaded = false
        await load()
    }

    static func newPlan() -> DeploymentPlan {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return DeploymentPlan(id: UUID().uuidString.lowercased(), name: "",
                              createdAtMilliseconds: now, updatedAtMilliseconds: now, sites: [])
    }
}
