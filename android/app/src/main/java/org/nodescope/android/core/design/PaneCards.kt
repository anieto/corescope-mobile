package org.nodescope.android.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.unit.dp

/**
 * Wide layouts (tablets, unfolded foldables) show each pane as a rounded card on a tinted
 * canvas, as Google's large-screen apps do; phones stay edge to edge. Screens that split into
 * panes read [LocalWideLayout] to draw their panes as separate cards.
 */
val LocalWideLayout = staticCompositionLocalOf { false }

val PaneCorner = 20.dp
val PaneShape = RoundedCornerShape(PaneCorner)
/** Space around cards and between panes. */
val PaneGap = 8.dp

/** The canvas behind cards and the navigation rail. */
@Composable
fun paneCanvasColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** A pane drawn as a card with the screen background. */
@Composable
fun Modifier.paneCard(): Modifier = clip(PaneShape).background(MaterialTheme.colorScheme.background)

/**
 * Rounds the corners of content that can't be clipped, such as the map: MapLibre draws into
 * its own surface, which ignores clipping, so the corners are covered with the canvas color.
 */
@Composable
fun CardCornerMasks(modifier: Modifier = Modifier, color: Color = paneCanvasColor()) {
    Canvas(modifier) {
        val r = PaneCorner.toPx()
        fun corner(x: Float, y: Float, center: Offset) {
            val square = Path().apply { addRect(Rect(Offset(x, y), Size(r, r))) }
            val round = Path().apply { addOval(Rect(center, r)) }
            drawPath(Path().apply { op(square, round, PathOperation.Difference) }, color)
        }
        corner(0f, 0f, Offset(r, r))
        corner(size.width - r, 0f, Offset(size.width - r, r))
        corner(0f, size.height - r, Offset(r, size.height - r))
        corner(size.width - r, size.height - r, Offset(size.width - r, size.height - r))
    }
}
