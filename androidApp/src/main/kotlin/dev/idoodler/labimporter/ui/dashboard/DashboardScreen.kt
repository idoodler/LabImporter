package dev.idoodler.labimporter.ui.dashboard

import dev.idoodler.labimporter.ui.design.isLargeText
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idoodler.labimporter.data.ReportsLoadProblem
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.AiProblem
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
    /** The reports couldn't be read: what's here may be out of date, and none doesn't mean there are none. */
    loadProblem: ReportsLoadProblem? = null,
    loadProblemActions: LoadProblemActions = LoadProblemActions(),
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
    val metrics = remember(reports, preferences) { Dashboard.sorted(Dashboard.dashboardMetrics(reports), preferences) }
    if (metrics.isEmpty() && isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CategoryWash(welcomePalette, intensity = 0.6f)
            LoadingIndicator(Modifier.size(64.dp))
        }
        return
    }
    if (metrics.isEmpty() && loadProblem != null) {
        DashboardLoadFailed(loadProblem, loadProblemActions, onOpenSettings, notices, modifier)
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
    // A personal welcome when the patient's name is known (iOS shows the same).
    val firstName = patientName.trim().substringBefore(' ')
    val gridState = rememberLazyGridState()
    KeepTopInView(
        gridState,
        listOfNotNull(
            "loadFailed".takeIf { loadProblem != null }, "notices".takeIf { notices != null }, "greeting".takeIf { firstName.isNotEmpty() },
            "filters".takeIf { categories.size > 1 }, "search".takeIf { searching },
        ),
    )

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
                state = gridState,
                columns = GridCells.Adaptive(minSize = if (isLargeText) 300.dp else 164.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (loadProblem != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "loadFailed") { ReportsLoadFailedCard(loadProblem, loadProblemActions) }
                }
                notices?.let { content ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "notices") { content() }
                }
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

/**
 * Keeps the grid at its top when what sits above the metrics changes there. Those rows can arrive
 * after the grid is on screen (the model's state, a failed read, the patient's name, the search
 * field), and a lazy grid keeps its first visible item in place: a card added above it would land
 * in the top padding, behind the expanded, clear top bar, over the title. Someone who has scrolled
 * down stays where they are.
 */
