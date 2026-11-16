package org.nodescope.android.feature.candidatesites

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

        fun copy(study: SavedStudy, planID: String?) {
            if (study.id in pack.unusableResults && !inputsOnly) return
            // The bytes travel in memory (base64, as loaded); the store installs them as result files before the manifest.
            val bytes = study.resultRef?.let { pack.results[it.sha256] }?.let(java.util.Base64.getEncoder()::encodeToString)
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
        val held = library.studies.mapNotNull { it.resultRef }.associate { it.sha256 to it.byteLength.toLong() }.toMutableMap()
        for (study in studies) {
            val encoded = study.coverage?.calculatedResult ?: study.linkProfileResult ?: continue
            val data = java.util.Base64.getDecoder().decode(encoded)
            held[DeploymentPlanStore.sha256(data)] = data.size.toLong()
        }
        if (held.values.sum() > 512L * 1024 * 1024) throw Refusal.TooLarge
        return Records(plans, containers, studies)
    }

    /** A plan is identical when its name, notes and dates are. */
    private fun matchingPlan(plan: StudyPlan, library: Library) = library.studyPlans.firstOrNull {
        it.name == plan.name && it.notes == plan.notes && it.createdAtMilliseconds == plan.createdAtMilliseconds &&
            it.updatedAtMilliseconds == plan.updatedAtMilliseconds
    }

    /** Kind, name, inputs, historical dates, source and result (by content) all equal. Membership is checked by the caller. */
    private fun same(imported: SavedStudy, local: SavedStudy) = imported.kind == local.kind && imported.name == local.name &&
        imported.linkProfile == local.linkProfile && imported.coverage?.copy(calculatedResult = null) == local.coverage?.copy(calculatedResult = null) &&
        imported.createdAtMilliseconds == local.createdAtMilliseconds && imported.updatedAtMilliseconds == local.updatedAtMilliseconds &&
        imported.origin == local.origin && imported.resultRef?.sha256 == local.resultRef?.sha256

    private fun sameSites(a: DeploymentPlan, b: DeploymentPlan) = a.name == b.name && a.sites == b.sites &&
        a.selectedSiteID == b.selectedSiteID && a.createdAtMilliseconds == b.createdAtMilliseconds && a.updatedAtMilliseconds == b.updatedAtMilliseconds

    /** `name`, or `name 2`, `name 3`… within the 160-byte limit, as the library names copies. */
    private fun unique(name: String, taken: MutableSet<String>): String {
        var candidate = name
        var number = 2
        while (candidate in taken) {
            val suffix = " $number"
            var base = name
            while ((base + suffix).toByteArray(Charsets.UTF_8).size > 160 && base.isNotEmpty()) base = base.dropLast(1)
            candidate = base + suffix
            number++
        }
        taken += candidate
        return candidate
    }

    private fun newID() = UUID.randomUUID().toString()

    /** Reads at most the package limit; null if the file can't be read or is larger. */
    fun readFile(context: android.content.Context, uri: Uri): ByteArray? = runCatching {
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
            failure = if ((error as? SavedPlansPackage.Failure)?.newerVersion == true) "This file needs a newer version of NodeScope. Nothing was imported."
                else "This isn't a NodeScope saved-plans file, or it's damaged or too large. Nothing was imported."
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !importing })
    ModalBottomSheet(onDismissRequest = { if (!importing) dismiss() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import saved plans", style = MaterialTheme.typography.titleLarge)
            val ready = preview
            when {
                failure != null -> Text(failure!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ready == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    val pack = ready.pack
                    Fact("From", "${if (pack.exportedBy.platform == SavedPlansPackage.Platform.IOS) "iPhone or iPad" else "Android"} · NodeScope ${pack.exportedBy.appVersion}")
                    Fact("Exported", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(pack.exportedAtMilliseconds)))
                    Fact("Results", if (pack.includesResults) "${ready.results} included" else "Inputs only")
                    HorizontalDivider()
                    Text("Contents", style = MaterialTheme.typography.titleSmall)
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
                            "Already in your library. Importing adds a separate copy; nothing here is replaced."
                        else "Imported items are added as copies. Nothing in your library is replaced, and nothing is calculated.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ready.unusable.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Results that can't be imported", style = MaterialTheme.typography.titleSmall)
                        ready.unusable.forEach { Text(it) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Import these as inputs only", Modifier.weight(1f))
                            Switch(inputsOnly, { inputsOnly = it })
                        }
                        Text(if (inputsOnly) "Their inputs are imported; calculate to see results."
                            else "These studies are left out unless you import their inputs only.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ready.omitted.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Left out when exported", style = MaterialTheme.typography.titleSmall)
                        ready.omitted.forEach { (name, reason) -> Fact(name, SavedPlansExport.describe(reason)) }
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = dismiss, enabled = !importing) { Text(if (ready == null) "Close" else "Cancel") }
                if (ready != null) Button(onClick = {
                    problem = null
                    val records = try {
                        SavedPlansImport.records(ready, current(), selected, inputsOnly)
                    } catch (refusal: SavedPlansImport.Refusal) { problem = refusal.message; return@Button }
                    importing = true
                    scope.launch {
                        val saved = library.addRecords(records.plans, records.studyPlans, records.studies)
                        importing = false
                        if (saved) dismiss() else problem = library.error ?: "The import couldn't be saved. Your library hasn't changed."
                    }
                }, enabled = !importing && selected.isNotEmpty() && library.loaded) {
                    Text(if (ready.copiesMatches && ready.items.all { it.status == SavedPlansImport.Status.AlreadyHere }) "Import copy" else "Import")
                }
            }
        }
    }
}

private fun detail(item: SavedPlansImport.Item): String {
    val count = if (item.kind == SavedPlansImport.Kind.PLAN) "${item.studies} ${if (item.studies == 1) "study" else "studies"} · " else ""
    return count + when (val status = item.status) {
        SavedPlansImport.Status.New -> "New"
        SavedPlansImport.Status.AlreadyHere -> "Already in your library"
        is SavedPlansImport.Status.PartlyHere -> "${status.fresh} not in your library yet"
    }
}

@Composable private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, Modifier.weight(1f), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
