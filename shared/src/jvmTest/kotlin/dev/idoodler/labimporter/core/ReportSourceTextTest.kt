package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.valuesToCheckCount
import dev.idoodler.labimporter.core.model.valuesToCorrectCount
import dev.idoodler.labimporter.core.parse.AiLabEntry
import dev.idoodler.labimporter.core.parse.AiLabReport
import dev.idoodler.labimporter.core.parse.LabParseChunks
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import dev.idoodler.labimporter.core.parse.ReportSourceText
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review stops a wrong value by showing what the report text doesn't print. */
class ReportSourceTextTest {
    @BeforeTest
    fun start() = startCatalog()

    private fun prints(text: String, result: String) = ReportSourceText(text).prints(result)

    @Test
    fun aNumberMustBePrintedWhole() {
        val alt = "ALT (GPT)             22             U/l            < 35"
        assertTrue(prints(alt, "22"))
        // evaluation/README.md: the model read ALT 22 as 222.
        assertFalse(prints(alt, "222"))
        assertFalse(prints("ALT 2220 U/l", "222"), "not inside a longer number")
        assertFalse(prints("ALT 222 U/l", "22"), "not a part of a longer number")
        assertFalse(prints("Glucose 5.1 mmol/l", "5"), "not the whole number in front of a decimal")
        assertFalse(prints("Glucose 5.1 mmol/l", "1"), "not the digits behind a decimal")
        assertFalse(prints("Glucose 5,1 mmol/l", "51"))
    }

    @Test
    fun decimalCommasAndPointsAreTheSameNumber() {
        assertTrue(prints("Kalium 5,1 mmol/l", "5.1"))
        assertTrue(prints("Kalium 5.1 mmol/l", "5,1"))
        assertTrue(prints("Kreatinin 0,80 mg/dl", "0.8"))
        assertTrue(prints("TSH 2 mU/l", "2.0"))
        assertTrue(prints("MCH .5 pg", "0.5"))
        assertFalse(prints("Kalium 5,1 mmol/l", "5,2"))
    }

    @Test
    fun thousandsSeparatorsDoNotRaiseAFalseAlarm() {
        assertTrue(prints("Platelets 263,000 /uL", "263000"))
        assertTrue(prints("Leukocytes 12 345 /uL", "12345"))
        assertTrue(prints("Leukozyten 1.234,5 /uL", "1234.5"))
        assertFalse(prints("Platelets 263,000 /uL", "26300"))
    }

    /** "263,000" read as 263 is a thousand times too low and printed all the same. */
    @Test
    fun thousandsAreReadByTheReportsOwnDecimalNotation() {
        fun value(result: String, text: String) =
            LabParsePipeline.postProcess(AiLabReport(listOf(AiLabEntry("777-3", "Platelets", result, "/uL")), reportLanguage = "en"), text).values.single()

        val points = "Platelets 263,000 /uL\nKreatinin 0.8 mg/dl"
        val thousands = value("263,000", points)
        assertEquals(263000.0, thousands.numericValue)
        assertEquals("263000", thousands.displayValue, "Editing one digit must not turn it into 263.5")
        assertFalse(thousands.needsSourceCheck)
        assertFalse(thousands.needsCorrection)
        assertTrue(value("263", points).needsSourceCheck, "The thousands dropped")

        // Decimal commas: this is a decimal, and "1,025" is no 1025.
        val commas = "Spez. Gewicht 1,025\nKreatinin 0,8 mg/dl"
        assertEquals(1.025, value("1,025", commas).numericValue)
        assertTrue(value("1025", commas).needsSourceCheck)
        // Decimal points: likewise.
        assertEquals(1.025, value("1.025", "Specific Gravity 1.025\nCreatinine 0.8 mg/dl").numericValue)
        assertEquals(0.125, value("0,125", "TSH 0,125").numericValue, "A leading zero rules out thousands")

        // Nothing tells which, or the notations contradict that reading: never a guess.
        for ((result, text) in listOf(
            "263,000" to "Platelets 263,000 /uL",
            "263.000" to "Thrombozyten 263.000 /ul\nKreatinin 0,8 mg/dl",
            "1,025" to "Spez. Gewicht 1,025\nGlucose 5.1\nKalium 5,1",
        )) {
            val unclear = value(result, text)
            assertNull(unclear.numericValue, "$result in $text")
            assertTrue(unclear.needsCorrection, "$result in $text")
            assertFalse(unclear.isExportable, "$result in $text")
        }
    }

