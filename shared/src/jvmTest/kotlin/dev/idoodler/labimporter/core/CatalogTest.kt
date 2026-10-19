package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.parse.HeuristicLabParser
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTest {

    @BeforeTest
    fun start() {
        LabCore.start(System.getProperty("loinc.db"), "de-DE") { LabDisplayPreferences() }
        assertTrue(LabCore.loinc!!.isAvailable, "loinc.db missing — run :androidApp:buildLoincDb first")
    }

    @Test
    fun resolvesGermanNamesToLoinc() {
        val (code, suggested) = LabParsePipeline.resolveLoinc("KREA", "Kreatinin", "de")
        assertTrue(suggested)
        assertTrue(LabCore.loinc!!.isKnownLoinc(code), code)
    }

    @Test
    fun heuristicPipelineProducesExportableValues() {
        val result = LabParsePipeline.postProcess(HeuristicLabParser.parse("Creatinin: 0,80 mg/dl\nKalium 4,1 mmol/l"))
        assertEquals(2, result.values.size)
        assertTrue(result.values.all { it.isExportable }, result.values.toString())
    }

    @Test
    fun fallbackGuessesReportLanguage() {
        LabCore.start(System.getProperty("loinc.db"), "en-US") { LabDisplayPreferences() }
        val text = "Patient: Max Mustermann\nCholesterin: 198 mg/dl; Triglyceride: 133 mg/dl\nKalium: 4,1 mmol/l; Natrium: 139 mmol/l"
        val report = HeuristicLabParser.parse(text)
        assertEquals("Max Mustermann", report.patientName)
        assertEquals("de", LabParsePipeline.guessLanguage(report.entries.map { it.name }))
        val result = LabParsePipeline.postProcess(report)
        assertTrue(result.values.count { it.isExportable } >= 3, result.values.toString())
    }

    @Test
    fun fhirRoundTrip() {
        val values = listOf(LabValue(code = "2160-0", name = "Kreatinin", displayValue = "0,8", numericValue = 0.8, unit = "mg/dl"))
        val report = LabReport.fromValues(values, LocalDate(2026, 5, 6), "Max", "Labor")
        val resources = FhirLabCodec.encode(report, "LabImporter test")
        assertTrue(resources.single().json.contains("\"laboratory\""))
        val decoded = FhirLabCodec.decode(resources.map { it.json }).single()
        assertEquals(report.id, decoded.id)
        assertEquals(report.date, decoded.date)
        assertEquals("Labor", decoded.authorName)
        assertEquals(0.8, decoded.entries.single().numericValue)
    }

    @Test
    fun cdaContainsObservation() {
        val values = listOf(LabValue(code = "2160-0", name = "Kreatinin", displayValue = "0,8", numericValue = 0.8, unit = "mg/dl"))
        val xml = CdaExport.generate(values, LocalDate(2026, 5, 6), "LabImporter test")
        assertTrue(xml.contains("code=\"2160-0\""))
        assertTrue(xml.contains("value=\"0.8\" unit=\"mg/dL\""))
        assertTrue(xml.contains("<effectiveTime value=\"20260506\"/>"))
    }
}
