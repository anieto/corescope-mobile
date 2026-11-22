package org.nodescope.android.feature.explore

import org.nodescope.android.core.design.UiText

import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.meshCardColors
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*
import java.text.DateFormat
import java.time.Instant
import java.util.Date

internal data class FavoriteChangeRow(
    val entity: MyNodesEntity, val title: String, val metric: MyNodesMetric?,
    val result: MyNodesReviewResult, val previousAt: Long?, val fresh: Boolean,
) {
    val id get() = entity.id
    val revision get() = metric?.fetchedAt
    val message: String get() = when (result.state) {
        MyNodesReviewResult.State.FIRST_VISIT -> "First visit · starting comparison history"
        MyNodesReviewResult.State.EXPIRED_BASELINE -> "Previous review is over 7 days old · starting again"
        MyNodesReviewResult.State.UNAVAILABLE -> "Comparison unavailable"
        MyNodesReviewResult.State.UNCHANGED -> "No activity change"
        MyNodesReviewResult.State.CHANGED -> when (result.event?.change) {
            "newlyActive", "observerActive" -> "Recently heard again"
            "noLongerRecentlyHeard", "observerInactive" -> "No longer recently heard"
            else -> "Activity changed"
        }
    }
}

internal class FavoriteChangesModel {
    var rows by mutableStateOf<List<FavoriteChangeRow>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiText?>(null)
        private set
    private val visits = Mutex()
    private var session: MyNodesVisitSession? = null
    private var commands: Channel<Boolean>? = null

    fun refresh(force: Boolean) { commands?.trySend(force) }

    suspend fun review(row: FavoriteChangeRow) {
        val visit = session ?: return
        val metric = row.metric ?: return
        if (!row.fresh || rows.none { it.id == row.id && it.revision == row.revision }) return
        try {
            visit.reviewed(row.entity, metric, true, System.currentTimeMillis())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = UiText.Resource(R.string.explore_changes_error_review_history_could_not_be_saved_refresh_to_try_again) }
    }

    suspend fun run(store: MyNodesSnapshotStore, acquire: () -> MyNodesRepositories, items: List<SavedItem>) =
        visits.withLock { runVisit(store, acquire, items) }

