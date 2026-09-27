package org.nodescope.android.core.design

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Where a vertical fold (Galaxy Z Fold, Pixel Fold) crosses a layout, so two panes can meet
 * exactly at the crease. Android reports the hinge in window coordinates; the layout starts
 * after the navigation rail, so [modifier] measures where the layout sits in the window and
 * [offset] is the fold's distance from the layout's left edge. Null when there is no vertical
 * fold inside the layout (phones, tablets, horizontal folds).
 */
class FoldSplit(val modifier: Modifier, val offset: Dp?)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberFoldSplit(): FoldSplit {
    val hinge = currentWindowAdaptiveInfo().windowPosture.hingeList.firstOrNull { it.isVertical }
    val density = LocalDensity.current
    var left by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val modifier = Modifier.onGloballyPositioned { coordinates ->
        left = coordinates.positionInWindow().x
        width = coordinates.size.width
    }
    val offsetPx = hinge?.let { it.bounds.center.x - left }
    val offset = offsetPx?.takeIf { width > 0 && it > 0f && it < width }?.let { with(density) { it.toDp() } }
    return FoldSplit(modifier, offset)
}

/**
 * The first pane's width when splitting at the fold, or null to use the normal layout: both
 * sides must keep at least [minPane] so neither pane is squeezed by an off-center hinge.
 */
fun FoldSplit.firstPaneWidth(available: Dp, minPane: Dp): Dp? =
    offset?.takeIf { it >= minPane && available - it >= minPane }