@Composable
private fun KeepTopInView(state: LazyGridState, leadingKeys: List<String>) {
    val atTop by remember(state) { derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 } }
    remember(leadingKeys) {
        // In composition, so the grid's next measure applies it together with the new rows.
        if (atTop) state.requestScrollToItem(0)
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

/** Grid card: badge + name, the latest value, its trend and a sparkline (or the latest results that aren't numbers). */
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
                MetricCardName(metric.name, Modifier.weight(1f))
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
            if (metric.isQualitative) {
                RecentResults(metric, count = 3, Modifier.fillMaxWidth().heightIn(min = 64.dp))
            } else {
                // Like iOS: the sparkline covers the year before the latest reading.
                AreaChart(
                    metric.history, color, Modifier.fillMaxWidth().height(64.dp),
                    range = metric.range, strokeWidth = 2.5.dp, showPoints = metric.history.size <= 8, sparkline = true,
                )
            }
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
                    Text(breakableAtHyphens(metric.name), style = MaterialTheme.typography.titleMedium.wrapsAtWords(), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            if (metric.isQualitative) {
                RecentResults(metric, count = 6, Modifier.fillMaxWidth().padding(top = 12.dp))
            } else {
                AreaChart(
                    metric.history, color, Modifier.fillMaxWidth().height(132.dp).padding(top = 12.dp),
                    range = metric.range, showPoints = values.size <= 12, sparkline = true,
                )
            }
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

/**
 * The name in a narrow grid card, two lines. It shrinks a little rather than cut a word (the
 * longest must fit a line, or Android breaks it at the edge) or lose its end (the D3 in
 * "25-hydroxyvitamin D3" is the test); auto-size alone sees neither as overflow until the last line.
 */
@Composable
private fun MetricCardName(name: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.titleSmall.wrapsAtWords()
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val available = constraints.maxWidth
        val largest = remember(name, style, available) {
            val words = name.split(WORD_BOUNDARY).filter { it.isNotEmpty() }
            generateSequence(style.fontSize.value) { it - CARD_NAME_STEP }
                .takeWhile { it > CARD_NAME_MIN_SIZE }
                .firstOrNull { size -> words.all { measurer.measure(it, style.copy(fontSize = size.sp), softWrap = false).size.width <= available } }
                ?: CARD_NAME_MIN_SIZE
        }
        Text(
            breakableAtHyphens(name), style = style, maxLines = 2, minLines = 2,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            autoSize = TextAutoSize.StepBased(minFontSize = CARD_NAME_MIN_SIZE.sp, maxFontSize = largest.sp, stepSize = CARD_NAME_STEP.sp),
        )
    }
}

private const val CARD_NAME_MIN_SIZE = 11f
private const val CARD_NAME_STEP = 0.5f
private val WORD_BOUNDARY = Regex("\\s+|(?<=-)")

/** Lines balanced, and no automatic hyphens: those would split "Choles-/terol in HDL" just to even them out. */
private fun TextStyle.wrapsAtWords(): TextStyle = copy(lineBreak = LineBreak.Heading, hyphens = Hyphens.None)

/**
 * A name in a narrow card, breakable after its own hyphens. Android only breaks there with
 * automatic hyphenation on; without it a word that fits no line is cut anywhere ("25-hydroxyvitami/n").
 */
private fun breakableAtHyphens(name: String): String = name.replace("-", "-\u200B")

@Composable
private fun ValueLine(metric: Metric, valueSize: Int) {
    // A result in words ("nicht reaktiv") is set smaller than a number so it fits the card.
    val size = if (metric.isQualitative) valueSize * 2 / 3 else valueSize
    // A value is never cut off: "0…" reads as a different result. The unit moves down a line instead.
    FlowRow(itemVerticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            if (metric.isQualitative) metric.entry.displayValue else formatNumber(metric.value),
            style = MaterialTheme.typography.displaySmallEmphasized.tabular().copy(fontSize = size.sp, lineHeight = (size + 4).sp),
            fontWeight = FontWeight.Bold,
            color = if (metric.rangeStatus?.isOutOfRange == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            metric.entry.unit, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = (size / 7).dp),
        )
    }
}

/** The latest results that aren't numbers, newest first — what such a metric shows in place of a chart. */
@Composable
private fun RecentResults(metric: Metric, count: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        metric.results.asReversed().take(count).forEach { result ->
            // Side by side while both fit, whole; the result moves down a line when they don't.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDate(result.date, FormatStyle.MEDIUM), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp),
                )
                Text("${result.text} ${result.unit}".trim(), style = MaterialTheme.typography.labelLarge)
            }
        }
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

/** The reports couldn't be read: not a first run, so no invitation to import as if there were none. */
@Composable
private fun DashboardLoadFailed(
    problem: ReportsLoadProblem,
    actions: LoadProblemActions,
    onOpenSettings: (() -> Unit)?,
    notices: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        CategoryWash(welcomePalette, intensity = 0.6f)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReportsLoadFailedCard(problem, actions, Modifier.widthIn(max = 520.dp))
            notices?.let { content -> Box(Modifier.widthIn(max = 520.dp)) { content() } }
        }
        onOpenSettings?.let {
            IconButton(
                onClick = it,
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)).padding(4.dp),
            ) { Icon(Icons.Rounded.Settings, stringResource(R.string.tab_settings)) }
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

@Preview(name = "Card name – narrow, long words", widthDp = 164, heightDp = 80)
@Composable
private fun MetricCardNameNarrowPreview() {
    LabImporterTheme(dynamicColor = false) { Surface { MetricCardName("Alanin-Aminotransferase") } }
}

