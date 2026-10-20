package dev.idoodler.labimporter.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.RangeStatus
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** "High"/"Low" pill for out-of-range values; nothing for in-range or no range. */
@Composable
fun RangeBadge(status: RangeStatus?, modifier: Modifier = Modifier) {
    if (status == null || !status.isOutOfRange) return
    val label = stringResource(if (status == RangeStatus.HIGH) R.string.range_high else R.string.range_low)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        shape = CircleShape,
    ) {
        Row(Modifier.padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (status == RangeStatus.HIGH) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Preview(name = "Range badges")
@Composable
private fun RangeBadgePreview() {
    LabImporterTheme(dynamicColor = false) {
        Surface { Row(Modifier.padding(8.dp)) { RangeBadge(RangeStatus.HIGH); RangeBadge(RangeStatus.LOW, Modifier.padding(start = 6.dp)) } }
    }
}

@Preview(name = "Range badges – dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RangeBadgeDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        Surface { Row(Modifier.padding(8.dp)) { RangeBadge(RangeStatus.HIGH); RangeBadge(RangeStatus.LOW, Modifier.padding(start = 6.dp)) } }
    }
}
