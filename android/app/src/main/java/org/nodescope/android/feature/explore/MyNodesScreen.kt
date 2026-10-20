package org.nodescope.android.feature.explore

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import org.nodescope.android.feature.nodes.roleColor
import org.nodescope.android.feature.nodes.roleIcon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.nodescope.android.core.design.rememberNow
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*
import java.time.Instant
import java.text.DateFormat
import java.util.Date

/** Activity only; visit comparisons are committed when the Changes presentation is connected. */
@Composable
fun MyNodesScreen(host: String, library: NodeLibrary, acquire: () -> MyNodesRepositories,
                  onNode: (String) -> Unit, onObserver: (String, String) -> Unit, sourceName: String? = null) {
    val nodes = library.favorites.filter { it.kind == SavedKind.NODE }
    val observers = library.favorites.filter { it.kind == SavedKind.OBSERVER }
    var rows by remember(host) { mutableStateOf<Map<String, MyNodesBaseRow>>(emptyMap()) }
    var catalog by remember(host) { mutableStateOf<MyNodesObserverCatalogRow?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAcquire by rememberUpdatedState(acquire)
    val now = rememberNow()
    var showsHelp by remember { mutableStateOf(false) }
    LaunchedEffect(host, nodes.map { it.entityId }, observers.map { it.entityId }, revision, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (nodes.isEmpty() && observers.isEmpty()) { rows = emptyMap(); catalog = null; return@repeatOnLifecycle }
            loading = true
            error = null
            val refresh = force
            force = false
            var owned: MyNodesRepositories? = null
            var registration: java.util.UUID? = null
            try {
                val repositories = currentAcquire()
                owned = repositories
                val job = currentCoroutineContext().job
                registration = repositories.lifetime.registerCancellation { job.cancel() }
                val keys = nodes.map { it.entityId }
                rows = repositories.base.cached(keys).mapValues { (key, snapshot) ->
                    MyNodesBaseRow(key, snapshot, MyNodesBaseState.CACHED)
                }
                catalog = repositories.base.observers()
                val updates = Channel<MyNodesBaseRow>(Channel.UNLIMITED)
                val collector = launch {
                    for (row in updates) {
                        repositories.lifetime.check()
                        rows = rows + (row.publicKey to row)
                    }
                }
                try {
                    val result = repositories.base.load(keys, refresh) { updates.trySend(it) }
                    updates.close()
                    collector.join()
                    repositories.lifetime.check()
                    rows = result.associateBy { it.publicKey }
                    catalog = repositories.base.observers()
                } finally { updates.close(); collector.cancel() }
                loading = false
                // Keep the invalidation subscription while this screen is visible.
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Activity could not be updated. Refresh to try again."
            } finally {
                registration?.let { owned?.lifetime?.removeCancellation(it) }
                if (owned?.lifetime?.isCurrent == false) {
                    rows = emptyMap(); catalog = null
                    error = "Activity data was cleared or the source changed. Refresh to continue."
                }
                loading = false
            }
        }
    }
    if (showsHelp) AlertDialog(
        onDismissRequest = { showsHelp = false },
        title = { Text("About activity") },
        text = { Text("Recently heard means the source recorded activity within 15 minutes. Silence does not mean a node is offline.\n\nLast heard is the latest reported activity. Checked is when the app retrieved that information. Saved data keeps its original check time.\n\nMissing observers have unknown activity. Some sources return an incomplete observer list.") },
        confirmButton = { TextButton(onClick = { showsHelp = false }) { Text("Done") } }
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { FavoriteSourceIdentity(host, sourceName) }
                        IconButton(onClick = { showsHelp = true }) { Icon(Icons.Outlined.Info, "About activity") }
                        IconButton(onClick = { force = true; revision++ }, enabled = !loading) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it) }
                }
            }
        }
        if (nodes.isEmpty() && observers.isEmpty()) item {
            Text("No favorites yet. Add nodes in Favorites or save observers from their detail pages to follow them here.")
        }
        if (nodes.isNotEmpty()) item { Text("Nodes", style = MaterialTheme.typography.titleMedium) }
        items(nodes, key = { it.key }) { item ->
            val row = rows[item.entityId.lowercase()]
            val snapshot = row?.snapshot
            val activity = snapshot?.let {
                MyNodesActivity.assess(runCatching { Instant.parse(it.node.lastSeen).toEpochMilli() }.getOrNull(), null,
                it.fetchedAt, now, row.state in listOf(MyNodesBaseState.FETCHED, MyNodesBaseState.CACHED))
            }
            Card(onClick = { onNode(item.entityId) }, modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityItemHeading(snapshot?.node?.name ?: item.title, snapshot?.node?.role ?: item.node?.role ?: "")
                    Text(when (activity?.state) {
                        MyNodesActivityState.RECENTLY_HEARD -> "Recently heard"
                        MyNodesActivityState.NOT_RECENTLY_HEARD -> "Not recently heard"
                        else -> "Activity unknown"
                    }, style = MaterialTheme.typography.bodyMedium,
                        color = if (activity?.state == MyNodesActivityState.RECENTLY_HEARD) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    activity?.activityAt?.let { Text("Last heard ${relativeActivityDate(it, now)}", style = MaterialTheme.typography.bodyMedium) }
                    snapshot?.let {
                        Text("${if (row.state == MyNodesBaseState.CACHED) "Saved data · checked" else "Checked"} ${relativeActivityDate(it.fetchedAt, now)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!it.isReusable(now)) Text("Cached · refresh needed", style = MaterialTheme.typography.bodySmall)
                    }
                    if (row?.state !in listOf(MyNodesBaseState.FETCHED, MyNodesBaseState.CACHED)) Text(when (row?.state) {
                        MyNodesBaseState.FETCHED -> "Updated"
                        MyNodesBaseState.CACHED -> "Cached"
                        MyNodesBaseState.UNAVAILABLE -> "Unavailable on this source"
                        MyNodesBaseState.DEFERRED -> "Not checked yet · use Load More"
                        MyNodesBaseState.FAILED -> "Update failed · retry with Refresh"
                        MyNodesBaseState.RATE_LIMITED -> "Server rate limit · try later"
                        null -> "Not checked yet"
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!loading && nodes.any { rows[it.entityId.lowercase()]?.state in listOf(null, MyNodesBaseState.DEFERRED) }) item {
            OutlinedButton(onClick = { force = false; revision++ }, enabled = !loading) { Text("Load More") }
        }
        if (observers.isNotEmpty()) item {
            Text("Favorite observers", style = MaterialTheme.typography.titleMedium)
        }
        items(observers, key = { it.key }) { item ->
            val record = catalog?.snapshot?.observers?.firstOrNull { it.id == item.entityId }
            Card(onClick = { onObserver(item.entityId, record?.name ?: item.title) }, modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityItemHeading(record?.name ?: item.title, "observer")
                    val seen = record?.lastSeen?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    Text(seen?.let { "Last heard ${relativeActivityDate(it, now)}" } ?: "Activity unknown", style = MaterialTheme.typography.bodyMedium)
                    catalog?.snapshot?.let { Text("Checked ${relativeActivityDate(it.fetchedAt, now)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (catalog?.state != MyNodesBaseState.FETCHED) Text(when (catalog?.state) {
                        MyNodesBaseState.FETCHED -> "Updated catalog"
                        MyNodesBaseState.CACHED -> "Cached catalog"
                        else -> "Catalog update unavailable"
                    }, style = MaterialTheme.typography.bodySmall)
                    if (catalog?.snapshot?.completeness != MyNodesCatalogCompleteness.COMPLETE) Text("Observer list may be incomplete", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun displayDate(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))


@Composable
internal fun FavoriteSourceIdentity(host: String, sourceName: String?) {
    val name = sourceName?.trim()?.takeIf { it.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name ?: host, style = MaterialTheme.typography.titleMedium)
        if (name != null && !name.equals(host, ignoreCase = true)) {
            Text(host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActivityItemHeading(title: String, role: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (role == "observer") Icons.Outlined.Sensors else roleIcon(role), null,
            Modifier.size(42.dp).background(if (role == "observer") MaterialTheme.colorScheme.primaryContainer else roleColor(role), CircleShape).padding(10.dp),
            tint = if (role == "observer") MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF14243A))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun relativeActivityDate(value: Long, now: Long): String {
    if (value > now) return displayDate(value)
    val minutes = (now - value) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 1440 -> "${minutes / 60} hr ago"
        else -> displayDate(value)
    }
}
