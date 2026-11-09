package org.nodescope.android.feature.map

import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.model.packetEpoch
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

    /**
     * Live routes redraw at most this often (~30 per second). Each redraw rebuilds the route
     * sources on the main thread; at the display's full 60–120 Hz a busy network kept the CPU
     * working continuously. Replays keep the full rate.
     */
    const val LIVE_FRAME_INTERVAL = 33L
}

/**
 * One hop. [curvature] bows it gently (iOS 0.07), so a packet that doubles back between nearby
 * nodes shows two arcs rather than one line drawn twice. A [dashed] hop bridges hops the analyzer
 * couldn't resolve: drawn faint and dashed, with no arrival ring, since no relay is claimed.
 */
internal class RouteHop(val start: Coordinate, val end: Coordinate, val startsAt: Long, val fadeStartsAt: Long, val fadeDuration: Long,
    val travel: Long = RouteTiming.HOP_TRAVEL, val curvature: Double = 0.0, val dashed: Boolean = false,
    /** A replay hop reaching a node or observer outside the selected region: drawn faded (iOS). */
    val outside: Boolean = false) {
    private val track = if (curvature == 0.0) RouteTrack(listOf(start, end)) else null
    fun position(fraction: Float): Coordinate = track?.position(fraction) ?: curvePoint(start, end, curvature, fraction.toDouble())
    /** The hop from its start to [fraction] of the way: two points straight, enough to look smooth curved. */
    fun points(fraction: Float = 1f): List<Coordinate> {
        if (track != null) return listOf(start, position(fraction))
        val samples = maxOf(2, kotlin.math.ceil(16 * fraction).toInt())
        return (0..samples).map { curvePoint(start, end, curvature, fraction.toDouble() * it / samples) }
    }
}

/** A quadratic curve in Web Mercator space, so it looks the same at every zoom (iOS `ActivePing`). */
internal fun curvePoint(start: Coordinate, end: Coordinate, curvature: Double, t: Double): Coordinate {
    val ax = Mercator.x(start.longitude); val ay = Mercator.y(start.latitude)
    var bx = Mercator.x(end.longitude); val by = Mercator.y(end.latitude)
    // The short way round across the antimeridian.
    if (bx - ax > 0.5) bx -= 1.0 else if (ax - bx > 0.5) bx += 1.0
    val dx = bx - ax; val dy = by - ay
    val cx = (ax + bx) / 2 + dy * curvature; val cy = (ay + by) / 2 - dx * curvature
    val u = 1 - t
    val x = u * u * ax + 2 * u * t * cx + t * t * bx
    val y = u * u * ay + 2 * u * t * cy + t * t * by
    return Coordinate(Mercator.latitude(y), Mercator.longitude(((x % 1.0) + 1.0) % 1.0))
}

/** Bow of live and replayed hops (iOS). */
internal const val HOP_CURVATURE = 0.07

/**
 * How long a hop takes (iOS `hopTravelTime`), in ms. A hop too short to see at [zoom] (under
 * 6 dp) passes straight through: given its usual time, the head sat wiggling in one spot and a
 * route that steps out to a neighbour and back read as ping-pong. Otherwise a steady
 * [kilometresPerSecond] when given, else gentle (logarithmic) growth reaching [longest] at 40 km.
 */
internal fun hopTravelMs(start: Coordinate, end: Coordinate, shortest: Long, longest: Long, zoom: Double?,
    kilometresPerSecond: Double? = null): Long {
    if (zoom != null) {
        val world = 512.0 * Math.pow(2.0, zoom)
        val dx = (Mercator.x(end.longitude) - Mercator.x(start.longitude)) * world
        val dy = (Mercator.y(end.latitude) - Mercator.y(start.latitude)) * world
        if (kotlin.math.hypot(dx, dy) < 6) return 50
    }
    val kilometres = distanceKm(start, end)
    if (kilometresPerSecond != null) return (kilometres / kilometresPerSecond * 1000).toLong().coerceIn(shortest, longest)
    val growth = (longest - shortest) / kotlin.math.log2(41.0)
    return minOf(shortest + (growth * kotlin.math.log2(1 + kilometres)).toLong(), longest)
}

internal fun distanceKm(a: Coordinate, b: Coordinate): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude); val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).let { it * it }
    return 2 * 6371.0 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}

/**
 * One observed packet. Hops within a resolved subchain travel one after another; separate
 * subchains (split by unresolved hops) start together, as on iOS. Isolated known positions pulse.
 *
 * A [historical] packet from recent history has already arrived at [receivedAt] (its packet
 * timestamp): every hop is shown complete and fades together over [RouteTiming.HISTORY_FADE],
 * without replaying travel or pulsing isolated points.
 */
