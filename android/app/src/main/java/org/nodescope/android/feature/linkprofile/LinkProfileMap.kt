package org.nodescope.android.feature.linkprofile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.feature.map.*
import kotlin.math.abs

/**
 * The map's saveable state, held by the screen above its layout branches so the camera and an
 * unconfirmed tap survive folding, rotation and posture changes.
 */
internal class LinkProfileMapState(val camera: MutableState<List<Double>>, val tentative: MutableState<Tentative?>,
    /** Whether the opening view has been chosen; a restored camera is never re-framed. */
    val framed: MutableState<Boolean>,
    /** The A and B last fitted on screen, so a restored camera is not re-fitted to the same pair. */
    val fittedPath: MutableState<List<Double>?>) {
    /** An unconfirmed tap and the endpoint it was made for; it never carries over from A to B. [name] is the known node tapped, if any. */
    data class Tentative(val endpoint: String, val latitude: Double, val longitude: Double, val name: String? = null)
}

private val tentativeSaver = androidx.compose.runtime.saveable.listSaver<LinkProfileMapState.Tentative?, Any>(
    save = { it?.let { tap -> listOf(tap.endpoint, tap.latitude, tap.longitude, tap.name.orEmpty()) } ?: emptyList() },
    restore = { values -> if (values.size == 4) LinkProfileMapState.Tentative(values[0] as String, values[1] as Double, values[2] as Double,
        (values[3] as String).ifEmpty { null }) else null })

@Composable
internal fun rememberLinkProfileMapState(origin: ProfileCoordinate?): LinkProfileMapState {
    val camera = rememberSaveable { mutableStateOf(listOf(origin?.latitude ?: 0.0, origin?.longitude ?: 0.0, if (origin != null) 10.0 else 1.0, 0.0, 0.0)) }
    val tentative = rememberSaveable(stateSaver = tentativeSaver) { mutableStateOf<LinkProfileMapState.Tentative?>(null) }
    // An endpoint already centers the camera; otherwise the selected region frames it once loaded.
    val framed = rememberSaveable { mutableStateOf(origin != null) }
    val fittedPath = rememberSaveable { mutableStateOf<List<Double>?>(null) }
    return remember(camera, tentative, framed, fittedPath) { LinkProfileMapState(camera, tentative, framed, fittedPath) }
}

/**
 * The full-bleed map. While [endpoint] is being chosen, a tap leaves a tentative pin that the
 * screen's header confirms or cancels. [bottomInset] is the part covered by the results sheet:
 * the logo, attribution and path framing stay above it.
 */
