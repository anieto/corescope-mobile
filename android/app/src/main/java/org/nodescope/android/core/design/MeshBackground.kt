package org.nodescope.android.core.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

val LocalBackgroundTexture = staticCompositionLocalOf { true }

private val meshNodes = listOf(
    Offset(-0.08f, 0.12f), Offset(0.18f, 0.07f), Offset(0.34f, 0.25f),
    Offset(0.08f, 0.43f), Offset(0.25f, 0.68f), Offset(-0.06f, 0.84f),
    Offset(0.48f, 0.91f), Offset(0.73f, 0.76f), Offset(0.94f, 0.94f),
    Offset(1.06f, 0.58f), Offset(0.78f, 0.44f), Offset(0.92f, 0.19f), Offset(0.68f, 0.06f),
)
private val meshLinks = listOf(
    0 to 1, 0 to 3, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 4 to 6, 5 to 6,
    6 to 7, 7 to 8, 7 to 9, 8 to 9, 9 to 10, 10 to 11, 11 to 12, 10 to 12,
)

/** Matches iOS MeshBackground. Attach outside scrolling content to keep the pattern still. */
@Composable
fun Modifier.meshPageBackground(subdued: Boolean = true): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val texture = LocalBackgroundTexture.current
    return drawWithCache {
        val gradient = Brush.linearGradient(
            if (dark) listOf(Color(0.065f, 0.10f, 0.16f), Color(0.035f, 0.085f, 0.10f))
            else listOf(Color(0.97f, 0.965f, 0.95f), Color(0.87f, 0.94f, 0.96f)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        val strength = if (subdued) 0.5f else 1f
        val link = (if (dark) Color(0.24f, 0.65f, 0.96f) else Color(0.08f, 0.40f, 0.72f))
            .copy(alpha = (if (dark) 0.38f else 0.32f) * strength)
        val node = (if (dark) Color(0xFFFF9F0A) else Color(0xFFFF9500))
            .copy(alpha = (if (dark) 0.45f else 0.50f) * strength)
        fun faded(color: Color) = Brush.horizontalGradient(
            0f to color, 0.5f to color.copy(alpha = color.alpha * 0.45f), 1f to color,
            startX = 0f, endX = size.width,
        )
        val links = Path()
        val dots = Path()
        val tileWidth = minOf(size.width, 520.dp.toPx())
        val tileHeight = 760.dp.toPx()
        val radius = 2.5.dp.toPx()
        if (texture && tileWidth > 0f) {
            var x = 0f
            while (x < size.width) {
                var y = 0f
                while (y < size.height) {
                    val points = meshNodes.map { Offset(x + it.x * tileWidth, y + it.y * tileHeight) }
                    meshLinks.forEach { (a, b) ->
                        links.moveTo(points[a].x, points[a].y)
                        links.lineTo(points[b].x, points[b].y)
                    }
                    points.forEach { dots.addOval(androidx.compose.ui.geometry.Rect(it, radius)) }
                    y += tileHeight
                }
                x += tileWidth
            }
        }
        val linkBrush = faded(link)
        val nodeBrush = faded(node)
        val stroke = Stroke(1.dp.toPx())
        onDrawBehind {
            drawRect(gradient)
            if (texture) clipRect {
                drawPath(links, linkBrush, style = stroke)
                drawPath(dots, nodeBrush)
            }
        }
    }
}

/** The mesh belongs to the sheet viewport, outside any scrolling children. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, dragHandle = null) {
        Column(Modifier.fillMaxWidth().meshPageBackground()) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                BottomSheetDefaults.DragHandle()
            }
            content()
        }
    }
}

/** iOS instrument-card fill; alpha affects the surface only, never text or icons. */
@Composable
fun meshCardColor(): Color =
    (if (MaterialTheme.colorScheme.background.luminance() < 0.3f)
        Color(0.08f, 0.13f, 0.20f) else Color.White).copy(alpha = 0.80f)

@Composable
fun meshCardColors(selected: Boolean = false): CardColors = CardDefaults.cardColors(
    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else meshCardColor(),
    contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
)
