package org.nodescope.android.feature.coverage

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.withDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import org.nodescope.android.core.design.LocalDistanceUnit
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.design.formatDistance
import java.util.Locale
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
    val resources = LocalResources.current
    val transmitterLabel = stringResource(R.string.coverage_transmitter)
    val notEvaluatedLabel = stringResource(R.string.coverage_not_evaluated_2)
    Box(modifier.withDescription(stringResource(R.string.coverage_map_for_choosing_the_transmitter_location)) { description -> contentDescription = description }) {
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
        // Selection belongs to this exact result; changing inputs or replacing the layer clears it.
        var inspection by remember(layer?.executionID, transmitter, nodes, statuses) { mutableStateOf<CoverageMapInspection?>(null) }
        val unit = LocalDistanceUnit.current
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
        // A completed or restored study owns its initial camera, as on iOS.
        LaunchedEffect(engine.camera, layer?.executionID) {
            val ready = engine.camera ?: return@LaunchedEffect
            val current = layer ?: return@LaunchedEffect
            val center = Coordinate.valid(current.raster.transmitter.latitude, current.raster.transmitter.longitude)
                ?: return@LaunchedEffect
            ready.animate(CameraMove.Center(center, studyZoom(current.raster.grid.radiusMeters)))
            mapState.framed.value = true
        }
        LaunchedEffect(engine.overlays, nodes, statuses, transmitter, model.transmitterName, inspection) {
            engine.overlays?.setPins(nodes.mapNotNull { node ->
                node.coordinate?.let { point ->
                    val status = statuses[node.publicKey]
                    MapPin(point, status.pinKind(), node.displayName + (status?.let { " · ${it.coverageLabel().resolve(resources)}" } ?: ""))
                }
            } + listOfNotNull(transmitter?.let { MapPin(it, MapPin.Kind.TRANSMITTER, model.transmitterName ?: transmitterLabel) }) + listOfNotNull(inspection?.pin))
        }
        // A known node under the finger wins over the spot itself.
        val onLongPress: (MapTap) -> Unit = { tap ->
            val spot = tap.coordinate
            if (spot != null) {
                val node = engine.camera?.let { ready -> nodeNear(spot, nodes, ready, 28 * density) }
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                inspection = null
                model.place(node?.coordinate ?: spot, node?.name?.takeIf { it.isNotBlank() })
            }
        }
        val onTap: (MapTap) -> Boolean = { tap ->
            val point = tap.coordinate
            val camera = engine.camera
            if (point == null || camera == null) false else {
                val node = nodeNear(point, nodes, camera, 28 * density)
                inspection = if (node != null) {
                    val status = statuses[node.publicKey]
                    val margin = status?.adjustedMarginDb
                    val budget = model.activeResult?.budget
                    CoverageMapInspection(node.displayName, status?.coverageLabel()?.resolve(resources) ?: notEvaluatedLabel,
                        margin?.let { value -> budget?.let { value + it.sensitivityDbm + it.reserveDb } }, margin)
                } else if (transmitter != null && pinNear(point, transmitter, camera, 28 * density)) {
                    CoverageMapInspection(model.transmitterName ?: transmitterLabel, transmitterLabel)
                } else {
                    layer?.let { current ->
                        coverageSampleInspection(current.raster.sample(point.latitude, point.longitude),
                            model.activeResult?.budget, point, current.raster.grid.spacingMeters)
                    }
                }
                true
            }
        }
        when (engine) {
            is MapLibreEngine -> MapLibreEngineHost(engine, choice.cartoStyle(dark), retry, insetPx, camera, { camera = it }, onTap,
                Modifier.fillMaxSize(), onLongPress = onLongPress)
            is GoogleMapEngine -> GoogleMapEngineHost(engine, choice.googleType, dark, insetPx, camera, { camera = it }, onTap,
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
                IconButton(onClick = { layersOpen = true }) { Icon(Icons.Outlined.Layers, stringResource(R.string.coverage_map_layers)) }
            }
            MapLayersMenu(layersOpen, { layersOpen = false }, choice, dark) {
                choice = it
                saveMapProvider(context, it)
                layersOpen = false
            }
        }
        if (engine.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (engine.failed) Surface(Modifier.align(Alignment.Center)) {
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.coverage_map_failed_to_load_retry)) }
        }
        // A few seconds to take back a placement, move or removal, above the attribution.
        model.undo?.let { offer ->
            LaunchedEffect(offer.id) {
                kotlinx.coroutines.delay(4_000)
                model.dismissUndo(offer.id)
            }
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp)
                .padding(bottom = bottomInset + if (engine is MapLibreEngine) 44.dp else 12.dp),
                action = { TextButton(onClick = model::undoLastChange) { Text(stringResource(R.string.coverage_undo), color = SnackbarDefaults.actionColor) } }) { Text(offer.message.asString()) }
        }
        inspection?.let { selected ->
            Surface(Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomInset + (if (engine is MapLibreEngine) 44.dp else 12.dp)
                    + if (model.undo != null) 64.dp else 0.dp)
                .widthIn(max = 320.dp), shape = MaterialTheme.shapes.large,
                tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { inspection = null }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.coverage_close_sample_details))
                        }
                    }
                    selected.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    selected.receiveDbm?.let {
                        CoverageInspectionValue(stringResource(R.string.coverage_predicted_rx), String.format(Locale.US, "%.1f dBm", it))
                    }
                    selected.marginDb?.let {
                        CoverageInspectionValue(stringResource(R.string.coverage_margin_after_reserve), String.format(Locale.US, "%.1f dB", it))
                    }
                    selected.spacingMeters?.let {
                        CoverageInspectionValue(stringResource(R.string.coverage_grid_spacing), formatDistance(it / 1000.0, unit))
                    }
                }
            }
        }
        // Google draws its own logo and attribution on the map.
        if (engine is MapLibreEngine) Row(Modifier.align(Alignment.BottomEnd).padding(bottom = bottomInset)) {
            val style = MaterialTheme.typography.labelSmall
            TextButton(onClick = { uri.openUri("https://carto.com/attributions") }) { Text(stringResource(R.string.common_carto_attribution), style = style) }
            TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }) { Text(stringResource(R.string.common_openstreetmap_attribution), style = style) }
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

