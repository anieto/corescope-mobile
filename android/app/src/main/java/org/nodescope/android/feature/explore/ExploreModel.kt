package org.nodescope.android.feature.explore

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.network.KeyedLoader
import org.nodescope.android.core.network.ResponseCache
import org.nodescope.android.core.storage.SavedItem
import org.nodescope.android.core.storage.SavedKind

/** Explore covers the entire network (no region), like iOS, so it loads its own data. */
class ExploreViewModel(repository: BrowseRepository) : ViewModel() {
    val nodes = KeyedLoader(viewModelScope, 60_000, restore = { host: String -> repository.saved(ResponseCache.STALE_LIFETIME) { allNodes(host) } }) { host -> repository.allNodes(host) }
    val observers = KeyedLoader(viewModelScope, 60_000, restore = { host: String -> repository.saved(ResponseCache.STALE_LIFETIME) { observers(host) } }) { host -> repository.observers(host) }
    val channels = KeyedLoader(viewModelScope, 90_000) { host: String -> repository.channels(AnalyzerSelection(host, null)) }
    val packets = KeyedLoader(viewModelScope, 60_000, restore = { host: String -> repository.saved(ResponseCache.STALE_LIFETIME) { recentPackets(host) } }) { host -> repository.recentPackets(host) }

    fun load(host: String, force: Boolean = false) {
        nodes.load(host, force); observers.load(host, force); channels.load(host, force); packets.load(host, force)
    }
}

const val ACTIVE_WINDOW = 15 * 60_000L
const val PACKET_WINDOW = 60 * 60_000L
const val PACKET_SAMPLE = 1_000

enum class GlanceMetric(val title: String) { ACTIVE_NODES("Active nodes"), OBSERVERS_ONLINE("Observers online"), PACKETS("Packets"), AVERAGE_SNR("Average SNR") }

data class GlanceValue(val value: String, val detail: String)

/** The iOS dashboard definitions: active within 15 minutes; packets and SNR over the last hour. */
fun glanceValue(metric: GlanceMetric, nodes: List<MeshNode>, observers: List<MeshObserver>, packets: List<LivePacket>, now: Long): GlanceValue {
    fun active(at: String?) = parseInstant(at)?.let { now - it.toEpochMilli() < ACTIVE_WINDOW } == true
    val recent = packets.filter { now - packetEpoch(it) < PACKET_WINDOW }
    return when (metric) {
        GlanceMetric.ACTIVE_NODES -> GlanceValue(nodes.count { active(it.lastSeen) }.toString(), "of ${nodes.size} seen")
        GlanceMetric.OBSERVERS_ONLINE -> GlanceValue(observers.count { active(it.lastSeen) }.toString(), "of ${observers.size} known")
        // The sample is the analyzer's latest 1,000 packets; saying "1000+" avoids undercounting.
        GlanceMetric.PACKETS -> GlanceValue(if (recent.size == packets.size && packets.size >= PACKET_SAMPLE) "${recent.size}+" else recent.size.toString(), "in the last hour")
        GlanceMetric.AVERAGE_SNR -> recent.mapNotNull { it.snr }.takeIf { it.isNotEmpty() }?.average()
            ?.let { GlanceValue(String.format(Locale.US, "%.1f", it), "dB in the last hour") } ?: GlanceValue("—", "no recent samples")
    }
}

/** Favorites whose node or observer was seen within the active window (channels are never "active"). */
fun activeFavoriteCount(favorites: List<SavedItem>, nodes: List<MeshNode>, observers: List<MeshObserver>, now: Long): Int {
    fun active(at: String?) = parseInstant(at)?.let { now - it.toEpochMilli() < ACTIVE_WINDOW } == true
    val nodeKeys = nodes.filter { active(it.lastSeen) }.map { it.publicKey.lowercase() }.toSet()
    val observerIds = observers.filter { active(it.lastSeen) }.map { it.id.lowercase() }.toSet()
    return favorites.count { item ->
        when (item.kind) {
            SavedKind.NODE -> item.entityId.lowercase() in nodeKeys
            SavedKind.OBSERVER -> item.entityId.lowercase() in observerIds
            else -> false
        }
    }
}

fun favoriteBreakdown(favorites: List<SavedItem>): String {
    fun count(kind: SavedKind, one: String, many: String) = favorites.count { it.kind == kind }.let { "$it ${if (it == 1) one else many}" }
    return listOf(count(SavedKind.CHANNEL, "channel", "channels"), count(SavedKind.NODE, "node", "nodes"),
        count(SavedKind.OBSERVER, "observer", "observers")).joinToString(" · ")
}

