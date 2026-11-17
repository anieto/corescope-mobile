package org.nodescope.android.feature.candidatesites

import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.core.design.AdaptiveChoiceRow
import org.nodescope.android.core.design.meshPageBackground

/** Type filter for studies, as iOS's StudyFilter. */
enum class StudyFilter(@param:StringRes val titleRes: Int) {
    ALL(R.string.planner_study_filter_all), LINK_PROFILE(R.string.planner_study_filter_link_profiles), COVERAGE(R.string.planner_study_filter_coverage);

    fun matches(kind: SavedStudy.Kind) = this == ALL || this == of(kind)

    companion object {
        fun of(kind: SavedStudy.Kind?) = when (kind) {
            null -> ALL
            SavedStudy.Kind.LINK_PROFILE -> LINK_PROFILE
            SavedStudy.Kind.COVERAGE -> COVERAGE
        }
    }
}

/**
 * Saved Plans home: plans, then standalone studies, then Candidate Sites, as on iOS. Opened from a
 * tool ([kind] set), it starts filtered to that tool's studies and hides Candidate Sites.
 */
@Composable
fun SavedPlansScreen(kind: SavedStudy.Kind?, onOpen: (SavedStudy) -> Unit, onPlan: (String, StudyFilter) -> Unit,
                     onCandidateSites: () -> Unit, onResumeDraft: (StudyDraft) -> Unit = {}, onUnfinished: () -> Unit = {}) {
    val context = LocalContext.current
    val library = SavedPlansLibrary.shared(context)
    LaunchedEffect(library) { library.load() }
    val drafts = rememberDrafts()
    var filter by rememberSaveable { mutableStateOf(StudyFilter.of(kind)) }
    var query by rememberSaveable { mutableStateOf("") }
    var creatingPlan by rememberSaveable { mutableStateOf(false) }
    var backingUp by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf<android.net.Uri?>(null) }
    // Most file providers don't know the package's type, so any file can be picked; reading checks it.
    val chooser = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> importing = uri }
    val actions = rememberStudyActions()
    val searching = query.isNotBlank()
    fun matches(text: String?) = text != null && text.contains(query.trim(), ignoreCase = true)
    val plans = SavedPlansLibrary.sorted(library.studyPlans, { it.id }, { it.updatedAtMilliseconds }).filter { plan ->
        val contained = library.studies.any { it.planID == plan.id && filter.matches(it.kind) }
        (filter == StudyFilter.ALL || contained) && (!searching || matches(plan.name) || matches(plan.notes))
    }
    val studies = SavedPlansLibrary.sorted(library.studies, { it.id }, { it.updatedAtMilliseconds }).filter {
        filter.matches(it.kind) && if (searching) matches(it.name) else it.planID == null
    }
    Box(Modifier.fillMaxSize().meshPageBackground()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text(stringResource(R.string.planner_plans_notes_and_studies)) })
            }
            if (kind != null) item { StudyFilterRow(filter) { filter = it } }
            // Unfinished work first: one draft resumes from here; several open their own list.
            if (kind == null && !searching) when (drafts.value.size) {
                0 -> Unit
                1 -> item { DraftRow(drafts.value.single(), stringResource(R.string.planner_continue_unfinished_study), onResumeDraft) { drafts.discard(it) } }
                else -> item {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onUnfinished), colors = meshCardColors()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.EditNote, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.planner_unfinished_studies), style = MaterialTheme.typography.titleMedium)
                                Text(pluralStringResource(R.plurals.planner_with_unsaved_changes, drafts.value.size, drafts.value.size), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                }
            }
            if (library.loaded && library.studyPlans.isEmpty() && library.studies.isEmpty()) item {
                Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.planner_group_related_studies_into_a_plan_such_as),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { creatingPlan = true }) { Text(stringResource(R.string.planner_create_plan)) }
                    }
                }
            }
            if (plans.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.planner_plans)) }
                items(plans, key = { "plan-" + it.id }) { plan ->
                    StudyPlanRow(plan, library.studies.filter { it.planID == plan.id }) { onPlan(plan.id, filter) }
                }
            }
            if (studies.isNotEmpty() || (searching && plans.isEmpty())) {
                item { SectionTitle(if (searching) stringResource(R.string.planner_studies_2) else stringResource(R.string.planner_standalone_studies)) }
                items(studies, key = { "study-" + it.id }) { study ->
                    SavedStudyRow(study, if (searching) library.plan(study.planID)?.name else null, !library.busy,
                        open = { onOpen(study) }, actions = actions)
                }
                if (studies.isEmpty()) item {
                    Text(stringResource(R.string.planner_no_plans_or_studies_match, query), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (kind == null && !searching) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Superseded by plans of studies: shown only to people who already saved candidate sites.
                    if (library.plans.isNotEmpty()) Card(Modifier.fillMaxWidth().clickable(onClick = onCandidateSites), colors = meshCardColors()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.planner_candidate_sites), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (library.loaded && (library.studies.isNotEmpty() || library.studyPlans.isNotEmpty() || library.plans.isNotEmpty()))
                            OutlinedButton(onClick = { backingUp = true }) {
                                Icon(Icons.Outlined.Share, null)
                                Text(stringResource(R.string.planner_back_up_library), Modifier.padding(start = 8.dp))
                            }
                        OutlinedButton(onClick = { chooser.launch(arrayOf("*/*")) }, enabled = library.loaded) {
                            Icon(Icons.Outlined.FileOpen, null)
                            Text(stringResource(R.string.planner_import), Modifier.padding(start = 8.dp))
                        }
                    }
                    Text(stringResource(R.string.planner_stored_on_this_device_backup_details_are_in),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { LibraryStatus(library) }
        }
        ExtendedFloatingActionButton(text = { Text(stringResource(R.string.planner_new_plan)) }, icon = { Icon(Icons.Outlined.CreateNewFolder, null) },
            onClick = { if (library.loaded) creatingPlan = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    if (creatingPlan) PlanEditorDialog(null) { creatingPlan = false }
    if (backingUp) ExportPackageSheet(SavedPlansExport.Contents.Library) { backingUp = false }
    importing?.let { uri -> ImportPackageSheet(uri) { importing = null } }
    StudyActionDialogs(actions)
}

/**
 * One plan: notes and plan actions, then its studies with a type filter. + adds a new or existing study.
 * A plan that no longer exists shows a recoverable not-found message.
 */
@Composable
fun StudyPlanScreen(planId: String, initialFilter: StudyFilter, onOpen: (SavedStudy) -> Unit,
                    onNewTool: (SavedStudy.Kind) -> Unit, onDeleted: () -> Unit) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    LaunchedEffect(library) { library.load() }
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var adding by remember { mutableStateOf(false) }
    var addingExisting by rememberSaveable { mutableStateOf(false) }
    var planMenu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var exportingPlan by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val actions = rememberStudyActions()
    val plan = library.plan(planId)
    if (plan == null) {
        Column(Modifier.fillMaxSize().meshPageBackground().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (library.loaded) {
                Text(stringResource(R.string.planner_plan_not_found), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.planner_it_may_have_been_deleted_its_studies_if),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            LibraryStatus(library)
        }
        return
    }
    val members = SavedPlansLibrary.sorted(library.studies.filter { it.planID == planId }, { it.id }, { it.updatedAtMilliseconds })
    val shown = members.filter { filter.matches(it.kind) }
    Box(Modifier.fillMaxSize().meshPageBackground()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Text(plan.notes ?: "", Modifier.weight(1f).padding(top = 12.dp))
                    Box {
                        IconButton(onClick = { planMenu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.planner_plan_actions)) }
                        DropdownMenu(planMenu, { planMenu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.planner_edit_name_and_notes)) }, { planMenu = false; editing = true },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                            DropdownMenuItem({ Text(stringResource(R.string.planner_export_plan)) }, { planMenu = false; exportingPlan = true },
                                leadingIcon = { Icon(Icons.Outlined.Share, null) })
                            DropdownMenuItem({ Text(stringResource(R.string.planner_delete_plan)) }, { planMenu = false; confirmingDelete = true },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                        }
                    }
                }
            }
            item { StudyFilterRow(filter) { filter = it } }
            item {
                SectionTitle(if (filter == StudyFilter.ALL) stringResource(R.string.planner_studies_3, members.size)
                    else stringResource(R.string.planner_of, stringResource(filter.titleRes), shown.size, members.size))
            }
            items(shown, key = { it.id }) { study ->
                SavedStudyRow(study, null, !library.busy, open = { onOpen(study) }, actions = actions)
            }
            if (members.isEmpty()) item {
                Text(stringResource(R.string.planner_no_studies_yet_use_to_start_a_link),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (shown.isEmpty()) item {
                Text(stringResource(R.string.planner_no_in_this_plan, stringResource(filter.titleRes).lowercase()), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { LibraryStatus(library) }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.planner_add_study)) }
            DropdownMenu(adding, { adding = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.planner_new_link_profile)) }, { adding = false; onNewTool(SavedStudy.Kind.LINK_PROFILE) },
                    leadingIcon = { Icon(Icons.Outlined.Route, null) })
                DropdownMenuItem({ Text(stringResource(R.string.planner_new_estimated_coverage)) }, { adding = false; onNewTool(SavedStudy.Kind.COVERAGE) },
                    leadingIcon = { Icon(Icons.Outlined.CellTower, null) })
                DropdownMenuItem({ Text(stringResource(R.string.planner_add_existing_study)) }, { adding = false; addingExisting = true },
                    leadingIcon = { Icon(Icons.Outlined.MoveToInbox, null) })
            }
        }
    }
    if (editing) PlanEditorDialog(plan) { editing = false }
    if (addingExisting) AddExistingStudyDialog(planId) { addingExisting = false }
    if (exportingPlan) ExportPackageSheet(SavedPlansExport.Contents.Plan(planId)) { exportingPlan = false }
    if (confirmingDelete) AlertDialog(onDismissRequest = { confirmingDelete = false },
        title = { Text(stringResource(R.string.planner_delete, plan.name)) },
        text = { Text(if (members.isEmpty()) stringResource(R.string.planner_this_plan_is_empty)
            else pluralStringResource(R.plurals.planner_orphaned_studies, members.size, members.size)) },
        confirmButton = { TextButton(onClick = {
            confirmingDelete = false
            scope.launch { if (library.deleteStudyPlan(planId)) onDeleted() }
        }) { Text(stringResource(R.string.planner_delete_plan), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.common_cancel)) } })
    StudyActionDialogs(actions)
}

