import Foundation
import Testing
@testable import CoreScopeViewer

@MainActor
struct CoverageStudyPublicationTests {
    @Test func actionableFailuresKeepTheirCause() {
        #expect(CoverageStudyRunFailure.classify(URLError(.notConnectedToInternet)) == .network)
        #expect(CoverageStudyRunFailure.classify(CoverageTerrainDownloadFailure.httpStatus(404)) == .terrain)
        #expect(CoverageStudyRunFailure.classify(CoverageTerrainDownloadFailure.httpStatus(503)) == .network)
        #expect(CoverageStudyRunFailure.classify(CoverageTerrainDownloadFailure.downloadLimit) == .limits)
        #expect(CoverageStudyRunFailure.classify(CoverageTerrainCacheFailure.quota) == .storage)
        #expect(CoverageStudyRunFailure.classify(CoverageTerrainCacheFailure.missing) == .terrain)
    }

    private func result(_ id: UUID, completed: Bool = true, cells: Bool = true, terrain: CoverageStudyTerrainSources? = nil) throws -> CoverageStudyResult {
        let grid = try CoverageGrid(radiusMeters: 1000, spacingMeters: 1000,
            minEastMeters: 0, maxEastMeters: 0, minNorthMeters: 0, maxNorthMeters: 0)
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        var run = try CoverageNodeRun(nodes: [], catalogComplete: true, evaluationLimit: 1, radiusMeters: 1000)
        if completed { run.complete() }
        return try CoverageStudyResult(executionID: id, inputs: CoverageStudyInputs(transmitter: CoverageTerrainCoordinate(latitude: 0, longitude: 0),
            grid: grid, budget: budget, frequencyMHz: 915, transmitHeightMeters: 30, receiveHeightMeters: 1.5),
            limits: CoverageStudyLimits(areaSamples: 0, areaGeometry: 0, nodeSamples: 0, nodeGeometry: 0, nodeModelCalls: 0),
            area: CoverageAreaResult(cells: cells ? [CoverageAreaCell(state: .nearSite, lossDb: nil, marginDb: nil)] : [],
                chargedSamples: 0, chargedGeometry: 0, workLimited: false),
            nodes: CoverageNodeBatchResult(run: run, chargedTerrainEvaluations: 0, chargedGeometryEvaluations: 0, modelCalls: 0), terrainSources: terrain)
    }
    @Test func publishedTerrainSourcesRemainWithStaleResultAndClearForNextRun() async throws {
        let descriptor = try CoverageTerrainDescriptor(dataset: "test", revision: "r1", tile: "N00E000",
            compressedSHA256: String(repeating: "0", count: 64), packedBytes: 20, side: 3,
            url: "https://example.com/tile", verticalDatum: "EGM96", attribution: "Test source")
        var catalog = [descriptor]
        let terrain = CoverageStudyTerrainSources(descriptors: catalog)
        catalog.removeAll()
        let runner = CoverageStudyRunner()
        let inputs = try result(UUID()).inputs
        await runner.runWithProgress(inputs: inputs) { id, _, report in
            await report(.calculating)
            return try await result(id, terrain: terrain)
        }
        #expect(runner.result?.terrainSources?.descriptors.first?.cacheKey == descriptor.cacheKey)
        #expect(runner.result?.terrainSources?.descriptors.first?.attribution == "Test source")
        runner.invalidate()
        #expect(runner.phase == .stale)
        #expect(runner.result?.terrainSources?.descriptors.count == 1)
        await runner.run(inputs: inputs) { id, _ in try await result(id) }
        #expect(runner.result?.terrainSources == nil)
    }

