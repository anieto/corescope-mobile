package org.nodescope.android.feature.packets

import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.model.packetEpoch

/** Every MeshCore payload type offered in the filter, in wire order (as iOS lists them). */
val packetTypeNames = listOf("REQ", "RESPONSE", "TXT_MSG", "ACK", "ADVERT", "GRP_TXT", "GRP_DATA", "ANON_REQ",
    "PATH", "TRACE", "MULTIPART", "CONTROL", "RAW_CUSTOM")

/** Observations of one packet heard by several observers within [GROUP_WINDOW_MILLIS] of the first. */
const val GROUP_WINDOW_MILLIS = 30_000L

/** One transmission: the observations of the same packet, oldest first. */
data class TransmissionGroup(val id: String, val observations: List<LivePacket>, val region: String?) {
    val latest: LivePacket get() = observations.last()
    val firstAt: Long get() = observedAt(observations.first())
    /** Computed once: sorting reads it on every comparison. */
    val latestAt: Long = observations.maxOf(::observedAt)
    val isLive: Boolean get() = observations.any { it.isLive }
    /** The analyzer's own count can exceed what this device received. */
    val observationCount: Int get() = maxOf(observations.size, observations.maxOf { it.observations })
    val typeName: String get() = latest.typeName
    val longestPath: List<String?> get() = observations.map { it.hops }.maxByOrNull { it.size }.orEmpty()
    val hopCount: Int get() = longestPath.size
    val preview: String get() = latest.payloadText ?: latest.payloadName ?: latest.payloadChannel
        ?: observations.firstNotNullOfOrNull { it.observerName?.takeIf(String::isNotBlank) } ?: id.take(12)
}

fun observedAt(packet: LivePacket): Long = if (packet.isLive) packet.receivedAt else packetEpoch(packet)

/**
 * Same grouping as iOS: observations sharing a hash within 30 s of the group's first become
 * one transmission; packets without a hash stay separate. Newest transmission first.
 */
fun groupTransmissions(packets: List<LivePacket>, typeFilter: String?, observers: List<MeshObserver>): List<TransmissionGroup> {
    val regionById = observers.mapNotNull { observer -> observer.iata?.let { observer.id.lowercase() to it } }.toMap()
    val groups = mutableListOf<MutableList<LivePacket>>()
    val regions = mutableListOf<String?>()
    packets.filter { typeFilter == null || it.typeName == typeFilter }.sortedBy(::observedAt).forEach { packet ->
        val region = packet.region ?: packet.observerId?.lowercase()?.let(regionById::get)
        val hash = packet.hash.trim()
        val index = if (hash.isEmpty()) -1 else groups.indexOfLast { group ->
            group.first().hash.trim() == hash && observedAt(packet) - observedAt(group.first()) <= GROUP_WINDOW_MILLIS
        }
        if (index >= 0) {
            groups[index] += packet
            if (region != null) regions[index] = region
        } else {
            groups += mutableListOf(packet)
            regions += region
        }
    }
    // A hash re-flooded after the grouping window starts a second transmission. Ids must stay
    // unique (they key the packet list, and a duplicate key crashes it), so a later
    // transmission of the same hash gets its start time appended; the first keeps the hash.
    val seen = mutableSetOf<String>()
    return groups.mapIndexed { index, group ->
        val first = group.first()
        val base = first.hash.trim().ifEmpty { "event-${first.id}-${observedAt(first)}" }
        val id = if (seen.add(base)) base else "$base@${observedAt(first)}"
        TransmissionGroup(id, group.toList(), regions[index])
    }.sortedByDescending { it.latestAt }
}

/**
 * Distinct resolved routes (node public keys, at least two), longest first, dropping any route
 * contained in a longer one, as iOS offers for replay.
 */
fun replayRoutes(group: TransmissionGroup): List<List<String>> = group.routeOptions().map { it.keys }

fun distinctRoutes(paths: List<List<String?>>): List<List<String>> = routeOptions(paths.map { HeardPath(it, null) }).map { it.keys }

/**
 * Adds a packet's complete routes (from its full observation list) to the routes a replay started
 * with. The existing routes keep their order, so the one playing stays put. Only routes that are
 * new and not already contained in one of them are appended.
 */
fun mergeReplayRoutes(existing: List<List<String>>, complete: List<List<String>>): List<List<String>> =
    mergeRouteOptions(existing.map { RouteOption(it) }, complete.map { RouteOption(it) }).map { it.keys }

/** One observer's report of a packet, used to say which observer heard which route. */
data class RouteHearing(val observer: String, val region: String? = null, val snr: Double? = null, val rssi: Double? = null,
    /** How many hops this observer's own path had. */
    val hops: Int = 0,
    /** The observer's id when known (live reports carry it; the packet API names observers only). */
    val observerId: String? = null)

/** A reported path and who reported it (null when unknown). */
data class HeardPath(val path: List<String?>, val hearing: RouteHearing?)

/**
 * One distinct route of a packet (node public keys) and who heard it: [heardBy] reported exactly
 * this path, strongest signal first; [alongTheWay] reported a shorter part of it, i.e. heard the
 * packet earlier on its way, longest first.
 */
data class RouteOption(val keys: List<String>, val heardBy: List<RouteHearing> = emptyList(),
    val alongTheWay: List<RouteHearing> = emptyList(), val hops: Int = keys.size)

private val strongestFirst = compareByDescending<RouteHearing> { it.rssi ?: Double.NEGATIVE_INFINITY }
    .thenByDescending { it.snr ?: Double.NEGATIVE_INFINITY }
private fun List<RouteHearing>.distinctObservers() = distinctBy { it.observer.lowercase() }
private fun List<String>.lower() = map(String::lowercase)
private fun List<String>.containsRoute(part: List<String>) = size > part.size && windowed(part.size).any { it == part }