@Composable private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable private fun StudyFilterRow(filter: StudyFilter, select: (StudyFilter) -> Unit) {
    AdaptiveChoiceRow(StudyFilter.entries, filter, label = { stringResource(it.titleRes) }, onSelect = select)
}

@Composable private fun LibraryStatus(library: SavedPlansLibrary) {
    val scope = rememberCoroutineScope()
    Column {
        library.error?.let { message ->
            Text(message.asString(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { scope.launch { library.reload() } }, enabled = !library.busy) { Text(stringResource(R.string.planner_reload_library)) }
        }
        if (library.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun savedDate(milliseconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(milliseconds))

@Composable private fun kindTitle(kind: SavedStudy.Kind) = stringResource(
    if (kind == SavedStudy.Kind.LINK_PROFILE) R.string.planner_study_type_link_profile else R.string.planner_study_type_coverage
)
private fun kindIcon(kind: SavedStudy.Kind): ImageVector = if (kind == SavedStudy.Kind.LINK_PROFILE) Icons.Outlined.Route else Icons.Outlined.CellTower

@Composable private fun StudyPlanRow(plan: StudyPlan, studies: List<SavedStudy>, open: () -> Unit) {
    val links = studies.count { it.kind == SavedStudy.Kind.LINK_PROFILE }
    val coverage = studies.size - links
    val counts = listOfNotNull(
        links.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.planner_link_profiles_count, it, it) },
        coverage.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.planner_coverage_studies_count, it, it) },
    ).joinToString(" · ").ifEmpty { stringResource(R.string.planner_no_studies) }
    Card(Modifier.fillMaxWidth().clickable(onClick = open), colors = meshCardColors()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(plan.name, style = MaterialTheme.typography.titleSmall)
                Text(counts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.planner_updated, savedDate(plan.updatedAtMilliseconds)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

private fun resultText(status: SavedResultStatus) = when (status) {
    SavedResultStatus.AVAILABLE -> "Includes saved results"
    SavedResultStatus.MISSING -> "Saved result not on this device"
    SavedResultStatus.UNVERIFIED -> "Saved result could not be verified"
    SavedResultStatus.UNSUPPORTED -> "Saved result needs another version of NodeScope"
    SavedResultStatus.NONE -> "Inputs only · Calculate to see results"
}

@Composable private fun SavedStudyRow(study: SavedStudy, planName: String?, enabled: Boolean, open: () -> Unit, actions: StudyActions) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = open), colors = meshCardColors()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(kindIcon(study.kind), null, Modifier.padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(study.name, style = MaterialTheme.typography.titleSmall)
                Text(kindTitle(study.kind), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                planName?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(resultText(study.resultStatus), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.planner_updated, savedDate(study.updatedAtMilliseconds)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.planner_actions_for, study.name)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.planner_rename)) }, { menu = false; actions.renaming = study.id },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.planner_move_to_plan)) }, { menu = false; actions.moving = study.id },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.planner_duplicate)) }, { menu = false; scope.launch { library.duplicateStudy(study.id) } },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.planner_export)) }, { menu = false; actions.exporting = study.id },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.planner_delete_study)) }, { menu = false; actions.deleting = study.id },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                }
            }
        }
    }
}

