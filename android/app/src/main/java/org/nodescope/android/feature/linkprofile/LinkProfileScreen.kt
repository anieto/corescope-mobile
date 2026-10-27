package org.nodescope.android.feature.linkprofile

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

@Composable
fun LinkProfileScreen(model: LinkProfileViewModel, distanceUnit: DistanceUnit = LocalDistanceUnit.current, nodes: List<org.nodescope.android.core.model.MeshNode> = emptyList(),
    snapshot: org.nodescope.android.core.model.AnalyzerSnapshot? = null, selectedRegion: String? = null,
    sourceViewport: org.nodescope.android.core.model.RegionCoordinate? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    var advanced by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var selecting by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(if (state.fields.getValue("Latitude A").isBlank() && BuildConfig.MAPS_CONFIGURED) "A" else null) }
    fun coordinate(endpoint: String): ProfileCoordinate? = runCatching {
        ProfileCoordinate(state.fields.getValue("Latitude $endpoint").toDouble(), state.fields.getValue("Longitude $endpoint").toDouble())
    }.getOrNull()
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selecting != null) { selecting = null }
    // Held here, above the layout branches below, so folding, rotating or changing posture keeps
    // the camera, an unconfirmed tap and the scroll position (rememberSaveable restores by position).
    val mapState = rememberLinkProfileMapState(coordinate("A") ?: coordinate("B"))
    val scroll = rememberScrollState()
    val currentNodes by rememberUpdatedState(nodes)
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentRegion by rememberUpdatedState(selectedRegion)
    val currentViewport by rememberUpdatedState(sourceViewport)
    // One map instance moves between layouts on a live posture change instead of being rebuilt.
    val map = remember {
        movableContentOf { modifier: Modifier ->
            LinkProfileMap(selecting, coordinate("A"), coordinate("B"), currentNodes, { selecting = null }, { point ->
                selecting?.let { endpoint ->
                    model.edit("Latitude $endpoint", point.latitude.toString())
                    model.edit("Longitude $endpoint", point.longitude.toString())
                    selecting = if (endpoint == "A" && coordinate("B") == null) "B" else null
                }
            }, modifier, mapState, currentSnapshot, currentRegion, currentViewport)
        }
    }
    val fold = rememberFoldSplit()
    val tabletop = rememberTabletopSplit()
    BoxWithConstraints(Modifier.fillMaxSize().then(fold.modifier).then(tabletop.modifier)) {
        val wide = maxWidth >= if (LocalDensity.current.fontScale >= 1.5f) 1000.dp else 840.dp
        // Unfolded: the panes meet at the crease, the gap between them sitting on the hinge.
        val editorAtFold = fold.firstPaneWidth(maxWidth, 280.dp)?.minus(PaneGap / 2)
        val mapAboveFold = tabletop.offset?.takeIf { it >= 160.dp && maxHeight - it >= 160.dp }?.minus(PaneGap / 2)
        val editor: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Compare terrain clearance and modeled path loss between two locations. Planning estimates do not prove a radio link will work.")
                Text("Selecting or entering endpoints sends their coordinates to Open-Meteo for elevation previews. Analyze sends sampled path coordinates. Cached elevations are retained for 30 days.", style = MaterialTheme.typography.bodySmall)
                listOf("A", "B").forEach { endpoint ->
                    Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Point $endpoint", style = MaterialTheme.typography.titleMedium)
                            listOf("Latitude $endpoint", "Longitude $endpoint").forEach { key -> ProfileField(key, state.fields.getValue(key)) { model.edit(key, it) } }
                            val meters = state.fields.getValue("Height $endpoint (m)").toDoubleOrNull() ?: 0.0
                            ProfileHeightControl(endpoint, meters, distanceUnit) { value ->
                                model.edit("Height $endpoint (m)", value.toString())
                            }
                            val elevation = state.elevations[endpoint]
                            Text(when {
                                elevation?.loading == true -> "Ground elevation: Loading…"
                                elevation?.meters != null -> "Ground elevation: ${formatProfileHeight(elevation.meters, distanceUnit)}"
                                else -> "Ground elevation: Unavailable"
                            }, style = MaterialTheme.typography.bodyMedium)
                            val height = state.fields.getValue("Height $endpoint (m)").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                            val total = elevation?.meters?.let { ground -> height?.let { ground + it } }
                            Text("Total height: ${total?.let { formatProfileHeight(it, distanceUnit) } ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                            Row {
                                if (BuildConfig.MAPS_CONFIGURED) TextButton(onClick = { selecting = endpoint }) { Text("Choose on map") }
                                TextButton(onClick = { model.edit("Latitude $endpoint", ""); model.edit("Longitude $endpoint", "") }) { Text("Clear") }
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Radio settings", style = MaterialTheme.typography.titleMedium)
                        listOf("US/Canada Narrow", "EU/UK Narrow", "Custom").forEach { name ->
                            FilterChip(selected = state.preset == name, onClick = { model.preset(name) }, label = { Text(name) })
                        }
                        ProfileField("Frequency (MHz)", state.fields.getValue("Frequency (MHz)")) { model.edit("Frequency (MHz)", it) }
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced assumptions" else "Advanced assumptions") }
                        if (advanced) state.fields.keys.drop(7).forEach { key -> ProfileField(key, state.fields.getValue(key)) { model.edit(key, it) } }
                        Text("Preset values are planning assumptions. Standard atmospheric refraction (k = 4/3) is used.", style = MaterialTheme.typography.bodySmall)
                        Text("Bandwidth, spreading factor and coding rate describe your radio. Sensitivity is entered explicitly; changing those settings does not calculate a new sensitivity.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Terrain does not account for buildings, foliage or interference.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = model::analyze, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(if (state.loading) "Analyzing…" else "Analyze Link") }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        val controls: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize().meshPageBackground().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                editor()
                state.result?.let { ProfileResult(it, distanceUnit) }
            }
        }
        if (!BuildConfig.MAPS_CONFIGURED) {
            controls()
        } else if (editorAtFold != null) {
            // Editor before the hinge, map after it; the editor is not squeezed into a side column.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(editorAtFold).fillMaxHeight()) { controls() }
                Spacer(Modifier.width(PaneGap))
                map(Modifier.weight(1f).fillMaxHeight())
            }
        } else if (mapAboveFold != null) {
            // Tabletop: map on the upper half, controls on the lower, nothing under the crease.
            Column(Modifier.fillMaxSize()) {
                map(Modifier.height(mapAboveFold).fillMaxWidth())
                Spacer(Modifier.height(PaneGap))
                Box(Modifier.weight(1f).fillMaxWidth()) { controls() }
            }
        } else if (wide) {
            // Tablets and wide windows: the editor first, sized as on iPad; the map takes the larger right side.
            val editorWidth = (maxWidth * 0.34f).coerceIn(340.dp, 420.dp)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(editorWidth).fillMaxHeight()) { controls() }
                map(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val panelHeight = if (expanded && state.result != null) (maxHeight * 0.72f).coerceAtMost(620.dp) else (maxHeight * 0.44f).coerceAtMost(330.dp)
            Column(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxWidth())
                if (selecting == null) Column(Modifier.fillMaxWidth().height(panelHeight).meshPageBackground()) {
                    if (state.result != null) TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Show More Map" else "Show More Results")
                    }
                    Box(Modifier.weight(1f)) { controls() }
                }
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (title, icon) = when (result.clearanceStatus) {
                    ClearanceStatus.CLEAR -> "Clear terrain path" to Icons.Filled.CheckCircle
                    ClearanceStatus.MARGINAL -> "Marginal clearance" to Icons.Filled.Error
                    ClearanceStatus.PARTIAL_OBSTRUCTION -> "Partially obstructed" to Icons.Filled.Contrast
                    ClearanceStatus.BLOCKED -> "Blocked by terrain" to Icons.Filled.Block
                }
                Icon(icon, null, Modifier.size(20.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("Estimate", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                    "k = ${dB(request.refractionK, 2)}."
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
private val ObstructedRed = Color(0xFFFF3B30)
private val PointABlue = Color(0xFF007AFF)
private val PointBOrange = Color(0xFFFF9500)

@Composable
private fun TerrainChart(analysis: LinkAnalysis, distanceUnit: DistanceUnit) {
    val points = analysis.terrain.points
    val request = analysis.request
    val length = analysis.result.distanceMeters
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
    val ink = MaterialTheme.colorScheme.onSurface
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
            samples.filter { it.isObstructed }.forEach { drawCircle(ObstructedRed, 2.5.dp.toPx(), point(it.distanceMeters, it.terrainMeters)) }
            drawPath(line { it.lineOfSightMeters }, ink, style = Stroke(2.dp.toPx()))
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
            ChartLegend(ink, "Line of sight")
            ChartLegend(FresnelTeal.copy(alpha = 0.55f), "Fresnel zone")
            ChartLegend(ObstructedRed.copy(alpha = 0.7f), "Obstructed")
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
