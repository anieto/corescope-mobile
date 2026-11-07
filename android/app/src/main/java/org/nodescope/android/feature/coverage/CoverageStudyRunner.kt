package org.nodescope.android.feature.coverage

import androidx.annotation.MainThread
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class CoverageStudyRunFailure { operation, invalidResult }

/** Screen-owned admission and publication; invoke methods on Main. Operations own terrain cleanup. */
@MainThread
class CoverageStudyRunner(private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    var snapshot by mutableStateOf(CoverageStudyPublicationSnapshot(CoverageStudyPublicationPhase.idle, null)); private set
    var failure by mutableStateOf<CoverageStudyRunFailure?>(null); private set
    var isBusy by mutableStateOf(false); private set
    var preparationStage by mutableStateOf<CoveragePreparationStage?>(null); private set
    private val publication = CoverageStudyPublication()
    private var worker: Deferred<CoverageStudyResult>? = null
    private var executionID: UUID? = null

    /** Marshal worker progress to the owner context and reject reports from obsolete operations. */
    suspend fun runWithProgress(inputs: CoverageStudyInputs,
                                operation: suspend (UUID, CoverageStudyInputs,
                                    suspend (CoveragePreparationStage) -> Unit) -> CoverageStudyResult): Boolean {
        val ownerContext = currentCoroutineContext().minusKey(Job)
        return run(inputs) { id, captured ->
            operation(id, captured) { stage ->
                withContext(ownerContext) {
                    if (executionID == id && publication.snapshot().phase == CoverageStudyPublicationPhase.running) {
                        preparationStage = stage
                    }
                }
            }
        }
    }

    /** False means a previous operation is still running or releasing its resources. */
    suspend fun run(inputs: CoverageStudyInputs,
                    operation: suspend (UUID, CoverageStudyInputs) -> CoverageStudyResult): Boolean {
        if (isBusy) return false
        isBusy = true
        failure = null
        preparationStage = null
        val id = publication.begin()
        executionID = id
        refresh()
        try {
            // Supervision lets the owner classify an operation failure without cancelling its screen.
            supervisorScope {
                val task = async(workerDispatcher, start = CoroutineStart.LAZY) {
                    ensureActive()
                    val output = operation(id, inputs)
                    ensureActive()
                    output
                }
                worker = task
                task.start()
                try {
                    val output = task.await()
                    if (!publication.publish(output) && publication.fail(id)) failure = CoverageStudyRunFailure.invalidResult
                } catch (cancelled: CancellationException) {
                    publication.cancel(id)
                    coroutineContext.ensureActive()
                } catch (_: Exception) {
                    if (publication.fail(id)) failure = CoverageStudyRunFailure.operation
                }
            }
        } catch (cancelled: CancellationException) {
            publication.cancel(id)
            throw cancelled
        } finally {
            // The structured scope waits for worker finally blocks before reopening admission.
            worker = null
            executionID = null
            isBusy = false
            refresh()
        }
        return true
    }

    fun cancel() {
        executionID?.let { publication.cancel(it) }
        worker?.cancel()
        refresh()
    }
    fun invalidate() {
        publication.invalidate()
        worker?.cancel()
        failure = null
        refresh()
    }
    private fun refresh() {
        snapshot = publication.snapshot()
        if (snapshot.phase != CoverageStudyPublicationPhase.running) preparationStage = null
    }
}