// Study actions

/** Which study a rename, move or delete dialog is open for; kept through configuration changes. */
private class StudyActions(renaming: String?, moving: String?, deleting: String?, exporting: String?) {
    var renaming by mutableStateOf(renaming)
    var moving by mutableStateOf(moving)
    var deleting by mutableStateOf(deleting)
    var exporting by mutableStateOf(exporting)
}

@Composable private fun rememberStudyActions(): StudyActions = rememberSaveable(saver = listSaver(
    save = { listOf(it.renaming, it.moving, it.deleting, it.exporting) }, restore = { StudyActions(it[0], it[1], it[2], it[3]) },
)) { StudyActions(null, null, null, null) }

@Composable private fun StudyActionDialogs(actions: StudyActions) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    val scope = rememberCoroutineScope()
    actions.exporting?.let { id -> ExportPackageSheet(SavedPlansExport.Contents.Study(id)) { actions.exporting = null } }
    actions.renaming?.let { id ->
        val study = library.studies.firstOrNull { it.id == id } ?: run { actions.renaming = null; return@let }
        var name by rememberSaveable(id) { mutableStateOf(study.name) }
        AlertDialog(onDismissRequest = { actions.renaming = null }, title = { Text(stringResource(R.string.planner_rename_study)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.planner_name)) })
                    Text(stringResource(R.string.planner_renaming_doesn_t_change_the_study_s_inputs), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch { if (library.renameStudy(id, name)) actions.renaming = null }
            }, enabled = validPlanName(name) && !library.busy) { Text(stringResource(R.string.planner_rename)) } },
            dismissButton = { TextButton(onClick = { actions.renaming = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    actions.moving?.let { id ->
        val study = library.studies.firstOrNull { it.id == id } ?: run { actions.moving = null; return@let }
        AlertDialog(onDismissRequest = { actions.moving = null }, title = { Text(stringResource(R.string.planner_move_2, study.name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.planner_now_in, library.plan(study.planID)?.name ?: stringResource(R.string.planner_standalone_2)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val choices = listOf<StudyPlan?>(null) + SavedPlansLibrary.sorted(library.studyPlans, { it.id }, { it.updatedAtMilliseconds })
                    choices.forEach { plan ->
                        ChoiceRow(plan?.name ?: stringResource(R.string.planner_standalone_2), if (plan == null) Icons.Outlined.Description else Icons.Outlined.Folder,
                            selected = study.planID == plan?.id, enabled = !library.busy) {
                            if (study.planID == plan?.id) actions.moving = null
                            else scope.launch { if (library.moveStudy(id, plan?.id)) actions.moving = null }
                        }
                    }
                    library.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { actions.moving = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    actions.deleting?.let { id ->
        val study = library.studies.firstOrNull { it.id == id } ?: run { actions.deleting = null; return@let }
        AlertDialog(onDismissRequest = { actions.deleting = null }, title = { Text(stringResource(R.string.planner_delete, study.name)) },
            text = { Text(stringResource(R.string.planner_this_removes_the_study_and_its_saved_result)) },
            confirmButton = { TextButton(onClick = { actions.deleting = null; scope.launch { library.deleteStudy(id) } }) {
                Text(stringResource(R.string.planner_delete_study), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { actions.deleting = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

@Composable private fun ChoiceRow(title: String, icon: ImageVector, selected: Boolean, enabled: Boolean = true, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton, onClick = select).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, null, enabled = enabled)
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, Modifier.weight(1f))
    }
}

/** Moves a study from Standalone or another plan into this one; never copies it. */
@Composable private fun AddExistingStudyDialog(planId: String, dismiss: () -> Unit) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    val scope = rememberCoroutineScope()
    val candidates = SavedPlansLibrary.sorted(library.studies.filter { it.planID != planId }, { it.id }, { it.updatedAtMilliseconds })
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.planner_add_existing_study)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.planner_adding_moves_the_study_here_from_where_it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (candidates.isEmpty()) Text(stringResource(R.string.planner_every_saved_study_is_already_in_this_plan))
                candidates.forEach { study ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = !library.busy) {
                        scope.launch { if (library.moveStudy(study.id, planId)) dismiss() }
                    }.padding(vertical = 8.dp)) {
                        Text(study.name, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.planner_value, kindTitle(study.kind), library.plan(study.planID)?.name ?: stringResource(R.string.planner_standalone_2)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                library.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** Creates a plan ([plan] null) or edits one's name and notes. */
@Composable private fun PlanEditorDialog(plan: StudyPlan?, dismiss: () -> Unit) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(plan?.name ?: "") }
    var notes by rememberSaveable { mutableStateOf(plan?.notes ?: "") }
    val valid = validPlanName(name) && notes.toByteArray(Charsets.UTF_8).size <= 4096
    AlertDialog(onDismissRequest = { if (!library.busy) dismiss() }, title = { Text(if (plan == null) stringResource(R.string.planner_new_plan_3) else stringResource(R.string.planner_edit_plan)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.planner_plan_name)) })
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                    label = { Text(stringResource(R.string.planner_notes_optional)) },
                    isError = notes.toByteArray(Charsets.UTF_8).size > 4096)
                library.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                val saved = if (plan == null) library.createStudyPlan(name, notes) != null else library.editStudyPlan(plan.id, name, notes)
                if (saved) dismiss()
            }
        }, enabled = library.loaded && !library.busy && valid) { Text(if (plan == null) stringResource(R.string.planner_create) else stringResource(R.string.planner_save_2)) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !library.busy) { Text(stringResource(R.string.common_cancel)) } })
}

// Saving from a tool

/**
 * Save and Open for a tool, as iOS's `SavedStudyActions`. Save opens a sheet for the name and
 * destination; Open lists this tool's saved studies. The sheet takes a fresh snapshot each time it
 * appears, so it survives rotation without putting a result into saved state.
 */
@Composable
fun SavedStudyActions(snapshot: () -> SavedStudy?, didSave: (SavedStudy) -> Unit, onOpen: () -> Unit, suggestedPlanID: String? = null) {
    var saving by rememberSaveable { mutableStateOf(false) }
    val available = snapshot() != null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { saving = true }, modifier = Modifier.weight(1f), enabled = available) { Text(stringResource(R.string.planner_save)) }
        Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.planner_open)) }
    }
    if (saving) {
        val study = remember { snapshot() }
        if (study == null) saving = false
        else SaveStudySheet(study, suggestedPlanID, { saving = false }) { saved -> saving = false; didSave(saved) }
    }
}

