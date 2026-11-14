package org.nodescope.android.feature.coverage

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.AnalyzerSnapshot
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.RegionCoordinate
import org.nodescope.android.core.storage.DistanceUnit
import org.nodescope.android.feature.learn.*
import org.nodescope.android.feature.map.*
import java.util.Locale

/**
 * Estimated Coverage, laid out like Link Profile and as on iOS: the map fills the screen, and the
 * setup and result live in a panel that collapses to a status header. A touch & hold places the
 * transmitter; presets fill in the radio, antennas and heights so a study takes a few taps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EstimatedCoverageScreen(model: EstimatedCoverageViewModel, nodes: List<MeshNode>, sourceKey: String,
    snapshot: AnalyzerSnapshot? = null, selectedRegion: String? = null, sourceViewport: RegionCoordinate? = null) {
    LaunchedEffect(nodes, sourceKey) { model.offerCatalog(nodes, sourceKey) }
    // Leaving or backgrounding cancels active work, as before and on iOS.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.cancel() }
    BackHandler(enabled = model.isChoosingLocation) { model.isChoosingLocation = false }
    var showsNodes by rememberSaveable { mutableStateOf(false) }
    val mapState = rememberCoverageMapState(model.coordinate)
    val scroll = rememberScrollState()
    var mapInset by remember { mutableStateOf(0.dp) }
    var settled by remember { mutableLongStateOf(0L) }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentRegion by rememberUpdatedState(selectedRegion)
    val currentViewport by rememberUpdatedState(sourceViewport)
    // One map instance moves between layouts on a live posture change instead of being rebuilt.
    val map = remember {
        movableContentOf { modifier: Modifier ->
            CoverageMap(model, modifier, mapState, currentSnapshot, currentRegion, currentViewport, mapInset) { settled = it }
        }
    }
    val header: @Composable () -> Unit = { CoverageHeader(model) }
    val details: @Composable () -> Unit = { CoverageDetails(model) { showsNodes = true } }
    val fold = rememberFoldSplit()
    val tabletop = rememberTabletopSplit()
    BoxWithConstraints(Modifier.fillMaxSize().then(fold.modifier).then(tabletop.modifier)) {
        val wide = maxWidth >= if (LocalDensity.current.fontScale >= 1.5f) 1000.dp else 840.dp
        val editorAtFold = fold.firstPaneWidth(maxWidth, 280.dp)?.minus(PaneGap / 2)
        val mapAboveFold = tabletop.offset?.takeIf { it >= 160.dp && maxHeight - it >= 160.dp }?.minus(PaneGap / 2)
        // Side and lower panels: the header stays put while everything under it scrolls.
        val panel: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.meshPageBackground()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { CoverageTitle(); header() }
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 16.dp)) { details() }
            }
        }
        if (editorAtFold != null) {
            SideEffect { mapInset = 0.dp }
            Row(Modifier.fillMaxSize()) {
                panel(Modifier.width(editorAtFold).fillMaxHeight())
                Spacer(Modifier.width(PaneGap))
                map(Modifier.weight(1f).fillMaxHeight())
            }
        } else if (mapAboveFold != null) {
            SideEffect { mapInset = 0.dp }
            Column(Modifier.fillMaxSize()) {
                map(Modifier.height(mapAboveFold).fillMaxWidth())
                Spacer(Modifier.height(PaneGap))
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        } else if (wide) {
            SideEffect { mapInset = 0.dp }
            // Tablets and wide windows: the editor first, sized as on iPad; the map takes the larger side.
            val editorWidth = (maxWidth * 0.34f).coerceIn(340.dp, 420.dp)
            Row(Modifier.fillMaxSize()) {
                panel(Modifier.width(editorWidth).fillMaxHeight())
                map(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Phones: the map fills the screen under a floating panel. Collapsed, the panel is its
            // header (the prompt, the progress, then the verdict); opened, it also holds the setup,
            // covering the map rather than moving it. A plain overlay, as in Link Profile.
            val density = LocalDensity.current
            val focus = LocalFocusManager.current
            var expanded by rememberSaveable { mutableStateOf(false) }
            var headerHeight by remember { mutableStateOf(0.dp) }
            SideEffect { mapInset = headerHeight + 16.dp }
            val detailsMax = (maxHeight - headerHeight - 88.dp).coerceAtLeast(160.dp)
            // A placement closes the panel for the map; once the camera settles it opens on the settings.
            LaunchedEffect(model.placementID) { if (model.placementID != 0L) expanded = false }
            LaunchedEffect(settled) { if (settled != 0L && settled == model.placementID && !model.isCalculating) expanded = true }
            LaunchedEffect(model.isCalculating) { if (model.isCalculating) { focus.clearFocus(); expanded = false } }
            // Choosing a point needs the map.
            LaunchedEffect(model.isChoosingLocation) { if (model.isChoosingLocation) expanded = false }
            Box(Modifier.fillMaxSize()) {
                map(Modifier.fillMaxSize())
                val panelShape = RoundedCornerShape(28.dp)
                val light = MaterialTheme.colorScheme.background.luminance() >= 0.3f
                Column(Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth()
                    .shadow(14.dp, panelShape).clip(panelShape).meshPageBackground()
                    .background(if (light) Color(0.62f, 0.70f, 0.82f, 0.25f) else Color.Transparent)
                    .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), panelShape)
                    .animateContentSize()) {
                    // The handle and header: tap the handle, or drag either, to open or close.
                    Column(Modifier.fillMaxWidth()
                        .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                        .pointerInput(Unit) {
                            var dragged = 0f
                            detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = {
                                if (dragged < -30 * density.density) expanded = true
                                if (dragged > 30 * density.density) expanded = false
                            }) { _, amount -> dragged += amount }
                        }) {
                        Box(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Hide coverage setup" else "Show coverage setup") { expanded = !expanded }
                            .semantics { contentDescription = if (expanded) "Hide coverage setup" else "Show coverage setup" },
                            contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CoverageTitle(compact = true)
                            header()
                        }
                    }
                    if (expanded) {
                        // Open nearly to the top, leaving a strip of map.
                        Column(Modifier.heightIn(max = detailsMax).verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                            details()
                        }
                    }
                }
            }
        }
    }
    if (showsNodes) CoverageKnownNodesDialog(model) { showsNodes = false }
}

@Composable private fun CoverageTitle(compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Estimated Coverage", style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge)
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text("Beta", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// MARK: - Header

/**
 * The always-visible line: what to do next, the run's progress, a failure, or the verdict.
 * Calculate and Reset sit beside it, so a study never needs the panel opened once it is set up.
 */
