import Foundation
import Observation

@MainActor @Observable
final class CandidatePlansModel {
    static let shared = CandidatePlansModel()
    private(set) var plans: [DeploymentPlan] = []
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
            plans = try await store.load().plans
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
        return await commit(next)
    }

    func delete(_ plan: DeploymentPlan) async {
        _ = await commit(plans.filter { $0.id != plan.id })
    }

    private func commit(_ next: [DeploymentPlan]) async -> Bool {
        guard isLoaded, !isBusy, let store else { return false }
        isBusy = true
        defer { isBusy = false }
        do {
            try await store.save(DeploymentPlanDocument(plans: next))
            plans = next
            error = nil
            return true
        } catch DeploymentPlanStoreError.changedOnDisk {
            error = "Saved plans changed outside this editor. Your draft is still open. Close it before reloading the library."
            return false
        } catch {
            self.error = "The plan could not be saved. Your draft is still open and the previous save is unchanged."
            return false
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