    @Test
    fun aDroppedQualifierIsAnotherResult() {
        assertFalse(prints("CRP <0,5 mg/dl", "0.5"))
        assertFalse(prints("CRP < 0,5 mg/dl", "0.5"))
        assertFalse(prints("CRP <.5 mg/dl", "0.5"))
        assertFalse(prints("GFR >90 ml/min", "90"))
        assertFalse(prints("GFR >= 90 ml/min", "90"))
        assertFalse(prints("GFR ≥ 1.000", "1000"))
        assertFalse(prints("ANA 1:160", "160"))
        assertFalse(prints("ANA 1 : 160", "160"))
        assertFalse(prints("ANA 1:160", "1"))
        // Printed plainly elsewhere, it's printed: the check doesn't read rows.
        assertTrue(prints("CRP 0,5 mg/dl\nReferenz <0,5", "0.5"))
        assertTrue(prints("Kreatinin:0,80mg/dl", "0.8"), "A colon behind a label is no titer")
        assertTrue(prints("Test 1: 5.1", "5.1"), "Nor a numbered row")
    }

    @Test
    fun aSignIsPartOfTheNumber() {
        assertTrue(prints("Base Excess -2,1 mmol/l", "-2.1"))
        assertTrue(prints("Base Excess −2,1 mmol/l", "-2.1"))
        assertFalse(prints("Base Excess -2,1 mmol/l", "2.1"))
        assertFalse(prints("Base Excess 5 mmol/l", "-5"))
        // A range or a label isn't a sign.
        assertTrue(prints("Referenz 3-5", "5"))
        assertTrue(prints("Referenz 3 - 5", "5"))
        assertTrue(prints("Referenz 10 -20", "20"))
        assertTrue(prints("ALT-22 U/l", "22"))
    }

    @Test
    fun anOppositeWordIsNotPrintedWhateverSeparatesIt() {
        for ((text, result) in listOf(
            "Hepatitis B: Not Detected" to "Detected",
            "HBsAg Non Reactive" to "Reactive",
            "HBsAg Non   Reactive" to "Reactive",
            "HBsAg Nicht nachweisbar" to "nachweisbar",
            "HBsAg kein Nachweis" to "Nachweis",
            "HBsAg non détecté" to "détecté",
            "HBsAg не обнаружено" to "обнаружено",
            "HBsAg 未检出" to "检出",
        )) {
            assertFalse(prints(text, result), "$result in $text")
        }
        assertTrue(prints("Hepatitis B: Not Detected", "Not Detected"))
        assertTrue(prints("Hepatitis B: Detected", "Detected"))
        assertTrue(prints("Sample no. Detected", "Detected"), "An abbreviation, not the word")
        assertTrue(prints("HBsAg 未检出", "未检出"))
    }

    @Test
    fun whatIsPrintedDoesNotRaiseAFalseAlarm() {
        assertTrue(prints("Leukozyten 1 234,5 /uL", "1234.5"))
        assertTrue(prints("Leukozyten 1\u00A0234,5 /uL", "1234,5"))
        assertTrue(prints("Leukocytes 1'234.5 /uL", "1234.5"))
        assertTrue(prints("血红蛋白145克/升", "145"))
        assertTrue(prints("HBsAg阴性", "阴性"))
        assertTrue(prints("Hb14g/dl", "14"))
        assertTrue(prints("Hb14,5g/dl", "14.5"))
        assertTrue(prints("Protein NEGATİF", "Negatif"))
        assertTrue(prints("Protein negatif", "NEGATİF"))
        assertTrue(prints("Renk KIRMIZI", "Kırmızı"))
        // Still not a result: the 1 of a name, and a word inside a Latin word.
        assertFalse(prints("HbA1c", "1"))
        assertFalse(prints("Prepositive", "positive"))
    }

