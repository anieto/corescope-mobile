package org.nodescope.android.feature.coverage

import org.nodescope.android.core.design.formatDisplayDecimal
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.withDescription
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
import org.nodescope.android.core.design.ActivityAmber
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
    snapshot: AnalyzerSnapshot? = null, selectedRegion: String? = null, sourceViewport: RegionCoordinate? = null,
    onOpenSaved: () -> Unit = {}) {
    LaunchedEffect(nodes, sourceKey, selectedRegion) { model.offerCatalog(nodes, sourceKey, selectedRegion) }
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
    val details: @Composable () -> Unit = { CoverageDetails(model, onOpenSaved) { showsNodes = true } }
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
            var expanded by rememberSaveable { mutableStateOf(model.fromLinkProfile) }
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
                        Box(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) stringResource(R.string.coverage_hide_coverage_setup_2) else stringResource(R.string.coverage_show_coverage_setup_2)) { expanded = !expanded }
                            .withDescription(if (expanded) stringResource(R.string.coverage_hide_coverage_setup) else stringResource(R.string.coverage_show_coverage_setup)) { desc -> contentDescription = desc },
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
        Text(stringResource(R.string.coverage_estimated_coverage), style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge)
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(stringResource(R.string.coverage_beta), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
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
                    Text(stringResource(R.string.coverage_choose_a_new_transmitter_location), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.coverage_touch_hold_the_map_to_place_it), color = secondary)
                    OutlinedButton(onClick = { model.isChoosingLocation = false }) { Text(stringResource(R.string.common_cancel)) }
                }
                model.coordinate == null -> {
                    Text(stringResource(R.string.coverage_place_your_transmitter_to_estimate_coverage), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.coverage_touch_hold_the_map_on_a_known_node), color = secondary)
                    CoverageCurrentLocationButton(model)
                }
                model.isCalculating -> {
                    val progress = model.calculationProgress.takeIf { model.runner.preparationStage == CoveragePreparationStage.calculating }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(when {
                            progress is CoverageCalculationProgress.CheckingNodes -> stringResource(R.string.coverage_checking_known_node_paths)
                            model.runner.preparationStage == CoveragePreparationStage.preparingTerrain -> stringResource(R.string.coverage_downloading_and_preparing_terrain)
                            model.runner.preparationStage == CoveragePreparationStage.calculating -> stringResource(R.string.coverage_calculating_map_samples)
                            else -> stringResource(R.string.coverage_validating_and_cleaning_up)
                        }, style = MaterialTheme.typography.titleMedium)
                    }
                    model.startedAt?.let { ElapsedTime(it) }
                    // As on iOS: how many of the samples that need a path are done.
                    if (progress is CoverageCalculationProgress.MapSamples) {
                        LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.coverage_d_of_d_map_samples_processed).format(progress.completed, progress.total),
                            fontFamily = MonaspaceNeon, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.coverage_some_samples_may_remain_unresolved_difficult_terrain_takes),
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                    Text(stringResource(R.string.coverage_keep_this_screen_open_leaving_cancels_the_calculation), style = MaterialTheme.typography.bodySmall, color = secondary)
                    OutlinedButton(onClick = model::cancel) { Text(stringResource(R.string.coverage_cancel_calculation)) }
                }
                failure != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Text(failure.coverageMessage().asString(), color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = model::calculate, enabled = model.canCalculate, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ActivityAmber, contentColor = Color.White)) { Text(stringResource(R.string.coverage_try_again)) }
                }
                result != null -> {
                    val counts = remember(result.executionID) { result.sampleCounts() }
                    val partial = counts.third > 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (partial) Icons.Filled.Warning else Icons.Filled.CheckCircle, null, Modifier.size(20.dp),
                            tint = if (partial) Color(0xFFFF9500) else Color(0xFF34C759))
                        Text(if (partial) stringResource(R.string.coverage_partial_coverage_estimate) else stringResource(R.string.coverage_estimated_coverage_2), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(stringResource(R.string.coverage_above_threshold_below_unresolved, counts.first, counts.second, counts.third),
                        fontFamily = MonaspaceNeon, style = MaterialTheme.typography.bodyMedium, color = secondary)
                    val pathLimited = result.nodes.run.nodesToEvaluate.any { result.nodes.run.status(it).reason == CoverageNodeReason.pathWorkLimit }
                    if (result.hasPendingWork) {
                        // A batch stopped early (its budget or low memory): Continue picks up where it left off.
                        Text(stringResource(R.string.coverage_this_batch_left_samples_unfinished_continue_to_calculate),
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                        Button(onClick = { model.calculate(continuing = true) }, enabled = model.canCalculate, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ActivityAmber, contentColor = Color.White)) { Text(stringResource(R.string.coverage_continue_calculation)) }
                    } else if (result.area.workLimited || pathLimited) {
                        Text(if (result.area.chargedSamples >= result.limits.areaSamples || result.area.chargedGeometry >= result.limits.areaGeometry)
                            stringResource(R.string.coverage_the_study_exhausted_its_work_budget_some_samples)
                            else stringResource(R.string.coverage_some_individual_paths_exceeded_their_work_limits_wider),
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                        Text(stringResource(R.string.coverage_hatched_areas_have_no_prediction), style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                    if (model.overlayError) Text(stringResource(R.string.coverage_the_result_could_not_be_drawn_calculate_again), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    else if (model.layer == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.coverage_preparing_coverage_overlay), style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> {
                    val problem = model.setupProblem
                    Text(when (model.runner.snapshot.phase) {
                        CoverageStudyPublicationPhase.stale -> stringResource(R.string.coverage_inputs_changed_calculate_again)
                        CoverageStudyPublicationPhase.cancelled -> stringResource(R.string.coverage_calculation_cancelled)
                        else -> if (problem == null || problem == CoverageSetupProblem.CheckingWork) stringResource(R.string.coverage_ready_to_calculate) else stringResource(R.string.coverage_review_the_setup)
                    }, style = MaterialTheme.typography.titleMedium)
                    Text(setupSummary(model, unit), style = MaterialTheme.typography.bodyMedium, color = secondary)
                    readyHint(model, problem)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary) }
                    Button(onClick = model::calculate, enabled = model.canCalculate, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ActivityAmber, contentColor = Color.White)) { Text(stringResource(R.string.coverage_calculate_coverage)) }
                }
            }
            model.inputError?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        if (!model.isChoosingLocation && model.coordinate != null && !model.isCalculating) {
            // A blue tonal fill keeps Reset subordinate to calculation.
            FilledTonalButton(onClick = model::clearTransmitter, colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.coverage_reset))
            }
        }
    }
}

