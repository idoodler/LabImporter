package dev.idoodler.labimporter.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import dev.idoodler.labimporter.core.model.ChartAxis
import dev.idoodler.labimporter.core.model.ChartScale
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.MetricPoint
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/**
 * An area chart for a metric's history on the shared [ChartAxis.scale]: straight
 * segments between readings, spaced by date, over a gradient fill down to zero,
 * an optional reference band, and, when [onSelect] is set, touch scrubbing that
 * highlights the nearest reading.
 *
 * @param yLabels draws the shared value ticks along the right edge.
 * @param xLabels optional (start, end) captions under the plot.
 * @param description what TalkBack reads for the chart as a whole (a drawing has no text of its own).
 * @param readingLabels what TalkBack reads for each of [points], the counterpart of iOS's
 *   AXChartDescriptor: one node per reading, placed where it is drawn, since scrubbing is touch-only.
 */
@Composable
fun AreaChart(
    points: List<MetricPoint>,
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
    description: String? = null,
    readingLabels: List<String>? = null,
) {
    val accent = accentFor(color)
    val band = accent.copy(alpha = if (isDarkScheme) 0.16f else 0.12f)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerFill = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor, fontFeatureSettings = "tnum")
    val scale = remember(points, range) { ChartAxis.scale(points, range) }
    val hasXLabels = xLabels != null

    var canvas: Modifier = Modifier.fillMaxSize()
    if (description != null) canvas = canvas.semantics { contentDescription = description }
    if (onSelect != null && scale != null && points.size > 1) {
        fun PointerInputScope.nearest(x: Float): Int {
            val plot = plotArea(size.toSize(), strokeWidth, yLabels, hasXLabels)
            return scale.nearestIndex(((x - plot.left) / plot.width).toDouble().coerceIn(0.0, 1.0))
        }
        canvas = canvas
            .pointerInput(points) {
                detectTapGestures(onPress = { offset ->
                    onSelect(nearest(offset.x))
                    tryAwaitRelease()
                    onSelect(null)
                })
            }
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { onSelect(nearest(it.x)) },
                    onDragEnd = { onSelect(null) },
                    onDragCancel = { onSelect(null) },
                ) { change, _ -> onSelect(nearest(change.position.x)) }
            }
    }

    Box(modifier) {
        Canvas(canvas) {
            if (scale == null) return@Canvas
            val plot = plotArea(size, strokeWidth, yLabels, hasXLabels)
            val stroke = strokeWidth.toPx()
            fun x(point: MetricPoint) = plot.left + plot.width * scale.xFraction(point.date).toFloat()
            fun y(value: Double) = plot.bottom - plot.height * scale.yFraction(value).toFloat()

            if (yLabels) {
                scale.yTicks.forEach { tick ->
                    val yy = y(tick)
                    drawLine(grid, Offset(plot.left, yy), Offset(plot.right, yy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    val text = measurer.measure(formatValue(tick), labelStyle)
                    drawText(text, topLeft = Offset(size.width - text.size.width, yy - text.size.height / 2))
                }
            }
            if (xLabels != null) {
                val start = measurer.measure(xLabels.first, labelStyle)
                val end = measurer.measure(xLabels.second, labelStyle)
                drawText(start, topLeft = Offset(plot.left, size.height - start.size.height))
                drawText(end, topLeft = Offset(plot.right - end.size.width, size.height - end.size.height))
            }

            if (range != null) {
                val bandTop = y(range.high ?: scale.yHigh)
                val bandBottom = y(range.low ?: scale.yLow)
                drawRoundRect(band, Offset(plot.left, bandTop), Size(plot.width, bandBottom - bandTop), CornerRadius(8.dp.toPx()))
            }

            if (points.size > 1) {
                val line = Path().apply {
                    moveTo(x(points.first()), y(points.first().value))
                    points.drop(1).forEach { lineTo(x(it), y(it.value)) }
                }
                val zero = y(0.0)
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(points.last()), zero)
                    lineTo(x(points.first()), zero)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.02f)), startY = plot.top, endY = zero))
                drawPath(line, accent, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (showPoints) {
                    points.forEach { point(accent, markerFill, Offset(x(it), y(it.value)), stroke * 1.1f, ring = false) }
                }
            }
            val highlighted = points[selectedIndex ?: points.lastIndex]
            if (selectedIndex != null) {
                drawLine(accent.copy(alpha = 0.5f), Offset(x(highlighted), plot.top), Offset(x(highlighted), plot.bottom), 1.5.dp.toPx())
            }
            point(accent, markerFill, Offset(x(highlighted), y(highlighted.value)), stroke * 2.4f, ring = true)
        }
        if (readingLabels != null && scale != null) {
            ReadingNodes(points, readingLabels, scale, strokeWidth, yLabels, hasXLabels)
        }
    }
}