    @Test
    fun aUnitCopiedAlongStaysOutOfTheResult() {
        val report = AiLabReport(
            listOf(
                AiLabEntry("2345-7", "Glucose", "5.6 mmol/l", "mmol/l"),
                AiLabEntry("1988-5", "CRP", "<0.5 MG/DL", "mg/dl"),
                AiLabEntry("2160-0", "Kreatinin", "6.5 %", ""),
            ),
            reportLanguage = "de",
        )
        val values = LabParsePipeline.postProcess(report, "Glucose 5.6 mmol/l\nCRP <0.5 mg/dl\nKreatinin 6.5 %").values
        assertEquals(listOf("5.6", "<0.5", "6.5 %"), values.map { it.displayValue })
        assertEquals(5.6, values[0].numericValue)
        assertTrue(values[0].isExportable)
        assertEquals("<0.5", values[1].qualitativeResult)
        // No unit to tell it by: stray text the user fixes, and is told about.
        assertFalse(values[2].isExportable)
        assertTrue(values[2].needsCorrection)
        assertEquals(listOf(values[2].id), values.filter { it.needsCorrection }.map { it.id })
        assertEquals(1, values.valuesToCorrectCount())
    }

    @Test
    fun aRowCanOnlyNeedCorrectionWhileItWouldBeSaved() {
        fun value(text: String, code: String = "2160-0", selected: Boolean = true) =
            LabValue(code = code, name = "Kreatinin", displayValue = text, numericValue = null, unit = "mg/dl", isSelected = selected)
        assertTrue(value("neg 1").needsCorrection)
        assertFalse(value("neg 1", selected = false).needsCorrection, "Deselecting is the user's answer")
        assertFalse(value("neg 1", code = "KREA").needsCorrection, "An unmapped row has its own section")
        assertFalse(value("Negativ").needsCorrection, "A qualitative result can be saved")
        assertFalse(value("-").needsCorrection, "The report prints no result")
        assertFalse(value("").needsCorrection)
    }

    @Test
    fun anEditTakesTheUnitOffToo() {
        val value = LabValue(code = "2345-7", name = "Glucose", displayValue = "5", numericValue = 5.0, unit = "mmol/l")
        val edited = value.withEditedResult("5.6 mmol/l")
        assertEquals("5.6", edited.displayValue)
        assertEquals(5.6, edited.numericValue)
    }

    @Test
    fun aNumberInADateOrANameIsNotAResult() {
        assertFalse(prints("Collected: 08/12/2026", "12"))
        assertFalse(prints("Befund vom 06.05.2026", "6"))
        assertFalse(prints("HbA1c", "1"))
        assertTrue(prints("Kreatinin:0,80mg/dl", "0,80"), "glued to its label and unit, it's still printed")
    }

    @Test
    fun aResultInWordsIsFoundWhateverTheCaseOrSpacing() {
        assertTrue(prints("Protein     NEGATIV", "Negativ"))
        assertTrue(prints("Hepatitis B: not   detected", "Not Detected"))
        assertFalse(prints("Protein     negativ", "Positiv"))
        assertFalse(prints("Protein     Negative", "Negativ"), "another word")
        assertFalse(prints("HBsAg  NON-REACTIVE", "Reactive"), "the opposite result")
        assertTrue(prints("HBsAg  NON-REACTIVE", "non-reactive"))
    }

    @Test
    fun aThresholdOrTiterIsFoundWhateverTheSpacingOrSeparator() {
        assertTrue(prints("CRP   < 0,5   mg/dl", "<0.5"))
        assertTrue(prints("CRP   <0.5", "< 0,5"))
        assertFalse(prints("CRP   <0.55", "<0.5"), "not inside a longer number")
        assertFalse(prints("CRP   <1.5", "<.5"))
        assertTrue(prints("ANA Titer 1 : 160", "1:160"))
        assertFalse(prints("ANA Titer 1:1600", "1:160"))
        assertTrue(prints("Protein 2 +", "2+"))
    }

    @Test
    fun noResultClaimsNothing() {
        assertTrue(prints("", "-"))
        assertTrue(prints("anything", ""))
    }

    @Test
    fun postProcessingFlagsOnlyWhatTheTextDoesNotPrint() {
        val report = AiLabReport(
            entries = listOf(
                AiLabEntry("GPT", "Alanin-Aminotransferase", "222", "U/l"),
                AiLabEntry("2160-0", "Kreatinin", "0,80", "mg/dl"),
                AiLabEntry("2823-3", "Kalium", "H 5,6", "mmol/l"),
                AiLabEntry("1988-5", "CRP", "<0.5", "mg/dl"),
                AiLabEntry("5196-1", "HBsAg", "Negativ", ""),
                AiLabEntry("2085-9", "HDL", "-", "mg/dl"),
            ),
            reportLanguage = "de",
        )
        val text = "ALT 22 U/l\nKreatinin 0,80 mg/dl\nKalium 5,6 H mmol/l\nCRP < 0,5 mg/dl\nHBsAg NEGATIV"
        val values = LabParsePipeline.postProcess(report, text).values
        assertEquals(listOf("222"), values.filter { it.needsSourceCheck }.map { it.displayValue })
    }

