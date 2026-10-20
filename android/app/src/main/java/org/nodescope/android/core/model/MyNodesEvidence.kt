package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MyNodesCompleteness {
    @SerialName("unknown") UNKNOWN,
    @SerialName("complete") COMPLETE,
    @SerialName("partial") PARTIAL,
    @SerialName("unsupported") UNSUPPORTED,
}

/** Per-metric evidence. Times are epoch milliseconds; a fresh fetch does not prove retention. */
@Serializable
data class MyNodesEvidence(
    val completeness: MyNodesCompleteness,
    val fetchedAt: Long,
    val requestedFrom: Long,
    val requestedTo: Long,
    val coveredFrom: Long?,
    val coveredTo: Long?,
) {
    fun permitsHistoryComparison(now: Long, maximumAge: Long = 15 * 60 * 1_000L): Boolean {
        if (completeness != MyNodesCompleteness.COMPLETE || maximumAge < 0 || fetchedAt > now ||
            requestedFrom >= requestedTo || requestedTo > fetchedAt || coveredFrom == null ||
            coveredTo == null || coveredFrom >= coveredTo) return false
        // Avoid overflowing a subtraction for malformed persisted dates.
        val age = runCatching { Math.subtractExact(now, fetchedAt) }.getOrNull() ?: return false
        return age <= maximumAge && coveredFrom <= requestedFrom && coveredTo >= requestedTo
    }

    companion object {
        fun from(context: AnalyticsContext?, fetchedAt: Long, requestedFrom: Long, requestedTo: Long) =
            MyNodesEvidence(when (context?.completeness) {
                AnalyticsCompleteness.COMPLETE -> MyNodesCompleteness.COMPLETE
                AnalyticsCompleteness.PARTIAL -> MyNodesCompleteness.PARTIAL
                AnalyticsCompleteness.UNSUPPORTED -> MyNodesCompleteness.UNSUPPORTED
                null -> MyNodesCompleteness.UNKNOWN
            }, fetchedAt, requestedFrom, requestedTo, context?.coveredFrom, context?.coveredTo)
    }
}