@Composable private fun ElapsedTime(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.coverage_elapsed))
        Text(stringResource(R.string.coverage_d_02d).format(seconds / 60, seconds % 60), fontFamily = MonaspaceNeon)
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
@Composable
private fun setupSummary(model: EstimatedCoverageViewModel, unit: DistanceUnit): String {
    val radio = model.radioPreset?.title?.asString() ?: stringResource(R.string.coverage_custom_radio_2)
    val power = model.number("power")?.let { stringResource(R.string.coverage_dbm, it.editorText()) } ?: "— dBm"
    val antenna = stringResource(R.string.coverage_antenna_2, model.displayNumber("txHeight"), if (model.heightInFeet) "ft" else "m")
    val area = "${formatDistance(model.radiusMeters / 1000.0, unit)} / ${spacingLabel(model.spacingMeters, unit)}"
    return listOf(radio, power, antenna, area).joinToString(" · ")
}

@Composable
private fun readyHint(model: EstimatedCoverageViewModel, problem: CoverageSetupProblem?): String? = when (problem) {
    CoverageSetupProblem.NoTransmitter, CoverageSetupProblem.CheckingWork -> null
    is CoverageSetupProblem.Field -> stringResource(R.string.coverage_a_value_is_out_of_range_open_the)
    CoverageSetupProblem.Radio -> stringResource(R.string.coverage_the_combined_radio_values_exceed_calculation_limits)
    CoverageSetupProblem.Grid, CoverageSetupProblem.TooMuchWork -> stringResource(R.string.coverage_this_area_needs_more_work_than_the_limit)
    null -> when {
        !model.acceptsScenario -> stringResource(R.string.coverage_accept_the_model_assumptions_in_the_setup_to)
        model.runner.snapshot.phase == CoverageStudyPublicationPhase.cancelled -> stringResource(R.string.coverage_no_partial_result_was_published)
        else -> null
    }
}

