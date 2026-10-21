package dev.idoodler.labimporter.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An area chart for a metric's history: a smooth line over a gradient fill
 * that fades into the card, an optional reference band, and — when
 * [onSelect] is set — touch scrubbing that highlights the nearest reading.
 *
 * @param yLabels draws three value labels along the right edge.
 * @param xLabels optional (start, end) captions under the plot.
 */
@Composable
fun AreaChart(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    range: ReferenceRange? = null,
    strokeWidth: Dp = 3.dp,
    showPoints: Boolean = true,
    yLabels: Boolean = false,
    xLabels: Pair<String, String>? = null,
    selectedIndex: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
    formatValue: (Double) -> String = { it.toString() },
    /** What TalkBack reads for the chart (a drawing has no text of its own); scrubbing is touch-only. */
    description: String? = null,
) {
    val accent = accentFor(color)
    val band = accent.copy(alpha = if (isDarkScheme) 0.16f else 0.12f)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerFill = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor, fontFeatureSettings = "tnum")

    var gestures = if (description != null) modifier.semantics { contentDescription = description } else modifier
    if (onSelect != null && values.size > 1) {
        gestures = gestures
            .pointerInput(values) {
                detectTapGestures(onPress = { offset ->
                    onSelect(nearestIndex(offset.x, size.width.toFloat(), values.size, yLabels))
                    tryAwaitRelease()
                    onSelect(null)
                })
            }
            .pointerInput(values) {
                detectDragGestures(
                    onDragStart = { onSelect(nearestIndex(it.x, size.width.toFloat(), values.size, yLabels)) },
                    onDragEnd = { onSelect(null) },
                    onDragCancel = { onSelect(null) },
                ) { change, _ -> onSelect(nearestIndex(change.position.x, size.width.toFloat(), values.size, yLabels)) }
            }
    }

    Canvas(gestures) {
        if (values.isEmpty()) return@Canvas
        val candidates = values + listOfNotNull(range?.low, range?.high)
        var min = candidates.min()
        var max = candidates.max()
        if (max - min < 1e-9) { min -= 1.0; max += 1.0 }
        val pad = (max - min) * 0.15
        min -= pad; max += pad

        val stroke = strokeWidth.toPx()
        val right = if (yLabels) 40.dp.toPx() else stroke * 2
        val bottom = if (xLabels != null) 20.dp.toPx() else stroke * 2
        val left = stroke * 2
        val top = stroke * 2
        val plotWidth = size.width - left - right
        val plotHeight = size.height - top - bottom
        fun x(i: Int) = if (values.size == 1) left + plotWidth / 2 else left + plotWidth * i / (values.size - 1)
        fun y(v: Double) = (top + plotHeight * (1 - (v - min) / (max - min))).toFloat()

        if (yLabels) {
            listOf(min + pad, (min + max) / 2, max - pad).forEach { v ->
                val yy = y(v)
                drawLine(grid, Offset(left, yy), Offset(left + plotWidth, yy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val text = measurer.measure(formatValue(v), labelStyle)
                drawText(text, topLeft = Offset(size.width - text.size.width, yy - text.size.height / 2))
            }
        }
        if (xLabels != null) {
            val start = measurer.measure(xLabels.first, labelStyle)
            val end = measurer.measure(xLabels.second, labelStyle)
            drawText(start, topLeft = Offset(left, size.height - start.size.height))
            drawText(end, topLeft = Offset(left + plotWidth - end.size.width, size.height - end.size.height))
        }

        if (range != null) {
            val bandTop = y(range.high ?: max)
            val bandBottom = y(range.low ?: min)
            drawRoundRect(band, Offset(left, bandTop), Size(plotWidth, bandBottom - bandTop), CornerRadius(8.dp.toPx()))
        }

        if (values.size == 1) {
            // One reading: a flat level with the same gradient fill, so the card still reads as a chart.
            val level = top + plotHeight * 0.35f
            drawRect(
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.02f)), startY = level, endY = top + plotHeight),
                Offset(left, level), Size(plotWidth, top + plotHeight - level),
            )
            drawLine(accent, Offset(left, level), Offset(left + plotWidth, level), stroke, cap = StrokeCap.Round)
            point(accent, markerFill, Offset(left + plotWidth, level), stroke * 2.4f, ring = true)
            return@Canvas
        }

        val line = smoothPath(values.indices.map { Offset(x(it), y(values[it])) })
        val fill = Path().apply {
            addPath(line)
            lineTo(x(values.lastIndex), top + plotHeight)
            lineTo(x(0), top + plotHeight)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.02f)), startY = top, endY = top + plotHeight))
        drawPath(line, accent, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))

        if (showPoints) {
            values.forEachIndexed { i, v -> point(accent, markerFill, Offset(x(i), y(v)), stroke * 1.1f, ring = false) }
        }
        val highlight = selectedIndex ?: values.lastIndex
        if (selectedIndex != null) {
            drawLine(accent.copy(alpha = 0.5f), Offset(x(highlight), top), Offset(x(highlight), top + plotHeight), 1.5.dp.toPx())
        }
        point(accent, markerFill, Offset(x(highlight), y(values[highlight])), stroke * 2.4f, ring = true)
    }
}

private fun DrawScope.point(color: Color, fill: Color, center: Offset, radius: Float, ring: Boolean) {
    drawCircle(color, radius, center)
    if (ring) drawCircle(fill, radius * 0.45f, center)
}

/** A gentle monotone-ish curve through the points (cubic midpoints). */
private fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val p0 = points[i - 1]
        val p1 = points[i]
        val midX = (p0.x + p1.x) / 2
        cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
    }
}

private fun nearestIndex(x: Float, width: Float, count: Int, hasYLabels: Boolean): Int {
    val left = 6f
    val right = if (hasYLabels) width * 0.12f else 6f
    val plot = (width - left - right).coerceAtLeast(1f)
    val fraction = ((x - left) / plot).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

@Suppress("unused")
private fun closeTo(a: Double, b: Double) = abs(a - b) < 1e-9

@Preview(name = "Area chart", widthDp = 340, heightDp = 220)
@Composable
private fun AreaChartPreview() {
    LabImporterTheme(dynamicColor = false) {
        Surface {
            AreaChart(
                listOf(6.9, 6.6, 6.8, 6.4, 6.1), LabCategory.GLYCEMIC.color, Modifier.fillMaxSize().padding(16.dp),
                range = ReferenceRange(4.0, 6.0), yLabels = true, xLabels = "Jan 2026" to "Sep 2026",
            )
        }
    }
}

@Preview(name = "Area chart – dark, single", widthDp = 340, heightDp = 160, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AreaChartSinglePreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        Surface { AreaChart(listOf(0.8), LabCategory.RENAL.color, Modifier.fillMaxSize().padding(16.dp)) }
    }
}
