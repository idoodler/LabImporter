package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.export.CdaParser
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.QualitativeResult
import dev.idoodler.labimporter.core.transfer.ReportTransfer
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

    private val qualitative = listOf(
        LabValue(code = "2160-0", name = "Creatinin", displayValue = "0,8", numericValue = 0.8, unit = "mg/dl"),
        LabValue(code = "5196-1", name = "HBsAg", displayValue = "nicht reaktiv", numericValue = null, unit = ""),
        LabValue(code = "5048-4", name = "ANA-Titer", displayValue = "1:160", numericValue = null, unit = ""),
        LabValue(code = "1988-5", name = "CRP", displayValue = "<0,5", numericValue = null, unit = "mg/l"),
        LabValue(code = "20454-5", name = "Eiweiß (Urin)", displayValue = "2+", numericValue = null, unit = ""),
        // A number with stray text is a misread to fix in review, not a result; "-" is no result.
        LabValue(code = "4548-4", name = "HbA1c", displayValue = "6,5 %", numericValue = null, unit = "%"),
        LabValue(code = "8061-4", name = "ANA", displayValue = "-", numericValue = null, unit = ""),
    )

    @Test
    fun qualitativeResultsAreExportedAsText() {
        assertEquals(listOf(true, true, true, true, true, false, false), qualitative.map { it.isExportable })
        val xml = CdaExport.generate(qualitative, LocalDate(2026, 5, 6), "LabImporter test")
        // Additive: the schema version stays, and older builds skip ST observations.
        assertTrue(xml.contains("<softwareName>LabImporter CDA v1</softwareName>"))
        assertTrue(xml.contains("<value xsi:type=\"ST\">nicht reaktiv</value>"))
        assertTrue(xml.contains("<value xsi:type=\"ST\">&lt;0,5 mg/l</value>"))
        val report = assertNotNull(CdaParser.parse(xml))
        assertEquals(
            listOf(
                Triple("0.8", 0.8, "mg/dL"), Triple("nicht reaktiv", null, ""), Triple("1:160", null, ""),
                Triple("<0,5", null, "mg/l"), Triple("2+", null, ""),
            ),
            report.entries.map { Triple(it.displayValue, it.numericValue, it.unit) },
        )
        // Saving a read-back report again writes the same results.
        assertEquals(report.entries.map { it.displayValue }, report.asLabValues.filter { it.isExportable }.map { it.displayValue })
    }

    @Test
    fun qualitativeResultsRoundTripThroughFhir() {
        val report = LabReport.fromValues(qualitative, LocalDate(2026, 5, 6), "Max", "Labor")
        assertEquals(listOf("0.8", "nicht reaktiv", "1:160", "<0,5", "2+"), report.entries.map { it.displayValue })
        val resources = FhirLabCodec.encode(report, "LabImporter test")
        assertEquals(5, resources.size)
        assertTrue(resources[3].json.contains("\"valueString\":\"<0,5 mg/l\""), resources[3].json)
        assertTrue(resources.drop(1).none { it.json.contains("valueQuantity") })
        val decoded = FhirLabCodec.decode(resources.map { it.json }).single()
        assertEquals(
            report.entries.map { Triple(it.displayValue, it.numericValue, it.unit) },
            decoded.entries.map { Triple(it.displayValue, it.numericValue, it.unit) },
        )
        // The transfer file recognizes the same report again.
        assertEquals(ReportTransfer.fingerprint(report), ReportTransfer.fingerprint(decoded))
    }

    @Test
    fun whatCountsAsAQualitativeResult() {
        for (text in listOf("Negativ", "not detected", "Speckled", "1:160", "1 : 80", "<0.5", "> 90", "≤ 5,0", "2+", "+++")) {
            assertEquals(text, QualitativeResult.of(" $text "), text)
        }
        for (text in listOf("", "-", "–", "6.5 %", "0,8 H", "12", "3-5", "*")) {
            assertNull(QualitativeResult.of(text), text)
        }
        assertEquals("<0.5" to "mg/dL", QualitativeResult.split("<0.5 mg/dL"))
        assertEquals("1:160" to "", QualitativeResult.split("1:160"))
        assertEquals("not detected" to "", QualitativeResult.split("not detected"))
    }

    @Test
    fun fhirWithoutOurTagsIsIgnored() {
        val foreign = """{"resourceType":"Observation","id":"x","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"2160-0"}]},"effectiveDateTime":"2026-01-01","valueQuantity":{"value":1}}"""
        assertTrue(FhirLabCodec.decode(listOf(foreign)).isEmpty())
    }
}
