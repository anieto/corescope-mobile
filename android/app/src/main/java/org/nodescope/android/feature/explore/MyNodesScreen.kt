package org.nodescope.android.feature.explore

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.meshCardColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.StarBorder
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
import org.nodescope.android.core.design.EmptyState
import org.nodescope.android.core.design.SectionLabel
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
    val resources = LocalResources.current
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
                error = resources.getString(R.string.explore_activity_could_not_be_updated_refresh_to_try)
            } finally {
                registration?.let { owned?.lifetime?.removeCancellation(it) }
                if (owned?.lifetime?.isCurrent == false) {
                    rows = emptyMap(); catalog = null
                    error = resources.getString(R.string.explore_activity_data_was_cleared_or_the_source_changed)
                }
                loading = false
            }
        }
    }
    if (showsHelp) AlertDialog(
        onDismissRequest = { showsHelp = false },
        title = { Text(stringResource(R.string.explore_about_activity)) },
        text = { Text(stringResource(R.string.explore_recently_heard_means_the_source_recorded_activity_within)) },
        confirmButton = { TextButton(onClick = { showsHelp = false }) { Text(stringResource(R.string.common_done)) } }
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = meshCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { FavoriteSourceIdentity(host, sourceName) }
                        IconButton(onClick = { showsHelp = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.explore_about_activity_2)) }
                        IconButton(onClick = { force = true; revision++ }, enabled = !loading) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { force = true; revision++ }, enabled = !loading) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
            }
        }
        if (nodes.isEmpty() && observers.isEmpty()) item {
            EmptyState(Icons.Outlined.StarBorder, stringResource(R.string.explore_no_favorites_yet), stringResource(R.string.explore_add_nodes_in_favorites_or_save_observers_from))
        }
        if (nodes.isNotEmpty()) item { SectionLabel(stringResource(R.string.explore_nodes)) }
        items(nodes, key = { it.key }) { item ->
            val row = rows[item.entityId.lowercase()]
            val snapshot = row?.snapshot
            val activity = snapshot?.let {
                MyNodesActivity.assess(runCatching { Instant.parse(it.node.lastSeen).toEpochMilli() }.getOrNull(), null,
                it.fetchedAt, now, row.state in listOf(MyNodesBaseState.FETCHED, MyNodesBaseState.CACHED))
            }
            Card(onClick = { onNode(item.entityId) }, modifier = Modifier.fillMaxWidth(),
            colors = meshCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityItemHeading(snapshot?.node?.name ?: item.title) { KindIcon(SavedKind.NODE, snapshot?.node ?: item.node) }
                    Text(when (activity?.state) {
                        MyNodesActivityState.RECENTLY_HEARD -> stringResource(R.string.explore_recently_heard)
                        MyNodesActivityState.NOT_RECENTLY_HEARD -> stringResource(R.string.explore_not_recently_heard)
                        else -> stringResource(R.string.explore_activity_unknown_2)
                    }, style = MaterialTheme.typography.bodyMedium,
                        color = if (activity?.state == MyNodesActivityState.RECENTLY_HEARD) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    activity?.activityAt?.let { Text(stringResource(R.string.explore_last_heard, relativeActivityDate(it, now)), style = MaterialTheme.typography.bodyMedium) }
                    snapshot?.let {
                        Text(stringResource(R.string.explore_value_2, if (row.state == MyNodesBaseState.CACHED) stringResource(R.string.explore_saved_data_checked) else stringResource(R.string.explore_checked_3), relativeActivityDate(it.fetchedAt, now)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!it.isReusable(now)) Text(stringResource(R.string.explore_cached_refresh_needed), style = MaterialTheme.typography.bodySmall)
                    }
                    if (row?.state !in listOf(MyNodesBaseState.FETCHED, MyNodesBaseState.CACHED)) Text(when (row?.state) {
                        MyNodesBaseState.FETCHED -> stringResource(R.string.explore_updated)
                        MyNodesBaseState.CACHED -> stringResource(R.string.explore_cached)
                        MyNodesBaseState.UNAVAILABLE -> stringResource(R.string.explore_unavailable_on_this_source)
                        MyNodesBaseState.DEFERRED -> stringResource(R.string.explore_not_checked_yet_use_load_more)
                        MyNodesBaseState.FAILED -> stringResource(R.string.explore_update_failed_retry_with_refresh)
                        MyNodesBaseState.RATE_LIMITED -> stringResource(R.string.explore_server_rate_limit_try_later)
                        null -> stringResource(R.string.explore_not_checked_yet)
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!loading && nodes.any { rows[it.entityId.lowercase()]?.state in listOf(null, MyNodesBaseState.DEFERRED) }) item {
            OutlinedButton(onClick = { force = false; revision++ }, enabled = !loading) { Text(stringResource(R.string.explore_load_more_2)) }
        }
        if (observers.isNotEmpty()) item { SectionLabel(stringResource(R.string.explore_favorite_observers)) }
        items(observers, key = { it.key }) { item ->
            val record = catalog?.snapshot?.observers?.firstOrNull { it.id == item.entityId }
            Card(onClick = { onObserver(item.entityId, record?.name ?: item.title) }, modifier = Modifier.fillMaxWidth(),
            colors = meshCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityItemHeading(record?.name ?: item.title) { KindIcon(SavedKind.OBSERVER, null) }
                    val seen = record?.lastSeen?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    Text(seen?.let { stringResource(R.string.explore_last_heard_2, relativeActivityDate(it, now)) } ?: stringResource(R.string.explore_activity_unknown), style = MaterialTheme.typography.bodyMedium)
                    catalog?.snapshot?.let { Text(stringResource(R.string.explore_checked_2, relativeActivityDate(it.fetchedAt, now)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (catalog?.state != MyNodesBaseState.FETCHED) Text(when (catalog?.state) {
                        MyNodesBaseState.FETCHED -> stringResource(R.string.explore_updated_catalog)
                        MyNodesBaseState.CACHED -> stringResource(R.string.explore_cached_catalog)
                        else -> stringResource(R.string.explore_catalog_update_unavailable)
                    }, style = MaterialTheme.typography.bodySmall)
                    if (catalog?.snapshot?.completeness != MyNodesCatalogCompleteness.COMPLETE) Text(stringResource(R.string.explore_observer_list_may_be_incomplete), style = MaterialTheme.typography.bodySmall)
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

/** Same icon treatment as saved rows on Favorites and Explore. */
@Composable
private fun ActivityItemHeading(title: String, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon()
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