/**
 * Name and destination for a save. A study already in the library offers Update existing (the default)
 * or Save as new. The sheet closes only once the save has succeeded; its fields survive rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SaveStudySheet(initial: SavedStudy, suggestedPlanID: String?, dismiss: () -> Unit, saved: (SavedStudy) -> Unit) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var asNew by rememberSaveable { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var newPlanName by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var inputsOnly by rememberSaveable { mutableStateOf(false) }
    // Never consume a sheet dismissal while saving.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { submitting.not() })
    LaunchedEffect(library) {
        library.load()
        if (destination == null && library.loaded) {
            library.error = null
            val existing = library.studies.firstOrNull { it.id == initial.id }
            // Current membership wins; then the plan this tool came from; then Standalone.
            val planID = if (existing != null) existing.planID else suggestedPlanID
            destination = library.plan(planID)?.let { SavedPlansLibrary.Destination.Plan(it.id).encoded() }
                ?: SavedPlansLibrary.Destination.Standalone.encoded()
        }
    }
    val prepared = destination != null
    val existing = library.studies.firstOrNull { it.id == initial.id }
    val wasDeleted = prepared && initial.revision != null && existing == null
    val mode = if (existing == null || asNew) SavedPlansLibrary.SaveMode.SAVE_AS_NEW else SavedPlansLibrary.SaveMode.UPDATE
    val target = destination?.let(SavedPlansLibrary.Destination::decode) ?: SavedPlansLibrary.Destination.Standalone
    val study = initial.copy(name = name)
    // Update would drop a result this device can't show; the user must confirm saving inputs only.
    val needsInputsOnly = mode == SavedPlansLibrary.SaveMode.UPDATE && library.dropsUnreadableResult(study)
    val valid = runCatching { study.validate() }.isSuccess && (target != SavedPlansLibrary.Destination.NewPlan || validPlanName(newPlanName)) &&
        (!needsInputsOnly || inputsOnly)

    fun submit() {
        if (submitting) return
        submitting = true
        scope.launch {
            try { library.saveStudy(study, mode, target, newPlanName, needsInputsOnly && inputsOnly)?.let(saved) } finally { submitting = false }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!submitting) dismiss() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (existing == null) stringResource(R.string.planner_save_study) else stringResource(R.string.planner_save_changes), style = MaterialTheme.typography.titleLarge)
            if (existing != null) {
                AdaptiveChoiceRow(listOf(false, true), asNew,
                    label = { stringResource(if (it) R.string.planner_save_as_new else R.string.planner_update_existing) },
                    onSelect = { saveAsNew ->
                        if (saveAsNew && !asNew && name == existing.name) name = library.uniqueName(SavedPlansLibrary.copyName(existing.name))
                        if (!saveAsNew && asNew && name == library.uniqueName(SavedPlansLibrary.copyName(existing.name))) name = existing.name
                        asNew = saveAsNew
                    })
                Text(if (asNew) stringResource(R.string.planner_keeps_as_it_is_and_saves_a_separate, existing.name)
                    else stringResource(R.string.planner_replaces_the_saved_with_what_s_open_now, existing.name),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (needsInputsOnly) {
                    Row(Modifier.fillMaxWidth().selectable(inputsOnly, !submitting, Role.Checkbox) { inputsOnly = !inputsOnly },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(inputsOnly, null, enabled = !submitting)
                        Text(stringResource(R.string.planner_save_inputs_only), Modifier.padding(start = 8.dp))
                    }
                    Text(stringResource(R.string.planner_the_saved_result_can_t_be_shown_on),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (wasDeleted) {
                Text(stringResource(R.string.planner_the_study_you_opened_was_deleted_this_saves), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The name is kept on focus; select or edit it as usual.
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.planner_study_name)) }, singleLine = true,
                enabled = !submitting)
            Text(stringResource(R.string.planner_saves_the_current_inputs_and_once_calculated_the),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.planner_save_in), style = MaterialTheme.typography.titleMedium)
            Column {
                ChoiceRow(stringResource(R.string.planner_standalone), Icons.Outlined.Description, target == SavedPlansLibrary.Destination.Standalone, !submitting) {
                    destination = SavedPlansLibrary.Destination.Standalone.encoded()
                }
                SavedPlansLibrary.sorted(library.studyPlans, { it.id }, { it.updatedAtMilliseconds }).forEach { plan ->
                    ChoiceRow(plan.name, Icons.Outlined.Folder, target == SavedPlansLibrary.Destination.Plan(plan.id), !submitting) {
                        destination = SavedPlansLibrary.Destination.Plan(plan.id).encoded()
                    }
                }
                ChoiceRow(stringResource(R.string.planner_new_plan_2), Icons.Outlined.CreateNewFolder, target == SavedPlansLibrary.Destination.NewPlan, !submitting) {
                    destination = SavedPlansLibrary.Destination.NewPlan.encoded()
                }
            }
            if (target == SavedPlansLibrary.Destination.NewPlan) OutlinedTextField(newPlanName, { newPlanName = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.planner_new_plan_name)) }, singleLine = true, enabled = !submitting)
            Text(if (existing != null && !asNew)
                    stringResource(R.string.planner_now_in_choose_another_plan_to_move_it, library.plan(existing.planID)?.name ?: stringResource(R.string.planner_standalone_2))
                else stringResource(R.string.planner_a_plan_groups_related_studies_such_as_every),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            library.error?.let { message ->
                Text(message.asString(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { library.reload() } }, enabled = !library.busy && !submitting) { Text(stringResource(R.string.planner_reload_library)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = dismiss, enabled = !submitting) { Text(stringResource(R.string.common_cancel)) }
                Button(onClick = ::submit, enabled = prepared && library.loaded && !library.busy && !submitting && valid) {
                    Text(when { existing == null -> stringResource(R.string.planner_save_study_2); asNew -> stringResource(R.string.planner_save_as_new_2); else -> stringResource(R.string.planner_update) })
                }
            }
        }
    }
}

// Recovery drafts (deliverable C)

/** Drafts on this device, read when the screen appears; [discard] removes one after confirmation. */
private class DraftList(private val store: StudyDraftStore, private val scope: kotlinx.coroutines.CoroutineScope) {
    var value by mutableStateOf<List<StudyDraft>>(emptyList()); private set

