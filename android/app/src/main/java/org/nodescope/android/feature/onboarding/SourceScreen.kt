package org.nodescope.android.feature.onboarding

import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.content.Intent
import android.content.Context
import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import org.nodescope.android.core.storage.SourceIcons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import org.nodescope.android.core.design.SectionLabel
import org.nodescope.android.core.model.AnalyzerSource
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
    fun isCurrent(source: AnalyzerSource) = runCatching { normalizeHost(source.host) }.getOrNull().equals(currentHost, true)
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionLabel("US community sources", Modifier.padding(horizontal = 4.dp)) }
        items(sources, key = { it.id }) { source ->
            val current = isCurrent(source)
            Card(onClick = { onSave(source.host) }, enabled = !saving,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().semantics { selected = current }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceLogo(source, icons)
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text(source.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(source.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (current) Icon(Icons.Outlined.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Text("Community sources are fetched from NodeScope's source registry when available.", Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionLabel("Custom analyzer", Modifier.padding(horizontal = 4.dp)) }
        item {
            OutlinedTextField(custom, { custom = it }, enabled = !saving,
                label = { Text(stringResource(R.string.analyzer_hostname)) }, placeholder = { Text("analyzer.example.org") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (custom.isNotBlank()) onSave(custom) }))
        }
        if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
        item {
            FilledTonalButton(onClick = { onSave(custom) }, enabled = !saving && custom.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) stringResource(R.string.saving) else "Use custom analyzer")
            }
        }
        item {
            Text(stringResource(R.string.source_privacy), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            val context = LocalContext.current
            TextButton(onClick = { requestCommunitySource(context) }) {
                Icon(Icons.Outlined.Email, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Request a community source")
            }
            Text("Suggest a public analyzer for the community list. Please confirm that its owner or operator permits it to be listed and used in NodeScope.",
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Where community source requests go, and the prefilled form (same as iOS). */
internal const val SOURCE_REQUEST_EMAIL = "betweenthieves@protonmail.com"
internal val sourceRequestBody = listOf(
    "Hello NodeScope team,", "",
    "I'd like to request that the following community source be added.", "",
    "SOURCE DETAILS", "Source name:", "Analyzer hostname or URL:", "Region or community:", "Owner or operator contact:", "",
    "PERMISSION",
    "Do you have permission from the source owner or operator for this source to be listed and used in NodeScope?",
    "Answer: Yes / No / Not yet", "",
    "ADDITIONAL DETAILS", "", "Thank you.",
).joinToString("\r\n")

/** Opens the person's email app with the request prefilled; nothing happens without one. */
private fun requestCommunitySource(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SOURCE_REQUEST_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "NodeScope community source request")
        putExtra(Intent.EXTRA_TEXT, sourceRequestBody)
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No email app is set up. Write to $SOURCE_REQUEST_EMAIL.", Toast.LENGTH_LONG).show()
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