// MARK: - Details

/** Everything under the header: the guide, transmitter, radio, receiver, study area and result. */
@Composable private fun CoverageDetails(model: EstimatedCoverageViewModel, onOpenSaved: () -> Unit, showNodes: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        org.nodescope.android.feature.candidatesites.SavedStudyActions(snapshot = model::savedSnapshot,
            didSave = { model.savedStudy = it; model.markClean() }, onOpen = onOpenSaved, suggestedPlanID = model.suggestedPlanID)
        if (model.usesSavedCatalog) {
            Text(if (model.savedStudy?.coverage?.calculatedResult == null) stringResource(R.string.coverage_saved_node_locations_inputs_only_save)
                else stringResource(R.string.coverage_saved_node_locations_saved_coverage_results),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model::refreshNodesFromSource, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.coverage_refresh_nodes_from_current_source)) }
        }
        CoverageSetupGuide(model)
        CoverageReportCard(model.diagnosticReport)
        CoverageTransmitterCard(model)
        CoverageRadioCard(model)
        CoverageReceiverCard(model)
        CoverageStudyAreaCard(model)
        if (model.canReuseSmallerStudy) Text(stringResource(R.string.coverage_the_next_calculation_will_reuse_matching_samples_from),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CoverageCalculationCard(model, showNodes)
        Text(stringResource(R.string.coverage_planning_estimates_only_terrain_does_not_account_for),
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
        val disclosureState = stringResource(if (expanded) R.string.coverage_disclosure_expanded else R.string.coverage_disclosure_collapsed)
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle).semantics { stateDescription = disclosureState },
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
        TextButton(onClick = { dismiss(false) }) { Text(stringResource(R.string.coverage_show_setup_guide)) }
        return
    }
    CoverageCard {
        Text(stringResource(R.string.coverage_your_first_coverage_study), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.coverage_1_place_your_transmitter), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.coverage_touch_and_hold_the_map_or_open_transmitter), style = MaterialTheme.typography.bodyMedium)
        if (model.coordinate == null) Button(onClick = { model.isChoosingLocation = true }) { Text(stringResource(R.string.coverage_choose_on_map)) }
        Text(stringResource(R.string.coverage_2_check_the_equipment), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.coverage_open_the_transmitter_radio_and_receiver_sections_set),
            style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.coverage_3_choose_the_area_and_calculate), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.coverage_start_with_a_small_radius_wider_sample_spacing),
            style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.coverage_colors_show_predicted_margin_not_guaranteed_connectivity_hatched),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dismiss(true) }) { Text(stringResource(R.string.coverage_got_it)) }
            OutlinedButton(onClick = { dismiss(true) }) { Text(stringResource(R.string.coverage_skip_guide)) }
        }
    }
}

