package org.nodescope.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.protocolJson

class MyNodesComparisonTest {
    @Serializable private data class SignalCase(
        val name: String, val current: MyNodesSignal, val baseline: MyNodesSignal?,
        val trend: Double?, val observers: Int, val significant: Boolean,
        val visit: Double?, val visitObservers: Int, val visitSignificant: Boolean,
    )

    @Serializable private data class ObserverRecord(
        val explicitOnline: Boolean?, val statusAt: Long?, val lastSeen: Long?,
        val fetchedAt: Long, val evaluatedAt: Long, val successfulRecord: Boolean,
    ) {
        fun assessment() = MyNodesObserverStatus.assess(explicitOnline, statusAt, lastSeen,
            fetchedAt, evaluatedAt, successfulRecord)
    }

    @Serializable private data class ObserverCase(
        val name: String, val current: ObserverRecord, val baseline: ObserverRecord?,
        val state: MyNodesObserverStatus.State, val method: MyNodesObserverStatus.Method,
        val eligible: Boolean, val changed: Boolean,
    )

    @Test fun sharedSignalCases() {
        val cases = protocolJson.decodeFromString<List<SignalCase>>(fixture("my-nodes/signal-cases.json"))
        assertEquals(25, cases.size)
        for (entry in cases) {
            check(entry.current.withinDayTrend(), entry.trend, entry.observers, entry.significant, entry.name)
            val encoded = protocolJson.encodeToString(entry.current.summary)
            assertFalse(encoded.contains("\"samples\""))
            val summary = protocolJson.decodeFromString<MyNodesSignal.Summary>(encoded)
            check(entry.baseline?.let { summary.visitChange(it.summary) }, entry.visit,
                entry.visitObservers, entry.visitSignificant, entry.name)
        }
    }

    private fun check(result: MyNodesSignal.Delta?, expected: Double?, observers: Int,
                      significant: Boolean, name: String) {
        assertEquals(name, expected == null, result == null)
        if (result != null && expected != null) {
            assertEquals(name, expected, result.decibels, 0.000001)
            assertEquals(name, observers, result.comparableObservers)
            assertEquals(name, significant, result.isSignificant)
        }
    }

    @Test fun reviewRejectsIncomparableSignalWindowsAndObserverMethods() {
        val namespace = MyNodesNamespace("https://fixture.invalid", "corescope", "local", 1)
        val node = MyNodesEntity.node("a".repeat(64))
        for (entry in protocolJson.decodeFromString<List<SignalCase>>(fixture("my-nodes/signal-cases.json"))) {
            val old = entry.baseline ?: continue
            val metric = MyNodesMetric(signal = entry.current.summary)
            val baseline = MyNodesBaseline("fixed", MyNodesMetric(signal = old.summary))
            val result = metric.review(namespace, node, baseline, false)
            assertEquals(entry.name, if (entry.visit == null) MyNodesReviewResult.State.UNAVAILABLE
                else if (entry.visitSignificant) MyNodesReviewResult.State.CHANGED else MyNodesReviewResult.State.UNCHANGED, result.state)
        }
        val previous = MyNodesMetric(observer = MyNodesObserverStatus.assess(true, 10_000_000, null, 10_000_000, 10_000_000, true))
        val inferred = MyNodesMetric(observer = MyNodesObserverStatus.assess(null, null, 10_010_000, 10_010_000, 10_010_000, true))
        assertEquals(MyNodesReviewResult.State.UNAVAILABLE, inferred.review(namespace, MyNodesEntity.observer("same-observer"),
            MyNodesBaseline("fixed", previous), false).state)
    }

    @Test fun nonfiniteSamplesDoNotMeetMinimum() {
        val signal = protocolJson.decodeFromString<List<SignalCase>>(fixture("my-nodes/signal-cases.json")).first().current
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val samples = signal.samples.drop(1) + signal.samples.first().copy(snr = invalid)
            assertNull(signal.copy(samples = samples).withinDayTrend())
        }
    }

    @Test fun sharedObserverCases() {
        val cases = protocolJson.decodeFromString<List<ObserverCase>>(fixture("my-nodes/observer-cases.json"))
        assertEquals(18, cases.size)
        for (entry in cases) {
            val current = entry.current.assessment()
            assertEquals(entry.name, entry.state, current.state)
            assertEquals(entry.name, entry.method, current.method)
            assertEquals(entry.name, entry.eligible, current.eligible)
            assertEquals(entry.name, entry.changed, entry.baseline?.let { current.changed(it.assessment()) } ?: false)
        }
    }
}