internal class LiveRoute(val key: String, val receivedAt: Long, val color: String, val anchors: List<MapMarker>,
    subchains: List<List<Coordinate>>, val historical: Boolean = false, val replay: Boolean = false,
    /** The observed packet, for route details when the route is tapped (none for a replay). */
    val packet: org.nodescope.android.core.model.LivePacket? = null,
    /** When the packet was observed, for route details; [receivedAt] is when the animation starts. */
    val observedAt: Long = receivedAt,
    /** The map's zoom when the route was built, so hops too short to see pass straight through. */
    zoom: Double? = null,
    /** A replay's hops, already planned (see [planReplayHops]). */
    plannedHops: List<RouteHop>? = null) {
    val hops: List<RouteHop> = plannedHops ?: if (replay) replayHops(subchains, receivedAt) else liveHops(subchains, receivedAt, historical, zoom)
    val pulses: List<Coordinate> = if (historical || replay) emptyList() else subchains.filter { it.size == 1 }.map { it.single() }
    private val startsAt: Long = hops.minOfOrNull { it.startsAt } ?: receivedAt
    val endsAt: Long = max(
        hops.maxOfOrNull { it.fadeStartsAt + it.fadeDuration } ?: receivedAt,
        if (pulses.isEmpty()) receivedAt else receivedAt + RouteTiming.SOLO_PULSE,
    )
    fun isActive(now: Long) = now >= startsAt && now < endsAt
}

/** How strongly a replay hop outside the selected region is drawn (iOS `regionEmphasis`). */
private const val OUTSIDE_REGION_STRENGTH = 0.45f

/** One resolved route of a packet, placed on the map: coordinate chains and the nodes along it. */
data class ReplayRoute(val subchains: List<List<Coordinate>>, val nodes: List<MeshNode>, val hops: Int,
    /** The route and who heard it, for naming it in the replay controls. */
    val option: org.nodescope.android.feature.packets.RouteOption? = null,
    /** The observers it ends at (name and position), drawn as the route's labelled endpoints. */
    val receivers: List<Pair<String, Coordinate>> = emptyList(),
    /** The route's own nodes, without the final hops to its observers. */
    val nodeChains: List<List<Coordinate>> = subchains,
    /** Positions of its nodes outside the selected region, and of observers outside it: drawn faded. */
    val outsidePoints: Set<Coordinate> = emptySet(),
    val outsideReceivers: Set<Coordinate> = emptySet(),
    /** Its last hops had no location, so the hops on to its observers are dashed bridges. */
    val endsInGap: Boolean = false)

/** [RouteReplay.selected] for replaying every route together (iOS `PacketReplayStore.allRoutes`). */
const val ALL_ROUTES = -1

/** The routes a replay shows: all of them, or the selected one. */
fun RouteReplay.shownRoutes(index: Int): List<ReplayRoute> =
    if (index == ALL_ROUTES) routes else listOfNotNull(routes.getOrNull(index))

/**
 * "Replay on map" from packet details (iOS `PacketReplayStore`): every route, and the one chosen.
 * [packetId] is the replayed packet's group id (its hash), so the live packets panel can
 * highlight the same packet the map is showing.
 */
data class RouteReplay(val id: Long, val routes: List<ReplayRoute>, val selected: Int, val packetId: String? = null,
    /** The routes and their observers, so more can be merged in while the replay runs. */
    val options: List<org.nodescope.android.feature.packets.RouteOption> = emptyList())

/** One hop's line; [route] and [hop] identify it from frame to frame. */
internal data class RouteLine(val points: List<Coordinate>, val color: String, val opacity: Float, val route: String = "", val hop: Int = 0,
    /** A bridge over unresolved hops: drawn dashed and fainter. */
    val dashed: Boolean = false)
internal data class RouteRing(val center: Coordinate, val color: String, val radius: Float, val width: Float, val opacity: Float)
internal data class RouteFrame(val lines: List<RouteLine>, val heads: List<Pair<Coordinate, String>>, val rings: List<RouteRing>)

/**
 * Live hops follow one another across the whole path (iOS): each takes 0.3–1.0 s by its real
 * length, and the pieces of a path split by unresolved hops are joined by a quick dashed bridge.
 * A [historical] route has already arrived: every hop shows complete and fades over
 * [RouteTiming.HISTORY_FADE].
 */
