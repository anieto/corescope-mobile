package org.nodescope.android

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageStudyPublicationTest {
    private fun result(id: UUID, completed: Boolean = true, cells: Boolean = true): CoverageStudyResult {
        val run = CoverageNodeRun(emptyList(), true, 1, 1000.0)
        if (completed) run.complete()
        return CoverageStudyResult(id, CoverageStudyInputs(CoverageTerrainCoordinate(0.0, 0.0), CoverageGrid(1000, 1000, 0, 0, 0, 0),
            CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0), 915.0, 30.0, 1.5), CoverageStudyLimits(0, 0, 0, 0, 0),
            CoverageAreaResult(if (cells) listOf(CoverageAreaCell(CoverageAreaState.nearSite)) else emptyList(), 0, 0, false),
            CoverageNodeBatchResult(run, 0, 0, 0))
    }
    @Test fun progressBelongsOnlyToTheActiveRun() = runTest {
        val runner = CoverageStudyRunner(StandardTestDispatcher(testScheduler))
        val inputs = result(UUID.randomUUID()).inputs
        var old: (suspend (CoveragePreparationStage) -> Unit)? = null
        runner.runWithProgress(inputs) { id, _, report ->
            old = report
            report(CoveragePreparationStage.validating)
            assertEquals(CoveragePreparationStage.validating, runner.preparationStage)
            report(CoveragePreparationStage.calculating)
            assertEquals(CoveragePreparationStage.calculating, runner.preparationStage)
            result(id)
        }
        assertNull(runner.preparationStage)
        old?.invoke(CoveragePreparationStage.preparingTerrain)
        assertNull(runner.preparationStage)
        runner.runWithProgress(inputs) { id, _, report ->
            report(CoveragePreparationStage.validating)
            old?.invoke(CoveragePreparationStage.calculating)
            assertEquals(CoveragePreparationStage.validating, runner.preparationStage)
            runner.cancel()
            withContext(NonCancellable) { report(CoveragePreparationStage.preparingTerrain) }
            assertNull(runner.preparationStage)
            result(id)
        }
        assertEquals(CoverageStudyPublicationPhase.cancelled, runner.snapshot.phase)
        assertNull(runner.preparationStage)
        runner.runWithProgress(inputs) { id, _, report ->
            report(CoveragePreparationStage.preparingTerrain)
            runner.invalidate()
            withContext(NonCancellable) { report(CoveragePreparationStage.calculating) }
            assertNull(runner.preparationStage)
            result(id)
        }
        assertEquals(CoverageStudyPublicationPhase.idle, runner.snapshot.phase)
        runner.runWithProgress(inputs) { _, _, report ->
            report(CoveragePreparationStage.calculating)
            throw IllegalArgumentException("model failure")
        }
        assertEquals(CoverageStudyPublicationPhase.failed, runner.snapshot.phase)
        assertNull(runner.preparationStage)
    }

    @Test fun runnerOwnsCancellationAdmissionAndLatePublication() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = CoverageStudyRunner(dispatcher)
        val inputs = result(UUID.randomUUID()).inputs
        assertTrue(runner.run(inputs) { id, captured ->
            assertSame(inputs, captured)
            result(id)
        })
        assertEquals(CoverageStudyPublicationPhase.completed, runner.snapshot.phase)
        assertFalse(runner.isBusy)
        runner.invalidate()
        assertEquals(CoverageStudyPublicationPhase.stale, runner.snapshot.phase)
        assertNotNull(runner.snapshot.result)
        assertTrue(runner.run(inputs) { id, _ ->
            runner.cancel()
            assertTrue(runner.isBusy)
            assertFalse(runner.run(inputs) { next, _ -> result(next) })
            result(id)
        })
        assertEquals(CoverageStudyPublicationPhase.cancelled, runner.snapshot.phase)
        assertNull(runner.snapshot.result)
        assertFalse(runner.isBusy)
        assertTrue(runner.run(inputs) { id, _ -> runner.invalidate(); result(id) })
        assertEquals(CoverageStudyPublicationPhase.idle, runner.snapshot.phase)
        assertNull(runner.failure)
        assertTrue(runner.run(inputs) { _, _ -> result(UUID.randomUUID()) })
        assertEquals(CoverageStudyRunFailure.invalidResult, runner.failure)
        assertTrue(runner.run(inputs) { _, _ -> throw IllegalArgumentException("model failure") })
        assertEquals(CoverageStudyRunFailure.operation, runner.failure)
        assertFalse(runner.isBusy)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun parentCancellationWaitsForWorkerCleanup() = runTest {
        val runner = CoverageStudyRunner(StandardTestDispatcher(testScheduler))
        val inputs = result(UUID.randomUUID()).inputs
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parent = launch {
            runner.run(inputs) { _, _ ->
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { release.await() } }
            }
        }
        entered.await()
        parent.cancel()
        runCurrent()
        assertTrue(runner.isBusy)
        assertFalse(runner.run(inputs) { id, _ -> result(id) })
        release.complete(Unit)
        parent.join()
        assertEquals(CoverageStudyPublicationPhase.cancelled, runner.snapshot.phase)
        assertFalse(runner.isBusy)
        assertNull(runner.snapshot.result)
    }

    @Test fun supersededCallbacksCannotReplaceActiveOrCompletedStudy() {
        val gate = CoverageStudyPublication()
        val old = gate.begin()
        val current = gate.begin()
        assertFalse(gate.publish(result(old)))
        assertFalse(gate.cancel(old))
        assertFalse(gate.fail(old))
        assertEquals(CoverageStudyPublicationPhase.running, gate.snapshot().phase)
        assertNull(gate.snapshot().result)
        assertTrue(gate.publish(result(current)))
        assertFalse(gate.publish(result(current)))
        assertFalse(gate.fail(current))
        assertEquals(current, gate.snapshot().result?.executionID)
        gate.invalidate()
        assertEquals(CoverageStudyPublicationPhase.stale, gate.snapshot().phase)
        assertEquals(current, gate.snapshot().result?.executionID)
        assertFalse(gate.publish(result(current)))
        gate.begin()
        assertNull(gate.snapshot().result)
    }
    @Test fun cancellationInvalidationAndMalformedResultsCannotPublish() {
        val gate = CoverageStudyPublication()
        val cancelled = gate.begin()
        assertTrue(gate.cancel(cancelled))
        assertFalse(gate.publish(result(cancelled)))
        assertEquals(CoverageStudyPublicationPhase.cancelled, gate.snapshot().phase)
        val invalidated = gate.begin()
        gate.invalidate()
        assertFalse(gate.publish(result(invalidated)))
        val current = gate.begin()
        assertFalse(gate.publish(result(current, completed = false)))
        assertFalse(gate.publish(result(current, cells = false)))
        assertTrue(gate.fail(current))
        assertEquals(CoverageStudyPublicationPhase.failed, gate.snapshot().phase)
        assertNull(gate.snapshot().result)
    }
}
