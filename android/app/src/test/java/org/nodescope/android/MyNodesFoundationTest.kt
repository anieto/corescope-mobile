package org.nodescope.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*

class MyNodesFoundationTest {
    @Serializable private data class EvidenceCase(
        val name: String, val completeness: AnalyticsCompleteness?, val fetchedAt: Long,
        val requestedFrom: Long, val requestedTo: Long, val coveredFrom: Long?,
        val coveredTo: Long?, val now: Long, val expected: Boolean,
    )

    @Test fun sharedEvidenceCases() {
        val cases = protocolJson.decodeFromString<List<EvidenceCase>>(fixture("my-nodes/evidence-cases.json"))
        for (entry in cases) {
            val context = entry.completeness?.let {
                AnalyticsContext(it, AnalyticsCountUnit.PACKETS, AnalyticsProvenance.SERVER,
                    entry.requestedFrom, entry.requestedTo, entry.coveredFrom, entry.coveredTo)
            }
            val evidence = MyNodesEvidence.from(context, entry.fetchedAt, entry.requestedFrom, entry.requestedTo)
            assertEquals(entry.name, entry.expected, evidence.permitsHistoryComparison(entry.now))
            if (context == null) assertEquals(MyNodesCompleteness.UNKNOWN, evidence.completeness)
        }
    }

    @Test fun favoriteCountsStayWithinBatch() = runTest {
        for (size in listOf(0, 1, 20, 100, 500)) {
            val budget = MyNodesRequestBudget()
            var attempts = 0
            for (index in 0 until size) {
                try { budget.perform("node-$index") { true }; attempts++ }
                catch (error: MyNodesBudgetException) {
                    assertEquals(MyNodesBudgetFailure.EXHAUSTED, error.reason)
                    break
                }
            }
            assertEquals(minOf(size, 20), attempts)
        }
    }

    @Test fun failedAttemptsConsumeNodeAllowance() = runTest {
        val budget = MyNodesRequestBudget(maximumRequests = 5, maximumPerNode = 2)
        repeat(2) {
            try { budget.perform("ABC") { throw java.io.IOException() }; fail("Expected transport failure") }
            catch (_: java.io.IOException) { }
        }
        expectBudget(MyNodesBudgetFailure.NODE_EXHAUSTED) { budget.perform("abc") { true } }
        assertTrue(budget.perform("other") { true })
    }

    @Test fun concurrentGateAndInvalidationRejectLateResults() = runTest {
        val budget = MyNodesRequestBudget(maximumConcurrent = 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        supervisorScope {
            val first = async {
                budget.perform { started.complete(Unit); release.await(); true }
            }
            started.await()
            expectBudget(MyNodesBudgetFailure.BUSY) { budget.perform { true } }
            budget.invalidate()
            release.complete(Unit)
            expectBudget(MyNodesBudgetFailure.INVALIDATED) { first.await() }
        }
        expectBudget(MyNodesBudgetFailure.INVALIDATED) { budget.perform { true } }
    }

    @Test fun cancellationRejectsResultAndReleasesSlot() = runTest {
        val budget = MyNodesRequestBudget(maximumRequests = 2, maximumConcurrent = 1)
        val started = CompletableDeferred<Unit>()
        val first = launch { budget.perform { started.complete(Unit); awaitCancellation() } }
        started.await()
        first.cancelAndJoin()
        assertTrue(budget.perform { true })
        expectBudget(MyNodesBudgetFailure.EXHAUSTED) { budget.perform { true } }
    }

    private suspend fun expectBudget(reason: MyNodesBudgetFailure, block: suspend () -> Any?) {
        try { block(); fail("Expected $reason") }
        catch (error: MyNodesBudgetException) { assertEquals(reason, error.reason) }
    }
}