/** The transmitter: where it is and its antenna; opening it shows the height, antenna and loss, and moving the point. */
@Composable private fun CoverageTransmitterCard(model: EstimatedCoverageViewModel) {
    var showsCoordinates by rememberSaveable { mutableStateOf(false) }
    val point = model.coordinate
    val title = if (point == null) stringResource(R.string.coverage_transmitter_not_placed) else model.transmitterName ?: stringResource(R.string.coverage_dropped_pin)
    val antenna = stringResource(R.string.coverage_antenna_dbi, model.displayNumber("txHeight"), model.heightUnit, model.displayNumber("txGain"))
    val place = point?.let { String.format("%.4f, %.4f", it.latitude, it.longitude) }
    CoverageDisclosure(stringResource(R.string.coverage_transmitter_settings), listOfNotNull(title, listOfNotNull(place, antenna).joinToString(" · ")),
        model.transmitterExpanded, { model.transmitterExpanded = !model.transmitterExpanded }) {
        CoverageHeightSetting(model, "txHeight", CoverageHeightPreset.transmitter)
        CoverageAntennaSetting(model, "txGain")
        CoverageNumberField(model, "txLoss", stringResource(R.string.coverage_field_system_loss), "dB", GlossaryTerm.CABLE_LOSS)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.isChoosingLocation = true }) { Text(if (point != null) stringResource(R.string.coverage_choose_new_point) else stringResource(R.string.coverage_choose_on_map_2)) }
            Button(onClick = { showsCoordinates = !showsCoordinates }) { Text(if (showsCoordinates) stringResource(R.string.coverage_hide_coordinates) else stringResource(R.string.coverage_enter_coordinates)) }
        }
        CoverageCurrentLocationButton(model)
        if (showsCoordinates) {
            LearnLabel(stringResource(R.string.coverage_coordinates), GlossaryTerm.PLANNING_COORDINATES)
            CoverageTextField(stringResource(R.string.coverage_latitude), model.latitudeText, model.issue("latitude")) { model.typeCoordinates(it, model.longitudeText) }
            CoverageTextField(stringResource(R.string.coverage_longitude), model.longitudeText, model.issue("longitude")) { model.typeCoordinates(model.latitudeText, it) }
        }
    }
}

/** Region and modem presets, then the values a preset sets. Editing one makes the radio custom. */
@Composable private fun CoverageRadioCard(model: EstimatedCoverageViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var customizing by rememberSaveable { mutableStateOf(false) }
    val preset = model.radioPreset
    val summary = listOfNotNull("${model.displayNumber("frequency")} MHz", preset?.modemSummary(),
        "${model.displayNumber("power")} dBm", stringResource(R.string.coverage_radio_sensitivity_summary, model.displayNumber("sensitivity"))).joinToString(" · ")
    CoverageDisclosure(stringResource(R.string.coverage_radio_settings), listOf(summary, stringResource(R.string.coverage_radio_reserve_summary, model.displayNumber("reserve"))), expanded, { expanded = !expanded }) {
        CoverageRadioPresetMenu(preset, model::applyRadioPreset)
        TextButton(onClick = { customizing = !customizing }, contentPadding = PaddingValues(0.dp)) {
            Text(if (customizing) stringResource(R.string.coverage_hide_radio_values) else if (preset == null) stringResource(R.string.coverage_customize_radio_edited) else stringResource(R.string.coverage_customize_radio))
        }
        if (customizing) {
            CoverageNumberField(model, "frequency", stringResource(R.string.coverage_field_frequency), "MHz", GlossaryTerm.RADIO_FREQUENCY)
            CoverageNumberField(model, "power", stringResource(R.string.coverage_field_transmit_power), "dBm", GlossaryTerm.TRANSMIT_POWER)
            CoverageNumberField(model, "sensitivity", stringResource(R.string.coverage_field_receiver_sensitivity), "dBm", GlossaryTerm.RECEIVER_SENSITIVITY)
        }
        LearnLabel(stringResource(R.string.coverage_planning_reserve), GlossaryTerm.PLANNING_RESERVE)
        val reserve = model.number("reserve")
        CoveragePresetRow(CoverageReservePreset.choicesDb.map { db ->
            Triple(if (db == 0.0) stringResource(R.string.coverage_none) else "${db.editorText()} dB", reserve == db) { model.set("reserve", db.editorText()) }
        })
        CoverageNumberField(model, "reserve", stringResource(R.string.coverage_field_reserve), "dB", null)
    }
}

