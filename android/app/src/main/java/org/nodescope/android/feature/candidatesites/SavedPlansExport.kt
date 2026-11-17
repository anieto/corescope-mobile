package org.nodescope.android.feature.candidatesites

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nodescope.android.BuildConfig
import org.nodescope.android.feature.coverage.PortableCoverageResult
import org.nodescope.android.feature.coverage.SavedCoverageCalculation

/**
 * Builds `.nodescope` packages from the saved library (docs/node-planner/saved-plans-v3-package.md, Exporting).
 * Pure: works on the records as loaded, with their results attached, and never changes the library. Mirrors iOS.
 */
object SavedPlansExport {
    sealed interface Contents {
        data class Study(val id: String) : Contents
        data class Plan(val id: String) : Contents
        data object Library : Contents
    }

    /** The package for [contents], or null if the study or plan no longer exists. */
    fun packageFor(contents: Contents, plans: List<DeploymentPlan>, studyPlans: List<StudyPlan>, studies: List<SavedStudy>,
                   includesResults: Boolean, includesNotes: Boolean, exportedAtMilliseconds: Long, appVersion: String): SavedPlansPackage? {
        val scope: SavedPlansPackage.Scope
        var chosenPlans = emptyList<StudyPlan>()
        var sites = emptyList<DeploymentPlan>()
        val chosen: List<SavedStudy>
        when (contents) {
            is Contents.Study -> {
                val study = studies.firstOrNull { it.id == contents.id } ?: return null
                scope = SavedPlansPackage.Scope.STUDY
                chosen = listOf(study.copy(planID = null))
            }
            is Contents.Plan -> {
                val plan = studyPlans.firstOrNull { it.id == contents.id } ?: return null
                scope = SavedPlansPackage.Scope.PLAN
                chosenPlans = listOf(plan)
                chosen = studies.filter { it.planID == contents.id }
            }
            Contents.Library -> {
                scope = SavedPlansPackage.Scope.LIBRARY
                chosenPlans = studyPlans
                chosen = studies
                sites = plans
            }
        }
        if (!includesNotes) chosenPlans = chosenPlans.map { it.copy(notes = null) }
        val results = mutableMapOf<String, ByteArray>()
        val omitted = mutableListOf<SavedPlansPackage.Omission>()
        val packaged = chosen.map { study ->
            val ref = study.resultRef
            if (!includesResults || ref == null) return@map study.copy(resultRef = null)
            when (val outcome = portable(study, ref)) {
                is Portable.Included -> { results[outcome.ref.sha256] = outcome.data; study.copy(resultRef = outcome.ref) }
                is Portable.Omitted -> { omitted += SavedPlansPackage.Omission(study.id, outcome.reason); study.copy(resultRef = null) }
            }
        }
        return SavedPlansPackage(scope, exportedAtMilliseconds, SavedPlansPackage.ExportedBy(SavedPlansPackage.Platform.ANDROID, appVersion),
            includesResults, includesNotes, sites, chosenPlans, packaged, omitted, results)
    }

    private sealed interface Portable {
        class Included(val ref: ResultRef, val data: ByteArray) : Portable
        class Omitted(val reason: SavedPlansPackage.OmissionReason) : Portable
    }

