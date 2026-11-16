import Foundation
import Observation

@MainActor @Observable
final class CandidatePlansModel {
    static let shared = CandidatePlansModel()
    private(set) var plans: [DeploymentPlan] = []
    private(set) var studies: [SavedStudy] = []
    /// Named study plans (version 3); kept through every save even before A2 shows them.
    private(set) var studyPlans: [StudyPlan] = []
    private(set) var isLoaded = false
    private(set) var isBusy = false
    var error: LocalizedStringResource?
    private var store: DeploymentPlanStore?

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

    /// Saves `study` and returns it as stored, with its revision; nil if it couldn't be saved. A study
    /// changed elsewhere since it was opened isn't overwritten. One deleted since it was opened is
    /// saved under a new ID rather than brought back.
    func saveStudy(_ study: SavedStudy) async -> SavedStudy? {
        let current = studies.first { $0.id == study.id }
        if let current, let opened = study.revision, current.revision != opened {
            error = "This study was changed elsewhere since you opened it. Reopen it to see the latest, or save under a new name."
            return nil
        }
        var saving = study
        if let current {
            // Keep the membership the library has now; an old editor must not undo a later move.
            saving.planID = current.planID
            saving.origin = current.origin
            saving.importOrigin = current.importOrigin
        } else if study.revision != nil {
            saving.id = UUID().uuidString.lowercased()
            saving.revision = nil
            saving.planID = nil
            saving.importOrigin = nil
        }
        var next = studies
        if let index = next.firstIndex(where: { $0.id == saving.id }) { next[index] = saving } else { next.append(saving) }
        guard let saved = await commit(plans, studies: next) else { return nil }
        return saved.studies.first { $0.id == saving.id }
    }

    func deleteStudy(_ study: SavedStudy) async {
        _ = await commit(plans, studies: studies.filter { $0.id != study.id })
    }

    private func apply(_ document: DeploymentPlanDocument) {
        plans = document.plans
        studies = document.studies
        studyPlans = document.studyPlans
    }

    @discardableResult
    private func commit(_ next: [DeploymentPlan], studies nextStudies: [SavedStudy]? = nil) async -> DeploymentPlanDocument? {
        guard isLoaded, !isBusy, let store else { return nil }
        isBusy = true
        defer { isBusy = false }
        do {
            let saved = try await store.save(DeploymentPlanDocument(schemaVersion: 3, plans: next,
                studies: nextStudies ?? studies, studyPlans: studyPlans))
            apply(saved)
            error = nil
            return saved
        } catch DeploymentPlanStoreError.changedOnDisk {
            error = "Saved plans changed outside this editor. Your draft is still open. Close it before reloading the library."
            return nil
        } catch DeploymentPlanStoreError.storageFull {
            error = "There isn’t enough storage to save this result. Your previous save is unchanged. Remove an older study, or save inputs only."
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