/** The assumed receiver: the same for the map and every known node. */
@Composable private fun CoverageReceiverCard(model: EstimatedCoverageViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CoverageDisclosure(stringResource(R.string.coverage_receiver_assumptions),
        listOf(stringResource(R.string.coverage_antenna_dbi_loss_db, model.displayNumber("rxHeight"), model.heightUnit, model.displayNumber("rxGain"), model.displayNumber("rxLoss"))),
        expanded, { expanded = !expanded }) {
        Text(stringResource(R.string.coverage_one_assumed_receiver_for_the_coverage_map_and),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CoverageHeightSetting(model, "rxHeight", CoverageHeightPreset.receiver)
        CoverageAntennaSetting(model, "rxGain")
        CoverageNumberField(model, "rxLoss", stringResource(R.string.coverage_field_system_loss), "dB", GlossaryTerm.CABLE_LOSS)
    }
}

/** Study radius in whole miles or kilometres, then the sample spacing and the work check. */
@Composable private fun CoverageStudyAreaCard(model: EstimatedCoverageViewModel) {
    val unit = LocalDistanceUnit.current
    val usesMiles = unit.usesMiles
    val step = if (usesMiles) 2.0 else 5.0
    val maximum = if (usesMiles) 50.0 else 80.0
    val metersPerUnit = if (usesMiles) 1609.344 else 1000.0
    var draft by remember { mutableStateOf<Float?>(null) }
    val shown = draft?.toDouble() ?: model.radiusMeters / metersPerUnit
    fun setRadius(value: Double) = model.setRadius((value.coerceIn(step, maximum) * metersPerUnit).let { kotlin.math.round(it).toInt() })
    fun adjust(increasing: Boolean) {
        // Snap off-step radii only in response to an explicit adjustment.
        val steps = shown / step
        val next = if (increasing) kotlin.math.floor(steps + 0.0005) + 1 else kotlin.math.ceil(steps - 0.0005) - 1
        setRadius(next * step)
    }
    val label = stringResource(R.string.coverage_value, formatDisplayDecimal(shown, 3), if (usesMiles) "mi" else "km")
    CoverageCard {
        Text(stringResource(R.string.coverage_study_area), style = MaterialTheme.typography.titleMedium)
        LearnLabel(stringResource(R.string.coverage_study_radius), GlossaryTerm.STUDY_RADIUS)
        Text(label, fontFamily = MonaspaceNeon)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { adjust(false) }, enabled = shown > step) { Text(stringResource(R.string.coverage_value_2), style = MaterialTheme.typography.titleLarge) }
            Slider(value = shown.coerceIn(step, maximum).toFloat(), onValueChange = { draft = it },
                onValueChangeFinished = { draft?.let { setRadius(it.toDouble()) }; draft = null },
                valueRange = step.toFloat()..maximum.toFloat(), steps = ((maximum - step) / step).toInt() - 1,
                modifier = Modifier.weight(1f).withDescription(stringResource(R.string.coverage_study_radius)) { description -> contentDescription = description; stateDescription = label })
            IconButton(onClick = { adjust(true) }, enabled = shown < maximum - 0.0005) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
        Text(stringResource(R.string.coverage_doubling_the_radius_roughly_quadruples_the_map_samples),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LearnLabel(stringResource(R.string.coverage_sample_spacing), GlossaryTerm.COVERAGE_DETAIL)
        val choices = (if (usesMiles) EstimatedCoverageViewModel.imperialSpacings else EstimatedCoverageViewModel.metricSpacings).let {
            if (model.spacingMeters in it) it else (it + model.spacingMeters).sorted()
        }
        CoverageSpacingMenu(model.spacingMeters, choices, unit, model::setSpacing)
        val grid = model.grid
        if (grid != null) {
            CoverageValueRow(stringResource(R.string.coverage_map_samples), stringResource(R.string.coverage_d).format(grid.count))
            CoverageValueRow(stringResource(R.string.coverage_minimum_terrain_samples), model.minimumWorkSamples?.let { "%,d".format(it) } ?: "—")
            Text(stringResource(R.string.coverage_lower_bound_only_extra_terrain_detail_and_refinement),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (model.workFits) {
                null -> Text(stringResource(R.string.coverage_checking_calculation_limits), style = MaterialTheme.typography.bodySmall)
                false -> Text(stringResource(R.string.coverage_this_area_needs_more_calculation_work_than_the),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                true -> Text(stringResource(R.string.coverage_minimum_work_fits_one_batch_complex_terrain_can), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Text(stringResource(R.string.coverage_this_radius_and_detail_exceed_the_current_grid), color = Color(0xFFFF9500))
    }
}

/** The model's assumptions to accept, the run's messages, and the result. */
@Composable private fun CoverageCalculationCard(model: EstimatedCoverageViewModel, showNodes: () -> Unit) {
    CoverageCard {
        Text(stringResource(R.string.coverage_calculation), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.coverage_beta_estimate_terrain_only_inland_paths_vertical_polarization),
            style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.coverage_use_these_model_assumptions), Modifier.weight(1f))
            Switch(model.acceptsScenario, model::updateAcceptsScenario)
        }
        Text(stringResource(R.string.coverage_downloads_terrain_on_demand_up_to_500_mb),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model.coordinate != null) OutlinedButton(onClick = showNodes) { Text(stringResource(R.string.coverage_known_nodes, model.knownNodes.size)) }
        when (model.runner.snapshot.phase) {
            CoverageStudyPublicationPhase.cancelled -> Text(stringResource(R.string.coverage_calculation_cancelled_no_partial_result_was_published), style = MaterialTheme.typography.bodySmall)
            CoverageStudyPublicationPhase.stale -> Text(stringResource(R.string.coverage_inputs_or_catalog_changed_calculate_again_to_update), style = MaterialTheme.typography.bodySmall)
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
    LearnLabel(stringResource(R.string.coverage_antenna_height), GlossaryTerm.ANTENNA_HEIGHT)
    CoveragePresetRow(presets.map { preset ->
        // Imperial chips advertise whole feet; apply that exact displayed height.
        val editorValue = if (feet) String.format(Locale.ROOT, "%.0f", preset.meters / 0.3048) else preset.meters.editorText()
        val text = editorValue.replace('.', java.text.DecimalFormatSymbols.getInstance().decimalSeparator)
        Triple("${preset.title.asString()} · $text ${model.heightUnit}", meters != null && CoverageHeightPreset.matching(meters, presets) == preset) {
            model.set(field, editorValue)
        }
    })
    CoverageNumberField(model, field, stringResource(R.string.coverage_field_above_ground), model.heightUnit, null)
}

/** An antenna type whose gain fills the field, or a custom gain. */
@Composable private fun CoverageAntennaSetting(model: EstimatedCoverageViewModel, field: String) {
    val gain = model.number(field)
    AntennaPresetMenu(gain) { model.set(field, it.editorText()) }
    CoverageNumberField(model, field, stringResource(R.string.coverage_field_antenna_gain), "dBi", null)
}

@Composable internal fun AntennaPresetMenu(gain: Double?, choose: (Double) -> Unit) {
    val match = gain?.let(CoverageAntennaPreset::matching)
    var open by remember { mutableStateOf(false) }
    LearnLabel(stringResource(R.string.coverage_antenna), GlossaryTerm.ANTENNA_GAIN)
    Box {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text(match?.title?.asString() ?: stringResource(R.string.coverage_custom), Modifier.weight(1f)); Icon(Icons.Filled.ExpandMore, null) }
        DropdownMenu(open, { open = false }) {
            CoverageAntennaPreset.all.forEach { preset ->
                DropdownMenuItem(text = { Text(stringResource(R.string.coverage_dbi, preset.title.asString(), formatDisplayDecimal(preset.gainDbi, 2))) },
                    trailingIcon = if (preset == match) ({ Text(stringResource(R.string.coverage_value_3)) }) else null,
                    onClick = { choose(preset.gainDbi); open = false })
            }
        }
    }
}

@Composable private fun CoverageRadioPresetMenu(current: CoverageRadioPreset?, choose: (CoverageRadioPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(current?.let { "${it.family.title} · ${it.title.asString()}" } ?: stringResource(R.string.coverage_custom_radio))
                current?.let { Text(it.modemSummary(), style = MaterialTheme.typography.bodySmall) }
            }
            Icon(Icons.Filled.ExpandMore, null)
        }
        DropdownMenu(open, { open = false }) {
            CoverageRadioPreset.Family.entries.forEach { family ->
                Text(family.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                CoverageRadioPreset.presets(family).forEach { preset ->
                    DropdownMenuItem(text = {
                        Column { Text(preset.title.asString()); Text(stringResource(R.string.coverage_mhz, preset.modemSummary(), formatDisplayDecimal(preset.frequencyMHz, 3)), style = MaterialTheme.typography.bodySmall) }
                    }, trailingIcon = if (preset == current) ({ Text(stringResource(R.string.coverage_value_3)) }) else null, onClick = { choose(preset); open = false })
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
    CoverageTextField(if (term != null) stringResource(R.string.coverage_value_4, label, unit) else stringResource(R.string.coverage_value_4, label, unit), model.values[field].orEmpty(), model.issue(field)) { model.set(field, it) }
}

@Composable private fun CoverageTextField(label: String, value: String, issue: CoverageSetupIssue?, change: (String) -> Unit) {
    val error = issue?.takeIf { it != CoverageSetupIssue.required }?.message()
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        isError = error != null, supportingText = error?.let { { Text(it) } },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonaspaceNeon),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
}

@Composable
private fun CoverageSetupIssue.message(): String = when (this) {
    CoverageSetupIssue.required -> stringResource(R.string.coverage_enter_a_value)
    CoverageSetupIssue.number -> stringResource(R.string.coverage_enter_a_finite_number_using_a_decimal_point)
    CoverageSetupIssue.latitude -> stringResource(R.string.coverage_coverage_calculations_support_latitude_from_85_to_85)
    CoverageSetupIssue.longitude -> stringResource(R.string.coverage_longitude_must_be_between_180_and_180)
    CoverageSetupIssue.frequency -> stringResource(R.string.coverage_frequency_must_be_between_30_and_6000_mhz)
    CoverageSetupIssue.height -> stringResource(R.string.coverage_antenna_height_must_be_between_1_and_3000)
    CoverageSetupIssue.nonnegative -> stringResource(R.string.coverage_enter_zero_or_a_positive_value)
    CoverageSetupIssue.unknownField -> stringResource(R.string.coverage_this_field_is_not_supported)
}

/** The last calculation report, as iOS: collapsed by default, shared only when asked. */
@Composable private fun CoverageReportCard(report: String?) {
    report ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.coverage_last_calculation_report), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                val disclosure = stringResource(if (open) R.string.coverage_disclosure_collapse else R.string.coverage_disclosure_expand)
                Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = disclosure })
            }
            if (open) {
                Text(stringResource(R.string.coverage_includes_coordinates_radio_assumptions_work_counters_and_available),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.coverage_coverage_calculation_report))
                        .putExtra(android.content.Intent.EXTRA_TEXT, report)
                    context.startActivity(android.content.Intent.createChooser(send, context.getString(R.string.coverage_share_calculation_report_2)))
                }) { Text(stringResource(R.string.coverage_share_calculation_report)) }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(report, fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
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
                    trailingIcon = if (choice == meters) ({ Text(stringResource(R.string.coverage_value_3)) }) else null)
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
    if (unit.usesMiles) {
        mapOf(201 to "⅛ mi", 402 to "¼ mi", 805 to "½ mi", 1609 to "1 mi", 2414 to "1½ mi", 3219 to "2 mi", 8047 to "5 mi")[meters]?.let { return it }
        return formatDistance(meters / 1000.0, unit)
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
                            model.place(result.coordinate, context.getString(R.string.coverage_current_location))
                            message = context.getString(R.string.coverage_check_the_pin_on_the_map_especially_when)
                        } else message = context.getString(R.string.coverage_your_location_is_outside_the_supported_latitude_range)
                    }
                    LocationResult.ServicesOff -> message = context.getString(R.string.coverage_turn_on_location_services_or_choose_a_point)
                    LocationResult.Unavailable -> message = context.getString(R.string.coverage_couldn_t_find_your_location_try_again_or)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) message = context.getString(R.string.coverage_couldn_t_get_your_location_check_location_permission)
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
                message = context.getString(R.string.coverage_location_permission_is_off_allow_it_in_the)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { cancel() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                generation++
                locating = true
                message = null
                if (hasLocationPermission(context)) locate() else permission.launch(LOCATION_PERMISSIONS)
            }, modifier = Modifier.weight(1f), enabled = !locating) { Text(stringResource(R.string.coverage_use_current_location)) }
            if (locating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = { cancel() }) { Text(stringResource(R.string.common_cancel)) }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// MARK: - Known nodes

