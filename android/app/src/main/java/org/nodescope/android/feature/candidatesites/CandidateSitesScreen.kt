package org.nodescope.android.feature.candidatesites

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.nodescope.android.core.design.LocalDistanceUnit
import org.nodescope.android.core.storage.DistanceUnit
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
fun CandidateSitesScreen(model: CandidatePlansViewModel) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TermTitle("Deployment plans", GlossaryTerm.DEPLOYMENT_PLAN)
            Text("Save proposed locations and equipment assumptions on this device. Coverage and potential-link analysis are not available here yet.")
            model.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::reload, enabled = !model.busy) { Text("Reload plans") }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.loaded) {
                Button(onClick = { model.open(CandidatePlansViewModel.newPlan()) }, enabled = !model.busy && model.plans.size < 100) { Text("New plan") }
                if (model.plans.isEmpty()) Text("No saved plans. Create one, then add your candidate sites.")
                model.plans.forEach { plan -> key(plan.id) {
                    Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(plan.name, style = MaterialTheme.typography.titleMedium)
                            Text("${plan.sites.size} sites", fontFamily = MonaspaceNeon)
                            Row {
                                TextButton(onClick = { model.open(plan) }, enabled = !model.busy) { Text("Open") }
                                TextButton(onClick = { deleting = plan.id }, enabled = !model.busy) { Text("Delete plan") }
                            }
                        }
                    }
                } }
            }
        }
    }
    deleting?.let { id ->
        ConfirmDiscard("Delete this plan and its saved sites?", "Delete plan", { deleting = null }) {
            model.plans.firstOrNull { it.id == id }?.let(model::delete)
            deleting = null
        }
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
    CandidateEditorDialog("Edit plan", close, "Save plan", { model.save(draft) },
        !model.busy && runCatching { draft.validate() }.isSuccess, model.busy) {
        Card(colors = meshCardColors(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TermTitle("Deployment plan", GlossaryTerm.DEPLOYMENT_PLAN)
                OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth(),
                    enabled = !model.busy, label = { Text("Plan name") }, singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = MonaspaceNeon))
                Text("Changes are saved only when you choose Save plan.", style = MaterialTheme.typography.bodySmall)
            }
        }
        TermTitle("Candidate sites", GlossaryTerm.CANDIDATE_SITE)
        draft.sites.forEach { site -> key(site.id) {
            var menu by remember { mutableStateOf(false) }
            Card(colors = meshCardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(site.name, style = MaterialTheme.typography.titleMedium)
                    if (draft.selectedSiteID == site.id) Text("Selected site", style = MaterialTheme.typography.bodySmall)
                    Text("${site.latitude}, ${site.longitude}", fontFamily = MonaspaceNeon)
                    Row {
                        TextButton(onClick = { siteEditor = site.id }, enabled = !model.busy) { Text("Edit") }
                        TextButton(onClick = { draft = draft.copy(selectedSiteID = site.id) },
                            enabled = !model.busy && draft.selectedSiteID != site.id) { Text("Select") }
                        Box {
                            TextButton(onClick = { menu = true }, enabled = !model.busy) { Text("More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Duplicate") }, enabled = draft.sites.size < 100, onClick = {
                                    val copy = site.copy(id = UUID.randomUUID().toString())
                                    draft = draft.copy(sites = draft.sites + copy, selectedSiteID = copy.id)
                                    menu = false
                                })
                                DropdownMenuItem(text = { Text("Remove site") }, onClick = { deleting = site.id; menu = false })
                            }
                        }
                    }
                }
            }
        } }
        TextButton(onClick = { siteEditor = "new" }, enabled = !model.busy && draft.sites.size < 100) { Text("Add site") }
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (discard) ConfirmDiscard("Discard unsaved changes?", "Discard changes", { discard = false }, model::closeEditor)
    deleting?.let { id ->
        ConfirmDiscard("Remove this site from the plan?", "Remove site", { deleting = null }) {
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

private enum class CandidateInputField(val label: String, val term: GlossaryTerm) {
    LATITUDE("Latitude (−90 to 90)", GlossaryTerm.PLANNING_COORDINATES),
    LONGITUDE("Longitude (−180 to 180)", GlossaryTerm.PLANNING_COORDINATES),
    HEIGHT("Antenna height (m, 0–10000)", GlossaryTerm.ANTENNA_HEIGHT),
    FREQUENCY("Frequency (MHz, 0.001–100000)", GlossaryTerm.RADIO_FREQUENCY),
    POWER("Transmit power (dBm, −200–100)", GlossaryTerm.TRANSMIT_POWER),
    GAIN("Antenna gain (dBi, −100–100)", GlossaryTerm.ANTENNA_GAIN),
    CABLE("Cable loss (dB, 0–200)", GlossaryTerm.CABLE_LOSS),
    ADDITIONAL("Additional loss (dB, 0–200)", GlossaryTerm.ADDITIONAL_LOSS),
}

@Composable
private fun CandidateSiteEditor(site: CandidateSite?, close: () -> Unit, apply: (CandidateSite) -> Unit) {
    val preference = LocalDistanceUnit.current
    // Keep this draft's unit stable across recomposition and activity recreation.
    val heightInFeet = rememberSaveable { preference == DistanceUnit.IMPERIAL }
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
                        locationMessage = "Location added to this draft. Check the pin on the map, especially when using approximate location."
                    }
                    LocationResult.ServicesOff -> locationMessage = "Turn on location services, or choose a point on the map."
                    LocationResult.Unavailable -> locationMessage = "Couldn't find your location. Try again, or choose a point on the map."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                locationMessage = "Couldn't get your location. Check location permission and services in Settings, or choose a point on the map."
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
                locationMessage = "Location permission is off. Allow it in the app's settings, or choose a point on the map."
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { locationJob?.cancel(); locating = false }
    val cancel = {
        locationJob?.cancel()
        locating = false
        if (name != site?.name.orEmpty() || values != original) discard = true else close()
    }
    CandidateEditorDialog(if (site == null) "Add site" else "Edit site", cancel, "Apply", {
        try {
            val result = CandidateSiteInput.make(site?.id ?: UUID.randomUUID().toString(), name, values, heightInFeet)
            // Preserve the exact stored height when the displayed field was not edited.
            apply(if (values[2] == original[2]) result.copy(assumptions = result.assumptions.copy(
                antennaHeightMeters = site?.assumptions?.antennaHeightMeters)) else result)
        } catch (_: Exception) {
            error = "Check the site name, coordinates, and displayed numeric ranges. Try a shorter name if needed. Optional fields may be blank."
        }
    }, !locating, false) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Site name") }, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = MonaspaceNeon))
        Text("Coordinates are required. Leave unknown equipment values blank. Coordinates use decimal degrees; a minus sign means south or west.")
        TextButton(onClick = { showsMap = true }, enabled = !locating) { Text("Choose on map") }
        TextButton(onClick = {
            locating = true
            locationMessage = null
            if (hasLocationPermission(context)) locate() else locationPermission.launch(LOCATION_PERMISSIONS)
        }, enabled = !locating) { Text("Use current location") }
        if (locating) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Finding location…")
            TextButton(onClick = { locationJob?.cancel(); locating = false }) { Text("Cancel location request") }
        }
        locationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        CandidateInputField.entries.forEach { field -> key(field) {
            val label = if (field == CandidateInputField.HEIGHT && heightInFeet) "Antenna height (ft)" else field.label
            Card(colors = meshCardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); LearnButton(field.term) }
                    if (field == CandidateInputField.HEIGHT && heightInFeet) {
                        Text("Range: 0 to 10,000 meters (about 32,808 feet).", style = MaterialTheme.typography.bodySmall)
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
    if (discard) ConfirmDiscard("Discard site changes?", "Discard changes", { discard = false }, close)
    if (showsMap) CandidateSiteMapPicker(values[0].trim().replace(',', '.').toDoubleOrNull(),
        values[1].trim().replace(',', '.').toDoubleOrNull(), { showsMap = false }) { latitude, longitude ->
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
                    TextButton(onClick = close, enabled = !busy) { Text("Close") }
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
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } })
}
