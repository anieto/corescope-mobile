package org.nodescope.android.feature.map

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.model.parseInstant

/** iOS `MapNodeActivityFilter`: how recently a node must have been heard. */
@Serializable
enum class ActivityFilter(val title: String, val maxAgeMillis: Long?) {
    ALL("Any activity", null),
    FIFTEEN_MINUTES("Last 15 minutes", 15 * 60_000L),
    ONE_HOUR("Last hour", 60 * 60_000L),
    ONE_DAY("Last 24 hours", 24 * 60 * 60_000L),
    ONE_WEEK("Last 7 days", 7 * 24 * 60 * 60_000L),
}

val MAP_ROLES = listOf("repeater", "room", "companion", "sensor")

/**
 * iOS `MapNodeFilterSelection`. Activity and roles choose which node markers show; the
 * observer only limits live and recent routes to packets that observer received.
 */
@Serializable
data class MapFilters(
    val roles: Set<String> = emptySet(),
    val activity: ActivityFilter = ActivityFilter.ALL,
    val observerId: String? = null,
) {
    val isFiltering: Boolean get() = roles.isNotEmpty() || activity != ActivityFilter.ALL || observerId != null
    val activeCount: Int get() = listOf(roles.isNotEmpty(), activity != ActivityFilter.ALL, observerId != null).count { it }

    /** A node with no last-heard time never matches an activity window. */
    fun shows(node: MeshNode, now: Long): Boolean =
        (roles.isEmpty() || node.role.lowercase() in roles) &&
            (activity.maxAgeMillis?.let { age -> parseInstant(node.lastSeen)?.let { now - it.toEpochMilli() <= age } == true } ?: true)

    fun showsRoute(packet: LivePacket): Boolean = observerId == null || packet.observerId.equals(observerId, ignoreCase = true)

    companion object {
        /** What "Active nodes" on the Explore dashboard opens (iOS `.activeNodes`). */
        val ACTIVE_NODES = MapFilters(activity = ActivityFilter.FIFTEEN_MINUTES)
    }
}

/** Observers that can be chosen: those in the selected region (or all), by name. */
fun mapObserverOptions(observers: List<MeshObserver>, region: String?): List<MeshObserver> =
    observers.filter { region == null || it.iata.equals(region, ignoreCase = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name?.takeIf(String::isNotBlank) ?: it.id })

private val filtersJson = Json { ignoreUnknownKeys = true }

/** Saved per analyzer, like iOS; nothing is stored while no filter is set. */
fun loadMapFilters(context: Context, host: String): MapFilters =
    context.getSharedPreferences("map-filters", Context.MODE_PRIVATE).getString(host.trim().lowercase(), null)
        ?.let { runCatching { filtersJson.decodeFromString<MapFilters>(it) }.getOrNull() }
        ?.let { it.copy(roles = it.roles.filterTo(mutableSetOf()) { role -> role in MAP_ROLES }) } ?: MapFilters()

fun saveMapFilters(context: Context, host: String, filters: MapFilters) {
    context.getSharedPreferences("map-filters", Context.MODE_PRIVATE).edit {
        if (filters.isFiltering) putString(host.trim().lowercase(), filtersJson.encodeToString(filters)) else remove(host.trim().lowercase())
    }
}
