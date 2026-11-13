package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.parse.AiLabEntry
import dev.idoodler.labimporter.core.parse.AiLabReport
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import dev.idoodler.labimporter.core.parse.ParseResult
import dev.idoodler.labimporter.core.parse.ReportSourceText
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cumulative report prints a test's result once a date: Review flags a value only where its row
 * prints several and it isn't the report date's, and the date only where it isn't the latest.
 * An ordinary report raises nothing.
 */
class ResultColumnsTest {
    @BeforeTest
    fun start() = startCatalog()

    private val today = LocalDate(2026, 11, 9)

    private val rows = """
        Kreatinin          0,82         0,95 H       0,78         mg/dl     0,50-0,90
        Kalium             4,1          4,4          3,9          mmol/l    3,5-5,1
        Leukozyten         6,8          11,2 H       7,1          10^9/l    4,0-10,0
        Ferritin           88                                     ng/ml     15-150
    """.trimIndent()

    /** The same table under each way a cumulative report heads its date columns. */
    private val headers = mapOf(
        "dates" to "Kumulativ          03.11.2026   18.08.2026   14.02.2026   Einheit   Referenz",
        "ISO dates" to "Kumulativ          2026-11-03   2026-08-18   2026-02-14   Einheit   Referenz",
        "slashes" to "CUMULATIVE         03/11/26     18/08/26     14/02/26     Units     Reference",
        "month names" to "Kumulativ          03-Nov-26    18-Aug-26    14-Feb-26    Einheit   Referenz",
        "no year" to "Kumulativ          03.11.       18.08.       14.02.       Einheit   Referenz",
        "times with Uhr" to "Kumulativ   31.10.26 07:12 Uhr   17.10.26 09:30 Uhr   14.02.26 08:05 Uhr   Einheit",
        "one date a line" to "Kumulativ   Einheit   Referenz\n03.11.2026\n18.08.2026\n14.02.2026",
    )

    private fun read(text: String, raw: String, name: String = "Kalium", unit: String = "mmol/l", date: String = ""): ParseResult =
        LabParsePipeline.postProcess(AiLabReport(listOf(AiLabEntry(name, name, raw, unit)), reportDate = date, reportLanguage = "de"), text, today)

    private fun flagged(text: String, raw: String, name: String = "Kalium", unit: String = "mmol/l", date: String = "") =
        read(text, raw, name, unit, date).values.single().needsSourceCheck

    @Test
    fun everyHeaderShapeMakesItsColumns() {
        for ((shape, header) in headers) {
            val text = "Kantonsspital Musterstadt - Labor\nPatient: Muster, Anna\n$header\n$rows"
            val columns = assertNotNull(ReportSourceText(text, today).resultColumns, shape)
            assertEquals(3, columns.dates.size, shape)
            assertEquals(0, columns.newest, shape)
            // The latest column's result is the report's; an older column's is flagged.
            assertFalse(flagged(text, "4,1"), "$shape: the report date's result")
            assertTrue(flagged(text, "4,4"), "$shape: the second column's")
            assertTrue(flagged(text, "3,9"), "$shape: the third column's")
            assertTrue(flagged(text, "0,95", "Kreatinin", "mg/dl"), "$shape: behind its flag")
            assertFalse(flagged(text, "0,82", "Kreatinin", "mg/dl"), shape)
            // The unit's 10^9 is no result; nor is the reference range.
            assertFalse(flagged(text, "6,8", "Leukozyten", "10^9/l"), shape)
            assertTrue(flagged(text, "11,2", "Leukozyten", "10^9/l"), shape)
            // A row of one result in a cumulative report has nothing to mix up.
            assertFalse(flagged(text, "88", "Ferritin", "ng/ml"), "$shape: a single result")
            assertTrue(read(text, "4,1").hasSeveralResultDates, shape)
        }
    }

    @Test
    fun theReportDateIsFlaggedOnlyWhereItIsntTheLatestColumns() {
        val text = "Gedruckt: 06.11.2026\n${headers.getValue("dates")}\n$rows"
        val latest = read(text, "4,1", date = "2026-11-03")
        assertFalse(latest.reportDateNeedsCheck)
        assertFalse(latest.values.single().needsSourceCheck)
        // An older column's date: flagged, and its column's values are the ones it goes with.
        val older = read(text, "4,4", date = "2026-08-18")
        assertTrue(older.reportDateNeedsCheck)
        assertFalse(older.values.single().needsSourceCheck)
        assertTrue(flagged(text, "4,1", date = "2026-08-18"))
        // The print date heads no column: flagged, and the values are checked against the latest column.
        val printed = read(text, "4,1", date = "2026-11-06")
        assertEquals(LocalDate(2026, 11, 6), printed.reportDate)
        assertTrue(printed.reportDateNeedsCheck)
        assertFalse(printed.values.single().needsSourceCheck)
        assertTrue(flagged(text, "4,4", date = "2026-11-06"))
    }

