package org.nodescope.android.feature.onboarding

import org.nodescope.android.core.network.analyzerErrorText

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
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
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.model.AnalyzerSource
import org.nodescope.android.core.model.AnalyzerSourceChoices
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
    sources: List<AnalyzerSource>, savedHost: String?, saving: Boolean, error: String?,
    onFinish: (String) -> Unit, icons: SourceIcons? = null,
) {
    val resources = LocalResources.current
    var step by rememberSaveable { mutableStateOf(OnboardingStep.SOURCE) }
    var chosenHost by rememberSaveable { mutableStateOf<String?>(null) }
    val choices = AnalyzerSourceChoices(sources, displayLocale = LocalConfiguration.current.locales[0])
    val host = chosenHost ?: choices.onboardingHost(savedHost, isOnboarded = false).orEmpty()
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = picking || step == OnboardingStep.OVERVIEW) {
        if (picking) picking = false else step = OnboardingStep.SOURCE
    }
    if (picking) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text(stringResource(R.string.onboarding_analyzer_source)) }, colors = org.nodescope.android.core.design.meshTopAppBarColors(), windowInsets = WindowInsets(0, 0, 0, 0), navigationIcon = {
                IconButton(onClick = { picking = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            })
            SourceScreen(sources, host, saving = false, error = pickError, onSave = { chosen ->
                runCatching { normalizeHost(chosen) }
                    .onSuccess { chosenHost = it; pickError = null; picking = false }
                    .onFailure { pickError = it.analyzerErrorText(R.string.onboarding_enter_a_valid_analyzer_hostname).resolve(resources) }
            }, icons = icons)
        }
        return
    }
    val source = sources.firstOrNull { runCatching { normalizeHost(it.host) }.getOrNull().equals(host, true) }
    when (step) {
        OnboardingStep.SOURCE -> OnboardingPage(
            icon = null, title = stringResource(R.string.welcome),
            description = stringResource(R.string.onboarding_choose_a_community_analyzer_to_explore_its_live),
            primary = stringResource(R.string.continue_label), primaryEnabled = host.isNotBlank(),
            onPrimary = { chosenHost = host; step = OnboardingStep.OVERVIEW },
        ) {
            Card(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (source != null) SourceLogo(source, icons)
                    else Icon(Icons.Outlined.SettingsInputAntenna, null, Modifier.size(40.dp).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        val name = when {
                            host.isBlank() -> stringResource(R.string.onboarding_choose_an_analyzer)
                            source != null -> source.name
                            else -> stringResource(R.string.onboarding_custom_analyzer_2)
                        }
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        if (host.isNotBlank()) {
                            Text(host, style = MaterialTheme.typography.bodySmall, fontFamily = MonaspaceNeon,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(Icons.Outlined.ChevronRight, stringResource(R.string.onboarding_choose_analyzer), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val note = if (host.isBlank()) {
                choices.userCountryName?.let { stringResource(R.string.onboarding_no_community_for_country, it) }
                    ?: stringResource(R.string.onboarding_no_community_for_region)
            } else {
                stringResource(R.string.onboarding_you_can_change_sources_anytime_in_settings_or)
            }
            Text(note, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OnboardingStep.OVERVIEW -> OnboardingPage(
            icon = Icons.Outlined.Map, title = stringResource(R.string.onboarding_follow_the_mesh),
            description = stringResource(R.string.onboarding_nodescope_makes_live_activity_easier_to_understand_at),
            primary = stringResource(if (saving) R.string.saving else R.string.start_exploring), primaryEnabled = !saving,
            onPrimary = { onFinish(host) }, onBack = { step = OnboardingStep.SOURCE },
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FeatureRow(Icons.Outlined.Hub, stringResource(R.string.onboarding_explore_nodes), stringResource(R.string.onboarding_tap_a_node_for_its_details_clusters_zoom))
                    FeatureRow(Icons.Outlined.Route, stringResource(R.string.onboarding_watch_packet_routes),
                        stringResource(R.string.onboarding_live_routes_animate_hop_by_hop_open_a))
                    FeatureRow(Icons.Outlined.FilterList, stringResource(R.string.onboarding_focus_your_view), stringResource(R.string.onboarding_choose_a_region_on_the_map_to_narrow))
                }
            }
            Text(stringResource(R.string.onboarding_location_uses), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Icon (the app icon when [icon] is null, as on the welcome step), title and description, the
 * step's content, then the primary action at the bottom.
 */
@Composable
private fun OnboardingPage(
    icon: ImageVector?, title: String, description: String, primary: String, primaryEnabled: Boolean,
    onPrimary: () -> Unit, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            onBack?.let { back -> TextButton(onClick = back, Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { Text(stringResource(R.string.back)) } }
        }
        // Centered when it fits; scrolls (e.g. at large text sizes) when it doesn't.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Column(Modifier.widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (icon != null) Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                    else androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.app_icon), null,
                        Modifier.size(96.dp).shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)))
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
