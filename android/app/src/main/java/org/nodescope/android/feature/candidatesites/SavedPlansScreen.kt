package org.nodescope.android.feature.candidatesites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.core.design.meshPageBackground

/**
 * Saved link profiles and coverage studies, then Candidate Sites, as on iOS. Opening a study pushes
 * a fresh tool with its inputs; [kind] narrows the list to one tool's studies (its Open button).
 */
@Composable
fun SavedPlansScreen(kind: SavedStudy.Kind?, onOpen: (SavedStudy) -> Unit, onCandidateSites: () -> Unit) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<SavedStudy?>(null) }
    LaunchedEffect(library) { library.load() }
    val visible = library.studies.filter { kind == null || it.kind == kind }.sortedByDescending { it.updatedAtMilliseconds }
    Column(Modifier.fillMaxSize().meshPageBackground().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Saved plans stay on this device. Coverage saves include available calculation results. Terrain files remain in the separate cache.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Studies", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
            Column {
                if (visible.isEmpty() && library.loaded) Text("Save a study from Link Profile or Estimated Coverage.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                visible.forEachIndexed { index, study ->
                    if (index > 0) HorizontalDivider()
                    SavedStudyRow(study, enabled = !library.busy, open = { onOpen(study) }, delete = { deleting = study })
                }
            }
        }
        if (kind == null) Card(Modifier.fillMaxWidth().clickable(onClick = onCandidateSites), colors = meshCardColors()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Candidate sites", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
        library.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { scope.launch { library.reload() } }, enabled = !library.busy) { Text("Reload library") }
        }
        if (library.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    deleting?.let { study ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this saved study?") },
            text = { Text(study.name) },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { library.deleteStudy(study) } }) {
                Text("Delete study", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable private fun SavedStudyRow(study: SavedStudy, enabled: Boolean, open: () -> Unit, delete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = open).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(study.name, style = MaterialTheme.typography.titleSmall)
            Text(if (study.kind == SavedStudy.Kind.LINK_PROFILE) "Link Profile" else "Estimated Coverage",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                study.kind == SavedStudy.Kind.LINK_PROFILE -> "Inputs only · Calculate to see results"
                study.coverage?.calculatedResult == null -> "Inputs only · Calculate to see results"
                else -> "Includes saved coverage results"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = delete, enabled = enabled) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * Save and Open for a tool, as iOS's `SavedStudyActions`. Save names the current setup and stores
 * it (replacing the study it was opened from); Open lists this tool's saved studies.
 */
@Composable
fun SavedStudyActions(snapshot: () -> SavedStudy?, didSave: (SavedStudy) -> Unit, onOpen: () -> Unit) {
    var draft by remember { mutableStateOf<SavedStudy?>(null) }
    val available = snapshot() != null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { draft = snapshot() }, enabled = available) { Text("Save") }
        OutlinedButton(onClick = onOpen) { Text("Open") }
    }
    draft?.let { study -> SaveStudyDialog(study, { draft = null }) { saved -> draft = null; didSave(saved) } }
}

@Composable private fun SaveStudyDialog(initial: SavedStudy, dismiss: () -> Unit, saved: (SavedStudy) -> Unit) {
    val library = SavedPlansLibrary.shared(LocalContext.current)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    // The suggested name clears on first focus, so typing replaces it, as on iOS.
    var edited by remember { mutableStateOf(false) }
    LaunchedEffect(library) { library.load() }
    val study = initial.copy(name = name, updatedAtMilliseconds = maxOf(initial.createdAtMilliseconds, System.currentTimeMillis()))
    val valid = runCatching { study.validate() }.isSuccess
    AlertDialog(onDismissRequest = { if (!library.busy) dismiss() }, title = { Text("Save study") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused && !edited) { name = ""; edited = true } },
                label = { Text("Study name") }, singleLine = true, enabled = !library.busy)
            Text("Saves the current inputs and captured node locations. Estimated Coverage also saves available results; Link Profile saves inputs only.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            library.error?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { library.reload() } }, enabled = !library.busy) { Text("Reload library") }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { scope.launch { if (library.saveStudy(study)) saved(study) } },
            enabled = library.loaded && !library.busy && valid) { Text("Save") }
    }, dismissButton = { TextButton(onClick = dismiss, enabled = !library.busy) { Text("Cancel") } })
}
