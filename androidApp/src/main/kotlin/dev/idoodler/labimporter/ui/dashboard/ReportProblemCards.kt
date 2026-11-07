package dev.idoodler.labimporter.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.groupContainerColor
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** The reports couldn't be read just now, which must never look like there being none. */
@Composable
fun ReportsLoadFailedCard(onTryAgain: () -> Unit, modifier: Modifier = Modifier) {
    ReportProblemCard(
        stringResource(R.string.reports_load_failed_title), stringResource(R.string.reports_load_failed_message), modifier,
    ) {
        Button(onClick = onTryAgain) { Text(stringResource(R.string.try_again)) }
    }
}

/** [count] saved reports can't be read. They're kept, so a later version can still read them. */
@Composable
fun UnreadableReportsCard(count: Int, modifier: Modifier = Modifier) {
    ReportProblemCard(
        stringResource(R.string.reports_unreadable_title), stringResource(R.string.reports_unreadable_message, count), modifier,
    )
}

@Composable
private fun ReportProblemCard(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Card(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = groupContainerColor()),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            ShapeBadge(MaterialShapes.Cookie9Sided, MaterialTheme.colorScheme.error, Icons.Rounded.ErrorOutline, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                action?.invoke()
            }
        }
    }
}

@Preview(name = "Load failed", widthDp = 400)
@Composable
private fun ReportsLoadFailedPreview() {
    LabImporterTheme(dynamicColor = false) { ReportsLoadFailedCard({}) }
}

@Preview(name = "Load failed – dark", widthDp = 400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReportsLoadFailedDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ReportsLoadFailedCard({}) }
}

@Preview(name = "Load failed – narrow, large text", widthDp = 320, fontScale = 1.5f)
@Composable
private fun ReportsLoadFailedLargeTextPreview() {
    LabImporterTheme(dynamicColor = false) { ReportsLoadFailedCard({}) }
}

@Preview(name = "Load failed – RTL", widthDp = 400, locale = "ar")
@Composable
private fun ReportsLoadFailedRtlPreview() {
    LabImporterTheme(dynamicColor = false) { ReportsLoadFailedCard({}) }
}

@Preview(name = "Unreadable reports", widthDp = 400)
@Composable
private fun UnreadableReportsPreview() {
    LabImporterTheme(dynamicColor = false) { UnreadableReportsCard(2) }
}

@Preview(name = "Unreadable reports – dark", widthDp = 400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UnreadableReportsDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { UnreadableReportsCard(1) }
}

@Preview(name = "Unreadable reports – narrow, large text", widthDp = 320, fontScale = 1.5f)
@Composable
private fun UnreadableReportsLargeTextPreview() {
    LabImporterTheme(dynamicColor = false) { UnreadableReportsCard(12) }
}
