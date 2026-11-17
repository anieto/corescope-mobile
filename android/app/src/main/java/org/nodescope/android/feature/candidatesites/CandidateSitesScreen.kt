package org.nodescope.android.feature.candidatesites

import org.nodescope.android.core.design.parseDecimalInput
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.nodescope.android.core.design.LocalDistanceUnit
import org.nodescope.android.feature.map.LOCATION_PERMISSIONS
import org.nodescope.android.feature.map.LocationResult
import org.nodescope.android.feature.map.currentLocation
import org.nodescope.android.feature.map.hasLocationPermission
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.core.design.meshPageBackground
import org.nodescope.android.feature.learn.GlossaryTerm
import org.nodescope.android.feature.learn.LearnButton
import java.util.UUID

@Composable
fun CandidateSitesScreen(model: CandidatePlansViewModel, heightInFeet: Boolean, onMoved: () -> Unit) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<SavedPlansLibrary.CandidateConversion?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TermTitle(stringResource(R.string.planner_deployment_plans), GlossaryTerm.DEPLOYMENT_PLAN)
            Text(stringResource(R.string.planner_candidate_sites_are_moving_into_saved_plans_each))
            if (model.loaded && model.plans.isNotEmpty())
                Button(onClick = { moving = model.conversion(heightInFeet) }, enabled = !model.busy) { Text(stringResource(R.string.planner_move_to_saved_plans)) }
            model.error?.let {
                Text(it.asString(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::reload, enabled = !model.busy) { Text(stringResource(R.string.planner_reload_plans)) }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.loaded) {
                if (model.plans.isEmpty()) Text(stringResource(R.string.planner_no_candidate_sites_plan_new_sites_in_saved))
                model.plans.forEach { plan -> key(plan.id) {
                    Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(plan.name, style = MaterialTheme.typography.titleMedium)
                            Text(pluralStringResource(R.plurals.planner_sites, plan.sites.size, plan.sites.size), fontFamily = MonaspaceNeon)
                            Row {
                                TextButton(onClick = { model.open(plan) }, enabled = !model.busy) { Text(stringResource(R.string.planner_open)) }
                                TextButton(onClick = { deleting = plan.id }, enabled = !model.busy) { Text(stringResource(R.string.planner_delete_plan)) }
                            }
                        }
                    }
                } }
            }
        }
    }
    deleting?.let { id ->
        ConfirmDiscard(stringResource(R.string.planner_delete_this_plan_and_its_saved_sites), stringResource(R.string.planner_delete_plan), { deleting = null }) {
            model.plans.firstOrNull { it.id == id }?.let(model::delete)
            deleting = null
        }
    }
    moving?.let { conversion ->
        AlertDialog(onDismissRequest = { moving = null }, title = { Text(stringResource(R.string.planner_move_to_saved_plans_2)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val plans = conversion.plans.size
                    val studies = conversion.studies.size
                    Text(stringResource(R.string.planner_create_coverage_studies_summary,
                        pluralStringResource(R.plurals.planner_plan_count, plans, plans),
                        pluralStringResource(R.plurals.planner_coverage_studies_count, studies, studies)))
                    conversion.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null; model.move(conversion, onMoved) }) { Text(stringResource(R.string.planner_move)) } },
            dismissButton = { TextButton(onClick = { moving = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    model.editing?.let { initial -> key(initial.id) { CandidatePlanEditor(initial, model) } }
}

private val planSaver = Saver<DeploymentPlan, String>(
    save = { Json.encodeToString(it) }, restore = { Json.decodeFromString<DeploymentPlan>(it) })

@Composable
private fun CandidatePlanEditor(initial: DeploymentPlan, model: CandidatePlansViewModel) {
    var draft by rememberSaveable(initial.id, stateSaver = planSaver) { mutableStateOf(initial) }
    var siteEditor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val close = { if (!model.busy) { if (draft != initial) discard = true else model.closeEditor() } }
    CandidateEditorDialog(stringResource(R.string.planner_edit_plan), close, stringResource(R.string.planner_save_plan), { model.save(draft) },
        !model.busy && runCatching { draft.validate() }.isSuccess, model.busy) {
        Card(colors = meshCardColors(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TermTitle(stringResource(R.string.planner_deployment_plan), GlossaryTerm.DEPLOYMENT_PLAN)
                OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth(),
                    enabled = !model.busy, label = { Text(stringResource(R.string.planner_plan_name)) }, singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = MonaspaceNeon))
                Text(stringResource(R.string.planner_changes_are_saved_only_when_you_choose_save), style = MaterialTheme.typography.bodySmall)
            }
        }
        TermTitle(stringResource(R.string.planner_candidate_sites), GlossaryTerm.CANDIDATE_SITE)
        draft.sites.forEach { site -> key(site.id) {
            var menu by remember { mutableStateOf(false) }
            Card(colors = meshCardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(site.name, style = MaterialTheme.typography.titleMedium)
                    if (draft.selectedSiteID == site.id) Text(stringResource(R.string.planner_selected_site), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.planner_value_2, site.latitude, site.longitude), fontFamily = MonaspaceNeon)
                    Row {
                        TextButton(onClick = { siteEditor = site.id }, enabled = !model.busy) { Text(stringResource(R.string.planner_edit)) }
                        TextButton(onClick = { draft = draft.copy(selectedSiteID = site.id) },
                            enabled = !model.busy && draft.selectedSiteID != site.id) { Text(stringResource(R.string.planner_select)) }
                        Box {
                            TextButton(onClick = { menu = true }, enabled = !model.busy) { Text(stringResource(R.string.planner_more)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.planner_duplicate)) }, enabled = draft.sites.size < 100, onClick = {
                                    val copy = site.copy(id = UUID.randomUUID().toString())
                                    draft = draft.copy(sites = draft.sites + copy, selectedSiteID = copy.id)
                                    menu = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.planner_remove_site)) }, onClick = { deleting = site.id; menu = false })
                            }
                        }
                    }
                }
            }
        } }
        TextButton(onClick = { siteEditor = "new" }, enabled = !model.busy && draft.sites.size < 100) { Text(stringResource(R.string.planner_add_site)) }
        model.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
    }
    if (discard) ConfirmDiscard(stringResource(R.string.planner_discard_unsaved_changes), stringResource(R.string.planner_discard_changes), { discard = false }, model::closeEditor)
    deleting?.let { id ->
        ConfirmDiscard(stringResource(R.string.planner_remove_this_site_from_the_plan), stringResource(R.string.planner_remove_site), { deleting = null }) {
            val remaining = draft.sites.filterNot { it.id == id }
            draft = draft.copy(sites = remaining, selectedSiteID = if (draft.selectedSiteID == id) remaining.firstOrNull()?.id else draft.selectedSiteID)
            deleting = null
        }
    }
    siteEditor?.let { id -> key(id) {
        CandidateSiteEditor(draft.sites.firstOrNull { it.id == id }, { siteEditor = null }) { site ->
            val sites = if (draft.sites.any { it.id == site.id }) draft.sites.map { if (it.id == site.id) site else it } else draft.sites + site
            draft = draft.copy(sites = sites, selectedSiteID = site.id)
            siteEditor = null
        }
    } }
}

