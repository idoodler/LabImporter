package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.export.CdaNarrative
import dev.idoodler.labimporter.core.export.CdaParser
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.ReportEncodingException
import dev.idoodler.labimporter.core.export.ReportReadProblem
import dev.idoodler.labimporter.core.export.ReportSchema
import dev.idoodler.labimporter.core.export.Ucum
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.QualitativeResult
import dev.idoodler.labimporter.core.parse.AiLabEntry
import dev.idoodler.labimporter.core.parse.AiLabReport
import dev.idoodler.labimporter.core.parse.LabParsePipeline
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
import kotlin.test.assertFalse
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
        assertTrue(xml.contains("<softwareName>LabImporter CDA v${ReportSchema.VERSION}</softwareName>"))
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
        assertNull(CdaParser.parse(xml.replace("LabImporter CDA v${ReportSchema.VERSION}", "Other App")))
        assertNull(CdaParser.parse(xml.replace("LabImporter CDA v${ReportSchema.VERSION}", "LabImporter CDA v99")))
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
        assertEquals(listOf("2160-0", "2823-3", "4548-4", "1742-6", "6690-2"), report.entries.map { it.code })
        assertEquals("10*9/L", report.entries.last().unit, "a count read back as UCUM writes it")
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
        assertTrue(xml.contains("<softwareName>LabImporter CDA v${ReportSchema.VERSION}</softwareName>"))
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

    // The tests below came from mutation testing (tools/quality/mutation.sh): each pins a rule
    // whose opposite the other tests let through.

    @Test
    fun onlyASelectedMappedResultMakesADocumentWorthWriting() {
        assertTrue(CdaExport.hasExportableValues(values))
        assertFalse(CdaExport.hasExportableValues(values.filter { it.code == "KREA" || !it.isSelected }))
        assertFalse(CdaExport.hasExportableValues(emptyList()))
    }

    /** The observations of [xml], in order: each is replaced by what [change] makes of it. */
    private fun editObservations(xml: String, change: (index: Int, observation: String) -> String): String {
        var index = 0
        return Regex("<observation classCode=\"OBS\".*?</observation>", RegexOption.DOT_MATCHES_ALL).replace(xml) { change(index++, it.value) }
    }

    @Test
    fun anObservationNeverBorrowsTheValueOrCodeOfTheOneBefore() {
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test", GoldenCda.narrative)
        // The second observation's number is unreadable (its unit is still there): it's no potassium
        // of 0.8, it's no value at all. And the same without its value element.
        val unreadable = editObservations(xml) { index, it -> if (index == 1) it.replace("value=\"4.1\"", "value=\"\"") else it }
        assertEquals(listOf("2160-0" to 0.8), assertNotNull(CdaParser.parse(unreadable)).entries.map { it.code to it.numericValue })
        val noValue = editObservations(xml) { index, it -> if (index == 1) it.replace(Regex("<value [^>]*/>"), "") else it }
        assertEquals(listOf("2160-0" to 0.8), assertNotNull(CdaParser.parse(noValue)).entries.map { it.code to it.numericValue })
        // Nor does a value without its own LOINC code belong to the test before it.
        val noCode = editObservations(xml) { index, it -> if (index == 1) it.replace("codeSystem=\"2.16.840.1.113883.6.1\"", "codeSystem=\"other\"") else it }
        assertEquals(listOf("2160-0" to 0.8), assertNotNull(CdaParser.parse(noCode)).entries.map { it.code to it.numericValue })
        // A text result doesn't carry over to a number that lost its value.
        val text = LabValue(code = "5196-1", name = "HBsAg", displayValue = "negativ", numericValue = null, unit = "")
        val mixed = CdaExport.generate(listOf(text, values[1]), LocalDate(2026, 5, 6), "LabImporter test", GoldenCda.narrative)
        val textThenNothing = editObservations(mixed) { index, it -> if (index == 1) it.replace(Regex("<value [^>]*/>"), "") else it }
        assertEquals(listOf("5196-1" to "negativ"), assertNotNull(CdaParser.parse(textThenNothing)).entries.map { it.code to it.displayValue })
    }

    @Test
    fun aCdaObservationThisBuildCanNotReadMakesTheReportReadOnly() {
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test", GoldenCda.narrative)
        val intact = assertNotNull(CdaParser.parse(xml))
        assertEquals(0, intact.unreadValues)
        assertFalse(intact.isReadOnly)

        // A later build's value type (here a coded result), written under the same schema version.
        val coded = editObservations(xml) { index, it ->
            if (index == 1) it.replace(Regex("<value [^>]*/>"), "<value xsi:type=\"CD\" code=\"260385009\" codeSystem=\"2.16.840.1.113883.6.96\"/>") else it
        }
        val report = assertNotNull(CdaParser.parse(coded))
        assertEquals(listOf("2160-0"), report.entries.map { it.code })
        assertEquals(1, report.unreadValues, "Seen but not read: counted, not dropped")
        assertTrue(report.isReadOnly)

        // Damage counts the same way: a number that isn't one, or no LOINC code.
        val broken = editObservations(xml) { index, it ->
            when (index) {
                0 -> it.replace("value=\"0.8\"", "value=\"\"")
                else -> it.replace("codeSystem=\"2.16.840.1.113883.6.1\"", "codeSystem=\"other\"")
            }
        }
        val empty = assertNotNull(CdaParser.parse(broken))
        assertEquals(emptyList(), empty.entries)
        assertEquals(2, empty.unreadValues)
    }

    @Test
    fun aFhirValueThisBuildCanNotReadMakesTheReportReadOnlyAndKeepsItsRecordId() {
        val report = LabReport(
            "r", LocalDate(2026, 5, 6), "", "",
            listOf(
                LabReport.Entry("r-1", "2160-0", "Creatinine", "0.8", 0.8, "mg/dl"),
                LabReport.Entry("r-2", "2823-3", "Potassium", "4.1", 4.1, "mmol/l"),
                LabReport.Entry("r-3", "4548-4", "HbA1c", "6.5", 6.5, "%"),
            ),
        )
        val (first, second, third) = FhirLabCodec.encodeForSaving(report, "x").map { it.json }
        val intact = FhirLabCodec.decodeStored(listOf(first, second, third)).reports.single()
        assertEquals(0, intact.unreadValues)
        assertFalse(intact.isReadOnly)

        // A later build's value type in place of the quantity, and a value that lost its date.
        val coded = Regex("\"valueQuantity\":\\{[^}]*\\}").replace(second, "\"valueCodeableConcept\":{\"text\":\"positive\"}")
        val undated = third.replace("effectiveDateTime", "lostTheDate")
        val stored = FhirLabCodec.decodeStored(listOf(first, coded, undated))

        val read = stored.reports.single()
        assertEquals(listOf("r-1"), read.entries.map { it.id }, "Never a \"-\" entry that a save would then drop")
        assertEquals(2, read.unreadValues)
        assertEquals(setOf("r-2", "r-3"), read.unreadRecordIds.toSet(), "So deleting the report removes them too")
        assertTrue(read.isReadOnly)
        assertEquals(1, stored.unreadableReports)
    }

    @Test
    fun theReportDateIsTheDocumentsNotTheLastObservations() {
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test", GoldenCda.narrative)
        val documentDate = "<effectiveTime value=\"20260506\"/>"
        val end = xml.indexOf(documentDate) + documentDate.length
        val laterDates = xml.substring(0, end) + xml.substring(end).replace("value=\"20260506\"", "value=\"20250101\"")
        assertEquals(LocalDate(2026, 5, 6), assertNotNull(CdaParser.parse(laterDates)).date)
    }

    @Test
    fun theVersionStampIsReadWhereverItStands() {
        assertEquals(12, CdaParser.schemaVersion("LabImporter CDA v12"))
        assertEquals(2, CdaParser.schemaVersion("CDA v2"))
        assertNull(CdaParser.schemaVersion("LabImporter"))
        // A stamp at the very start of the text is still a stamp.
        assertEquals(ReportReadProblem.UNREADABLE, CdaParser.problem("<softwareName>LabImporter CDA v${ReportSchema.VERSION}</softwareName>"))
    }

    @Test
    fun fhirListsTheNewestReportFirstAndCountsADamagedReportOnce() {
        fun resources(id: String, date: String, damaged: Boolean = false) = FhirLabCodec.encodeForSaving(
            LabReport(
                id, LocalDate.parse(date), "", "",
                listOf(
                    LabReport.Entry("$id-1", "2160-0", "Creatinine", "0.8", 0.8, "mg/dl"),
                    LabReport.Entry("$id-2", "2823-3", "Potassium", "4.1", 4.1, "mmol/l"),
                ),
            ),
            "x",
        ).map { if (damaged) it.json.replace("\"effectiveDateTime\":\"$date\"", "\"effectiveDateTime\":\"soon\"") else it.json }

        val stored = FhirLabCodec.decodeStored(
            resources("old", "2025-01-02") + resources("new", "2026-05-06") + resources("mid", "2025-09-30") +
                resources("hurt", "2026-01-01", damaged = true),
        )
        assertEquals(listOf("new", "mid", "old"), stored.reports.map { it.id })
        assertEquals(1, stored.unreadableReports, "Both of its resources are damaged, but it's one report")
    }

    private val counts = listOf(
        // French labs print leukocytes and platelets in G/L, German ones in Gpt/l: 10⁹ cells a litre.
        LabValue(code = "6690-2", name = "Leucocytes", displayValue = "7,2", numericValue = 7.2, unit = "G/L"),
        LabValue(code = "777-3", name = "Thrombozyten", displayValue = "263", numericValue = 263.0, unit = "Gpt/l"),
        LabValue(code = "789-8", name = "Erythrocytes", displayValue = "4,52", numericValue = 4.52, unit = "T/L"),
        // A real mass concentration in grams a litre.
        LabValue(code = "2885-2", name = "Protéines", displayValue = "70", numericValue = 70.0, unit = "g/L"),
    )

    private fun quantityCodes(resources: List<String>) = resources.map {
        Json.parseToJsonElement(it).jsonObject.getValue("valueQuantity").jsonObject.getValue("code").jsonPrimitive.content
    }

    @Test
    fun aCountPerLitreIsExportedAsACountNeverAsGrams() {
        val xml = CdaExport.generate(counts, LocalDate(2026, 5, 6), "x", GoldenCda.narrative)
        assertEquals(listOf("10*9/L", "10*9/L", "10*12/L", "g/L"), assertNotNull(CdaParser.parse(xml)).entries.map { it.unit })
        val resources = FhirLabCodec.encode(LabReport.fromValues(counts, LocalDate(2026, 5, 6), "", ""), "x").map { it.json }
        assertEquals(listOf("10*9/L", "10*9/L", "10*12/L", "g/L"), quantityCodes(resources))
        // Health Connect shows the unit as printed.
        assertEquals(listOf("G/L", "Gpt/l", "T/L", "g/L"), FhirLabCodec.decode(resources).single().entries.map { it.unit })
    }

    @Test
    fun theTermDecidesBetweenGigaAndGramsWhereTheLetterCaseCanNot() {
        assertEquals("g/L", Ucum.fromPrinted("G/L", "2885-2"), "an all-capitals report's protein is still grams")
        assertEquals("10*9/L", Ucum.fromPrinted("g/l", "6690-2"), "a count per volume is never grams")
        assertEquals("10*9/L", Ucum.fromPrinted("G/l", "NOT-A-CODE"), "without a known term, the capital G decides")
        assertEquals("g/L", Ucum.fromPrinted("g/L"))
        assertEquals("10*9/L", Ucum.fromPrinted("GPT/L"))
        assertEquals("10*12/L", Ucum.fromPrinted("Tpt/l"))
    }

    @Test
    fun aPrintedNoteIsNoPartOfTheUnit() {
        assertEquals("mg/dL", Ucum.fromPrinted("mg/dL (calc)"))
        assertEquals("U/L", Ucum.fromPrinted("U/l (37 °C)"))
        assertEquals("mL/min/{1.73_m2}", Ucum.fromPrinted("mL/min/1.73 m2 (CKD-EPI)"))
        assertEquals("mg/dL", Ucum.withoutAnnotation("mg/dL(calc)"))
        // A part of the unit in parentheses, or a unit all in parentheses, is no note.
        assertEquals("mL/min/(1.73 m2)", Ucum.withoutAnnotation("mL/min/(1.73 m2)"))
        assertEquals("(mmol/l)", Ucum.withoutAnnotation("(mmol/l)"))
        // Nor is a power in parentheses (Cerner, MyChart): "x10/L" would be off by 10⁸.
        assertEquals("x10(9)/L", Ucum.withoutAnnotation("x10(9)/L"))
        assertEquals("x10(3)/mcL", Ucum.fromPrinted("x10(3)/mcL"))
        assertEquals("10(12)/L", Ucum.withoutAnnotation("10(12)/L (calc)"))
        assertEquals("mL/min/1.73m2", Ucum.withoutAnnotation("mL/min/1.73m2(CKD-EPI)"), "a note behind a digit is still a note")
        val ldl = LabValue(code = "13457-7", name = "LDL", displayValue = "138", numericValue = 138.0, unit = "mg/dL (calc)")
        val xml = CdaExport.generate(listOf(ldl), LocalDate(2026, 5, 6), "x", GoldenCda.narrative)
        assertTrue(xml.contains("value=\"138\" unit=\"mg/dL\""), xml)
        // Review shows the unit without the note, and the report printing it backs it.
        val read = LabParsePipeline.postProcess(
            AiLabReport(listOf(AiLabEntry("LDL-Cholesterol", "LDL-Cholesterol", "138", "H mg/dL (calc)")), reportLanguage = "en"),
            "LDL-Cholesterol         138       H      mg/dL (calc)   <100",
        ).values.single()
        assertEquals("mg/dL", read.unit)
        assertFalse(read.needsSourceCheck)
    }

    @Test
    fun aVersion1DocumentsCountInGramsReadsBackAsACount() {
        val ldl = LabValue(code = "13457-7", name = "LDL", displayValue = "138", numericValue = 138.0, unit = "mg/dL")
        val current = CdaExport.generate(counts.take(1) + counts.last() + ldl, LocalDate(2026, 5, 6), "x", GoldenCda.narrative)
        // What version 1 wrote for the same report: G/L as grams, and the printed note in the unit.
        val old = current.replace("LabImporter CDA v${ReportSchema.VERSION}", "LabImporter CDA v1")
            .replace("unit=\"10*9/L\"", "unit=\"g/L\"")
            .replace("unit=\"mg/dL\"", "unit=\"mg/dL (calc)\"")
        assertEquals(2, Regex("unit=\"g/L\"").findAll(old).count())
        val report = assertNotNull(CdaParser.parse(old))
        assertEquals(listOf(7.2, 70.0, 138.0), report.entries.map { it.numericValue })
        // Apple Health shows the leukocytes as a count again; the protein stays grams.
        assertEquals(listOf("10*9/L", "g/L", "mg/dL"), report.entries.map { it.unit })
        // Saved again, it has the units of a version 2 document.
        fun units(xml: String) = Regex("unit=\"([^\"]*)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(units(current), units(CdaExport.generate(report.asLabValues, report.date, "x", GoldenCda.narrative)))
    }

    @Test
    fun aVersion1PowerInParenthesesStaysPartOfTheUnit() {
        val entries = listOf(
            LabReport.Entry("1", "6690-2", "WBC", "6.8", 6.8, "x10(9)/L"),
            LabReport.Entry("2", "777-3", "Platelets", "245", 245.0, "x10(3)/mcL"),
        )
        val report = LabReport("r", LocalDate(2026, 5, 6), "", "", entries)
        val version1 = FhirLabCodec.encode(report, "x").map {
            it.json.replace("\"code\":\"${ReportSchema.VERSION}\"", "\"code\":\"1\"")
        }
        assertEquals(listOf("x10(9)/L", "x10(3)/mcL"), FhirLabCodec.decode(version1).single().entries.map { it.unit })
        val cda = CdaExport.generate(report.asLabValues, report.date, "x", GoldenCda.narrative)
            .replace("LabImporter CDA v${ReportSchema.VERSION}", "LabImporter CDA v1")
        assertEquals(listOf("x10(9)/L", "x10(3)/mcL"), assertNotNull(CdaParser.parse(cda)).entries.map { it.unit })
    }

    @Test
    fun aVersion1FhirRecordKeepsItsPrintedUnitUnlessItWasGrams() {
        val entries = listOf(
            LabReport.Entry("1", "6690-2", "Leucocytes", "7.2", 7.2, "G/L"),
            LabReport.Entry("2", "777-3", "Plaquettes", "245", 245.0, "g/l"),
            LabReport.Entry("3", "2885-2", "Protéines", "70", 70.0, "g/L"),
            LabReport.Entry("4", "13457-7", "LDL", "138", 138.0, "mg/dL (calc)"),
            LabReport.Entry("5", "5196-1", "HBsAg", "negativ", null, ""),
        )
        val report = LabReport("r", LocalDate(2026, 5, 6), "", "", entries)
        val version1 = FhirLabCodec.encode(report, "x").map {
            it.json.replace("\"code\":\"${ReportSchema.VERSION}\"", "\"code\":\"1\"")
        }
        assertTrue(version1.all { "\"code\":\"1\"" in it })
        val migrated = FhirLabCodec.decode(version1).single()
        // "G/L" was always shown right; a count printed "g/l" wasn't, nor was the note.
        assertEquals(listOf("G/L", "10*9/L", "g/L", "mg/dL", ""), migrated.entries.map { it.unit })
        assertEquals(
            listOf("10*9/L", "10*9/L", "g/L", "mg/dL"),
            quantityCodes(FhirLabCodec.encode(migrated, "x").map { it.json }.filter { "valueQuantity" in it }),
        )
    }
}
