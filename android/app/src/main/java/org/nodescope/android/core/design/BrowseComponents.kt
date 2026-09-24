package org.nodescope.android.core.design

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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.nodescope.android.core.network.LiveConnection
import org.nodescope.android.core.network.Loaded

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
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val actions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = onFilters) {
            BadgedBox(badge = { if (filterCount > 0) Badge(containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) { Text(filterCount.toString()) } }) {
                Icon(Icons.Outlined.FilterList, "Filter and sort, $filterCount active")
            }
        }
        IconButton(onClick = onSearch) {
            Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, if (searching) "Close search" else "Search $title")
        }
        addAction?.invoke()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            if (!largeText) actions()
        }
        if (largeText) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).background(statusAccent(connection == LiveConnection.LIVE), CircleShape))
            Text(connectionLabel(connection, liveLabel), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BrowseSearchField(query: String, onQuery: (String) -> Unit, placeholder: String) {
    TextField(query, onQuery, singleLine = true, shape = MaterialTheme.shapes.large, placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search") } },
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
                Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (hasContent) Text("Showing the last loaded data" + (state.updatedAt?.let { " from ${relativeTime(java.time.Instant.ofEpochMilli(it), now)}" } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        state.updatedAt != null -> Text("Updated ${relativeTime(java.time.Instant.ofEpochMilli(state.updatedAt), now)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

class FilterGroup<T>(val title: String, val options: List<T>, val selected: T, val label: (T) -> String, val onSelect: (T) -> Unit)

/** Filters and sorting in a native sheet; region uses the app-wide region control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(title: String, regionControl: @Composable () -> Unit, groups: List<FilterGroup<*>>, onReset: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onReset) { Text("Reset") }
            }
            SectionLabel("Region")
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
    Text(text.uppercase(), modifier.padding(top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun MetricChip(text: String, icon: ImageVector, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(color = color.copy(alpha = 0.11f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(13.dp), tint = color)
            Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        }
    }
}

/** Connection state is independent of data freshness and individual node activity. */
fun connectionLabel(connection: LiveConnection, liveLabel: String = "Live"): String = when (connection) {
    LiveConnection.LIVE -> liveLabel
    LiveConnection.CONNECTING -> "Connecting"
    LiveConnection.RECONNECTING -> "Reconnecting"
    LiveConnection.PAUSED -> "Paused"
}

/** Full-width radio rows preserve readable labels when segmented controls become cramped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> AdaptiveChoiceRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = option == selected, onClick = null)
                    Text(label(option), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    } else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(selected = option == selected, onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size)) { Text(label(option)) }
        }
    }
}
