package org.nodescope.android.feature.linkprofile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.feature.map.*

/**
 * The map's saveable state, held by the screen above its layout branches so the camera and an
 * unconfirmed tap survive folding, rotation and posture changes.
 */
internal class LinkProfileMapState(val camera: MutableState<List<Double>>, val tentative: MutableState<Tentative?>,
    /** Whether the opening view has been chosen; a restored camera is never re-framed. */
    val framed: MutableState<Boolean>,
    /** The A and B last fitted on screen, so a restored camera is not re-fitted to the same pair. */
    val fittedPath: MutableState<List<Double>?>) {
    /** An unconfirmed tap and the endpoint it was made for; it never carries over from A to B. */
    data class Tentative(val endpoint: String, val latitude: Double, val longitude: Double)
}

private val tentativeSaver = androidx.compose.runtime.saveable.listSaver<LinkProfileMapState.Tentative?, Any>(
    save = { it?.let { tap -> listOf(tap.endpoint, tap.latitude, tap.longitude) } ?: emptyList() },
    restore = { values -> if (values.size == 3) LinkProfileMapState.Tentative(values[0] as String, values[1] as Double, values[2] as Double) else null })

@Composable
internal fun rememberLinkProfileMapState(origin: ProfileCoordinate?): LinkProfileMapState {
    val camera = rememberSaveable { mutableStateOf(listOf(origin?.latitude ?: 0.0, origin?.longitude ?: 0.0, if (origin != null) 10.0 else 1.0, 0.0, 0.0)) }
    val tentative = rememberSaveable(stateSaver = tentativeSaver) { mutableStateOf<LinkProfileMapState.Tentative?>(null) }
    // An endpoint already centers the camera; otherwise the selected region frames it once loaded.
    val framed = rememberSaveable { mutableStateOf(origin != null) }
    val fittedPath = rememberSaveable { mutableStateOf<List<Double>?>(null) }
    return remember(camera, tentative, framed, fittedPath) { LinkProfileMapState(camera, tentative, framed, fittedPath) }
}