/**
 * Distinct resolved routes (at least two nodes), longest first, dropping any route contained in a
 * longer one, as iOS offers for replay. Who heard each route is kept: a dropped shorter route's
 * observers heard the longer one along the way.
 */
fun routeOptions(reports: List<HeardPath>): List<RouteOption> {
    class Entry(val keys: List<String>, val hearers: MutableList<RouteHearing> = mutableListOf()) { val lower = keys.lower() }
    val byPath = LinkedHashMap<List<String>, Entry>()
    reports.forEach { report ->
        val keys = report.path.mapNotNull { it?.takeIf(String::isNotBlank) }
        if (keys.size < 2) return@forEach
        val entry = byPath.getOrPut(keys.lower()) { Entry(keys) }
        report.hearing?.let { entry.hearers += it.copy(hops = keys.size) }
    }
    val entries = byPath.values.sortedByDescending { it.keys.size }
    val kept = entries.filter { candidate -> entries.none { it.lower.containsRoute(candidate.lower) } }
    val along = kept.associateWith { mutableListOf<RouteHearing>() }
    (entries - kept.toSet()).forEach { part -> kept.firstOrNull { it.lower.containsRoute(part.lower) }?.let { along.getValue(it) += part.hearers } }
    return kept.map { route ->
        RouteOption(route.keys, route.hearers.distinctObservers().sortedWith(strongestFirst),
            along.getValue(route).distinctObservers().sortedByDescending { it.hops })
    }
}

/** The routes in a transmission's observations, with the observer that heard each. */
fun TransmissionGroup.routeOptions(): List<RouteOption> = routeOptions(observations.map { packet ->
    HeardPath(packet.resolvedPath, RouteHearing(packet.title, packet.region, packet.snr, packet.rssi, observerId = packet.observerId))
})

/**
 * Adds a packet's complete routes to the ones already offered, keeping their order so a route
 * that is playing stays put. Observers of a known route join it; a route already contained in a
 * known one adds its observers as heard along the way; only new routes are appended.
 */
fun mergeRouteOptions(existing: List<RouteOption>, complete: List<RouteOption>): List<RouteOption> {
    val merged = existing.toMutableList()
    complete.forEach { option ->
        val lower = option.keys.lower()
        val same = merged.indexOfFirst { it.keys.lower() == lower }
        val container = merged.indexOfFirst { it.keys.lower().containsRoute(lower) }
        when {
            same >= 0 -> merged[same] = merged[same].let { known ->
                known.copy(heardBy = (known.heardBy + option.heardBy).distinctObservers().sortedWith(strongestFirst),
                    alongTheWay = (known.alongTheWay + option.alongTheWay).distinctObservers().sortedByDescending { it.hops })
            }
            container >= 0 -> merged[container] = merged[container].let { known ->
                known.copy(alongTheWay = (known.alongTheWay + option.heardBy + option.alongTheWay).distinctObservers().sortedByDescending { it.hops })
            }
            else -> merged += option
        }
    }
    return merged
}

/** Which route (index) a reported path belongs to: the same route, or one it is part of. */
fun routeIndexFor(path: List<String?>, options: List<RouteOption>): Int? {
    val keys = path.mapNotNull { it?.takeIf(String::isNotBlank) }.lower()
    if (keys.size < 2) return null
    return options.indexOfFirst { it.keys.lower() == keys }.takeIf { it >= 0 }
        ?: options.indexOfFirst { it.keys.lower().containsRoute(keys) }.takeIf { it >= 0 }
}

/** Hops, region and signal of the strongest observer, for the line under a route's name. */
fun RouteOption.summary(): String = listOfNotNull("$hops hops", heardBy.firstOrNull()?.region,
    heardBy.firstOrNull()?.let { first -> first.rssi?.let { "%.0f dBm".format(it) } ?: first.snr?.let { "%.1f dB".format(it) } }).joinToString(" · ")

/** Coordinates for a route of public keys; an unknown or unplaced node breaks the chain rather than bridging it. */
fun routeSubchains(route: List<String>, nodes: List<MeshNode>): List<List<Coordinate>> {
    val lookup = nodes.associateBy { it.publicKey.lowercase() }
    val chains = mutableListOf<MutableList<Coordinate>>(mutableListOf())
    route.forEach { key ->
        val point = lookup[key.lowercase()]?.coordinate
        if (point == null) { if (chains.last().isNotEmpty()) chains += mutableListOf<Coordinate>() }
        else if (chains.last().lastOrNull() != point) chains.last() += point
    }
    return chains.filter { it.isNotEmpty() }
}

/** [groups] with each unchanged group replaced by its instance in [previous], which then holds this list. */
internal fun reuseUnchanged(groups: List<TransmissionGroup>, previous: MutableMap<String, TransmissionGroup>): List<TransmissionGroup> {
    val result = groups.map { group -> previous[group.id]?.takeIf { it == group } ?: group }
    previous.clear()
    result.forEach { previous[it.id] = it }
    return result
}

/**
 * Where a route ends: the observers that heard exactly this path, at their positions, so a replay
 * can finish with the hop to each of them. Observers without a known position are left out.
 */
fun RouteOption.receivers(observers: List<MeshObserver>): List<Pair<String, Coordinate>> = heardBy.mapNotNull { hearing ->
    val observer = observers.firstOrNull { hearing.observerId != null && it.id.equals(hearing.observerId, true) }
        ?: observers.firstOrNull { it.displayName.equals(hearing.observer, true) }
    observer?.coordinate?.let { hearing.observer to it }
}.distinctBy { it.second }
