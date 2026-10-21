package dev.idoodler.labimporter.ui.export

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.export.PdfPageFormat
import dev.idoodler.labimporter.core.export.PdfTimeRange
import dev.idoodler.labimporter.core.model.Dashboard
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.export.PdfColorMode
import dev.idoodler.labimporter.export.PdfOptions
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.ConnectedChoices
import dev.idoodler.labimporter.ui.design.GroupedListItem
import dev.idoodler.labimporter.ui.design.SectionTitle
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.dominantColors
import dev.idoodler.labimporter.ui.design.groupContainerColor
import dev.idoodler.labimporter.ui.design.groupShape
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.design.washTopBarColors
import dev.idoodler.labimporter.ui.design.readableListPadding
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Configures a PDF export — colour treatment, paper size, sections, trend
 * window and exactly which values appear (the iOS `PDFExportView`) — then
 * renders it and hands it to the share sheet.
 *
 * @param onGenerate renders the PDF; null when it couldn't be written.
 */
@Composable
fun PdfExportScreen(
    reports: List<LabReport>,
    preferences: LabDisplayPreferences,
    onGenerate: suspend (PdfOptions) -> Uri?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Every value with a numeric reading, in dashboard order — the same list iOS offers.
    val available = remember(reports, preferences) { Dashboard.trendMetrics(reports, preferences) }
    var options by rememberSaveable(stateSaver = OptionsSaver) {
        mutableStateOf(
            PdfOptions(
                selectedCodes = available.map { it.code }.toSet(),
                pageFormat = PdfPageFormat.defaultFor(Locale.getDefault(Locale.Category.FORMAT).country),
            ),
        )
    }
    var generating by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.pdf_export)
    val canGenerate = options.selectedCodes.isNotEmpty() &&
        (options.includeSummary || options.includeLatestResults || options.includeTrends)
    val generate = {
        generating = true
        scope.launch {
            val uri = onGenerate(options)
            generating = false
            if (uri == null) {
                failed = true
            } else {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, shareTitle))
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Box(modifier.fillMaxSize()) {
        CategoryWash(dominantColors(available.map { it.category }), intensity = 0.7f)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.pdf_export), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    },
                    actions = {
                        if (generating) {
                            LoadingIndicator(Modifier.padding(end = 12.dp).size(40.dp))
                        } else {
                            Button(onClick = { generate() }, enabled = canGenerate, modifier = Modifier.padding(end = 8.dp)) {
                                Icon(Icons.Rounded.PictureAsPdf, null, Modifier.padding(end = 6.dp).size(18.dp))
                                Text(stringResource(R.string.pdf_generate))
                            }
                        }
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
                item(key = "appearance") {
                    SectionTitle(stringResource(R.string.pdf_appearance))
                    ChoiceGroup(index = 0, count = 2, title = stringResource(R.string.pdf_color_mode)) {
                        ConnectedChoices(
                            PdfColorMode.entries, options.colorMode, { options = options.copy(colorMode = it) },
                            { stringResource(if (it == PdfColorMode.COLOR) R.string.pdf_color else R.string.pdf_monochrome) },
                        )
                    }
                    ChoiceGroup(index = 1, count = 2, title = stringResource(R.string.pdf_page_size)) {
                        ConnectedChoices(PdfPageFormat.entries, options.pageFormat, { options = options.copy(pageFormat = it) }, { stringResource(it.labelRes) })
                    }
                }
                item(key = "sections") {
                    SectionTitle(stringResource(R.string.pdf_sections))
                    SectionSwitch(0, stringResource(R.string.pdf_summary_page), options.includeSummary) { options = options.copy(includeSummary = it) }
                    SectionSwitch(1, stringResource(R.string.pdf_latest_results), options.includeLatestResults) { options = options.copy(includeLatestResults = it) }
                    SectionSwitch(2, stringResource(R.string.pdf_trend_charts), options.includeTrends) { options = options.copy(includeTrends = it) }
                    Footer(stringResource(R.string.pdf_trend_footer))
                }
                if (options.includeTrends) {
                    item(key = "range") {
                        SectionTitle(stringResource(R.string.pdf_trend_time_range))
                        ChoiceGroup(index = 0, count = 1, title = null) {
                            ConnectedChoices(PdfTimeRange.entries, options.timeRange, { options = options.copy(timeRange = it) }, { stringResource(it.shortLabelRes) })
                        }
                    }
                }
                item(key = "values-title") {
                    val allSelected = available.isNotEmpty() && options.selectedCodes.size == available.size
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.pdf_included_values), Modifier.weight(1f))
                        TextButton(onClick = {
                            options = options.copy(selectedCodes = if (allSelected) emptySet() else available.map { it.code }.toSet())
                        }) { Text(stringResource(if (allSelected) R.string.deselect_all else R.string.select_all)) }
                    }
                }
                itemsIndexed(available, key = { _, metric -> "value-${metric.code}" }) { index, metric ->
                    val checked = metric.code in options.selectedCodes
                    val toggle = { value: Boolean ->
                        options = options.copy(selectedCodes = if (value) options.selectedCodes + metric.code else options.selectedCodes - metric.code)
                    }
                    GroupedListItem(
                        index, available.size,
                        leading = { Box(Modifier.size(10.dp).background(metric.category.color, CircleShape)) },
                        headline = { Text(metric.name) },
                        trailing = { Checkbox(checked = checked, onCheckedChange = null) },
                        modifier = Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = toggle),
                    )
                }
                item(key = "values-footer") {
                    Footer(
                        if (options.selectedCodes.isEmpty()) stringResource(R.string.pdf_select_one)
                        else stringResource(R.string.pdf_selected_count, options.selectedCodes.size, available.size),
                    )
                }
            }
        }
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            title = { Text(stringResource(R.string.pdf_export_error)) },
            text = { Text(stringResource(R.string.pdf_failed)) },
            confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** A grouped row holding a titled picker. */
