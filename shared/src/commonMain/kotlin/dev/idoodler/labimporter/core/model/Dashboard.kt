package dev.idoodler.labimporter.core.model

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** One reading of a metric. */
data class MetricPoint(val date: LocalDate, val value: Double, val unit: String, val reportId: String)

/** A tracked code and its display name. */
data class CodeName(val code: String, val name: String)

/** Direction of the latest reading relative to the previous one. */
enum class MetricTrend { RISING, FALLING, STEADY;

    companion object {
        /** Null when there's no earlier reading to compare with. */
        fun of(points: List<MetricPoint>): MetricTrend? {
            if (points.size < 2) return null
            val latest = points[points.lastIndex].value
            val previous = points[points.lastIndex - 1].value
            return when {
                latest > previous -> RISING
                latest < previous -> FALLING
                else -> STEADY
            }
        }
    }
}

/**
 * One tracked metric on the dashboard: the latest reading ([entry] from the
 * report dated [date]) plus its history.
 *
 * @property history readings within [Dashboard.SPARKLINE_WINDOW_MONTHS] of the
 *   metric's own latest reading (never fewer than two when two exist) — what a
 *   dashboard card charts.
 * @property points every reading, oldest first — what the trend screen charts.
 */
data class Metric(
    val entry: LabReport.Entry,
    val date: LocalDate,
    val history: List<MetricPoint>,
    val points: List<MetricPoint>,
) {
    val code: String get() = entry.code
    val name: String get() = entry.resolvedName

    /** The latest reading's value (metrics exist only for numeric readings). */
    val value: Double get() = checkNotNull(entry.numericValue) { "Metric $code has no numeric reading" }
    val category: LabCategory get() = LabCategory.forCode(entry.code)
    val trend: MetricTrend? get() = MetricTrend.of(history)
    val range: ReferenceRange? get() = LabMapping.referenceRange(entry.code)
    val rangeStatus: RangeStatus? get() = LabMapping.rangeStatus(entry.numericValue, entry.code)
}

/** The dashboard's metric list — identical rules on iOS and Android. */
object Dashboard {
    /** Months of history a card's sparkline shows, measured back from the metric's own latest reading. */
    const val SPARKLINE_WINDOW_MONTHS = 12

    /**
     * One [Metric] per code that has a numeric reading. The latest reading is
     * the one from the most recent report (on equal dates the first one seen wins).
     */
    fun metrics(reports: List<LabReport>): List<Metric> {
        val latest = linkedMapOf<String, Pair<LabReport.Entry, LocalDate>>()
        val points = mutableMapOf<String, MutableList<MetricPoint>>()
        for (report in reports) {
            for (entry in report.entries) {
                val value = entry.numericValue ?: continue
                points.getOrPut(entry.code) { mutableListOf() } += MetricPoint(report.date, value, entry.unit, report.id)
                val existing = latest[entry.code]
                if (existing == null || report.date > existing.second) latest[entry.code] = entry to report.date
            }
        }
        return latest.values.map { (entry, date) ->
            val all = points.getValue(entry.code).sortedBy { it.date }
            val cutoff = date.minus(DatePeriod(months = SPARKLINE_WINDOW_MONTHS))
            var history = all.filter { it.date >= cutoff }
            // Never let the window collapse a real trend to a single point.
            if (history.size < 2 && all.size >= 2) history = all.takeLast(2)
            Metric(entry, date, history, all)
        }
    }

    /**
     * Visible metrics in dashboard order: hidden codes removed; pinned first;
     * then the user's custom order; then alphabetically by name.
     */
    fun sorted(metrics: List<Metric>, preferences: LabDisplayPreferences): List<Metric> {
        val hidden = preferences.hiddenSet
        return ordered(metrics.filter { it.code !in hidden }, preferences)
    }

    /**
     * Every metric — hidden ones included, so a hidden metric's history stays
     * reachable — in dashboard order: what the trend screen can show.
     */
    fun trendMetrics(reports: List<LabReport>, preferences: LabDisplayPreferences): List<Metric> =
        ordered(metrics(reports), preferences)

    private fun ordered(metrics: List<Metric>, preferences: LabDisplayPreferences): List<Metric> {
        val pinned = preferences.pinnedSet
        val order = mutableMapOf<String, Int>()
        preferences.orderedCodes.forEachIndexed { index, code -> if (code !in order) order[code] = index }
        // A name resolves through the catalog: look each one up once, not per comparison.
        return metrics.map { it to it.name }
            .sortedWith(
                compareBy<Pair<Metric, String>> { (metric, _) -> if (metric.code in pinned) 0 else 1 }
                    .thenBy { (metric, _) -> order[metric.code] ?: Int.MAX_VALUE }
                    .thenBy { (_, name) -> name },
            )
            .map { (metric, _) -> metric }
    }

    /** Up to [limit] distinct categories of [sorted] metrics, in order — the background wash. */
    fun backgroundCategories(sorted: List<Metric>, limit: Int = 3): List<LabCategory> =
        sorted.map { it.category }.distinct().take(limit)

    /** Every code with a reading and its display name, first-seen order (the settings editors' list). */
    fun codeNames(reports: List<LabReport>): List<CodeName> {
        val seen = mutableSetOf<String>()
        return reports.flatMap { it.entries }.filter { seen.add(it.code) }.map { CodeName(it.code, it.resolvedName) }
    }
}

/** Time windows for a metric's trend chart. */
enum class TrendWindow(val days: Int?) {
    MONTHS_3(92), MONTHS_6(183), YEAR_1(366), ALL(null);

    /** The readings inside this window, which ends at the latest reading. */
    fun visible(points: List<MetricPoint>): List<MetricPoint> {
        val span = days ?: return points
        val latest = points.maxOfOrNull { it.date } ?: return points
        val start = latest.minus(DatePeriod(days = span))
        return points.filter { it.date >= start }
    }
}