@Composable private fun CoverageHeader(model: EstimatedCoverageViewModel) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val unit = LocalDistanceUnit.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val result = model.activeResult
            val failure = model.runner.failure
            when {
                model.isChoosingLocation -> {
                    Text("Choose a new transmitter location", style = MaterialTheme.typography.titleMedium)
                    Text("Touch & hold the map to place it.", color = secondary)
                    OutlinedButton(onClick = { model.isChoosingLocation = false }) { Text("Cancel") }
                }
                model.coordinate == null -> {
                    Text("Place your transmitter to estimate coverage.", style = MaterialTheme.typography.titleMedium)
                    Text("Touch & hold the map, on a known node or anywhere, or use your location.", color = secondary)
                    CoverageCurrentLocationButton(model)
                }
                model.isCalculating -> {
                    val progress = model.calculationProgress.takeIf { model.runner.preparationStage == CoveragePreparationStage.calculating }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(when {
                            progress is CoverageCalculationProgress.CheckingNodes -> "Checking known-node paths…"
                            model.runner.preparationStage == CoveragePreparationStage.preparingTerrain -> "Downloading and preparing terrain…"
                            model.runner.preparationStage == CoveragePreparationStage.calculating -> "Calculating map samples…"
                            else -> "Validating and cleaning up…"
                        }, style = MaterialTheme.typography.titleMedium)
                    }
                    model.startedAt?.let { ElapsedTime(it) }
                    // As on iOS: how many of the samples that need a path are done.
                    if (progress is CoverageCalculationProgress.MapSamples) {
                        LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth())
                        Text("%,d of %,d map samples processed".format(progress.completed, progress.total),
                            fontFamily = MonaspaceNeon, style = MaterialTheme.typography.bodyMedium)
                        Text("Some samples may remain unresolved. Difficult terrain takes longer.",
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                    Text("Keep this screen open; leaving cancels the calculation.", style = MaterialTheme.typography.bodySmall, color = secondary)
                    OutlinedButton(onClick = model::cancel) { Text("Cancel calculation") }
                }
                failure != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Text(failure.coverageMessage(), color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = model::calculate, enabled = model.canCalculate) { Text("Try Again") }
                }
                result != null -> {
                    val counts = remember(result.executionID) { result.sampleCounts() }
                    val partial = counts.third > 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (partial) Icons.Filled.Warning else Icons.Filled.CheckCircle, null, Modifier.size(20.dp),
                            tint = if (partial) Color(0xFFFF9500) else Color(0xFF34C759))
                        Text(if (partial) "Partial coverage estimate" else "Estimated coverage", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${counts.first} above threshold · ${counts.second} below · ${counts.third} unresolved",
                        fontFamily = MonaspaceNeon, style = MaterialTheme.typography.bodyMedium, color = secondary)
                    if (result.area.workLimited) {
                        Text(if (result.area.chargedSamples >= result.limits.areaSamples || result.area.chargedGeometry >= result.limits.areaGeometry)
                            "The study exhausted its work budget. Some samples were not calculated. Try a smaller radius or wider sample spacing."
                            else "Some individual paths exceeded their work limits. Wider grid spacing may not fix these paths; try a smaller radius.",
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                        Text("Hatched areas have no prediction.", style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                    if (model.overlayError) Text("The result could not be drawn. Calculate again.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    else if (model.layer == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Preparing coverage overlay…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> {
                    val problem = model.setupProblem
                    Text(when (model.runner.snapshot.phase) {
                        CoverageStudyPublicationPhase.stale -> "Inputs changed. Calculate again."
                        CoverageStudyPublicationPhase.cancelled -> "Calculation cancelled."
                        else -> if (problem == null || problem == CoverageSetupProblem.CheckingWork) "Ready to calculate." else "Review the setup."
                    }, style = MaterialTheme.typography.titleMedium)
                    Text(setupSummary(model, unit), style = MaterialTheme.typography.bodyMedium, color = secondary)
                    readyHint(model, problem)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary) }
                    Button(onClick = model::calculate, enabled = model.canCalculate) { Text("Calculate coverage") }
                }
            }
            model.inputError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        if (!model.isChoosingLocation && model.coordinate != null && !model.isCalculating) {
            // A primary-coloured outline, as Link Profile's Reset.
            OutlinedButton(onClick = model::clearTransmitter, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset")
            }
        }
    }
}

