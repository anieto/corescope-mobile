package org.nodescope.android.feature.linkprofile

import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.core.design.meshCardColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import org.nodescope.android.core.design.LocalDistanceUnit
import org.nodescope.android.core.design.formatDistance
import org.nodescope.android.core.storage.DistanceUnit
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nodescope.android.core.design.meshPageBackground
import org.nodescope.android.core.design.PaneGap
import org.nodescope.android.core.design.firstPaneWidth
import org.nodescope.android.core.design.rememberFoldSplit
import org.nodescope.android.core.design.rememberTabletopSplit
import androidx.compose.ui.platform.LocalDensity
import org.nodescope.android.BuildConfig
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkProfileScreen(model: LinkProfileViewModel, distanceUnit: DistanceUnit = LocalDistanceUnit.current, nodes: List<org.nodescope.android.core.model.MeshNode> = emptyList(),
    snapshot: org.nodescope.android.core.model.AnalyzerSnapshot? = null, selectedRegion: String? = null,
    sourceViewport: org.nodescope.android.core.model.RegionCoordinate? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    var selecting by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(if (state.fields.getValue("Latitude A").isBlank() && BuildConfig.MAPS_CONFIGURED) "A" else null) }
    fun coordinate(endpoint: String): ProfileCoordinate? = runCatching {
        ProfileCoordinate(state.fields.getValue("Latitude $endpoint").toDouble(), state.fields.getValue("Longitude $endpoint").toDouble())
    }.getOrNull()
    androidx.activity.compose.BackHandler(enabled = selecting != null) { selecting = null }
    // Held here, above the layout branches below, so folding, rotating or changing posture keeps
    // the camera, an unconfirmed tap and the scroll position (rememberSaveable restores by position).
    val mapState = rememberLinkProfileMapState(coordinate("A") ?: coordinate("B"))
    val scroll = rememberScrollState()
    val currentNodes by rememberUpdatedState(nodes)
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentRegion by rememberUpdatedState(selectedRegion)
    val currentViewport by rememberUpdatedState(sourceViewport)
    val currentAnalysis by rememberUpdatedState(state.result)
    val currentSelecting by rememberUpdatedState(selecting)
    var mapInset by remember { mutableStateOf(0.dp) }
    // One map instance moves between layouts on a live posture change instead of being rebuilt.
    val map = remember {
        movableContentOf { modifier: Modifier ->
            LinkProfileMap(currentSelecting, coordinate("A"), coordinate("B"), currentNodes, modifier, mapState,
                currentSnapshot, currentRegion, currentViewport, currentAnalysis, mapInset)
        }
    }
    fun choose(endpoint: String) {
        mapState.tentative.value = null
        selecting = endpoint
    }
    val header: @Composable () -> Unit = {
        LinkHeader(state, selecting, mapState.tentative.value?.takeIf { it.endpoint == selecting }, distanceUnit,
            onChoose = ::choose,
            onCancel = { mapState.tentative.value = null; selecting = null },
            onUse = { tap ->
                model.place(tap.endpoint, ProfileCoordinate(tap.latitude, tap.longitude), tap.name)
                mapState.tentative.value = null
                selecting = if (tap.endpoint == "A" && coordinate("B") == null) "B" else null
            },
            onRetry = model::analyze)
    }
    val details: @Composable () -> Unit = {
        LinkDetails(model, state, distanceUnit, onMove = ::choose, onStartOver = {
            model.startOver()
            mapState.fittedPath.value = null
            if (BuildConfig.MAPS_CONFIGURED) choose("A") else selecting = null
        })
    }
    val fold = rememberFoldSplit()
    val tabletop = rememberTabletopSplit()
    BoxWithConstraints(Modifier.fillMaxSize().then(fold.modifier).then(tabletop.modifier)) {
        val wide = maxWidth >= if (LocalDensity.current.fontScale >= 1.5f) 1000.dp else 840.dp
        // Unfolded: the panes meet at the crease, the gap between them sitting on the hinge.
        val editorAtFold = fold.firstPaneWidth(maxWidth, 280.dp)?.minus(PaneGap / 2)
        val mapAboveFold = tabletop.offset?.takeIf { it >= 160.dp && maxHeight - it >= 160.dp }?.minus(PaneGap / 2)
        // Side and lower panels: the header stays put while everything under it scrolls.
        val panel: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.meshPageBackground()) {
                Box(Modifier.padding(16.dp)) { header() }
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 16.dp)) { details() }
            }
        }
        if (!BuildConfig.MAPS_CONFIGURED) {
            SideEffect { mapInset = 0.dp }
            panel(Modifier.fillMaxSize())
        } else if (editorAtFold != null) {
            SideEffect { mapInset = 0.dp }
            // Editor before the hinge, map after it; the editor is not squeezed into a side column.
            Row(Modifier.fillMaxSize()) {
                panel(Modifier.width(editorAtFold).fillMaxHeight())
                Spacer(Modifier.width(PaneGap))
                map(Modifier.weight(1f).fillMaxHeight())
            }
        } else if (mapAboveFold != null) {
            SideEffect { mapInset = 0.dp }
            // Tabletop: map on the upper half, controls on the lower, nothing under the crease.
            Column(Modifier.fillMaxSize()) {
                map(Modifier.height(mapAboveFold).fillMaxWidth())
                Spacer(Modifier.height(PaneGap))
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        } else if (wide) {
            SideEffect { mapInset = 0.dp }
            // Tablets and wide windows: the editor first, sized as on iPad; the map takes the larger right side.
            val editorWidth = (maxWidth * 0.34f).coerceIn(340.dp, 420.dp)
            Row(Modifier.fillMaxSize()) {
                panel(Modifier.width(editorWidth).fillMaxHeight())
                map(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Phones: the map fills the screen under a sheet. Collapsed, the sheet is just its
            // header (the prompt, or the verdict); pulled up, it holds the points, radio and results.
            val sheet = rememberBottomSheetScaffoldState()
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            var headerHeight by remember { mutableStateOf(0.dp) }
            val peek = headerHeight + 48.dp
            val sheetMax = maxHeight - 48.dp
            SideEffect { mapInset = peek }
            // Choosing a point needs the map: lower the sheet out of the way.
            LaunchedEffect(selecting) { if (selecting != null) sheet.bottomSheetState.partialExpand() }
            // The peek follows the header, which is only measured after the first frame; a
            // collapsed sheet does not move to a new peek by itself, so settle it again.
            LaunchedEffect(peek) {
                if (sheet.bottomSheetState.currentValue == SheetValue.PartiallyExpanded) sheet.bottomSheetState.partialExpand()
            }
            // The sheet carries the page's mesh, as the side panels and iOS's panel do; the
            // header stays pinned while the points, radio and results scroll under it.
            BottomSheetScaffold(
                scaffoldState = sheet,
                sheetPeekHeight = peek,
                sheetContainerColor = Color.Transparent,
                sheetShadowElevation = 8.dp,
                sheetDragHandle = null,
                sheetContent = {
                    Column(Modifier.fillMaxWidth().heightIn(max = sheetMax)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).meshPageBackground()) {
                        // Drawn here so it sits on the mesh; tapping it expands or collapses the
                        // sheet, as the scaffold's own handle would.
                        val expanded = sheet.bottomSheetState.currentValue == SheetValue.Expanded
                        Box(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Collapse" else "Expand") {
                            scope.launch { if (expanded) sheet.bottomSheetState.partialExpand() else sheet.bottomSheetState.expand() }
                        }.semantics { contentDescription = if (expanded) "Collapse results" else "Expand results" },
                            contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
                        Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                            .onSizeChanged { headerHeight = with(density) { it.height.toDp() + 12.dp } }) { header() }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                            details()
                        }
                    }
                },
            ) { map(Modifier.fillMaxSize()) }
        }
    }
}

