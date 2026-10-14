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
            uptimeHeatmap = heatmap(observations),
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
    private fun activity(observations: List<BeaconObservation>, from: Long, to: Long): List<ActivityPoint> {
        val counts = observations.groupingBy { hourStart(it.heardAt) }.eachCount()
        val points = mutableListOf<ActivityPoint>()
        var bucket = hourStart(from)
        while (bucket <= to) {
            points += ActivityPoint(iso(bucket), (counts[bucket] ?: 0).toLong())
            bucket += HOUR_MILLIS
        }
        return points
    }

    /** `dayOfWeek` 0 = Sunday, in UTC, matching CoreScope's heatmap. */
    private fun heatmap(observations: List<BeaconObservation>): List<UptimeCell> =
        observations.groupingBy { o ->
            val time = Instant.ofEpochMilli(o.heardAt).atZone(ZoneOffset.UTC)
            (time.dayOfWeek.value % 7) to time.hour
        }.eachCount().map { (key, count) -> UptimeCell(key.first, key.second, count.toLong()) }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.hour }))

    private fun hourStart(millis: Long) = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.HOURS).toEpochMilli()
    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()
}