    /** The study's result in a portable format: as saved, or converted from this platform's legacy format. */
    private fun portable(study: SavedStudy, ref: ResultRef): Portable {
        when (study.resultStatus) {
            SavedResultStatus.MISSING -> return Portable.Omitted(SavedPlansPackage.OmissionReason.NOT_ON_DEVICE)
            SavedResultStatus.UNVERIFIED -> return Portable.Omitted(SavedPlansPackage.OmissionReason.COULD_NOT_BE_VERIFIED)
            SavedResultStatus.UNSUPPORTED -> return Portable.Omitted(if (ref.format.startsWith("legacy."))
                SavedPlansPackage.OmissionReason.NOT_READABLE_HERE else SavedPlansPackage.OmissionReason.NEEDS_NEWER_VERSION)
            else -> Unit
        }
        if (ref.format == ResultRef.PORTABLE_LINK_PROFILE) {
            val data = study.linkProfileResult?.let { Base64.getDecoder().decode(it) }
                ?: return Portable.Omitted(SavedPlansPackage.OmissionReason.NOT_ON_DEVICE)
            return Portable.Included(ref, data)
        }
        val coverage = study.coverage
        val encoded = coverage?.calculatedResult ?: return Portable.Omitted(SavedPlansPackage.OmissionReason.NOT_ON_DEVICE)
        if (ref.format == ResultRef.PORTABLE_COVERAGE) return Portable.Included(ref, Base64.getDecoder().decode(encoded))
        // This platform's legacy result: converted the way B converts it on the next save.
        val converted = runCatching {
            check(ref.format == ResultRef.ANDROID_LEGACY)
            val result = SavedCoverageCalculation.decode(encoded).restore(PortableCoverageResult.inputs(coverage),
                PortableCoverageResult.snapshots(coverage), coverage.catalogComplete)
            requireNotNull(PortableCoverageResult.encode(result))
        }.getOrNull()
        val fingerprint = converted?.let(PortableCoverageResult::fingerprint)
            ?: return Portable.Omitted(SavedPlansPackage.OmissionReason.NOT_READABLE_HERE)
        return Portable.Included(ResultRef(ResultRef.PORTABLE_COVERAGE, 1, converted.size, DeploymentPlanStore.sha256(converted), fingerprint), converted)
    }

    /** The file name: the study or plan name, or a dated library backup. */
    fun fileName(pack: SavedPlansPackage, exportedAtMilliseconds: Long): String {
        val base = when (pack.scope) {
            SavedPlansPackage.Scope.STUDY -> pack.studies.firstOrNull()?.name ?: "Study"
            SavedPlansPackage.Scope.PLAN -> pack.studyPlans.firstOrNull()?.name ?: "Plan"
            SavedPlansPackage.Scope.LIBRARY ->
                "NodeScope saved plans " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(exportedAtMilliseconds))
        }
        val safe = base.map { if (it in "/\\:?%*|\"<>" || it.isISOControl()) '-' else it }.joinToString("").take(100).trim()
        return "$safe.${SavedPlansPackage.FILE_EXTENSION}"
    }

    /** This build's version, limited to what a package's `appVersion` allows. */
    fun appVersion(): String = BuildConfig.VERSION_NAME.filter { it.isLetterOrDigit() && it.code < 128 || it in ".+-" }.take(32).ifEmpty { "0" }

    /** What a package reveals, for the preview before sharing. */
    data class Preview(
        val studies: Int = 0, val plans: Int = 0, val candidateSitePlans: Int = 0,
        /** Precise places: study sites and endpoints, and candidate sites. */
        val locations: Int = 0,
        /** Distinct captured nodes, by analyzer and ID; a few names for the summary. */
        val nodes: Int = 0, val nodeNames: List<String> = emptyList(),
        /** Analyzer and region of each study's inputs, as recorded. */
        val analyzers: List<String> = emptyList(), val unknownSource: Int = 0,
        val notedPlans: Int = 0, val results: Int = 0, val resultBytes: Long = 0,
        val omitted: List<Pair<String, SavedPlansPackage.OmissionReason>> = emptyList(),
    ) {
        companion object {
            fun of(pack: SavedPlansPackage): Preview {
                val seen = mutableSetOf<String>()
                val names = mutableListOf<String>()
                val places = sortedSetOf<String>()
                var unknown = 0
                for (study in pack.studies) {
                    for (node in study.coverage?.nodes.orEmpty()) {
                        if (seen.add("${node.sourceID}/${node.nodeID}")) node.name?.let { if (it !in names) names += it }
                    }
                    val host = study.origin?.host
                    if (host != null) places += study.origin.region?.let { "$host · $it" } ?: host else unknown++
                }
                val byID = pack.studies.associate { it.id to it.name }
                return Preview(pack.studies.size, pack.studyPlans.size, pack.candidateSitePlans.size,
                    pack.candidateSitePlans.sumOf { it.sites.size } + pack.studies.sumOf { if (it.kind == SavedStudy.Kind.LINK_PROFILE) 2 else 1 },
                    seen.size, names.take(3), places.toList(), unknown, pack.studyPlans.count { it.notes != null },
                    pack.studies.count { it.resultRef != null }, pack.results.values.sumOf { it.size.toLong() },
                    pack.omittedResults.map { (byID[it.studyID] ?: "A study") to it.reason })
            }
        }
    }

    fun describe(reason: SavedPlansPackage.OmissionReason) = when (reason) {
        SavedPlansPackage.OmissionReason.NOT_ON_DEVICE -> "Result not on this device"
        SavedPlansPackage.OmissionReason.COULD_NOT_BE_VERIFIED -> "Result could not be verified"
        SavedPlansPackage.OmissionReason.NEEDS_NEWER_VERSION -> "Result needs a newer version of NodeScope"
        SavedPlansPackage.OmissionReason.NOT_READABLE_HERE -> "Result can't be converted on this device"
    }
}