/** Persistent map: tentative taps are committed only by Use Point; cancel leaves inputs intact. */
@Composable
internal fun LinkProfileMap(endpoint: String?, pointA: ProfileCoordinate?, pointB: ProfileCoordinate?,
    nodes: List<org.nodescope.android.core.model.MeshNode>, onCancel: () -> Unit, onChoose: (ProfileCoordinate) -> Unit,
    modifier: Modifier = Modifier, mapState: LinkProfileMapState = rememberLinkProfileMapState(pointA ?: pointB),
    snapshot: org.nodescope.android.core.model.AnalyzerSnapshot? = null, selectedRegion: String? = null,
    sourceViewport: org.nodescope.android.core.model.RegionCoordinate? = null, analysis: LinkAnalysis? = null) {
    Surface(modifier) {
        Column(Modifier.fillMaxSize()) {
            var tentativePoint by mapState.tentative
            val current = tentativePoint?.takeIf { it.endpoint == endpoint }
            val latitude = current?.latitude
            val longitude = current?.longitude
            var camera by mapState.camera
            var retry by remember { mutableIntStateOf(0) }
            // The provider and style picked on the live map (CARTO or Google Maps).
            val context = LocalContext.current
            val choice = remember { loadMapProvider(context) }
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
            val engine: MapEngineState = if (choice.provider == MapProvider.GOOGLE) rememberGoogleMapEngine() else rememberMapLibreEngine()
            // With no endpoints yet, open on the region selected for the live map, framed the same way.
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            LaunchedEffect(engine.camera, snapshot, selectedRegion, sourceViewport) {
                val ready = engine.camera ?: return@LaunchedEffect
                if (mapState.framed.value || snapshot == null) return@LaunchedEffect
                val inset = (24 * density).toInt()
                when (val target = cameraTarget(snapshot, selectedRegion, sourceViewport)) {
                    null -> return@LaunchedEffect
                    is CameraTarget.Center -> ready.jump(CameraMove.Center(target.coordinate, target.zoom))
                    is CameraTarget.Bounds -> ready.jump(CameraMove.Fit(target, FramingInsets(inset, inset, inset, inset)))
                }
                mapState.framed.value = true
            }
            // Once both endpoints are chosen, show the whole path, clear of the legend and the pins above it.
            LaunchedEffect(engine.camera, pointA, pointB, endpoint) {
                val ready = engine.camera ?: return@LaunchedEffect
                if (endpoint != null || pointA == null || pointB == null) return@LaunchedEffect
                val path = listOf(pointA.latitude, pointA.longitude, pointB.latitude, pointB.longitude)
                if (mapState.fittedPath.value == path) return@LaunchedEffect
                val ends = listOfNotNull(Coordinate.gps(pointA.latitude, pointA.longitude), Coordinate.gps(pointB.latitude, pointB.longitude))
                if (ends.size < 2) return@LaunchedEffect
                val side = (40 * density).toInt()
                ready.animate(CameraMove.Fit(boundsOf(ends), FramingInsets(side, (88 * density).toInt(), side, (32 * density).toInt())))
                mapState.fittedPath.value = path
                mapState.framed.value = true
            }
            LaunchedEffect(engine.overlays, latitude, longitude, pointA, pointB, nodes, analysis, dark) {
                val a = pointA?.let { Coordinate.gps(it.latitude, it.longitude) }
                val b = pointB?.let { Coordinate.gps(it.latitude, it.longitude) }
                val tentative = latitude?.let { lat -> longitude?.let { lon -> Coordinate.gps(lat, lon) } }
                engine.overlays?.setNodes(nodeMarkers(nodes), grouped = true)
                engine.overlays?.setPins(listOfNotNull(
                    a?.let { MapPin(it, MapPin.Kind.POINT_A, "Point A") },
                    b?.let { MapPin(it, MapPin.Kind.POINT_B, "Point B") },
                    tentative?.let { MapPin(it, MapPin.Kind.TENTATIVE, "Tentative location") }))
                engine.overlays?.setLinkPath(if (a != null && b != null) linkPathStretches(a, b, analysis, dark) else emptyList())
            }
            Text(if (endpoint != null) "Choose Point $endpoint" else "Link endpoints", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
            if (endpoint != null) Text("Confirming a point sends its coordinates to Open-Meteo for elevation.",
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // A known node under the finger wins; otherwise the tapped spot, or a group expands.
                val onTap: (MapTap) -> Boolean = { tap ->
                    val node = nodes.firstOrNull { it.publicKey == tap.nodeKey }?.coordinate
                    val spot = node ?: tap.coordinate?.takeIf { tap.expandGroup == null }
                    if (endpoint != null && spot != null) { tentativePoint = LinkProfileMapState.Tentative(endpoint, spot.latitude, spot.longitude); true }
                    else { tap.expandGroup?.invoke(); tap.expandGroup != null }
                }
                when (engine) {
                    is MapLibreEngine -> MapLibreEngineHost(engine, choice.cartoStyle(dark), retry, 0, camera, { camera = it }, onTap, Modifier.fillMaxSize())
                    is GoogleMapEngine -> GoogleMapEngineHost(engine, choice.googleType, dark, 0, camera, { camera = it }, onTap, Modifier.fillMaxSize())
                }
                if (analysis != null && endpoint == null) ClearanceLegend(Modifier.align(Alignment.TopStart).padding(8.dp))
                if (engine.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (engine.failed) Surface(Modifier.align(Alignment.Center)) {
                    TextButton(onClick = { retry++ }) { Text("Map failed to load. Retry") }
                }
            }
            val uri = LocalUriHandler.current
            // Google draws its own logo and attribution on the map.
            if (engine is MapLibreEngine) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { uri.openUri("https://carto.com/attributions") }) { Text("© CARTO") }
                TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap") }
            }
            if (endpoint != null) {
                Text(latitude?.let { String.format(java.util.Locale.getDefault(), "%.6f, %.6f", it, longitude) } ?: "Tap the map or a known node", Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = {
                        val lat = latitude
                        val lon = longitude
                        if (lat != null && lon != null) onChoose(ProfileCoordinate(lat, lon))
                    }, enabled = latitude != null && longitude != null) { Text("Use Point $endpoint") }
                }
            }
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

private const val UnanalyzedBlue = 0xFF299EFF.toInt()

/**
 * The A–B line: one solid blue line until analyzed, then dashed throughout and coloured by
 * clearance, so a result never reads as the unanalyzed line. Samples are evenly spaced in latitude and longitude
 * ([LinkGeometry.samples]), so a distance along the path maps to the same fraction between A and B.
 */
internal fun linkPathStretches(a: Coordinate, b: Coordinate, analysis: LinkAnalysis?, dark: Boolean): List<PathStretch> {
    if (analysis == null) return listOf(PathStretch(listOf(a, b), UnanalyzedBlue, dashed = false))
    val length = analysis.result.distanceMeters.takeIf { it > 0 } ?: return listOf(PathStretch(listOf(a, b), UnanalyzedBlue, dashed = false))
    var delta = b.longitude - a.longitude
    if (delta > 180) delta -= 360
    if (delta < -180) delta += 360
    fun at(meters: Double): Coordinate? {
        val fraction = (meters / length).coerceIn(0.0, 1.0)
        var longitude = a.longitude + fraction * delta
        if (longitude > 180) longitude -= 360
        if (longitude < -180) longitude += 360
        return Coordinate.gps(a.latitude + fraction * (b.latitude - a.latitude), longitude)
    }
    return LinkProfileEngine.clearanceStretches(analysis.request).map { stretch ->
        PathStretch(listOfNotNull(at(stretch.startMeters), at(stretch.endMeters)), stretch.clearance.argb(dark), dashed = true)
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

/** A short dashed line in the clearance's colour, then its name; shared with the chart legend. */
@Composable
internal fun ClearanceSwatch(color: androidx.compose.ui.graphics.Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp, 4.dp)) {
            drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                strokeWidth = size.height, cap = androidx.compose.ui.graphics.StrokeCap.Butt,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx())))
        }
        Text(title, style = MaterialTheme.typography.bodySmall)
    }
}
