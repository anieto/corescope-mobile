package org.nodescope.android.core.network

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt
import org.nodescope.android.core.model.*

/**
 * Port of iOS BeaconNodeAnalyticsBuilder: node analytics derived from Beacon observations, with
 * counts in observations (not packets). Observer coverage, peers, availability, longest silence
 * and signal grade need observer or packet identity that node history lacks, so they stay unknown.
 */
object BeaconNodeAnalyticsBuilder {
    private const val HOUR_MILLIS = 3_600_000L
    private const val DAY_MILLIS = 24 * HOUR_MILLIS

    /** Beacon keeps individual observations for seven days; `/nodes/{id}/activity` serves at most 30 days of hourly totals. */
    const val OBSERVATION_DAYS = 7
    const val ACTIVITY_DAYS = BeaconNodeActivity.MAXIMUM_HOURS / 24

    /**
     * With [activity] (Beacon 2.2.0+), activity, signal, the weekly pattern and the totals are complete
     * hourly counts over up to 30 days; packet types and hops still come from individual observations.
     */
    fun build(
        history: BeaconObservationHistory, activity: BeaconNodeActivity?,
        requestedDays: Int, requestedFrom: Long, requestedTo: Long,
    ): NodeAnalytics {
        if (activity == null) return build(history, requestedDays, requestedFrom, requestedTo)
        val safeDays = maxOf(1, requestedDays)
        val observationFrom = maxOf(requestedFrom, requestedTo - OBSERVATION_DAYS * DAY_MILLIS)
        val observations = history.observations.filter { it.heardAt in observationFrom..requestedTo }
        val samplesCoverWindow = safeDays <= OBSERVATION_DAYS && history.isComplete
        val explanation = when {
            safeDays > ACTIVITY_DAYS -> "Beacon provides up to 30 days of hourly activity. Packet types and hops cover the last seven days."
            safeDays > OBSERVATION_DAYS -> "Packet types and hops cover the last seven days, the individual observations Beacon keeps."
            !history.isComplete -> "Packet types and hops use the observations NodeScope downloaded before reaching its request limit."
            else -> null
        }
        val context = AnalyticsContext(
            completeness = if (explanation == null) AnalyticsCompleteness.COMPLETE else AnalyticsCompleteness.PARTIAL,
            countUnit = AnalyticsCountUnit.OBSERVATIONS,
            provenance = AnalyticsProvenance.DERIVED,
            requestedFrom = requestedTo - safeDays * DAY_MILLIS,
            requestedTo = requestedTo,
            coveredFrom = activity.windowStart,
            coveredTo = requestedTo,
            explanation = explanation,
        )
        val signal = activity.points.mapNotNull { p -> p.snrAvg?.let { SignalPoint(iso(p.t), it, p.rssiAvg, null, null) } }
        val sampledSnr = observations.mapNotNull { it.snr }
        val sampledMean = sampledSnr.takeIf { it.isNotEmpty() }?.average()
        val hops = observations.mapNotNull { it.hopCount }
        val windowDays = maxOf((requestedTo - activity.windowStart).toDouble() / DAY_MILLIS, 1.0 / 24.0)
        val hourly = activity.points.groupingBy { it.t }.fold(0) { total, point -> total + point.observations }

        return NodeAnalytics(
            node = history.node.meshNode(),
            timeRange = AnalyticsTimeRange(iso(activity.windowStart), iso(requestedTo), minOf(safeDays, ACTIVITY_DAYS)),
            context = context,
            activityTimeline = hourlyPoints(hourly, activity.windowStart, requestedTo),
            snrTrend = signal,
            packetTypeBreakdown = observations.groupingBy { it.payloadType }.eachCount()
                .map { (type, count) -> PacketTypeCount(type, count.toLong()) }.sortedBy { it.payloadType },
            observerCoverage = emptyList(),
            hopDistribution = hops.groupingBy { it }.eachCount().toSortedMap()
                .map { (hop, count) -> HopCount(hop.toString(), count.toLong()) },
            peerInteractions = emptyList(),
            uptimeHeatmap = heatmap(activity.points.map { it.t to it.observations }),
            computedStats = ComputedStats(
                availabilityPct = null, longestSilenceMs = null, longestSilenceStart = null, signalGrade = null,
                snrMean = activity.summary.snrAvg,
                // A spread needs individual readings; hourly averages would understate it.
                snrStdDev = sampledMean?.takeIf { samplesCoverWindow }
                    ?.let { m -> sqrt(sampledSnr.sumOf { (it - m) * (it - m) } / sampledSnr.size) },
                relayPct = hops.takeIf { it.isNotEmpty() }?.let { values -> values.count { it > 0 }.toDouble() / values.size * 100 },
                totalPackets = activity.summary.observations.toLong(),
                uniqueObservers = null, uniquePeers = null,
                avgPacketsPerDay = activity.summary.observations / windowDays,
            ),
        )
    }