@Composable
internal fun LinkProfileMap(endpoint: String?, pointA: ProfileCoordinate?, pointB: ProfileCoordinate?,
    nodes: List<org.nodescope.android.core.model.MeshNode>,
    modifier: Modifier = Modifier, mapState: LinkProfileMapState = rememberLinkProfileMapState(pointA ?: pointB),
    snapshot: org.nodescope.android.core.model.AnalyzerSnapshot? = null, selectedRegion: String? = null,
    sourceViewport: org.nodescope.android.core.model.RegionCoordinate? = null, analysis: LinkAnalysis? = null,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp, names: Map<String, String> = emptyMap()) {
    Box(modifier) {
        var tentativePoint by mapState.tentative
        val current = tentativePoint?.takeIf { it.endpoint == endpoint }
        val latitude = current?.latitude
        val longitude = current?.longitude
        var camera by mapState.camera
        var retry by remember { mutableIntStateOf(0) }
        // The provider and style, shared with the live map: picking one here changes both.
        val context = LocalContext.current
        var choice by remember { mutableStateOf(loadMapProvider(context)) }
        var layersOpen by remember { mutableStateOf(false) }
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
        val engine: MapEngineState = if (choice.provider == MapProvider.GOOGLE) rememberGoogleMapEngine() else rememberMapLibreEngine()
        // With no endpoints yet, open on the region selected for the live map, framed the same way.
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val insetPx = (bottomInset.value * density).toInt()
        LaunchedEffect(engine.camera, snapshot, selectedRegion, sourceViewport) {
            val ready = engine.camera ?: return@LaunchedEffect
            if (mapState.framed.value || snapshot == null) return@LaunchedEffect
            val inset = (24 * density).toInt()
            when (val target = cameraTarget(snapshot, selectedRegion, sourceViewport)) {
                null -> return@LaunchedEffect
                is CameraTarget.Center -> ready.jump(CameraMove.Center(target.coordinate, target.zoom))
                is CameraTarget.Bounds -> ready.jump(CameraMove.Fit(target, FramingInsets(inset, inset, inset, inset + insetPx)))
            }
            mapState.framed.value = true
        }
        // Once both endpoints are chosen, show the whole path, clear of the legend and the pins
        // above it and of the sheet below.
        LaunchedEffect(engine.camera, pointA, pointB, endpoint, insetPx) {
            val ready = engine.camera ?: return@LaunchedEffect
            if (endpoint != null || pointA == null || pointB == null) return@LaunchedEffect
            val path = listOf(pointA.latitude, pointA.longitude, pointB.latitude, pointB.longitude)
            if (mapState.fittedPath.value == path) return@LaunchedEffect
            val ends = listOfNotNull(Coordinate.gps(pointA.latitude, pointA.longitude), Coordinate.gps(pointB.latitude, pointB.longitude))
            if (ends.size < 2) return@LaunchedEffect
            val side = (40 * density).toInt()
            ready.animate(CameraMove.Fit(boundsOf(ends), FramingInsets(side, (88 * density).toInt(), side, (32 * density).toInt() + insetPx)))
            mapState.fittedPath.value = path
            mapState.framed.value = true
        }
        LaunchedEffect(engine.overlays, latitude, longitude, current?.name, pointA, pointB, nodes, analysis, dark, names) {
            val a = pointA?.let { Coordinate.gps(it.latitude, it.longitude) }
            val b = pointB?.let { Coordinate.gps(it.latitude, it.longitude) }
            val tentative = latitude?.let { lat -> longitude?.let { lon -> Coordinate.gps(lat, lon) } }
            // As on iOS: a pending choice is drawn as the endpoint it will become, lettered and in
            // its colour, and stands in for that endpoint's old pin. A node that is an endpoint, or
            // about to be, gives up its own marker, so the pin visibly takes the node's place.
            val pending = endpoint.takeIf { tentative != null }
            val occupied = listOfNotNull(a, b, tentative)
            fun isOccupied(node: org.nodescope.android.core.model.MeshNode) = node.coordinate?.let { spot ->
                occupied.any { abs(it.latitude - spot.latitude) < 1e-7 && abs(it.longitude - spot.longitude) < 1e-7 }
            } == true
            fun kind(letter: String) = if (letter == "A") MapPin.Kind.POINT_A else MapPin.Kind.POINT_B
            engine.overlays?.setNodes(nodeMarkers(nodes.filterNot(::isOccupied)), grouped = true)
            engine.overlays?.setPins(listOfNotNull(
                a?.takeIf { pending != "A" }?.let { MapPin(it, MapPin.Kind.POINT_A, names["A"] ?: "Point A") },
                b?.takeIf { pending != "B" }?.let { MapPin(it, MapPin.Kind.POINT_B, names["B"] ?: "Point B") },
                tentative?.let { spot -> pending?.let { MapPin(spot, kind(it), current?.name ?: "Dropped pin") } }))
        }
        // The route's dashes are laid out for the screen, so they are redrawn whenever the camera settles.
        LaunchedEffect(engine.overlays, engine.camera, pointA, pointB, analysis, dark, camera) {
            val a = pointA?.let { Coordinate.gps(it.latitude, it.longitude) }
            val b = pointB?.let { Coordinate.gps(it.latitude, it.longitude) }
            val screen = engine.camera?.takeIf { a != null && b != null }?.let { LinkPathScreen(it.toScreen(a!!), it.toScreen(b!!), it.width, it.height, density) }
            engine.overlays?.setLinkPath(if (a != null && b != null) linkPathStretches(a, b, analysis, dark, screen) else emptyList())
        }
        // A known node under the finger wins; otherwise the tapped spot, or a group expands.
        val onTap: (MapTap) -> Boolean = { tap ->
            val node = nodes.firstOrNull { it.publicKey == tap.nodeKey }
            val spot = node?.coordinate ?: tap.coordinate?.takeIf { tap.expandGroup == null }
            if (endpoint != null && spot != null) {
                tentativePoint = LinkProfileMapState.Tentative(endpoint, spot.latitude, spot.longitude, node?.name?.takeIf { it.isNotBlank() })
                true
            } else { tap.expandGroup?.invoke(); tap.expandGroup != null }
        }
        when (engine) {
            is MapLibreEngine -> MapLibreEngineHost(engine, choice.cartoStyle(dark), retry, insetPx, camera, { camera = it }, onTap, Modifier.fillMaxSize())
            // Google draws its labels over the route; dropping businesses clears most of them.
            is GoogleMapEngine -> GoogleMapEngineHost(engine, choice.googleType, dark, insetPx, camera, { camera = it }, onTap, Modifier.fillMaxSize(),
                hidesBusinesses = true)
        }
        if (analysis != null && endpoint == null) ClearanceLegend(Modifier.align(Alignment.TopStart).padding(8.dp))
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                IconButton(onClick = { layersOpen = true }) { Icon(androidx.compose.material.icons.Icons.Outlined.Layers, "Map layers") }
            }
            MapLayersMenu(layersOpen, { layersOpen = false }, choice, dark) {
                choice = it
                saveMapProvider(context, it)
                layersOpen = false
            }
        }
        if (engine.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (engine.failed) Surface(Modifier.align(Alignment.Center)) {
            TextButton(onClick = { retry++ }) { Text("Map failed to load. Retry") }
        }
        // Google draws its own logo and attribution on the map.
        val uri = LocalUriHandler.current
        if (engine is MapLibreEngine) Row(Modifier.align(Alignment.BottomEnd).padding(bottom = bottomInset)) {
            val style = MaterialTheme.typography.labelSmall
            TextButton(onClick = { uri.openUri("https://carto.com/attributions") }) { Text("© CARTO", style = style) }
            TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap", style = style) }
        }
    }
}

