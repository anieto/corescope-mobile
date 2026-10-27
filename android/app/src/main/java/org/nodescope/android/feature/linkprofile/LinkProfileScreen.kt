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
import org.nodescope.android.BuildConfig
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LinkProfileScreen(model: LinkProfileViewModel, distanceUnit: DistanceUnit = LocalDistanceUnit.current, nodes: List<org.nodescope.android.core.model.MeshNode> = emptyList()) {
    val state by model.state.collectAsStateWithLifecycle()
    var advanced by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var selecting by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(if (state.fields.getValue("Latitude A").isBlank() && BuildConfig.MAPS_CONFIGURED) "A" else null) }
    fun coordinate(endpoint: String): ProfileCoordinate? = runCatching {
        ProfileCoordinate(state.fields.getValue("Latitude $endpoint").toDouble(), state.fields.getValue("Longitude $endpoint").toDouble())
    }.getOrNull()
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selecting != null) { selecting = null }
    val map: @Composable (Modifier) -> Unit = { modifier ->
        LinkProfileMap(selecting, coordinate("A"), coordinate("B"), nodes, { selecting = null }, { point ->
            selecting?.let { endpoint ->
                model.edit("Latitude $endpoint", point.latitude.toString())
                model.edit("Longitude $endpoint", point.longitude.toString())
                selecting = if (endpoint == "A" && coordinate("B") == null) "B" else null
            }
        }, modifier)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
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
            Column(Modifier.fillMaxSize().meshPageBackground().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                editor()
                state.result?.let { ProfileResult(it, distanceUnit) }
            }
        }
        if (!BuildConfig.MAPS_CONFIGURED) {
            controls()
        } else if (wide) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(400.dp).fillMaxHeight()) { controls() }
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

private fun Double.display() = String.format(Locale.getDefault(), "%.2f", this)

@Composable
private fun ProfileResult(analysis: LinkAnalysis, distanceUnit: DistanceUnit) {
    val result = analysis.result
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = meshCardColor().copy(alpha = 1f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when (result.clearanceStatus) {
                ClearanceStatus.CLEAR -> "Clear terrain path"
                ClearanceStatus.MARGINAL -> "Marginal clearance"
                ClearanceStatus.PARTIAL_OBSTRUCTION -> "Partial obstruction"
                ClearanceStatus.BLOCKED -> "Blocked by terrain"
            }, style = MaterialTheme.typography.titleLarge)
            TerrainChart(analysis, distanceUnit)
            Text("Brown: terrain · Teal: first Fresnel zone · Red: less than 60% clearance", style = MaterialTheme.typography.bodySmall)
            val metrics = linkedMapOf(
                "Distance" to formatDistance(result.distanceMeters / 1000, distanceUnit), "Initial bearing" to "${analysis.bearing.display()}°",
                "Ground A / B" to "${formatProfileHeight(analysis.terrain.points.first().elevationMeters, distanceUnit)} / ${formatProfileHeight(analysis.terrain.points.last().elevationMeters, distanceUnit)}",
                "Worst Fresnel clearance" to "${result.worstClearancePercent.display()}%", "Free-space path loss" to "${result.freeSpacePathLossDB.display()} dB",
                "Peak diffraction loss" to "${result.peakDiffractionLossDB.display()} dB", "Total path loss" to "${result.totalPathLossDB.display()} dB",
                "Receive power" to "${result.receivePowerDBm?.display()} dBm", "Link margin" to "${result.linkMarginDB?.display()} dB",
                "Terrain samples" to "${analysis.terrain.points.size} · ${analysis.terrain.origin}",
                "Terrain fetched" to DateFormat.getDateTimeInstance().format(Date(analysis.terrain.fetchedAt)),
                "Calculated" to DateFormat.getDateTimeInstance().format(Date(analysis.calculatedAt)))
            metrics.forEach { (label, value) -> Text("$label: $value") }
            Text("Terrain: Open-Meteo / Copernicus DEM GLO-90 (nominal resolution ${formatProfileHeight(90.0, distanceUnit)}). Model: ${LinkProfileEngine.ENGINE_ID} v${LinkProfileEngine.ENGINE_VERSION}.", style = MaterialTheme.typography.bodySmall)
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(onClick = { uri.openUri("https://open-meteo.com/en/docs/elevation-api") }) { Text("Terrain data attribution") }
        }
    }
}

@Composable
private fun TerrainChart(analysis: LinkAnalysis, distanceUnit: DistanceUnit) {
    val points = analysis.terrain.points
    val request = analysis.request
    val length = analysis.result.distanceMeters
    val start = points.first().elevationMeters + request.heightAMeters
    val end = points.last().elevationMeters + request.heightBMeters
    val ground = points.map { it.elevationMeters + LinkProfileEngine.earthBulge(it.distanceMeters, length - it.distanceMeters, request.refractionK) }
    val sight = points.map { start + it.distanceMeters / length * (end - start) }
    val radii = points.map { LinkProfileEngine.fresnelRadius(request.frequencyMHz, it.distanceMeters, length - it.distanceMeters) }
    val low = minOf(ground.min(), sight.indices.minOf { sight[it] - radii[it] }) - 5
    val high = maxOf(ground.max(), sight.indices.maxOf { sight[it] + radii[it] }) + 5
    val lineColor = MaterialTheme.colorScheme.onSurface
    Text("Terrain profile · elevation ${formatProfileHeight(low, distanceUnit)}–${formatProfileHeight(high, distanceUnit)}", style = MaterialTheme.typography.titleSmall)
    Canvas(Modifier.fillMaxWidth().height(240.dp).semantics {
        contentDescription = "Terrain, line of sight and first Fresnel zone over ${formatDistance(length / 1000, distanceUnit)}. Worst clearance ${analysis.result.worstClearancePercent.display()} percent."
    }) {
        fun position(index: Int, elevation: Double) = Offset((points[index].distanceMeters / length * size.width).toFloat(), (size.height * (1 - (elevation - low) / (high - low))).toFloat())
        val terrain = Path().apply {
            moveTo(0f, size.height)
            ground.indices.forEach { val p = position(it, ground[it]); lineTo(p.x, p.y) }
            lineTo(size.width, size.height); close()
        }
        drawPath(terrain, Color(0xFF9B7653))
        val fresnel = Path().apply {
            sight.indices.forEach { val p = position(it, sight[it] + radii[it]); if (it == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            sight.indices.reversed().forEach { val p = position(it, sight[it] - radii[it]); lineTo(p.x, p.y) }; close()
        }
        drawPath(fresnel, Color(0x55009999))
        drawLine(lineColor, position(0, start), position(points.lastIndex, end), 2.dp.toPx())
        ground.indices.filter { it > 0 && it < points.lastIndex && ground[it] > sight[it] - 0.6 * radii[it] }.forEach {
            drawCircle(Color.Red, 3.dp.toPx(), position(it, ground[it]))
        }
        drawRect(lineColor.copy(alpha = 0.3f), style = Stroke(1.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("A · ${formatDistance(0.0, distanceUnit)}"); Text("B · ${formatDistance(length / 1000, distanceUnit)}") }
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
