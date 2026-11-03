package org.nodescope.android.feature.coverage

import java.util.UUID

enum class CoverageStudyPublicationPhase { idle, running, completed, cancelled, failed, stale }
data class CoverageStudyPublicationSnapshot(val phase: CoverageStudyPublicationPhase, val result: CoverageStudyResult?)
/** Synchronized state transitions; UI reads phase/result together. Does not schedule or stop workers. */
class CoverageStudyPublication {
    private var phase = CoverageStudyPublicationPhase.idle
    private var result: CoverageStudyResult? = null
    private var activeID: UUID? = null
    @Synchronized fun snapshot() = CoverageStudyPublicationSnapshot(phase, result)
    @Synchronized fun begin(): UUID {
        val id = UUID.randomUUID()
        activeID = id
        result = null
        phase = CoverageStudyPublicationPhase.running
        return id
    }
    @Synchronized fun publish(result: CoverageStudyResult): Boolean {
        if (phase != CoverageStudyPublicationPhase.running || activeID != result.executionID ||
            result.nodes.run.phase != CoverageNodeRunPhase.completed || result.area.cells.size != result.grid.count) return false
        this.result = result
        activeID = null
        phase = CoverageStudyPublicationPhase.completed
        return true
    }
    @Synchronized fun cancel(id: UUID) = finish(id, CoverageStudyPublicationPhase.cancelled)
    @Synchronized fun fail(id: UUID) = finish(id, CoverageStudyPublicationPhase.failed)
    @Synchronized fun invalidate() {
        activeID = null
        phase = if (result == null) CoverageStudyPublicationPhase.idle else CoverageStudyPublicationPhase.stale
    }
    private fun finish(id: UUID, next: CoverageStudyPublicationPhase): Boolean {
        if (phase != CoverageStudyPublicationPhase.running || activeID != id) return false
        activeID = null
        result = null
        phase = next
        return true
    }
}