// region Dashboard preferences (per analyzer, like iOS GlancePreferences)

@Serializable
data class GlancePreferences(val order: List<GlanceMetric> = GlanceMetric.entries, val hidden: Set<GlanceMetric> = emptySet()) {
    /** Unknown or missing metrics are appended so new metrics appear after an update. */
    fun normalized() = copy(order = order.distinct() + GlanceMetric.entries.filterNot { it in order })
    val visible: List<GlanceMetric> get() = normalized().order.filterNot { it in hidden }
}

private val prefsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun loadGlancePreferences(context: Context, host: String): GlancePreferences =
    context.getSharedPreferences("glance-preferences", Context.MODE_PRIVATE).getString(host.lowercase(), null)
        ?.let { runCatching { prefsJson.decodeFromString<GlancePreferences>(it) }.getOrNull() }?.normalized() ?: GlancePreferences()

fun saveGlancePreferences(context: Context, host: String, preferences: GlancePreferences) {
    context.getSharedPreferences("glance-preferences", Context.MODE_PRIVATE).edit { putString(host.lowercase(), prefsJson.encodeToString(preferences)) }
}

// endregion
// region Export (same fields as iOS NetworkGlanceExport)

private val metricIds = mapOf(GlanceMetric.ACTIVE_NODES to "activeNodes", GlanceMetric.OBSERVERS_ONLINE to "observersOnline",
    GlanceMetric.PACKETS to "packets", GlanceMetric.AVERAGE_SNR to "averageSNR")

private fun csvField(value: String) = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

fun glanceCsv(metrics: List<Pair<GlanceMetric, GlanceValue>>, activeFavorites: Int, breakdown: String): String =
    (listOf(listOf("metric", "value", "detail")) + metrics.map { (metric, value) ->
        listOf(metricIds.getValue(metric), value.value.takeUnless { it == "—" }.orEmpty(), value.detail)
    } + listOf(listOf("favorites", activeFavorites.toString(), breakdown))).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::csvField) }

@Serializable private data class MetricExport(val id: String, val value: String, val detail: String)
@Serializable private data class GlanceExport(val analyzer: String, val exportedAt: String, val scope: String, val metrics: List<MetricExport>,
    val activeFavorites: Int, val favoriteCount: Int)

fun glanceJson(host: String, now: Long, metrics: List<Pair<GlanceMetric, GlanceValue>>, activeFavorites: Int, favoriteCount: Int): String =
    Json { prettyPrint = true }.encodeToString(GlanceExport(host, Instant.ofEpochMilli(now).toString(), "entire_network",
        metrics.map { (metric, value) -> MetricExport(metricIds.getValue(metric), value.value.takeUnless { it == "—" }.orEmpty(), value.detail) },
        activeFavorites, favoriteCount))

// endregion
// region Global search

data class SearchResults(val channels: List<MeshChannel>, val nodes: List<MeshNode>, val observers: List<MeshObserver>, val packets: List<LivePacket>) {
    val isEmpty: Boolean get() = channels.isEmpty() && nodes.isEmpty() && observers.isEmpty() && packets.isEmpty()
}

/**
 * iOS `GlobalSearchSheet` matching: at most 20 per section; monitored channels are included
 * and replace the server entry with the same name.
 */
fun searchNetwork(query: String, nodes: List<MeshNode>, observers: List<MeshObserver>, channels: List<MeshChannel>,
    monitored: List<MonitoredChannel>, packets: List<LivePacket>): SearchResults {
    val q = query.trim()
    if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList(), emptyList())
    fun String?.has() = this?.contains(q, ignoreCase = true) == true
    val allChannels = monitored.map { MeshChannel("user:${it.channelName}", it.title, lastMessage = "Monitored locally") } +
        channels.filter { channel -> monitored.none { it.channelName == channel.name } }
    return SearchResults(
        allChannels.filter { it.name.has() || it.lastMessage.has() || it.lastSender.has() || it.hash.has() }.take(20),
        nodes.filter { it.name.has() || it.publicKey.has() || it.role.has() }.take(20),
        observers.filter { it.name.has() || it.id.has() || it.iata.has() || it.model.has() }.take(20),
        packets.distinctBy { it.hash }.filter { it.hash.has() || it.typeName.has() || it.observerName.has() }.take(20),
    )
}

// endregion
