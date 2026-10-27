package org.nodescope.android.feature.map

import org.nodescope.android.core.design.meshPageBackground
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
import kotlinx.coroutines.launch
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
import org.nodescope.android.feature.packets.RouteOption
import org.nodescope.android.feature.packets.routeOptions

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
    selectedId: String?, onSelect: (id: String?, hash: String?, routes: List<RouteOption>) -> Unit, onDetails: (String) -> Unit,
    /** The packet whose route is on the map, and one whose full routes are still being fetched. */
    replayingId: String? = null, lookingUpId: String? = null,
    /** [onShowPackets] restores the wide panel; [onShowPacketsSheet] opens the phone sheet (iOS compact). */
    map: @Composable (onShowPackets: (() -> Unit)?, onShowPacketsSheet: (() -> Unit)?, bottomObscuredFraction: Float) -> Unit,
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
            PacketsSheetLayout(feed, onReconnect, selectedId, onSelect, onDetails, replayingId, lookingUpId, maxHeight) { show, obscured ->
                MapCard(wide, Modifier.fillMaxSize()) { map(null, show, obscured) }
            }
            return@BoxWithConstraints
        }
        if (!open) {
            MapCard(true, Modifier.fillMaxSize()) { map({ setOpen(true) }, null, 0f) }
            return@BoxWithConstraints
        }
        // Panel and map as two cards on the canvas; at a fold the gap between them sits on the crease.
        Row(Modifier.fillMaxSize().background(paneCanvasColor())) {
            Column(Modifier.width(atFold?.minus(PaneGap / 2) ?: PANEL_WIDTH).fillMaxHeight().paneCard().meshPageBackground()) {
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
                    LivePacketsList(feed, onReconnect, selectedId, onSelect, onDetails, replayingId, lookingUpId)
                }
            }
            Spacer(Modifier.width(PaneGap))
            MapCard(true, Modifier.weight(1f).fillMaxHeight()) { map(null, null, 0f) }
        }
    }
}

/** The packets list shared by the wide panel and the phone sheet. */
@Composable
private fun LivePacketsList(
    feed: LiveFeedState, onReconnect: () -> Unit,
    selectedId: String?, onSelect: (id: String?, hash: String?, routes: List<RouteOption>) -> Unit, onDetails: (String) -> Unit,
    replayingId: String?, lookingUpId: String?,
    /**
     * Called when a tap starts a route replay or opens details, so the phone sheet can get out of
     * the way; [then] runs once it has (details replace the map, so the sheet must close first).
     */
    onLeave: (then: () -> Unit) -> Unit = { it() },
) {
    val beaconHost = feed.selection?.host?.takeIf { org.nodescope.android.core.design.LocalBeaconSource.current }
    // Beacon rows are server snapshots without routes: a tap opens the packet's details (iOS sidebar).
    if (beaconHost != null) org.nodescope.android.feature.packets.BeaconPacketScreen(feed, beaconHost,
        selectedHash = selectedId, onPacket = { hash -> onLeave { onDetails(hash) } })
    else PacketScreen(feed, onReconnect, selectedId = selectedId,
        onGroup = { id ->
            // Tapping the selected packet again deselects it (and ends its replay).
            if (selectedId == id) onSelect(null, null, emptyList()) else {
                val group = groupTransmissions(feed.visiblePackets, null, feed.observers).firstOrNull { it.id == id }
                val routes = group?.routeOptions().orEmpty()
                onSelect(id, group?.latest?.hash?.takeIf(String::isNotBlank), routes)
                // As on iPhone, a packet with a route closes the sheet to show its replay.
                if (routes.isNotEmpty()) onLeave {}
            }
        },
        selectedActions = { group -> SelectedPacketActions(group, replaying = group.id == replayingId, lookingUp = group.id == lookingUpId) { id -> onLeave { onDetails(id) } } })
}

/**
 * Phones (iOS compact): the map keeps the screen and a "Show live packets" button opens the list
 * in a sheet at half height, draggable to nearly full; the map stays usable behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PacketsSheetLayout(
    feed: LiveFeedState, onReconnect: () -> Unit,
    selectedId: String?, onSelect: (id: String?, hash: String?, routes: List<RouteOption>) -> Unit, onDetails: (String) -> Unit,
    replayingId: String?, lookingUpId: String?, height: androidx.compose.ui.unit.Dp,
    map: @Composable (onShowPacketsSheet: () -> Unit, bottomObscuredFraction: Float) -> Unit,
) {
    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
    val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
    val scope = rememberCoroutineScope()
    val open = sheetState.targetValue != SheetValue.Hidden
    fun hide() { scope.launch { sheetState.hide() } }
    // Details are a separate screen: close the sheet first, or it comes back open with the map
    // (its saved position) and covers a replay started from there.
    fun leave(then: () -> Unit) { scope.launch { try { sheetState.hide() } finally { then() } } }
    // Whatever starts a replay (a row here, or "Replay on map" in packet details), the route is
    // what to show, as on iPhone.
    LaunchedEffect(replayingId) { if (replayingId != null && sheetState.currentValue != SheetValue.Hidden) sheetState.hide() }
    androidx.activity.compose.BackHandler(enabled = open) { hide() }
    BottomSheetScaffold(
        scaffoldState = scaffoldState, sheetPeekHeight = height / 2, sheetSwipeEnabled = true,
        sheetContent = {
            Column(Modifier.fillMaxWidth().height(height * 0.85f).meshPageBackground()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Live packets", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = ::hide) { Icon(Icons.Outlined.Close, "Close live packets") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Composed only while open, so a hidden sheet doesn't hold a Beacon packet subscription.
                    if (open) LivePacketsList(feed, onReconnect, selectedId, onSelect, onDetails, replayingId, lookingUpId, onLeave = ::leave)
                }
            }
        },
    ) {
        // The map keeps the whole area; the sheet floats over it.
        // The sheet's resting height, not every drag frame, like iOS detents (half or nearly full).
        val obscured = when (sheetState.targetValue) {
            SheetValue.Hidden -> 0f
            SheetValue.PartiallyExpanded -> 0.5f
            SheetValue.Expanded -> 0.85f
        }
        map({ scope.launch { sheetState.partialExpand() } }, obscured)
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
