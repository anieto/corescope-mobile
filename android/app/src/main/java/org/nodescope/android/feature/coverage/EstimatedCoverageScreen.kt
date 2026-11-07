package org.nodescope.android.feature.coverage

import androidx.compose.foundation.layout.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.core.design.meshPageBackground
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.FieldCoordinate
import org.nodescope.android.core.model.FieldGeometry
import org.nodescope.android.feature.learn.*
import org.nodescope.android.feature.map.*

@Composable
fun EstimatedCoverageScreen(nodes: List<MeshNode>) {
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var values by rememberSaveable { mutableStateOf(List(10) { "" }) }
    var radius by rememberSaveable { mutableIntStateOf(20_000) }
    var spacing by rememberSaveable { mutableIntStateOf(1000) }
    var showNodes by rememberSaveable { mutableStateOf(false) }
    var showsRequired by rememberSaveable { mutableStateOf(false) }
    val grid = remember(radius, spacing) { try { CoverageGrid(radius, spacing, -radius, radius, -radius, radius) } catch (_: CoverageGridFailure) { null } }
    val workFits by key(radius, spacing) { produceState<Boolean?>(null) {
        value = null
        value = withContext(Dispatchers.Default) {
            val context = coroutineContext
            if (grid == null) false else try {
                CoverageGridPreflight.estimate(grid, 150_000_000, 150_000_000,
                    cancelled = { context.ensureActive(); false })
                true
            } catch (_: CoverageGridPreflightFailure) { false }
        }
    } }
    val selected = Coordinate.valid(CoverageSetupValidation.number(latitude), CoverageSetupValidation.number(longitude))
    val known = remember(nodes, selected) { nearestCoverageNodes(nodes, selected) }
    val map: @Composable (Modifier) -> Unit = { modifier ->
        CoverageSetupMap(selected, known, modifier) { latitude = it.latitude.toString(); longitude = it.longitude.toString() }
    }
    val editor: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.meshPageBackground().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CoverageSetupCard("Transmitter location") {
                LearnLabel("Coordinates", GlossaryTerm.PLANNING_COORDINATES)
                CoverageInput("Latitude", latitude, "latitude", showsRequired) { latitude = it }
                CoverageInput("Longitude", longitude, "longitude", showsRequired) { longitude = it }
                TextButton(onClick = { showNodes = true }) { Text("Known nodes (${known.size})") }
            }
            CoverageSetupCard("Transmitter") {
                CoverageRadioInputs(CoverageSetupFields.transmitter, values, showsRequired) { index, value -> values = values.toMutableList().also { it[index] = value } }
            }
            CoverageSetupCard("Receiver assumptions") {
                Text("Use the same assumed receiver for the coverage map and known nodes. These are not the nodes’ reported equipment settings.", style = MaterialTheme.typography.bodySmall)
                CoverageRadioInputs(CoverageSetupFields.receiver, values, showsRequired) { index, value -> values = values.toMutableList().also { it[index] = value } }
            }
            CoverageSetupCard("Study area") {
                LearnLabel("Study radius", GlossaryTerm.STUDY_RADIUS)
                CoverageChoice(radius, listOf(5000 to "5 km", 20_000 to "20 km", 50_000 to "50 km", 100_000 to "100 km")) { radius = it }
                LearnLabel("Sample spacing", GlossaryTerm.COVERAGE_DETAIL)
                CoverageChoice(spacing, listOf(100 to "100 m", 200 to "200 m", 500 to "500 m", 1000 to "1 km", 2000 to "2 km")) { spacing = it }
                if (grid != null) {
                    Text("${grid.count} map samples", fontFamily = MonaspaceNeon)
                    when (workFits) {
                        null -> Text("Checking calculation limits", style = MaterialTheme.typography.bodySmall)
                        false -> Text("This area needs more calculation work than the current limit allows. Choose wider spacing or a smaller radius.", color = MaterialTheme.colorScheme.error)
                        true -> Text("Within the initial work limit. Terrain complexity may still prevent a complete result.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                else Text("This radius and detail exceed the current grid limit. Choose wider spacing or a smaller radius.", color = MaterialTheme.colorScheme.error)
            }
            CoverageSetupCard("Calculation") {
                Text("Coverage calculation is not available in this build. You can review the setup, but no coverage or node reception has been predicted.")
                TextButton(onClick = { showsRequired = true }) { Text("Review inputs") }
                if (showsRequired) {
                    val fields = (CoverageSetupFields.keys.zip(values) + listOf("latitude" to latitude, "longitude" to longitude)).toMap()
                    val reviewMessage = try {
                        CoverageStudyInputs.fromEditor(fields, radius, spacing)
                        when (workFits) {
                            null -> "Checking calculation limits for this study area."
                            false -> "Review the study area above. Choose wider spacing or a smaller radius."
                            true -> "Setup passes input and initial work checks. Terrain availability and model suitability have not been checked. No coverage has been calculated."
                        }
                    } catch (_: CoverageStudyInputFailure) {
                        "Review the highlighted fields above. All inputs are required."
                    } catch (_: CoverageGridFailure) {
                        "Review the study area above. Choose wider spacing or a smaller radius."
                    } catch (_: IllegalArgumentException) {
                        "The combined radio values exceed calculation limits. Review transmit power, antenna gains, losses, sensitivity and reserve."
                    }
                    Text(reviewMessage, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {}, enabled = false) { Text("Calculate coverage") }
                Text("This setup is not saved when you leave the tool.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 800.dp) Row(Modifier.fillMaxSize()) { map(Modifier.weight(1f).fillMaxHeight()); editor(Modifier.width(380.dp).fillMaxHeight()) }
        else Column(Modifier.fillMaxSize()) { map(Modifier.fillMaxWidth().weight(0.38f)); editor(Modifier.fillMaxWidth().weight(0.62f)) }
    }
    if (showNodes) AlertDialog(onDismissRequest = { showNodes = false }, title = { Text("Known nodes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Known nodes are not evaluated yet. After you select a transmitter, this list shows the nearest 200 located nodes available in the current catalog. It is not a complete inventory of nearby radios.")
                if (known.isEmpty()) Text(if (selected == null) "Select a transmitter location to find nearby known nodes."
                    else "No located nodes are available in the current catalog.")
                known.forEach { node ->
                    Text(node.displayName, fontFamily = MonaspaceNeon)
                    Text("Not evaluated", style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = { showNodes = false }) { Text("Done") } })
}

internal fun nearestCoverageNodes(nodes: List<MeshNode>, selected: Coordinate?): List<MeshNode> {
    val origin = selected?.let { FieldCoordinate.create(it.latitude, it.longitude) } ?: return emptyList()
    // Rank before capping markers so API ordering cannot exclude a nearer node.
    return nodes.asSequence().distinctBy { it.publicKey }.mapNotNull { node ->
        val destination = node.coordinate?.let { FieldCoordinate.create(it.latitude, it.longitude) }
            ?: return@mapNotNull null
        node to FieldGeometry.geometry(origin, destination).distanceMeters
    }.sortedWith(compareBy<Pair<MeshNode, Double>> { it.second }.thenBy { it.first.publicKey })
        .take(200).map { it.first }.toList()
}

private data class CoverageSetupField(val index: Int, val title: String, val term: GlossaryTerm)
private object CoverageSetupFields {
    val keys = listOf("frequency", "power", "txHeight", "txGain", "txLoss", "rxHeight", "rxGain", "rxLoss", "sensitivity", "reserve")
    val transmitter = listOf(
        CoverageSetupField(0, "Frequency (MHz)", GlossaryTerm.RADIO_FREQUENCY),
        CoverageSetupField(1, "Transmit power (dBm)", GlossaryTerm.TRANSMIT_POWER),
        CoverageSetupField(2, "Antenna height (m)", GlossaryTerm.ANTENNA_HEIGHT),
        CoverageSetupField(3, "Antenna gain (dBi)", GlossaryTerm.ANTENNA_GAIN),
        CoverageSetupField(4, "Cable / system loss (dB)", GlossaryTerm.CABLE_LOSS))
    val receiver = listOf(
        CoverageSetupField(5, "Antenna height (m)", GlossaryTerm.ANTENNA_HEIGHT),
        CoverageSetupField(6, "Antenna gain (dBi)", GlossaryTerm.ANTENNA_GAIN),
        CoverageSetupField(7, "Cable / system loss (dB)", GlossaryTerm.CABLE_LOSS),
        CoverageSetupField(8, "Sensitivity (dBm)", GlossaryTerm.RECEIVER_SENSITIVITY),
        CoverageSetupField(9, "Planning reserve (dB)", GlossaryTerm.PLANNING_RESERVE))
}
@Composable private fun CoverageSetupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable private fun CoverageInput(label: String, value: String, field: String, showsRequired: Boolean, change: (String) -> Unit) {
    val issue = CoverageSetupValidation.issue(field, value)?.takeIf { it != CoverageSetupIssue.required || showsRequired }
    val error = issue?.let { when (it) {
        CoverageSetupIssue.required -> "Enter a value."
        CoverageSetupIssue.number -> "Enter a finite number using a decimal point."
        CoverageSetupIssue.latitude -> "Coverage calculations support latitude from −85° to 85°."
        CoverageSetupIssue.longitude -> "Longitude must be between −180° and 180°."
        CoverageSetupIssue.frequency -> "Frequency must be between 30 and 6000 MHz."
        CoverageSetupIssue.height -> "Antenna height must be between 1 and 3000 m above ground."
        CoverageSetupIssue.nonnegative -> "Enter zero or a positive value."
        CoverageSetupIssue.unknownField -> "This field is not supported."
    } }
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        isError = error != null, supportingText = if (error == null) null else ({ Text(error) }),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonaspaceNeon),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
}
@Composable private fun CoverageRadioInputs(fields: List<CoverageSetupField>, values: List<String>, showsRequired: Boolean, change: (Int, String) -> Unit) {
    fields.forEach { field ->
        Row(verticalAlignment = Alignment.CenterVertically) { Text(field.title, Modifier.weight(1f)); LearnButton(field.term) }
        CoverageInput(field.title, values[field.index], CoverageSetupFields.keys[field.index], showsRequired) { change(field.index, it) }
    }
}
@Composable private fun CoverageChoice(value: Int, options: List<Pair<Int, String>>, change: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(options.first { it.first == value }.second) }
        DropdownMenu(open, { open = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.second) }, onClick = { change(option.first); open = false }) }
        }
    }
}

