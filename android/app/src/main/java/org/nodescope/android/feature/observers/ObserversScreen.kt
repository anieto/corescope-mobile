package org.nodescope.android.feature.observers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.model.parseInstant
import org.nodescope.android.core.network.LiveFeedState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObserversScreen(
    model: ObserversViewModel, selection: AnalyzerSelection, feed: LiveFeedState,
    regionControl: @Composable () -> Unit, onClearRegion: () -> Unit, onObserver: (MeshObserver) -> Unit,
    activeOnlyRequest: Long? = null,
) {
    val state by model.observers.state.collectAsStateWithLifecycle()
    var activity by rememberSaveable { mutableStateOf(ObserverActivity.ALL) }
    var hardware by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(ObserverSort.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    // Explore's "Observers online" asks for recently active observers only (iOS showActiveObservers).
    LaunchedEffect(activeOnlyRequest) { if (activeOnlyRequest != null) activity = ObserverActivity.RECENT }
    val now = rememberNow()
    LaunchedEffect(selection.host) { model.observers.load(selection.host) }
    val all = state.value.takeIf { state.key == selection.host }.orEmpty()
    val visible = remember(all, selection.region, activity, hardware, query, sort, now) {
        visibleObservers(all, selection.region, activity, hardware, query, sort, now)
    }
    val models = remember(all) { all.mapNotNull { it.model }.distinct().sorted() }

    if (showFilters) FilterSheet("Observer filters", regionControl, listOf(
        FilterGroup("Activity", ObserverActivity.entries, activity, { it.title }) { activity = it },
        FilterGroup("Hardware", listOf(null) + models, hardware, { it ?: "All models" }) { hardware = it },
        FilterGroup("Sort by", ObserverSort.entries, sort, { it.title }) { sort = it },
    ), onReset = { activity = ObserverActivity.ALL; hardware = null; sort = ObserverSort.RECENT; onClearRegion() }) { showFilters = false }

    val filterCount = listOf(selection.region != null, activity != ObserverActivity.ALL, hardware != null, sort != ObserverSort.RECENT).count { it }
    val summary = (listOf(selection.region ?: "Entire network") + listOfNotNull(activity.takeIf { it != ObserverActivity.ALL }?.title,
        hardware, sort.takeIf { it != ObserverSort.RECENT }?.title)).joinToString(" · ")
    fun refresh() = model.observers.load(selection.host, force = true)

    PullToRefreshBox(isRefreshing = state.loading && all.isNotEmpty(), onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BrowseHeader("Observers", feed.connection, "Network telemetry live", summary, filterCount, searching,
                    onFilters = { showFilters = true }, onSearch = { searching = !searching; if (!searching) query = "" })
            }
            if (searching) item { BrowseSearchField(query, { query = it }, "Search name, ID, region or model") }
            item { LoadStatus(state, all.isNotEmpty(), now, ::refresh) }
            if (visible.isNotEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryMetric("Observers", visible.size.toString(), Modifier.weight(1f))
                    SummaryMetric("Packets / hr", compactCount(visible.sumOf { it.packetsLastHour ?: 0 }), Modifier.weight(1f))
                    SummaryMetric("Packets", compactCount(visible.sumOf { it.packetCount ?: 0 }), Modifier.weight(1f))
                }
            }
            if (visible.isNotEmpty()) item { SectionLabel("Observer nodes") }
            items(visible, key = { it.id }) { ObserverCard(it, now) { onObserver(it) } }
            if (visible.isEmpty() && !state.loading) item {
                EmptyState(Icons.Outlined.SensorsOff, if (all.isEmpty()) "No observers" else "No matching observers",
                    if (all.isEmpty()) "This analyzer has not reported any observers." else "Try a different search, region or filter.")
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier.semantics(mergeDescendants = true) {}, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ObserverCard(observer: MeshObserver, now: Long, onClick: () -> Unit) {
    val active = observer.isActive(now)
    val tone = statusAccent(active)
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(tone.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Sensors, if (active) "Active" else "Inactive", tint = tone)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(observer.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeTime(parseInstant(observer.lastSeen), now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(listOfNotNull(if (active) "Active" else "Inactive", observer.iata, observer.model).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(listOfNotNull(
                    observer.packetsLastHour?.let { "${compactCount(it)}/hr" },
                    observer.packetCount?.let { "${compactCount(it)} packets" },
                ).joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