    fun build(history: BeaconObservationHistory, requestedDays: Int, requestedFrom: Long, requestedTo: Long): NodeAnalytics {
        val retainedFrom = maxOf(requestedFrom, requestedTo - 7 * DAY_MILLIS)
        val observations = history.observations.filter { it.heardAt in retainedFrom..requestedTo }
        val coveredFrom = if (history.isComplete) retainedFrom else observations.lastOrNull()?.heardAt ?: retainedFrom
        val context = AnalyticsContext.beaconObservations(
            requestedDays = requestedDays, coveredFrom = coveredFrom, coveredTo = requestedTo,
            isComplete = history.isComplete,
            explanation = if (history.isComplete) null else "NodeScope reached its request limit before Beacon history was exhausted.",
        )
        val signal = observations.mapNotNull { o -> o.snr?.let { SignalPoint(iso(o.heardAt), it, o.rssi, null, null) } }
        val snr = signal.mapNotNull { it.snr }
        val mean = snr.takeIf { it.isNotEmpty() }?.average()
        val hops = observations.mapNotNull { it.hopCount }
        val coveredDays = maxOf((requestedTo - coveredFrom).toDouble() / DAY_MILLIS, 1.0 / 24.0)

        return NodeAnalytics(
            node = history.node.meshNode(),
            timeRange = AnalyticsTimeRange(iso(retainedFrom), iso(requestedTo), minOf(maxOf(1, requestedDays), 7)),
            context = context,
            activityTimeline = activity(observations, retainedFrom, requestedTo),
            snrTrend = signal,
            packetTypeBreakdown = observations.groupingBy { it.payloadType }.eachCount()
                .map { (type, count) -> PacketTypeCount(type, count.toLong()) }.sortedBy { it.payloadType },
            observerCoverage = emptyList(),
            hopDistribution = hops.groupingBy { it }.eachCount().toSortedMap()
                .map { (hop, count) -> HopCount(hop.toString(), count.toLong()) },
            peerInteractions = emptyList(),
            uptimeHeatmap = heatmap(observations.map { it.heardAt to 1 }),
            computedStats = ComputedStats(
                availabilityPct = null, longestSilenceMs = null, longestSilenceStart = null, signalGrade = null,
                snrMean = mean,
                snrStdDev = mean?.let { m -> sqrt(snr.sumOf { (it - m) * (it - m) } / snr.size) },
                relayPct = hops.takeIf { it.isNotEmpty() }?.let { values -> values.count { it > 0 }.toDouble() / values.size * 100 },
                totalPackets = observations.size.toLong(),
                uniqueObservers = null, uniquePeers = null,
                avgPacketsPerDay = observations.size / coveredDays,
            ),
        )
    }

    /** Hourly UTC buckets across the whole retained window, zeros included. */
    private fun activity(observations: List<BeaconObservation>, from: Long, to: Long): List<ActivityPoint> =
        hourlyPoints(observations.groupingBy { hourStart(it.heardAt) }.eachCount(), from, to)

    private fun hourlyPoints(counts: Map<Long, Int>, from: Long, to: Long): List<ActivityPoint> {
        val points = mutableListOf<ActivityPoint>()
        var bucket = hourStart(from)
        while (bucket <= to) {
            points += ActivityPoint(iso(bucket), (counts[bucket] ?: 0).toLong())
            bucket += HOUR_MILLIS
        }
        return points
    }

    /** `dayOfWeek` 0 = Sunday, in UTC, matching CoreScope's heatmap. Tallies are (time, count): single observations or hourly totals. */
    private fun heatmap(tallies: List<Pair<Long, Int>>): List<UptimeCell> =
        tallies.filter { it.second > 0 }.groupingBy { (millis, _) ->
            val time = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
            (time.dayOfWeek.value % 7) to time.hour
        }.fold(0) { total, tally -> total + tally.second }
            .map { (key, count) -> UptimeCell(key.first, key.second, count.toLong()) }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.hour }))

    private fun hourStart(millis: Long) = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.HOURS).toEpochMilli()
    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()
}
