package org.nodescope.android.feature.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.model.NodeAnalytics
import org.nodescope.android.core.model.SignalPoint
import org.nodescope.android.core.model.UptimeCell
import org.nodescope.android.core.model.parseInstant
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.network.HttpFailure
import org.nodescope.android.core.network.KeyedLoader
import org.nodescope.android.core.network.ResponseCache

enum class AnalyticsRange(val days: Int, val title: String) { DAY(1, "24h"), WEEK(7, "7d"), MONTH(30, "30d"), YEAR(365, "1y") }

sealed interface AnalyticsResult {
    data class Available(val analytics: NodeAnalytics) : AnalyticsResult
    /** HTTP 404: this analyzer does not provide node analytics (iOS shows the same state). */
    data object Unsupported : AnalyticsResult
}

data class AnalyticsKey(val host: String, val publicKey: String, val days: Int)

class NodeAnalyticsViewModel(repository: BrowseRepository) : ViewModel() {
    /** Keyed by range too, so a slow response for the previous range can never replace the current one. */
    val analytics = KeyedLoader(viewModelScope, 5 * 60_000, restore = { key: AnalyticsKey ->
        repository.saved(ResponseCache.DETAIL_LIFETIME) { AnalyticsResult.Available(nodeAnalytics(key.host, key.publicKey, key.days)) }
    }) { key ->
        try {
            AnalyticsResult.Available(repository.nodeAnalytics(key.host, key.publicKey, key.days))
        } catch (e: HttpFailure) {
            if (e.status == 404) AnalyticsResult.Unsupported else throw e
        }
    }
    /** Radio settings per observer, for each observer's decode limit. Optional: numbers still show without it. */
    val observers = KeyedLoader(viewModelScope, 10 * 60_000, restore = { host: String -> repository.saved(ResponseCache.STALE_LIFETIME) { observers(host) } }) { host -> repository.observers(host) }
}

/** 7 × 24 counts (row 0 = Sunday, UTC hours), summing duplicate cells. */
fun heatmapGrid(cells: List<UptimeCell>): Array<LongArray> {
    val grid = Array(7) { LongArray(24) }
    cells.filter { it.dayOfWeek in 0..6 && it.hour in 0..23 }.forEach { grid[it.dayOfWeek][it.hour] += it.count }
    return grid
}

/** Same thresholds as iOS: seconds, minutes, then one-decimal hours or days. */
fun formatSilence(milliseconds: Double): String {
    val seconds = maxOf(0.0, milliseconds / 1_000)
    return when {
        seconds >= 86_400 -> String.format(Locale.US, "%.1fd", seconds / 86_400)
        seconds >= 3_600 -> String.format(Locale.US, "%.1fh", seconds / 3_600)
        seconds >= 60 -> String.format(Locale.US, "%.0fm", seconds / 60)
        else -> String.format(Locale.US, "%.0fs", seconds)
    }
}

/**
 * Like Swift Charts' automatic scale on iOS, a time axis spans the data itself rather than
 * the whole requested range. A single timestamp gets an hour either side.
 */
fun dataDomain(times: List<Long>): Pair<Long, Long>? {
    val first = times.minOrNull() ?: return null
    val last = times.max()
    return if (last > first) first to last else (first - 3_600_000) to (last + 3_600_000)
}

/** Label granularity follows the span actually shown. */
fun analyticsTimeLabel(epochMillis: Long, spanMillis: Long): String = DateTimeFormatter.ofPattern(when {
    spanMillis <= 36 * 3_600_000L -> "MMM d, HH:mm"
    spanMillis <= 90 * 86_400_000L -> "MMM d"
    else -> "MMM yyyy"
}).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))

private fun percentile(sorted: List<Double>, fraction: Double): Double {
    if (sorted.size == 1) return sorted[0]
    val position = fraction * (sorted.size - 1)
    val lower = sorted[position.toInt()]
    val upper = sorted[minOf(position.toInt() + 1, sorted.lastIndex)]
    return lower + (upper - lower) * (position - position.toInt())
}