private fun liveHops(subchains: List<List<Coordinate>>, startsAt: Long, historical: Boolean, zoom: Double?): List<RouteHop> {
    val hops = mutableListOf<RouteHop>()
    var elapsed = 0L
    var previousEnd: Coordinate? = null
    for (chain in subchains) {
        val from = previousEnd; val to = chain.firstOrNull()
        if (from != null && to != null && from != to) {
            hops += if (historical) RouteHop(from, to, startsAt - 200, startsAt - 200, RouteTiming.HISTORY_FADE, 200, HOP_CURVATURE, dashed = true)
            else RouteHop(from, to, startsAt + elapsed, startsAt + elapsed + 200 + RouteTiming.HOLD, RouteTiming.FADE, 200, HOP_CURVATURE, dashed = true)
            elapsed += 200
        }
        previousEnd = chain.lastOrNull() ?: previousEnd
        for ((a, b) in chain.zipWithNext()) {
            val travel = hopTravelMs(a, b, 300, 1_000, zoom)
            hops += if (historical) {
                val arrived = startsAt - travel
                RouteHop(a, b, arrived, arrived, RouteTiming.HISTORY_FADE, travel, HOP_CURVATURE)
            } else RouteHop(a, b, startsAt + elapsed, startsAt + elapsed + travel + RouteTiming.HOLD, RouteTiming.FADE, travel, HOP_CURVATURE)
            elapsed += travel
        }
    }
    return hops
}

/**
 * A replay's hops (iOS `replayPacketRoute`). Each hop takes its real length at a steady 90 km/s,
 * held to 0.45–1.5 s (a hop too short to see at [zoom] passes straight through), after the hops
 * before it on its route; the final hops, from the route's last node to each of its observers,
 * travel together. With [allRoutes], hops the routes share are planned once, so the packet spreads
 * out from a common trunk; each observer stays only on its shortest route; and each hop leaves a
 * trail, holding a second after it arrives before fading, so the packet is seen spreading rather
 * than as a growing tree. One route instead stays drawn until it finishes, then fades as one path.
 * Returns the hops and the observers kept.
 */