    @Test
    fun theLatestColumnCanBeTheLast() {
        val text = """
            Datum              14.02.2026   18.08.2026   03.11.2026   Einheit
            Kalium             3,9          4,4          4,1          mmol/l
            Natrium            141          139          138          mmol/l
        """.trimIndent()
        assertEquals(2, ReportSourceText(text, today).resultColumns?.newest)
        assertFalse(flagged(text, "4,1"))
        assertTrue(flagged(text, "3,9"))
        assertFalse(read(text, "4,1", date = "2026-11-03").reportDateNeedsCheck)
        assertTrue(read(text, "4,1", date = "2026-02-14").reportDateNeedsCheck)
    }

    @Test
    fun aRowThatLinesUpWithTheHeaderIsReadByColumn() {
        // No result on the report date: the next column's is no stand-in for it.
        val text = """
            Kumulativ          03.11.2026   18.08.2026   14.02.2026   Einheit
            Kalium             4,1          4,4          3,9          mmol/l
            Glukose                         7,2          5,6          mmol/l
            Natrium            138                       140          mmol/l
        """.trimIndent()
        assertTrue(flagged(text, "7,2", "Glukose"))
        assertTrue(flagged(text, "140", "Natrium"))
        assertFalse(flagged(text, "138", "Natrium"))
    }

    @Test
    fun ordinaryReportsRaiseNothing() {
        val ordinary = listOf(
            // A header of dates over values of one date: collected, received, reported.
            """
            Collected              Received     Reported     Fasting
            10/14/2026 0830 Local   10/15/2026   10/16/2026   Yes

            Glucose            92        mg/dL      70-99
            Creatinine         1.32  H   mg/dL      0.57-1.00
            Hemoglobin         13.5      g/dL       11.1-15.9
            eGFR               >60       mL/min/1.73m2   >59
            """,
            // A birth date beside the report date.
            """
            Muster, Anna   12.03.1965   03.11.2026
            Kreatinin          0,82         mg/dl     0,50-0,90
            Kalium             4,1          mmol/l    3,5-5,1
            """,
            // Labelled dates, the same date twice, a period.
            """
            Auftrag: 31.10.2026 07:12       Visiert: 31.10.2026 15:30
            Eingang 03.11.2026   03.11.2026
            Zeitraum 01.10.2026 - 31.10.2026
            Kalium             4,1     4,4 H      mmol/l    3,5-5,1
            Natrium            138     135 L      mmol/l    135-145
            """,
            // A previous result with its date on its row: one result date in the header.
            """
            Date Collected: 10/14/2026      Date Reported: 10/14/2026
            Glucose            65      Low               92      04/02/2026         mg/dL      70-99
            Sodium             138                       140     04/02/2026         mmol/L     134-144
            """,
            // Two dates over rows of one result each.
            """
            Befund             03.11.2026   18.08.2026
            Kalium             4,1                       mmol/l    3,5-5,1
            Natrium            138                       mmol/l    135-145
            """,
        ).map { it.trimIndent() }
        for (text in ordinary) {
            assertNull(ReportSourceText(text, today).resultColumns, text.lines().first())
        }
        assertFalse(flagged(ordinary[0], "92", "Glucose", "mg/dL"))
        assertFalse(flagged(ordinary[0], ">60", "eGFR", "mL/min/1.73m2"))
        assertFalse(read(ordinary[0], "92", "Glucose", "mg/dL", date = "2026-10-14").reportDateNeedsCheck)
        assertFalse(read(ordinary[0], "92", "Glucose", "mg/dL", date = "2026-10-14").hasSeveralResultDates)
        assertFalse(flagged(ordinary[1], "4,1"))
        assertFalse(read(ordinary[1], "4,1", date = "2026-11-03").reportDateNeedsCheck)
        assertFalse(flagged(ordinary[2], "4,1", date = "2026-10-31"))
        assertFalse(read(ordinary[2], "4,1", date = "2026-10-31").reportDateNeedsCheck)
        assertFalse(flagged(ordinary[3], "65", "Glucose", "mg/dL", date = "2026-10-14"))
        assertFalse(read(ordinary[3], "65", "Glucose", "mg/dL", date = "2026-10-14").reportDateNeedsCheck)
        assertFalse(flagged(ordinary[4], "4,1", date = "2026-11-03"))
        assertFalse(read(ordinary[4], "4,1", date = "2026-11-03").reportDateNeedsCheck)
    }

    @Test
    fun aBirthDateIsNoResultColumn() {
        val text = "Muster, Anna   12.03.1965   03.11.2026   18.08.2026\n$rows"
        val columns = assertNotNull(ReportSourceText(text, today).resultColumns)
        assertEquals(2, columns.dates.size)
        assertNull(ReportSourceText("Muster, Anna   12.03.65   03.11.26\n$rows", today).resultColumns, "a two-digit birth year")
    }
}
