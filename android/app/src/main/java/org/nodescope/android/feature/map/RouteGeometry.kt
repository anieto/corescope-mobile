package org.nodescope.android.feature.map

import org.nodescope.android.core.model.Coordinate

/** Preserve gaps. An unresolved hop must never join two unrelated route segments. */
fun resolvedSegments(hops: List<Coordinate?>): List<List<Coordinate>> {
    val segments = mutableListOf<List<Coordinate>>()
    var current = mutableListOf<Coordinate>()
    for (hop in hops) {
        if (hop == null) {
            if (current.size > 1) segments += current.toList()
            current = mutableListOf()
        } else current += hop
    }
    if (current.size > 1) segments += current.toList()
    return segments
}

/** Cached Mercator distances keep particles at constant speed along the rendered line. */
internal class RouteTrack(private val route: List<Coordinate>) {
    private fun mercator(latitude: Double) = kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878)) / 2))
    private val ys = route.map { mercator(it.latitude) }
    private val dx = route.zipWithNext { a, b -> Math.toRadians(((b.longitude - a.longitude + 540) % 360) - 180) }
    private val lengths = dx.mapIndexed { i, x -> kotlin.math.hypot(x, ys[i + 1] - ys[i]) }
    private val total = lengths.sum()

    fun position(progress: Float): Coordinate? {
        if (route.isEmpty()) return null
        if (route.size == 1 || total == 0.0 || progress <= 0f) return route.first()
        if (progress >= 1f) return route.last()
        var distance = progress * total
        for (i in lengths.indices) {
            if (distance <= lengths[i] && lengths[i] > 0) {
                val fraction = distance / lengths[i]
                val longitude = ((route[i].longitude + Math.toDegrees(dx[i]) * fraction + 540) % 360) - 180
                val latitude = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp(ys[i] + (ys[i + 1] - ys[i]) * fraction)) - Math.PI / 2)
                return Coordinate(latitude, longitude)
            }
            distance -= lengths[i]
        }
        return route.last()
    }
}

fun routePosition(route: List<Coordinate>, progress: Float): Coordinate? = RouteTrack(route).position(progress)

/** Never guess among colliding prefixes or draw a line over an unresolved hop. */
fun packetRoute(packet: org.nodescope.android.core.model.LivePacket,
    nodes: List<org.nodescope.android.core.model.MeshNode>,
    observers: List<org.nodescope.android.core.model.MeshObserver>): List<List<Coordinate>> {
    val chain = resolvedRouteNodes(packet, nodes).map { it?.coordinate }.toMutableList()
    val observer = observers.firstOrNull { it.id.equals(packet.observerId, true) }
        ?: observers.filter { packet.observerName != null && it.name.equals(packet.observerName, true) }.singleOrNull()
    observer?.coordinate?.let { if (chain.lastOrNull() != it) chain.add(it) }
    // Keep isolated known positions as pulses without fabricating links.
    val result = mutableListOf<List<Coordinate>>()
    var segment = mutableListOf<Coordinate>()
    for (point in chain) {
        if (point == null) { if (segment.isNotEmpty()) result.add(segment); segment = mutableListOf() }
        else if (segment.lastOrNull() != point) segment.add(point)
    }
    if (segment.isNotEmpty()) result.add(segment)
    return result
}

/** Shared identity resolution for lines and temporary route markers; never guess a prefix. */
internal fun resolvedRouteNodes(packet: org.nodescope.android.core.model.LivePacket,
    nodes: List<org.nodescope.android.core.model.MeshNode>): List<org.nodescope.android.core.model.MeshNode?> {
    val lookup = nodes.associateBy { it.publicKey.lowercase() }
    return (0 until maxOf(packet.hops.size, packet.resolvedPath.size)).map { index ->
        val resolved = packet.resolvedPath.getOrNull(index)
        if (!resolved.isNullOrBlank()) lookup[resolved.lowercase()]
        // Explicit server nulls are unresolved; preserve the gap.
        else if (index < packet.resolvedPath.size) null
        else packet.hops.getOrNull(index)?.takeIf { it.isNotBlank() }?.lowercase()?.let { prefix ->
            lookup[prefix] ?: nodes.filter { it.publicKey.startsWith(prefix, true) }.singleOrNull()
        }
    }
}
