package dev.idoodler.labimporter.ui.history

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.ui.components.RangeBadge
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.components.labelRes
import dev.idoodler.labimporter.ui.design.CategoryBadge
import dev.idoodler.labimporter.ui.design.CategoryChip
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.GroupedListItem
import dev.idoodler.labimporter.ui.design.SectionTitle
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.dominantColors
import dev.idoodler.labimporter.ui.design.tabular
import dev.idoodler.labimporter.ui.design.tintedSurface
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.design.washTopBarColors
import dev.idoodler.labimporter.ui.design.readableListPadding
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import java.time.format.FormatStyle

/** One saved report: a summary hero, values grouped by category, edit / share as CDA / delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportDetailScreen(
    report: LabReport,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    exportCda: () -> Uri,
    onOpenTerm: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.share_cda)
    val grouped = remember(report) {
        report.entries.groupBy { LabCategory.forCode(it.code) }.entries.sortedBy { it.key.ordinal }
    }
    val colors = remember(report) { dominantColors(report.entries.map { LabCategory.forCode(it.code) }) }
    val dominant = report.dominantCategory ?: LabCategory.OTHER

    // The bar turns solid once the list scrolls under it.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Box(Modifier.fillMaxSize()) {
        CategoryWash(colors)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    },
                    actions = {
                        IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, stringResource(R.string.edit)) }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/xml"
                                putExtra(Intent.EXTRA_STREAM, exportCda())
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        }) { Icon(Icons.Rounded.Share, shareTitle) }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
                    },
                    colors = washTopBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = readableListPadding(top = padding.calculateTopPadding(), bottom = 40.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "hero") {
                    Card(
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = tintedSurface(dominant.color, 0.16f).copy(alpha = 0.94f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ShapeBadge(
                                    MaterialShapes.Cookie9Sided, dominant.color, Icons.Rounded.Description, size = 60.dp,
                                    brush = Brush.linearGradient(listOf(lerp(dominant.color, Color.White, 0.25f), dominant.color)),
                                )
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(formatDate(report.date, FormatStyle.LONG), style = MaterialTheme.typography.headlineSmallEmphasized, fontWeight = FontWeight.Bold)
                                    Text(
                                        pluralStringResource(R.plurals.value_count, report.entries.size, report.entries.size),
                                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (report.authorName.isNotBlank() || report.patientName.isNotBlank()) {
                                Column(Modifier.padding(top = 14.dp)) {
                                    if (report.authorName.isNotBlank()) Text(report.authorName, style = MaterialTheme.typography.titleSmall)
                                    if (report.patientName.isNotBlank()) {
                                        Text(
                                            stringResource(R.string.patient_label, report.patientName),
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            FlowRow(
                                Modifier.padding(top = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                grouped.forEach { (category, entries) -> CategoryChip(category, entries.size) }
                            }
                        }
                    }
                }
                grouped.forEach { (category, entries) ->
                    item(key = "h-${category.name}") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                            CategoryBadge(category, size = 24.dp)
                            SectionTitle(stringResource(category.labelRes), Modifier.padding(bottom = 0.dp))
                        }
                    }
                    entries.forEachIndexed { index, entry ->
                        item(key = entry.id) {
                            // A value the catalog knows opens its LOINC details, as on iOS.
                            val known = remember(entry.code) { LabCore.loinc?.term(entry.code) != null }
                            GroupedListItem(
                                index, entries.size,
                                onClick = if (known) ({ onOpenTerm(entry.code) }) else null,
                                headline = { Text(entry.resolvedName, style = MaterialTheme.typography.titleSmall) },
                                supporting = { Text("LOINC ${entry.code}") },
                                trailing = {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            (entry.numericValue?.let(::formatNumber) ?: entry.displayValue) + " " + entry.unit,
                                            style = MaterialTheme.typography.titleMedium.tabular(), fontWeight = FontWeight.SemiBold,
                                        )
                                        RangeBadge(LabMapping.rangeStatus(entry.numericValue, entry.code))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(stringResource(R.string.delete_report_title)) },
            text = { Text(stringResource(R.string.delete_report_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Preview(name = "Detail", widthDp = 400, heightDp = 900)
@Composable
private fun ReportDetailPreview() {
    LabImporterTheme(dynamicColor = false) { ReportDetailScreen(PreviewSamples.reports.first(), {}, {}, {}, { Uri.EMPTY }) }
}

@Preview(name = "Detail – dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReportDetailDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        ReportDetailScreen(PreviewSamples.reports.last(), {}, {}, {}, { Uri.EMPTY })
    }
}
