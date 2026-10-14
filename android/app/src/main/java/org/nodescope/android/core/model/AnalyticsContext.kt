package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Port of iOS AnalyticsContext.swift. Times are epoch milliseconds.

@Serializable
enum class AnalyticsCountUnit { @SerialName("packets") PACKETS, @SerialName("observations") OBSERVATIONS }

@Serializable
enum class AnalyticsCompleteness { @SerialName("complete") COMPLETE, @SerialName("partial") PARTIAL, @SerialName("unsupported") UNSUPPORTED }

@Serializable
enum class AnalyticsProvenance { @SerialName("server") SERVER, @SerialName("derived") DERIVED }

/** Describes what an analytics payload can honestly claim without changing its visual model. */
@Serializable
data class AnalyticsContext(
    val completeness: AnalyticsCompleteness,
    val countUnit: AnalyticsCountUnit,
    val provenance: AnalyticsProvenance,
    val requestedFrom: Long,
    val requestedTo: Long,
    val coveredFrom: Long? = null,
    val coveredTo: Long? = null,
    val explanation: String? = null,
) {
    val hasKnownCoverage: Boolean get() = coveredFrom != null && coveredTo != null

    companion object {
        private const val DAY_MILLIS = 24 * 60 * 60 * 1_000L

        fun beaconObservations(
            requestedDays: Int, coveredFrom: Long, coveredTo: Long,
            isComplete: Boolean = true, explanation: String? = null,
        ): AnalyticsContext {
            val safeDays = maxOf(1, requestedDays)
            val capped = safeDays > 7
            return AnalyticsContext(
                completeness = if (isComplete && !capped) AnalyticsCompleteness.COMPLETE else AnalyticsCompleteness.PARTIAL,
                countUnit = AnalyticsCountUnit.OBSERVATIONS,
                provenance = AnalyticsProvenance.DERIVED,
                requestedFrom = coveredTo - safeDays * DAY_MILLIS,
                requestedTo = coveredTo,
                coveredFrom = coveredFrom,
                coveredTo = coveredTo,
                explanation = explanation ?: if (capped) "Beacon retains up to seven days of observation history." else null,
            )
        }

        fun unsupported(countUnit: AnalyticsCountUnit, requestedFrom: Long, requestedTo: Long, explanation: String) =
            AnalyticsContext(AnalyticsCompleteness.UNSUPPORTED, countUnit, AnalyticsProvenance.SERVER,
                requestedFrom, requestedTo, null, null, explanation)
    }
}
