package dev.idoodler.labimporter.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.MetricSeries
import dev.idoodler.labimporter.core.model.metricSeries
import dev.idoodler.labimporter.ui.components.CategoryDot
import dev.idoodler.labimporter.ui.components.EmptyState
import dev.idoodler.labimporter.ui.components.RangeBadge
import dev.idoodler.labimporter.ui.components.TrendChart
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import dev.idoodler.labimporter.ui.theme.harmonize
import java.time.format.FormatStyle

/** The latest value of every tracked metric, as adaptive cards with sparklines. */
@Composable
fun DashboardScreen(
    reports: List<LabReport>,
    preferences: LabDisplayPreferences,
    onOpenMetric: (String) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val series = remember(reports, preferences) { reports.metricSeries(preferences) }
    val filtered = remember(series, query) {
        if (query.isBlank()) series else series.filter { it.name.contains(query, ignoreCase = true) || it.code.contains(query) }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = { if (series.isNotEmpty()) ImportFab(onImport) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tab_dashboard)) },
                subtitle = if (series.isNotEmpty()) {
                    { Text(pluralStringResource(R.plurals.metric_count, series.size, series.size)) }
                } else null,
                actions = {
                    if (series.isNotEmpty()) {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(Icons.Default.Search, stringResource(R.string.search))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (series.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.dashboard_empty_title),
                message = stringResource(R.string.dashboard_empty_message),
                modifier = Modifier.padding(padding),
                action = {
                    androidx.compose.material3.Button(onClick = onImport) { Text(stringResource(R.string.import_report)) }
                },
            )
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (searching) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_metrics)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(filtered, key = { it.code }) { metric ->
                MetricCard(metric, pinned = metric.code in preferences.pinnedSet, onClick = { onOpenMetric(metric.code) })
            }
        }
    }
}

/** The primary action on Dashboard and History. */
@Composable
fun ImportFab(onClick: () -> Unit) {
    val label = stringResource(R.string.import_report)
    androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = onClick,
        // The extended FAB's label isn't exposed to accessibility services in
        // material3 1.5 betas; describe it explicitly so TalkBack can read it.
        modifier = Modifier.semantics { contentDescription = label },
        icon = { Icon(Icons.Default.Add, null) },
        text = { Text(stringResource(R.string.import_report)) },
    )
}

@Composable
fun MetricCard(metric: MetricSeries, pinned: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.harmonize(Color(metric.category.argb))
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(color)
                Spacer(Modifier.width(8.dp))
                Text(
                    metric.name, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (pinned) Icon(Icons.Default.PushPin, stringResource(R.string.pinned), Modifier.padding(start = 4.dp).height(16.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatNumber(metric.latest.value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Text(
                    metric.latest.unit, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                TrendIcon(metric)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDate(metric.latest.date, FormatStyle.MEDIUM), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                RangeBadge(metric.rangeStatus)
            }
            if (metric.points.size > 1) {
                Spacer(Modifier.height(8.dp))
                TrendChart(
                    metric.points.map { it.value }, color, Modifier.fillMaxWidth().height(44.dp),
                    strokeWidth = 2.dp, showPoints = false,
                )
            }
        }
    }
}

@Composable
private fun TrendIcon(metric: MetricSeries) {
    val previous = metric.previous ?: return
    val delta = metric.latest.value - previous.value
    val icon = when {
        delta > 1e-9 -> Icons.AutoMirrored.Filled.TrendingUp
        delta < -1e-9 -> Icons.AutoMirrored.Filled.TrendingDown
        else -> Icons.AutoMirrored.Filled.TrendingFlat
    }
    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Preview(name = "Populated", widthDp = 400, heightDp = 800)
@Composable
private fun DashboardPopulatedPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(pinnedCodes = listOf("4548-4")), {}, {})
    }
}

@Preview(name = "Dark", widthDp = 400, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, {})
    }
}

@Preview(name = "Empty", widthDp = 400, heightDp = 800)
@Composable
private fun DashboardEmptyPreview() {
    LabImporterTheme(dynamicColor = false) { DashboardScreen(emptyList(), LabDisplayPreferences(), {}, {}) }
}

@Preview(name = "Tablet", widthDp = 900, heightDp = 640)
@Composable
private fun DashboardTabletPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, {})
    }
}
