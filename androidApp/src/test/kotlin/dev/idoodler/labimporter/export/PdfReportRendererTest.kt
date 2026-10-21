package dev.idoodler.labimporter.export

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.core.export.PdfPageFormat
import dev.idoodler.labimporter.core.export.PdfReport
import dev.idoodler.labimporter.core.export.PdfTimeRange
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.data.PatientDetails
import dev.idoodler.labimporter.data.PatientSex
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime

/**
 * The PDF renderer draws every page the shared core lays out, in every format
 * and colour mode (native graphics for all tests — see `build.gradle.kts`).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class PdfReportRendererTest {
    private val renderer = PdfReportRenderer(ApplicationProvider.getApplicationContext())
    private val today = LocalDate(2026, 10, 1)

    /** Monthly reports with many values, so the table and charts span several pages. */
    private val reports: List<LabReport> = (0 until 8).map { month ->
        LabReport(
            "r$month", LocalDate(2026, 1, 15).plus(DatePeriod(months = month)), "Max Mustermann", "Labor Dr. Muster",
            CODES.mapIndexed { i, code -> LabReport.Entry("e$month-$i", code, "", "${1.0 + i + month * 0.1}", 1.0 + i + month * 0.1, "mg/dl") },
        )
    }

    /**
     * Draws every page onto a bitmap (Robolectric has no native PdfDocument)
     * and returns how many pages there were, checking none runs into its footer.
     */
    private fun drawAll(options: PdfOptions, patient: PatientDetails = PatientDetails()): Int {
        val data = PdfReport.build(reports, options.selectedCodes, options.timeRange, "", today)
        val pages = renderer.pages(data, options)
        val format = options.pageFormat
        pages.indices.forEach { index ->
            val bitmap = Bitmap.createBitmap(format.width.toInt(), format.height.toInt(), Bitmap.Config.ARGB_8888)
            val end = renderer.drawPage(Canvas(bitmap), pages, index, data, options, patient, LocalDateTime.of(2026, 10, 1, 9, 30))
            assertTrue("$format page ${index + 1} ends at $end", end <= renderer.footerTop(format))
        }
        return pages.size
    }

    @Test
    fun everyFormatAndColourModeRendersTheSharedPages() {
        for (format in PdfPageFormat.entries) {
            for (mode in PdfColorMode.entries) {
                val options = PdfOptions(CODES.toSet(), PdfTimeRange.YEAR_1, mode, format)
                val pages = drawAll(options, PatientDetails(LocalDate(1980, 4, 2), PatientSex.FEMALE))
                assertTrue("$format/$mode", pages > 3)
            }
        }
    }

    @Test
    fun sectionsCanBeLeftOut() {
        val onlyTrends = PdfOptions(CODES.toSet(), includeSummary = false, includeLatestResults = false)
        assertTrue(drawAll(onlyTrends) >= 1)
        val onlyCover = PdfOptions(setOf(CODES.first()), includeLatestResults = false, includeTrends = false)
        assertEquals(1, drawAll(onlyCover))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nothingSelectedIsRefused() {
        val data = PdfReport.build(reports, emptySet(), PdfTimeRange.YEAR_1, "", today)
        renderer.render(data, PdfOptions(emptySet()), PatientDetails(), LocalDateTime.now(), ByteArrayOutputStream())
    }

    private companion object {
        /** Real LOINC codes across categories (names come from the catalog). */
        val CODES = listOf(
            "4548-4", "2345-7", "2160-0", "33914-3", "2093-3", "2085-9", "13457-7", "2571-8", "1742-6", "1920-8",
            "6768-6", "2324-2", "3016-3", "3024-7", "2951-2", "2823-3", "718-7", "6690-2", "777-3", "789-8",
        )
    }
}