/** Invisible nodes for TalkBack, one per reading, where the chart draws it (explore-by-touch finds them there). */
@Composable
private fun ReadingNodes(
    points: List<MetricPoint>,
    labels: List<String>,
    scale: ChartScale,
    strokeWidth: Dp,
    yLabels: Boolean,
    hasXLabels: Boolean,
) {
    Layout(
        content = { labels.forEach { label -> Box(Modifier.semantics { contentDescription = label }) } },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val plot = plotArea(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), strokeWidth, yLabels, hasXLabels)
        val side = READING_NODE_SIZE.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(side, side)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val center = Offset(
                    plot.left + plot.width * scale.xFraction(points[index].date).toFloat(),
                    plot.bottom - plot.height * scale.yFraction(points[index].value).toFloat(),
                )
                placeable.place((center.x - side / 2f).roundToInt(), (center.y - side / 2f).roundToInt())
            }
        }
    }
}

/** The plot's rectangle: the one geometry drawing, hit-testing and the TalkBack nodes all use. */
private fun Density.plotArea(size: Size, strokeWidth: Dp, yLabels: Boolean, hasXLabels: Boolean): Rect {
    val inset = strokeWidth.toPx() * 2
    return Rect(
        left = inset,
        // A tick on the top edge needs half a label's height of room.
        top = if (yLabels) 8.dp.toPx() else inset,
        right = (size.width - if (yLabels) 40.dp.toPx() else inset).coerceAtLeast(inset + 1f),
        bottom = (size.height - if (hasXLabels) 20.dp.toPx() else inset).coerceAtLeast(inset + 1f),
    )
}

private fun DrawScope.point(color: Color, fill: Color, center: Offset, radius: Float, ring: Boolean) {
    drawCircle(color, radius, center)
    if (ring) drawCircle(fill, radius * 0.45f, center)
}

private val READING_NODE_SIZE = 32.dp

private fun previewPoints() = listOf(
    LocalDate(2024, 1, 15) to 6.9, LocalDate(2024, 2, 1) to 6.6, LocalDate(2025, 11, 20) to 6.8,
    LocalDate(2026, 3, 2) to 6.4, LocalDate(2026, 9, 1) to 6.1,
).map { (date, value) -> MetricPoint(date, value, "%", "preview") }

@Preview(name = "Area chart", widthDp = 340, heightDp = 220)
@Composable
private fun AreaChartPreview() {
    LabImporterTheme(dynamicColor = false) {
        Surface {
            AreaChart(
                previewPoints(), LabCategory.GLYCEMIC.color, Modifier.fillMaxSize().padding(16.dp),
                range = ReferenceRange(4.0, 6.0), yLabels = true, xLabels = "Jan 2024" to "Sep 2026",
            )
        }
    }
}

@Preview(name = "Area chart – selected", widthDp = 340, heightDp = 220)
@Composable
private fun AreaChartSelectedPreview() {
    LabImporterTheme(dynamicColor = false) {
        Surface {
            AreaChart(
                previewPoints(), LabCategory.GLYCEMIC.color, Modifier.fillMaxSize().padding(16.dp),
                yLabels = true, xLabels = "Jan 2024" to "Sep 2026", selectedIndex = 2,
            )
        }
    }
}

@Preview(name = "Area chart – dark, single", widthDp = 340, heightDp = 160, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AreaChartSinglePreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            AreaChart(listOf(MetricPoint(LocalDate(2026, 5, 28), 0.8, "mg/dL", "preview")), LabCategory.RENAL.color, Modifier.fillMaxSize().padding(16.dp))
        }
    }
}
