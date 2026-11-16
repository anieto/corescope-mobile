package org.nodescope.android.feature.candidatesites

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
import org.nodescope.android.core.design.meshPageBackground

/** Type filter for studies, as iOS's StudyFilter. */
enum class StudyFilter(val title: String) {
    ALL("All"), LINK_PROFILE("Link Profiles"), COVERAGE("Coverage");

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
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Plans, notes and studies") })
            }
            if (kind != null) item { StudyFilterRow(filter) { filter = it } }
            // Unfinished work first: one draft resumes from here; several open their own list.
            if (kind == null && !searching) when (drafts.value.size) {
                0 -> Unit
                1 -> item { DraftRow(drafts.value.single(), "Continue unfinished study", onResumeDraft) { drafts.discard(it) } }
                else -> item {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onUnfinished), colors = meshCardColors()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.EditNote, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("Unfinished studies", style = MaterialTheme.typography.titleMedium)
                                Text("${drafts.value.size} with unsaved changes", style = MaterialTheme.typography.bodyMedium,
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
                        Text("Group related studies into a plan, such as one for each site you're considering. Link Profile and Estimated Coverage can also save standalone studies.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { creatingPlan = true }) { Text("Create plan") }
                    }
                }
            }
            if (plans.isNotEmpty()) {
                item { SectionTitle("Plans") }
                items(plans, key = { "plan-" + it.id }) { plan ->
                    StudyPlanRow(plan, library.studies.filter { it.planID == plan.id }) { onPlan(plan.id, filter) }
                }
            }
            if (studies.isNotEmpty() || (searching && plans.isEmpty())) {
                item { SectionTitle(if (searching) "Studies" else "Standalone studies") }
                items(studies, key = { "study-" + it.id }) { study ->
                    SavedStudyRow(study, if (searching) library.plan(study.planID)?.name else null, !library.busy,
                        open = { onOpen(study) }, actions = actions)
                }
                if (studies.isEmpty()) item {
                    Text("No plans or studies match “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (kind == null && !searching) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Superseded by plans of studies: shown only to people who already saved candidate sites.
                    if (library.plans.isNotEmpty()) Card(Modifier.fillMaxWidth().clickable(onClick = onCandidateSites), colors = meshCardColors()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Candidate sites", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                    Text("Stored on this device. Backup details are in Settings › Cache & Storage.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { LibraryStatus(library) }
        }
        ExtendedFloatingActionButton(text = { Text("New plan") }, icon = { Icon(Icons.Outlined.CreateNewFolder, null) },
            onClick = { if (library.loaded) creatingPlan = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    if (creatingPlan) PlanEditorDialog(null) { creatingPlan = false }
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
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val actions = rememberStudyActions()
    val plan = library.plan(planId)
    if (plan == null) {
        Column(Modifier.fillMaxSize().meshPageBackground().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (library.loaded) {
                Text("Plan not found", style = MaterialTheme.typography.titleLarge)
                Text("It may have been deleted. Its studies, if any, are under Standalone studies.",
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
                        IconButton(onClick = { planMenu = true }) { Icon(Icons.Outlined.MoreVert, "Plan actions") }
                        DropdownMenu(planMenu, { planMenu = false }) {
                            DropdownMenuItem({ Text("Edit name and notes") }, { planMenu = false; editing = true },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                            DropdownMenuItem({ Text("Delete plan") }, { planMenu = false; confirmingDelete = true },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                        }
                    }
                }
            }
            item { StudyFilterRow(filter) { filter = it } }
            item {
                SectionTitle(if (filter == StudyFilter.ALL) "Studies (${members.size})"
                    else "${filter.title} (${shown.size} of ${members.size})")
            }
            items(shown, key = { it.id }) { study ->
                SavedStudyRow(study, null, !library.busy, open = { onOpen(study) }, actions = actions)
            }
            if (members.isEmpty()) item {
                Text("No studies yet. Use + to start a Link Profile or Estimated Coverage here, or add a study you've already saved.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (shown.isEmpty()) item {
                Text("No ${filter.title.lowercase()} in this plan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { LibraryStatus(library) }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "Add study") }
            DropdownMenu(adding, { adding = false }) {
                DropdownMenuItem({ Text("New Link Profile") }, { adding = false; onNewTool(SavedStudy.Kind.LINK_PROFILE) },
                    leadingIcon = { Icon(Icons.Outlined.Route, null) })
                DropdownMenuItem({ Text("New Estimated Coverage") }, { adding = false; onNewTool(SavedStudy.Kind.COVERAGE) },
                    leadingIcon = { Icon(Icons.Outlined.CellTower, null) })
                DropdownMenuItem({ Text("Add existing study") }, { adding = false; addingExisting = true },
                    leadingIcon = { Icon(Icons.Outlined.MoveToInbox, null) })
            }
        }
    }
    if (editing) PlanEditorDialog(plan) { editing = false }
    if (addingExisting) AddExistingStudyDialog(planId) { addingExisting = false }
    if (confirmingDelete) AlertDialog(onDismissRequest = { confirmingDelete = false },
        title = { Text("Delete “${plan.name}”?") },
        text = { Text(if (members.isEmpty()) "This plan is empty."
            else "Its ${members.size} ${if (members.size == 1) "study stays" else "studies stay"} in Saved Plans as standalone studies.") },
        confirmButton = { TextButton(onClick = {
            confirmingDelete = false
            scope.launch { if (library.deleteStudyPlan(planId)) onDeleted() }
        }) { Text("Delete plan", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } })
    StudyActionDialogs(actions)
}

@Composable private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable private fun StudyFilterRow(filter: StudyFilter, select: (StudyFilter) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        StudyFilter.entries.forEachIndexed { index, option ->
            SegmentedButton(filter == option, { select(option) }, SegmentedButtonDefaults.itemShape(index, StudyFilter.entries.size)) {
                Text(option.title, maxLines = 1)
            }
        }
    }
}

@Composable private fun LibraryStatus(library: SavedPlansLibrary) {
    val scope = rememberCoroutineScope()
    Column {
        library.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { scope.launch { library.reload() } }, enabled = !library.busy) { Text("Reload library") }
        }
        if (library.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun savedDate(milliseconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(milliseconds))

private fun kindTitle(kind: SavedStudy.Kind) = if (kind == SavedStudy.Kind.LINK_PROFILE) "Link Profile" else "Estimated Coverage"
private fun kindIcon(kind: SavedStudy.Kind): ImageVector = if (kind == SavedStudy.Kind.LINK_PROFILE) Icons.Outlined.Route else Icons.Outlined.CellTower

@Composable private fun StudyPlanRow(plan: StudyPlan, studies: List<SavedStudy>, open: () -> Unit) {
    val links = studies.count { it.kind == SavedStudy.Kind.LINK_PROFILE }
    val coverage = studies.size - links
    val counts = listOfNotNull(
        links.takeIf { it > 0 }?.let { if (it == 1) "1 Link Profile" else "$it Link Profiles" },
        coverage.takeIf { it > 0 }?.let { if (it == 1) "1 Coverage study" else "$it Coverage studies" },
    ).joinToString(" · ").ifEmpty { "No studies" }
    Card(Modifier.fillMaxWidth().clickable(onClick = open), colors = meshCardColors()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(plan.name, style = MaterialTheme.typography.titleSmall)
                Text(counts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Updated ${savedDate(plan.updatedAtMilliseconds)}", style = MaterialTheme.typography.bodySmall,
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
                Text("Updated ${savedDate(study.updatedAtMilliseconds)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "Actions for ${study.name}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; actions.renaming = study.id },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    DropdownMenuItem({ Text("Move to plan") }, { menu = false; actions.moving = study.id },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) })
                    DropdownMenuItem({ Text("Duplicate") }, { menu = false; scope.launch { library.duplicateStudy(study.id) } },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                    DropdownMenuItem({ Text("Delete study") }, { menu = false; actions.deleting = study.id },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                }
            }
        }
    }
}

// Study actions

/** Which study a rename, move or delete dialog is open for; kept through configuration changes. */
private class StudyActions(renaming: String?, moving: String?, deleting: String?) {
    var renaming by mutableStateOf(renaming)
    var moving by mutableStateOf(moving)
    var deleting by mutableStateOf(deleting)
}

@Composable private fun rememberStudyActions(): StudyActions = rememberSaveable(saver = listSaver(
    save = { listOf(it.renaming, it.moving, it.deleting) }, restore = { StudyActions(it[0], it[1], it[2]) },
)) { StudyActions(null, null, null) }

@Composable private fun StudyActionDialogs(actions: StudyActions) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    val scope = rememberCoroutineScope()
    actions.renaming?.let { id ->
        val study = library.studies.firstOrNull { it.id == id } ?: run { actions.renaming = null; return@let }
        var name by rememberSaveable(id) { mutableStateOf(study.name) }
        AlertDialog(onDismissRequest = { actions.renaming = null }, title = { Text("Rename study") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                    Text("Renaming doesn't change the study's inputs or results.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch { if (library.renameStudy(id, name)) actions.renaming = null }
            }, enabled = validPlanName(name) && !library.busy) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { actions.renaming = null }) { Text("Cancel") } })
    }
    actions.moving?.let { id ->
        val study = library.studies.firstOrNull { it.id == id } ?: run { actions.moving = null; return@let }
        AlertDialog(onDismissRequest = { actions.moving = null }, title = { Text("Move “${study.name}”") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Now in ${library.plan(study.planID)?.name ?: "Standalone"}.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val choices = listOf<StudyPlan?>(null) + SavedPlansLibrary.sorted(library.studyPlans, { it.id }, { it.updatedAtMilliseconds })
                    choices.forEach { plan ->
                        ChoiceRow(plan?.name ?: "Standalone", if (plan == null) Icons.Outlined.Description else Icons.Outlined.Folder,
                            selected = study.planID == plan?.id, enabled = !library.busy) {
                            if (study.planID == plan?.id) actions.moving = null
                            else scope.launch { if (library.moveStudy(id, plan?.id)) actions.moving = null }
                        }
                    }
                    library.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { actions.moving = null }) { Text("Cancel") } })
    }
    actions.deleting?.let { id ->
        val study = library.studies.firstOrNull { it.id == id } ?: run { actions.deleting = null; return@let }
        AlertDialog(onDismissRequest = { actions.deleting = null }, title = { Text("Delete “${study.name}”?") },
            text = { Text("This removes the study and its saved result from this device.") },
            confirmButton = { TextButton(onClick = { actions.deleting = null; scope.launch { library.deleteStudy(id) } }) {
                Text("Delete study", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { actions.deleting = null }) { Text("Cancel") } })
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
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add existing study") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Adding moves the study here from where it is now.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (candidates.isEmpty()) Text("Every saved study is already in this plan.")
                candidates.forEach { study ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = !library.busy) {
                        scope.launch { if (library.moveStudy(study.id, planId)) dismiss() }
                    }.padding(vertical = 8.dp)) {
                        Text(study.name, style = MaterialTheme.typography.titleSmall)
                        Text("${kindTitle(study.kind)} · ${library.plan(study.planID)?.name ?: "Standalone"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                library.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

/** Creates a plan ([plan] null) or edits one's name and notes. */
@Composable private fun PlanEditorDialog(plan: StudyPlan?, dismiss: () -> Unit) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(plan?.name ?: "") }
    var notes by rememberSaveable { mutableStateOf(plan?.notes ?: "") }
    val valid = validPlanName(name) && notes.toByteArray(Charsets.UTF_8).size <= 4096
    AlertDialog(onDismissRequest = { if (!library.busy) dismiss() }, title = { Text(if (plan == null) "New plan" else "Edit plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Plan name") })
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                    label = { Text("Notes (optional)") },
                    isError = notes.toByteArray(Charsets.UTF_8).size > 4096)
                library.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                val saved = if (plan == null) library.createStudyPlan(name, notes) != null else library.editStudyPlan(plan.id, name, notes)
                if (saved) dismiss()
            }
        }, enabled = library.loaded && !library.busy && valid) { Text(if (plan == null) "Create" else "Save") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !library.busy) { Text("Cancel") } })
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
        Button(onClick = { saving = true }, modifier = Modifier.weight(1f), enabled = available) { Text("Save") }
        Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Open") }
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
            Text(if (existing == null) "Save study" else "Save changes", style = MaterialTheme.typography.titleLarge)
            if (existing != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!asNew, {
                        if (asNew && name == library.uniqueName(SavedPlansLibrary.copyName(existing.name))) name = existing.name
                        asNew = false
                    }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Update existing") }
                    SegmentedButton(asNew, {
                        if (!asNew && name == existing.name) name = library.uniqueName(SavedPlansLibrary.copyName(existing.name))
                        asNew = true
                    }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Save as new") }
                }
                Text(if (asNew) "Keeps “${existing.name}” as it is and saves a separate study."
                    else "Replaces the saved “${existing.name}” with what's open now.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (needsInputsOnly) {
                    Row(Modifier.fillMaxWidth().selectable(inputsOnly, !submitting, Role.Checkbox) { inputsOnly = !inputsOnly },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(inputsOnly, null, enabled = !submitting)
                        Text("Save inputs only", Modifier.padding(start = 8.dp))
                    }
                    Text("The saved result can't be shown on this device and doesn't match the changed inputs. Updating keeps the new inputs without a result; calculate again to get one. To keep the original and its result, choose Save as new.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (wasDeleted) {
                Text("The study you opened was deleted. This saves it as a new study.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The name is kept on focus; select or edit it as usual.
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Study name") }, singleLine = true,
                enabled = !submitting)
            Text("Saves the current inputs and, once calculated, the result. Estimated Coverage also keeps the node locations it used.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Save in", style = MaterialTheme.typography.titleMedium)
            Column {
                ChoiceRow("Standalone", Icons.Outlined.Description, target == SavedPlansLibrary.Destination.Standalone, !submitting) {
                    destination = SavedPlansLibrary.Destination.Standalone.encoded()
                }
                SavedPlansLibrary.sorted(library.studyPlans, { it.id }, { it.updatedAtMilliseconds }).forEach { plan ->
                    ChoiceRow(plan.name, Icons.Outlined.Folder, target == SavedPlansLibrary.Destination.Plan(plan.id), !submitting) {
                        destination = SavedPlansLibrary.Destination.Plan(plan.id).encoded()
                    }
                }
                ChoiceRow("New plan…", Icons.Outlined.CreateNewFolder, target == SavedPlansLibrary.Destination.NewPlan, !submitting) {
                    destination = SavedPlansLibrary.Destination.NewPlan.encoded()
                }
            }
            if (target == SavedPlansLibrary.Destination.NewPlan) OutlinedTextField(newPlanName, { newPlanName = it }, Modifier.fillMaxWidth(),
                label = { Text("New plan name") }, singleLine = true, enabled = !submitting)
            Text(if (existing != null && !asNew)
                    "Now in ${library.plan(existing.planID)?.name ?: "Standalone"}. Choose another plan to move it there, or Standalone to keep it outside any plan."
                else "A plan groups related studies, such as every study for one site. Standalone keeps this study outside any plan. You can move it later.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            library.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { library.reload() } }, enabled = !library.busy && !submitting) { Text("Reload library") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = dismiss, enabled = !submitting) { Text("Cancel") }
                Button(onClick = ::submit, enabled = prepared && library.loaded && !library.busy && !submitting && valid) {
                    Text(when { existing == null -> "Save study"; asNew -> "Save as new"; else -> "Update" })
                }
            }
        }
    }
}

// Recovery drafts (deliverable C)

/** Drafts on this device, read when the screen appears; [discard] removes one after confirmation. */
private class DraftList(private val store: StudyDraftStore, private val scope: kotlinx.coroutines.CoroutineScope) {
    var value by mutableStateOf<List<StudyDraft>>(emptyList()); private set

    fun reload() = scope.launch { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.list() } }

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
            Text("Changes you haven't saved, kept on this device. Resume one to keep working, or discard it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(drafts.value, key = { it.id }) { draft -> DraftRow(draft, null, onResume) { drafts.discard(it) } }
        if (drafts.value.isEmpty()) item { Text("No unfinished studies.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    Text("Edited $edited", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirming = true }) { Text("Discard") }
                TextButton(onClick = { resume(draft) }) { Text("Resume") }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("Discard “${draft.title}”?") },
        text = { Text("Its unsaved changes can't be recovered. Saved studies aren't affected.") },
        confirmButton = { TextButton(onClick = { confirming = false; discard(draft) }) {
            Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
}
