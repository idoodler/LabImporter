package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.PdfLayout
import dev.idoodler.labimporter.core.export.PdfPageFormat
import dev.idoodler.labimporter.core.export.PdfReport
import dev.idoodler.labimporter.core.export.PdfTimeRange
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.ReferenceRange
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfReportTest {
    @BeforeTest
    fun start() = startCatalog("en-US", LabDisplayPreferences(referenceRanges = mapOf("2160-0" to ReferenceRange(0.5, 1.2))))

    private fun report(id: String, date: LocalDate, patient: String, vararg readings: Pair<String, Double?>) = LabReport(
        id, date, patient, "Labor $id",
        readings.mapIndexed { i, (code, value) -> LabReport.Entry("$id-$i", code, code, value.toString(), value, "mg/dl") },
    )

    private val today = LocalDate(2026, 10, 1)
    private val reports = listOf(
        report("old", LocalDate(2024, 1, 1), "Max", "2160-0" to 0.7, "4548-4" to 5.4),
        report("mid", LocalDate(2026, 6, 1), "Max", "2160-0" to 0.9, "2823-3" to 4.1),
        report("new", LocalDate(2026, 9, 1), "Max Mustermann", "2160-0" to 1.4, "2951-2" to null),
    )

    @Test
    fun metricsCoverTheSelectionWithWindowedStats() {
        val data = PdfReport.build(reports, setOf("2160-0", "4548-4", "2951-2"), PdfTimeRange.YEAR_1, "", today)
        // 2951-2 has no numeric reading; unselected 2823-3 is left out.
        assertEquals(setOf("2160-0", "4548-4"), data.metrics.map { it.code }.toSet())
        val creatinine = data.metrics.single { it.code == "2160-0" }
        assertEquals(listOf(0.7, 0.9, 1.4), creatinine.allPoints.map { it.value })
        assertEquals(listOf(0.9, 1.4), creatinine.windowPoints.map { it.value })
        assertEquals(1.15, creatinine.averageValue!!, 1e-9)
        assertEquals(0.5, creatinine.change!!, 1e-9)
        assertTrue(creatinine.hasTrend)
        assertEquals(1, data.outOfRangeCount) // 1.4 is above 1.2.
        // HbA1c's only reading is older than a year: no trend, but still in the table.
        val hba1c = data.metrics.single { it.code == "4548-4" }
        assertFalse(hba1c.hasTrend)
        assertNull(hba1c.change)
        assertEquals(5.4, hba1c.latest!!.value)
    }

    @Test
    fun headerFactsComeFromTheReports() {
        val data = PdfReport.build(reports, setOf("2160-0"), PdfTimeRange.ALL, "  ", today)
        assertEquals("Max Mustermann", data.patientName) // Newest report when no name is configured.
        assertEquals("Labor new", data.authorName)
        assertEquals(LocalDate(2024, 1, 1), data.earliestDate)
        assertEquals(LocalDate(2026, 9, 1), data.latestDate)
        assertEquals(3, data.reportCount)
        assertEquals(6, data.totalValueCount)
        assertEquals("Erika", PdfReport.build(reports, setOf("2160-0"), PdfTimeRange.ALL, "Erika", today).patientName)
    }

    @Test
    fun metricsAreGroupedByCategoryThenName() {
        val data = PdfReport.build(reports, setOf("2160-0", "4548-4", "2823-3"), PdfTimeRange.ALL, "", today)
        val order = data.metrics.map { it.category.ordinal }
        assertEquals(order.sorted(), order)
        assertEquals(data.categories, LabCategory.entries.filter { it in data.metrics.map { m -> m.category } })
    }

    @Test
    fun pagesFollowTheSectionsAndPaperSize() {
        val data = PdfReport.build(reports, setOf("2160-0", "4548-4", "2823-3"), PdfTimeRange.ALL, "", today)
        val pages = PdfReport.pages(data, PdfPageFormat.A4, includeSummary = true, includeLatestResults = true, includeTrends = true)
        assertIs<PdfReport.Page.Cover>(pages.first())
        val latest = pages.filterIsInstance<PdfReport.Page.Latest>()
        assertEquals(1, latest.size)
        assertTrue(latest.single().showTitle)
        // One heading per category plus one row per metric.
        assertEquals(data.categories.size + data.metrics.size, latest.single().rows.size)
        // Only creatinine has two readings.
        assertEquals(listOf("2160-0"), pages.filterIsInstance<PdfReport.Page.Trends>().flatMap { it.metrics }.map { it.code })
        assertEquals(listOf(PdfReport.Page.Cover), PdfReport.pages(data, PdfPageFormat.A4, true, false, false))
    }

    @Test
    fun headingsStayWithTheirValuesAndStripesRestartPerCategory() {
        val codes = listOf(
            "4548-4", "2345-7", "2160-0", "2093-3", "2085-9", "13457-7", "2571-8", "1742-6", "2324-2", "3016-3",
            "2951-2", "2823-3", "718-7", "6690-2", "777-3", "2276-4",
        )
        val many = listOf(report("m", today, "", *codes.map { it to 1.0 }.toTypedArray()))
        val data = PdfReport.build(many, codes.toSet(), PdfTimeRange.ALL, "", today)
        val latest = PdfReport.pages(data, PdfPageFormat.US_LETTER, false, true, false).filterIsInstance<PdfReport.Page.Latest>()
        assertTrue(latest.size > 1)
        // The 11th row would be the "Liver" heading: it moves to the next page with its values.
        assertEquals(10, latest.first().rows.size)
        latest.dropLast(1).forEach { assertIs<PdfReport.LatestRow.Value>(it.rows.last()) }
        val rows = latest.flatMap { it.rows }
        assertEquals(data.categories.size + data.metrics.size, rows.size)
        var position = 0
        for (row in rows) {
            if (row is PdfReport.LatestRow.Heading) position = 0
            if (row is PdfReport.LatestRow.Value) assertEquals(position++ % 2 == 1, row.striped)
        }
    }

    @Test
    fun capacitiesMatchTheIosLayout() {
        // usable = height − 2·42 − 28 − 30; rows: (usable − 56)/50, charts: (usable − 56)/212.
        assertEquals(PdfLayout.Capacity(12, 13), PdfLayout.tableRows(PdfPageFormat.A4))
        assertEquals(PdfLayout.Capacity(3, 3), PdfLayout.charts(PdfPageFormat.A4))
        assertTrue(PdfLayout.tableRows(PdfPageFormat.LEGAL).first > PdfLayout.tableRows(PdfPageFormat.US_LETTER).first)
    }

    @Test
    fun paperSizeDefaultsToTheRegion() {
        assertEquals(PdfPageFormat.US_LETTER, PdfPageFormat.defaultFor("US"))
        assertEquals(PdfPageFormat.US_LETTER, PdfPageFormat.defaultFor("ca"))
        assertEquals(PdfPageFormat.A4, PdfPageFormat.defaultFor("DE"))
        assertEquals(PdfPageFormat.A4, PdfPageFormat.defaultFor(null))
    }
}