@Composable private fun ElapsedTime(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Elapsed")
        Text("%d:%02d".format(seconds / 60, seconds % 60), fontFamily = MonaspaceNeon)
    }
}

/** Map samples predicted above and below the threshold, and those left unresolved. */
internal fun CoverageStudyResult.sampleCounts(): Triple<Int, Int, Int> {
    val qualified = area.cells.filter { it.state == CoverageAreaState.qualified }
    val above = qualified.count { (it.marginDb ?: Double.NEGATIVE_INFINITY) >= 0 }
    val unresolved = area.cells.count { it.state !in listOf(CoverageAreaState.qualified, CoverageAreaState.nearSite, CoverageAreaState.outsideRadius) }
    return Triple(above, qualified.size - above, unresolved)
}

/** The transmitter and area in a line: "USA/Canada · 22 dBm · Antenna 20 ft · 10 mi / 1 mi". */
private fun setupSummary(model: EstimatedCoverageViewModel, unit: DistanceUnit): String {
    val radio = model.radioPreset?.title ?: "Custom radio"
    val power = model.number("power")?.let { "${it.editorText()} dBm" } ?: "— dBm"
    val antenna = "Antenna ${model.values["txHeight"].orEmpty()} ${if (model.heightInFeet) "ft" else "m"}"
    val area = "${formatDistance(model.radiusMeters / 1000.0, unit)} / ${spacingLabel(model.spacingMeters, unit)}"
    return listOf(radio, power, antenna, area).joinToString(" · ")
}

private fun readyHint(model: EstimatedCoverageViewModel, problem: CoverageSetupProblem?): String? = when (problem) {
    CoverageSetupProblem.NoTransmitter, CoverageSetupProblem.CheckingWork -> null
    is CoverageSetupProblem.Field -> "A value is out of range. Open the setup to fix it."
    CoverageSetupProblem.Radio -> "The combined radio values exceed calculation limits."
    CoverageSetupProblem.Grid, CoverageSetupProblem.TooMuchWork -> "This area needs more work than the limit allows. Choose wider spacing or a smaller radius."
    null -> when {
        !model.acceptsScenario -> "Accept the model assumptions in the setup to calculate."
        model.runner.snapshot.phase == CoverageStudyPublicationPhase.cancelled -> "No partial result was published."
        else -> null
    }
}

