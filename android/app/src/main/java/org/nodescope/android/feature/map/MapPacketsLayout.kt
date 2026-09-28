package org.nodescope.android.feature.map

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.nodescope.android.core.design.CardCornerMasks
import org.nodescope.android.core.design.LocalWideLayout
import org.nodescope.android.core.design.PaneGap
import org.nodescope.android.core.design.firstPaneWidth
import org.nodescope.android.core.design.paneCanvasColor
import org.nodescope.android.core.design.paneCard
import org.nodescope.android.core.design.rememberFoldSplit
import org.nodescope.android.core.network.LiveFeedState
import org.nodescope.android.feature.packets.PacketScreen
import org.nodescope.android.feature.packets.TransmissionGroup
import org.nodescope.android.feature.packets.groupTransmissions
import org.nodescope.android.feature.packets.replayRoutes

/** Without a fold, below this content width the map keeps the whole screen (phones, small tablets). */
internal val PACKETS_PANEL_MIN_WIDTH = 840.dp
private val PANEL_WIDTH = 360.dp
/** On a foldable, each side of the crease must keep at least this much for the split. */
private val MIN_PANE_AT_FOLD = 280.dp
private const val PANEL_OPEN_KEY = "packets-panel-open"

/**
 * Wide screens show the live packet feed beside the map, as CoreScope's web live view does:
 * the panel sits next to the navigation rail and the map keeps the right side, where its
 * controls are. Tapping a packet selects it and replays its route on the map (the map's replay
 * controls restart it or pick another route); the selected row offers "Details". The highlight
 * is [selectedId], owned by the app with the replay, so the panel and the map always agree:
 * leaving the replay clears it and a replay started elsewhere moves it. The panel starts open;
 * collapsed, the map takes the
 * whole width and its header gets a "Show live packets" button (passed to [map]). The choice
 * is remembered.
 */
@Composable
fun MapPacketsLayout(
    feed: LiveFeedState, onReconnect: () -> Unit,
    selectedId: String?, onSelect: (id: String?, hash: String?, routes: List<List<String>>) -> Unit, onDetails: (String) -> Unit,
    /** The packet whose route is on the map, and one whose full routes are still being fetched. */
    replayingId: String? = null, lookingUpId: String? = null,
    map: @Composable (onShowPackets: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("map-layout", Context.MODE_PRIVATE) }
    var open by remember { mutableStateOf(preferences.getBoolean(PANEL_OPEN_KEY, true)) }
    fun setOpen(value: Boolean) {
        open = value
        preferences.edit { putBoolean(PANEL_OPEN_KEY, value) }
    }
    val fold = rememberFoldSplit()
    val wide = LocalWideLayout.current
    BoxWithConstraints(Modifier.fillMaxSize().then(fold.modifier)) {
        // An unfolded foldable splits exactly at the crease (panel on one half, map on the
        // other), whatever its width; other screens use the width cutoff.
        val atFold = fold.firstPaneWidth(maxWidth, MIN_PANE_AT_FOLD)
        if (atFold == null && maxWidth < PACKETS_PANEL_MIN_WIDTH) {
            MapCard(wide, Modifier.fillMaxSize()) { map(null) }
            return@BoxWithConstraints
        }
        if (!open) {
            MapCard(true, Modifier.fillMaxSize()) { map { setOpen(true) } }
            return@BoxWithConstraints
        }
        // Panel and map as two cards on the canvas; at a fold the gap between them sits on the crease.
        Row(Modifier.fillMaxSize().background(paneCanvasColor())) {
            Column(Modifier.width(atFold?.minus(PaneGap / 2) ?: PANEL_WIDTH).fillMaxHeight().paneCard()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live packets", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        Text("Tap a packet to see its route", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Closes into the map header's "Show live packets" button.
                    IconButton(onClick = { setOpen(false) }) { Icon(Icons.Outlined.Close, "Hide live packets") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PacketScreen(feed, onReconnect, selectedId = selectedId,
                        onGroup = { id ->
                            // Tapping the selected packet again deselects it (and ends its replay).
                            if (selectedId == id) onSelect(null, null, emptyList()) else {
                                val group = groupTransmissions(feed.visiblePackets, null, feed.observers).firstOrNull { it.id == id }
                                onSelect(id, group?.latest?.hash?.takeIf(String::isNotBlank), group?.let(::replayRoutes).orEmpty())
                            }
                        },
                        selectedActions = { group -> SelectedPacketActions(group, replaying = group.id == replayingId, lookingUp = group.id == lookingUpId, onDetails) })
                }
            }
            Spacer(Modifier.width(PaneGap))
            MapCard(true, Modifier.weight(1f).fillMaxHeight()) { map(null) }
        }
    }
}

/**
 * Under the selected row: its details, and a note when there is no route to draw. Replaying is
 * left to the map's replay controls, which also restart it and choose between routes.
 */
@Composable
private fun SelectedPacketActions(group: TransmissionGroup, replaying: Boolean, lookingUp: Boolean, onDetails: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(when { replaying -> "Route on the map"; lookingUp -> "Looking for routes…"; else -> "No route to show" }, Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onDetails(group.id) }) { Text("Details") }
    }
}

/** The map as a rounded card: its surface can't be clipped, so the corners are masked. */
@Composable
private fun MapCard(rounded: Boolean, modifier: Modifier = Modifier, map: @Composable () -> Unit) {
    Box(modifier) {
        map()
        if (rounded) CardCornerMasks(Modifier.matchParentSize())
    }
}