@Composable private fun CoverageKnownNodesDialog(model: EstimatedCoverageViewModel, dismiss: () -> Unit) {
    val statuses = model.nodeStatuses
    val result = model.activeResult
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.coverage_known_nodes_2)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.coverage_this_list_shows_the_nearest_200_located_nodes),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.knownNodes.isEmpty()) Text(if (model.coordinate == null) stringResource(R.string.coverage_place_a_transmitter_to_find_nearby_known_nodes)
                else stringResource(R.string.coverage_no_located_nodes_are_available_in_the_current))
            model.knownNodes.forEach { node ->
                val status = statuses[node.publicKey]
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(node.displayName, fontFamily = MonaspaceNeon, style = MaterialTheme.typography.titleSmall)
                    Text(status?.coverageLabel()?.asString() ?: stringResource(R.string.coverage_not_evaluated), style = MaterialTheme.typography.bodySmall)
                    status?.adjustedMarginDb?.let { margin ->
                        Text(stringResource(R.string.coverage_inspection_margin, String.format("%.1f", margin)), fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelMedium)
                        result?.let {
                            Text(stringResource(R.string.coverage_inspection_receive_level, String.format("%.1f", margin + it.budget.sensitivityDbm + it.budget.reserveDb)),
                                fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) } })
}