// MARK: - Details

/** Everything under the header: the guide, transmitter, radio, receiver, study area and result. */
@Composable private fun CoverageDetails(model: EstimatedCoverageViewModel, showNodes: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CoverageSetupGuide(model)
        CoverageTransmitterCard(model)
        CoverageRadioCard(model)
        CoverageReceiverCard(model)
        CoverageStudyAreaCard(model)
        CoverageCalculationCard(model, showNodes)
        Text("Planning estimates only: terrain does not account for buildings, foliage or interference. Terrain is downloaded from AWS Skadi and cached on this device. This setup is not saved when you leave the tool.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun CoverageCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** A card whose title row opens and closes the rest, like iOS's disclosure groups. */
@Composable private fun CoverageDisclosure(title: String, summary: List<String>, expanded: Boolean, toggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    CoverageCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (expanded) content()
    }
}

/** Optional inline guidance beside the setup, without blocking the map. */
@Composable private fun CoverageSetupGuide(model: EstimatedCoverageViewModel) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(COVERAGE_PREFERENCES, Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(preferences.getBoolean("coverage.setupGuideDismissed.v1", false)) }
    fun dismiss(value: Boolean) { dismissed = value; preferences.edit { putBoolean("coverage.setupGuideDismissed.v1", value) } }
    if (dismissed) {
        TextButton(onClick = { dismiss(false) }) { Text("Show setup guide") }
        return
    }
    CoverageCard {
        Text("Your first coverage study", style = MaterialTheme.typography.titleMedium)
        Text("1. Place your transmitter", style = MaterialTheme.typography.titleSmall)
        Text("Touch and hold the map, or open Transmitter settings to enter coordinates or use your location.", style = MaterialTheme.typography.bodyMedium)
        if (model.coordinate == null) OutlinedButton(onClick = { model.isChoosingLocation = true }) { Text("Choose on map") }
        Text("2. Check the equipment", style = MaterialTheme.typography.titleSmall)
        Text("Open the transmitter, radio and receiver sections. Set antenna heights above ground and choose the radio preset used by your mesh.",
            style = MaterialTheme.typography.bodyMedium)
        Text("3. Choose the area and calculate", style = MaterialTheme.typography.titleSmall)
        Text("Start with a small radius. Wider sample spacing is faster; finer spacing reveals smaller coverage gaps. Review the model assumptions, then tap Calculate coverage above.",
            style = MaterialTheme.typography.bodyMedium)
        Text("Colors show predicted margin, not guaranteed connectivity. Hatched areas have no prediction.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dismiss(true) }) { Text("Got it") }
            OutlinedButton(onClick = { dismiss(true) }) { Text("Skip guide") }
        }
    }
}

/** The transmitter: where it is and its antenna; opening it shows the height, antenna and loss, and moving the point. */
@Composable private fun CoverageTransmitterCard(model: EstimatedCoverageViewModel) {
    var showsCoordinates by rememberSaveable { mutableStateOf(false) }
    val point = model.coordinate
    val title = if (point == null) "Transmitter not placed" else model.transmitterName ?: "Dropped pin"
    val antenna = "Antenna ${model.values["txHeight"].orEmpty()} ${model.heightUnit} · ${model.values["txGain"].orEmpty()} dBi"
    val place = point?.let { String.format(Locale.US, "%.4f, %.4f", it.latitude, it.longitude) }
    CoverageDisclosure("Transmitter settings", listOfNotNull(title, listOfNotNull(place, antenna).joinToString(" · ")),
        model.transmitterExpanded, { model.transmitterExpanded = !model.transmitterExpanded }) {
        CoverageHeightSetting(model, "txHeight", CoverageHeightPreset.transmitter)
        CoverageAntennaSetting(model, "txGain")
        CoverageNumberField(model, "txLoss", "System loss", "dB", GlossaryTerm.CABLE_LOSS)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.isChoosingLocation = true }) { Text(if (point != null) "Choose new point" else "Choose on map") }
            OutlinedButton(onClick = { showsCoordinates = !showsCoordinates }) { Text(if (showsCoordinates) "Hide coordinates" else "Enter coordinates") }
        }
        CoverageCurrentLocationButton(model)
        if (showsCoordinates) {
            LearnLabel("Coordinates", GlossaryTerm.PLANNING_COORDINATES)
            CoverageTextField("Latitude", model.latitudeText, model.issue("latitude")) { model.typeCoordinates(it, model.longitudeText) }
            CoverageTextField("Longitude", model.longitudeText, model.issue("longitude")) { model.typeCoordinates(model.latitudeText, it) }
        }
    }
}

