package dev.idoodler.labimporter.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.PreparingStage
import dev.idoodler.labimporter.ai.gemma.GemmaModel
import dev.idoodler.labimporter.ui.components.ModelStatusCard
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.Dashboard
import dev.idoodler.labimporter.core.model.Metric
import dev.idoodler.labimporter.ui.components.RangeBadge
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.components.labelRes
import dev.idoodler.labimporter.ui.design.AreaChart
import dev.idoodler.labimporter.ui.design.CategoryBadge
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.MorphingShape
import dev.idoodler.labimporter.ui.design.StatTile
import dev.idoodler.labimporter.ui.design.TrendBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.tabular
import dev.idoodler.labimporter.ui.design.tintedSurface
import dev.idoodler.labimporter.ui.design.welcomePalette
import dev.idoodler.labimporter.ui.importflow.ImportActions
import dev.idoodler.labimporter.ui.importflow.ImportOptionCards
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.design.washTopBarColors
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import java.time.format.FormatStyle

/** The latest value of every tracked metric over a wash of their category colours. */
@Composable
fun DashboardScreen(
    reports: List<LabReport>,
    preferences: LabDisplayPreferences,
    onOpenMetric: (String) -> Unit,
    importActions: ImportActions,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    // Null where a navigation rail already offers Reports and Settings (wide windows).
    onOpenReports: (() -> Unit)? = {},
    onOpenSettings: (() -> Unit)? = {},
    patientName: String = "",
    /**
     * Cards above everything else while something needs the user's eye: the model download
     * ([dev.idoodler.labimporter.ui.components.ModelStatusCard]), Health Connect becoming
     * available ([HealthConnectOfferCard]).
     */
    notices: (@Composable () -> Unit)? = null,
) {
    // Shared rules (LabImporterCore `Dashboard`), identical on iOS.
    val metrics = remember(reports, preferences) { Dashboard.sorted(Dashboard.metrics(reports), preferences) }
    if (metrics.isEmpty() && isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CategoryWash(welcomePalette, intensity = 0.6f)
            LoadingIndicator(Modifier.size(64.dp))
        }
        return
    }
    if (metrics.isEmpty()) {
        DashboardEmpty(importActions, onOpenReports?.takeIf { reports.isNotEmpty() }, onOpenSettings, notices, modifier)
        return
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<LabCategory?>(null) }
    val categories = remember(metrics) { metrics.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value } }
    val filtered = remember(metrics, query, filter) {
        metrics.filter { (filter == null || it.category == filter) && (query.isBlank() || it.name.contains(query, true) || it.code.contains(query)) }
    }
    val wash = remember(metrics, filter) { filter?.let { listOf(it.color) } ?: Dashboard.backgroundCategories(metrics).map { it.color } }

    Box(modifier.fillMaxSize()) {
        CategoryWash(wash)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.dashboard_title), fontWeight = FontWeight.Bold) },
                    subtitle = { Text(pluralStringResource(R.plurals.metric_count, metrics.size, metrics.size)) },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(Icons.Rounded.Search, stringResource(R.string.search))
                        }
                        onOpenReports?.let { IconButton(onClick = it) { Icon(Icons.Rounded.History, stringResource(R.string.history_title)) } }
                        onOpenSettings?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Settings, stringResource(R.string.tab_settings)) } }
                    },
                    colors = washTopBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 164.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                notices?.let { content ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "notices") { content() }
                }
                // A personal welcome when the patient's name is known (iOS shows the same).
                val firstName = patientName.trim().substringBefore(' ')
                if (firstName.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "greeting") {
                        Text(
                            stringResource(R.string.welcome_back, firstName), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 2.dp).semantics { heading() },
                        )
                    }
                }
                if (categories.size > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                        CategoryFilterRow(categories.map { it.key to it.value }, filter) { filter = it }
                    }
                }
                if (searching) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_metrics)) },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                filtered.forEach { metric ->
                    val pinned = metric.code in preferences.pinnedSet
                    if (pinned || filtered.size == 1) {
                        item(key = metric.code, span = { GridItemSpan(maxLineSpan) }) {
                            HeroMetricCard(metric, pinned, onClick = { onOpenMetric(metric.code) }, modifier = Modifier.animateItem())
                        }
                    } else {
                        item(key = metric.code) {
                            MetricCard(metric, onClick = { onOpenMetric(metric.code) }, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterRow(categories: List<Pair<LabCategory, Int>>, selected: LabCategory?, onSelect: (LabCategory?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        item {
            FilterChip(
                selected = selected == null, onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.filter_all)) }, shape = CircleShape,
            )
        }
        items(categories, key = { it.first.name }) { (category, count) ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text("${stringResource(category.labelRes)}  $count") },
                leadingIcon = { Box(Modifier.size(10.dp).background(category.color, CircleShape)) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
                    selectedContainerColor = tintedSurface(category.color, 0.35f),
                ),
            )
        }
    }
}