@Preview(name = "Card name – narrow, dark", widthDp = 120, heightDp = 80, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MetricCardNameNarrowDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { Surface { MetricCardName("25-hydroxyvitamin D3") } }
}

@Preview(name = "Dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions)
    }
}

@Preview(name = "Results that aren't numbers", widthDp = 400, heightDp = 1100)
@Composable
private fun DashboardQualitativePreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reportsWithQualitative, LabDisplayPreferences(), {}, previewActions)
    }
}

@Preview(name = "Results that aren't numbers – pinned, dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardQualitativePinnedDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        DashboardScreen(PreviewSamples.reportsWithQualitative, LabDisplayPreferences(pinnedCodes = listOf("5196-1")), {}, previewActions)
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

private val modelLoadFailed: @Composable () -> Unit = {
    ModelStatusCard(AiAvailability.PreparationFailed(AiProblem.LOAD_FAILED), AiEngine.GEMMA, {}, {})
}

@Preview(name = "Populated – model couldn't load", widthDp = 400, heightDp = 900)
@Composable
private fun DashboardModelLoadFailedPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions, notices = modelLoadFailed)
    }
}

@Preview(name = "Populated – model couldn't load, dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardModelLoadFailedDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions, notices = modelLoadFailed)
    }
}

@Preview(name = "Populated – model couldn't load, largest text", widthDp = 400, heightDp = 900, fontScale = 2f)
@Composable
private fun DashboardModelLoadFailedLargestTextPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions, notices = modelLoadFailed)
    }
}

@Preview(name = "Populated – model couldn't load, two-line title", widthDp = 400, heightDp = 900, locale = "el")
@Composable
private fun DashboardModelLoadFailedLongTitlePreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions, notices = modelLoadFailed)
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

@Preview(name = "Load failed", widthDp = 400, heightDp = 900)
@Composable
private fun DashboardLoadFailedPreview() {
    LabImporterTheme(dynamicColor = false) { DashboardScreen(emptyList(), LabDisplayPreferences(), {}, previewActions, loadProblem = ReportsLoadProblem.READ_FAILED) }
}

@Preview(name = "Load failed – dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardLoadFailedDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { DashboardScreen(emptyList(), LabDisplayPreferences(), {}, previewActions, loadProblem = ReportsLoadProblem.READ_FAILED) }
}

@Preview(name = "Load failed – unreadable reports", widthDp = 400, heightDp = 900)
@Composable
private fun DashboardLoadFailedUnreadablePreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(emptyList(), LabDisplayPreferences(), {}, previewActions, loadProblem = ReportsLoadProblem.READ_FAILED, notices = { UnreadableReportsCard(2) })
    }
}

@Preview(name = "Populated – load failed", widthDp = 400, heightDp = 900)
@Composable
private fun DashboardStaleReportsPreview() {
    LabImporterTheme(dynamicColor = false) { DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions, loadProblem = ReportsLoadProblem.READ_FAILED) }
}

@Preview(name = "Populated – unreadable reports", widthDp = 400, heightDp = 900)
@Composable
private fun DashboardUnreadablePreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions, notices = { UnreadableReportsCard(1) })
    }
}

@Preview(name = "Tablet", widthDp = 900, heightDp = 640)
@Composable
private fun DashboardTabletPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions)
    }
}

@Preview(name = "Health Connect access off, no reports", widthDp = 400, heightDp = 800)
@Composable
private fun DashboardAccessOffPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(emptyList(), LabDisplayPreferences(), {}, previewActions, loadProblem = ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF)
    }
}

@Preview(name = "Reports need an update", widthDp = 400, heightDp = 800)
@Composable
private fun DashboardNewerReportsPreview() {
    LabImporterTheme(dynamicColor = false) {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, previewActions, notices = { NewerVersionReportsCard(2) })
    }
}