/** Region and modem presets, then the values a preset sets. Editing one makes the radio custom. */
@Composable private fun CoverageRadioCard(model: EstimatedCoverageViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var customizing by rememberSaveable { mutableStateOf(false) }
    val preset = model.radioPreset
    val summary = listOfNotNull("${model.values["frequency"].orEmpty()} MHz", preset?.modemSummary,
        "${model.values["power"].orEmpty()} dBm", "sensitivity ${model.values["sensitivity"].orEmpty()} dBm").joinToString(" · ")
    CoverageDisclosure("Radio settings", listOf(summary, "Reserve: ${model.values["reserve"].orEmpty()} dB"), expanded, { expanded = !expanded }) {
        CoverageRadioPresetMenu(preset, model::applyRadioPreset)
        TextButton(onClick = { customizing = !customizing }, contentPadding = PaddingValues(0.dp)) {
            Text(if (customizing) "Hide radio values" else if (preset == null) "Customize radio · edited" else "Customize radio")
        }
        if (customizing) {
            CoverageNumberField(model, "frequency", "Frequency", "MHz", GlossaryTerm.RADIO_FREQUENCY)
            CoverageNumberField(model, "power", "Transmit power", "dBm", GlossaryTerm.TRANSMIT_POWER)
            CoverageNumberField(model, "sensitivity", "Receiver sensitivity", "dBm", GlossaryTerm.RECEIVER_SENSITIVITY)
        }
        LearnLabel("Planning reserve", GlossaryTerm.PLANNING_RESERVE)
        val reserve = model.number("reserve")
        CoveragePresetRow(CoverageReservePreset.choicesDb.map { db ->
            Triple(if (db == 0.0) "None" else "${db.editorText()} dB", reserve == db) { model.set("reserve", db.editorText()) }
        })
        CoverageNumberField(model, "reserve", "Reserve", "dB", null)
    }
}

