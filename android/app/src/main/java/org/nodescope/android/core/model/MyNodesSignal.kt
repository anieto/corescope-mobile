package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class MyNodesSignalMethod {
    @SerialName("matchedObservers") MATCHED_OBSERVERS,
    @SerialName("mixedObservations") MIXED_OBSERVATIONS,
}

/** Pure SNR policy. The caller supplies source-scoped samples and history evidence. */
@Serializable
data class MyNodesSignal(
    val method: MyNodesSignalMethod,
    val evidence: MyNodesEvidence,
    val evaluatedAt: Long,
    val samples: List<Sample>,
) {
    @Serializable data class Sample(val timestamp: Long, val snr: Double, val observerID: String?)
    data class Delta(val decibels: Double, val comparableObservers: Int, val permitsSignificantEvent: Boolean) {
        val isSignificant: Boolean get() = permitsSignificantEvent && abs(decibels) >= 5
    }

    private val eligible: Boolean get() = evidence.permitsHistoryComparison(evaluatedAt) &&
        difference(evidence.requestedTo, evidence.requestedFrom) == DAY
    private val validSamples: List<Sample> get() = samples.filter {
        it.snr.isFinite() && it.timestamp >= evidence.requestedFrom && it.timestamp < evidence.requestedTo
    }

    fun withinDayTrend(): Delta? {
        if (!eligible) return null
        val midpoint = evidence.requestedFrom + DAY / 2
        return delta(validSamples.filter { it.timestamp < midpoint },
            validSamples.filter { it.timestamp >= midpoint }, method)
    }

    @Serializable data class ObserverSummary(val median: Double, val sampleCount: Int)

    /** Compact persistence payload, without individual sample history. */
    @Serializable data class Summary(
        val method: MyNodesSignalMethod, val evidence: MyNodesEvidence,
        val evaluatedAt: Long, val observers: Map<String, ObserverSummary>,
    ) {
        val eligible: Boolean get() = method == MyNodesSignalMethod.MATCHED_OBSERVERS &&
            evidence.permitsHistoryComparison(evaluatedAt) &&
            difference(evidence.requestedTo, evidence.requestedFrom) == DAY &&
            observers.isNotEmpty() && observers.all { (key, value) ->
                key.isNotBlank() && value.sampleCount >= 5 && value.median.isFinite()
            }

        fun visitChange(baseline: Summary): Delta? {
            val elapsed = difference(evaluatedAt, baseline.evaluatedAt) ?: return null
            if (!eligible || !baseline.eligible || elapsed !in DAY..7 * DAY ||
                baseline.evidence.requestedTo > evidence.requestedFrom) return null
            val deltas = baseline.observers.mapNotNull { (key, value) ->
                observers[key]?.median?.minus(value.median)?.takeIf { it.isFinite() }
            }
            return median(deltas)?.let { Delta(it, deltas.size, true) }
        }
    }

    val summary: Summary get() {
        val observers = validSamples.filter { !it.observerID.isNullOrBlank() }
            .groupBy { it.observerID.orEmpty() }.mapNotNull { (key, values) ->
                if (values.size < 5) null else median(values.map { it.snr })?.let {
                    key to ObserverSummary(it, values.size)
                }
            }.toMap()
        return Summary(method, evidence, evaluatedAt, observers)
    }

    fun visitChange(baseline: MyNodesSignal): Delta? = summary.visitChange(baseline.summary)

    companion object {
        private const val DAY = 86_400_000L
        private fun difference(later: Long, earlier: Long): Long? =
            runCatching { Math.subtractExact(later, earlier) }.getOrNull()

        private fun delta(older: List<Sample>, newer: List<Sample>, method: MyNodesSignalMethod): Delta? {
            if (method == MyNodesSignalMethod.MIXED_OBSERVATIONS) {
                if (older.size < 5 || newer.size < 5) return null
                val before = median(older.map { it.snr }) ?: return null
                val after = median(newer.map { it.snr }) ?: return null
                return (after - before).takeIf { it.isFinite() }?.let { Delta(it, 0, false) }
            }
            val before = observerMedians(older)
            val after = observerMedians(newer)
            val deltas = before.mapNotNull { (key, value) ->
                after[key]?.minus(value)?.takeIf { it.isFinite() }
            }
            return median(deltas)?.let { Delta(it, deltas.size, true) }
        }

        private fun observerMedians(samples: List<Sample>): Map<String, Double> =
            samples.filter { !it.observerID.isNullOrBlank() }.groupBy { it.observerID.orEmpty() }
                .mapNotNull { (key, values) ->
                    if (values.size < 5) null else median(values.map { it.snr })?.let { key to it }
                }.toMap()

        private fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) sorted[middle - 1] / 2 + sorted[middle] / 2 else sorted[middle]
        }
    }
}