/**
 * Choices, a preview of what the file reveals, then Share or Save to device. Finishing either doesn't
 * mean a cloud provider has finished uploading, and the sheet doesn't say so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportPackageSheet(contents: SavedPlansExport.Contents, dismiss: () -> Unit) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val library = SavedPlansLibrary.shared(context)
    val scope = rememberCoroutineScope()
    var includesResults by rememberSaveable { mutableStateOf(true) }
    var includesNotes by rememberSaveable { mutableStateOf(true) }
    var decided by rememberSaveable { mutableStateOf(false) }
    val exportedAt = rememberSaveable { System.currentTimeMillis() }
    var preview by remember { mutableStateOf<SavedPlansExport.Preview?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val title = when (contents) {
        is SavedPlansExport.Contents.Study -> stringResource(R.string.planner_export_study)
        is SavedPlansExport.Contents.Plan -> stringResource(R.string.planner_export_plan_2)
        SavedPlansExport.Contents.Library -> stringResource(R.string.planner_back_up_library_2)
    }
    val hasNotes = when (contents) {
        is SavedPlansExport.Contents.Study -> false
        is SavedPlansExport.Contents.Plan -> library.studyPlans.firstOrNull { it.id == contents.id }?.notes != null
        SavedPlansExport.Contents.Library -> library.studyPlans.any { it.notes != null }
    }

    LaunchedEffect(includesResults, includesNotes, library.loaded) {
        library.load()
        file = null
        message = null
        val (plans, studyPlans, studies) = Triple(library.plans, library.studyPlans, library.studies)
        val version = SavedPlansExport.appVersion()
        if (!decided && library.loaded) {
            // A library backup includes results; a study or plan only when every result can travel. Decided once the
            // library has loaded, and only marked decided after the check finishes, so a cancelled run checks again.
            val trial = if (contents == SavedPlansExport.Contents.Library) null else withContext(Dispatchers.Default) {
                SavedPlansExport.packageFor(contents, plans, studyPlans, studies, true, true, exportedAt, version)
            }
            decided = true
            if (trial != null && trial.omittedResults.isNotEmpty()) { includesResults = false; return@LaunchedEffect }
        }
        val built = withContext(Dispatchers.IO) {
            runCatching {
                val pack = requireNotNull(SavedPlansExport.packageFor(contents, plans, studyPlans, studies, includesResults,
                    includesNotes, exportedAt, version))
                val folder = File(context.cacheDir, "exports/${UUID.randomUUID()}").apply { mkdirs() }
                val target = File(folder, SavedPlansExport.fileName(pack, exportedAt))
                target.writeBytes(pack.encoded())
                SavedPlansExport.Preview.of(pack) to target
            }.getOrNull()
        }
        if (built == null) message = resources.getString(R.string.planner_the_file_couldn_t_be_prepared_your_saved)
        else { preview = built.first; file = built.second }
    }
    // Files from earlier exports: a recipient may still have been reading them while that sheet was open.
    remember { File(context.cacheDir, "exports").deleteRecursively() }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SavedPlansPackage.MIME_TYPE)) { uri ->
        val source = file ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                // Streamed, so a large backup isn't held in memory.
                runCatching { context.contentResolver.openOutputStream(uri, "w")!!.use { out -> source.inputStream().use { it.copyTo(out) } } }.isSuccess
            }
            message = if (saved) resources.getString(R.string.planner_saved) else resources.getString(R.string.planner_the_file_couldn_t_be_saved_there_nothing)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            ToggleRow(stringResource(R.string.planner_include_available_results), includesResults) { includesResults = it }
            Text(if (includesResults) stringResource(R.string.planner_results_open_on_another_device_without_calculating_again)
                else stringResource(R.string.planner_inputs_only_the_recipient_calculates_again_to_see),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasNotes) ToggleRow(stringResource(R.string.planner_include_plan_notes), includesNotes) { includesNotes = it }
            HorizontalDivider()
            Text(stringResource(R.string.planner_this_file_shows), style = MaterialTheme.typography.titleSmall)
            val shown = preview
            if (shown == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                Fact(stringResource(R.string.planner_studies), stringResource(R.string.planner_value_3, shown.studies))
                if (shown.plans > 0) Fact(stringResource(R.string.planner_plans), stringResource(R.string.planner_value_3, shown.plans))
                if (shown.candidateSitePlans > 0) Fact(stringResource(R.string.planner_candidate_site_lists), stringResource(R.string.planner_value_3, shown.candidateSitePlans))
                Fact(stringResource(R.string.planner_precise_locations), stringResource(R.string.planner_value_3, shown.locations))
                if (shown.nodes > 0) {
                    val more = shown.nodes - shown.nodeNames.size
                    val names = shown.nodeNames.joinToString()
                    Fact(stringResource(R.string.planner_node_names_and_ids), if (names.isEmpty()) stringResource(R.string.planner_value_3, shown.nodes) else if (more > 0) stringResource(R.string.planner_and_more, names, more) else names)
                }
                if (shown.analyzers.isNotEmpty() || shown.unknownSource > 0)
                    Fact(stringResource(R.string.planner_analyzer), (shown.analyzers + listOfNotNull(if (shown.unknownSource > 0) stringResource(R.string.planner_unknown_source, shown.unknownSource) else null)).joinToString("\n"))
                if (shown.notedPlans > 0) Fact(stringResource(R.string.planner_plan_notes), stringResource(R.string.planner_value_3, shown.notedPlans))
                if (includesResults) Fact(stringResource(R.string.planner_results), if (shown.results == 0) stringResource(R.string.planner_none)
                    else stringResource(R.string.planner_value_4, shown.results, Formatter.formatShortFileSize(context, shown.resultBytes)))
                if (includesResults && shown.omitted.isNotEmpty()) {
                    Text(stringResource(R.string.planner_results_not_included), style = MaterialTheme.typography.titleSmall)
                    shown.omitted.forEach { (name, reason) -> Fact(name, SavedPlansExport.describe(reason)) }
                }
            }
            Text(stringResource(R.string.planner_locations_aren_t_hidden_a_study_without_them),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onClick = { file?.let { saver.launch(it.name) } }, enabled = file != null) {
                    Icon(Icons.Outlined.SaveAlt, null)
                    Text(stringResource(R.string.planner_save_to_device), Modifier.padding(start = 8.dp))
                }
                Button(onClick = {
                    val target = file ?: return@Button
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", target)
                    val send = Intent(Intent.ACTION_SEND).setType(SavedPlansPackage.MIME_TYPE).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, title))
                }, enabled = file != null) {
                    Icon(Icons.Outlined.Share, null)
                    Text(stringResource(R.string.common_share), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, Modifier.weight(1f), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