/** The assumed receiver: the same for the map and every known node. */
@Composable private fun CoverageReceiverCard(model: EstimatedCoverageViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CoverageDisclosure("Receiver assumptions",
        listOf("Antenna ${model.values["rxHeight"].orEmpty()} ${model.heightUnit} · ${model.values["rxGain"].orEmpty()} dBi · loss ${model.values["rxLoss"].orEmpty()} dB"),
        expanded, { expanded = !expanded }) {
        Text("One assumed receiver for the coverage map and the known nodes. These are not the nodes’ reported equipment.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CoverageHeightSetting(model, "rxHeight", CoverageHeightPreset.receiver)
        CoverageAntennaSetting(model, "rxGain")
        CoverageNumberField(model, "rxLoss", "System loss", "dB", GlossaryTerm.CABLE_LOSS)
    }
}

/** Study radius in whole miles or kilometres, then the sample spacing and the work check. */
@Composable private fun CoverageStudyAreaCard(model: EstimatedCoverageViewModel) {
    val unit = LocalDistanceUnit.current
    val imperial = unit == DistanceUnit.IMPERIAL
    val step = if (imperial) 2.0 else 5.0
    val maximum = if (imperial) 50.0 else 80.0
    val metersPerUnit = if (imperial) 1609.344 else 1000.0
    var draft by remember { mutableStateOf<Float?>(null) }
    val shown = draft?.toDouble() ?: model.radiusMeters / metersPerUnit
    fun setRadius(value: Double) = model.setRadius((value.coerceIn(step, maximum) * metersPerUnit).let { kotlin.math.round(it).toInt() })
    fun adjust(increasing: Boolean) {
        // Snap off-step radii only in response to an explicit adjustment.
        val steps = shown / step
        val next = if (increasing) kotlin.math.floor(steps + 0.0005) + 1 else kotlin.math.ceil(steps - 0.0005) - 1
        setRadius(next * step)
    }
    val label = "${shown.let { if (it % 1.0 == 0.0) it.toLong().toString() else String.format(Locale.US, "%.3f", it).trimEnd('0') }} ${if (imperial) "mi" else "km"}"
    CoverageCard {
        Text("Study area", style = MaterialTheme.typography.titleMedium)
        LearnLabel("Study radius", GlossaryTerm.STUDY_RADIUS)
        Text(label, fontFamily = MonaspaceNeon)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { adjust(false) }, enabled = shown > step) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Slider(value = shown.coerceIn(step, maximum).toFloat(), onValueChange = { draft = it },
                onValueChangeFinished = { draft?.let { setRadius(it.toDouble()) }; draft = null },
                valueRange = step.toFloat()..maximum.toFloat(), steps = ((maximum - step) / step).toInt() - 1,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Study radius"; stateDescription = label })
            IconButton(onClick = { adjust(true) }, enabled = shown < maximum - 0.0005) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
        Text("Doubling the radius roughly quadruples the map samples at the same spacing. Longer paths also take more work.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LearnLabel("Sample spacing", GlossaryTerm.COVERAGE_DETAIL)
        val choices = (if (imperial) EstimatedCoverageViewModel.imperialSpacings else EstimatedCoverageViewModel.metricSpacings).let {
            if (model.spacingMeters in it) it else (it + model.spacingMeters).sorted()
        }
        CoverageSpacingMenu(model.spacingMeters, choices, unit, model::setSpacing)
        val grid = model.grid
        if (grid != null) {
            CoverageValueRow("Map samples", "%,d".format(grid.count))
            CoverageValueRow("Minimum terrain samples", model.minimumWorkSamples?.let { "%,d".format(it) } ?: "—")
            Text("Lower bound only; extra terrain detail and refinement add work. The area budget is 120 million samples per batch.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (model.workFits) {
                null -> Text("Checking calculation limits…", style = MaterialTheme.typography.bodySmall)
                false -> Text("This area needs more calculation work than the current limit allows. Choose wider spacing or a smaller radius.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                true -> Text("Minimum work fits one batch; complex terrain can require more.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Text("This radius and detail exceed the current grid limit. Choose wider spacing or a smaller radius.", color = Color(0xFFFF9500))
    }
}

/** The model's assumptions to accept, the run's messages, and the result. */
@Composable private fun CoverageCalculationCard(model: EstimatedCoverageViewModel, showNodes: () -> Unit) {
    CoverageCard {
        Text("Calculation", style = MaterialTheme.typography.titleMedium)
        Text("Beta estimate: terrain-only, inland paths, vertical polarization and median atmosphere. No building or tree clutter model; terrain surfaces may still contain vegetation. Not suitable for coastal or sea paths without a different model configuration.",
            style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use these model assumptions", Modifier.weight(1f))
            Switch(model.acceptsScenario, model::updateAcceptsScenario)
        }
        Text("Downloads terrain on demand (up to 500 MB per run; 1 GB cache). Start with a small area.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model.coordinate != null) OutlinedButton(onClick = showNodes) { Text("Known nodes (${model.knownNodes.size})") }
        when (model.runner.snapshot.phase) {
            CoverageStudyPublicationPhase.cancelled -> Text("Calculation cancelled. No partial result was published.", style = MaterialTheme.typography.bodySmall)
            CoverageStudyPublicationPhase.stale -> Text("Inputs or catalog changed. Calculate again to update the result.", style = MaterialTheme.typography.bodySmall)
            else -> {}
        }
        model.activeResult?.let { result ->
            HorizontalDivider()
            CoverageResults(result)
        }
    }
}

// MARK: - Settings controls

/** Typical heights as chips, then the exact value in the setup's unit. */
@Composable private fun CoverageHeightSetting(model: EstimatedCoverageViewModel, field: String, presets: List<CoverageHeightPreset>) {
    val feet = model.heightInFeet
    val meters = model.number(field)?.let { if (feet) it * 0.3048 else it }
    LearnLabel("Antenna height", GlossaryTerm.ANTENNA_HEIGHT)
    CoveragePresetRow(presets.map { preset ->
        // Imperial chips advertise whole feet; apply that exact displayed height.
        val text = if (feet) String.format(Locale.US, "%.0f", preset.meters / 0.3048) else preset.meters.editorText()
        Triple("${preset.title} · $text ${model.heightUnit}", meters != null && CoverageHeightPreset.matching(meters, presets) == preset) {
            model.set(field, text)
        }
    })
    CoverageNumberField(model, field, "Above ground", model.heightUnit, null)
}

/** An antenna type whose gain fills the field, or a custom gain. */
@Composable private fun CoverageAntennaSetting(model: EstimatedCoverageViewModel, field: String) {
    val gain = model.number(field)
    val match = gain?.let(CoverageAntennaPreset::matching)
    var open by remember { mutableStateOf(false) }
    LearnLabel("Antenna", GlossaryTerm.ANTENNA_GAIN)
    Box {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text(match?.title ?: "Custom", Modifier.weight(1f)); Icon(Icons.Filled.ExpandMore, null) }
        DropdownMenu(open, { open = false }) {
            CoverageAntennaPreset.all.forEach { preset ->
                DropdownMenuItem(text = { Text("${preset.title} · ${preset.gainDbi.editorText()} dBi") },
                    trailingIcon = if (preset == match) ({ Text("✓") }) else null,
                    onClick = { model.set(field, preset.gainDbi.editorText()); open = false })
            }
        }
    }
    CoverageNumberField(model, field, "Antenna gain", "dBi", null)
}

@Composable private fun CoverageRadioPresetMenu(current: CoverageRadioPreset?, choose: (CoverageRadioPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(current?.let { "${it.family.title} · ${it.title}" } ?: "Custom radio")
                current?.let { Text(it.modemSummary, style = MaterialTheme.typography.bodySmall) }
            }
            Icon(Icons.Filled.ExpandMore, null)
        }
        DropdownMenu(open, { open = false }) {
            CoverageRadioPreset.Family.entries.forEach { family ->
                Text(family.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                CoverageRadioPreset.presets(family).forEach { preset ->
                    DropdownMenuItem(text = {
                        Column { Text(preset.title); Text("${preset.modemSummary} · ${preset.frequencyMHz.editorText()} MHz", style = MaterialTheme.typography.bodySmall) }
                    }, trailingIcon = if (preset == current) ({ Text("✓") }) else null, onClick = { choose(preset); open = false })
                }
            }
        }
    }
}

@Composable private fun CoveragePresetRow(options: List<Triple<String, Boolean, () -> Unit>>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, selected, action) -> FilterChip(selected, action, label = { Text(label) }) }
    }
}

@Composable private fun CoverageNumberField(model: EstimatedCoverageViewModel, field: String, label: String, unit: String, term: GlossaryTerm?) {
    if (term != null) LearnLabel(label, term)
    CoverageTextField(if (term != null) "$label ($unit)" else "$label ($unit)", model.values[field].orEmpty(), model.issue(field)) { model.set(field, it) }
}

@Composable private fun CoverageTextField(label: String, value: String, issue: CoverageSetupIssue?, change: (String) -> Unit) {
    val error = issue?.takeIf { it != CoverageSetupIssue.required }?.message()
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        isError = error != null, supportingText = error?.let { { Text(it) } },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonaspaceNeon),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
}

