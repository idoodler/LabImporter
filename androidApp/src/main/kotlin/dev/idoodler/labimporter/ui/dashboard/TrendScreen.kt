package dev.idoodler.labimporter.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.core.model.metricSeries
import dev.idoodler.labimporter.ui.components.RangeBadge
import dev.idoodler.labimporter.ui.components.TrendChart
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.components.labelRes
import dev.idoodler.labimporter.ui.components.parseNumberInput
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import dev.idoodler.labimporter.ui.theme.harmonize

/** One metric over time: chart, readings, and per-metric display settings. */
@Composable
fun TrendScreen(
    code: String,
    reports: List<LabReport>,
    preferences: LabDisplayPreferences,
    onUpdatePreferences: ((LabDisplayPreferences) -> LabDisplayPreferences) -> Unit,
    onBack: () -> Unit,
) {
    val series = remember(reports, preferences, code) {
        reports.metricSeries(preferences, includeHidden = true).firstOrNull { it.code == code }
    }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingRange by remember { mutableStateOf(false) }
    val pinned = code in preferences.pinnedSet
    val range = preferences.referenceRange(code)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(series?.name ?: code, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { onUpdatePreferences { it.togglePinned(code) } }) {
                        Icon(
                            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            stringResource(if (pinned) R.string.unpin else R.string.pin),
                        )
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reference_range)) },
                            leadingIcon = { Icon(Icons.Default.Straighten, null) },
                            onClick = { menu = false; editingRange = true },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(if (code in preferences.hiddenSet) R.string.show_on_dashboard else R.string.hide_from_dashboard))
                            },
                            leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                            onClick = { menu = false; onUpdatePreferences { it.toggleHidden(code) } },
                        )
                        LabMapping.loincUrl(code)?.let { url ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_loinc)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                onClick = {
                                    menu = false
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (series == null) return@Scaffold
        val color = MaterialTheme.colorScheme.harmonize(Color(series.category.argb))
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(series.category.labelRes), style = MaterialTheme.typography.labelLarge, color = color)
                        Row {
                            Text(
                                "${formatNumber(series.latest.value)} ${series.latest.unit}",
                                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            formatDate(series.latest.date) + (range?.let { " · " + it.formatted(series.latest.unit, ::formatNumber) } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TrendChart(
                            series.points.map { it.value }, color,
                            Modifier.fillMaxWidth().height(200.dp).padding(top = 16.dp), range = range,
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.readings), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            items(series.points.reversed(), key = { it.reportId + it.date }) { point ->
                ListItem(
                    headlineContent = { Text("${formatNumber(point.value)} ${point.unit}") },
                    supportingContent = { Text(formatDate(point.date)) },
                    trailingContent = { RangeBadge(range?.status(point.value)) },
                )
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(preferences.nickname(code).orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text(LabMapping.catalogName(code)) },
                    supportingText = { Text(stringResource(R.string.rename_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = { onUpdatePreferences { it.withNickname(text, code) }; renaming = false }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { onUpdatePreferences { it.withNickname(null, code) }; renaming = false }) {
                    Text(stringResource(R.string.reset))
                }
            },
        )
    }

    if (editingRange) {
        var low by remember { mutableStateOf(range?.low?.let(::formatNumber).orEmpty()) }
        var high by remember { mutableStateOf(range?.high?.let(::formatNumber).orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingRange = false },
            title = { Text(stringResource(R.string.reference_range)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.reference_range_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        low, { low = it }, label = { Text(stringResource(R.string.range_lower)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        high, { high = it }, label = { Text(stringResource(R.string.range_upper)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdatePreferences { it.withReferenceRange(ReferenceRange(parseNumberInput(low), parseNumberInput(high)), code) }
                    editingRange = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { onUpdatePreferences { it.withReferenceRange(null, code) }; editingRange = false }) {
                    Text(stringResource(R.string.reset))
                }
            },
        )
    }
}

@Preview(name = "Trend", widthDp = 400, heightDp = 800)
@Composable
private fun TrendPreview() {
    LabImporterTheme(dynamicColor = false) {
        TrendScreen(
            "4548-4", PreviewSamples.reports,
            LabDisplayPreferences(referenceRanges = mapOf("4548-4" to ReferenceRange(4.0, 6.0))), {}, {},
        )
    }
}

@Preview(name = "Trend – dark", widthDp = 400, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TrendDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        TrendScreen("2160-0", PreviewSamples.reports, LabDisplayPreferences(), {}, {})
    }
}
