package org.nodescope.android.feature.candidatesites

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Importing a `.nodescope` package into the saved library (docs/node-planner/saved-plans-v3-package.md, Importing).
 * Pure: plans the new records; the library commits them in one save. Never overwrites, calculates or fetches. Mirrors iOS.
 */
object SavedPlansImport {
    data class Library(val plans: List<DeploymentPlan>, val studyPlans: List<StudyPlan>, val studies: List<SavedStudy>)

    enum class Kind { PLAN, STUDY, CANDIDATE_SITES }
    sealed interface Status {
        data object New : Status
        /** Everything in it is already in the library. */
        data object AlreadyHere : Status
        /** The plan is here; this many of its studies aren't. */
        data class PartlyHere(val fresh: Int) : Status
    }

    /** [id] is the package record's ID. */
    data class Item(val id: String, val kind: Kind, val name: String, val studies: Int, val status: Status)

    class Preview(
        val pack: SavedPlansPackage, val items: List<Item>,
        /** Studies whose result can't be imported here: inputs only, if acknowledged. */
        val unusable: List<String>,
        /** Results the exporting device left out, and why. */
        val omitted: List<Pair<String, SavedPlansPackage.OmissionReason>>,
        val results: Int,
    ) {
        /** A study or plan import that is already here imports as a copy; a library import skips what's identical. */
        val copiesMatches get() = pack.scope != SavedPlansPackage.Scope.LIBRARY
        val defaultSelection get() = items.filter { copiesMatches || it.status != Status.AlreadyHere }.map { it.id }.toSet()
    }

    data class Records(val plans: List<DeploymentPlan>, val studyPlans: List<StudyPlan>, val studies: List<SavedStudy>)

    sealed class Refusal(message: String) : Exception(message) {
        data object NothingSelected : Refusal("Everything selected is already in your library.")
        data class TooMany(val what: String) : Refusal("Saved Plans can hold 100 $what. Choose fewer items to import, or remove some first.")
        data object TooLarge : Refusal("There isn't room for these results. Choose fewer items, or import inputs only.")
    }

    fun preview(pack: SavedPlansPackage, library: Library): Preview {
        val copies = pack.scope != SavedPlansPackage.Scope.LIBRARY
        val items = mutableListOf<Item>()
        for (site in pack.candidateSitePlans) {
            items += Item(site.id, Kind.CANDIDATE_SITES, site.name, 0,
                if (!copies && library.plans.any { sameSites(it, site) }) Status.AlreadyHere else Status.New)
        }
        for (plan in pack.studyPlans) {
            val members = pack.studies.filter { it.planID == plan.id }
            val local = matchingPlan(plan, library)
            val status = if (local == null) Status.New else {
                val fresh = members.count { study -> library.studies.none { it.planID == local.id && same(study, it) } }
                if (fresh == 0) Status.AlreadyHere else Status.PartlyHere(fresh)
            }
            items += Item(plan.id, Kind.PLAN, plan.name, members.size, status)
        }
        for (study in pack.studies.filter { it.planID == null }) {
            // A study exported on its own left its plan behind, so it matches wherever it is here.
            val here = library.studies.any { (pack.scope == SavedPlansPackage.Scope.STUDY || it.planID == null) && same(study, it) }
            items += Item(study.id, Kind.STUDY, study.name, 1, if (here) Status.AlreadyHere else Status.New)
        }
        val names = pack.studies.associate { it.id to it.name }
        return Preview(pack, items, pack.studies.filter { it.id in pack.unusableResults }.map { it.name },
            pack.omittedResults.map { (names[it.studyID] ?: "A study") to it.reason }, pack.studies.count { it.resultRef != null })
    }