private enum class CandidateInputField(@param:StringRes val labelRes: Int, val term: GlossaryTerm) {
    LATITUDE(R.string.planner_latitude_90_to_90, GlossaryTerm.PLANNING_COORDINATES),
    LONGITUDE(R.string.planner_longitude_180_to_180, GlossaryTerm.PLANNING_COORDINATES),
    HEIGHT(R.string.planner_antenna_height_m_0_10000, GlossaryTerm.ANTENNA_HEIGHT),
    FREQUENCY(R.string.planner_frequency_mhz_0_001_100000, GlossaryTerm.RADIO_FREQUENCY),
    POWER(R.string.planner_transmit_power_dbm_200_100, GlossaryTerm.TRANSMIT_POWER),
    GAIN(R.string.planner_antenna_gain_dbi_100_100, GlossaryTerm.ANTENNA_GAIN),
    CABLE(R.string.planner_cable_loss_db_0_200, GlossaryTerm.CABLE_LOSS),
    ADDITIONAL(R.string.planner_additional_loss_db_0_200, GlossaryTerm.ADDITIONAL_LOSS),
}

@Composable
private fun CandidateSiteEditor(site: CandidateSite?, close: () -> Unit, apply: (CandidateSite) -> Unit) {
    val resources = LocalResources.current
    val preference = LocalDistanceUnit.current
    // Keep this draft's unit stable across recomposition and activity recreation.
    val heightInFeet = rememberSaveable { preference.usesFeet }
    val original = remember(site, heightInFeet) { listOf(site?.latitude, site?.longitude,
        site?.assumptions?.antennaHeightMeters?.let { if (heightInFeet) it / 0.3048 else it },
        site?.assumptions?.frequencyMHz, site?.assumptions?.transmitPowerDBm, site?.assumptions?.antennaGainDBi,
        site?.assumptions?.cableLossDB, site?.assumptions?.additionalLossDB).map { it?.toString().orEmpty() } }
    var name by rememberSaveable { mutableStateOf(site?.name.orEmpty()) }
    var values by rememberSaveable { mutableStateOf(original) }
    var showsMap by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val locationScope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var locationJob by remember { mutableStateOf<Job?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    fun locate() {
        locationJob = locationScope.launch {
            try {
                when (val result = currentLocation(context)) {
                    is LocationResult.Found -> {
                        values = values.toMutableList().also {
                            it[0] = result.coordinate.latitude.toString()
                            it[1] = (if (result.coordinate.longitude == 180.0) -180.0 else result.coordinate.longitude).toString()
                        }
                        locationMessage = resources.getString(R.string.planner_location_added_to_this_draft_check_the_pin)
                    }
                    LocationResult.ServicesOff -> locationMessage = resources.getString(R.string.planner_turn_on_location_services_or_choose_a_point)
                    LocationResult.Unavailable -> locationMessage = resources.getString(R.string.planner_couldn_t_find_your_location_try_again_or)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                locationMessage = resources.getString(R.string.planner_couldn_t_get_your_location_check_location_permission)
            } finally {
                locating = false
            }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (locating) {
            if (grants.values.any { it }) locate()
            else {
                locating = false
                locationMessage = resources.getString(R.string.planner_location_permission_is_off_allow_it_in_the)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { locationJob?.cancel(); locating = false }
    val cancel = {
        locationJob?.cancel()
        locating = false
        if (name != site?.name.orEmpty() || values != original) discard = true else close()
    }
    CandidateEditorDialog(if (site == null) stringResource(R.string.planner_add_site_2) else stringResource(R.string.planner_edit_site), cancel, stringResource(R.string.planner_apply), {
        try {
            val result = CandidateSiteInput.make(site?.id ?: UUID.randomUUID().toString(), name, values, heightInFeet)
            // Preserve the exact stored height when the displayed field was not edited.
            apply(if (values[2] == original[2]) result.copy(assumptions = result.assumptions.copy(
                antennaHeightMeters = site?.assumptions?.antennaHeightMeters)) else result)
        } catch (_: Exception) {
            error = resources.getString(R.string.planner_check_the_site_name_coordinates_and_displayed_numeric)
        }
    }, !locating, false) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.planner_site_name)) }, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = MonaspaceNeon))
        Text(stringResource(R.string.planner_coordinates_are_required_leave_unknown_equipment_values_blank))
        TextButton(onClick = { showsMap = true }, enabled = !locating) { Text(stringResource(R.string.planner_choose_on_map)) }
        TextButton(onClick = {
            locating = true
            locationMessage = null
            if (hasLocationPermission(context)) locate() else locationPermission.launch(LOCATION_PERMISSIONS)
        }, enabled = !locating) { Text(stringResource(R.string.planner_use_current_location)) }
        if (locating) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.planner_finding_location))
            TextButton(onClick = { locationJob?.cancel(); locating = false }) { Text(stringResource(R.string.planner_cancel_location_request)) }
        }
        locationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        CandidateInputField.entries.forEach { field -> key(field) {
            val label = if (field == CandidateInputField.HEIGHT && heightInFeet) stringResource(R.string.planner_antenna_height_ft) else stringResource(field.labelRes)
            Card(colors = meshCardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); LearnButton(field.term) }
                    if (field == CandidateInputField.HEIGHT && heightInFeet) {
                        Text(stringResource(R.string.planner_range_0_to_10_000_meters_about_32), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(values[field.ordinal], { text -> values = values.toMutableList().also { it[field.ordinal] = text } },
                        Modifier.fillMaxWidth(), singleLine = true, enabled = !locating, label = { Text(label) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        textStyle = LocalTextStyle.current.copy(fontFamily = MonaspaceNeon))
                }
            }
        } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (discard) ConfirmDiscard(stringResource(R.string.planner_discard_site_changes), stringResource(R.string.planner_discard_changes), { discard = false }, close)
    if (showsMap) CandidateSiteMapPicker(values[0].parseDecimalInput(),
        values[1].parseDecimalInput(), { showsMap = false }) { latitude, longitude ->
        values = values.toMutableList().also { it[0] = latitude.toString(); it[1] = longitude.toString() }
        showsMap = false
    }
}

@Composable
private fun TermTitle(title: String, term: GlossaryTerm) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        LearnButton(term)
    }
}

@Composable
private fun CandidateEditorDialog(title: String, close: () -> Unit, saveLabel: String, save: () -> Unit,
                                  saveEnabled: Boolean, busy: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().meshPageBackground().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.planner_close)) }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = save, enabled = saveEnabled) { Text(saveLabel) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                }
            }
        }
    }
}

@Composable
private fun ConfirmDiscard(title: String, action: String, cancel: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text(title) },
        confirmButton = { TextButton(onClick = confirm) { Text(action) } },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) } })
}
