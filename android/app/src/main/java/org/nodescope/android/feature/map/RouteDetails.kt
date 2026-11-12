package org.nodescope.android.feature.map

import java.util.Locale
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.MeshNode

/** One stop on a tapped route; [publicKey] is set only when the hop resolved to a known node. */
data class RouteStop(val position: Int, val title: String, val publicKey: String?, val receiver: Boolean = false)

/** iOS `MapRouteDetails`: what a tapped live or recent route carried and where it went. */
data class RouteDetails(val packet: LivePacket, val observedAt: Long?, val stops: List<RouteStop>,
    /** Who sent the message, when it is known apart from the text (a replay). */
    val sender: String? = null) {
    val hopCount: Int get() = stops.count { !it.receiver }

    fun shareText(relative: String?): String = buildList {
        add("NodeScope Route")
        add("Hops: $hopCount")
        relative?.let { add("Observed: $it") }
        packet.observerName?.takeIf(String::isNotBlank)?.let { add("Observer: $it") }
        packet.snr?.let { add("SNR: ${String.format(Locale.US, "%.1f", it)} dB") }
        packet.rssi?.let { add("RSSI: ${String.format(Locale.US, "%.0f", it)} dBm") }
        packet.hash.takeIf(String::isNotBlank)?.let { add("Packet: ${it.uppercase()}") }
        packet.payloadText?.trim()?.takeIf(String::isNotEmpty)?.let {
            add(""); add(sender?.takeIf(String::isNotBlank)?.let { name -> "Message from $name:" } ?: "Message:"); add(it)
        }
        add("")
        add("Route:")
        stops.forEach { stop -> add("${stop.position}. ${stop.title}" + (stop.publicKey?.let { " ($it)" } ?: "")) }
    }.joinToString("\n")
}

/**
 * Every hop in path order, then the observer that received it. Hops that did not resolve are
 * listed by their prefix rather than guessed, the same rule the map uses to draw them.
 */
fun routeDetails(packet: LivePacket, nodes: List<MeshNode>, observedAt: Long): RouteDetails {
    val hops = resolvedRouteNodes(packet, nodes).mapIndexed { index, node ->
        RouteStop(index + 1, node?.displayName ?: packet.hops.getOrNull(index)?.takeIf(String::isNotBlank)
            ?.let { "Unknown node (${it.uppercase()})" } ?: "Unknown node", node?.publicKey)
    }
    return RouteDetails(packet, observedAt, hops + RouteStop(hops.size + 1, packet.title, null, receiver = true))
}

/**
 * Details for a tapped replayed route (iOS: a replay ping carries its packet): the route's own
 * stops, then the observer that heard it, with that observer's signal.
 */
fun replayRouteDetails(replay: RouteReplay, route: ReplayRoute, nodes: List<MeshNode>): RouteDetails {
    val info = replay.packet
    val known = (nodes + route.nodes).associateBy { it.publicKey.lowercase() }
    val keys = route.option?.keys.orEmpty()
    val hearing = route.option?.heardBy?.firstOrNull()
    val hops = keys.mapIndexed { index, key ->
        val node = known[key.lowercase()]
        RouteStop(index + 1, node?.displayName ?: "Unknown node (${key.take(8).uppercase()})", node?.publicKey ?: key.takeIf { it.length == 64 })
    }
    val packet = LivePacket(
        id = null, hash = info?.hash ?: replay.packetId.orEmpty(), payloadType = null, typeName = "",
        observerId = hearing?.observerId, observerName = hearing?.observer, region = hearing?.region,
        snr = hearing?.snr ?: info?.snr, rssi = hearing?.rssi ?: info?.rssi, hops = emptyList(), resolvedPath = emptyList(),
        timestamp = null, receivedAt = info?.observedAt ?: 0L, isLive = false,
        payloadText = info?.text, payloadChannel = info?.channel,
    )
    val receiver = listOfNotNull(hearing?.observer?.takeIf(String::isNotBlank)?.let { RouteStop(hops.size + 1, it, null, receiver = true) })
    return RouteDetails(packet, info?.observedAt, hops + receiver, sender = info?.sender)
}

/** The shown route nearest a tap: the one whose line passes closest to [at] (the first without one). */
fun nearestReplayRoute(routes: List<ReplayRoute>, at: org.nodescope.android.core.model.Coordinate?): ReplayRoute? {
    if (at == null || routes.size < 2) return routes.firstOrNull()
    fun distance(a: org.nodescope.android.core.model.Coordinate, b: org.nodescope.android.core.model.Coordinate): Double {
        // Planar in degrees, longitude scaled by latitude: only the ordering matters.
        val scale = kotlin.math.cos(Math.toRadians(at.latitude))
        val ax = (a.longitude - at.longitude) * scale; val ay = a.latitude - at.latitude
        val bx = (b.longitude - at.longitude) * scale; val by = b.latitude - at.latitude
        val dx = bx - ax; val dy = by - ay
        val length = dx * dx + dy * dy
        val t = if (length == 0.0) 0.0 else (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return px * px + py * py
    }
    return routes.minByOrNull { route ->
        val points = route.subchains.flatten() + route.receivers.map { it.second }
        if (points.size < 2) points.firstOrNull()?.let { distance(it, it) } ?: Double.MAX_VALUE
        else points.zipWithNext().minOf { (a, b) -> distance(a, b) }
    }
}