/**
 * The sheet's always-visible line: what to do next while points are being placed, then the
 * verdict. Choosing a point is confirmed here, so the map stays clear.
 */
@Composable
private fun LinkHeader(state: LinkProfileViewModel.State, selecting: String?, tentative: LinkProfileMapState.Tentative?,
    distanceUnit: DistanceUnit, onChoose: (String) -> Unit, onCancel: () -> Unit,
    onUse: (LinkProfileMapState.Tentative) -> Unit, onRetry: () -> Unit) {
    fun placed(endpoint: String) = state.fields.getValue("Latitude $endpoint").isNotBlank() && state.fields.getValue("Longitude $endpoint").isNotBlank()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val result = state.result
        when {
            selecting != null -> {
                Text("Choose Point $selecting", style = MaterialTheme.typography.titleMedium)
                Text(when {
                    tentative == null -> "Tap the map or a known node."
                    tentative.name != null -> tentative.name
                    else -> "Dropped pin"
                }, color = secondary)
                Text("Confirming sends its coordinates to Open-Meteo for elevation.", style = MaterialTheme.typography.bodySmall, color = secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { tentative?.let(onUse) }, enabled = tentative != null) { Text("Use Point $selecting") }
                }
            }
            !placed("A") || !placed("B") -> {
                val next = if (!placed("A")) "A" else "B"
                Text("Place Point A and Point B to analyze the link.", style = MaterialTheme.typography.titleMedium)
                if (BuildConfig.MAPS_CONFIGURED) Button(onClick = { onChoose(next) }) { Text("Choose Point $next") }
            }
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Analyzing link…", style = MaterialTheme.typography.titleMedium)
            }
            state.error != null -> {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Try Again") }
            }
            result != null -> {
                val (title, icon) = verdict(result.result.clearanceStatus)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, null, Modifier.size(22.dp), tint = Color(result.result.clearanceStatus.pathClearance.argb(
                        MaterialTheme.colorScheme.background.luminance() < 0.3f)))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                val margin = result.result.linkMarginDB?.let { "Link margin ${String.format(Locale.getDefault(), "%.1f", it)} dB · " }.orEmpty()
                Text(margin + formatDistance(result.result.distanceMeters / 1000, distanceUnit), color = secondary)
            }
            else -> Text("Analyzing link…", style = MaterialTheme.typography.titleMedium)
        }
    }
}

