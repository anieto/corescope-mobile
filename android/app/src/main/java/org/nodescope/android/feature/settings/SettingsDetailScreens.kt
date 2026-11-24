package org.nodescope.android.feature.settings

import androidx.compose.ui.res.stringResource
import org.nodescope.android.core.design.withDescription
import org.nodescope.android.core.design.meshCardColors
import androidx.compose.foundation.clickable
import org.nodescope.android.core.design.rememberCopyAction
import org.nodescope.android.app.AppIdentity
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nodescope.android.BuildConfig
import org.nodescope.android.R
import org.nodescope.android.core.design.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.CacheStorage
import org.nodescope.android.core.storage.CacheUsage

// region Diagnostics

private fun DiagnosticLevel.icon(): ImageVector = when (this) {
    DiagnosticLevel.SUCCESS -> Icons.Outlined.CheckCircle
    DiagnosticLevel.WARNING -> Icons.Outlined.Warning
    DiagnosticLevel.FAILURE -> Icons.Outlined.Cancel
}
@Composable
private fun DiagnosticLevel.tone(): Color = when (this) {
    DiagnosticLevel.SUCCESS -> HealthyGreen
    DiagnosticLevel.WARNING -> ActivityAmber
    DiagnosticLevel.FAILURE -> MaterialTheme.colorScheme.error
}
@Composable private fun DiagnosticLevel.spoken() = stringResource(when (this) {
    DiagnosticLevel.SUCCESS -> R.string.settings_diagnostics_spoken_available
    DiagnosticLevel.WARNING -> R.string.settings_diagnostics_spoken_limited
    DiagnosticLevel.FAILURE -> R.string.settings_diagnostics_spoken_unavailable
})
private fun AnalyzerCapability.icon(): ImageVector = when (this) {
    AnalyzerCapability.MAP_CONFIGURATION -> Icons.Outlined.Map
    AnalyzerCapability.NODES -> Icons.Outlined.Hub
    AnalyzerCapability.CHANNELS -> Icons.Outlined.Forum
    AnalyzerCapability.OBSERVERS -> Icons.Outlined.Sensors
    AnalyzerCapability.REGIONS -> Icons.Outlined.Public
    AnalyzerCapability.NEIGHBORS -> Icons.Outlined.Hub
    AnalyzerCapability.NODE_ANALYTICS -> Icons.Outlined.QueryStats
    AnalyzerCapability.OBSERVER_ACTIVITY -> Icons.Outlined.MonitorHeart
    AnalyzerCapability.OBSERVER_TELEMETRY -> Icons.Outlined.Speed
    AnalyzerCapability.CHANNEL_MESSAGES -> Icons.Outlined.ChatBubbleOutline
}

