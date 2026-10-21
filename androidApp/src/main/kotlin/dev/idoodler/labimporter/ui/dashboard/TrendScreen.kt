package dev.idoodler.labimporter.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.MetricPoint
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.core.model.Dashboard
import dev.idoodler.labimporter.core.model.TrendWindow
import dev.idoodler.labimporter.ui.components.RangeBadge
import dev.idoodler.labimporter.ui.components.ReferenceRangeDialog
import dev.idoodler.labimporter.ui.components.RenameValueDialog
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.components.labelRes
import dev.idoodler.labimporter.ui.design.AreaChart
import dev.idoodler.labimporter.ui.design.CategoryBadge
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.ConnectedChoices
import dev.idoodler.labimporter.ui.design.GroupedListItem
import dev.idoodler.labimporter.ui.design.SectionTitle
import dev.idoodler.labimporter.ui.design.StatTile
import dev.idoodler.labimporter.ui.design.TrendBadge
import dev.idoodler.labimporter.ui.design.accentFor
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.tabular
import dev.idoodler.labimporter.ui.design.tintedSurface
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.design.washTopBarColors
import dev.idoodler.labimporter.ui.design.readableListPadding
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import java.time.format.FormatStyle

/** The shared trend windows (same spans as iOS), labelled. */
private val TrendWindow.label: Int
    get() = when (this) {
        TrendWindow.MONTHS_3 -> R.string.window_3m
        TrendWindow.MONTHS_6 -> R.string.window_6m
        TrendWindow.YEAR_1 -> R.string.window_1y
        TrendWindow.ALL -> R.string.window_all
    }

