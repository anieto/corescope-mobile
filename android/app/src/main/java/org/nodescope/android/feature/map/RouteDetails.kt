package org.nodescope.android.feature.map

import java.util.Locale
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.MeshNode

/** One stop on a tapped route; [publicKey] is set only when the hop resolved to a known node. */
data class RouteStop(val position: Int, val title: String, val publicKey: String?, val receiver: Boolean = false)

/** iOS `MapRouteDetails`: what a tapped live or recent route carried and where it went. */
data class RouteDetails(val packet: LivePacket, val observedAt: Long, val stops: List<RouteStop>) {
    val hopCount: Int get() = stops.count { !it.receiver }

    fun shareText(relative: String): String = buildList {
        add("NodeScope Route")
        add("Hops: $hopCount")
        add("Observed: $relative")
        packet.observerName?.takeIf(String::isNotBlank)?.let { add("Observer: $it") }
        packet.snr?.let { add("SNR: ${String.format(Locale.US, "%.1f", it)} dB") }
        packet.rssi?.let { add("RSSI: ${String.format(Locale.US, "%.0f", it)} dBm") }
        packet.hash.takeIf(String::isNotBlank)?.let { add("Packet: ${it.uppercase()}") }
        packet.payloadText?.trim()?.takeIf(String::isNotEmpty)?.let { add(""); add("Message:"); add(it) }
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