/** iOS `AnalyzerDiagnosticsScreen`: read-only probes of the selected analyzer. */
@Composable
fun DiagnosticsScreen(diagnostics: AnalyzerDiagnostics, host: String, connection: LiveConnection) {
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    fun run() {
        if (running) return
        running = true
        scope.launch { try { report = diagnostics.run(host) } finally { running = false } }
    }
    LaunchedEffect(host) { run() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section(stringResource(R.string.settings_connection)) {
                Row { Text(stringResource(R.string.settings_analyzer), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(host, textAlign = TextAlign.End) }
                val current = report
                if (current != null) {
                    Row { Text(stringResource(R.string.settings_server_type), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(current.backendType, textAlign = TextAlign.End) }
                    current.serverVersion?.let { version ->
                        Row { Text(stringResource(R.string.settings_server_version_last_reported), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(version, textAlign = TextAlign.End) }
                    }
                    DiagnosticRow(stringResource(R.string.settings_https_connection), current.connectionDetail.asString(), Icons.Outlined.Lock, current.connectionLevel)
                    DiagnosticRow(stringResource(R.string.settings_api_compatibility), current.compatibilityDetail.asString(), Icons.Outlined.Verified, current.compatibilityLevel)
                    Row {
                        Text(stringResource(R.string.settings_last_checked), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(relativeTime(java.time.Instant.ofEpochMilli(current.checkedAt), now))
                    }
                } else if (running) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(stringResource(R.string.settings_checking_analyzer))
                }
            }
        }
        item {
            Section(stringResource(R.string.settings_live_updates), footer = stringResource(R.string.settings_the_live_stream_uses_a_separate_websocket_connection)) {
                DiagnosticRow(stringResource(R.string.settings_live_packet_stream), when (connection) {
                    LiveConnection.LIVE -> stringResource(R.string.settings_connected)
                    else -> stringResource(R.string.settings_connecting_or_temporarily_unavailable)
                }, Icons.Outlined.SettingsInputAntenna, if (connection == LiveConnection.LIVE) DiagnosticLevel.SUCCESS else DiagnosticLevel.WARNING)
            }
        }
        item {
            Section(stringResource(R.string.settings_capabilities)) {
                val current = report
                when {
                    current != null -> current.capabilities.forEach { check -> DiagnosticRow(stringResource(check.capability.titleRes), check.detail.asString(), check.capability.icon(), check.level) }
                    running -> (if (LocalBeaconSource.current) AnalyzerCapability.entries else AnalyzerCapability.coreScopeProbed).forEach { capability ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(capability.icon(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(capability.titleRes), Modifier.weight(1f))
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    else -> Text(stringResource(R.string.settings_run_diagnostics_to_check_this_analyzer), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Section(null, footer = stringResource(R.string.settings_diagnostics_make_read_only_requests_to_the_selected)) {
                FilledTonalButton(onClick = ::run, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp))
                    Text(if (running) stringResource(R.string.settings_running_diagnostics) else stringResource(R.string.settings_run_diagnostics_again))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(title: String, detail: String, icon: ImageVector, level: DiagnosticLevel) {
    Row(Modifier.fillMaxWidth().withDescription(stringResource(R.string.settings_value, title, level.spoken(), detail), mergeDescendants = true) { description -> contentDescription = description },
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(level.icon(), null, tint = level.tone())
    }
}

// endregion
// region Storage

/** iOS `StorageSettingsScreen`, measuring what Android actually caches (map tiles and the cache directory). */
@Composable
fun StorageScreen(storage: CacheStorage, onCleared: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    var usage by remember { mutableStateOf<CacheUsage?>(null) }
    var busy by remember { mutableStateOf(true) }
    var clearedAt by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var savedPlans by remember { mutableStateOf<org.nodescope.android.feature.candidatesites.SavedPlansUsage?>(null) }
    LaunchedEffect(Unit) {
        usage = storage.usage(); busy = false
        savedPlans = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            org.nodescope.android.feature.candidatesites.SavedPlansUsage.application(context.filesDir)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.settings_clear_cached_data)) },
        text = { Text(stringResource(R.string.settings_downloaded_map_tiles_temporary_files_and_favorites_activity)) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                busy = true
                scope.launch {
                    error = storage.clear()
                    onCleared()
                    usage = storage.usage()
                    clearedAt = if (error == null) System.currentTimeMillis() else null
                    busy = false
                }
            }) { Text(stringResource(R.string.settings_clear_cached_data_2), color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section(stringResource(R.string.settings_cache_usage)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_downloaded_data), Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(Formatter.formatShortFileSize(context, usage?.bytes ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row { Text(stringResource(R.string.settings_cached_files), Modifier.weight(1f)); Text(stringResource(R.string.settings_value_2, usage?.files ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                clearedAt?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = HealthyGreen); Text(stringResource(R.string.settings_cleared, relativeTime(java.time.Instant.ofEpochMilli(it), now)))
                } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            Section(stringResource(R.string.settings_what_is_cached), footer = stringResource(R.string.settings_favorites_monitored_channel_keys_recent_items_app_preferences)) {
                ContentRow(stringResource(R.string.settings_map_tiles), stringResource(R.string.settings_basemap_tiles_fonts_and_styles_downloaded_while_you), Icons.Outlined.Map)
                ContentRow(stringResource(R.string.settings_temporary_files), stringResource(R.string.settings_short_lived_data_the_app_and_system_keep), Icons.Outlined.Autorenew)
                ContentRow(stringResource(R.string.settings_favorites_activity_snapshots), stringResource(R.string.settings_saved_comparisons_reset_when_you_clear_cached_data), Icons.Outlined.Autorenew)
                ContentRow(stringResource(R.string.settings_analyzer_responses), stringResource(R.string.settings_kept_in_memory_while_the_app_is_open), Icons.Outlined.Dns)
            }
        }
        item {
            val plans = savedPlans
            val limit = org.nodescope.android.feature.candidatesites.DeploymentPlanStore.MAXIMUM_RESULT_STORAGE
            val backups = (plans?.backupBytes ?: 0) > 0
            Section(stringResource(R.string.settings_saved_plans), footer = stringResource(if (backups) R.string.settings_saved_plans_backup_footer_with_copies else R.string.settings_saved_plans_backup_footer)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_saved_results), Modifier.weight(1f))
                    if (plans == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.settings_of, Formatter.formatShortFileSize(context, plans.resultBytes), Formatter.formatShortFileSize(context, limit)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (plans != null && backups) Row {
                    Text(stringResource(R.string.settings_upgrade_backups), Modifier.weight(1f))
                    Text(Formatter.formatShortFileSize(context, plans.backupBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Section(null, footer = stringResource(R.string.settings_current_content_can_remain_visible_while_nodescope_fetches)) {
                Button(onClick = { confirm = true }, enabled = !busy && (usage?.bytes ?: 0) > 0, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.settings_clear_cached_data_2)) }
            }
        }
    }
}

@Composable
private fun ContentRow(title: String, detail: String, icon: ImageVector) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column { Text(title, style = MaterialTheme.typography.bodyLarge); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// endregion
// region About

/** iOS `AboutScreen`, with an Android section crediting the map and airport data. */
@Composable
fun AboutScreen() {
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.drawable.app_icon), null, Modifier.size(72.dp).clip(MaterialTheme.shapes.medium))
                Text(stringResource(R.string.settings_nodescope), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Section(stringResource(R.string.settings_what_this_is)) {
                Text(stringResource(R.string.settings_nodescope_is_an_unofficial_read_only_companion_app))
                Text(stringResource(R.string.settings_about_data_sources))
            }
        }
        item {
            Section(stringResource(R.string.settings_how_to_use)) {
                HowTo(Icons.Outlined.Map, stringResource(R.string.settings_map), stringResource(R.string.settings_every_known_node_color_coded_by_role_live))
                HowTo(Icons.Outlined.Explore, stringResource(R.string.settings_explore), stringResource(R.string.settings_network_at_a_glance_saved_nodes_recently_viewed))
                HowTo(Icons.Outlined.Tag, stringResource(R.string.settings_channels), stringResource(R.string.settings_group_channel_traffic_as_a_chat_feed_tap))
                HowTo(Icons.Outlined.Sensors, stringResource(R.string.settings_observers), stringResource(R.string.settings_the_physical_devices_feeding_this_analyzer_hardware_firmware))
                HowTo(Icons.Outlined.FilterList, stringResource(R.string.settings_region_filter), stringResource(R.string.settings_available_on_map_channels_observers_and_live_packets))
            }
        }
        item {
            Section(stringResource(R.string.settings_project_links)) {
                LinkRow(Icons.Outlined.Inventory2, stringResource(R.string.settings_corescope_project)) { uri.openUri("https://github.com/Kpa-clawbot/CoreScope") }
                LinkRow(Icons.Outlined.Code, stringResource(R.string.settings_nodescope_on_github)) { uri.openUri("https://github.com/anieto/corescope-mobile") }
            }
        }
        item {
            // What the map provider checks; a support request for a map that won't load needs this.
            val context = LocalContext.current
            val copy = rememberCopyAction()
            val certificate = remember { runCatching { AppIdentity.fingerprint(AppIdentity.signingSha1(context)) }.getOrDefault("unavailable") }
            Section(stringResource(R.string.settings_app_identity)) {
                Text(stringResource(R.string.settings_if_the_map_won_t_load_send_these), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IdentityRow(stringResource(R.string.settings_package), context.packageName) { copy(context.getString(R.string.settings_package_2), context.packageName, false) }
                IdentityRow(stringResource(R.string.settings_signing_certificate_sha_1), certificate) { copy(context.getString(R.string.settings_signing_certificate_sha_1_2), certificate, false) }
            }
        }
        item {
            Section(stringResource(R.string.settings_map_data)) {
                Text(stringResource(R.string.settings_basemaps_carto_map_data_openstreetmap_contributors_rendered_with),
                    style = MaterialTheme.typography.bodyMedium)
                LinkRow(Icons.Outlined.Map, stringResource(R.string.settings_openstreetmap_copyright)) { uri.openUri("https://www.openstreetmap.org/copyright") }
                LinkRow(Icons.Outlined.Layers, stringResource(R.string.settings_carto_attributions)) { uri.openUri("https://carto.com/attributions") }
            }
        }
        item {
            Section(stringResource(R.string.settings_font)) {
                Text(stringResource(R.string.settings_headings_and_values_use_monaspace_neon_by_github),
                    style = MaterialTheme.typography.bodyMedium)
                LinkRow(Icons.Outlined.TextFields, stringResource(R.string.settings_monaspace)) { uri.openUri("https://github.com/githubnext/monaspace") }
            }
        }
        item {
            Section(stringResource(R.string.settings_license)) {
                Text(stringResource(R.string.settings_nodescope_copyright_2026_betweenthieves_development), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_free_and_open_source_software_licensed_under_gnu),
                    style = MaterialTheme.typography.bodyMedium)
                LinkRow(Icons.Outlined.Description, stringResource(R.string.settings_view_gplv3_license)) { uri.openUri("https://www.gnu.org/licenses/gpl-3.0.html") }
            }
        }
    }
}

@Composable
private fun HowTo(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.settings_opens_in_browser), Modifier.size(18.dp))
    }
}

// endregion

@Composable
private fun Section(title: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let { SectionLabel(it, Modifier.padding(horizontal = 4.dp)) }
        Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
        footer?.let { Text(it, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun IdentityRow(label: String, value: String, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.settings_copy, label), onClick = onCopy).padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = MonaspaceNeon)
    }
}
