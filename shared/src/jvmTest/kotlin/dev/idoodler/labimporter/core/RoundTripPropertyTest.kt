package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.export.CdaParser
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.Ucum
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.QualitativeResult
import dev.idoodler.labimporter.core.platform.NumberFormatting
import dev.idoodler.labimporter.core.transfer.ReportTransfer
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Pseudo-random reports through every format a report is stored or carried in.
 * The seed is fixed, so a failure names a report that can be regenerated.
 */
class RoundTripPropertyTest {

    @BeforeTest
    fun start() {
        startCatalog("en-US")
        for (code in CODES) assertNotNull(LabMapping.loincCode(code), "$code isn't in the catalog")
    }

    private val reports: List<LabReport> = Random(SEED).let { random -> List(REPORTS) { random.report("report-$it") } }

    @Test
    fun fhirKeepsEveryReportExactly() {
        for (report in reports) {
            val resources = FhirLabCodec.encodeForSaving(report, "LabImporter test").map { it.json }
            assertEquals(report, FhirLabCodec.decode(resources).single())
        }
    }

    @Test
    fun theTransferFileKeepsEveryReportExactly() {
        val read = assertIs<ReportTransfer.ReadResult.Reports>(ReportTransfer.read(ReportTransfer.export(reports, "LabImporter test").contents))
        assertEquals(reports.associateBy { it.id }, read.reports.associateBy { it.id })
        // Importing the same file again would add nothing.
        assertEquals(0, ReportTransfer.plan(reports, read.reports).toImport.size)
    }

    @Test
    fun cdaKeepsEveryValueUnitDateAndCode() {
        for (report in reports) {
            val read = throughCda(report)
            assertEquals(report.date, read.date, report.id)
            assertEquals(report.patientName, read.patientName, report.id)
            assertEquals(report.authorName, read.authorName, report.id)
            assertEquals(readings(report), readings(read), report.id)
            assertEquals(ReportTransfer.fingerprint(report), ReportTransfer.fingerprint(read), report.id)
            // Saving what was read back (what editing does on iOS) changes nothing further.
            assertEquals(readings(read), readings(throughCda(read)), report.id)
        }
    }

    @Test
    fun aResultInWordsKeepsItsWholeTextEvenWithAUnit() {
        // Words have no unit to tell apart (see QualitativeResult), so the unit joins the text: still all there.
        val entry = LabReport.Entry("e", "5196-1", "HBsAg", "nicht reaktiv", null, "mg/l")
        val report = LabReport("r", LocalDate(2026, 5, 6), "", "", listOf(entry))
        val decoded = FhirLabCodec.decode(FhirLabCodec.encodeForSaving(report, "test").map { it.json }).single().entries.single()
        assertEquals("nicht reaktiv mg/l", QualitativeResult.stored(decoded.displayValue, decoded.unit))
        assertEquals("nicht reaktiv mg/l", throughCda(report).entries.single().let { QualitativeResult.stored(it.displayValue, it.unit) })
    }

    private fun throughCda(report: LabReport): LabReport {
        val xml = CdaExport.generate(report.asLabValues, report.date, "LabImporter test", GoldenCda.narrative, report.patientName, report.authorName)
        return assertNotNull(CdaParser.parse(xml, report.id), "unreadable document for ${report.id}")
    }

    /** What a report says, by code. CDA writes numbers' units in UCUM by design, and doesn't carry entry ids or printed names. */
    private fun readings(report: LabReport) = report.entries.associate { entry ->
        entry.code to Triple(entry.numericValue, entry.displayValue, if (entry.numericValue != null) Ucum.fromPrinted(entry.unit) else entry.unit)
    }

    private fun Random.report(id: String): LabReport {
        val entries = CODES.shuffled(this).take(nextInt(1, 13)).mapIndexed { i, code -> entry("$id-$i", code) }
        return LabReport(id, date(), PATIENTS.random(this), AUTHORS.random(this), entries)
    }

    private fun Random.date() = LocalDate(nextInt(1950, 2100), nextInt(1, 13), nextInt(1, 29))

    private fun Random.entry(id: String, code: String): LabReport.Entry {
        val name = NAMES.random(this)
        return when (nextInt(4)) {
            0 -> LabReport.Entry(id, code, name, WORDS.random(this), null, "")
            1 -> LabReport.Entry(id, code, name, SHAPES.random(this), null, UNITS.random(this))
            else -> number().let { LabReport.Entry(id, code, name, NumberFormatting.exactValue(it), it, UNITS.random(this)) }
        }
    }

    /** Numbers as labs print them, and doubles whose every digit counts. */
    private fun Random.number(): Double {
        val value = when (nextInt(6)) {
            0 -> nextInt(1, 200_000).toDouble()
            1 -> nextInt(1, 20_000) / 10.0
            2 -> nextInt(1, 2_000_000) / 1000.0
            3 -> nextInt(1, 100_000) / 100_000.0
            4 -> nextDouble(1.0, 1e7)
            else -> nextDouble(1e-7, 1e-3)
        }
        return if (nextInt(20) == 0) -value else value
    }

    private companion object {
        const val SEED = 20261107
        const val REPORTS = 500

        val CODES = listOf(
            "2160-0", "2823-3", "4548-4", "718-7", "2093-3", "2085-9", "13457-7", "2571-8", "1742-6", "1920-8", "6690-2",
            "789-8", "777-3", "2951-2", "3016-3", "1988-5", "5196-1", "5048-4", "20454-5", "8061-4", "5671-3",
        )
        val UNITS = listOf(
            "", "mg/dl", "mg/dL", "mmol/l", "%", "U/l", "µg/l", "μg/dl", "ng/ml", "g/dl", "fl", "pg", "/µl", "10^3/µl", "Mio/µl",
            "Tsd/µl", "S/CO", "µkat/l", "ml/min/1,73m2KOF", "mg / dl", "IU/ml", "x10E3/uL", "Ratio", "<custom&unit>",
        )
        val WORDS = listOf(
            "Negativ", "nicht reaktiv", "not detected", "Speckled", "positiv (+)", "阴性", "отрицательно", "陰性", "Çok az", "A&B <x> \"q\"",
            "Spuren ✓", "😀 reaktiv", "négatif", "ne'gatif",
        )
        val SHAPES = listOf("<0.5", "<0,5", "> 90", ">= 12", "≤ 5,0", "≥60", "1:160", "1 : 80", "1:1000", "2+", "+++", "4+", "<1.0E")
            .filter { QualitativeResult.of(it) != null }
        val NAMES = listOf(
            "Kreatinin", "Hämoglobin", "血红蛋白", "Гемоглобин", "Créatinine", "Test & <b>\"Wert\"</b>", "😀 Ferritin", "ヘモグロビン", "ΗΒ", "C-Peptid 'frei'",
        )
        val PATIENTS = listOf("", "Max Mustermann", "Zoë O'Connor & Sohn", "山田 太郎", "Ιωάννης <Ν.>", "Müller \"Mü\"", "🧑 Test")
        val AUTHORS = listOf("", "Labor & Co", "Dr. Müller <Labor>", "北京检验所", "Quest \"Diagnostics\"", "Лаборатория №1")
    }
}
