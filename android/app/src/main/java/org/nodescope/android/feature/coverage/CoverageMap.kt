package org.nodescope.android.feature.coverage

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.nodescope.android.core.model.AnalyzerSnapshot
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.RegionCoordinate
import org.nodescope.android.feature.map.*
import kotlin.math.hypot

/** The map's camera, held by the screen above its layout branches so folding and rotation keep it. */
internal class CoverageMapState(val camera: MutableState<List<Double>>, val framed: MutableState<Boolean>,
    /** The placement last centred on screen, so a restored or recomposed map never re-centres the same one. */
    val centered: MutableState<Long>)

@Composable
internal fun rememberCoverageMapState(origin: Coordinate?): CoverageMapState {
    val camera = rememberSaveable { mutableStateOf(listOf(origin?.latitude ?: 0.0, origin?.longitude ?: 0.0, if (origin != null) 10.0 else 1.0, 0.0, 0.0)) }
    val framed = rememberSaveable { mutableStateOf(origin != null) }
    val centered = rememberSaveable { mutableLongStateOf(0L) }
    return remember(camera, framed, centered) { CoverageMapState(camera, framed, centered) }
}

/**
 * Estimated Coverage's full-bleed map, as on iOS: a touch & hold places the transmitter (on a known
 * node, that node), known nodes are tinted by their prediction, and the result draws as margin bands.
 * [bottomInset] is the part covered by the phone panel; the credits, framing and Undo stay above it.
 */
@Composable
internal fun CoverageMap(model: EstimatedCoverageViewModel, modifier: Modifier, mapState: CoverageMapState,
    snapshot: AnalyzerSnapshot?, selectedRegion: String?, sourceViewport: RegionCoordinate?,
    bottomInset: Dp, onPlacementSettled: (Long) -> Unit) {
    Box(modifier.semantics { contentDescription = "Map for choosing the transmitter location" }) {
        var camera by mapState.camera
        val context = LocalContext.current
        val uri = LocalUriHandler.current
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current.density
        var choice by remember { mutableStateOf(loadMapProvider(context)) }
        var layersOpen by remember { mutableStateOf(false) }
        var retry by remember { mutableIntStateOf(0) }
        val dark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.3f
        val engine: MapEngineState = if (choice.provider == MapProvider.GOOGLE) rememberGoogleMapEngine() else rememberMapLibreEngine()
        val insetPx = (bottomInset.value * density).toInt()
        val transmitter = model.coordinate
        val nodes = model.knownNodes
        val statuses = model.nodeStatuses
        val layer = model.layer?.takeIf { it.executionID == model.activeResult?.executionID }
        var overlayFailure by remember { mutableStateOf<String?>(null) }
        CoverageMapAttachment(engine, layer) { overlayFailure = it }

        // With no transmitter yet, open on the region selected for the live map, framed the same way.
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
        // A new placement centres the transmitter at a zoom that shows the study area; then the
        // phone panel may open on its settings.
        LaunchedEffect(engine.camera, model.placementID) {
            val ready = engine.camera ?: return@LaunchedEffect
            val placement = model.placementID
            if (placement == 0L || mapState.centered.value == placement) return@LaunchedEffect
            val point = model.coordinate ?: return@LaunchedEffect
            mapState.centered.value = placement
            ready.animate(CameraMove.Center(point, studyZoom(model.radiusMeters)))
            mapState.framed.value = true
            onPlacementSettled(placement)
        }
        LaunchedEffect(engine.overlays, nodes, statuses, transmitter, model.transmitterName) {
            engine.overlays?.setPins(nodes.mapNotNull { node ->
                node.coordinate?.let { point ->
                    val status = statuses[node.publicKey]
                    MapPin(point, status.pinKind(), node.displayName + (status?.let { " · ${it.coverageLabel()}" } ?: ""))
                }
            } + listOfNotNull(transmitter?.let { MapPin(it, MapPin.Kind.TRANSMITTER, model.transmitterName ?: "Transmitter") }))
        }
        // A known node under the finger wins over the spot itself.
        val onLongPress: (MapTap) -> Unit = { tap ->
            val spot = tap.coordinate
            if (spot != null) {
                val node = engine.camera?.let { ready -> nodeNear(spot, nodes, ready, 28 * density) }
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                model.place(node?.coordinate ?: spot, node?.name?.takeIf { it.isNotBlank() })
            }
        }
        when (engine) {
            is MapLibreEngine -> MapLibreEngineHost(engine, choice.cartoStyle(dark), retry, insetPx, camera, { camera = it }, { false },
                Modifier.fillMaxSize(), onLongPress = onLongPress)
            is GoogleMapEngine -> GoogleMapEngineHost(engine, choice.googleType, dark, insetPx, camera, { camera = it }, { false },
                Modifier.fillMaxSize(), hidesBusinesses = true, onLongPress = onLongPress)
        }
        // Prompts live in the header; the map only carries the legend once there is a result.
        if (layer != null) Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp, shadowElevation = 2.dp) {
            Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) { CoverageMarginLegend() }
        }
        overlayFailure?.let { message ->
            Surface(Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 16.dp, end = 16.dp), shape = MaterialTheme.shapes.medium) {
                Text(message, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                IconButton(onClick = { layersOpen = true }) { Icon(Icons.Outlined.Layers, "Map layers") }
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
        // A few seconds to take back a placement, move or removal, above the attribution.
        model.undo?.let { offer ->
            LaunchedEffect(offer.id) {
                kotlinx.coroutines.delay(4_000)
                model.dismissUndo(offer.id)
            }
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp)
                .padding(bottom = bottomInset + if (engine is MapLibreEngine) 44.dp else 12.dp),
                action = { TextButton(onClick = model::undoLastChange) { Text("Undo", color = SnackbarDefaults.actionColor) } }) { Text(offer.message) }
        }
        // Google draws its own logo and attribution on the map.
        if (engine is MapLibreEngine) Row(Modifier.align(Alignment.BottomEnd).padding(bottom = bottomInset)) {
            val style = MaterialTheme.typography.labelSmall
            TextButton(onClick = { uri.openUri("https://carto.com/attributions") }) { Text("© CARTO", style = style) }
            TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap", style = style) }
        }
    }
}

