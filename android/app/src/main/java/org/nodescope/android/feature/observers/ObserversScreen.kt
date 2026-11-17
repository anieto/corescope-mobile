package org.nodescope.android.feature.observers

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.feature.learn.*
import org.nodescope.android.core.design.meshCardColors
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.model.parseInstant
import org.nodescope.android.core.network.LiveFeedState
import org.nodescope.android.core.network.LiveFeedConfiguration
import org.nodescope.android.core.network.LiveFeedDemand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObserversScreen(
    model: ObserversViewModel, selection: AnalyzerSelection, feed: LiveFeedState,
    regionControl: @Composable () -> Unit, onClearRegion: () -> Unit, onObserver: (MeshObserver) -> Unit,
    activeOnlyRequest: Long? = null,
    selectedId: String? = null,
    resetRequest: Int = 0,
) {
    val state by model.observers.state.collectAsStateWithLifecycle()
    var activity by rememberSaveable { mutableStateOf(ObserverActivity.ALL) }
    var hardware by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(ObserverSort.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    // Reselecting the tab clears the search and closes the filters (iOS).
    org.nodescope.android.core.design.OnTabReset(resetRequest) { query = ""; searching = false; showFilters = false }
    // Explore's "Observers online" asks for recently active observers only (iOS showActiveObservers).
    var handledActiveRequest by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(activeOnlyRequest) {
        if (activeOnlyRequest != null && activeOnlyRequest != handledActiveRequest) {
            activity = ObserverActivity.RECENT
            handledActiveRequest = activeOnlyRequest
        }
    }
    val now = rememberNow()
    // Beacon observers come from the live feed's recovered catalog plus live status events.
    val beaconSource = LocalBeaconSource.current
    val beacon = feed.beacon.takeIf { beaconSource }
    val liveActions = LocalLiveFeedActions.current
    if (beaconSource) LiveFeedDemandEffect(selection.host,
        LiveFeedDemand(configuration = LiveFeedConfiguration(recoverObservers = true)))
    else LaunchedEffect(selection.host) { model.observers.load(selection.host) }
    val all = if (beaconSource) remember(beacon) {
        beacon?.recoveredObservers().orEmpty().map { observer -> beacon?.observers?.get(observer.id.lowercase())?.applying(observer) ?: observer }
    } else state.value.takeIf { state.key == selection.host }.orEmpty()
    val visible = remember(all, selection.region, activity, hardware, query, sort, now) {
        visibleObservers(all, selection.region, activity, hardware, query, sort, now)
    }
    val models = remember(all) { all.mapNotNull { it.model }.distinct().sorted() }

    if (showFilters) FilterSheet(stringResource(R.string.observers_observer_filters), regionControl, listOf(
        FilterGroup(stringResource(R.string.observers_activity), ObserverActivity.entries, activity, { stringResource(it.titleRes) }) { activity = it },
        FilterGroup(stringResource(R.string.observers_hardware), listOf(null) + models, hardware, { it ?: stringResource(R.string.observers_all_models) }) { hardware = it },
        FilterGroup(stringResource(R.string.observers_sort_by), ObserverSort.entries, sort, { stringResource(it.titleRes) }) { sort = it },
    ), onReset = { activity = ObserverActivity.ALL; hardware = null; sort = ObserverSort.RECENT; onClearRegion() }) { showFilters = false }

    val filterCount = listOf(selection.region != null, activity != ObserverActivity.ALL, hardware != null, sort != ObserverSort.RECENT).count { it }
    val summary = (listOf(selection.region ?: stringResource(R.string.observers_entire_network)) + listOfNotNull(activity.takeIf { it != ObserverActivity.ALL }?.let { stringResource(it.titleRes) },
        hardware, sort.takeIf { it != ObserverSort.RECENT }?.let { stringResource(it.titleRes) })).joinToString(" · ")
    fun refresh() = if (beacon != null) liveActions.refreshObservers() else model.observers.load(selection.host, force = true)

    PullToRefreshBox(isRefreshing = beacon == null && state.loading && all.isNotEmpty(), onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BrowseHeader(stringResource(R.string.observers_observers), feed.connection, stringResource(R.string.observers_network_telemetry_live), summary, filterCount, searching,
                    onFilters = { showFilters = true }, onSearch = { searching = !searching; if (!searching) query = "" },
                    titleAccessory = { LearnButton(GlossaryTerm.OBSERVER) })
            }
            if (searching) item { BrowseSearchField(query, { query = it }, stringResource(R.string.observers_search_name_id_region_or_model)) }
            if (beaconSource) {
                if (beacon?.observerNeedsRecovery != false) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.observers_observer_status_may_be_outdated), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (beacon?.observerRecoveryError != null) TextButton(onClick = ::refresh) { Text(stringResource(R.string.retry)) }
                    }
                }
            } else item { LoadStatus(state, all.isNotEmpty(), now, ::refresh) }
            if (visible.isNotEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryMetric(stringResource(R.string.observers_observers), visible.size.toString(), Modifier.weight(1f))
                    SummaryMetric(stringResource(R.string.observers_packets_hr), observerCountTotal(visible.map { it.packetsLastHour })?.let(::compactCount) ?: stringResource(R.string.observers_unknown), Modifier.weight(1f))
                    SummaryMetric(stringResource(R.string.observers_packets), observerCountTotal(visible.map { it.packetCount })?.let(::compactCount) ?: stringResource(R.string.observers_unknown), Modifier.weight(1f))
                }
            }
            if (visible.isNotEmpty()) item { SectionLabel(stringResource(R.string.observers_observer_nodes)) }
            items(visible, key = { it.id }) { ObserverCard(it, now, it.id == selectedId) { onObserver(it) } }
            val loaded = if (beaconSource) beacon?.observerCatalog != null && !beacon.observerNeedsRecovery
                else !state.loading && state.updatedAt != null
            if (visible.isEmpty() && loaded) item {
                EmptyState(Icons.Outlined.SensorsOff, if (all.isEmpty()) stringResource(R.string.observers_no_observers) else stringResource(R.string.observers_no_matching_observers),
                    if (all.isEmpty()) stringResource(R.string.observers_this_analyzer_has_not_reported_any_observers) else stringResource(R.string.observers_try_a_different_search_region_or_filter))
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier.semantics(mergeDescendants = true) {}, colors = meshCardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ObserverCard(observer: MeshObserver, now: Long, isSelected: Boolean, onClick: () -> Unit) {
    // A Beacon status event says whether it's online; otherwise activity means seen in 15 minutes.
    val active = observer.online ?: observer.isActive(now)
    val tone = statusAccent(active)
    Card(onClick = onClick, modifier = Modifier.semantics { selected = isSelected },
        colors = meshCardColors(selected = isSelected)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(tone.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Sensors, if (active) stringResource(R.string.observers_active) else stringResource(R.string.observers_inactive), tint = tone)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(observer.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(observer.online?.let { if (it) stringResource(R.string.observers_online) else stringResource(R.string.observers_offline) } ?: relativeTime(parseInstant(observer.lastSeen), now),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(listOfNotNull(if (active) stringResource(R.string.observers_active_2) else stringResource(R.string.observers_inactive_2), observer.iata, observer.model).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(listOfNotNull(
                    observer.packetsLastHour?.let { "${compactCount(it)}/hr" },
                    observer.packetCount?.let { pluralStringResource(R.plurals.observers_list_packet_count, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), compactCount(it)) },
                ).joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
