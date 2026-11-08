package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.export.CdaNarrative
import dev.idoodler.labimporter.core.export.CdaParser
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.ReportEncodingException
import dev.idoodler.labimporter.core.export.ReportReadProblem
import dev.idoodler.labimporter.core.export.ReportSchema
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.QualitativeResult
import dev.idoodler.labimporter.core.transfer.ReportTransfer
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test", GoldenCda.narrative, "Max Mustermann", "Labor & Co")
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
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test", GoldenCda.narrative)
        val report = assertNotNull(CdaParser.parse(xml))
        assertEquals("", report.patientName) // "Unknown" placeholder
        assertEquals("", report.authorName)
    }

    @Test
    fun unversionedOrForeignCdaIsSkipped() {
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test", GoldenCda.narrative)
        assertNull(CdaParser.parse(xml.replace("LabImporter CDA v1", "Other App")))
        assertNull(CdaParser.parse(xml.replace("LabImporter CDA v1", "LabImporter CDA v99")))
        assertNull(CdaParser.parse("not xml at all"))
        assertNull(CdaParser.parse("<ClinicalDocument><broken"))
    }

    @Test
    fun aDocumentDeclaresTheCallersLanguageAndHeadings() {
        val french = CdaNarrative("fr", "Résultats d'analyses", "Valeurs <labo>", "Analyse", "Résultat")
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test", french)
        assertTrue(xml.contains("<languageCode code=\"fr\"/>"))
        assertTrue(xml.contains("<title>Résultats d'analyses</title>"))
        assertTrue(xml.contains("<title>Valeurs &lt;labo&gt;</title>"))
        assertTrue(xml.contains("<th>Analyse</th><th>Résultat</th>"))
        assertTrue("Laborwerte" !in xml && "Ergebnis" !in xml && "de-DE" !in xml)
        assertEquals(listOf("2160-0", "2823-3"), assertNotNull(CdaParser.parse(xml)).entries.map { it.code })
    }

    @Test
    fun documentsSavedWithTheGermanHeadingsStillReadBack() {
        val german = File(System.getProperty("golden.dir"), "report.cda.xml").readText()
        assertTrue("<languageCode code=\"de-DE\"/>" in german && "<th>Ergebnis</th>" in german)
        val report = assertNotNull(CdaParser.parse(german.replace("root=\"UUID\"", "root=\"00000000-0000-0000-0000-000000000000\"")))
        assertEquals(LocalDate(2026, 5, 6), report.date)
        assertEquals("Labor & Co", report.authorName)
        assertEquals(listOf("2160-0", "2823-3", "4548-4", "1742-6"), report.entries.map { it.code })
    }

    @Test
    fun whyACdaDocumentWasRefused() {
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test", GoldenCda.narrative)
        val stamp = "LabImporter CDA v${ReportSchema.VERSION}"
        assertEquals(ReportReadProblem.NEWER_VERSION, CdaParser.problem(xml.replace(stamp, "LabImporter CDA v${ReportSchema.VERSION + 1}")))
        // Too damaged to parse, but it's still ours and worth saying so.
        assertEquals(ReportReadProblem.UNREADABLE, CdaParser.problem(xml.substringBefore("</assignedAuthor>")))
        assertNull(CdaParser.problem(xml.replace(stamp, "Other App")), "Unversioned documents are ignored by design")
        assertNull(CdaParser.problem("not xml at all"))
        // A name is escaped, so it can't pass for the version stamp.
        val named = CdaExport.generate(values, LocalDate(2026, 5, 6), "x", GoldenCda.narrative, "<softwareName>LabImporter CDA v99")
        assertEquals(ReportReadProblem.UNREADABLE, CdaParser.problem(named))
    }

    @Test
    fun fhirCountsWhatItCanNotList() {
        val current = "\"code\":\"${ReportSchema.VERSION}\""
        val future = "\"code\":\"${ReportSchema.VERSION + 1}\""
        fun report(id: String) = LabReport(
            id, LocalDate(2026, 5, 6), "", "",
            listOf(
                LabReport.Entry("$id-1", "2160-0", "Creatinine", "0.8", 0.8, "mg/dl"),
                LabReport.Entry("$id-2", "2823-3", "Potassium", "4.1", 4.1, "mmol/l"),
            ),
        )
        fun resources(id: String) = FhirLabCodec.encodeForSaving(report(id), "x").map { it.json }

        val mixed = resources("half").let { (first, second) -> listOf(first, second.replace(current, future)) }
        val stored = FhirLabCodec.decodeStored(
            resources("fine") +
                resources("newer").map { it.replace(current, future) } +
                mixed +
                resources("hurt").let { (first, second) -> listOf(first, second.replace("\"effectiveDateTime\":\"2026-05-06\"", "\"effectiveDateTime\":\"soon\"")) } +
                "{not json",
        )
        assertEquals(listOf("hurt", "fine"), stored.reports.map { it.id }.sorted().reversed())
        assertEquals(2, stored.newerVersionReports, "A report with any newer value isn't shown in part")
        assertEquals(2, stored.unreadableReports, "The report missing a value, and the resource that's no JSON")
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
    fun fhirKeepsTheLoincDisplayInTheCodingWhateverTheAppCallsTheValue() {
        startCatalog("pt-BR")
        val shown = LabValue(code = "2160-0", name = LabMapping.displayName("2160-0"), displayValue = "1", numericValue = 1.0, unit = "mg/dL")
        val report = LabReport.fromValues(listOf(shown), LocalDate(2026, 5, 6), "", "")
        val code = Json.parseToJsonElement(FhirLabCodec.encode(report, "LabImporter test").single().json).jsonObject.getValue("code").jsonObject
        assertEquals("Creat", code.getValue("text").jsonPrimitive.content, "the record's text is what the user saw")
        assertEquals("Creatinine [Mass/Vol]", code.getValue("coding").jsonArray.single().jsonObject.getValue("display").jsonPrimitive.content)
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
        val xml = CdaExport.generate(qualitative, LocalDate(2026, 5, 6), "LabImporter test", GoldenCda.narrative)
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
    fun savingRefusesToDropAResult() {
        fun report(vararg entries: LabReport.Entry) = LabReport("r", LocalDate(2026, 5, 6), "", "", entries.toList())
        val known = LabReport.Entry("a", "2160-0", "Creatinine", "0.8", 0.8, "mg/dl")
        val noResult = LabReport.Entry("b", "2823-3", "Potassium", "-", null, "mmol/l")
        val unknown = LabReport.Entry("c", "NOPE", "Unmapped", "1", 1.0, "")

        // Nothing is lost when an entry without a result stays behind…
        assertEquals(listOf("a"), FhirLabCodec.encodeForSaving(report(known, noResult), "test").map { it.id })
        // …but a result the codec would silently drop, or a report with nothing to store, is an error.
        assertEquals(1, assertFailsWith<ReportEncodingException> { FhirLabCodec.encodeForSaving(report(known, unknown), "test") }.unencoded)
        assertFailsWith<ReportEncodingException> { FhirLabCodec.encodeForSaving(report(noResult), "test") }
        assertFailsWith<ReportEncodingException> { FhirLabCodec.encodeForSaving(report(), "test") }
    }

    @Test
    fun savingWithoutTheCatalogIsAnErrorNotAnEmptySuccess() {
        val report = LabReport("r", LocalDate(2026, 5, 6), "", "", listOf(LabReport.Entry("a", "2160-0", "Creatinine", "0.8", 0.8, "mg/dl")))
        LabCore.start("/nonexistent/loinc.db", "en-US") { LabDisplayPreferences() } // Not unpacked yet, as on a cold start after an update.
        try {
            assertEquals(1, assertFailsWith<ReportEncodingException> { FhirLabCodec.encodeForSaving(report, "test") }.unencoded)
        } finally {
            startCatalog()
        }
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