    @Test func runnerRejectsChangedInputsButAcceptsEquivalentValues() async throws {
        let runner = CoverageStudyRunner()
        let baseline = try result(UUID())
        let original = baseline.inputs
        let fields: [String: String] = ["latitude": "0", "longitude": "0", "frequency": "915",
            "txHeight": "30", "rxHeight": "1.5", "power": "22", "txGain": "3", "rxGain": "2",
            "txLoss": "1", "rxLoss": "1", "sensitivity": "-130", "reserve": "5"]
        var variants: [CoverageStudyInputs] = []
        for key in fields.keys.sorted() {
            var changed = fields
            changed[key] = String((Double(fields[key] ?? "0") ?? 0) + 1)
            let parsed = try CoverageStudyInputs.fromEditor(fields: changed, radiusMeters: 1000, spacingMeters: 1000)
            variants.append(try CoverageStudyInputs(transmitter: parsed.transmitter, grid: original.grid,
                budget: parsed.budget, frequencyMHz: parsed.frequencyMHz,
                transmitHeightMeters: parsed.transmitHeightMeters, receiveHeightMeters: parsed.receiveHeightMeters))
        }
        let grids = try [
            CoverageGrid(radiusMeters: 2000, spacingMeters: 1000, minEastMeters: 0, maxEastMeters: 0, minNorthMeters: 0, maxNorthMeters: 0),
            CoverageGrid(radiusMeters: 1000, spacingMeters: 500, minEastMeters: 0, maxEastMeters: 0, minNorthMeters: 0, maxNorthMeters: 0),
            CoverageGrid(radiusMeters: 1000, spacingMeters: 1000, minEastMeters: 1000, maxEastMeters: 1000, minNorthMeters: 0, maxNorthMeters: 0),
            CoverageGrid(radiusMeters: 1000, spacingMeters: 1000, minEastMeters: 0, maxEastMeters: 0, minNorthMeters: 1000, maxNorthMeters: 1000),
            CoverageGrid(radiusMeters: 1000, spacingMeters: 1000, minEastMeters: 0, maxEastMeters: 1000, minNorthMeters: 0, maxNorthMeters: 0),
            CoverageGrid(radiusMeters: 1000, spacingMeters: 1000, minEastMeters: 0, maxEastMeters: 0, minNorthMeters: -1000, maxNorthMeters: 0)
        ]
        for grid in grids {
            variants.append(try CoverageStudyInputs(transmitter: original.transmitter, grid: grid,
                budget: original.budget, frequencyMHz: original.frequencyMHz,
                transmitHeightMeters: original.transmitHeightMeters, receiveHeightMeters: original.receiveHeightMeters))
        }
        for changed in variants {
            #expect(!original.matches(changed))
            await runner.run(inputs: original) { id, _ in
                CoverageStudyResult(executionID: id, inputs: changed, limits: baseline.limits,
                    area: baseline.area, nodes: baseline.nodes)
            }
            #expect(runner.failure == .invalidResult && runner.result == nil && !runner.isBusy)
        }
        await runner.run(inputs: original) { id, _ in try await result(id) }
        #expect(runner.phase == .completed && runner.failure == nil)
    }

    private actor CleanupGate {
        private var continuation: CheckedContinuation<Void, Never>?
        private var released = false
        func wait() async {
            if released { return }
            await withCheckedContinuation { continuation = $0 }
        }
        func release() {
            released = true
            continuation?.resume()
            continuation = nil
        }
    }

    private actor ProgressCapture {
        var report: (@Sendable (CoveragePreparationStage) async -> Void)?
        func save(_ report: @escaping @Sendable (CoveragePreparationStage) async -> Void) { self.report = report }
        func send(_ stage: CoveragePreparationStage) async { await report?(stage) }
    }

