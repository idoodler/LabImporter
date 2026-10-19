package dev.idoodler.labimporter.core.model

import kotlinx.datetime.LocalDate

/** One reading of a metric over time. */
data class MetricPoint(val date: LocalDate, val value: Double, val unit: String, val reportId: String)

/**
 * Every reading of one LOINC code across all reports, oldest first — the model
 * behind a dashboard card and its trend chart.
 */
data class MetricSeries(val code: String, val points: List<MetricPoint>) {
    val latest: MetricPoint get() = points.last()
    val previous: MetricPoint? get() = points.getOrNull(points.size - 2)
    val name: String get() = LabMapping.displayName(code)
    val category: LabCategory get() = LabCategory.forCode(code)
    val rangeStatus: RangeStatus? get() = LabMapping.rangeStatus(latest.value, code)
}

/**
 * Builds the dashboard's metric list, honouring the user's display
 * preferences: hidden codes are dropped; pinned codes come first (in pin
 * order), then codes the user explicitly ordered, then the rest by most recent
 * reading.
 */
fun List<LabReport>.metricSeries(
    preferences: LabDisplayPreferences,
    includeHidden: Boolean = false,
): List<MetricSeries> {
    val byCode = linkedMapOf<String, MutableList<MetricPoint>>()
    for (report in this) {
        for (entry in report.entries) {
            val value = entry.numericValue ?: continue
            byCode.getOrPut(entry.code) { mutableListOf() } += MetricPoint(report.date, value, entry.unit, report.id)
        }
    }
    val series = byCode
        .filterKeys { includeHidden || it !in preferences.hiddenSet }
        .map { (code, points) -> MetricSeries(code, points.sortedBy { it.date }) }

    val pinned = preferences.pinnedCodes
    val ordered = preferences.orderedCodes
    return series.sortedWith(
        compareBy<MetricSeries> { s -> pinned.indexOf(s.code).let { if (it < 0) Int.MAX_VALUE else it } }
            .thenBy { s -> ordered.indexOf(s.code).let { if (it < 0) Int.MAX_VALUE else it } }
            .thenByDescending { it.latest.date }
            .thenBy { it.name },
    )
}