/** Grid card: badge + name, the latest value, its trend and a gradient sparkline. */
@Composable
fun MetricCard(metric: Metric, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = metric.category.color
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tintedSurface(color, 0.12f).copy(alpha = 0.94f)),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CategoryBadge(metric.category, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    metric.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, minLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            ValueLine(metric, valueSize = 34)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    formatDate(metric.date, FormatStyle.MEDIUM), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                RangeBadge(metric.rangeStatus)
                metric.trend?.let { TrendBadge(it, color, Modifier.padding(start = 6.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            // Like iOS: the sparkline covers the year before the latest reading.
            AreaChart(
                metric.history.map { it.value }, color, Modifier.fillMaxWidth().height(64.dp),
                range = metric.range, strokeWidth = 2.5.dp, showPoints = metric.history.size <= 8,
            )
        }
    }
}

/** Full-width card for pinned metrics (or a lone one): bigger number, taller chart, min/max/avg. */
@Composable
fun HeroMetricCard(metric: Metric, pinned: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = metric.category.color
    val values = metric.history.map { it.value }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tintedSurface(color, 0.18f).copy(alpha = 0.94f)),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(metric.category, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(metric.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(metric.category.labelRes), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pinned) Icon(Icons.Rounded.PushPin, stringResource(R.string.pinned), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            ValueLine(metric, valueSize = 52)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDate(metric.date, FormatStyle.LONG), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                RangeBadge(metric.rangeStatus)
                metric.trend?.let { TrendBadge(it, color, Modifier.padding(start = 6.dp), size = 26.dp) }
            }
            AreaChart(
                values, color, Modifier.fillMaxWidth().height(132.dp).padding(top = 12.dp),
                range = metric.range, showPoints = values.size <= 12,
            )
            if (values.size > 1) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatTile(formatNumber(values.min()), stringResource(R.string.stat_min))
                    StatTile(formatNumber(values.average()), stringResource(R.string.stat_avg))
                    StatTile(formatNumber(values.max()), stringResource(R.string.stat_max))
                }
            }
        }
    }
}

@Composable
private fun ValueLine(metric: Metric, valueSize: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            formatNumber(metric.value),
            style = MaterialTheme.typography.displaySmallEmphasized.tabular().copy(fontSize = valueSize.sp, lineHeight = (valueSize + 4).sp),
            fontWeight = FontWeight.Bold,
            color = if (metric.rangeStatus?.isOutOfRange == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            metric.entry.unit, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = (valueSize / 7).dp),
        )
    }
}

/** First run: a living wash, morphing art and the four ways to import. */
@Composable
private fun DashboardEmpty(
    importActions: ImportActions,
    onOpenReports: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    notices: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        CategoryWash(welcomePalette, animated = true)
        Column(
            // safeDrawing, not just the status bar: in a freeform window (Waydroid, ChromeOS, DeX,
            // desktop mode) Android draws its caption bar over the top of the app.
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            MorphingShape(welcomePalette, 176.dp) {
                Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground_art), null, Modifier.size(104.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.import_title), style = MaterialTheme.typography.headlineLargeEmphasized,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.import_subtitle), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            notices?.let { content ->
                Box(Modifier.widthIn(max = 520.dp).padding(bottom = 16.dp)) { content() }
            }
            ImportOptionCards(importActions, Modifier.widthIn(max = 520.dp))
        }
        // Drawn after the full-screen scrolling column so it sits on top and receives taps.
        Row(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)).padding(4.dp)) {
            if (onOpenReports != null) {
                IconButton(onClick = onOpenReports) { Icon(Icons.Rounded.History, stringResource(R.string.history_title)) }
            }
            onOpenSettings?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Settings, stringResource(R.string.tab_settings)) } }
        }
    }
}

private val previewActions = ImportActions({}, {}, {}, {}, {})

@Preview(name = "Populated", widthDp = 400, heightDp = 900)
@Composable
private fun DashboardPopulatedPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(pinnedCodes = listOf("4548-4")), {}, previewActions)
    }
}

@Preview(name = "Dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions)
    }
}

@Preview(name = "Empty", widthDp = 400, heightDp = 900)
@Composable
private fun DashboardEmptyPreview() {
    LabImporterTheme(dynamicColor = false) { DashboardScreen(emptyList(), LabDisplayPreferences(), {}, previewActions) }
}

@Preview(name = "Empty – model downloading", widthDp = 400, heightDp = 1000)
@Composable
private fun DashboardEmptyDownloadingPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(emptyList(), LabDisplayPreferences(), {}, previewActions, notices = {
            ModelStatusCard(AiAvailability.Preparing(1_100_000_000, GemmaModel.SIZE_BYTES), AiEngine.GEMMA, {}, {})
        })
    }
}

@Preview(name = "Populated – model waiting for Wi-Fi", widthDp = 400, heightDp = 900)
@Composable
private fun DashboardWaitingPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions, notices = {
            ModelStatusCard(AiAvailability.Preparing(0, GemmaModel.SIZE_BYTES, PreparingStage.WAITING_FOR_WIFI), AiEngine.GEMMA, {}, {})
        })
    }
}

@Preview(name = "Populated – Health Connect available", widthDp = 400, heightDp = 900)
@Composable
private fun DashboardHealthConnectOfferPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions, notices = { HealthConnectOfferCard({}, {}) })
    }
}

@Preview(name = "Empty – dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardEmptyDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { DashboardScreen(emptyList(), LabDisplayPreferences(), {}, previewActions) }
}

@Preview(name = "Tablet", widthDp = 900, heightDp = 640)
@Composable
private fun DashboardTabletPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions)
    }
}