/** iOS marker tints: amber or green above threshold by headroom, red below, blue not evaluated, grey unknown. */
private fun CoverageNodeStatus?.pinKind(): MapPin.Kind = when (this?.state) {
    null, CoverageNodeState.notEvaluated -> MapPin.Kind.COVERAGE_PENDING
    CoverageNodeState.inCoverage -> if ((adjustedMarginDb ?: 0.0) <= 10) MapPin.Kind.COVERAGE_LIMITED else MapPin.Kind.COVERAGE_STRONG
    CoverageNodeState.belowThreshold -> MapPin.Kind.COVERAGE_BELOW
    CoverageNodeState.unknown, CoverageNodeState.stale -> MapPin.Kind.COVERAGE_UNKNOWN
}

/** The known node whose pin covers [spot]: a pin stands above its tip, as in Link Profile. */
private fun nodeNear(spot: Coordinate, nodes: List<MeshNode>, camera: MapCamera, reach: Float): MeshNode? {
    val (x, y) = camera.toScreen(spot)
    return nodes.mapNotNull { node ->
        val point = node.coordinate ?: return@mapNotNull null
        val (px, py) = camera.toScreen(point)
        val dx = x - px
        val dy = y - py
        // The pin's head sits about 20 dp above its tip.
        if (kotlin.math.abs(dx) <= reach * 0.75f && dy in -reach * 1.7f..reach * 0.45f) node to hypot(dx, dy + reach * 0.7f) else null
    }.minByOrNull { it.second }?.first
}

/** A zoom that shows the study area around the transmitter. */
internal fun studyZoom(radiusMeters: Int): Double = when {
    radiusMeters <= 5000 -> 11.0
    radiusMeters <= 20_000 -> 9.0
    radiusMeters <= 50_000 -> 8.0
    else -> 7.0
}
