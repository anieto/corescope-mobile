package org.nodescope.android.feature.coverage

import java.io.File
import java.util.UUID
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import org.nodescope.android.core.design.LocalDistanceUnit
import org.nodescope.android.core.storage.DistanceUnit
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
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val runner = remember { CoverageStudyRunner() }
    // The app recreates this composition for each source session.
    val sourceID = remember { UUID.randomUUID().toString() }
    var calculation by remember { mutableStateOf<Job?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var acceptsScenario by rememberSaveable { mutableStateOf(false) }
    val preference = LocalDistanceUnit.current
    val heightInFeet = rememberSaveable { preference == DistanceUnit.IMPERIAL }
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
                CoverageGridPreflight.estimate(grid, 120_000_000, 120_000_000,
                    cancelled = { context.ensureActive(); false })
                true
            } catch (_: CoverageGridPreflightFailure) { false }
        }
    } }
    val selected = Coordinate.valid(CoverageSetupValidation.number(latitude), CoverageSetupValidation.number(longitude))
        ?.takeIf { CoverageSetupValidation.supportsCoordinate(it.latitude, it.longitude) }
    val known = remember(nodes, selected) { nearestCoverageNodes(nodes, selected) }
    val fields = remember(values, latitude, longitude) {
        (CoverageSetupFields.keys.zip(values) + listOf("latitude" to latitude, "longitude" to longitude)).toMap()
    }
    val inputs = remember(fields, radius, spacing) {
        try { CoverageStudyInputs.fromEditor(fields, radius, spacing, heightInFeet) } catch (_: Exception) { null }
    }
    val snapshots = remember(known, sourceID) { known.mapNotNull { node -> node.coordinate?.let {
        CoverageNodeSnapshot(sourceID, node.publicKey, it.latitude, it.longitude)
    } } }
    LaunchedEffect(fields, radius, spacing, snapshots) {
        runner.invalidate(); calculation?.cancel(); inputError = null
    }
    DisposableEffect(runner) { onDispose { runner.cancel(); calculation?.cancel() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { runner.cancel(); calculation?.cancel() }
    val result = runner.snapshot.result?.takeIf {
        runner.snapshot.phase == CoverageStudyPublicationPhase.completed && inputs != null && it.inputs.matches(inputs) &&
            it.nodes.run.nodesToEvaluate == snapshots
    }
    val statuses = remember(result, snapshots) { result?.let { completed -> snapshots.associate {
        it.nodeID to completed.nodes.run.status(it)
    } }.orEmpty() }
    var rasterError by remember { mutableStateOf<String?>(null) }
    val layer by produceState<CoverageMapLayer?>(null, result?.executionID) {
        value = null
        rasterError = null
        if (result != null) try {
            value = withContext(Dispatchers.Default) {
                val raster = CoverageRaster(result.grid, result.transmitter, result.area)
                var zoom = when { result.grid.radiusMeters <= 5000 -> 12; result.grid.radiusMeters <= 20000 -> 11;
                    result.grid.radiusMeters <= 50000 -> 10; else -> 9 }
                while (true) {
                    try { CoverageMapTilePlan.make(raster, zoom); break }
                    catch (error: CoverageMapLibreTilesFailure) {
                        if (error.state != CoverageMapLibreTilesState.tileLimit || zoom == 0) throw error
                        zoom--
                    }
                }
                CoverageMapLayer(result.executionID, raster, zoom)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { rasterError = "The result could not be drawn. Retry the calculation." }
    }

    val calculate: () -> Unit = calculate@{
        if (calculation != null || runner.isBusy) return@calculate
        showsRequired = true
        val captured = inputs
        if (captured == null) { inputError = "Review the highlighted inputs and choose a smaller area or wider spacing."; return@calculate }
        inputError = null
        calculation = scope.launch {
            try {
                runner.runWithProgress(captured) { id, fixed, progress ->
                    CoveragePreparedStudy.evaluate(fixed, snapshots, false, 200,
                        CoverageStudyLimits(120_000_000, 120_000_000, 30_000_000, 30_000_000, 1000),
                        samplingHaloMeters = 100.0, cacheRoot = File(context.cacheDir, "estimated-coverage-terrain-v1"),
                        cacheQuota = 1_000_000_000, downloadLimit = 500_000_000, offline = false,
                        executionID = id, progress = progress)
                }
            } finally { calculation = null }
        }
    }
    val map: @Composable (Modifier) -> Unit = { modifier ->
        CoverageSetupMap(selected, listOf(latitude, longitude), known, modifier,
            layer = layer?.takeIf { it.executionID == result?.executionID }, statuses = statuses,
            calculating = calculation != null || runner.isBusy,
            cancelCalculation = { runner.cancel(); calculation?.cancel() }) { latitude = it.latitude.toString(); longitude = it.longitude.toString() }
    }
    val editor: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.meshPageBackground().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CoverageSetupCard("Transmitter location") {
                CoverageCurrentLocation(latitude, longitude) { lat, lon -> latitude = lat; longitude = lon }
                LearnLabel("Coordinates", GlossaryTerm.PLANNING_COORDINATES)
                CoverageInput("Latitude", latitude, "latitude", showsRequired) { latitude = it }
                CoverageInput("Longitude", longitude, "longitude", showsRequired) { longitude = it }
                TextButton(onClick = { showNodes = true }) { Text("Known nodes (${known.size})") }
            }
            CoverageSetupCard("Transmitter") {
                CoverageRadioInputs(CoverageSetupFields.transmitter, values, showsRequired, heightInFeet) { index, value -> values = values.toMutableList().also { it[index] = value } }
            }
            CoverageSetupCard("Receiver assumptions") {
                Text("Use the same assumed receiver for the coverage map and known nodes. These are not the nodes’ reported equipment settings.", style = MaterialTheme.typography.bodySmall)
                CoverageRadioInputs(CoverageSetupFields.receiver, values, showsRequired, heightInFeet) { index, value -> values = values.toMutableList().also { it[index] = value } }
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
                Text("Beta estimate: terrain-only, inland paths, vertical polarization and median atmosphere. No building or tree clutter model; terrain surfaces may still contain vegetation. Not suitable for coastal or sea paths without a different model configuration.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acceptsScenario, { acceptsScenario = it })
                    Text("Use these model assumptions")
                }
                Text("Downloads terrain on demand (up to 500 MB per run; 1 GB cache). Start with a small area. Keep this screen open; leaving or backgrounding cancels active work.", style = MaterialTheme.typography.bodySmall)
                if (calculation != null || runner.isBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(when (runner.preparationStage) {
                        CoveragePreparationStage.preparingTerrain -> "Downloading and preparing terrain…"
                        CoveragePreparationStage.calculating -> "Calculating paths…"
                        else -> "Validating and cleaning up…"
                    })
                    TextButton(onClick = { runner.cancel(); calculation?.cancel() }) { Text("Cancel calculation") }
                } else Button(onClick = calculate, enabled = acceptsScenario && workFits == true) { Text("Calculate coverage") }
                inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                runner.failure?.let { Text(it.coverageMessage(), color = MaterialTheme.colorScheme.error) }
                if (runner.snapshot.phase == CoverageStudyPublicationPhase.cancelled) Text("Calculation cancelled. No partial result was published.")
                if (runner.snapshot.phase == CoverageStudyPublicationPhase.stale) Text("Inputs or catalog changed. Calculate again to update the result.")
                if (result != null) {
                    CoverageResults(result)
                    if (layer?.executionID == result.executionID) Text("Overlay zoom limit: ${layer?.maximumRasterZoom}. Zooming enlarges the same prediction samples.", style = MaterialTheme.typography.bodySmall)
                    else if (rasterError == null) Text("Preparing coverage overlay…")
                    rasterError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
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
                Text("This list shows the nearest 200 located nodes available in the current catalog. Predictions use your receiver assumptions, not reported node equipment. It is not a complete inventory of nearby radios.")
                if (known.isEmpty()) Text(if (selected == null) "Select a transmitter location to find nearby known nodes."
                    else "No located nodes are available in the current catalog.")
                known.forEach { node ->
                    Text(node.displayName, fontFamily = MonaspaceNeon)
                    Text(statuses[node.publicKey]?.coverageLabel() ?: "Not evaluated", style = MaterialTheme.typography.bodySmall)
                    statuses[node.publicKey]?.adjustedMarginDb?.let { Text("Margin after reserve: %.1f dB".format(it), fontFamily = MonaspaceNeon) }
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
@Composable private fun CoverageInput(label: String, value: String, field: String, showsRequired: Boolean, heightInFeet: Boolean = false, change: (String) -> Unit) {
    val issue = CoverageSetupValidation.issue(field, value, heightInFeet)?.takeIf { it != CoverageSetupIssue.required || showsRequired }
    val error = issue?.let { when (it) {
        CoverageSetupIssue.required -> "Enter a value."
        CoverageSetupIssue.number -> "Enter a finite number using a decimal point."
        CoverageSetupIssue.latitude -> "Coverage calculations support latitude from −85° to 85°."
        CoverageSetupIssue.longitude -> "Longitude must be between −180° and 180°."
        CoverageSetupIssue.frequency -> "Frequency must be between 30 and 6000 MHz."
        CoverageSetupIssue.height -> if (heightInFeet) "Antenna height must be between 1 and 3000 meters above ground (about 3.28 to 9842.52 feet)." else "Antenna height must be between 1 and 3000 m above ground."
        CoverageSetupIssue.nonnegative -> "Enter zero or a positive value."
        CoverageSetupIssue.unknownField -> "This field is not supported."
    } }
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        isError = error != null, supportingText = if (error == null) null else ({ Text(error) }),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonaspaceNeon),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
}
@Composable private fun CoverageRadioInputs(fields: List<CoverageSetupField>, values: List<String>, showsRequired: Boolean, heightInFeet: Boolean, change: (Int, String) -> Unit) {
    fields.forEach { field ->
        val title = if (heightInFeet && CoverageSetupFields.keys[field.index] in listOf("txHeight", "rxHeight")) "Antenna height (ft)" else field.title
        Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); LearnButton(field.term) }
        CoverageInput(title, values[field.index], CoverageSetupFields.keys[field.index], showsRequired, heightInFeet) { change(field.index, it) }
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

@Composable private fun CoverageSetupMap(selected: Coordinate?, locationInput: List<String>, nodes: List<MeshNode>, modifier: Modifier,
                                        layer: CoverageMapLayer? = null, statuses: Map<String, CoverageNodeStatus> = emptyMap(),
                                        calculating: Boolean = false, cancelCalculation: () -> Unit = {}, choose: (Coordinate) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var choice by remember { mutableStateOf(loadMapProvider(context)) }
    var layersOpen by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    // Proposals belong to the coordinate fields at placement time, even if those fields are invalid.
    var pending by remember(locationInput) { mutableStateOf<List<Double>?>(null) }
    val initial = selected ?: nodes.firstOrNull()?.coordinate
    var camera by rememberSaveable { mutableStateOf(listOf(initial?.latitude ?: 0.0, initial?.longitude ?: 0.0, if (initial == null) 1.0 else 10.0, 0.0, 0.0)) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val engine: MapEngineState = if (choice.provider == MapProvider.GOOGLE) rememberGoogleMapEngine() else rememberMapLibreEngine()
    var overlayFailure by remember { mutableStateOf<String?>(null) }
    var preparingOverlay by remember { mutableStateOf(false) }
    CoverageMapAttachment(engine, layer, onPreparing = { preparingOverlay = it }) { overlayFailure = it }
    LaunchedEffect(engine.overlays, nodes, selected, pending, statuses) {
        engine.overlays?.setPins(nodes.mapNotNull { node -> node.coordinate?.let { MapPin(it, MapPin.Kind.TENTATIVE, "${node.displayName} · ${statuses[node.publicKey]?.coverageLabel() ?: "Not evaluated"}") } } +
            listOfNotNull(selected?.let { MapPin(it, MapPin.Kind.TENTATIVE, "Transmitter") },
                pending?.let { MapPin(Coordinate(it[0], it[1]), MapPin.Kind.TENTATIVE, "Proposed location") }))
    }
    LaunchedEffect(engine.camera, selected, layer?.executionID) { selected?.let {
        val zoom = layer?.raster?.grid?.radiusMeters?.let { radius -> when {
            radius <= 5000 -> 11.0; radius <= 20000 -> 9.0; radius <= 50000 -> 8.0; else -> 7.0
        } } ?: 11.0
        engine.camera?.animate(CameraMove.Center(it, zoom))
    } }
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
                if (calculating) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text("Calculating coverage…", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = cancelCalculation) { Text("Cancel calculation") }
                }
                val supported = pending?.let { CoverageSetupValidation.supportsCoordinate(it[0], it[1]) } == true
                if (pending != null && !supported) {
                    Text("Choose a location between −85° and 85° latitude.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (pending != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                    Button(onClick = { pending?.let { choose(Coordinate(it[0], it[1])) }; pending = null }, enabled = !engine.failed && supported) { Text("Use location") }
                } else Text("Touch and hold to choose a transmitter location.", style = MaterialTheme.typography.bodySmall)
                if (preparingOverlay) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing coverage overlay…", style = MaterialTheme.typography.bodySmall) }
                overlayFailure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text(if (layer == null) "Nodes: not evaluated" else "Node labels show predictions; green shading is estimated coverage", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun CoverageCurrentLocation(latitude: String, longitude: String, choose: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentChoose by rememberUpdatedState(choose)
    var locating by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    fun cancel() { generation++; job?.cancel(); job = null; locating = false }
    fun locate() {
        val request = generation
        job = scope.launch {
            try {
                val result = currentLocation(context)
                if (request != generation) return@launch
                when (result) {
                    is LocationResult.Found -> {
                        if (CoverageSetupValidation.supportsCoordinate(result.coordinate.latitude, result.coordinate.longitude)) {
                            currentChoose(result.coordinate.latitude.toString(), result.coordinate.longitude.toString())
                            message = "Location added to this setup. Check the pin on the map, especially when using approximate location."
                        } else message = "Your location is outside the supported latitude range of −85° to 85°. Choose a point on the map."
                    }
                    LocationResult.ServicesOff -> message = "Turn on location services, or choose a point on the map."
                    LocationResult.Unavailable -> message = "Couldn't find your location. Try again, or choose a point on the map."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) message = "Couldn't get your location. Check location permission and services in Settings, or choose a point on the map."
            } finally {
                if (request == generation) locating = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (locating) {
            if (grants.values.any { it }) locate()
            else {
                locating = false
                message = "Location permission is off. Allow it in the app's settings, or choose a point on the map."
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { cancel() }
    DisposableEffect(latitude, longitude) { onDispose { cancel() } }
    TextButton(onClick = {
        generation++
        locating = true
        message = null
        if (hasLocationPermission(context)) locate() else permission.launch(LOCATION_PERMISSIONS)
    }, enabled = !locating) { Text("Use current location") }
    if (locating) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Finding location…")
        TextButton(onClick = { cancel() }) { Text("Cancel location request") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
