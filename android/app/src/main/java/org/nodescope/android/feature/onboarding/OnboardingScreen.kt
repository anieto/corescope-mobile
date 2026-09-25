package org.nodescope.android.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.nodescope.android.R
import org.nodescope.android.core.model.AnalyzerSource
import org.nodescope.android.core.network.normalizeHost
import org.nodescope.android.core.storage.SourceIcons

private enum class OnboardingStep { SOURCE, OVERVIEW }

/**
 * First launch, as on iOS (`OnboardingScreen`): choose an analyzer, then a short overview of
 * what the app shows, then "Start exploring" saves the source. The analyzer card opens the
 * same picker Settings uses; choosing there comes back here with that source selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    sources: List<AnalyzerSource>, initialHost: String, saving: Boolean, error: String?,
    onFinish: (String) -> Unit, icons: SourceIcons? = null,
) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.SOURCE) }
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = picking || step == OnboardingStep.OVERVIEW) {
        if (picking) picking = false else step = OnboardingStep.SOURCE
    }
    if (picking) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Analyzer source") }, windowInsets = WindowInsets(0, 0, 0, 0), navigationIcon = {
                IconButton(onClick = { picking = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            })
            SourceScreen(sources, host, saving = false, error = pickError, onSave = { chosen ->
                runCatching { normalizeHost(chosen) }
                    .onSuccess { host = it; pickError = null; picking = false }
                    .onFailure { pickError = it.message ?: "Enter a valid analyzer hostname." }
            }, icons = icons)
        }
        return
    }
    val source = sources.firstOrNull { runCatching { normalizeHost(it.host) }.getOrNull().equals(host, true) }
    when (step) {
        OnboardingStep.SOURCE -> OnboardingPage(
            icon = Icons.Outlined.SettingsInputAntenna, title = stringResource(R.string.welcome),
            description = "Choose a community analyzer to explore its live nodes, routes, channels, and observers.",
            primary = stringResource(R.string.continue_label), primaryEnabled = host.isNotBlank(),
            onPrimary = { step = OnboardingStep.OVERVIEW },
        ) {
            Card(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (source != null) SourceLogo(source, icons)
                    else Icon(Icons.Outlined.SettingsInputAntenna, null, Modifier.size(40.dp).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(source?.name ?: "Custom analyzer", style = MaterialTheme.typography.titleMedium)
                        Text(host.ifBlank { "Choose an analyzer" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, "Choose analyzer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("You can change sources anytime in Settings, or add a custom analyzer.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OnboardingStep.OVERVIEW -> OnboardingPage(
            icon = Icons.Outlined.Map, title = "Follow the mesh",
            description = "NodeScope makes live activity easier to understand at a glance.",
            primary = stringResource(if (saving) R.string.saving else R.string.start_exploring), primaryEnabled = !saving,
            onPrimary = { onFinish(host) }, onBack = { step = OnboardingStep.SOURCE },
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FeatureRow(Icons.Outlined.Hub, "Explore nodes", "Tap a node for its details. Clusters zoom in to reveal nearby nodes.")
                    FeatureRow(Icons.Outlined.Route, "Watch packet routes",
                        "Live routes animate hop by hop. Open a channel message to replay its path.")
                    FeatureRow(Icons.Outlined.FilterList, "Focus your view", "Choose a region on the map to narrow traffic and center the view.")
                }
            }
            Text("Location is optional and is only used when you choose to center the map on yourself.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/** Icon, title and description, the step's content, then the primary action at the bottom. */
@Composable
private fun OnboardingPage(
    icon: ImageVector, title: String, description: String, primary: String, primaryEnabled: Boolean,
    onPrimary: () -> Unit, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            onBack?.let { back -> TextButton(onClick = back, Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { Text("Back") } }
        }
        // Centered when it fits; scrolls (e.g. at large text sizes) when it doesn't.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Column(Modifier.widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text(description, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    content()
                }
            }
        }
        Button(onClick = onPrimary, enabled = primaryEnabled,
            modifier = Modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) { Text(primary) }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