// MARK: - Legend

/** Swatches on the map once a result shows; the dialog explains what the bands do and don't mean. */
@Composable internal fun CoverageMarginLegend() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) {
        Column {
            Text(stringResource(R.string.coverage_predicted_margin), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverageMarginSwatch(CoverageRasterClass.belowThreshold, "<0")
                CoverageMarginSwatch(CoverageRasterClass.limitedHeadroom, "0–10")
                CoverageMarginSwatch(CoverageRasterClass.aboveThreshold, ">10 dB")
                CoverageMarginSwatch(CoverageRasterClass.unresolved, "?")
            }
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.coverage_coverage_colors)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.coverage_margin_after_planning_reserve), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.coverage_red_below_0_db_predicted_below_threshold))
            Text(stringResource(R.string.coverage_amber_0_10_db_limited_predicted_headroom))
            Text(stringResource(R.string.coverage_green_above_10_db_greater_predicted_headroom))
            Text(stringResource(R.string.coverage_gray_hatching_no_usable_prediction))
            Text(stringResource(R.string.coverage_outside_the_study_radius_is_unshaded_the_area),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.coverage_what_this_means), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.coverage_margin_is_the_predicted_received_signal_minus_receiver))
            Text(stringResource(R.string.coverage_green_does_not_mean_reachable_this_is_a))
            Text(stringResource(R.string.coverage_map_cells_use_the_nearest_calculated_sample_known))
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_done)) } })
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

private fun EstimatedCoverageViewModel.displayNumber(field: String): String =
    number(field)?.let { formatDisplayDecimal(it, 12) } ?: values[field].orEmpty()
