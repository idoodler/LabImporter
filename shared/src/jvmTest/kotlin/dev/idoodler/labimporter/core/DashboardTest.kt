package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.model.CodeName
import dev.idoodler.labimporter.core.model.Dashboard
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.MetricTrend
import dev.idoodler.labimporter.core.model.TrendWindow
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardTest {

    @BeforeTest
    fun start() = startCatalog("en-US")

    private fun report(id: String, date: LocalDate, vararg readings: Pair<String, Double?>) = LabReport(
        id, date, "", "",
        readings.mapIndexed { i, (code, value) -> LabReport.Entry("$id-$i", code, code, value.toString(), value, "u") },
    )

    @Test
    fun latestReadingWinsAndTiesKeepTheFirst() {
        val reports = listOf(
            report("a", LocalDate(2026, 1, 1), "2160-0" to 1.0),
            report("b", LocalDate(2026, 3, 1), "2160-0" to 3.0),
            report("c", LocalDate(2026, 3, 1), "2160-0" to 9.0),
            report("d", LocalDate(2026, 2, 1), "2160-0" to 2.0, "4548-4" to null),
        )
        val metric = Dashboard.metrics(reports).single()
        assertEquals(3.0, metric.entry.numericValue)
        assertEquals(listOf(1.0, 2.0, 3.0, 9.0), metric.points.map { it.value })
    }

    @Test
    fun sparklineCoversTwelveMonthsButKeepsTwoPoints() {
        val metric = Dashboard.metrics(
            listOf(
                report("old", LocalDate(2023, 1, 1), "2160-0" to 1.0),
                report("mid", LocalDate(2025, 9, 9), "2160-0" to 2.0),
                report("new", LocalDate(2026, 9, 10), "2160-0" to 3.0),
            ),
        ).single()
        assertEquals(listOf(2.0, 3.0), metric.history.map { it.value })
        assertEquals(MetricTrend.RISING, metric.trend)

        val sparse = Dashboard.metrics(
            listOf(report("x", LocalDate(2020, 1, 1), "2160-0" to 5.0), report("y", LocalDate(2026, 1, 1), "2160-0" to 5.0)),
        ).single()
        assertEquals(2, sparse.history.size)
        assertEquals(MetricTrend.STEADY, sparse.trend)
        assertNull(Dashboard.metrics(listOf(report("z", LocalDate(2026, 1, 1), "2160-0" to 5.0))).single().trend)
    }

    @Test
    fun orderingMatchesIos() {
        val metrics = Dashboard.metrics(
            listOf(report("r", LocalDate(2026, 1, 1), "2160-0" to 1.0, "4548-4" to 6.0, "2823-3" to 4.0, "2951-2" to 140.0)),
        )
        // No preferences: alphabetical by name.
        assertEquals(metrics.map { it.name }.sorted(), Dashboard.sorted(metrics, LabDisplayPreferences()).map { it.name })
        // Pinned first (pin order doesn't matter), then custom order, then names; hidden removed.
        val prefs = LabDisplayPreferences(
            pinnedCodes = listOf("2951-2", "4548-4"),
            orderedCodes = listOf("2823-3", "4548-4", "2823-3"),
            hiddenCodes = listOf("2160-0"),
        )
        val sorted = Dashboard.sorted(metrics, prefs).map { it.code }
        assertEquals(listOf("4548-4", "2951-2", "2823-3"), sorted)
    }

    @Test
    fun trendScreenKeepsHiddenMetricsInDashboardOrder() {
        val reports = listOf(report("r", LocalDate(2026, 1, 1), "2160-0" to 1.0, "4548-4" to 6.0, "2823-3" to null))
        val prefs = LabDisplayPreferences(pinnedCodes = listOf("4548-4"), hiddenCodes = listOf("4548-4", "2160-0"))
        // Only numeric readings chart; hidden metrics stay reachable; pinned still lead.
        assertEquals(listOf("4548-4", "2160-0"), Dashboard.trendMetrics(reports, prefs).map { it.code })
        assertEquals(emptyList(), Dashboard.sorted(Dashboard.metrics(reports), prefs))
    }

    @Test
    fun codeNamesListEveryCodeOnceInFirstSeenOrderWithNicknames() {
        startCatalog("en-US", LabDisplayPreferences(nicknames = mapOf("2160-0" to "Kreatinin")))
        val reports = listOf(
            report("a", LocalDate(2026, 1, 1), "4548-4" to 6.0, "2160-0" to null),
            report("b", LocalDate(2026, 2, 1), "2160-0" to 1.0, "LOCAL-1" to 2.0),
        )
        assertEquals(
            listOf(CodeName("4548-4", LabMapping.catalogName("4548-4")), CodeName("2160-0", "Kreatinin"), CodeName("LOCAL-1", "LOCAL-1")),
            Dashboard.codeNames(reports),
        )
    }

    @Test
    fun trendWindowsCountDaysBackFromTheLatestReading() {
        val metric = Dashboard.metrics(
            listOf(
                report("a", LocalDate(2025, 1, 1), "2160-0" to 1.0),
                report("b", LocalDate(2026, 5, 1), "2160-0" to 2.0),
                report("c", LocalDate(2026, 9, 1), "2160-0" to 3.0),
            ),
        ).single()
        assertEquals(listOf(2.0, 3.0), TrendWindow.MONTHS_6.visible(metric.points).map { it.value })
        assertEquals(listOf(3.0), TrendWindow.MONTHS_3.visible(metric.points).map { it.value })
        assertEquals(3, TrendWindow.ALL.visible(metric.points).size)
        assertEquals(2, TrendWindow.YEAR_1.visible(metric.points).size)
    }
}
