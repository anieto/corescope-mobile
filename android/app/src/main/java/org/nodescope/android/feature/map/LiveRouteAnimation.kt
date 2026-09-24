package org.nodescope.android.feature.map

import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.MeshNode
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Live route timing shared with iOS 0.7.2 (`ActivePing` / `MapTrafficCanvas`). */
internal object RouteTiming {
    const val HOP_TRAVEL = 660L
    const val HOLD = 450L
    const val FADE = 550L
    const val ARRIVAL_PULSE = 800L
    const val SOLO_PULSE = 3_000L
    /** History packets are shown already arrived and fade steadily over this window. */
    const val HISTORY_FADE = 12_000L
    /** iOS replay: slower hops, one at a time, then the whole route holds and fades together. */
    const val REPLAY_HOP_TRAVEL = 850L
    const val REPLAY_HOLD = 750L
    const val REPLAY_FADE = 650L
    /** iOS keeps at most 40 anchored hops and 20 moving/pulsing ones during live traffic. */
    const val MAX_TRANSIENT_ROUTES = 20
}

internal class RouteHop(val start: Coordinate, val end: Coordinate, val startsAt: Long, val fadeStartsAt: Long, val fadeDuration: Long,
    val travel: Long = RouteTiming.HOP_TRAVEL) {
    private val track = RouteTrack(listOf(start, end))
    fun position(fraction: Float): Coordinate = track.position(fraction) ?: end
}

/**
 * One observed packet. Hops within a resolved subchain travel one after another; separate
 * subchains (split by unresolved hops) start together, as on iOS. Isolated known positions pulse.
 *
 * A [historical] packet from recent history has already arrived at [receivedAt] (its packet
 * timestamp): every hop is shown complete and fades together over [RouteTiming.HISTORY_FADE],
 * without replaying travel or pulsing isolated points.
 */
internal class LiveRoute(val key: String, val receivedAt: Long, val color: String, val anchors: List<Feature>,
    subchains: List<List<Coordinate>>, val historical: Boolean = false, val replay: Boolean = false,
    /** The observed packet, for route details when the route is tapped (none for a replay). */
    val packet: org.nodescope.android.core.model.LivePacket? = null) {
    val hops: List<RouteHop> = if (replay) replayHops(subchains, receivedAt) else subchains.filter { it.size > 1 }.flatMap { chain ->
        chain.zipWithNext().mapIndexed { index, (a, b) ->
            if (historical) {
                val arrived = receivedAt - RouteTiming.HOP_TRAVEL
                RouteHop(a, b, arrived, arrived, RouteTiming.HISTORY_FADE)
            } else {
                val startsAt = receivedAt + index * RouteTiming.HOP_TRAVEL
                RouteHop(a, b, startsAt, startsAt + RouteTiming.HOP_TRAVEL + RouteTiming.HOLD, RouteTiming.FADE)
            }
        }
    }
    val pulses: List<Coordinate> = if (historical || replay) emptyList() else subchains.filter { it.size == 1 }.map { it.single() }
    private val startsAt: Long = hops.minOfOrNull { it.startsAt } ?: receivedAt
    val endsAt: Long = max(
        hops.maxOfOrNull { it.fadeStartsAt + it.fadeDuration } ?: receivedAt,
        if (pulses.isEmpty()) receivedAt else receivedAt + RouteTiming.SOLO_PULSE,
    )
    fun isActive(now: Long) = now >= startsAt && now < endsAt
}

/** One resolved route of a packet, placed on the map: coordinate chains and the nodes along it. */
data class ReplayRoute(val subchains: List<List<Coordinate>>, val nodes: List<MeshNode>, val hops: Int)

/** "Replay on map" from packet details (iOS `PacketReplayStore`): every route, and the one chosen. */
data class RouteReplay(val id: Long, val routes: List<ReplayRoute>, val selected: Int)

internal data class RouteLine(val points: List<Coordinate>, val color: String, val opacity: Float, val route: String = "")
internal data class RouteRing(val center: Coordinate, val color: String, val radius: Float, val width: Float, val opacity: Float)
internal data class RouteFrame(val lines: List<RouteLine>, val heads: List<Pair<Coordinate, String>>, val rings: List<RouteRing>)

/** Replay hops travel strictly one after another across every subchain, then fade as one route. */
private fun replayHops(subchains: List<List<Coordinate>>, startsAt: Long): List<RouteHop> {
    val pairs = subchains.filter { it.size > 1 }.flatMap { it.zipWithNext() }
    val fadeStart = startsAt + pairs.size * RouteTiming.REPLAY_HOP_TRAVEL + RouteTiming.REPLAY_HOLD
    return pairs.mapIndexed { index, (a, b) ->
        RouteHop(a, b, startsAt + index * RouteTiming.REPLAY_HOP_TRAVEL, fadeStart, RouteTiming.REPLAY_FADE, RouteTiming.REPLAY_HOP_TRAVEL)
    }
}