internal fun verdict(status: ClearanceStatus) = when (status) {
    ClearanceStatus.CLEAR -> "Clear terrain path" to Icons.Filled.CheckCircle
    ClearanceStatus.MARGINAL -> "Marginal clearance" to Icons.Filled.Error
    ClearanceStatus.PARTIAL_OBSTRUCTION -> "Partially obstructed" to Icons.Filled.Contrast
    ClearanceStatus.BLOCKED -> "Blocked by terrain" to Icons.Filled.Block
}

/** The verdict's colour, matching the path: both obstructed statuses read as obstructed. */
private val ClearanceStatus.pathClearance get() = when (this) {
    ClearanceStatus.CLEAR -> PathClearance.CLEAR
    ClearanceStatus.MARGINAL -> PathClearance.MARGINAL
    ClearanceStatus.PARTIAL_OBSTRUCTION, ClearanceStatus.BLOCKED -> PathClearance.OBSTRUCTED
}

/** Everything under the header: the two points, the radio, the full result, and Start Over. */
@Composable
private fun LinkDetails(model: LinkProfileViewModel, state: LinkProfileViewModel.State, distanceUnit: DistanceUnit,
    onMove: (String) -> Unit, onStartOver: () -> Unit) {
    var customizing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("A", "B").forEach { endpoint -> PointCard(model, state, endpoint, distanceUnit, onMove) }
        Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Radio settings", style = MaterialTheme.typography.titleMedium)
                // Editing a value below makes the radio custom; neither preset is then selected.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("US/Canada Narrow", "EU/UK Narrow").forEach { name ->
                        FilterChip(selected = state.preset == name, onClick = { model.preset(name) }, label = { Text(name) })
                    }
                }
                TextButton(onClick = { customizing = !customizing }) {
                    Text(when {
                        customizing -> "Hide radio values"
                        state.preset == "Custom" -> "Customize radio · edited"
                        else -> "Customize radio"
                    })
                }
                if (customizing) LinkProfileViewModel.presetKeys.forEach { key -> ProfileField(key, state.fields.getValue(key)) { model.edit(key, it) } }
            }
        }
        state.result?.let { ProfileResult(it, distanceUnit) }
        if (listOf("Latitude A", "Longitude A", "Latitude B", "Longitude B").any { state.fields.getValue(it).isNotBlank() }) {
            // A primary-coloured outline: the tonal fill nearly vanished on the light mesh.
            OutlinedButton(onClick = onStartOver, Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start Over")
            }
        }
        Text("Planning estimates only: terrain does not account for buildings, foliage or interference. Placing points sends their coordinates to Open-Meteo for elevation; cached elevations are kept for 30 days.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One endpoint: its name (the known node, or a dropped pin), ground elevation and antenna height.
 * Tapping opens the antenna settings, moving the point, and typed coordinates.
 */
@Composable
private fun PointCard(model: LinkProfileViewModel, state: LinkProfileViewModel.State, endpoint: String, distanceUnit: DistanceUnit, onMove: (String) -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(endpoint) { mutableStateOf(false) }
    // Without a map, typing is the only way to place a point.
    var coordinatesShown by androidx.compose.runtime.saveable.rememberSaveable(endpoint) { mutableStateOf(!BuildConfig.MAPS_CONFIGURED) }
    val placed = state.fields.getValue("Latitude $endpoint").isNotBlank() && state.fields.getValue("Longitude $endpoint").isNotBlank()
    val meters = state.fields.getValue("Height $endpoint (m)").toDoubleOrNull() ?: 0.0
    val elevation = state.elevations[endpoint]
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.semantics(mergeDescendants = true) {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).background(if (endpoint == "A") PointABlue else PointBOrange, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(endpoint, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(when {
                        !placed -> "Point $endpoint not placed"
                        else -> state.names[endpoint] ?: "Dropped pin"
                    }, style = MaterialTheme.typography.titleMedium)
                    val ground = when {
                        elevation?.loading == true -> "Ground elevation: Loading…"
                        elevation?.meters != null -> "Ground elevation: ${formatProfileHeight(elevation.meters, distanceUnit)}"
                        placed -> "Ground elevation: Unavailable"
                        else -> null
                    }
                    Text(listOfNotNull(ground, "Antenna ${formatProfileHeight(meters, distanceUnit)}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = secondary)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                ProfileHeightControl(endpoint, meters, distanceUnit) { model.edit("Height $endpoint (m)", it.toString()) }
                val height = state.fields.getValue("Height $endpoint (m)").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                val total = elevation?.meters?.let { ground -> height?.let { ground + it } }
                Text("Total height: ${total?.let { formatProfileHeight(it, distanceUnit) } ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                listOf("Gain $endpoint (dBi)", "Loss $endpoint (dB)").forEach { key -> ProfileField(key, state.fields.getValue(key)) { model.edit(key, it) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (BuildConfig.MAPS_CONFIGURED) TextButton(onClick = { onMove(endpoint) }) { Text(if (placed) "Move on map" else "Choose on map") }
                    TextButton(onClick = { coordinatesShown = !coordinatesShown }) { Text(if (coordinatesShown) "Hide coordinates" else "Enter coordinates") }
                }
                if (coordinatesShown) listOf("Latitude $endpoint", "Longitude $endpoint").forEach { key -> ProfileField(key, state.fields.getValue(key)) { model.edit(key, it) } }
            }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, edit: (String) -> Unit) {
    OutlinedTextField(value, edit, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth())
}


@Composable
private fun ProfileResult(analysis: LinkAnalysis, distanceUnit: DistanceUnit) {
    val result = analysis.result
    fun dB(value: Double, digits: Int = 1) = String.format(Locale.getDefault(), "%.${digits}f", value)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = meshCardColor().copy(alpha = 1f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TerrainChart(analysis, distanceUnit)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricRow("Distance", formatDistance(result.distanceMeters / 1000, distanceUnit))
                MetricRow("Initial bearing", "${dB(analysis.bearing, 0)}° true")
                MetricRow("Free-space path loss", "${dB(result.freeSpacePathLossDB)} dB")
                MetricRow("Diffraction loss", "${dB(result.peakDiffractionLossDB)} dB")
                MetricRow("Total path loss", "${dB(result.totalPathLossDB)} dB")
                result.receivePowerDBm?.let { MetricRow("Predicted receive level", "${dB(it)} dBm") }
                result.linkMarginDB?.let { MetricRow("Link margin", "${dB(it)} dB") }
                MetricRow("Worst Fresnel clearance", "${dB(result.worstClearancePercent, 0)}%")
                MetricRow("Terrain source", "Copernicus GLO-90 · Open-Meteo")
                MetricRow("Calculated", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(analysis.calculatedAt)))
            }
            HorizontalDivider()
            Text("Assumptions", style = MaterialTheme.typography.titleSmall)
            val request = analysis.request
            Text(request.linkBudget?.let { budget ->
                "TX power ${dB(budget.transmitPowerDBm)} dBm; antenna gains ${dB(budget.gainADB)}/${dB(budget.gainBDB)} dBi; " +
                    "system losses ${dB(budget.lossADB)}/${dB(budget.lossBDB)} dB; receiver sensitivity ${dB(budget.receiverSensitivityDBm)} dBm; " +
                    "standard atmospheric refraction (k = 4/3)."
            } ?: "No complete link budget was supplied.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Model: ${LinkProfileEngine.ENGINE_ID} v${LinkProfileEngine.ENGINE_VERSION}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(onClick = { uri.openUri("https://open-meteo.com/en/docs/elevation-api") }) { Text("Terrain data attribution") }
        }
    }
}

/** A label/value row as on iOS: secondary label, value aligned to the trailing edge. */
@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

// This terrain-profile renderer is adapted from MeshCore One by Avi0n, via NodeScope iOS:
// https://github.com/Avi0n/MeshCoreOne
// Upstream commit: 9fba8e45ca14c38f4ed20e5ede157944fa1c246d
// Source: MC1/Views/Tools/LineOfSight/TerrainProfileCanvas.swift and
// MC1/Views/Tools/LineOfSight/ChartCoordinateSpace.swift
// Licensed under GNU GPL v3.0. Modified for NodeScope's analysis types,
// styling, accessibility, endpoint-only workflow, and adaptive layouts.
private class ChartSample(val distanceMeters: Double, val terrainMeters: Double, val lineOfSightMeters: Double, val fresnelRadiusMeters: Double) {
    val fresnelTop get() = lineOfSightMeters + fresnelRadiusMeters
    val fresnelBottom get() = lineOfSightMeters - fresnelRadiusMeters
    val fresnelTop60 get() = lineOfSightMeters + fresnelRadiusMeters * 0.6
    val fresnelBottom60 get() = lineOfSightMeters - fresnelRadiusMeters * 0.6
    /** Fresnel bands stop at the ground rather than being drawn through it. */
    val visibleFresnelBottom get() = terrainMeters.coerceAtLeast(fresnelBottom).coerceAtMost(fresnelTop)
    val visibleFresnelBottom60 get() = terrainMeters.coerceAtLeast(fresnelBottom60).coerceAtMost(fresnelTop60)
    val isObstructed get() = terrainMeters > fresnelBottom60
}

private val TerrainBrown = Color(0xFFA2845E)
private val FresnelTeal = Color(0xFF30B0C7)
private val PointABlue = Color(0xFF007AFF)
private val PointBOrange = Color(0xFFFF9500)

@Composable
private fun TerrainChart(analysis: LinkAnalysis, distanceUnit: DistanceUnit) {
    val points = analysis.terrain.points
    val request = analysis.request
    val length = analysis.result.distanceMeters
    val stretches = remember(analysis) { LinkProfileEngine.clearanceStretches(request) }
    val samples = remember(analysis) {
        val start = points.first().elevationMeters + request.heightAMeters
        val end = points.last().elevationMeters + request.heightBMeters
        points.map { point ->
            val a = point.distanceMeters
            val b = (length - a).coerceAtLeast(0.0)
            ChartSample(a, point.elevationMeters + LinkProfileEngine.earthBulge(a, b, request.refractionK),
                start + (if (length > 0) a / length else 0.0) * (end - start), LinkProfileEngine.fresnelRadius(request.frequencyMHz, a, b))
        }
    }
    // The terrain sets the floor; the Fresnel zone's top sets the ceiling, with headroom for both.
    val minimum = samples.minOf { it.terrainMeters }
    val maximum = samples.maxOf { it.fresnelTop }
    val span = maxOf(10.0, maximum - minimum)
    val low = minimum - span * 0.12
    val high = maximum + span * 0.2
    val xMax = maxOf(1.0, samples.last().distanceMeters)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 9.sp, color = secondary)
    val markerStyle = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Terrain profile", style = MaterialTheme.typography.titleSmall)
        Canvas(Modifier.fillMaxWidth().height(240.dp).semantics {
            contentDescription = "Terrain elevation, line of sight, first Fresnel zone, and obstructed regions. " +
                "Distance ${formatDistance(length / 1000, distanceUnit)}. Worst Fresnel clearance ${analysis.result.worstClearancePercent.roundToInt()} percent."
        }) {
            val left = 42.dp.toPx(); val right = 14.dp.toPx(); val top = 22.dp.toPx(); val bottom = 28.dp.toPx()
            val plotWidth = (size.width - left - right).coerceAtLeast(1f)
            val plotHeight = (size.height - top - bottom).coerceAtLeast(1f)
            fun point(x: Double, y: Double) = Offset(left + plotWidth * (x / xMax).toFloat(), size.height - bottom - plotHeight * ((y - low) / (high - low)).toFloat())
            fun line(value: (ChartSample) -> Double) = Path().apply {
                samples.forEachIndexed { index, sample -> point(sample.distanceMeters, value(sample)).let { if (index == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            }
            fun band(upper: (ChartSample) -> Double, lower: (ChartSample) -> Double) = line(upper).apply {
                samples.asReversed().forEach { sample -> point(sample.distanceMeters, lower(sample)).let { lineTo(it.x, it.y) } }
                close()
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            // Grid with elevation labels (left) and distance labels (below), in the app's units.
            for (index in 0..4) {
                val y = low + (high - low) * index / 4
                drawLine(secondary.copy(alpha = 0.22f), point(0.0, y), point(xMax, y), 0.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                val height = profileDisplayHeight(y, distanceUnit).toInt()
                val label = measurer.measure(if (index == 4) "$height ${if (distanceUnit == DistanceUnit.IMPERIAL) "ft" else "m"}" else "$height", axisStyle)
                val at = point(0.0, y)
                drawText(label, topLeft = Offset(at.x - label.size.width - 3.dp.toPx(), at.y - label.size.height / 2f))
            }
            for (index in 0..4) {
                val x = xMax * index / 4
                val distance = if (distanceUnit == DistanceUnit.IMPERIAL) x / 1_609.344 else x / 1_000
                val number = String.format(Locale.getDefault(), "%.${if (xMax < 10_000) 1 else 0}f", distance)
                val label = measurer.measure(if (index == 4) "$number ${if (distanceUnit == DistanceUnit.IMPERIAL) "mi" else "km"}" else number, axisStyle)
                val at = point(x, low)
                val textLeft = (at.x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
                drawText(label, topLeft = Offset(textLeft, at.y + 2.dp.toPx()))
            }
            // Fresnel zone: the full zone faint, the 60% band stronger, both stopping at the ground.
            drawPath(band({ it.fresnelTop }, { it.visibleFresnelBottom }), FresnelTeal.copy(alpha = 0.2f))
            drawPath(band({ it.fresnelTop60 }, { it.visibleFresnelBottom60 }), FresnelTeal.copy(alpha = 0.42f))
            drawPath(line { it.fresnelTop }, FresnelTeal.copy(alpha = 0.65f), style = Stroke(1.dp.toPx(), pathEffect = dash))
            drawPath(line { it.fresnelBottom }, FresnelTeal.copy(alpha = 0.65f), style = Stroke(1.dp.toPx(), pathEffect = dash))
            // Terrain.
            val terrain = line { it.terrainMeters }
            drawPath(Path().apply {
                addPath(terrain)
                point(xMax, low).let { lineTo(it.x, it.y) }
                point(0.0, low).let { lineTo(it.x, it.y) }
                close()
            }, TerrainBrown.copy(alpha = 0.35f))
            drawPath(terrain, TerrainBrown, style = Stroke(1.dp.toPx()))
            // Terrain reaching into the 60% zone.
            val obstructedRed = Color(PathClearance.OBSTRUCTED.argb(dark))
            samples.filter { it.isObstructed }.forEach { drawCircle(obstructedRed, 2.5.dp.toPx(), point(it.distanceMeters, it.terrainMeters)) }
            // Line of sight dashed and coloured by clearance, as on the map.
            val sightStart = samples.first().lineOfSightMeters
            val sightEnd = samples.last().lineOfSightMeters
            fun sight(meters: Double) = sightStart + (meters / xMax).coerceIn(0.0, 1.0) * (sightEnd - sightStart)
            // One dash pattern along the whole line, so the dashes run on across colour changes.
            val sightDash = floatArrayOf(5.dp.toPx(), 3.dp.toPx())
            stretches.forEach { stretch ->
                val phase = (point(stretch.startMeters, 0.0).x - point(0.0, 0.0).x) % (sightDash[0] + sightDash[1])
                drawLine(Color(stretch.clearance.argb(dark)), point(stretch.startMeters, sight(stretch.startMeters)),
                    point(stretch.endMeters, sight(stretch.endMeters)), 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(sightDash, phase))
            }
            // Antenna masts from the ground to the line of sight, lettered as on the map.
            listOf(Triple("A", samples.first(), PointABlue), Triple("B", samples.last(), PointBOrange)).forEach { (name, sample, color) ->
                val ground = point(sample.distanceMeters, sample.terrainMeters)
                val antenna = point(sample.distanceMeters, sample.lineOfSightMeters)
                drawLine(color, ground, antenna, 2.dp.toPx())
                drawCircle(color, 7.dp.toPx(), antenna)
                val label = measurer.measure(name, markerStyle)
                drawText(label, topLeft = Offset(antenna.x - label.size.width / 2f, antenna.y - label.size.height / 2f))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChartLegend(TerrainBrown, "Terrain")
            ChartLegend(FresnelTeal.copy(alpha = 0.55f), "Fresnel zone")
            // The line of sight's three clearances, matching the map.
            PathClearance.entries.forEach { ClearanceSwatch(Color(it.argb(dark)), it.title) }
        }
    }
}

@Composable
private fun ChartLegend(color: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(18.dp, 4.dp).background(color, RoundedCornerShape(2.dp)))
        Text(title, style = MaterialTheme.typography.bodySmall)
    }
}

/** Slider range and one-unit increments match iOS; switching units never rewrites the input. */
@Composable
internal fun ProfileHeightControl(endpoint: String, meters: Double, unit: DistanceUnit, onMetersChanged: (Double) -> Unit) {
    val maximum = profileHeightMaximum(unit)
    val displayed = profileDisplayHeight(meters, unit)
    Text("Additional antenna height $endpoint: ${formatProfileHeight(meters, unit)}", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = displayed.toFloat().coerceIn(0f, maximum),
        onValueChange = { onMetersChanged(profileHeightMeters(it.roundToInt().toDouble(), unit)) },
        valueRange = 0f..maximum,
        steps = maximum.toInt() - 1,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "Additional antenna height $endpoint"
            stateDescription = formatProfileHeight(meters, unit)
        },
    )
    Text("Above ground · ${if (unit == DistanceUnit.IMPERIAL) "0–100 ft" else "0–30 m"}", style = MaterialTheme.typography.bodySmall)
}
