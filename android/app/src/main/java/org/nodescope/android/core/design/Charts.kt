package org.nodescope.android.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class ChartPoint(val label: String, val value: Long)

/** Screen readers get a text summary: charts are an addition to, not the only form of, the data. */
private fun describe(title: String, points: List<ChartPoint>): String {
    val peak = points.maxByOrNull { it.value } ?: return "$title: no data"
    return "$title: ${points.size} intervals from ${points.first().label} to ${points.last().label}; peak ${peak.value} at ${peak.label}"
}

@Composable
fun BarChart(title: String, points: List<ChartPoint>, color: Color, modifier: Modifier = Modifier) {
    val max = points.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = describe(title, points) }) {
        Row(Modifier.fillMaxWidth().height(170.dp)) {
            AxisLabels(max)
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val slot = size.width / points.size.coerceAtLeast(1)
                val gap = (slot * 0.18f).coerceAtMost(6.dp.toPx())
                points.forEachIndexed { index, point ->
                    val height = size.height * point.value / max
                    drawRoundRect(color, Offset(index * slot + gap / 2, size.height - height), Size(slot - gap, height),
                        CornerRadius(3.dp.toPx().coerceAtMost((slot - gap) / 2)))
                }
            }
        }
        XLabels(points)
    }
}

@Composable
fun LineChart(title: String, points: List<ChartPoint>, color: Color, modifier: Modifier = Modifier) {
    val max = points.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = describe(title, points) }) {
        Row(Modifier.fillMaxWidth().height(170.dp)) {
            AxisLabels(max)
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp)) {
                if (points.isEmpty()) return@Canvas
                val step = if (points.size > 1) size.width / (points.size - 1) else 0f
                val positions = points.mapIndexed { index, point ->
                    Offset(if (points.size > 1) index * step else size.width / 2, size.height - size.height * point.value / max)
                }
                val line = Path().apply { positions.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                val area = Path().apply {
                    addPath(line); lineTo(positions.last().x, size.height); lineTo(positions.first().x, size.height); close()
                }
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f))))
                drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                positions.forEach { drawCircle(color, 3.dp.toPx(), it) }
            }
        }
        XLabels(points)
    }
}

/** Ranked horizontal bars with the exact count as text. */
@Composable
fun RankedBars(rows: List<ChartPoint>, color: Color) {
    val max = rows.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    val total = rows.sumOf { it.value }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row {
                    Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${compactCount(row.value)} · ${row.value * 100 / total}%", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)) {
                    Box(Modifier.fillMaxWidth(row.value.toFloat() / max).fillMaxHeight().background(color, MaterialTheme.shapes.small))
                }
            }
        }
    }
}

@Composable
private fun AxisLabels(max: Long) {
    Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
        Text(compactCount(max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun XLabels(points: List<ChartPoint>) {
    if (points.isEmpty()) return
    val shown = listOf(points.first(), points[points.size / 2], points.last()).distinct()
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp)) {
        shown.forEachIndexed { index, point ->
            Text(point.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = when { shown.size == 1 -> TextAlign.Center; index == 0 -> TextAlign.Start; index == shown.lastIndex -> TextAlign.End; else -> TextAlign.Center }, maxLines = 1)
        }
    }
}

data class TimeSeries(val color: Color, val points: List<Pair<Long, Double>>)

/**
 * Values placed by time across [from]..[to], so sparse buckets keep their real gaps.
 * With [area] the first series is filled; [zeroBased] anchors the axis at zero for counts.
 */
@Composable
fun TimeChart(description: String, series: List<TimeSeries>, from: Long, to: Long, area: Boolean, zeroBased: Boolean,
    formatValue: (Double) -> String, formatTime: (Long) -> String, modifier: Modifier = Modifier) {
    val values = series.flatMap { s -> s.points.map { it.second } }
    if (values.isEmpty() || to <= from) return
    val low = if (zeroBased) 0.0 else values.min()
    val high = values.max().let { if (it - low < 1e-9) low + 1 else it }
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().height(170.dp)) {
            Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                Text(formatValue(high), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatValue(low), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp)) {
                fun place(point: Pair<Long, Double>) = Offset(
                    size.width * ((point.first - from).toFloat() / (to - from)).coerceIn(0f, 1f),
                    size.height - size.height * ((point.second - low) / (high - low)).toFloat())
                series.forEachIndexed { index, s ->
                    val positions = s.points.map(::place)
                    if (positions.size > 1) {
                        val line = Path().apply { positions.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                        if (area && index == 0) drawPath(Path().apply {
                            addPath(line); lineTo(positions.last().x, size.height); lineTo(positions.first().x, size.height); close()
                        }, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0.03f))))
                        drawPath(line, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    positions.forEach { drawCircle(s.color, 2.5.dp.toPx(), it) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp)) {
            Text(formatTime(from), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(to), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}

/** Categorical colors for chart slices, distinct in both light and dark themes. */
val CategoryColors = listOf(
    Color(0xFF3B82F6), Color(0xFF22A06B), Color(0xFFF59E0B), Color(0xFF8B5CF6), Color(0xFFEF4444), Color(0xFF06B6D4),
    Color(0xFFEAB308), Color(0xFFEC4899), Color(0xFF6366F1), Color(0xFF84CC16), Color(0xFFA16207), Color(0xFF64748B), Color(0xFF14B8A6),
)

/**
 * Donut chart like the iOS observer packet-type chart (Swift Charts `SectorMark`,
 * 58% inner radius, small gaps): total in the middle, and a legend with each slice's
 * count and share so the exact numbers stay readable.
 */
@Composable
fun DonutChart(description: String, slices: List<ChartPoint>, modifier: Modifier = Modifier, centerLabel: String = "packets") {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(200.dp).semantics(mergeDescendants = true) { contentDescription = description }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val thickness = size.minDimension / 2 * (1 - 0.58f)
                val inset = thickness / 2
                val arcSize = Size(size.width - thickness, size.height - thickness)
                val gap = if (slices.size > 1) 1.5f else 0f
                var start = -90f
                if (slices.isEmpty()) drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(thickness))
                slices.forEachIndexed { index, slice ->
                    val sweep = 360f * slice.value / total
                    if (sweep > gap * 2) drawArc(CategoryColors[index % CategoryColors.size], start + gap, sweep - gap * 2, false,
                        Offset(inset, inset), arcSize, style = Stroke(thickness))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(compactCount(slices.sumOf { it.value }), style = MaterialTheme.typography.headlineSmall)
                Text(centerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEachIndexed { index, slice ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(9.dp).background(CategoryColors[index % CategoryColors.size], CircleShape))
                    Text(slice.label, style = MaterialTheme.typography.labelMedium)
                    Text("${compactCount(slice.value)} · ${percentOf(slice.value, total)}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Whole percent, but never "0%" for a non-zero slice. */
fun percentOf(value: Long, total: Long): String {
    if (total <= 0) return "0%"
    val percent = value * 100.0 / total
    return if (value > 0 && percent < 1) "<1%" else "${Math.round(percent)}%"
}
