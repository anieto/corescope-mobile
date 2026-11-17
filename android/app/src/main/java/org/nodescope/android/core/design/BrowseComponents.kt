package org.nodescope.android.core.design

import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.feature.learn.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import org.nodescope.android.core.network.LiveConnection
import org.nodescope.android.core.network.Loaded
import org.nodescope.android.core.network.beaconUiExplanation

/** Minute-resolution clock so "5m ago" labels stay truthful while a screen is open. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    return now
}

@Composable
fun BrowseHeader(
    title: String, connection: LiveConnection, liveLabel: String, summary: String,
    filterCount: Int, searching: Boolean, onFilters: () -> Unit, onSearch: () -> Unit,
    addAction: (@Composable () -> Unit)? = null,
    titleAccessory: (@Composable () -> Unit)? = null,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val actions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = onFilters) {
            BadgedBox(badge = { if (filterCount > 0) Badge(containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) { Text(filterCount.toString()) } }) {
                Icon(Icons.Outlined.FilterList, stringResource(R.string.common_filter_and_sort_active, filterCount))
            }
        }
        IconButton(onClick = onSearch) {
            Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, if (searching) stringResource(R.string.common_close_search) else stringResource(R.string.common_search, title))
        }
        addAction?.invoke()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A tablet's list pane can be narrower than a phone even at normal text size.
        val separateActions = largeText || maxWidth < 360.dp
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineLarge)
                    titleAccessory?.invoke()
                }
                if (!separateActions) actions()
            }
            if (separateActions) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(statusAccent(connection == LiveConnection.LIVE), CircleShape))
                Text(connectionLabel(connection, liveLabel), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BrowseSearchField(query: String, onQuery: (String) -> Unit, placeholder: String) {
    TextField(query, onQuery, singleLine = true, shape = MaterialTheme.shapes.large, placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.common_clear_search)) } },
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth())
}

/** Loading, stale-data and freshness line; downloaded data is never presented as newer than it is. */
@Composable
fun LoadStatus(state: Loaded<*, *>, hasContent: Boolean, now: Long, onRetry: () -> Unit) {
    when {
        state.loading && !hasContent -> LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.displayError?.asString() ?: stringResource(R.string.analyzer_error_load), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (hasContent) Text(state.updatedAt?.let { stringResource(R.string.common_showing_last_loaded_data_at, relativeTime(java.time.Instant.ofEpochMilli(it), now)) } ?: stringResource(R.string.common_showing_last_loaded_data),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        state.updatedAt != null -> Text(stringResource(R.string.common_updated, relativeTime(java.time.Instant.ofEpochMilli(state.updatedAt), now)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        // Centered as a block, so a message that wraps doesn't read as left-aligned under a centered title.
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

class FilterGroup<T>(val title: String, val options: List<T>, val selected: T, val label: @Composable (T) -> String, val onSelect: (T) -> Unit)

/** Filters and sorting in a native sheet; region uses the app-wide region control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(title: String, regionControl: @Composable () -> Unit, groups: List<FilterGroup<*>>, onReset: () -> Unit, onDismiss: () -> Unit) {
    MeshModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onReset) { Text(stringResource(R.string.common_reset)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.common_region))
                LearnButton(GlossaryTerm.REGION, Modifier.padding(top = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Public, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                regionControl()
            }
            groups.forEach { group -> FilterGroupRows(group) }
        }
    }
}

@Composable
private fun <T> FilterGroupRows(group: FilterGroup<T>) {
    SectionLabel(group.title)
    group.options.forEach { option ->
        Row(Modifier.fillMaxWidth().selectable(option == group.selected, role = Role.RadioButton) { group.onSelect(option) }
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = option == group.selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Text(group.label(option), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun MetricChip(text: String, icon: ImageVector, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(color = color.copy(alpha = 0.11f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(13.dp), tint = color)
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** Connection state is independent of data freshness and individual node activity. */
@Composable
fun connectionLabel(connection: LiveConnection, liveLabel: String = stringResource(R.string.common_connection_live)): String = when (connection) {
    LiveConnection.LIVE -> liveLabel
    LiveConnection.CONNECTING -> stringResource(R.string.common_connection_connecting)
    LiveConnection.RECONNECTING -> stringResource(R.string.common_connection_reconnecting)
    LiveConnection.PAUSED -> stringResource(R.string.common_connection_paused)
}

/**
 * Segmented buttons that turn into full-width radio rows when any label wouldn't fit on one line, as with
 * longer translations or larger text sizes. Labels are measured against their segment on every layout, so
 * the same screen can show segments in one language and rows in another. iOS `AdaptiveChoicePicker`
 * follows the same rule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> AdaptiveChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
) {
    val labels = options.map { label(it) }
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Each segment gets an equal share; its label shares it with the padding and the selected checkmark.
        val available = with(density) { (maxWidth / options.size.coerceAtLeast(1) - SegmentChrome).toPx() }
        val fits = remember(labels, style, available) {
            available > 0 && labels.all { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <= available }
        }
        if (fits) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(selected = option == selected, onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        icon = { SegmentedButtonDefaults.Icon(option == selected) { icon?.let { Icon(it(option), null, Modifier.size(18.dp)) } } }) {
                        Text(labels[index], maxLines = 1)
                    }
                }
            }
        } else Column(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = option == selected, onClick = null)
                    icon?.let { Icon(it(option), null, Modifier.size(20.dp)) }
                    Text(labels[options.indexOf(option)], Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Material segmented button padding (12 dp each side) plus the 18 dp icon and its 8 dp gap. */
private val SegmentChrome = 12.dp * 2 + 18.dp + 8.dp

/** True while the selected analyzer is a Beacon server; screens use it to show [BeaconFeatureNotice]. */
val LocalBeaconSource = staticCompositionLocalOf { false }

/** iOS `BeaconFeatureNotice`: why a CoreScope-only feature isn't shown for a Beacon source. */
@Composable
fun BeaconFeatureNotice(capability: org.nodescope.android.core.network.BackendCapability, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.common_beacon_support), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(capability.beaconUiExplanation.asString(),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What screens can ask of the shared live feed: extra demand while visible, and an observer reload. */
class LiveFeedActions(
    val setDemand: (consumer: java.util.UUID, host: String, demand: org.nodescope.android.core.network.LiveFeedDemand?) -> Unit = { _, _, _ -> },
    val refreshObservers: () -> Unit = {},
    val retryMetadata: () -> Unit = {},
)
val LocalLiveFeedActions = staticCompositionLocalOf { LiveFeedActions() }

/** Holds [demand] on the live feed while the screen is resumed (iOS `setDemand` on appear/scene phase). */
@Composable
fun LiveFeedDemandEffect(host: String, demand: org.nodescope.android.core.network.LiveFeedDemand) {
    val actions = LocalLiveFeedActions.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val consumer = remember { java.util.UUID.randomUUID() }
    LaunchedEffect(lifecycle, host, demand) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            try {
                actions.setDemand(consumer, host, demand)
                kotlinx.coroutines.awaitCancellation()
            } finally {
                actions.setDemand(consumer, host, null)
            }
        }
    }
}