/** Clear green, marginal orange, obstructed red: the system colours, brighter on dark maps. */
internal fun PathClearance.argb(dark: Boolean): Int = when (this) {
    PathClearance.CLEAR -> if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    PathClearance.MARGINAL -> if (dark) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()
    PathClearance.OBSTRUCTED -> if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
}

internal val PathClearance.title: String get() = when (this) {
    PathClearance.CLEAR -> "Clear"
    PathClearance.MARGINAL -> "Marginal"
    PathClearance.OBSTRUCTED -> "Obstructed"
}

private const val UnanalyzedBlue = 0xD9299EFF.toInt()

/** The unanalyzed line's faint dark edge. */
private const val UnanalyzedCasing = 0x40000000

/** The analyzed route's dark edge, the same on every map, as on iOS. */
private const val RouteCasing = 0x66000000

/**
 * The analyzed route's dash, as on iOS: 8 dp pills with 5 dp gaps on the 4 dp line. Each dash is
 * its own short line with round ends, so the line between them is the pill less the width.
 */
private const val DashDp = 4.0
private const val DashPeriodDp = 13.0
/** Below this length on screen there's room for only a few dashes, and short stretches turn into stray dots, so the route is solid. */
private const val ShortestDashedDp = 80.0

/** Where A and B are on screen, in pixels, and the screen they are on. */
internal data class LinkPathScreen(val a: Pair<Float, Float>, val b: Pair<Float, Float>, val width: Int, val height: Int, val density: Float)

/**
 * The A–B line: a thin solid blue line until analyzed, then rounded dashes coloured by clearance,
 * so dashes always mean a result. Neither map engine can carry a dash pattern across colour
 * changes or round every dash, so the dashes are drawn as separate lines, laid out for the
 * [screen] the camera last settled on and only where they are near it. Without a [screen] yet,
 * or while the route is short on screen, it is solid. Samples are evenly spaced in latitude and longitude ([LinkGeometry.samples]),
 * so a distance along the path maps to the same fraction between A and B.
 */