@Composable private fun CoverageSetupMap(selected: Coordinate?, nodes: List<MeshNode>, modifier: Modifier,
                                        layer: CoverageMapLayer? = null, choose: (Coordinate) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var choice by remember { mutableStateOf(loadMapProvider(context)) }
    var layersOpen by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var pending by rememberSaveable { mutableStateOf<List<Double>?>(null) }
    val initial = selected ?: nodes.firstOrNull()?.coordinate
    var camera by rememberSaveable { mutableStateOf(listOf(initial?.latitude ?: 0.0, initial?.longitude ?: 0.0, if (initial == null) 1.0 else 10.0, 0.0, 0.0)) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val engine: MapEngineState = if (choice.provider == MapProvider.GOOGLE) rememberGoogleMapEngine() else rememberMapLibreEngine()
    var overlayFailure by remember { mutableStateOf<String?>(null) }
    CoverageMapAttachment(engine, layer) { overlayFailure = it }
    LaunchedEffect(engine.overlays, nodes, selected, pending) {
        engine.overlays?.setPins(nodes.mapNotNull { node -> node.coordinate?.let { MapPin(it, MapPin.Kind.TENTATIVE, "${node.displayName} · Not evaluated") } } +
            listOfNotNull(selected?.let { MapPin(it, MapPin.Kind.TENTATIVE, "Transmitter") },
                pending?.let { MapPin(Coordinate(it[0], it[1]), MapPin.Kind.TENTATIVE, "Proposed location") }))
    }
    LaunchedEffect(engine.camera, selected) { selected?.let { engine.camera?.animate(CameraMove.Center(it, 11.0)) } }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val place: (MapTap) -> Unit = { tap -> tap.coordinate?.let { pending = listOf(it.latitude, it.longitude) } }
            when (engine) {
                is MapLibreEngine -> MapLibreEngineHost(engine, choice.cartoStyle(dark), retry, 0, camera, { camera = it }, { false }, Modifier.fillMaxSize(), onLongPress = place)
                is GoogleMapEngine -> GoogleMapEngineHost(engine, choice.googleType, dark, 0, camera, { camera = it }, { false }, Modifier.fillMaxSize(), hidesBusinesses = true, onLongPress = place)
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                TextButton(onClick = { layersOpen = true }) { Text("Map layers") }
                MapLayersMenu(layersOpen, { layersOpen = false }, choice, dark) { choice = it; saveMapProvider(context, it); layersOpen = false }
            }
            if (engine.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (engine.failed) Surface(Modifier.align(Alignment.Center)) { TextButton(onClick = { retry++ }) { Text("Map failed to load. Retry") } }
            if (engine is MapLibreEngine) Surface(Modifier.align(Alignment.BottomEnd)) {
                Row { TextButton(onClick = { uri.openUri("https://carto.com/attributions") }) { Text("© CARTO", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap", style = MaterialTheme.typography.labelSmall) } }
            }
        }
        Surface {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                if (pending != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                    Button(onClick = { pending?.let { choose(Coordinate(it[0], it[1])) }; pending = null }, enabled = !engine.failed) { Text("Use location") }
                } else Text("Touch and hold to choose a transmitter location.", style = MaterialTheme.typography.bodySmall)
                overlayFailure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text("Gray nodes: not evaluated", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
