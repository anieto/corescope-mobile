package org.nodescope.android.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.nodescope.android.BuildConfig
import org.nodescope.android.R
import org.nodescope.android.core.design.*
import org.nodescope.android.core.network.LiveConnection
import org.nodescope.android.core.storage.*

private sealed interface SourceStatus {
    data object Connecting : SourceStatus
    data object Connected : SourceStatus
    data class Failed(val message: String) : SourceStatus
}

/** iOS `SettingsScreen`: appearance, analyzer source, connection and diagnostics, storage, about. */
@Composable
fun SettingsScreen(
    preferences: AppPreferences, connection: LiveConnection, sessionError: String?, sourceChangedAt: Long?,
    onAppearance: (Appearance) -> Unit, onSource: () -> Unit, onDiagnostics: () -> Unit, onStorage: () -> Unit,
    onAbout: () -> Unit, onMapLab: () -> Unit, onSupport: (() -> Unit)? = null,
    onDistanceUnit: (DistanceUnit) -> Unit = {},
) {
    // After an analyzer change, report whether the new source connects (as iOS does).
    var status by remember { mutableStateOf<SourceStatus?>(null) }
    val currentConnection by rememberUpdatedState(connection)
    val currentError by rememberUpdatedState(sessionError)
    LaunchedEffect(sourceChangedAt) {
        val changedAt = sourceChangedAt ?: return@LaunchedEffect
        if (System.currentTimeMillis() - changedAt > 8_000) return@LaunchedEffect
        status = SourceStatus.Connecting
        while (System.currentTimeMillis() - changedAt < 8_000) {
            if (currentConnection == LiveConnection.LIVE) { status = SourceStatus.Connected; delay(2_000); status = null; return@LaunchedEffect }
            currentError?.let { status = SourceStatus.Failed("Couldn't connect: $it"); delay(4_000); status = null; return@LaunchedEffect }
            delay(100)
        }
        status = SourceStatus.Failed("Couldn't connect to ${preferences.host}.")
        delay(4_000)
        status = null
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).background(if (connection == LiveConnection.LIVE) HealthyGreen else ActivityAmber, CircleShape))
                        Text(if (connection == LiveConnection.LIVE) "Analyzer connected" else "Analyzer offline", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Panel(stringResource(R.string.appearance), Icons.Outlined.Contrast) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Appearance.entries.forEachIndexed { index, mode ->
                            SegmentedButton(selected = mode == preferences.appearance, onClick = { onAppearance(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, Appearance.entries.size)) {
                                Text(stringResource(when (mode) { Appearance.SYSTEM -> R.string.system; Appearance.LIGHT -> R.string.light; Appearance.DARK -> R.string.dark }))
                            }
                        }
                    }
                }
            }
            item {
                Panel("Units", Icons.Outlined.Straighten) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        DistanceUnit.entries.forEachIndexed { index, unit ->
                            SegmentedButton(selected = unit == preferences.distanceUnit, onClick = { onDistanceUnit(unit) },
                                shape = SegmentedButtonDefaults.itemShape(index, DistanceUnit.entries.size)) {
                                Text(when (unit) { DistanceUnit.IMPERIAL -> "Imperial (mi)"; DistanceUnit.METRIC -> "Metric (km)" })
                            }
                        }
                    }
                }
            }
            item {
                Panel("Analyzer source", Icons.Outlined.Dns) {
                    NavigationRow("Choose source", preferences.host, Icons.Outlined.Hub, onSource)
                    Text("Every screen reads regions, areas, and map defaults from this CoreScope analyzer.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Panel("Connection", Icons.Outlined.SettingsInputAntenna) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val live = connection == LiveConnection.LIVE
                        Box(Modifier.size(32.dp).background((if (live) HealthyGreen else ActivityAmber).copy(alpha = 0.14f), MaterialTheme.shapes.small),
                            contentAlignment = Alignment.Center) {
                            Icon(if (live) Icons.Outlined.CheckCircle else Icons.Outlined.SyncProblem, null, Modifier.size(18.dp), tint = if (live) HealthyGreen else ActivityAmber)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(when (connection) {
                                LiveConnection.LIVE -> "Connected"; LiveConnection.CONNECTING -> "Connecting…"
                                LiveConnection.RECONNECTING -> "Reconnecting…"; LiveConnection.PAUSED -> "Paused"
                            }, style = MaterialTheme.typography.titleSmall)
                            Text(sessionError ?: preferences.host, style = MaterialTheme.typography.labelMedium,
                                color = if (sessionError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationRow("Analyzer diagnostics", "Check connection and features", Icons.Outlined.HealthAndSafety, onDiagnostics)
                }
            }
            item {
                Panel("Storage", Icons.Outlined.Storage) {
                    NavigationRow("Cache & storage", "Review or clear downloaded data", Icons.Outlined.SdStorage, onStorage)
                }
            }
            item { CardRow { NavigationRow("About & how to use", "NodeScope field guide", Icons.Outlined.Info, onAbout) } }
            onSupport?.let { open -> item { CardRow { NavigationRow("Support development", "Buy me a coffee", Icons.Outlined.Favorite, open, external = true) } } }
            if (BuildConfig.DEBUG) item {
                TextButton(onClick = onMapLab) { Icon(Icons.Outlined.Science, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.map_lab)) }
            }
        }
        AnimatedVisibility(status != null, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp)) {
            val shown = status
            val (icon, text, tone) = when (shown) {
                is SourceStatus.Failed -> Triple(Icons.Outlined.ErrorOutline, shown.message, MaterialTheme.colorScheme.error)
                SourceStatus.Connected -> Triple(Icons.Outlined.CheckCircle, "Connected to ${preferences.host}", HealthyGreen)
                else -> Triple(Icons.Outlined.Sync, "Connecting to ${preferences.host}…", MaterialTheme.colorScheme.primary)
            }
            Surface(shape = MaterialTheme.shapes.large, shadowElevation = 6.dp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (shown == SourceStatus.Connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(icon, null, tint = tone)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Panel(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    CardRow {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        content()
    }
}

@Composable
private fun CardRow(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
fun NavigationRow(title: String, value: String, icon: ImageVector, onClick: () -> Unit, external: Boolean = false) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.11f), MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // A link that leaves the app says so instead of implying another screen.
        Icon(if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.Outlined.ChevronRight, if (external) "Opens in your browser" else null,
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

