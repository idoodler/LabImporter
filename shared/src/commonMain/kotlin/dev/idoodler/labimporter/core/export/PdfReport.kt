package dev.idoodler.labimporter.core.export

import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.MetricPoint
import dev.idoodler.labimporter.core.model.RangeStatus
import dev.idoodler.labimporter.core.model.ReferenceRange
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** Time window of the PDF's trend charts (the latest-results table always shows the newest reading). */
enum class PdfTimeRange(val days: Int?) {
    MONTHS_3(92), MONTHS_6(183), YEAR_1(366), YEAR_2(731), ALL(null),
}

/** Paper size, in PostScript points (1/72"). */
enum class PdfPageFormat(val width: Double, val height: Double) {
    A4(595.28, 841.89), US_LETTER(612.0, 792.0), LEGAL(612.0, 1008.0);

    companion object {
        /** Regions where US Letter (or its local equivalent) is the office paper size; elsewhere ISO A4. */
        private val LETTER_REGIONS = setOf("US", "CA", "MX", "PH", "CL", "CO", "CR", "GT", "NI", "PA", "DO", "SV", "VE", "PR")

        /** A sensible default from the device's region (no OS exposes a paper-size setting). */
        fun defaultFor(regionCode: String?): PdfPageFormat = if (regionCode?.uppercase() in LETTER_REGIONS) US_LETTER else A4
    }
}

/**
 * One metric's picture in the export: identity, every reading (for the
 * latest value) and the readings inside the time window (charts and stats).
 */
data class PdfMetric(
    val code: String,
    val name: String,
    val category: LabCategory,
    val unit: String,
    val referenceRange: ReferenceRange?,
    /** All readings, oldest first. */
    val allPoints: List<MetricPoint>,
    /** Readings within the selected window, oldest first. */
    val windowPoints: List<MetricPoint>,
) {
    val latest: MetricPoint? get() = allPoints.lastOrNull()
    val latestStatus: RangeStatus? get() = latest?.let { referenceRange?.status(it.value) }

    /** Enough in-window readings to draw a meaningful line. */
    val hasTrend: Boolean get() = windowPoints.size >= 2
    val minValue: Double? get() = windowPoints.minOfOrNull { it.value }
    val maxValue: Double? get() = windowPoints.maxOfOrNull { it.value }
    val averageValue: Double? get() = windowPoints.takeIf { it.isNotEmpty() }?.map { it.value }?.average()

    /** Net change across the window (newest − oldest in-window reading). */
    val change: Double? get() = if (windowPoints.size >= 2) windowPoints.last().value - windowPoints.first().value else null
}

/** Everything the PDF's pages show, assembled once. */
data class PdfReportData(
    val patientName: String,
    val authorName: String,
    val earliestDate: LocalDate?,
    val latestDate: LocalDate?,
    val reportCount: Int,
    val totalValueCount: Int,
    val timeRange: PdfTimeRange,
    val metrics: List<PdfMetric>,
) {
    /** Selected metrics whose newest reading is outside its reference range. */
    val outOfRangeCount: Int get() = metrics.count { it.latestStatus?.isOutOfRange == true }

    /** Categories present, in canonical order — the latest-results table's grouping. */
    val categories: List<LabCategory> get() = metrics.map { it.category }.toSet().let { present -> LabCategory.entries.filter { it in present } }

    /** Categories by how many selected metrics they hold (ties canonical) — the cover's colours. */
    val dominantCategories: List<LabCategory> get() = LabCategory.ranked(metrics.map { it.category })
}

/** Turns saved reports into the PDF's content — the same on iOS and Android. */
object PdfReport {

