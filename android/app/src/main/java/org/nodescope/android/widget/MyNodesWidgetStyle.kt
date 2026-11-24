package org.nodescope.android.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import org.nodescope.android.R
import org.nodescope.android.core.design.meshGradientColors
import org.nodescope.android.core.design.meshLinks
import org.nodescope.android.core.design.meshNodes
import kotlin.math.roundToInt

internal class WidgetColors(context: Context) {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    val primary = if (dark) 0xFFF3F6FA.toInt() else 0xFF152637.toInt()
    val secondary = if (dark) 0xFFB7C8D6.toInt() else 0xFF425768.toInt()
}

internal fun widgetMesh(dark: Boolean, texture: Boolean): Bitmap {
    val size = 240
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val colors = meshGradientColors(dark).map { it.toArgb() }.toIntArray()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), colors, null, Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    if (texture) {
        paint.shader = null
        paint.color = if (dark) 0x3065B0FF else 0x280A65B8
        paint.strokeWidth = 1.35f
        meshLinks.forEach { (start, end) ->
            val a = meshNodes[start]
            val b = meshNodes[end]
            canvas.drawLine(a.x * size, a.y * size, b.x * size, b.y * size, paint)
        }
        paint.color = 0x48FF9F0A
        meshNodes.forEach { canvas.drawCircle(it.x * size, it.y * size, 2.2f, paint) }
    }
    return bitmap
}

internal data class WidgetText(val bitmap: Bitmap, val height: Float)

internal fun widgetText(
    context: Context, text: String, widthDp: Float, color: Int, fontSize: Float = 14f, lines: Int = 3,
): WidgetText {
    // Glance uses RemoteViews. A small bitmap lets the widget share the app's bundled font.
    val metrics = context.resources.displayMetrics
    val density = metrics.density.coerceAtMost(2f)
    val width = (widthDp * density).roundToInt().coerceAtLeast(1)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.monaspace_neon_semibold)
        textSize = fontSize * density
        this.color = color
    }
    val longestWord = text.split(Regex("\\s+")).maxOf(paint::measureText)
    if (longestWord > width) {
        paint.textSize *= (width / longestWord).coerceAtLeast(0.85f)
    }
    fun layout(): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setMaxLines(lines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
    var rendered = layout()
    if (rendered.lineCount == lines && rendered.getEllipsisCount(lines - 1) > 0) {
        paint.textSize = (fontSize - 2) * density
        rendered = layout()
    }
    val height = rendered.height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    rendered.draw(Canvas(bitmap))
    return WidgetText(bitmap, height / density)
}
