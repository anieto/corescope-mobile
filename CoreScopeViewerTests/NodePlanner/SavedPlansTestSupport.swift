import Foundation
@testable import CoreScopeViewer

struct SavedPlansContent: Equatable {
    let plans: [DeploymentPlan]
    let studies: [SavedStudy]
}

extension DeploymentPlanDocument {
    /// A library's content without version 3 bookkeeping (versions, revisions, result references and states).
    var content: SavedPlansContent {
        SavedPlansContent(plans: plans.map { var plan = $0; plan.revision = nil; return plan },
            studies: studies.map { var study = $0; study.revision = nil; study.resultRef = nil; study.resultStatus = .none; return study })
    }
}

/// Where the store keeps a pre-upgrade original with these exact bytes.
func migrationBackup(_ file: URL, _ original: Data) -> URL {
    file.deletingLastPathComponent().appendingPathComponent("migration-backups/\(DeploymentPlanStore.sha256(original)).json")
}
