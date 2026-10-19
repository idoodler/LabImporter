package dev.idoodler.labimporter.ui.history

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.ui.components.CategoryDot
import dev.idoodler.labimporter.ui.components.RangeBadge
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import dev.idoodler.labimporter.ui.theme.harmonize
import java.time.format.FormatStyle

/** One saved report: its values, plus edit / share as CDA / delete. */
@Composable
fun ReportDetailScreen(
    report: LabReport,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    exportCda: () -> Uri,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.share_cda)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formatDate(report.date, FormatStyle.LONG)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                    IconButton(onClick = {
                        val uri = exportCda()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/xml"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    }) { Icon(Icons.Default.Share, stringResource(R.string.share_cda)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            pluralStringResource(R.plurals.value_count, report.entries.size, report.entries.size),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        )
                        if (report.authorName.isNotBlank()) Text(report.authorName, style = MaterialTheme.typography.bodyLarge)
                        if (report.patientName.isNotBlank()) {
                            Text(
                                stringResource(R.string.patient_label, report.patientName),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            items(report.entries, key = { it.id }) { entry ->
                ListItem(
                    leadingContent = { CategoryDot(MaterialTheme.colorScheme.harmonize(Color(LabCategory.forCode(entry.code).argb))) },
                    headlineContent = { Text(entry.resolvedName) },
                    supportingContent = { Text(entry.code) },
                    trailingContent = {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(
                                (entry.numericValue?.let(::formatNumber) ?: entry.displayValue) + " " + entry.unit,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            RangeBadge(LabMapping.rangeStatus(entry.numericValue, entry.code))
                        }
                    },
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
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

@Preview(name = "Detail", widthDp = 400, heightDp = 700)
@Composable
private fun ReportDetailPreview() {
    LabImporterTheme(dynamicColor = false) { ReportDetailScreen(PreviewSamples.reports.first(), {}, {}, {}, { Uri.EMPTY }) }
}

@Preview(name = "Detail – dark", widthDp = 400, heightDp = 700, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReportDetailDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        ReportDetailScreen(PreviewSamples.reports.last(), {}, {}, {}, { Uri.EMPTY })
    }
}