/**
 * A hop draws itself progressively with a small head, sends an arrival ring from its end,
 * holds briefly and fades. With system animations off, hops appear complete and nothing moves.
 */
internal fun routeFrame(routes: List<LiveRoute>, now: Long, animate: Boolean): RouteFrame {
    val lines = mutableListOf<RouteLine>()
    val heads = mutableListOf<Pair<Coordinate, String>>()
    val rings = mutableListOf<RouteRing>()
    val transient = routes.sortedByDescending { it.receivedAt }.take(RouteTiming.MAX_TRANSIENT_ROUTES).toSet()
    for (route in routes) {
        for (hop in route.hops) {
            val elapsed = now - hop.startsAt
            if (elapsed < 0) continue
            if (animate && elapsed < hop.travel) {
                if (route !in transient) continue
                val current = hop.position(elapsed.toFloat() / hop.travel)
                lines += RouteLine(listOf(hop.start, current), route.color, 1f, route.key)
                heads += current to route.color
                continue
            }
            val fade = ((now - hop.fadeStartsAt).toFloat() / hop.fadeDuration).coerceIn(0f, 1f)
            if (fade < 1f) lines += RouteLine(listOf(hop.start, hop.end), route.color, 1f - fade, route.key)
            val arrival = elapsed - hop.travel
            if (animate && route in transient && arrival < RouteTiming.ARRIVAL_PULSE) {
                val p = arrival.toFloat() / RouteTiming.ARRIVAL_PULSE
                rings += RouteRing(hop.end, route.color, (8 + p * 42) / 2, max(0.5f, 2.5f - p * 1.5f), (1 - p) * 0.9f)
            }
        }
        val pulseElapsed = now - route.receivedAt
        if (animate && route in transient && pulseElapsed in 0 until RouteTiming.SOLO_PULSE) {
            val p = pulseElapsed.toFloat() / RouteTiming.SOLO_PULSE
            route.pulses.forEach { point ->
                rings += RouteRing(point, route.color, (14 + p * 42) / 2, 2.5f, (1 - p) * 0.8f)
                rings += RouteRing(point, route.color, (10 + p * 20) / 2, 1.5f, (1 - p) * 0.45f)
            }
        }
    }
    return RouteFrame(lines, heads, rings)
}

internal fun RouteFrame.lineFeatures() = lines.map { line ->
    Feature.fromGeometry(LineString.fromLngLats(line.points.map { Point.fromLngLat(it.longitude, it.latitude) })).apply {
        addStringProperty("color", line.color)
        addNumberProperty("opacity", line.opacity)
        addStringProperty("route", line.route)
    }
}
internal fun RouteFrame.headFeatures() = heads.map { (point, color) ->
    Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply { addStringProperty("color", color) }
}
internal fun RouteFrame.ringFeatures() = rings.map { ring ->
    Feature.fromGeometry(Point.fromLngLat(ring.center.longitude, ring.center.latitude)).apply {
        addStringProperty("color", ring.color)
        addNumberProperty("radius", ring.radius)
        addNumberProperty("width", ring.width)
        addNumberProperty("opacity", ring.opacity)
    }
}

/**
 * iOS parity for co-located nodes: nodes that share an exact position fan out around it on a
 * circle of constant screen size, so each stays individually tappable. The data position is
 * unchanged; only the marker moves. Recomputed when the zoom settles.
 */
internal fun spreadCoincidentNodes(nodes: List<MeshNode>, zoom: Double, radiusDp: Double = 18.0): Map<String, Coordinate> {
    val degreesPerDp = 360.0 / (512.0 * Math.pow(2.0, zoom))
    val spread = mutableMapOf<String, Coordinate>()
    nodes.filter { it.coordinate != null }.groupBy { it.coordinate!! }.forEach { (origin, group) ->
        if (group.size < 2) return@forEach
        val radius = radiusDp * max(1.0, group.size / 6.0) * degreesPerDp
        group.sortedBy { it.publicKey }.forEachIndexed { index, node ->
            val angle = index.toDouble() / group.size * 2 * Math.PI - Math.PI / 2
            // Web Mercator: a screen dp covers cos(latitude) as many degrees north-south as east-west.
            val latitude = (origin.latitude - sin(angle) * radius * cos(Math.toRadians(origin.latitude))).coerceIn(-85.0, 85.0)
            val longitude = ((origin.longitude + cos(angle) * radius + 540) % 360) - 180
            spread[node.publicKey] = Coordinate(latitude, longitude)
        }
    }
    return spread
}
