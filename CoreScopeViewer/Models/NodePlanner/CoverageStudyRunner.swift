import Foundation
import Observation

enum CoverageStudyRunFailure: Sendable {
    case operation, invalidResult, network, terrain, storage, limits

    static func classify(_ error: Error) -> Self {
        if error is URLError { return .network }
        if let download = error as? CoverageTerrainDownloadFailure {
            switch download {
            case .downloadLimit, .invalidLimits: return .limits
            case .httpStatus(let status): return status == 404 ? .terrain : .network
            case .corrupt: return .terrain
            }
        }
        if let cache = error as? CoverageTerrainCacheFailure {
            return cache == .missing ? .terrain : .storage
        }
        if error is CoverageGridPreflightFailure { return .limits }
        if error is CoverageTerrainFootprintFailure || error is CoverageTerrainRepositoryFailure { return .terrain }
        return .operation
    }
}

/// Screen-owned execution. The operation owns and releases terrain leases before returning.
/// Cancellation changes presentation immediately, but admission stays closed until cleanup ends.
@MainActor @Observable
final class CoverageStudyRunner {
    private(set) var phase: CoverageStudyPublicationPhase = .idle
    private(set) var result: CoverageStudyResult?
    private(set) var failure: CoverageStudyRunFailure?
    private(set) var isBusy = false
    private(set) var preparationStage: CoveragePreparationStage?
    private(set) var startedAt: Date?
    @ObservationIgnored private let publication = CoverageStudyPublication()
    @ObservationIgnored private var worker: Task<CoverageStudyResult, Error>?
    @ObservationIgnored private var executionID: UUID?

    /// Progress callbacks may outlive their operation; identity and phase reject obsolete reports.
    @discardableResult
    func runWithProgress(inputs: CoverageStudyInputs,
                         operation: @escaping @Sendable (UUID, CoverageStudyInputs,
                             @escaping @Sendable (CoveragePreparationStage) async -> Void) async throws -> CoverageStudyResult) async -> Bool {
        await run(inputs: inputs) { [weak self] id, captured in
            try await operation(id, captured) { [weak self] stage in
                await self?.report(stage, executionID: id)
            }
        }
    }

    private func report(_ stage: CoveragePreparationStage, executionID id: UUID) {
        guard executionID == id, publication.phase == .running else { return }
        // Worker notifications cross to the main actor asynchronously. Never let a
        // delayed map count replace the later node stage or regress the count.
        if case .checkingNodes = preparationStage { return }
        if case .mapSamples(let old, _) = preparationStage,
           case .mapSamples(let next, _) = stage, next < old { return }
        preparationStage = stage
    }

    /// Returns false if another operation is still executing or releasing resources.
    @discardableResult
    func run(inputs: CoverageStudyInputs,
             operation: @escaping @Sendable (UUID, CoverageStudyInputs) async throws -> CoverageStudyResult) async -> Bool {
        guard !isBusy else { return false }
        isBusy = true
        startedAt = Date()
        failure = nil
        preparationStage = nil
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
            } else if (!output.inputs.matches(inputs) || !publication.publish(output)), publication.fail(id) {
                failure = .invalidResult
            }
        } catch {
            if task.isCancelled || Task.isCancelled || error is CancellationError {
                publication.cancel(id)
            } else if publication.fail(id) {
                failure = CoverageStudyRunFailure.classify(error)
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
        if publication.phase != .running { preparationStage = nil }
        phase = publication.phase
        result = publication.result
    }
}
