package dev.idoodler.labimporter.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.GroupedListItem
import dev.idoodler.labimporter.ui.design.MorphingShape
import dev.idoodler.labimporter.ui.design.SectionTitle
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.StatTile
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.dominantColors
import dev.idoodler.labimporter.ui.design.welcomePalette
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.design.washTopBarColors
import dev.idoodler.labimporter.ui.design.readableListPadding
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import java.time.format.FormatStyle

/** Saved reports, newest first, grouped by year under a summary card. */
@Composable
fun HistoryScreen(
    reports: List<LabReport>,
    onOpenReport: (LabReport) -> Unit,
    modifier: Modifier = Modifier,
    /** Null at the root of a navigation-rail section (wide windows): no back arrow. */
    onBack: (() -> Unit)? = null,
    onExportPdf: () -> Unit = {},
    onDeleteReports: (Set<String>) -> Unit = {},
    onSelectionModeChange: (Boolean) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Selecting several reports to delete (iOS edit mode); Delete All is one tap away.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingDelete by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val setSelecting = { value: Boolean ->
        selecting = value
        if (!value) selected = emptyList()
        onSelectionModeChange(value)
    }
    // Drop selections that no longer exist, and leave selection once nothing is left.
    LaunchedEffect(reports) {
        selected = selected.filter { id -> reports.any { it.id == id } }
        if (reports.isEmpty() && selecting) setSelecting(false)
    }
    BackHandler(enabled = selecting) { setSelecting(false) }
    val byYear = remember(reports) { reports.groupBy { it.date.year } }
    val allCategories = remember(reports) { reports.flatMap { r -> r.entries.map { LabCategory.forCode(it.code) } } }
    Box(modifier.fillMaxSize()) {
        CategoryWash(if (reports.isEmpty()) welcomePalette else dominantColors(allCategories), animated = reports.isEmpty())
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        Text(
                            if (selecting && selected.isNotEmpty()) pluralStringResource(R.plurals.selected_count, selected.size, selected.size) else stringResource(R.string.history_title),
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        if (selecting) {
                            IconButton(onClick = { setSelecting(false) }) { Icon(Icons.Rounded.Close, stringResource(R.string.done)) }
                        } else {
                            onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } }
                        }
                    },
                    actions = {
                        if (selecting) {
                            TextButton(
                                onClick = { pendingDelete = reports.map { it.id } },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text(stringResource(R.string.delete_all)) }
                        } else if (reports.isNotEmpty()) {
                            IconButton(onClick = onExportPdf) { Icon(Icons.Rounded.PictureAsPdf, stringResource(R.string.pdf_export)) }
                            IconButton(onClick = { setSelecting(true) }) { Icon(Icons.Rounded.Checklist, stringResource(R.string.edit)) }
                        }
                    },
                    colors = washTopBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                if (selecting) {
                    val allSelected = selected.size == reports.size
                    BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        TextButton(onClick = { selected = if (allSelected) emptyList() else reports.map { it.id } }) {
                            Text(stringResource(if (allSelected) R.string.deselect_all else R.string.select_all))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { pendingDelete = selected },
                            enabled = selected.isNotEmpty(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            },
        ) { padding ->
            if (reports.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    MorphingShape(welcomePalette, 140.dp) {
                        Icon(Icons.Rounded.History, null, Modifier.size(56.dp), tint = Color.White)
                    }
                    Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.headlineSmallEmphasized, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.history_empty_message), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Scaffold
            }
            LazyColumn(
                contentPadding = readableListPadding(top = padding.calculateTopPadding(), bottom = 112.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "summary") { SummaryCard(reports, allCategories.distinct().size) }
                byYear.forEach { (year, items) ->
                    item(key = "y$year") { SectionTitle(year.toString()) }
                    itemsIndexed(items, key = { _, r -> r.id }) { index, report ->
                        val checked = report.id in selected
                        ReportRow(
                            report, index, items.size,
                            selection = if (selecting) checked else null,
                            onClick = {
                                if (selecting) {
                                    selected = if (checked) selected - report.id else selected + report.id
                                } else {
                                    onOpenReport(report)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    if (pendingDelete.isNotEmpty()) {
        val count = pendingDelete.size
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(stringResource(if (count == 1) R.string.delete_report_title else R.string.delete_reports_title)) },
            text = { Text(pluralStringResource(R.plurals.delete_reports_message, count, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteReports(pendingDelete.toSet())
                        pendingDelete = emptyList()
                        setSelecting(false)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = emptyList() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SummaryCard(reports: List<LabReport>, categories: Int) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.background(
                Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = 0.9f), scheme.tertiaryContainer.copy(alpha = 0.9f))),
            ).padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatTile(reports.size.toString(), stringResource(R.string.stat_reports))
                StatTile(reports.sumOf { it.entries.size }.toString(), stringResource(R.string.stat_values))
                StatTile(categories.toString(), stringResource(R.string.stat_categories))
            }
            reports.maxByOrNull { it.date }?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                    Icon(Icons.Rounded.History, null, Modifier.size(16.dp), tint = scheme.onPrimaryContainer)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.last_updated, formatDate(it.date)), style = MaterialTheme.typography.labelLarge,
                        color = scheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportRow(report: LabReport, index: Int, count: Int, onClick: () -> Unit, selection: Boolean? = null) {
    val categories = remember(report) { report.entries.map { LabCategory.forCode(it.code) } }
    val dominant = report.dominantCategory ?: LabCategory.OTHER
    GroupedListItem(
        index, count,
        onClick = onClick,
        leading = {
            ShapeBadge(
                MaterialShapes.Cookie9Sided, dominant.color, Icons.Rounded.Description, size = 48.dp,
                brush = Brush.linearGradient(listOf(lerp(dominant.color, Color.White, 0.25f), dominant.color)),
            )
        },
        headline = { Text(formatDate(report.date, FormatStyle.LONG), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        supporting = {
            Column {
                val who = listOf(report.patientName, report.authorName).filter { it.isNotBlank() }.joinToString(" · ")
                if (who.isNotEmpty()) Text(who, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    DotStack(dominantColors(categories, 5))
                    Spacer(Modifier.width(8.dp))
                    Text(pluralStringResource(R.plurals.value_count, report.entries.size, report.entries.size), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        trailing = {
            // In selection mode the row toggles; its checkbox reads out the state.
            if (selection != null) Checkbox(checked = selection, onCheckedChange = null) else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        },
    )
}

/** Overlapping category dots, like the iOS report rows. */
@Composable
fun DotStack(colors: List<Color>) {
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    Box {
        colors.forEachIndexed { i, color ->
            Box(
                Modifier.offset(x = (i * 9).dp).size(14.dp).border(2.dp, ring, CircleShape).padding(1.dp).background(color, CircleShape),
            )
        }
        Spacer(Modifier.width((14 + (colors.size - 1).coerceAtLeast(0) * 9).dp))
    }
}

@Preview(name = "Populated", widthDp = 400, heightDp = 800)
@Composable
private fun HistoryPreview() {
    LabImporterTheme(dynamicColor = false) { HistoryScreen(PreviewSamples.reports, {}, onBack = {}) }
}

@Preview(name = "Dark", widthDp = 400, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { HistoryScreen(PreviewSamples.reports, {}, onBack = {}) }
}

@Preview(name = "Single", widthDp = 400, heightDp = 800)
@Composable
private fun HistorySinglePreview() {
    LabImporterTheme(dynamicColor = false) { HistoryScreen(PreviewSamples.reports.take(1), {}, onBack = {}) }
}

@Preview(name = "Empty", widthDp = 400, heightDp = 800)
@Composable
private fun HistoryEmptyPreview() {
    LabImporterTheme(dynamicColor = false) { HistoryScreen(emptyList(), {}, onBack = {}) }
}

@Preview(name = "Rail section root (tablet)", widthDp = 840, heightDp = 800)
@Composable
private fun HistoryRailRootPreview() {
    LabImporterTheme(dynamicColor = false) { HistoryScreen(PreviewSamples.reports, {}) }
}