    /**
     * The new records for the selected items: copies with new IDs, memberships remapped, and each study's
     * `importOrigin` naming the package record. Throws a [Refusal] if nothing would be imported or the library can't hold it.
     */
    fun records(preview: Preview, library: Library, selected: Set<String>, inputsOnly: Boolean): Records {
        val pack = preview.pack
        val copies = preview.copiesMatches
        val studyNames = library.studies.map { it.name }.toMutableSet()
        val planNames = library.studyPlans.map { it.name }.toMutableSet()
        val plans = mutableListOf<DeploymentPlan>()
        val containers = mutableListOf<StudyPlan>()
        val studies = mutableListOf<SavedStudy>()
        // Result files by content, with their sizes: the package's verified references, so nothing is hashed again.
        val held = library.studies.mapNotNull { it.resultRef }.associate { it.sha256 to it.byteLength.toLong() }.toMutableMap()

        fun copy(study: SavedStudy, planID: String?) {
            if (study.id in pack.unusableResults && !inputsOnly) return
            // The bytes travel in memory (base64, as loaded); the store installs them as result files before the manifest.
            val ref = study.resultRef?.takeIf { it.sha256 in pack.results }
            ref?.let { held[it.sha256] = it.byteLength.toLong() }
            val bytes = ref?.let { java.util.Base64.getEncoder().encodeToString(pack.results.getValue(it.sha256)) }
            studies += study.copy(id = newID(), planID = planID, name = unique(study.name, studyNames), revision = null,
                importOrigin = ImportOrigin(study.id, study.revision ?: 1), resultRef = null, resultStatus = SavedResultStatus.NONE,
                coverage = study.coverage?.copy(calculatedResult = if (study.kind == SavedStudy.Kind.COVERAGE) bytes else null),
                linkProfileResult = if (study.kind == SavedStudy.Kind.LINK_PROFILE) bytes else null)
        }

        for (site in pack.candidateSitePlans.filter { it.id in selected }) {
            if (!copies && library.plans.any { sameSites(it, site) }) continue
            plans += site.copy(id = newID(), revision = null)
        }
        for (plan in pack.studyPlans.filter { it.id in selected }) {
            val members = pack.studies.filter { it.planID == plan.id }
            val local = if (copies) null else matchingPlan(plan, library)
            if (local != null) {
                members.filter { study -> library.studies.none { it.planID == local.id && same(study, it) } }.forEach { copy(it, local.id) }
                continue
            }
            val next = plan.copy(id = newID(), name = unique(plan.name, planNames), revision = null)
            containers += next
            members.forEach { copy(it, next.id) }
        }
        for (study in pack.studies.filter { it.planID == null && it.id in selected }) {
            if (!copies && library.studies.any { it.planID == null && same(study, it) }) continue
            copy(study, null)
        }

        if (plans.isEmpty() && containers.isEmpty() && studies.isEmpty()) throw Refusal.NothingSelected
        if (library.studies.size + studies.size > 100) throw Refusal.TooMany("studies")
        if (library.studyPlans.size + containers.size > 100) throw Refusal.TooMany("plans")
        if (library.plans.size + plans.size > 100) throw Refusal.TooMany("candidate site lists")
        // Result files are shared by content, so only bytes the library doesn't hold yet count.
        if (held.values.sum() > 512L * 1024 * 1024) throw Refusal.TooLarge
        return Records(plans, containers, studies)
    }

    /** A plan is identical when its name, notes and dates are. */
    private fun matchingPlan(plan: StudyPlan, library: Library) = library.studyPlans.firstOrNull {
        it.name == plan.name && it.notes == plan.notes && it.createdAtMilliseconds == plan.createdAtMilliseconds &&
            it.updatedAtMilliseconds == plan.updatedAtMilliseconds
    }

    /**
     * Kind, name, inputs, historical dates and source all equal, and the file brings no result this copy lacks: it has
     * none, or the local study's result shows here. Result bytes aren't compared: export converts older results, so a
     * backup of this very library carries different bytes for them. Membership is checked by the caller. Matches iOS.
     */
    private fun same(imported: SavedStudy, local: SavedStudy) = imported.kind == local.kind && imported.name == local.name &&
        imported.linkProfile == local.linkProfile && imported.coverage?.copy(calculatedResult = null) == local.coverage?.copy(calculatedResult = null) &&
        imported.createdAtMilliseconds == local.createdAtMilliseconds && imported.updatedAtMilliseconds == local.updatedAtMilliseconds &&
        imported.origin == local.origin && (imported.resultRef == null || local.resultStatus == SavedResultStatus.AVAILABLE)

    private fun sameSites(a: DeploymentPlan, b: DeploymentPlan) = a.name == b.name && a.sites == b.sites &&
        a.selectedSiteID == b.selectedSiteID && a.createdAtMilliseconds == b.createdAtMilliseconds && a.updatedAtMilliseconds == b.updatedAtMilliseconds

    /** The library's own naming: `name`, or `name 2`, `name 3`… within the 160-byte limit. */
    private fun unique(name: String, taken: MutableSet<String>): String =
        SavedPlansLibrary.uniqueName(name, taken).also { taken += it }

    private fun newID() = UUID.randomUUID().toString()

    /**
     * Reads at most the package limit; null if the file can't be read or is larger. When the provider reports
     * the size, the bytes go straight into one array of that size instead of a growing buffer and a copy.
     */
    fun readFile(context: android.content.Context, uri: Uri): ByteArray? = runCatching {
        val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (size > SavedPlansPackage.MAXIMUM_PACKAGE_BYTES) return null
        if (size >= 0) context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(size.toInt())
            var read = 0
            while (read < bytes.size) {
                val count = input.read(bytes, read, bytes.size - read)
                if (count < 0) return null
                read += count
            }
            if (input.read() >= 0) return null  // longer than the provider said
            return bytes
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size() > SavedPlansPackage.MAXIMUM_PACKAGE_BYTES) return null
            }
            out.toByteArray()
        }
    }.getOrNull()
}

