package org.nodescope.android.feature.onboarding

import android.icu.text.ListFormatter
import androidx.compose.ui.text.font.FontStyle
import java.util.Locale
import org.nodescope.android.core.model.AnalyzerServiceArea

import org.nodescope.android.core.design.meshCardColors
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.content.Intent
import android.content.Context
import android.content.ActivityNotFoundException
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import org.nodescope.android.core.storage.SourceIcons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.nodescope.android.R
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.design.SectionLabel
import org.nodescope.android.core.model.AnalyzerSource
import org.nodescope.android.core.model.AnalyzerSourceChoices
import org.nodescope.android.core.model.TexasBoundary
import org.nodescope.android.core.model.AnalyzerSourceType
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.feature.map.LocationResult
import org.nodescope.android.feature.map.currentLocation
import org.nodescope.android.feature.map.hasLocationPermission
import org.nodescope.android.core.network.normalizeHost

/**
 * iOS `AnalyzerSourcePickerScreen`, used from Settings and from onboarding: a community
 * source applies as soon as it is tapped; a custom host has its own action.
 */
@Composable
fun SourceScreen(
    sources: List<AnalyzerSource>, currentHost: String,
    saving: Boolean, error: String?, onSave: (String) -> Unit, icons: SourceIcons? = null,
) {
    var custom by rememberSaveable(currentHost) {
        mutableStateOf(currentHost.takeIf { current -> sources.none { it.host.equals(current, true) } }.orEmpty())
    }
    val context = LocalContext.current
    val choices = AnalyzerSourceChoices(sources, displayLocale = LocalConfiguration.current.locales[0])
    val permissionHistory = remember(context) {
        context.getSharedPreferences("source_picker", Context.MODE_PRIVATE)
    }
    var permissionRequested by remember {
        mutableStateOf(permissionHistory.getBoolean("location_requested", false))
    }
    val texasBoundary = remember(context) {
        lazy {
            runCatching {
                context.assets.open("texas-boundary.json").bufferedReader().use { TexasBoundary(it.readText()) }
            }.getOrNull()
        }
    }
    var isLocating by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf<Coordinate?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        isLocating = granted
    }
    LaunchedEffect(isLocating) {
        if (isLocating) {
            try {
                if (hasLocationPermission(context)) {
                    location = (currentLocation(context) as? LocationResult.Found)?.coordinate
                }
            } finally {
                isLocating = false
            }
        }
    }
    val customAndRequest: LazyListScope.() -> Unit = {
        customAnalyzerItems(custom, { custom = it }, choices.customFirst, saving, error, onSave)
        requestSourceItem(choices.userCountryName.orEmpty())
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TextButton(
            onClick = {
                if (location != null) {
                    location = null
                } else if (hasLocationPermission(context)) {
                    isLocating = true
                } else if (!permissionRequested) {
                    permissionRequested = true
                    permissionHistory.edit().putBoolean("location_requested", true).apply()
                    permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            },
            enabled = !isLocating && (location != null || hasLocationPermission(context) || !permissionRequested),
            modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(
                if (location == null) R.string.onboarding_sort_by_nearest else R.string.onboarding_group_by_country,
            ))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (choices.customFirst) customAndRequest()
            val origin = location
            if (origin != null) {
                item("nearest_header") {
                    SectionLabel(stringResource(R.string.onboarding_nearest_to_you), Modifier.padding(horizontal = 4.dp))
                }
                items(choices.nearest(origin, texasBoundary.value), key = { "source_${it.id}" }) {
                    CommunitySourceRow(it, currentHost, saving, onSave, icons)
                }
            } else {
                for (section in choices.countrySections()) {
                    item("section_${section.country}") {
                        SectionLabel(section.name ?: stringResource(R.string.onboarding_other_sources), Modifier.padding(horizontal = 4.dp))
                    }
                    items(section.sources, key = { "source_${it.id}" }) {
                        CommunitySourceRow(it, currentHost, saving, onSave, icons)
                    }
                }
            }
            item {
                Text(stringResource(R.string.onboarding_community_sources_are_fetched_from_nodescope_s_source),
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!choices.customFirst) customAndRequest()
        }
    }
}