private fun CoverageSetupIssue.message(): String = when (this) {
    CoverageSetupIssue.required -> "Enter a value."
    CoverageSetupIssue.number -> "Enter a finite number using a decimal point."
    CoverageSetupIssue.latitude -> "Coverage calculations support latitude from −85° to 85°."
    CoverageSetupIssue.longitude -> "Longitude must be between −180° and 180°."
    CoverageSetupIssue.frequency -> "Frequency must be between 30 and 6000 MHz."
    CoverageSetupIssue.height -> "Antenna height must be between 1 and 3000 m above ground (about 3 to 9843 ft)."
    CoverageSetupIssue.nonnegative -> "Enter zero or a positive value."
    CoverageSetupIssue.unknownField -> "This field is not supported."
}

/** Android's standard drop-down field, so the spacing reads as a choice to change, not a label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CoverageSpacingMenu(meters: Int, choices: List<Int>, unit: DistanceUnit, choose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(spacingLabel(meters, unit), {}, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            readOnly = true, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonaspaceNeon))
        ExposedDropdownMenu(open, { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(spacingLabel(choice, unit)) }, onClick = { choose(choice); open = false },
                    trailingIcon = if (choice == meters) ({ Text("✓") }) else null)
            }
        }
    }
}

@Composable private fun CoverageValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontFamily = MonaspaceNeon, style = MaterialTheme.typography.bodyMedium)
    }
}

private val EstimatedCoverageViewModel.heightUnit: String get() = if (heightInFeet) "ft" else "m"

/** Spacing in the preferred unit: fractions of a mile, or metres and kilometres. */
private fun spacingLabel(meters: Int, unit: DistanceUnit): String {
    if (unit == DistanceUnit.IMPERIAL) {
        mapOf(201 to "⅛ mi", 402 to "¼ mi", 805 to "½ mi", 1609 to "1 mi", 2414 to "1½ mi", 3219 to "2 mi", 8047 to "5 mi")[meters]?.let { return it }
        return "$meters m"
    }
    return if (meters >= 1000 && meters % 1000 == 0) "${meters / 1000} km" else "$meters m"
}