/** Display-only selection; RF values always come from the completed study, never current edits. */
internal data class CoverageMapInspection(val title: String, val status: String? = null,
    val receiveDbm: Double? = null, val marginDb: Double? = null, val spacingMeters: Int? = null,
    val pin: MapPin? = null)

internal fun CoverageRasterSample.pinKind(): MapPin.Kind = when (state) {
    CoverageRasterClass.aboveThreshold -> MapPin.Kind.COVERAGE_STRONG
    CoverageRasterClass.limitedHeadroom -> MapPin.Kind.COVERAGE_LIMITED
    CoverageRasterClass.belowThreshold -> MapPin.Kind.COVERAGE_BELOW
    else -> MapPin.Kind.COVERAGE_UNKNOWN
}

private fun pinNear(point: Coordinate, pin: Coordinate, camera: MapCamera, reach: Float): Boolean {
    val (x, y) = camera.toScreen(point)
    val (px, py) = camera.toScreen(pin)
    return kotlin.math.abs(x - px) <= reach * 0.75f && y - py in -reach * 1.7f..reach * 0.45f
}

internal fun coverageSampleInspection(sample: CoverageRasterSample, budget: CoverageLinkBudget?,
    coordinate: Coordinate, spacingMeters: Int): CoverageMapInspection? {
    if (sample.state == CoverageRasterClass.outsideAnalysis) return null
    val reception = sample.lossDb?.let { budget?.reception(it) }
    return CoverageMapInspection("Nearby grid sample", if (reception == null) "No usable prediction" else null,
        reception?.receiveLevelDbm, reception?.adjustedMarginDb, spacingMeters,
        MapPin(coordinate, sample.pinKind(), "Nearby grid sample"))
}

@Composable
private fun CoverageInspectionValue(label: String, value: String) {
    Text(buildAnnotatedString {
        append("$label: ")
        withStyle(SpanStyle(fontFamily = MonaspaceNeon)) { append(value) }
    }, style = MaterialTheme.typography.bodyMedium)
}