@Composable
private fun ChoiceGroup(index: Int, count: Int, title: String?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = if (index < count - 1) 2.dp else 0.dp)
            .background(groupContainerColor(), groupShape(index, count)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun SectionSwitch(index: Int, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    GroupedListItem(
        index, 3,
        headline = { Text(title) },
        trailing = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private val PdfPageFormat.labelRes: Int
    get() = when (this) {
        PdfPageFormat.A4 -> R.string.pdf_page_a4
        PdfPageFormat.US_LETTER -> R.string.pdf_page_letter
        PdfPageFormat.LEGAL -> R.string.pdf_page_legal
    }

private val PdfTimeRange.shortLabelRes: Int
    get() = when (this) {
        PdfTimeRange.MONTHS_3 -> R.string.window_3m
        PdfTimeRange.MONTHS_6 -> R.string.window_6m
        PdfTimeRange.YEAR_1 -> R.string.window_1y
        PdfTimeRange.YEAR_2 -> R.string.window_2y
        PdfTimeRange.ALL -> R.string.window_all
    }

/** Keeps the choices across rotation and process death. */
private val OptionsSaver = Saver<PdfOptions, List<Any>>(
    save = {
        listOf(
            ArrayList(it.selectedCodes), it.timeRange.name, it.colorMode.name, it.pageFormat.name,
            it.includeSummary, it.includeLatestResults, it.includeTrends,
        )
    },
    restore = {
        @Suppress("UNCHECKED_CAST")
        PdfOptions(
            selectedCodes = (it[0] as List<String>).toSet(),
            timeRange = PdfTimeRange.valueOf(it[1] as String),
            colorMode = PdfColorMode.valueOf(it[2] as String),
            pageFormat = PdfPageFormat.valueOf(it[3] as String),
            includeSummary = it[4] as Boolean,
            includeLatestResults = it[5] as Boolean,
            includeTrends = it[6] as Boolean,
        )
    },
)

@Preview(name = "Populated", widthDp = 400, heightDp = 1200)
@Composable
private fun PdfExportPreview() {
    LabImporterTheme(dynamicColor = false) { PdfExportScreen(PreviewSamples.reports, LabDisplayPreferences(), { null }, {}) }
}

@Preview(name = "Dark", widthDp = 400, heightDp = 1200, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PdfExportDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { PdfExportScreen(PreviewSamples.reports, LabDisplayPreferences(), { null }, {}) }
}

@Preview(name = "Single report", widthDp = 400, heightDp = 1000)
@Composable
private fun PdfExportSinglePreview() {
    LabImporterTheme(dynamicColor = false) { PdfExportScreen(PreviewSamples.reports.take(1), LabDisplayPreferences(), { null }, {}) }
}

@Preview(name = "RTL", widthDp = 400, heightDp = 1200, locale = "ar")
@Composable
private fun PdfExportRtlPreview() {
    LabImporterTheme(dynamicColor = false) { PdfExportScreen(PreviewSamples.reports, LabDisplayPreferences(), { null }, {}) }
}
