package dev.idoodler.labimporter.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.ui.components.EmptyState
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import dev.idoodler.labimporter.ui.theme.harmonize
import java.time.format.FormatStyle

/** Saved reports, newest first, grouped by year. */
@Composable
fun HistoryScreen(
    reports: List<LabReport>,
    onOpenReport: (LabReport) -> Unit,
    modifier: Modifier = Modifier,
    onImport: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val byYear = remember(reports) { reports.groupBy { it.date.year } }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = { dev.idoodler.labimporter.ui.dashboard.ImportFab(onImport) },
        topBar = {
            LargeFlexibleTopAppBar(title = { Text(stringResource(R.string.tab_history)) }, scrollBehavior = scrollBehavior)
        },
    ) { padding ->
        if (reports.isEmpty()) {
            EmptyState(
                stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_message),
                Modifier.padding(padding), icon = Icons.Outlined.History,
            )
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            byYear.forEach { (year, items) ->
                item(key = "y$year") {
                    Text(
                        year.toString(), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(items, key = { it.id }) { report -> ReportRow(report, onClick = { onOpenReport(report) }) }
            }
        }
    }
}

@Composable
private fun ReportRow(report: LabReport, onClick: () -> Unit) {
    val tint = report.dominantCategory?.let { MaterialTheme.colorScheme.harmonize(Color(it.argb)) } ?: MaterialTheme.colorScheme.primary
    ListItem(
        modifier = Modifier.padding(horizontal = 4.dp).clickable(onClick = onClick),
        leadingContent = {
            Surface(shape = CircleShape, color = tint.copy(alpha = 0.18f)) {
                Icon(Icons.Outlined.Description, null, tint = tint, modifier = Modifier.padding(10.dp).size(24.dp))
            }
        },
        headlineContent = { Text(formatDate(report.date, FormatStyle.LONG)) },
        supportingContent = {
            val count = pluralStringResource(R.plurals.value_count, report.entries.size, report.entries.size)
            Text(if (report.authorName.isBlank()) count else "$count · ${report.authorName}")
        },
    )
}

@Preview(name = "Populated", widthDp = 400, heightDp = 700)
@Composable
private fun HistoryPreview() {
    LabImporterTheme(dynamicColor = false) { HistoryScreen(PreviewSamples.reports, {}) }
}

@Preview(name = "Dark", widthDp = 400, heightDp = 700, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { HistoryScreen(PreviewSamples.reports.take(1), {}) }
}

@Preview(name = "Empty", widthDp = 400, heightDp = 700)
@Composable
private fun HistoryEmptyPreview() {
    LabImporterTheme(dynamicColor = false) { HistoryScreen(emptyList(), {}) }
}
