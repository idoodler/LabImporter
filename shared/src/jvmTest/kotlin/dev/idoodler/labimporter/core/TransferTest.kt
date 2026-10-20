package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaExport
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
        val file = ReportTransfer.export(listOf(sep, may), "LabImporter test")
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
        val xml = CdaExport.generate(may.asLabValues, may.date, "LabImporter iOS")
        val read = assertIs<ReportTransfer.ReadResult.Reports>(ReportTransfer.read(xml))
        assertEquals(ReportTransfer.fingerprint(may), ReportTransfer.fingerprint(read.reports.single()))
    }

    @Test
    fun importingTwiceAddsNothing() {
        val file = ReportTransfer.export(listOf(may, sep), "LabImporter test")
        val incoming = (ReportTransfer.read(file) as ReportTransfer.ReadResult.Reports).reports
        val first = ReportTransfer.plan(existing = emptyList(), incoming = incoming)
        assertEquals(2, first.toImport.size)
        val second = ReportTransfer.plan(existing = first.toImport, incoming = incoming)
        assertEquals(0, second.toImport.size)
        assertEquals(2, second.alreadyPresent)
    }

    @Test
    fun fingerprintIgnoresUnitSpellingAndExportPrecision() {
        // iOS reads units back as UCUM ("mg/dL") and values at %.4g; Android keeps what was printed.
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
        val unversioned = CdaExport.generate(may.asLabValues, may.date, "x").replace("LabImporter CDA v1", "Other")
        assertIs<ReportTransfer.ReadResult.Unsupported>(ReportTransfer.read(unversioned))
    }
}