/** Requests location only after a tap; leaving or moving the transmitter cancels pending delivery. */
@Composable private fun CoverageCurrentLocationButton(model: EstimatedCoverageViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                            model.place(result.coordinate, "Current location")
                            message = "Check the pin on the map, especially when using approximate location."
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                generation++
                locating = true
                message = null
                if (hasLocationPermission(context)) locate() else permission.launch(LOCATION_PERMISSIONS)
            }, enabled = !locating) { Text("Use current location") }
            if (locating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = { cancel() }) { Text("Cancel") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// MARK: - Known nodes

@Composable private fun CoverageKnownNodesDialog(model: EstimatedCoverageViewModel, dismiss: () -> Unit) {
    val statuses = model.nodeStatuses
    val result = model.activeResult
    AlertDialog(onDismissRequest = dismiss, title = { Text("Known nodes") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This list shows the nearest 200 located nodes available in the current catalog. Predictions use your receiver assumptions, not reported node equipment. It is not a complete inventory of nearby radios.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.knownNodes.isEmpty()) Text(if (model.coordinate == null) "Place a transmitter to find nearby known nodes."
                else "No located nodes are available in the current catalog.")
            model.knownNodes.forEach { node ->
                val status = statuses[node.publicKey]
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(node.displayName, fontFamily = MonaspaceNeon, style = MaterialTheme.typography.titleSmall)
                    Text(status?.coverageLabel() ?: "Not evaluated", style = MaterialTheme.typography.bodySmall)
                    status?.adjustedMarginDb?.let { margin ->
                        Text(String.format(Locale.US, "Margin after reserve: %.1f dB", margin), fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelMedium)
                        result?.let {
                            Text(String.format(Locale.US, "Predicted receive level: %.1f dBm", margin + it.budget.sensitivityDbm + it.budget.reserveDb),
                                fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

// MARK: - Legend

/** Swatches on the map once a result shows; the dialog explains what the bands do and don't mean. */
@Composable internal fun CoverageMarginLegend() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) {
        Column {
            Text("Predicted margin ⓘ", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverageMarginSwatch(CoverageRasterClass.belowThreshold, "<0")
                CoverageMarginSwatch(CoverageRasterClass.limitedHeadroom, "0–10")
                CoverageMarginSwatch(CoverageRasterClass.aboveThreshold, ">10 dB")
                CoverageMarginSwatch(CoverageRasterClass.unresolved, "?")
            }
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Coverage colors") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Margin after planning reserve", style = MaterialTheme.typography.titleSmall)
            Text("Red · Below 0 dB: predicted below threshold")
            Text("Amber · 0–10 dB: limited predicted headroom")
            Text("Green · Above 10 dB: greater predicted headroom")
            Text("Gray hatching · No usable prediction")
            Text("Outside the study radius is unshaded. The area within 250 m of the transmitter is unsupported and hatched.",
                style = MaterialTheme.typography.bodySmall)
            Text("What this means", style = MaterialTheme.typography.titleSmall)
            Text("Margin is the predicted received signal minus receiver sensitivity and your planning reserve. Colors use unrounded values.")
            Text("Green does not mean reachable. This is a one-way terrain prediction, not a test of packet delivery, return-link strength, modem compatibility or interference.")
            Text("Map cells use the nearest calculated sample. Known nodes are calculated at their exact coordinates and can differ from the surrounding cell.")
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } })
}

@Composable private fun CoverageMarginSwatch(state: CoverageRasterClass, label: String) {
    // The overlay's own colours, opaque enough to read at swatch size.
    val color = Color(CoverageRaster.rgba(state, 0, 0).let { rgba -> (0xB3 shl 24) or (rgba ushr 8) })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private const val COVERAGE_PREFERENCES = "estimated-coverage"
