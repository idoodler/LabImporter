package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.export.CdaParser
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.LabValue
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportTest {

    @BeforeTest
    fun start() = startCatalog()

    private val values = listOf(
        LabValue(code = "2160-0", name = "Creatinin", displayValue = "0,8", numericValue = 0.8, unit = "mg/dl"),
        LabValue(code = "2823-3", name = "Kalium", displayValue = "4,1", numericValue = 4.1, unit = "mmol/l"),
        LabValue(code = "KREA", name = "unmapped", displayValue = "1", numericValue = 1.0, unit = ""),
        LabValue(code = "4548-4", name = "HbA1c", displayValue = "6,5", numericValue = 6.5, unit = "%", isSelected = false),
    )

    @Test
    fun cdaRoundTrip() {
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test", "Max Mustermann", "Labor & Co")
        assertTrue(xml.contains("<softwareName>LabImporter CDA v1</softwareName>"))
        assertTrue(xml.contains("value=\"0.8\" unit=\"mg/dL\""))
        assertTrue(xml.contains("<name>Labor &amp; Co</name>"))
        val report = assertNotNull(CdaParser.parse(xml, "id-1"))
        assertEquals("id-1", report.id)
        assertEquals(LocalDate(2026, 5, 6), report.date)
        assertEquals("Max Mustermann", report.patientName)
        assertEquals("Labor & Co", report.authorName)
        // Only selected, numeric, LOINC-mapped values are exported.
        assertEquals(listOf("2160-0" to 0.8, "2823-3" to 4.1), report.entries.map { it.code to it.numericValue })
        assertEquals(listOf("mg/dL", "mmol/L"), report.entries.map { it.unit })
    }

    @Test
    fun cdaPlaceholdersAreIgnored() {
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test")
        val report = assertNotNull(CdaParser.parse(xml))
        assertEquals("", report.patientName) // "Unknown" placeholder
        assertEquals("", report.authorName)
    }

    @Test
    fun unversionedOrForeignCdaIsSkipped() {
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test")
        assertNull(CdaParser.parse(xml.replace("LabImporter CDA v1", "Other App")))
        assertNull(CdaParser.parse(xml.replace("LabImporter CDA v1", "LabImporter CDA v99")))
        assertNull(CdaParser.parse("not xml at all"))
        assertNull(CdaParser.parse("<ClinicalDocument><broken"))
    }

    @Test
    fun fhirRoundTrip() {
        val report = LabReport.fromValues(values, LocalDate(2026, 5, 6), "Max", "Labor")
        assertEquals(2, report.entries.size)
        val resources = FhirLabCodec.encode(report, "LabImporter test")
        assertTrue(resources.all { it.json.contains("\"laboratory\"") })
        val decoded = FhirLabCodec.decode(resources.map { it.json }).single()
        assertEquals(report.id, decoded.id)
        assertEquals(report.date, decoded.date)
        assertEquals("Max", decoded.patientName)
        assertEquals("Labor", decoded.authorName)
        assertEquals(report.entries.map { it.code to it.numericValue }, decoded.entries.map { it.code to it.numericValue })
    }

    @Test
    fun fhirWithoutOurTagsIsIgnored() {
        val foreign = """{"resourceType":"Observation","id":"x","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"2160-0"}]},"effectiveDateTime":"2026-01-01","valueQuantity":{"value":1}}"""
        assertTrue(FhirLabCodec.decode(listOf(foreign)).isEmpty())
    }
}
