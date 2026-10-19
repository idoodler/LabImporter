package dev.idoodler.labimporter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.RangeStatus
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science

/** A small filled dot in a metric's category colour. */
@Composable
fun CategoryDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** "High"/"Low" pill for out-of-range values; nothing for in-range or no range. */
@Composable
fun RangeBadge(status: RangeStatus?, modifier: Modifier = Modifier) {
    if (status == null || !status.isOutOfRange) return
    val label = stringResource(if (status == RangeStatus.HIGH) R.string.range_high else R.string.range_low)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/**
 * Line chart for a metric's history. Draws an optional reference band, a
 * gradient fill under the line and a marker on the latest reading.
 */
@Composable
fun TrendChart(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    range: ReferenceRange? = null,
    strokeWidth: Dp = 3.dp,
    showPoints: Boolean = true,
) {
    val bandColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    val markerFill = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val candidates = values + listOfNotNull(range?.low, range?.high)
        var min = candidates.min()
        var max = candidates.max()
        if (max - min < 1e-9) { min -= 1; max += 1 }
        val pad = (max - min) * 0.12
        min -= pad; max += pad
        val inset = strokeWidth.toPx() * 2
        fun x(i: Int) = if (values.size == 1) size.width / 2 else inset + (size.width - 2 * inset) * i / (values.size - 1)
        fun y(v: Double) = (inset + (size.height - 2 * inset) * (1 - (v - min) / (max - min))).toFloat()

        if (range != null) {
            val top = y(range.high ?: max)
            val bottom = y(range.low ?: min)
            drawRect(bandColor, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, bottom - top))
        }

        val line = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
        }
        if (values.size > 1) {
            val fill = Path().apply {
                addPath(line)
                lineTo(x(values.lastIndex), size.height)
                lineTo(x(0), size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (showPoints) {
            values.forEachIndexed { i, v ->
                val last = i == values.lastIndex
                val r = if (last) strokeWidth.toPx() * 2.2f else strokeWidth.toPx() * 1.2f
                drawCircle(color, r, Offset(x(i), y(v)))
                if (last) drawCircle(markerFill, r * 0.45f, Offset(x(i), y(v)))
            }
        }
    }
}

/** Centered illustration + text + optional action for empty screens. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Science,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(24.dp).size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
        )
        action?.invoke()
    }
}

@Preview(name = "Trend chart", widthDp = 320, heightDp = 160)
@Preview(name = "Trend chart – dark", widthDp = 320, heightDp = 160, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TrendChartPreview() {
    LabImporterTheme(dynamicColor = false) {
        Surface {
            TrendChart(
                listOf(6.9, 6.6, 6.8, 6.4, 6.1), Color(0xFFFF9500), Modifier.fillMaxSize().padding(16.dp),
                range = ReferenceRange(4.0, 6.0),
            )
        }
    }
}

@Preview(name = "Badges")
@Composable
private fun BadgePreview() {
    LabImporterTheme(dynamicColor = false) {
        Surface { Column(Modifier.padding(8.dp)) { RangeBadge(RangeStatus.HIGH); RangeBadge(RangeStatus.LOW) } }
    }
}
