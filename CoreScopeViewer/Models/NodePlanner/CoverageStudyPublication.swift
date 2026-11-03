import Foundation

enum CoverageStudyPublicationPhase: Sendable { case idle, running, completed, cancelled, failed, stale }

/// UI-owned publication state. Worker code carries the token, never the coordinator.
@MainActor
final class CoverageStudyPublication {
    private(set) var phase: CoverageStudyPublicationPhase = .idle
    private(set) var result: CoverageStudyResult?
    private var activeID: UUID?

    func begin() -> UUID {
        let id = UUID()
        activeID = id
        result = nil
        phase = .running
        return id
    }

    @discardableResult func publish(_ result: CoverageStudyResult) -> Bool {
        guard phase == .running, activeID == result.executionID,
              result.nodes.run.phase == .completed, result.area.cells.count == result.grid.count else { return false }
        self.result = result
        activeID = nil
        phase = .completed
        return true
    }

    @discardableResult func cancel(_ id: UUID) -> Bool {
        finish(id, phase: .cancelled)
    }

    @discardableResult func fail(_ id: UUID) -> Bool {
        finish(id, phase: .failed)
    }

    /// Input changes invalidate active work and retain any published result only as explicitly stale.
    func invalidate() {
        activeID = nil
        phase = result == nil ? .idle : .stale
    }

    private func finish(_ id: UUID, phase: CoverageStudyPublicationPhase) -> Bool {
        guard self.phase == .running, activeID == id else { return false }
        activeID = nil
        result = nil
        self.phase = phase
        return true
    }
}