internal fun planReplayHops(routes: List<ReplayRoute>, startsAt: Long, allRoutes: Boolean, zoom: Double?): Pair<List<RouteHop>, List<Pair<String, Coordinate>>> {
    fun travel(a: Coordinate, b: Coordinate) = hopTravelMs(a, b, 450, 1_500, zoom, kilometresPerSecond = 90.0)
    val reached = HashSet<String>()
    val kept = routes.sortedBy { it.hops }.mapNotNull { route ->
        val receivers = if (allRoutes) route.receivers.filter { reached.add(it.first.lowercase()) } else route.receivers
        // A route whose observers are all on shorter routes adds nothing of its own.
        if (allRoutes && route.receivers.isNotEmpty() && receivers.isEmpty()) null else route to receivers
    }
    data class Planned(val from: Coordinate, val to: Coordinate, val start: Long, val travel: Long,
        val dashed: Boolean = false, val outside: Boolean = false)
    val planned = mutableListOf<Planned>()
    val seen = HashSet<Pair<Coordinate, Coordinate>>()
    fun isNew(a: Coordinate, b: Coordinate) = a != b && seen.add(a to b)
    for ((route, receivers) in kept) {
        var elapsed = 0L
        var previous: Coordinate? = null
        for (chain in route.nodeChains) {
            // Pieces split by hops with no location are joined by a dashed bridge (iOS), not drawn
            // as if the two nodes were directly linked.
            val first = chain.firstOrNull() ?: continue
            previous?.takeIf { it != first }?.let { from ->
                val time = travel(from, first)
                if (isNew(from, first)) planned += Planned(from, first, elapsed, time, dashed = true, outside = first in route.outsidePoints)
                elapsed += time
            }
            for ((a, b) in chain.zipWithNext()) {
                val time = travel(a, b)
                if (isNew(a, b)) planned += Planned(a, b, elapsed, time, outside = b in route.outsidePoints)
                elapsed += time
            }
            previous = chain.last()
        }
        val last = previous ?: continue
        receivers.forEach { (_, at) ->
            if (isNew(last, at)) planned += Planned(last, at, elapsed, travel(last, at), dashed = route.endsInGap, outside = at in route.outsideReceivers)
        }
    }
    val fadeStart = startsAt + (planned.maxOfOrNull { it.start + it.travel } ?: 0) + 750
    val hops = planned.map { hop ->
        val begins = startsAt + hop.start
        RouteHop(hop.from, hop.to, begins, if (allRoutes) begins + hop.travel + 1_000 else fadeStart, 650, hop.travel, HOP_CURVATURE,
            dashed = hop.dashed, outside = hop.outside)
    }
    return hops to kept.flatMap { it.second }.distinctBy { it.second }
}

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
        for ((index, hop) in route.hops.withIndex()) {
            val elapsed = now - hop.startsAt
            if (elapsed < 0) continue
            val strength = (if (hop.dashed) 0.6f else 1f) * (if (hop.outside) OUTSIDE_REGION_STRENGTH else 1f)
            if (animate && elapsed < hop.travel) {
                if (route !in transient) continue
                val fraction = elapsed.toFloat() / hop.travel
                lines += RouteLine(hop.points(fraction), route.color, strength, route.key, index, hop.dashed)
                heads += hop.position(fraction) to route.color
                continue
            }
            val fade = ((now - hop.fadeStartsAt).toFloat() / hop.fadeDuration).coerceIn(0f, 1f)
            if (fade < 1f) lines += RouteLine(hop.points(), route.color, (1f - fade) * strength, route.key, index, hop.dashed)
            val arrival = elapsed - hop.travel
            if (animate && route in transient && !hop.dashed && arrival < RouteTiming.ARRIVAL_PULSE) {
                val p = arrival.toFloat() / RouteTiming.ARRIVAL_PULSE
                rings += RouteRing(hop.end, route.color, (8 + p * 42) / 2, max(0.5f, 2.5f - p * 1.5f),
                    (1 - p) * 0.9f * (if (hop.outside) OUTSIDE_REGION_STRENGTH else 1f))
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

/**
 * Live routes for the newest packets. Each packet's route is worked out once and reused, with
 * one node lookup shared by all packets, instead of rebuilding every route on each new packet.
 */
internal class LiveRouteBuilder {
    private var nodes: List<MeshNode>? = null
    private var observers: List<MeshObserver>? = null
    private var lookup: Map<String, MeshNode> = emptyMap()
    private val routes = HashMap<LivePacket, LiveRoute>()
    /**
     * A live route starts animating when it is first ready to draw, not when its packet arrived:
     * the time spent getting it to the map would otherwise be skipped, a jump at the start. Kept
     * per packet, so an update to the packet (more observations) never restarts its route.
     */
    private val starts = HashMap<String, Long>()

    /** When each packet was first seen, for Realistic Propagation. */
    private val firstSeen = HashMap<String, Long>()
    private var holdMs: Long? = null

    /**
     * [holdMs] set (Realistic Propagation, iOS): every observation of a packet waits until that long
     * after the packet was first seen, then all of its paths animate together. The analyzer's
     * buffer is how long it waits for every observer, so the map runs that far behind live.
     */
    fun build(packets: List<LivePacket>, nodes: List<MeshNode>, observers: List<MeshObserver>, filters: MapFilters,
        zoom: Double? = null, holdMs: Long? = null): List<LiveRoute> = traced("NodeScope:routes") {
        if (holdMs != this.holdMs) { this.holdMs = holdMs; routes.clear(); starts.clear() }
        if (nodes !== this.nodes) { this.nodes = nodes; lookup = nodeLookup(nodes); routes.clear() }
        if (observers !== this.observers) { this.observers = observers; routes.clear() }
        // Recent history appears as already-arrived routes while it is under 12 s old, as on iOS.
        val historyCutoff = System.currentTimeMillis() - RouteTiming.HISTORY_FADE
        // Packets that arrive while live traffic is paused by hand are never drawn.
        val candidates = packets.filter { (it.isLive || packetEpoch(it) > historyCutoff) && filters.showsRoute(it) && !LiveTrafficPause.skips(it) }
        // Each observer's first copy of a packet only (iOS): in a flood an observer also hears
        // repeaters echoing the packet back, and drawing those echoes read as ping-pong.
        val reached = HashSet<String>()
        val firstCopies = candidates.sortedBy { if (it.isLive) it.receivedAt else packetEpoch(it) }
            .filterTo(HashSet()) { reached.add("${it.hash}|${(it.observerId ?: it.observerName)?.lowercase()}") }
        val shown = candidates.filter { it in firstCopies }.take(40)
        val result = shown.map { packet ->
            routes.getOrPut(packet) {
                val color = listOf("#66B9FF", "#FFC27A", "#65DDB4", "#BEA1FF")[(packet.hash.hashCode() and Int.MAX_VALUE) % 4]
                val observed = if (packet.isLive) packet.receivedAt else packetEpoch(packet)
                // Only for packets that just arrived: older ones (back after a filter change) keep
                // their own timing rather than replaying.
                val start = if (packet.isLive) starts.getOrPut(packet.key) {
                    val now = System.currentTimeMillis()
                    val arrival = if (now - observed < 2_000) maxOf(observed, now) else observed
                    // Held: from the packet's first sighting, or now if that has already passed.
                    holdMs?.let { hold -> maxOf(firstSeen.getOrPut(packet.hash) { arrival } + hold, arrival) } ?: arrival
                } else observed
                LiveRoute(packet.key, start, color, routeAnchors(packet, nodes, observers, lookup),
                    packetRoute(packet, nodes, observers, lookup), historical = !packet.isLive, packet = packet, observedAt = observed, zoom = zoom)
            }
        }
        routes.keys.retainAll(shown.toSet())
        starts.keys.retainAll(shown.mapTo(HashSet()) { it.key })
        if (firstSeen.size > 400) firstSeen.keys.retainAll(packets.mapTo(HashSet()) { it.hash })
        result
    }
}

/**
 * Whether this display frame should rebuild the live route overlays. Routes entering or leaving
 * always draw at once, and replays draw every frame; otherwise moving routes redraw at most every
 * [RouteTiming.LIVE_FRAME_INTERVAL] ms.
 */
internal fun shouldDrawRouteFrame(now: Long, lastDrawnAt: Long?, routesChanged: Boolean, replaying: Boolean): Boolean =
    routesChanged || replaying || lastDrawnAt == null || now - lastDrawnAt >= RouteTiming.LIVE_FRAME_INTERVAL


/** Below this zoom, ungrouped markers that would draw on top of one another are thinned out. */
internal const val THIN_MARKERS_BELOW_ZOOM = 9.0

/**
 * With Group Nearby Nodes off and the map zoomed out (iOS `withoutCoveredMarkers`), nodes within
 * a few dp of each other mostly hide under one another. Keeps one per cell (a repeater over a room
 * over a companion over a sensor, then by key so the pick is stable) and returns, by kept node's
 * key, the nodes it covers, so a tap there can zoom in on the stack.
 */
internal fun thinCoveredNodes(nodes: List<MeshNode>, zoom: Double): Pair<List<MeshNode>, Map<String, List<MeshNode>>> {
    if (zoom >= THIN_MARKERS_BELOW_ZOOM) return nodes to emptyMap()
    // Every marker costs while panning, so the cell grows with how crowded the map is: 4 dp
    // normally, up to 6 dp until about 300 markers remain (iOS `withoutCoveredMarkers`). Even at
    // 6 dp a hidden node always sits under the 10 dp marker kept.
    var result = thinMarkers(nodes, zoom, 4.0)
    for (cellDp in listOf(5.0, 6.0)) {
        if (result.first.size <= THIN_MARKER_BUDGET) break
        result = thinMarkers(nodes, zoom, cellDp)
    }
    return result
}

private const val THIN_MARKER_BUDGET = 300

private fun thinMarkers(nodes: List<MeshNode>, zoom: Double, cellDp: Double): Pair<List<MeshNode>, Map<String, List<MeshNode>>> {
    val degreesPerDp = 360.0 / (512.0 * Math.pow(2.0, zoom))
    val cell = cellDp * degreesPerDp
    fun rank(node: MeshNode) = MAP_ROLES.indexOf(node.role.lowercase()).let { if (it < 0) MAP_ROLES.size else it }
    val best = HashMap<Pair<Long, Long>, MeshNode>()
    val covered = HashMap<Pair<Long, Long>, MutableList<MeshNode>>()
    for (node in nodes) {
        val at = node.coordinate ?: continue
        // Web Mercator: a dp spans fewer degrees of latitude than longitude away from the equator.
        val key = kotlin.math.floor(at.longitude / cell).toLong() to
            kotlin.math.floor(Mercator.y(at.latitude) * 512.0 * Math.pow(2.0, zoom) / cellDp).toLong()
        val current = best[key]
        if (current == null) { best[key] = node; continue }
        val replaces = compareValuesBy(node, current, ::rank, MeshNode::publicKey) < 0
        covered.getOrPut(key) { mutableListOf() } += if (replaces) current else node
        if (replaces) best[key] = node
    }
    val kept = best.values.sortedBy { it.publicKey }
    return kept to best.entries.mapNotNull { (key, node) -> covered[key]?.let { node.publicKey to it.toList() } }.toMap()
}

/**
 * The role of a node at an observer's site (within 75 m), for the ring around its replay marker:
 * a repeater over a room over a companion over a sensor (iOS `observersWithNodeRoles`).
 */
internal fun siteRole(site: Coordinate, nodes: List<MeshNode>): String? =
    nodes.filter { node -> node.coordinate?.let { distanceKm(it, site) <= 0.075 } == true }
        .map { it.role.lowercase() }
        .minByOrNull { MAP_ROLES.indexOf(it).let { rank -> if (rank < 0) MAP_ROLES.size else rank } }