    @Test func progressBelongsOnlyToTheActiveRun() async throws {
        let runner = CoverageStudyRunner()
        let inputs = try result(UUID()).inputs
        let old = ProgressCapture()
        await runner.runWithProgress(inputs: inputs) { id, _, report in
            await old.save(report)
            await report(.validating)
            #expect(await runner.preparationStage == .validating)
            await report(.calculating)
            #expect(await runner.preparationStage == .calculating)
            return try await result(id)
        }
        #expect(runner.preparationStage == nil)
        await old.send(.preparingTerrain)
        #expect(runner.preparationStage == nil)
        await runner.runWithProgress(inputs: inputs) { id, _, report in
            await report(.validating)
            await old.send(.calculating)
            #expect(await runner.preparationStage == .validating)
            await runner.cancel()
            await report(.preparingTerrain)
            #expect(await runner.preparationStage == nil)
            return try await result(id)
        }
        #expect(runner.phase == .cancelled && runner.preparationStage == nil)
        await runner.runWithProgress(inputs: inputs) { id, _, report in
            await report(.preparingTerrain)
            await runner.invalidate()
            await report(.calculating)
            #expect(await runner.preparationStage == nil)
            return try await result(id)
        }
        #expect(runner.phase == .idle && runner.preparationStage == nil)
        await runner.runWithProgress(inputs: inputs) { _, _, report in
            await report(.calculating)
            throw CoverageLinkBudgetFailure.invalidInput
        }
        #expect(runner.phase == .failed && runner.preparationStage == nil)
    }

    @Test func parentCancellationWaitsForWorkerCleanup() async throws {
        let runner = CoverageStudyRunner()
        let inputs = try result(UUID()).inputs
        let entered = AsyncStream<Void>.makeStream()
        let cleanup = CleanupGate()
        let parent = Task {
            await runner.run(inputs: inputs) { id, _ in
                entered.continuation.yield(())
                await cleanup.wait()
                return try await result(id)
            }
        }
        for await _ in entered.stream { break }
        parent.cancel()
        #expect(runner.isBusy)
        let accepted = await runner.run(inputs: inputs) { id, _ in try await result(id) }
        #expect(!accepted)
        await cleanup.release()
        _ = await parent.value
        entered.continuation.finish()
        #expect(runner.phase == .cancelled && runner.result == nil && !runner.isBusy)
    }

    @Test func runnerOwnsCancellationAdmissionAndLatePublication() async throws {
        let runner = CoverageStudyRunner()
        let inputs = try result(UUID()).inputs
        let completed = await runner.run(inputs: inputs) { id, captured in
            #expect(captured.frequencyMHz == inputs.frequencyMHz)
            return try await result(id)
        }
        #expect(completed)
        #expect(runner.phase == .completed && runner.result != nil && !runner.isBusy)
        runner.invalidate()
        #expect(runner.phase == .stale && runner.result != nil)
        let cancelled = await runner.run(inputs: inputs) { id, _ in
            await runner.cancel()
            #expect(await runner.isBusy)
            let accepted = await runner.run(inputs: inputs) { id, _ in try await result(id) }
            #expect(!accepted)
            return try await result(id)
        }
        #expect(cancelled)
        #expect(runner.phase == .cancelled && runner.result == nil && !runner.isBusy)
        #expect(await runner.run(inputs: inputs) { id, _ in
            await runner.invalidate()
            return try await result(id)
        })
        #expect(runner.phase == .idle && runner.result == nil && runner.failure == nil)
        #expect(await runner.run(inputs: inputs) { _, _ in try await result(UUID()) })
        #expect(runner.phase == .failed && runner.failure == .invalidResult)
        #expect(await runner.run(inputs: inputs) { _, _ in throw CoverageLinkBudgetFailure.invalidInput })
        #expect(runner.phase == .failed && runner.failure == .operation && !runner.isBusy)
    }

    @Test func supersededCallbacksCannotReplaceActiveOrCompletedStudy() throws {
        let gate = CoverageStudyPublication()
        let old = gate.begin()
        let current = gate.begin()
        #expect(!gate.publish(try result(old)))
        #expect(!gate.cancel(old))
        #expect(!gate.fail(old))
        #expect(gate.phase == .running && gate.result == nil)
        #expect(gate.publish(try result(current)))
        #expect(!gate.publish(try result(current)))
        #expect(!gate.fail(current))
        #expect(gate.result?.executionID == current)
        gate.invalidate()
        #expect(gate.phase == .stale && gate.result?.executionID == current)
        #expect(!gate.publish(try result(current)))
        _ = gate.begin()
        #expect(gate.result == nil)
    }
    @Test func cancellationInvalidationAndMalformedResultsCannotPublish() throws {
        let gate = CoverageStudyPublication()
        let cancelled = gate.begin()
        #expect(gate.cancel(cancelled))
        #expect(!gate.publish(try result(cancelled)))
        #expect(gate.phase == .cancelled)
        let invalidated = gate.begin()
        gate.invalidate()
        #expect(!gate.publish(try result(invalidated)))
        let current = gate.begin()
        #expect(!gate.publish(try result(current, completed: false)))
        #expect(!gate.publish(try result(current, cells: false)))
        #expect(gate.fail(current))
        #expect(gate.phase == .failed && gate.result == nil)
    }
}