    private suspend fun runVisit(store: MyNodesSnapshotStore, acquire: () -> MyNodesRepositories, items: List<SavedItem>) {
        rows = emptyList()
        error = null
        val requests = Channel<Boolean>(Channel.CONFLATED)
        commands = requests
        var opened: MyNodesVisitSession? = null
        var owned: MyNodesRepositories? = null
        var registration: java.util.UUID? = null
        try {
            val repositories = acquire()
            owned = repositories
            val source = repositories.base.source.split("|", limit = 2)
            val namespace = MyNodesNamespace(source.last(), source.first(), "local", 1)
            val visit = MyNodesVisitSession.open(store, repositories.lifetime, namespace,
                items.filter { it.kind == SavedKind.NODE }.map { it.entityId }.toSet(),
                items.filter { it.kind == SavedKind.OBSERVER }.map { it.entityId }.toSet(),
                now = System.currentTimeMillis())
            opened = visit
            currentCoroutineContext().ensureActive()
            session = visit
            val job = currentCoroutineContext().job
            registration = repositories.lifetime.registerCancellation { job.cancel() }
            requests.trySend(true)
            for (force in requests) {
                loading = true
                error = null
                try {
                    val keys = items.filter { it.kind == SavedKind.NODE }.map { it.entityId }
                    val loaded = if (items.isEmpty()) emptyList() else visit.loadBase(repositories.base, keys, force)
                    val catalog = repositories.base.observers()
                    currentCoroutineContext().ensureActive()
                    repositories.lifetime.check()
                    val captured = visit.visit
                    val now = System.currentTimeMillis()
                    rows = items.map { item ->
                        val entity = if (item.kind == SavedKind.NODE) MyNodesEntity.node(item.entityId) else MyNodesEntity.observer(item.entityId)
                        var metric: MyNodesMetric? = null
                        var fresh = false
                        if (item.kind == SavedKind.NODE) {
                            loaded.firstOrNull { it.publicKey == item.entityId.lowercase() }?.let { row ->
                                row.snapshot?.let { snapshot ->
                                    fresh = row.state == MyNodesBaseState.FETCHED
                                    metric = MyNodesMetric(activity = MyNodesActivity.assess(
                                        date(snapshot.node.lastSeen), null, snapshot.fetchedAt, now, fresh))
                                }
                            }
                        } else {
                            catalog.snapshot?.let { snapshot ->
                                snapshot.observers.firstOrNull { it.id == item.entityId }?.let { observer ->
                                    fresh = catalog.state == MyNodesBaseState.FETCHED
                                    metric = MyNodesMetric(observer = MyNodesObserverStatus.assess(
                                        null, null, date(observer.lastSeen), snapshot.fetchedAt, now, fresh))
                                }
                            }
                        }
                        val previous = rows.firstOrNull { it.id == entity.id }
                        if (!force && !fresh && previous?.fresh == true && previous.revision == metric?.fetchedAt) previous
                        else {
                            val category = if (item.kind == SavedKind.NODE) MyNodesMetric.Category.ACTIVITY else MyNodesMetric.Category.OBSERVER
                            val key = MyNodesSnapshotStore.metricKey(entity, category)
                            val baseline = captured.baselines[key]
                            val result = metric?.takeIf { fresh && it.fetchedAt >= captured.startedAt }
                                ?.review(namespace, entity, baseline, key in captured.expiredKeys) ?: MyNodesReviewResult.unavailable
                            FavoriteChangeRow(entity, item.title, metric, result, baseline?.metric?.evaluatedAt, fresh)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = UiText.Resource(R.string.explore_changes_error_changes_could_not_be_updated_refresh_to_try_again) }
                finally { loading = false }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = UiText.Resource(R.string.explore_changes_error_changes_could_not_be_opened_try_again) }
        finally {
            registration?.let { owned?.lifetime?.removeCancellation(it) }
            if (owned?.lifetime?.isCurrent == false) {
                rows = emptyList()
                error = UiText.Resource(R.string.explore_changes_error_comparison_data_was_cleared_or_the_source_changed_reopen_changes_to_continue)
            }
            session = null
            commands = null
            requests.close()
            loading = false
            try { opened?.finish() }
            catch (_: Exception) { error = UiText.Resource(R.string.explore_changes_error_review_history_could_not_be_saved) }
        }
    }

    private fun date(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

@Composable
fun FavoriteChangesScreen(host: String, sourceName: String?, library: NodeLibrary,
                          store: MyNodesSnapshotStore, acquire: () -> MyNodesRepositories) {
    val items = library.favorites.filter { it.kind == SavedKind.NODE || it.kind == SavedKind.OBSERVER }
    val model = remember(host) { FavoriteChangesModel() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAcquire by rememberUpdatedState(acquire)
    val listState = rememberLazyListState()
    LaunchedEffect(host, items.map { it.key }, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.run(store, currentAcquire, items) }
    }
    LaunchedEffect(model, lifecycle, listState) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                val layout = listState.layoutInfo
                val visible = layout.visibleItemsInfo.filter {
                    val overlap = minOf(it.offset + it.size, layout.viewportEndOffset) - maxOf(it.offset, layout.viewportStartOffset)
                    overlap > 0 && overlap >= it.size * 0.5
                }.map { it.key }.toSet()
                model.rows.filter { it.id in visible }
            }.collectLatest { rows -> rows.forEach { model.review(it) } }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "summary") {
            Card(colors = meshCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FavoriteSourceIdentity(host, sourceName)
                    Row {
                        Text(stringResource(R.string.explore_activity_since_your_last_review), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { model.refresh(true) }, enabled = !model.loading) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
                        }
                    }
                    Text(stringResource(R.string.explore_compares_saved_nodes_and_observers_using_the_15),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.explore_fresh_comparisons_you_view_become_the_baseline_for),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    model.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (items.isEmpty()) item {
            Text(stringResource(R.string.explore_no_favorites_to_compare_save_nodes_or_observers))
        }
        items(model.rows, key = { it.id }) { row ->
            Card(colors = meshCardColors()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text(row.message, style = MaterialTheme.typography.bodyMedium)
                    row.metric?.activity?.let {
                        Text(when (it.state) {
                            MyNodesActivityState.RECENTLY_HEARD -> stringResource(R.string.explore_recently_heard)
                            MyNodesActivityState.NOT_RECENTLY_HEARD -> stringResource(R.string.explore_not_recently_heard)
                            else -> stringResource(R.string.explore_activity_unknown_2)
                        }, style = MaterialTheme.typography.bodyMedium)
                    }
                    row.metric?.observer?.let {
                        Text(when (it.state) {
                            MyNodesObserverStatus.State.ACTIVE -> stringResource(R.string.explore_recently_seen)
                            MyNodesObserverStatus.State.INACTIVE -> stringResource(R.string.explore_not_recently_seen)
                            else -> stringResource(R.string.explore_observer_activity_unknown)
                        }, style = MaterialTheme.typography.bodyMedium)
                    }
                    row.previousAt?.let { Text(stringResource(R.string.explore_last_reviewed, formatReviewDate(it)), style = MaterialTheme.typography.bodySmall) }
                    row.metric?.fetchedAt?.let { Text(stringResource(R.string.explore_checked, formatReviewDate(it)), style = MaterialTheme.typography.bodySmall) }
                    if (row.result.state == MyNodesReviewResult.State.UNAVAILABLE) {
                        Text(stringResource(R.string.explore_a_fresh_successful_check_and_comparable_history_are),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (items.isNotEmpty()) item {
            OutlinedButton(onClick = { model.refresh(false) }, enabled = !model.loading) { Text(stringResource(R.string.explore_load_more)) }
        }
    }
}

private fun formatReviewDate(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))
