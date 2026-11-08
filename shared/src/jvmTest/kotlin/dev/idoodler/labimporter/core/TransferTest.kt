package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.ReportSchema
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.transfer.ReportTransfer
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferTest {

    @BeforeTest
    fun start() = startCatalog()

    private fun report(id: String, date: LocalDate, vararg readings: Triple<String, Double, String>) = LabReport(
        id, date, "Max", "Labor",
        readings.mapIndexed { i, (code, value, unit) -> LabReport.Entry("$id-$i", code, code, value.toString(), value, unit) },
    )

    private val may = report("may", LocalDate(2026, 5, 6), Triple("2160-0", 0.8, "mg/dl"), Triple("2823-3", 4.1, "mmol/l"))
    private val sep = report("sep", LocalDate(2026, 9, 10), Triple("2160-0", 0.93, "mg/dl"))

    @Test
    fun bundleRoundTrip() {
        val file = ReportTransfer.export(listOf(sep, may), "LabImporter test").contents
        assertTrue(file.contains("\"resourceType\":\"Bundle\""))
        val read = assertIs<ReportTransfer.ReadResult.Reports>(ReportTransfer.read(file))
        assertEquals(setOf("may", "sep"), read.reports.map { it.id }.toSet())
        assertEquals(
            read.reports.map(ReportTransfer::fingerprint).toSet(),
            listOf(may, sep).map(ReportTransfer::fingerprint).toSet(),
        )
    }

    @Test
    fun cdaFromEitherAppImports() {
        val xml = CdaExport.generate(may.asLabValues, may.date, "LabImporter iOS", GoldenCda.narrative)
        val read = assertIs<ReportTransfer.ReadResult.Reports>(ReportTransfer.read(xml))
        assertEquals(ReportTransfer.fingerprint(may), ReportTransfer.fingerprint(read.reports.single()))
    }

    @Test
    fun importingTwiceAddsNothing() {
        val file = ReportTransfer.export(listOf(may, sep), "LabImporter test").contents
        val incoming = (ReportTransfer.read(file) as ReportTransfer.ReadResult.Reports).reports
        val first = ReportTransfer.plan(existing = emptyList(), incoming = incoming)
        assertEquals(2, first.toImport.size)
        val second = ReportTransfer.plan(existing = first.toImport, incoming = incoming)
        assertEquals(0, second.toImport.size)
        assertEquals(2, second.alreadyPresent)
    }

    @Test
    fun fingerprintIgnoresUnitSpellingAndExportPrecision() {
        // iOS reads units back as UCUM ("mg/dL"), older exports carry values at %.4g; Android keeps what was printed.
        val ios = report("a", may.date, Triple("2160-0", 0.8, "mg/dL"), Triple("2823-3", 4.1, "mmol/L"))
        assertEquals(ReportTransfer.fingerprint(may), ReportTransfer.fingerprint(ios))
        val precise = report("b", sep.date, Triple("2160-0", 0.93000001, "mg/dl"))
        assertEquals(ReportTransfer.fingerprint(sep), ReportTransfer.fingerprint(precise))
        val other = report("c", sep.date, Triple("2160-0", 0.94, "mg/dl"))
        assertTrue(ReportTransfer.fingerprint(sep) != ReportTransfer.fingerprint(other))
    }

    @Test
    fun foreignFilesAreRejected() {
        for (content in listOf("", "hello", "{}", "{\"resourceType\":\"Patient\"}", "[1,2]", "<html></html>")) {
            assertIs<ReportTransfer.ReadResult.Unsupported>(ReportTransfer.read(content), content)
        }
        val unversioned = CdaExport.generate(may.asLabValues, may.date, "x", GoldenCda.narrative).replace("LabImporter CDA v1", "Other")
        assertIs<ReportTransfer.ReadResult.Unsupported>(ReportTransfer.read(unversioned))
    }

    @Test
    fun anExportSaysWhatItLeftOut() {
        val unmapped = LabReport(
            "local", may.date, "", "",
            listOf(
                LabReport.Entry("ok", "2160-0", "Creatinine", "0.8", 0.8, "mg/dl"),
                // The catalog doesn't know this code, so there's no FHIR resource for it.
                LabReport.Entry("gone", "NOT-A-CODE", "Something", "1", 1.0, "mg/dl"),
                LabReport.Entry("empty", "2823-3", "Potassium", "-", null, "mmol/l"),
            ),
        )
        val export = ReportTransfer.export(listOf(may, unmapped), "LabImporter test")
        assertEquals(3, export.writtenResults)
        assertEquals(1, export.leftOutResults, "A value without a result has nothing to lose")
        val read = assertIs<ReportTransfer.ReadResult.Reports>(ReportTransfer.read(export.contents))
        assertEquals(listOf("2160-0"), read.reports.single { it.id == "local" }.entries.map { it.code })
    }

    @Test
    fun anExportWithNothingToWriteSaysSo() {
        val export = ReportTransfer.export(listOf(report("x", may.date, Triple("NOT-A-CODE", 1.0, ""))), "LabImporter test")
        assertEquals(0, export.writtenResults)
        assertEquals(1, export.leftOutResults)
    }

    @Test
    fun aFileFromANewerVersionAsksForAnUpdate() {
        val future = "\"code\":\"${ReportSchema.VERSION + 1}\""
        val current = "\"code\":\"${ReportSchema.VERSION}\""
        val onlyNewer = ReportTransfer.export(listOf(may), "x").contents.replace(current, future)
        assertIs<ReportTransfer.ReadResult.NewerVersion>(ReportTransfer.read(onlyNewer))

        val cda = CdaExport.generate(may.asLabValues, may.date, "x", GoldenCda.narrative)
            .replace("LabImporter CDA v${ReportSchema.VERSION}", "LabImporter CDA v${ReportSchema.VERSION + 1}")
        assertIs<ReportTransfer.ReadResult.NewerVersion>(ReportTransfer.read(cda))
    }

    @Test
    fun aFileWithSomeNewerReportsImportsTheRestAndCountsThem() {
        val future = "\"code\":\"${ReportSchema.VERSION + 1}\""
        val current = "\"code\":\"${ReportSchema.VERSION}\""
        val newer = FhirLabCodec.encodeForSaving(may, "x").map { it.json.replace(current, future) }
        val readable = FhirLabCodec.encodeForSaving(sep, "x").map { it.json }
        val file = "{\"resourceType\":\"Bundle\",\"entry\":[" + (newer + readable).joinToString(",") { "{\"resource\":$it}" } + "]}"
        val read = assertIs<ReportTransfer.ReadResult.Reports>(ReportTransfer.read(file))
        assertEquals(listOf("sep"), read.reports.map { it.id })
        assertEquals(1, read.newerVersionReports, "Two values of one report are one report")
    }

    @Test
    fun aDamagedFileSaysHowManyReportsItCouldntCarry() {
        // One of May's two values and September's only one have no date any more.
        val mayValues = FhirLabCodec.encodeForSaving(may, "x").map { it.json }
        val damaged = listOf(mayValues.first().replace("effectiveDateTime", "lost"), mayValues.last()) +
            FhirLabCodec.encodeForSaving(sep, "x").map { it.json.replace("effectiveDateTime", "lost") }
        val file = "{\"resourceType\":\"Bundle\",\"entry\":[" + damaged.joinToString(",") { "{\"resource\":$it}" } + "]}"

        val read = assertIs<ReportTransfer.ReadResult.Reports>(ReportTransfer.read(file))

        assertEquals(2, read.unreadableReports, "September is gone, and May lost a value")
        assertEquals(listOf("may"), read.reports.map { it.id })
        assertEquals(1, read.reports.single().entries.size)
    }

    @Test
    fun anIntactFileHasNothingDamaged() {
        val read = assertIs<ReportTransfer.ReadResult.Reports>(ReportTransfer.read(ReportTransfer.export(listOf(may, sep), "x").contents))
        assertEquals(0, read.unreadableReports)
    }
}