/** Reads the file completely, shows what it holds, and imports the chosen items as copies. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPackageSheet(uri: Uri, dismiss: () -> Unit) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val library = SavedPlansLibrary.shared(context)
    val scope = rememberCoroutineScope()
    var preview by remember(uri) { mutableStateOf<SavedPlansImport.Preview?>(null) }
    var failure by remember(uri) { mutableStateOf<String?>(null) }
    var selected by remember(uri) { mutableStateOf(emptySet<String>()) }
    var inputsOnly by remember(uri) { mutableStateOf(false) }
    var problem by remember(uri) { mutableStateOf<String?>(null) }
    var importing by remember(uri) { mutableStateOf(false) }
    fun current() = SavedPlansImport.Library(library.plans, library.studyPlans, library.studies)

    LaunchedEffect(uri) {
        library.load()
        val outcome = withContext(Dispatchers.IO) {
            val data = SavedPlansImport.readFile(context, uri) ?: return@withContext Result.failure(SavedPlansPackage.Failure(false, "unreadable"))
            runCatching { SavedPlansPackage.read(data) }
        }
        outcome.onSuccess { pack ->
            val ready = SavedPlansImport.preview(pack, current())
            selected = ready.defaultSelection
            preview = ready
        }.onFailure { error ->
            failure = if ((error as? SavedPlansPackage.Failure)?.newerVersion == true) resources.getString(R.string.planner_this_file_needs_a_newer_version_of_nodescope)
                else resources.getString(R.string.planner_this_isn_t_a_nodescope_saved_plans_file)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !importing })
    ModalBottomSheet(onDismissRequest = { if (!importing) dismiss() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.planner_import_saved_plans), style = MaterialTheme.typography.titleLarge)
            val ready = preview
            when {
                failure != null -> Text(failure!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ready == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    val pack = ready.pack
                    Fact(stringResource(R.string.planner_from), stringResource(R.string.planner_nodescope, if (pack.exportedBy.platform == SavedPlansPackage.Platform.IOS) stringResource(R.string.planner_iphone_or_ipad) else stringResource(R.string.planner_android), pack.exportedBy.appVersion))
                    Fact(stringResource(R.string.planner_exported), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(pack.exportedAtMilliseconds)))
                    Fact(stringResource(R.string.planner_results), if (pack.includesResults) stringResource(R.string.planner_included, ready.results) else stringResource(R.string.planner_inputs_only))
                    HorizontalDivider()
                    Text(stringResource(R.string.planner_contents), style = MaterialTheme.typography.titleSmall)
                    ready.items.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name)
                                Text(detail(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (pack.scope == SavedPlansPackage.Scope.LIBRARY) Checkbox(item.id in selected, { on ->
                                selected = if (on) selected + item.id else selected - item.id
                            })
                        }
                    }
                    Text(if (ready.copiesMatches && ready.items.any { it.status != SavedPlansImport.Status.New })
                            stringResource(R.string.planner_already_in_your_library_importing_adds_a_separate)
                        else stringResource(R.string.planner_imported_items_are_added_as_copies_nothing_in),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ready.unusable.isNotEmpty()) {
                        HorizontalDivider()
                        Text(stringResource(R.string.planner_results_that_can_t_be_imported), style = MaterialTheme.typography.titleSmall)
                        ready.unusable.forEach { Text(it) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.planner_import_these_as_inputs_only), Modifier.weight(1f))
                            Switch(inputsOnly, { inputsOnly = it })
                        }
                        Text(if (inputsOnly) stringResource(R.string.planner_their_inputs_are_imported_calculate_to_see_results)
                            else stringResource(R.string.planner_these_studies_are_left_out_unless_you_import),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ready.omitted.isNotEmpty()) {
                        HorizontalDivider()
                        Text(stringResource(R.string.planner_left_out_when_exported), style = MaterialTheme.typography.titleSmall)
                        ready.omitted.forEach { (name, reason) -> Fact(name, SavedPlansExport.describe(reason)) }
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = dismiss, enabled = !importing) { Text(if (ready == null) stringResource(R.string.planner_close_2) else stringResource(R.string.common_cancel_2)) }
                if (ready != null) Button(onClick = {
                    problem = null
                    val records = try {
                        SavedPlansImport.records(ready, current(), selected, inputsOnly)
                    } catch (refusal: SavedPlansImport.Refusal) { problem = refusal.message; return@Button }
                    importing = true
                    scope.launch {
                        val saved = library.addRecords(records.plans, records.studyPlans, records.studies)
                        importing = false
                        if (saved) dismiss() else problem = library.error?.resolve(resources) ?: resources.getString(R.string.planner_the_import_couldn_t_be_saved_your_library)
                    }
                }, enabled = !importing && selected.isNotEmpty() && library.loaded) {
                    Text(if (ready.copiesMatches && ready.items.all { it.status == SavedPlansImport.Status.AlreadyHere }) stringResource(R.string.planner_import_copy) else stringResource(R.string.planner_import_2))
                }
            }
        }
    }
}

@Composable private fun detail(item: SavedPlansImport.Item): String {
    val detail = when (val status = item.status) {
        SavedPlansImport.Status.New -> stringResource(R.string.planner_import_new)
        SavedPlansImport.Status.AlreadyHere -> stringResource(R.string.planner_import_already_here)
        is SavedPlansImport.Status.PartlyHere -> stringResource(R.string.planner_import_partly_here, status.fresh)
    }
    return if (item.kind == SavedPlansImport.Kind.PLAN) stringResource(R.string.planner_import_item_with_count,
        pluralStringResource(R.plurals.planner_study_count, item.studies, item.studies), detail) else detail
}

@Composable private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, Modifier.weight(1f), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
