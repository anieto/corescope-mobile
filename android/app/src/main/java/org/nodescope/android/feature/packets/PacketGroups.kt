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
    val latestAt: Long get() = observations.maxOf(::observedAt)
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
    return groups.mapIndexed { index, group ->
        val first = group.first()
        TransmissionGroup(first.hash.trim().ifEmpty { "event-${first.id}-${observedAt(first)}" }, group.toList(), regions[index])
    }.sortedByDescending { it.latestAt }
}

/**
 * Distinct resolved routes (node public keys, at least two), longest first, dropping any route
 * contained in a longer one, as iOS offers for replay.
 */
fun replayRoutes(group: TransmissionGroup): List<List<String>> = distinctRoutes(group.observations.map { it.resolvedPath })

fun distinctRoutes(paths: List<List<String?>>): List<List<String>> {
    val seen = mutableSetOf<List<String>>()
    val routes = paths.map { path -> path.mapNotNull { it?.takeIf(String::isNotBlank) } }
        .filter { it.size >= 2 && seen.add(it.map(String::lowercase)) }
        .sortedByDescending { it.size }
    return routes.filter { candidate ->
        routes.none { route -> route.size > candidate.size && route.map(String::lowercase).windowed(candidate.size).any { it == candidate.map(String::lowercase) } }
    }
}

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