    /**
     * @param selectedCodes the LOINC codes the user chose to include.
     * @param configuredPatientName the user's own name setting; the newest report's name otherwise.
     * @param today the window ends here (readings on or after `today − days` count).
     */
    fun build(
        reports: List<LabReport>,
        selectedCodes: Set<String>,
        timeRange: PdfTimeRange,
        configuredPatientName: String,
        today: LocalDate,
    ): PdfReportData {
        val sorted = reports.sortedBy { it.date }
        val cutoff = timeRange.days?.let { today.minus(DatePeriod(days = it)) }
        val points = linkedMapOf<String, MutableList<MetricPoint>>()
        val meta = mutableMapOf<String, Pair<String, String>>()
        for (report in sorted) {
            for (entry in report.entries) {
                if (entry.code !in selectedCodes) continue
                val value = entry.numericValue ?: continue
                points.getOrPut(entry.code) { mutableListOf() } += MetricPoint(report.date, value, entry.unit, report.id)
                meta[entry.code] = entry.resolvedName to entry.unit // Newest name/unit wins.
            }
        }
        val metrics = points.map { (code, all) ->
            val (name, unit) = meta.getValue(code)
            PdfMetric(
                code = code,
                name = name,
                category = LabCategory.forCode(code),
                unit = unit,
                referenceRange = LabMapping.referenceRange(code),
                allPoints = all,
                windowPoints = cutoff?.let { cut -> all.filter { it.date >= cut } } ?: all,
            )
        }.sortedWith(compareBy<PdfMetric> { it.category.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        return PdfReportData(
            patientName = configuredPatientName.trim().ifEmpty { sorted.lastOrNull()?.patientName.orEmpty() },
            authorName = sorted.lastOrNull()?.authorName.orEmpty(),
            earliestDate = sorted.firstOrNull()?.date,
            latestDate = sorted.lastOrNull()?.date,
            reportCount = reports.size,
            totalValueCount = reports.sumOf { it.entries.size },
            timeRange = timeRange,
            metrics = metrics,
        )
    }

    /** One page of the export. */
    sealed interface Page {
        data object Cover : Page

        /** Latest-results rows; the first page of the section carries its title. */
        data class Latest(val rows: List<LatestRow>, val showTitle: Boolean) : Page

        /** Trend charts; the first page of the section carries its title. */
        data class Trends(val metrics: List<PdfMetric>, val showTitle: Boolean) : Page
    }

    /** A row of the latest-results table: a category heading or a metric. */
    sealed interface LatestRow {
        data class Heading(val category: LabCategory, val count: Int) : LatestRow

        /** [striped]: every second value within its category gets the zebra background. */
        data class Value(val metric: PdfMetric, val striped: Boolean) : LatestRow
    }

    /**
     * The export's pages in order: cover, latest results (grouped by
     * category), then one chart per metric with a trend in the window —
     * paginated for [format] (see [PdfLayout]).
     */
    fun pages(
        data: PdfReportData,
        format: PdfPageFormat,
        includeSummary: Boolean,
        includeLatestResults: Boolean,
        includeTrends: Boolean,
    ): List<Page> {
        val pages = mutableListOf<Page>()
        if (includeSummary) pages += Page.Cover
        if (includeLatestResults) {
            val byCategory = data.metrics.groupBy { it.category }
            val rows = data.categories.flatMap { category ->
                val metrics = byCategory.getValue(category)
                listOf(LatestRow.Heading(category, metrics.size)) + metrics.mapIndexed { i, metric -> LatestRow.Value(metric, i % 2 == 1) }
            }
            // A heading never ends a page: it moves on with its first value.
            pages += paginate(rows, PdfLayout.tableRows(format)) { it is LatestRow.Heading }.map { (items, first) -> Page.Latest(items, first) }
        }
        if (includeTrends) {
            pages += paginate(data.metrics.filter { it.hasTrend }, PdfLayout.charts(format))
                .map { (items, first) -> Page.Trends(items, first) }
        }
        return pages
    }

    /** Splits [items] into pages; an item for which [keepWithNext] holds never ends a page. */
    private fun <T> paginate(
        items: List<T>,
        capacity: PdfLayout.Capacity,
        keepWithNext: (T) -> Boolean = { false },
    ): List<Pair<List<T>, Boolean>> {
        val result = mutableListOf<Pair<List<T>, Boolean>>()
        var index = 0
        while (index < items.size) {
            val first = result.isEmpty()
            val size = (if (first) capacity.first else capacity.rest).coerceAtLeast(1)
            var end = minOf(index + size, items.size)
            while (end < items.size && end - index > 1 && keepWithNext(items[end - 1])) end--
            result += items.subList(index, end) to first
            index = end
        }
        return result
    }
}

/**
 * Page geometry shared by both renderers. Capacities derive from the page
 * height so each paper size uses its space; everything is sized for the
 * worst case (a two-line value name, a full chart card) so nothing clips. The
 * first page of a section holds fewer items because it also carries the title.
 */
object PdfLayout {
    const val MARGIN = 42.0
    private const val FOOTER = 28.0
    private const val RUNNING_HEADER = 30.0
    private const val SECTION_TITLE = 56.0
    private const val ROW_UNIT = 50.0
    private const val CHART_UNIT = 212.0

    /** Items per page: on the section's first page, and on later pages. */
    data class Capacity(val first: Int, val rest: Int)

    fun contentWidth(format: PdfPageFormat): Double = format.width - MARGIN * 2

    private fun usableHeight(format: PdfPageFormat) = format.height - MARGIN * 2 - FOOTER - RUNNING_HEADER

    fun tableRows(format: PdfPageFormat): Capacity = capacity(format, ROW_UNIT)

    fun charts(format: PdfPageFormat): Capacity = capacity(format, CHART_UNIT)

    private fun capacity(format: PdfPageFormat, unit: Double): Capacity {
        val height = usableHeight(format)
        return Capacity(maxOf(1, ((height - SECTION_TITLE) / unit).toInt()), maxOf(1, (height / unit).toInt()))
    }
}