struct CoverageBudgetReuseTests {
    @Test func updatesMarginsButKeepsThresholdAdjacentPathsPending() throws {
        let grid = try CoverageGrid.studyArea(radiusMeters: 1000, spacingMeters: 1000)
        func inputs(power: Double, height: Double = 20) throws -> CoverageStudyInputs {
            try CoverageStudyInputs(transmitter: .init(latitude: 0, longitude: 0), grid: grid,
                budget: CoverageLinkBudget(transmitPowerDbm: power, transmitGainDbi: 0, receiveGainDbi: 0,
                    transmitLossDb: 0, receiveLossDb: 0, sensitivityDbm: -120, reserveDb: 10),
                frequencyMHz: 915, transmitHeightMeters: height, receiveHeightMeters: 2)
        }
        let original = try inputs(power: 20)
        let node = CoverageNodeSnapshot(sourceID: "test", nodeID: "node", latitude: 0.005, longitude: 0)
        var run = try CoverageNodeRun(nodes: [node], catalogComplete: true, evaluationLimit: 1, radiusMeters: 1000)
        let accepted = run.accept(CoverageNodeEvaluation(runID: run.id, node: node, distanceMeters: 500,
            prediction: .qualifiedMargin(10)))
        let completed = run.complete()
        #expect(accepted)
        #expect(completed)
        let cells = (0..<grid.count).map { index -> CoverageAreaCell in
            switch grid.point(at: index)?.state {
            case .nearSite: return .init(state: .nearSite, lossDb: nil, marginDb: nil)
            case .outsideRadius: return .init(state: .outsideRadius, lossDb: nil, marginDb: nil)
            case .eligible: return .init(state: .qualified, lossDb: 120, marginDb: 10)
            case nil: return .init(state: .notComputed, lossDb: nil, marginDb: nil)
            }
        }
        let source = CoverageStudyResult(executionID: UUID(), inputs: original,
            limits: .init(areaSamples: 1000, areaGeometry: 1000, nodeSamples: 1000, nodeGeometry: 1000, nodeModelCalls: 10),
            area: .init(cells: cells, chargedSamples: 100, chargedGeometry: 100, workLimited: false),
            nodes: .init(run: run, chargedTerrainEvaluations: 100, chargedGeometryEvaluations: 100, modelCalls: 3),
            terrainSources: .init(descriptors: []),
            model: .init(sourceRevision: CoverageRFKernel.sourceRevision, scenario: CoverageRFKernel.scenario))
        let stronger = try source.updatingBudget(inputs(power: 25))
        #expect(stronger.area.cells.filter { $0.state == .qualified }.allSatisfy { $0.marginDb == 15 && $0.lossDb == 120 })
        #expect(stronger.nodes.run.status(for: node).adjustedMarginDb == 15)
        #expect(stronger.nodes.modelCalls == 0)
        #expect(stronger.executionID != source.executionID)
        let threshold = try source.updatingBudget(inputs(power: 10))
        #expect(threshold.area.pendingCount > 0)
        #expect(threshold.nodes.run.status(for: node).reason == .processingLimit)
        #expect(throws: CoverageTerrainProfileFailure.invalidInput) {
            try source.updatingBudget(inputs(power: 25, height: 21))
        }
    }
}