    /** Drafts whose tool is open somewhere aren't offered: resuming would open the same work twice. */
    fun reload() = scope.launch {
        val open = StudyDraftRegistry.openIDs
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.list() }.filter { it.id !in open }
    }

    fun discard(draft: StudyDraft) = scope.launch {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.delete(draft.id) }
        reload()
    }
}

@Composable private fun rememberDrafts(): DraftList {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drafts = remember { DraftList(StudyDraftStore.application(context.applicationContext.filesDir), scope) }
    LaunchedEffect(drafts) { drafts.reload() }
    return drafts
}

/** All unfinished studies, most recently edited first. */
@Composable
fun UnfinishedStudiesScreen(onResume: (StudyDraft) -> Unit) {
    val drafts = rememberDrafts()
    LazyColumn(Modifier.fillMaxSize().meshPageBackground(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.planner_changes_you_haven_t_saved_kept_on_this),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(drafts.value, key = { it.id }) { draft -> DraftRow(draft, null, onResume) { drafts.discard(it) } }
        if (drafts.value.isEmpty()) item { Text(stringResource(R.string.planner_no_unfinished_studies), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun DraftRow(draft: StudyDraft, heading: String?, resume: (StudyDraft) -> Unit, discard: (StudyDraft) -> Unit) {
    var confirming by rememberSaveable(draft.id) { mutableStateOf(false) }
    val edited = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(Date(draft.updatedAtMilliseconds))
    Card(Modifier.fillMaxWidth().clickable { resume(draft) }, colors = meshCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            heading?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(draft.kind), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(draft.title, style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(kindTitle(draft.kind), draft.host).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.planner_edited, edited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.planner_discard)) }
                TextButton(onClick = { resume(draft) }) { Text(stringResource(R.string.planner_resume)) }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text(stringResource(R.string.planner_discard_2, draft.title)) },
        text = { Text(stringResource(R.string.planner_its_unsaved_changes_can_t_be_recovered_saved)) },
        confirmButton = { TextButton(onClick = { confirming = false; discard(draft) }) {
            Text(stringResource(R.string.planner_discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) } })
}