@Composable
private fun CommunitySourceRow(source: AnalyzerSource, currentHost: String, saving: Boolean,
                               onSave: (String) -> Unit, icons: SourceIcons?) {
    val current = runCatching { normalizeHost(source.host) }.getOrNull().equals(currentHost, true)
    Card(onClick = { onSave(source.host) }, enabled = !saving,
        colors = meshCardColors(),
        modifier = Modifier.fillMaxWidth().semantics { selected = current }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceLogo(source, icons)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(source.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
                    SourceTypeLabel(source.backendType)
                }
                val subtitle = source.displaySubtitle(stringResource(R.string.content_language))
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val area = serviceAreaLabel(source.serviceAreas.orEmpty(), LocalConfiguration.current.locales[0])
                if (area != null) {
                    Text(area, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    Text(source.host, style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonaspaceNeon, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (current) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.onboarding_selected), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun serviceAreaLabel(areas: List<AnalyzerServiceArea>, locale: Locale): String? {
    val countries = areas.groupBy { it.country }.toSortedMap()
    if (countries.isEmpty()) return null
    return countries.entries.joinToString(" · ") { (country, countryAreas) ->
        val labels = countryAreas.mapNotNull { area ->
            val region = area.subdivisionName ?: area.subdivision
            area.metros.singleOrNull()?.let { metro ->
                listOfNotNull(metro.name, region).joinToString(", ")
            } ?: region
        }
        val name = runCatching { Locale.Builder().setRegion(country).build().getDisplayCountry(locale) }
            .getOrDefault(country).ifBlank { country }
        if (labels.isEmpty()) name else {
            val regions = ListFormatter.getInstance(locale).format(labels)
            if (country == "US" && countries.size == 1) regions else "$regions, $name"
        }
    }
}

private fun LazyListScope.customAnalyzerItems(
    custom: String, onCustomChange: (String) -> Unit, customFirst: Boolean,
    saving: Boolean, error: String?, onSave: (String) -> Unit,
) {
    item {
        Card(modifier = Modifier.fillMaxWidth(), colors = meshCardColors()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel(stringResource(R.string.onboarding_custom_analyzer))
                OutlinedTextField(custom, onCustomChange, enabled = !saving,
                    label = { Text(stringResource(R.string.analyzer_hostname)) },
                    placeholder = { Text(stringResource(R.string.onboarding_analyzer_hostname_example)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (custom.isNotBlank()) onSave(custom) }))
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                FilledTonalButton(onClick = { onSave(custom) }, enabled = !saving && custom.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (saving) R.string.saving else R.string.onboarding_use_custom_analyzer))
                }
            }
        }
    }
    item {
        Text(stringResource(R.string.source_privacy), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (customFirst) item {
        Text(stringResource(R.string.onboarding_custom_analyzer_hint), Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.requestSourceItem(country: String) {
    item {
        val context = LocalContext.current
        TextButton(onClick = { requestCommunitySource(context, country) }) {
            Icon(Icons.Outlined.Email, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_request_a_community_source))
        }
        Text(stringResource(R.string.onboarding_suggest_a_public_analyzer_for_the_community_list),
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Where community source requests go, and the prefilled form (same as iOS). */
internal const val SOURCE_REQUEST_EMAIL = "betweenthieves@protonmail.com"
/** Opens the person's email app with the request prefilled; nothing happens without one. */
private fun requestCommunitySource(context: Context, country: String) {
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SOURCE_REQUEST_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.onboarding_source_request_subject))
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.onboarding_source_request_body, country).replace("\n", "\r\n"))
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.onboarding_no_email_app, SOURCE_REQUEST_EMAIL), Toast.LENGTH_LONG).show()
    }
}

/** The community's logo from the registry; the generic symbol when it has none or it can't load. */
@Composable
internal fun SourceLogo(source: AnalyzerSource, icons: SourceIcons?) {
    val logo by produceState<ImageBitmap?>(null, source.icon, icons) { value = icons?.load(source.icon) }
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        logo?.let { Image(it, null, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))) }
            ?: Icon(if (source.isDefault) Icons.Outlined.Stars else Icons.Outlined.SettingsInputAntenna, null,
                tint = if (source.isDefault) Color(0xFFE0A100) else MaterialTheme.colorScheme.primary)
    }
}

/** Which server software a listed source runs, so Beacon sources are recognizable before choosing. */
@Composable
private fun SourceTypeLabel(type: AnalyzerSourceType) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))) {
        Text(
            if (type == AnalyzerSourceType.BEACON) stringResource(R.string.onboarding_beacon_beta) else stringResource(R.string.onboarding_corescope),
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
