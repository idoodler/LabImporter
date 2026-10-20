package dev.idoodler.labimporter.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Material 3 Expressive "segmented" lists: related rows sit in one rounded
 * group, separated by hairline gaps, with large outer corners and small inner
 * ones — the look of Android 16's Settings.
 */

private val Outer = 24.dp
private val Inner = 6.dp

/** The shape for row [index] of [count] in a group. */
fun groupShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(Outer)
    index == 0 -> RoundedCornerShape(topStart = Outer, topEnd = Outer, bottomStart = Inner, bottomEnd = Inner)
    index == count - 1 -> RoundedCornerShape(topStart = Inner, topEnd = Inner, bottomStart = Outer, bottomEnd = Outer)
    else -> RoundedCornerShape(Inner)
}

/** Translucent row colour so the category wash glows through. */
@Composable
fun groupContainerColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (isDarkScheme) 0.78f else 0.86f)

/** One row of a group, built on [ListItem]. */
@Composable
fun GroupedListItem(
    index: Int,
    count: Int,
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    containerColor: Color = groupContainerColor(),
) {
    val shape = groupShape(index, count)
    val item = @Composable {
        ListItem(
            headlineContent = headline,
            supportingContent = supporting,
            leadingContent = leading,
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = containerColor, modifier = modifier) { item() }
    } else {
        Surface(shape = shape, color = containerColor, modifier = modifier) { item() }
    }
    if (index < count - 1) Spacer(Modifier.height(2.dp))
}

/** A titled group of rows. */
@Composable
fun GroupedSection(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        if (title != null) SectionTitle(title)
        content()
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}