internal fun linkPathStretches(a: Coordinate, b: Coordinate, analysis: LinkAnalysis?, dark: Boolean, screen: LinkPathScreen? = null): List<PathStretch> {
    val unanalyzed = listOf(PathStretch(listOf(a, b), UnanalyzedBlue, UnanalyzedCasing, width = 2.5f, casingWidth = 4.5f))
    if (analysis == null) return unanalyzed
    val length = analysis.result.distanceMeters.takeIf { it > 0 } ?: return unanalyzed
    var delta = b.longitude - a.longitude
    if (delta > 180) delta -= 360
    if (delta < -180) delta += 360
    fun at(fraction: Double): Coordinate? {
        var longitude = a.longitude + fraction * delta
        if (longitude > 180) longitude -= 360
        if (longitude < -180) longitude += 360
        return Coordinate.gps(a.latitude + fraction * (b.latitude - a.latitude), longitude)
    }
    val stretches = LinkProfileEngine.clearanceStretches(analysis.request).map { stretch ->
        Triple((stretch.startMeters / length).coerceIn(0.0, 1.0), (stretch.endMeters / length).coerceIn(0.0, 1.0), stretch.clearance.argb(dark))
    }
    fun piece(from: Double, to: Double, color: Int) = PathStretch(listOfNotNull(at(from), at(to)), color, RouteCasing)
    val fractions = screen?.let(::routeDashes) ?: return stretches.map { (from, to, color) -> piece(from, to, color) }
    return stretches.flatMap { (from, to, color) ->
        val pieces = fractions.mapNotNull { (start, end) ->
            val s = maxOf(start, from)
            val e = minOf(end, to)
            if (e > s) piece(s, e, color) else null
        }
        // A stretch shorter than a gap still shows, as a dot at its middle.
        if (pieces.isNotEmpty() || to <= from || fractions.isEmpty()) pieces
        else fractions.first().let { (start, end) -> (end - start) / 4 }.let { half ->
            listOf(piece((from + to) / 2 - half, (from + to) / 2 + half, color))
        }
    }
}

/**
 * The dashes, as fractions from A to B, near the [screen]: within half a screen of it on every
 * side, so a short pan still finds dashes drawn. Dashes are counted from A, so they stay put as the
 * camera moves, and [DashPeriodDp] apart whatever the zoom. Null when the route is too short on screen to dash.
 */
internal fun routeDashes(screen: LinkPathScreen): List<Pair<Double, Double>>? {
    val (ax, ay) = screen.a
    val dx = (screen.b.first - ax).toDouble()
    val dy = (screen.b.second - ay).toDouble()
    val lengthDp = kotlin.math.hypot(dx, dy) / screen.density
    if (lengthDp < ShortestDashedDp) return null
    // Clip the route to the screen widened by half on every side (Liang–Barsky).
    var t0 = 0.0
    var t1 = 1.0
    fun clip(p: Double, q: Double): Boolean {
        if (p == 0.0) return q >= 0
        val r = q / p
        if (p < 0) { if (r > t1) return false; if (r > t0) t0 = r } else { if (r < t0) return false; if (r < t1) t1 = r }
        return true
    }
    val left = -screen.width / 2.0
    val right = screen.width * 1.5
    val top = -screen.height / 2.0
    val bottom = screen.height * 1.5
    if (!(clip(-dx, ax - left) && clip(dx, right - ax) && clip(-dy, ay - top) && clip(dy, bottom - ay))) return emptyList()
    val period = DashPeriodDp / lengthDp
    val dash = DashDp / lengthDp
    val first = kotlin.math.floor(t0 / period).toLong()
    val last = kotlin.math.ceil(t1 / period).toLong()
    return (first..last).mapNotNull { index ->
        val start = index * period
        (start to minOf(start + dash, 1.0)).takeIf { start < 1.0 && start >= 0.0 }
    }
}

@Composable
private fun ClearanceLegend(modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    Surface(modifier, shape = MaterialTheme.shapes.small, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PathClearance.entries.forEach { clearance ->
                ClearanceSwatch(androidx.compose.ui.graphics.Color(clearance.argb(dark)), clearance.title)
            }
        }
    }
}

/** Two rounded dashes in the clearance's colour, as on the map, then its name; shared with the chart legend. */
@Composable
internal fun ClearanceSwatch(color: androidx.compose.ui.graphics.Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp, 4.dp)) {
            val y = size.height / 2
            listOf(2f to 5f, 13f to 16f).forEach { (from, to) ->
                drawLine(color, androidx.compose.ui.geometry.Offset(from.dp.toPx(), y), androidx.compose.ui.geometry.Offset(to.dp.toPx(), y),
                    strokeWidth = size.height, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Text(title, style = MaterialTheme.typography.bodySmall)
    }
}