/** One metric over time: a scrubbable chart, statistics and every reading. */
@Composable
fun TrendScreen(
    code: String,
    reports: List<LabReport>,
    preferences: LabDisplayPreferences,
    onUpdatePreferences: ((LabDisplayPreferences) -> LabDisplayPreferences) -> Unit,
    onBack: () -> Unit,
    onOpenTerm: (String) -> Unit = {},
) {
    // Hidden metrics stay reachable here (shared rule, as on iOS).
    val metric = remember(reports, preferences, code) {
        Dashboard.trendMetrics(reports, preferences).firstOrNull { it.code == code }
    } ?: return
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingRange by remember { mutableStateOf(false) }
    var window by rememberSaveable { mutableStateOf(TrendWindow.ALL) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val pinned = code in preferences.pinnedSet
    val range = preferences.referenceRange(code)
    val color = metric.category.color
    val term = remember(code) { LabCore.loinc?.term(code) }

    val points: List<MetricPoint> = remember(metric, window) { window.visible(metric.points) }
    val shown = selected?.let { points.getOrNull(it) } ?: points.last()
    val values = points.map { it.value }

    Box(Modifier.fillMaxSize()) {
        CategoryWash(listOf(color, accentFor(color), color), intensity = 1.2f)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(metric.name, maxLines = 2, fontWeight = FontWeight.Bold) },
                    subtitle = { Text(stringResource(metric.category.labelRes)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    },
                    actions = {
                        IconButton(onClick = { onUpdatePreferences { it.togglePinned(code) } }) {
                            Icon(
                                if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                                stringResource(if (pinned) R.string.unpin else R.string.pin),
                            )
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { menu = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reference_range)) },
                                leadingIcon = { Icon(Icons.Rounded.Straighten, null) },
                                onClick = { menu = false; editingRange = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(if (code in preferences.hiddenSet) R.string.show_on_dashboard else R.string.hide_from_dashboard))
                                },
                                leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                                onClick = { menu = false; onUpdatePreferences { it.toggleHidden(code) } },
                            )
                            LabMapping.loincUrl(code)?.let { url ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open_loinc)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                                    onClick = { menu = false; context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                                )
                            }
                        }
                    },
                    colors = washTopBarColors(tintedSurface(color, 0.12f)),
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
                        colors = CardDefaults.cardColors(containerColor = tintedSurface(color, 0.16f).copy(alpha = 0.94f)),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryBadge(metric.category, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    formatDate(shown.date, FormatStyle.LONG), style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                                )
                                RangeBadge(range?.status(shown.value))
                                if (selected == null) {
                                    metric.trend?.let { TrendBadge(it, color, Modifier.padding(start = 8.dp), 28.dp) }
                                }
                            }
                            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 12.dp)) {
                                Text(
                                    formatNumber(shown.value),
                                    style = MaterialTheme.typography.displayLargeEmphasized.tabular().copy(fontSize = 60.sp, lineHeight = 64.sp),
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    shown.unit, style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp),
                                )
                            }
                            range?.let {
                                Text(
                                    stringResource(R.string.reference_range_value, it.formatted(shown.unit, ::formatNumber)),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (metric.points.size > 1) {
                                WindowPicker(window) { window = it; selected = null }
                            }
                            AreaChart(
                                values, color, Modifier.fillMaxWidth().height(240.dp).padding(top = 16.dp),
                                range = range, yLabels = true,
                                xLabels = if (points.size > 1) formatDate(points.first().date, FormatStyle.MEDIUM) to formatDate(points.last().date, FormatStyle.MEDIUM) else null,
                                selectedIndex = selected,
                                onSelect = { selected = it },
                                formatValue = ::formatNumber,
                                description = chartDescription(metric.name, points),
                            )
                            if (values.size > 1) {
                                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    StatTile(formatNumber(values.min()), stringResource(R.string.stat_min))
                                    StatTile(formatNumber(values.average()), stringResource(R.string.stat_avg))
                                    StatTile(formatNumber(values.max()), stringResource(R.string.stat_max))
                                }
                            }
                        }
                    }
                }
                term?.let { term ->
                    item(key = "about") {
                        // The value's LOINC description, opening the full details (iOS ValueDescriptionCard).
                        val description = term.description?.takeIf { it.isNotBlank() } ?: term.englishName.takeIf { it != term.name }
                        Spacer(Modifier.height(12.dp))
                        GroupedListItem(
                            0, 1,
                            onClick = { onOpenTerm(code) },
                            headline = { Text(stringResource(R.string.about_this_value), fontWeight = FontWeight.SemiBold) },
                            supporting = {
                                Column {
                                    description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                    Text(
                                        "LOINC $code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            },
                            trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        )
                    }
                }
                item(key = "readings-title") { SectionTitle(stringResource(R.string.readings)) }
                val readings = metric.points.reversed()
                itemsIndexed(readings, key = { _, p -> p.reportId + p.date }) { index, point ->
                    GroupedListItem(
                        index, readings.size,
                        headline = {
                            Text(
                                "${formatNumber(point.value)} ${point.unit}",
                                style = MaterialTheme.typography.titleMedium.tabular(), fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supporting = { Text(formatDate(point.date, FormatStyle.LONG)) },
                        trailing = { RangeBadge(range?.status(point.value)) },
                    )
                }
            }
        }
    }

    if (renaming) RenameValueDialog(code, preferences, onUpdatePreferences) { renaming = false }
    if (editingRange) ReferenceRangeDialog(code, preferences, onUpdatePreferences) { editingRange = false }
}

/** TalkBack's summary of the chart: how many readings over which dates, the extremes and the latest. */
@Composable
private fun chartDescription(name: String, points: List<MetricPoint>): String? {
    if (points.size < 2) return null
    val withUnit = { value: Double -> listOf(formatNumber(value), points.last().unit).filter { it.isNotBlank() }.joinToString(" ") }
    return stringResource(
        R.string.chart_summary,
        name,
        pluralStringResource(R.plurals.chart_readings, points.size, points.size),
        formatDate(points.first().date, FormatStyle.MEDIUM),
        formatDate(points.last().date, FormatStyle.MEDIUM),
        withUnit(points.minOf { it.value }),
        withUnit(points.maxOf { it.value }),
        withUnit(points.last().value),
    )
}

/** The time window as a connected button group. */
@Composable
private fun WindowPicker(selected: TrendWindow, onSelect: (TrendWindow) -> Unit) {
    ConnectedChoices(TrendWindow.entries, selected, onSelect, { stringResource(it.label) }, Modifier.padding(top = 16.dp))
}

@Preview(name = "Trend", widthDp = 400, heightDp = 1000)
@Composable
private fun TrendPreview() {
    LabImporterTheme(dynamicColor = false) {
        TrendScreen(
            "4548-4", PreviewSamples.reports,
            LabDisplayPreferences(referenceRanges = mapOf("4548-4" to ReferenceRange(4.0, 6.0))), {}, {},
        )
    }
}

@Preview(name = "Trend – dark", widthDp = 400, heightDp = 1000, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TrendDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        TrendScreen("2160-0", PreviewSamples.reports, LabDisplayPreferences(), {}, {})
    }
}