    /** Each part sees only its own lines; a value read from the last part is still in the report's text. */
    @Test
    fun aLongReportIsCheckedAgainstItsWholeText() {
        val filler = (1..60).joinToString("\n") { "Note $it: this marker reflects how your body is doing over time." }
        val text = "Kreatinin 0,80 mg/dl\n$filler\nKalium 5,6 mmol/l"
        val parts = LabParseChunks.parts(text)
        assertTrue(parts.size > 1, "read in parts")
        assertFalse("0,80" in parts.last())
        val replies = listOf(
            AiLabReport(listOf(AiLabEntry("2160-0", "Kreatinin", "0,80", "mg/dl")), reportLanguage = "de"),
            AiLabReport(listOf(AiLabEntry("2823-3", "Kalium", "5,6", "mmol/l")), reportLanguage = "de"),
        )
        val values = LabParsePipeline.postProcess(LabParseChunks.merge(replies), text).values
        assertEquals(listOf(false, false), values.map { it.needsSourceCheck })
    }

    @Test
    fun aDateTheTextDoesNotPrintIsNoDate() {
        val report = AiLabReport(listOf(AiLabEntry("2160-0", "Kreatinin", "0,80", "mg/dl")), reportDate = "2026-05-06", reportLanguage = "de")
        assertEquals(LocalDate(2026, 5, 6), LabParsePipeline.postProcess(report, "Befund vom 06.05.2026\nKreatinin 0,80").reportDate)
        assertNull(LabParsePipeline.postProcess(report, "Befund vom 07.04.2025\nKreatinin 0,80").reportDate)
        assertNull(LabParsePipeline.postProcess(report.copy(reportDate = ""), "Kreatinin 0,80").reportDate)
    }

    @Test
    fun aHandEnteredValueIsNotFlaggedAndEditingClearsTheFlag() {
        val flagged = LabValue(code = "2160-0", name = "Kreatinin", displayValue = "222", numericValue = 222.0, unit = "U/l", needsSourceCheck = true)
        assertFalse(LabValue(code = "2160-0", name = "Kreatinin", displayValue = "0,8", numericValue = 0.8, unit = "mg/dl").needsSourceCheck)
        val edited = flagged.withEditedResult("22,5")
        assertFalse(edited.needsSourceCheck)
        assertEquals("22,5", edited.displayValue)
        assertEquals(22.5, edited.numericValue)
        // Text the shared parser doesn't call a number stays out of the number, "inf" and "NaN" included.
        assertNull(flagged.withEditedResult("inf").numericValue)
        assertNull(flagged.withEditedResult("NaN").numericValue)
    }

    @Test
    fun onlyValuesAboutToBeSavedCountAsToCheck() {
        fun value(code: String, selected: Boolean = true, flagged: Boolean = true) =
            LabValue(code = code, name = code, displayValue = "1", numericValue = 1.0, unit = "", isSelected = selected, needsSourceCheck = flagged)
        assertEquals(1, listOf(value("2160-0"), value("KREA"), value("2823-3", selected = false), value("4548-4", flagged = false)).valuesToCheckCount())
    }

    /**
     * No model needed: everything the sample reports print, as the people who
     * wrote them read it, must count as printed. A false alarm on most rows
     * would teach people to ignore the badge.
     */
    @Test
    fun theSampleReportsRaiseNoFalseAlarm() {
        val reports = File("../evaluation/reports").listFiles { file -> file.name.endsWith(".txt") }!!
        assertEquals(9, reports.size)
        for (report in reports) {
            val source = ReportSourceText(report.readText())
            val expected = Json.parseToJsonElement(File(report.parentFile, report.nameWithoutExtension + ".expected.json").readText())
            for (value in expected.jsonObject.getValue("values").jsonArray) {
                val number = value.jsonObject.getValue("value").jsonPrimitive.content
                assertTrue(source.prints(number), "${report.name}: $number")
            }
        }
    }
}
