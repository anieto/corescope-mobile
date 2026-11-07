import Foundation
import Observation

enum CoverageStudyRunFailure: Sendable { case operation, invalidResult }

/// Screen-owned execution. The operation owns and releases terrain leases before returning.
/// Cancellation changes presentation immediately, but admission stays closed until cleanup ends.
@MainActor @Observable
final class CoverageStudyRunner {
    private(set) var phase: CoverageStudyPublicationPhase = .idle
    private(set) var result: CoverageStudyResult?
    private(set) var failure: CoverageStudyRunFailure?
    private(set) var isBusy = false
    @ObservationIgnored private let publication = CoverageStudyPublication()
    @ObservationIgnored private var worker: Task<CoverageStudyResult, Error>?
    @ObservationIgnored private var executionID: UUID?

    /// Returns false if another operation is still executing or releasing resources.
    @discardableResult
    func run(inputs: CoverageStudyInputs,
             operation: @escaping @Sendable (UUID, CoverageStudyInputs) async throws -> CoverageStudyResult) async -> Bool {
        guard !isBusy else { return false }
        isBusy = true
        failure = nil
        let id = publication.begin()
        executionID = id
        refresh()
        let task = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let output = try await operation(id, inputs)
            try Task.checkCancellation()
            return output
        }
        worker = task
        defer {
            worker = nil
            executionID = nil
            isBusy = false
            refresh()
        }
        do {
            let output = try await withTaskCancellationHandler {
                try await task.value
            } onCancel: {
                task.cancel()
            }
            if Task.isCancelled {
                publication.cancel(id)
            } else if !publication.publish(output), publication.fail(id) {
                failure = .invalidResult
            }
        } catch {
            if task.isCancelled || Task.isCancelled || error is CancellationError {
                publication.cancel(id)
            } else if publication.fail(id) {
                failure = .operation
            }
        }
        return true
    }

    func cancel() {
        if let executionID { publication.cancel(executionID) }
        worker?.cancel()
        refresh()
    }

    func invalidate() {
        publication.invalidate()
        worker?.cancel()
        failure = nil
        refresh()
    }

    private func refresh() {
        phase = publication.phase
        result = publication.result
    }
}