/** Observer `radio` is "frequency,bandwidth,spreadingFactor,codingRate", e.g. "910.525,62.5,7,5". */
fun spreadingFactor(radio: String?): Int? = radio?.split(',')?.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 5..12 }

/** Semtech SX126x demodulation SNR limit: −7.5 dB at SF7, 2.5 dB lower per SF step. */
fun snrFloor(spreadingFactor: Int): Double = -7.5 - 2.5 * (spreadingFactor - 7)

/** Plain-language quality from how far the SNR sits above the decode limit. */
enum class SignalQuality(val label: String) { STRONG("Strong"), GOOD("Good"), WEAK("Weak"), NEAR_LIMIT("Near limit") }

fun signalQuality(margin: Double): SignalQuality = when {
    margin >= 15 -> SignalQuality.STRONG
    margin >= 10 -> SignalQuality.GOOD
    margin >= 5 -> SignalQuality.WEAK
    else -> SignalQuality.NEAR_LIMIT
}

/** Everything an advanced reader may want per observer; [floor] is null when its SF is unknown. */
data class ObserverSignal(
    val observerId: String?, val observer: String, val count: Int,
    val median: Double, val low: Double, val high: Double, val min: Double, val max: Double,
    val medianRssi: Double?, val lastHeard: Long?, val floor: Double?, val spreadingFactor: Int?,
) {
    val margin: Double? get() = floor?.let { median - it }
    val quality: SignalQuality? get() = margin?.let(::signalQuality)
    /** A handful of readings is not enough to judge a link. */
    val fewReadings: Boolean get() = count < 5
}

/**
 * SNR per observer, best first. Each observer's decode limit comes from its own reported
 * spreading factor, falling back to the most common one on the analyzer; with neither,
 * observers keep their numbers but get no quality label.
 */
fun observerSignals(points: List<SignalPoint>, observers: List<MeshObserver>): List<ObserverSignal> {
    val byId = observers.associateBy { it.id.lowercase() }
    val networkSf = observers.mapNotNull { spreadingFactor(it.radio) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    return points.groupBy { it.observerId?.lowercase() ?: it.observerName ?: "unknown" }.mapNotNull { (_, samples) ->
        val snr = samples.mapNotNull { it.snr }.sorted()
        if (snr.isEmpty()) return@mapNotNull null
        val id = samples.firstNotNullOfOrNull { it.observerId }
        val known = id?.let { byId[it.lowercase()] } ?: samples.firstNotNullOfOrNull { it.observerName }?.let { name -> observers.firstOrNull { it.name == name } }
        val sf = spreadingFactor(known?.radio) ?: networkSf
        val rssi = samples.mapNotNull { it.rssi }.sorted()
        ObserverSignal(id, samples.firstNotNullOfOrNull { it.observerName?.takeIf(String::isNotBlank) } ?: known?.displayName ?: id?.take(12) ?: "Unknown",
            snr.size, percentile(snr, 0.5), percentile(snr, 0.1), percentile(snr, 0.9), snr.first(), snr.last(),
            rssi.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) },
            samples.mapNotNull { parseInstant(it.timestamp)?.toEpochMilli() }.maxOrNull(), sf?.let(::snrFloor), sf)
    }.sortedWith(observerRanking)
}

/** Best median first, then more readings, then name, so the order never depends on grouping order (matches iOS). */
val observerRanking: Comparator<ObserverSignal> = compareByDescending<ObserverSignal> { it.median }
    .thenByDescending { it.count }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.observer }.thenBy { it.observerId.orEmpty() }

/** Individual SNR readings for one observer, oldest first, as (epoch millis, dB). */
fun observerReadings(points: List<SignalPoint>, signal: ObserverSignal): List<Pair<Long, Double>> =
    points.filter { point -> signal.observerId?.let { point.observerId.equals(it, ignoreCase = true) } ?: (point.observerName == signal.observer) }
        .mapNotNull { point -> point.snr?.let { snr -> parseInstant(point.timestamp)?.let { it.toEpochMilli() to snr } } }
        .sortedBy { it.first }
