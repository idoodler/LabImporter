package dev.idoodler.labimporter.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.MetricTrend
import dev.idoodler.labimporter.ui.components.labelRes
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer

/** An icon inside a Material 3 Expressive shape — the app's signature badge. */
@Composable
fun ShapeBadge(
    polygon: RoundedPolygon,
    color: Color,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    brush: Brush? = null,
    iconTint: Color = contentColorOn(color),
    contentDescription: String? = null,
) {
    Box(
        modifier.size(size).clip(polygon.toShape()).background(brush ?: SolidColor(color)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A category's badge: its shape, colour and symbol. */
@Composable
fun CategoryBadge(category: LabCategory, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    ShapeBadge(
        category.polygon, category.color, category.icon, modifier, size,
        brush = Brush.linearGradient(listOf(category.color, lerpSaturated(category.color))),
        contentDescription = stringResource(category.labelRes),
    )
}

private fun lerpSaturated(color: Color) = androidx.compose.ui.graphics.lerp(color, Color.Black, 0.18f)

/** A pill with a category dot, its name and an optional count (e.g. "Lipids 4"). */
@Composable
fun CategoryChip(category: LabCategory, count: Int?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = tintedSurface(category.color, 0.22f),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(category.color, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(stringResource(category.labelRes), style = MaterialTheme.typography.labelLarge)
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    count.toString(), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The iOS-style trend glyph (shared rule, `MetricTrend`): an arrow in a filled circle of the category colour. */
@Composable
fun TrendBadge(trend: MetricTrend, color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val (icon, label) = when (trend) {
        MetricTrend.RISING -> Icons.Rounded.NorthEast to R.string.trend_up
        MetricTrend.FALLING -> Icons.Rounded.SouthEast to R.string.trend_down
        MetricTrend.STEADY -> Icons.Rounded.East to R.string.trend_flat
    }
    Box(modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, stringResource(label), tint = contentColorOn(color), modifier = Modifier.size(size * 0.62f))
    }
}

/** A big number over a small caption, for summary rows. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMediumEmphasized.tabular(), fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "Badges")
@Composable
private fun BadgesPreview() {
    LabImporterTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(12.dp)) {
                Row { LabCategory.entries.take(8).forEach { CategoryBadge(it, Modifier.padding(4.dp)) } }
                Row { LabCategory.entries.drop(8).forEach { CategoryBadge(it, Modifier.padding(4.dp)) } }
                Row(Modifier.padding(top = 8.dp)) {
                    CategoryChip(LabCategory.LIPIDS, 4); Spacer(Modifier.width(6.dp)); CategoryChip(LabCategory.RENAL, 1)
                }
                Row(Modifier.padding(top = 8.dp)) {
                    MetricTrend.entries.forEach { TrendBadge(it, LabCategory.ELECTROLYTES.color, Modifier.padding(4.dp)) }
                    ShapeBadge(MaterialShapes.Sunny, Color(0xFF7C4DFF), null, Modifier.padding(4.dp))
                }
            }
        }
    }
}
