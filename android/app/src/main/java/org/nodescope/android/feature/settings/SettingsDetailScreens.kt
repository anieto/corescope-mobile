package org.nodescope.android.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.activity.compose.LocalActivity
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
private fun DiagnosticLevel.spoken() = when (this) {
    DiagnosticLevel.SUCCESS -> "available"; DiagnosticLevel.WARNING -> "limited"; DiagnosticLevel.FAILURE -> "unavailable"
}
private fun AnalyzerCapability.icon(): ImageVector = when (this) {
    AnalyzerCapability.MAP_CONFIGURATION -> Icons.Outlined.Map
    AnalyzerCapability.NODES -> Icons.Outlined.Hub
    AnalyzerCapability.CHANNELS -> Icons.Outlined.Forum
    AnalyzerCapability.OBSERVERS -> Icons.Outlined.Sensors
    AnalyzerCapability.REGIONS -> Icons.Outlined.Public
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
            Section("Connection") {
                Row { Text("Analyzer", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(host, textAlign = TextAlign.End) }
                val current = report
                if (current != null) {
                    DiagnosticRow("HTTPS connection", current.connectionDetail, Icons.Outlined.Lock, current.connectionLevel)
                    DiagnosticRow("API compatibility", current.compatibilityDetail, Icons.Outlined.Verified, current.compatibilityLevel)
                    Row {
                        Text("Last checked", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(relativeTime(java.time.Instant.ofEpochMilli(current.checkedAt), now))
                    }
                } else if (running) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Checking analyzer…")
                }
            }
        }
        item {
            Section("Live updates", footer = "The live stream uses a separate WebSocket connection, so it can reconnect independently of the read-only API.") {
                DiagnosticRow("Live packet stream", when (connection) {
                    LiveConnection.LIVE -> "Connected"
                    else -> "Connecting or temporarily unavailable"
                }, Icons.Outlined.SettingsInputAntenna, if (connection == LiveConnection.LIVE) DiagnosticLevel.SUCCESS else DiagnosticLevel.WARNING)
            }
        }
        item {
            Section("Capabilities") {
                val current = report
                when {
                    current != null -> current.capabilities.forEach { check -> DiagnosticRow(check.capability.title, check.detail, check.capability.icon(), check.level) }
                    running -> AnalyzerCapability.entries.forEach { capability ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(capability.icon(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(capability.title, Modifier.weight(1f))
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    else -> Text("Run diagnostics to check this analyzer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Section(null, footer = "Diagnostics make read-only requests to the selected analyzer. No settings or analyzer data are changed.") {
                FilledTonalButton(onClick = ::run, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp))
                    Text(if (running) "Running diagnostics…" else "Run diagnostics again")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(title: String, detail: String, icon: ImageVector, level: DiagnosticLevel) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$title, ${level.spoken()}. $detail" },
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
    LaunchedEffect(Unit) { usage = storage.usage(); busy = false }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Clear cached data?") },
        text = { Text("Downloaded map tiles and temporary files will be removed. Favorites, monitored channels and keys, recent items, appearance, and analyzer settings will be kept.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                busy = true
                scope.launch {
                    error = storage.clear()?.let { "Map tiles couldn't be fully cleared: $it" }
                    onCleared()
                    usage = storage.usage()
                    clearedAt = System.currentTimeMillis()
                    busy = false
                }
            }) { Text("Clear cached data", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("Cache usage") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloaded data", Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(Formatter.formatShortFileSize(context, usage?.bytes ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row { Text("Cached files", Modifier.weight(1f)); Text("${usage?.files ?: 0}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                clearedAt?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = HealthyGreen); Text("Cleared ${relativeTime(java.time.Instant.ofEpochMilli(it), now)}")
                } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            Section("What is cached", footer = "Favorites, monitored-channel keys, recent items, app preferences, and your selected analyzer are stored separately and are never removed here.") {
                ContentRow("Map tiles", "Basemap tiles, fonts and styles downloaded while you use the map", Icons.Outlined.Map)
                ContentRow("Temporary files", "Short-lived data the app and system keep to speed things up", Icons.Outlined.Autorenew)
                ContentRow("Analyzer responses", "Kept in memory while the app is open and refreshed when you clear", Icons.Outlined.Dns)
            }
        }
        item {
            Section(null, footer = "Current content can remain visible while NodeScope fetches fresh data. The map re-downloads tiles as needed.") {
                Button(onClick = { confirm = true }, enabled = !busy && (usage?.bytes ?: 0) > 0, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Clear cached data") }
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
                Text("NodeScope", style = MaterialTheme.typography.titleLarge)
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Section("What this is") {
                Text("NodeScope is an unofficial, read-only companion app for CoreScope, the mesh network analyzer used by MeshTexas and other MeshCore communities. It talks directly to a CoreScope server's public API to show the same live nodes, packet traffic, channel messages, and observer data you'd see on the CoreScope web dashboard — just in a native app.")
                Text("This app has no backend of its own. Everything shown here comes from whatever analyzer host is configured in Settings, and it's not affiliated with the CoreScope or MeshCore projects.")
            }
        }
        item {
            Section("How to use") {
                HowTo(Icons.Outlined.Map, "Map", "Every known node, color-coded by role. Live packet traffic animates as it hops between nodes. Tap a node for its details.")
                HowTo(Icons.Outlined.Explore, "Explore", "Network at a glance, saved nodes, recently viewed items, and the live packet feed.")
                HowTo(Icons.Outlined.Tag, "Channels", "Group channel traffic as a chat feed. Tap View packet on a message to see how it reached the analyzer and replay it on the map.")
                HowTo(Icons.Outlined.Sensors, "Observers", "The physical devices feeding this analyzer — hardware, firmware, and recent signal stats.")
                HowTo(Icons.Outlined.FilterList, "Region filter", "Available on Map, Channels, Observers, and Live Packets. Narrows everything to one region and zooms the map there automatically.")
            }
        }
        item {
            Section("Project links") {
                LinkRow(Icons.Outlined.Inventory2, "CoreScope project") { uri.openUri("https://github.com/Kpa-clawbot/CoreScope") }
                LinkRow(Icons.Outlined.Code, "NodeScope on GitHub") { uri.openUri("https://github.com/anieto/corescope-mobile") }
            }
        }
        item {
            Section("Map data") {
                Text("Basemaps © CARTO, map data © OpenStreetMap contributors, rendered with MapLibre. Region locations use OurAirports data (public domain).",
                    style = MaterialTheme.typography.bodyMedium)
                LinkRow(Icons.Outlined.Map, "OpenStreetMap copyright") { uri.openUri("https://www.openstreetmap.org/copyright") }
                LinkRow(Icons.Outlined.Layers, "CARTO attributions") { uri.openUri("https://carto.com/attributions") }
            }
        }
        item {
            Section("License") {
                Text("NodeScope · Copyright © 2026 BetweenThieves Development", style = MaterialTheme.typography.titleSmall)
                Text("Free and open-source software licensed under GNU GPL version 3 with an application-store distribution exception. You may share and modify it under those terms. NodeScope comes with absolutely no warranty.",
                    style = MaterialTheme.typography.bodyMedium)
                LinkRow(Icons.Outlined.Description, "View GPLv3 license") { uri.openUri("https://www.gnu.org/licenses/gpl-3.0.html") }
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
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Opens in browser", Modifier.size(18.dp))
    }
}

// endregion

@Composable
private fun Section(title: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let { SectionLabel(it, Modifier.padding(horizontal = 4.dp)) }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
        footer?.let { Text(it, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// region Support development

private fun Tip.icon(): ImageVector = when (this) {
    Tip.COFFEE -> Icons.Outlined.LocalCafe
    Tip.LUNCH -> Icons.Outlined.LunchDining
    Tip.DINNER -> Icons.Outlined.Restaurant
}

/** iOS `SupportDevelopmentScreen`: optional one-time tips, through Google Play Billing. */
@Composable
fun SupportDevelopmentScreen(model: TipViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    LaunchedEffect(Unit) { model.load() }
    if (state.thankYou) AlertDialog(onDismissRequest = model::dismissThankYou, title = { Text("Thank you!") },
        text = { Text("Thank you for supporting NodeScope!") }, confirmButton = { TextButton(onClick = model::dismissThankYou) { Text("Done") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Favorite, null, Modifier.size(48.dp), tint = ActivityAmber)
                Text("Enjoying NodeScope?", style = MaterialTheme.typography.titleLarge)
                Text("If NodeScope has been useful, you can leave an optional tip to support its continued development.", textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Section("Leave a tip", footer = "Tips are one-time purchases. They don't unlock features or include goods, services, or membership benefits.") {
                if (state.loading && state.products.isEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Loading tips…")
                }
                state.products.forEach { product ->
                    val purchasing = state.purchasing == product.tip
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .clickable(enabled = state.purchasing == null && activity != null, onClickLabel = "Make a one-time tip purchase") {
                            activity?.let { model.purchase(it, product) }
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(product.tip.icon(), null, tint = MaterialTheme.colorScheme.primary)
                        Text(product.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (purchasing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(product.price, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        state.error?.let { message ->
            item {
                Section(null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Warning, null, tint = ActivityAmber); Text(message)
                    }
                    if (state.products.isEmpty() && !state.loading) TextButton(onClick = { model.load(force = true) }) { Text("Try again") }
                }
            }
        }
    }
}

// endregion
