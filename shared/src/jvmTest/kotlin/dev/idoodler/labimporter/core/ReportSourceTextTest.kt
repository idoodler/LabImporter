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

    private fun dropsQualifier(text: String, result: String, vararg names: String) = ReportSourceText(text).dropsQualifier(result, names.toList())

    /** ">60" read as 60, where the row prints 60 again in its reference: the row's result is the first 60 behind the name. */
    @Test
    fun aQualifierDroppedFromTheRowsResultIsCaught() {
        // evaluation/README.md: Quest's reference "> OR = 60" printed the 60 that hid it.
        val quest = "eGFR                    >60              mL/min/1.73m2  > OR = 60"
        assertTrue(dropsQualifier(quest, "60", "eGFR"))
        assertFalse(ReportSourceText(quest).backs("60", "mL/min/1.73m2", listOf("eGFR")))
        assertTrue(ReportSourceText(quest).backs(">60", "mL/min/1.73m2", listOf("eGFR")))
        assertTrue(dropsQualifier("Bilirubin, Direct           <0.2           mg/dL          < OR = 0.2", "0.2", "Bilirubin, Direct"))
        // Spelled out, or a previous result behind it.
        assertTrue(dropsQualifier("Vitamin D >150 ng/ml (150 and above: possibly toxic)", "150", "Vitamin D"))
        assertTrue(dropsQualifier("CRP < 0,5 mg/dl  vorher: 0,5", "0,5", "CRP"))
        // A row that goes on to the next line, starting with its result.
        assertTrue(dropsQualifier("eGFR (CKD-EPI)\n> 90 ml/min   > OR = 90", "90", "eGFR"))
    }

    @Test
    fun aPlainResultBesideAThresholdReferenceIsNoDroppedQualifier() {
        assertFalse(dropsQualifier("LDL Cholesterol               100      H      mg/dL     <100", "100", "LDL Cholesterol"))
        assertFalse(dropsQualifier("CRP 0,5 mg/dl < 0,5", "0.5", "CRP"))
        // Another test's threshold on the same line isn't this one's result.
        assertFalse(dropsQualifier("KREA: < 0,5 mg/dl; HARNS: 0,5 mg/dl", "0,5", "HARNS"))
        assertTrue(dropsQualifier("KREA: < 0,5 mg/dl; HARNS: 0,5 mg/dl", "0,5", "KREA"))
        // A reading that keeps its qualifier, or isn't a number, drops none.
        assertFalse(dropsQualifier("eGFR >60 mL/min", ">60", "eGFR"))
        assertFalse(dropsQualifier("eGFR >60 mL/min", "Negativ", "eGFR"))
        // No row of the test prints it: nothing claimed.
        assertFalse(dropsQualifier("Natrium 141 mmol/l", "60", "eGFR"))
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

    // From mutation testing (tools/quality/mutation.sh): each pins a rule whose opposite the
    // tests above let through.

    @Test
    fun aNumberIsNotItsOwnFractionNorAZeroBehindAThreshold() {
        // Off by a factor of 100: 22 doesn't print 0.22.
        assertFalse(prints("ALT 22 U/l", "0.22"))
        assertFalse(prints("Kalium 4,1 mmol/l", "0.4"))
        // A threshold prints no number at all, not even a 0 the model made of it.
        assertFalse(prints("CRP <0,5 mg/dl", "0"))
        assertFalse(prints("GFR >= 90 ml/min", "0"))
        // A one-digit titer is a titer too.
        assertFalse(prints("Titer 1:8", "8"))
        assertFalse(prints("Titer 1:8", "1"))
    }

    @Test
    fun aTextMayStartWithItsResult() {
        assertTrue(prints("22 U/l", "22"))
        assertTrue(prints(" 5,1 mmol/l", "5.1"))
        assertTrue(prints("  Negativ", "negativ"))
        assertTrue(prints("<0,5 mg/dl", "<0.5"))
    }

    @Test
    fun aResultInExponentNotationIsComparedAsPrinted() {
        assertTrue(prints("Leukocytes 1.2e5 /ul", "1.2e5"))
        assertFalse(prints("Leukocytes 1.2e5 /ul", "1.3e5"))
        assertFalse(prints("Leukocytes 1.2e5 /ul", "2e5"), "not the end of another number")
    }

    @Test
    fun aThresholdIsNotPrintedByALongerOne() {
        assertFalse(prints("CRP <1.5 mg/l", "<1"))
        assertTrue(prints("CRP <1.", "<1"), "a full stop isn't a decimal")
    }

    @Test
    fun theDecimalNotationComesFromTheReportsOwnReadings() {
        fun platelets(text: String) = ReportSourceText(text).numberFrom("263,000")
        // One decimal comma, or a number that can only be one ("1.234,5"), makes 263,000 a decimal.
        assertEquals(263.0, platelets("Kalium 4,1 mmol/l\nThrombozyten 263,000 /nl"))
        assertEquals(263.0, platelets("Leukozyten 1.234,5 /ul\nThrombozyten 263,000 /nl"))
        // A version or a number glued to a label says nothing: these reports still write decimal commas.
        assertEquals(263.0, platelets("Analyzer v1.2\nKalium 4,1 mmol/l\nThrombozyten 263,000 /nl"))
        assertEquals(263000.0, platelets("Hb14,5 g/dl\nGlucose 5.1 mmol/l\nPlatelets 263,000 /ul"))
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

    private fun printsUnit(text: String, unit: String) = ReportSourceText(text).printsUnit(unit)

    @Test
    fun aUnitMustBePrintedWhole() {
        val alt = "ALT (GPT)             22             U/l            < 35"
        // evaluation/README.md: the model read ALT's unit as "/l".
        assertFalse(printsUnit(alt, "/l"))
        assertFalse(printsUnit(alt, "U"), "not the start of a longer unit")
        assertFalse(printsUnit("Kreatinin 1,12 mg/dl", "mg"))
        assertFalse(printsUnit("Kreatinin 1,12 mg/dl", "dl"))
        assertFalse(printsUnit("Kreatinin 1,12 mg/dl", "mg/l"))
        // Nor the end of a power or a factor.
        assertFalse(printsUnit("Leukozyten 6,8 10^9/l", "/l"))
        assertFalse(printsUnit("WBC 6.8 10*3/uL", "/uL"))
        assertFalse(printsUnit("eGFR 58 ml/min/1,73m²", "m²"))
        assertFalse(printsUnit("eGFR 58 ml/min/1,73m²", "min"))
    }

    @Test
    fun aUnitIsFoundWhateverTheCaseSpacingOrMicroSign() {
        val alt = "ALT (GPT)             22             U/l            < 35"
        assertTrue(printsUnit(alt, "U/l"))
        assertTrue(printsUnit(alt, "U/L"))
        assertTrue(printsUnit(alt, "u / l"))
        assertTrue(printsUnit("Kreatinin 88 umol/L", "µmol/l"))
        assertTrue(printsUnit("Kreatinin 88 µmol/L", "umol/l"))
        assertTrue(printsUnit("Ferritin 88 μg/l", "µg/l"), "Greek mu and the micro sign")
        assertTrue(printsUnit("eGFR 58 ml/min/1,73m²", "ml/min/1.73m²"))
        assertTrue(printsUnit("eGFR 58 ml / min / 1,73 m²", "ml/min/1,73m²"))
        assertTrue(printsUnit("LDL-Cholesterol 138 H mg/dL (calc) <100", "mg/dL (calc)"))
        assertTrue(printsUnit("LDL-Cholesterol 138 H mg/dL (calc) <100", "mg/dL"))
        assertTrue(printsUnit("Albumin/Kreatinin-Quotient 40,9 mg/g Krea < 30", "mg/g"))
        assertTrue(printsUnit("Leukozyten 6,8 10^9/l", "10^9/l"))
    }

    @Test
    fun aUnitGluedToItsValueOrAtTheEndIsPrinted() {
        assertTrue(printsUnit("Thrombozyten 263/nl", "/nl"))
        assertTrue(printsUnit("Thrombozyten 263 /nl", "/nl"))
        assertTrue(printsUnit("Hb14g/dl", "g/dl"))
        assertTrue(printsUnit("HbA1c 6,5%", "%"))
        assertTrue(printsUnit("Kreatinin:0,80mg/dl", "mg/dl"))
        assertTrue(printsUnit("Kalium 4,1 mmol/l.", "mmol/l"), "a full stop behind it")
        assertTrue(printsUnit("Kalium 4,1 (mmol/l)", "mmol/l"))
        assertTrue(printsUnit("U/l", "U/l"), "at the start and the end of the text")
        assertTrue(printsUnit("血红蛋白145克/升", "克/升"))
    }

    @Test
    fun noUnitClaimsNothing() {
        assertTrue(printsUnit("HBsAg NEGATIV", ""))
        assertTrue(printsUnit("HBsAg NEGATIV", "  "))
    }

    private fun beside(text: String, result: String, vararg names: String) = ReportSourceText(text).printsBesideTest(result, names.toList())

    private val table = """
        Untersuchung          Ergebnis       Einheit        Referenzbereich
        Natrium               141            mmol/l         136 - 145
        Kalium                5,6     H      mmol/l         3,5 - 5,1
        Non-HDL-Cholesterin   138            mg/dl          < 130
        HDL-Cholesterin       52             mg/dl          > 40
        CRP                   < 0,5          mg/dl          < 0,5
        eGFR (CKD-EPI)        58      L      ml/min/1,73m²  > 60
        Nitrit                negativ
        Eiweiß                positiv (+)
    """.trimIndent()

    @Test
    fun aResultOnAnotherTestsRowIsCaught() {
        assertFalse(beside(table, "141", "Kalium"), "printed, but on Natrium's row")
        assertFalse(beside(table, "5,6", "Natrium"))
        assertFalse(beside(table, "138", "HDL-Cholesterin"), "Non-HDL is another test")
        assertFalse(beside(table, "52", "Non-HDL-Cholesterin"))
        assertFalse(beside(table, "141", "CRP"), "a row printing a threshold prints a result")
        assertFalse(beside(table, "141", "Kalium", "K"), "by any of its names")
        assertFalse(beside(table, "141", "kalium"), "whatever the case")
    }

    @Test
    fun aResultOnItsOwnRowIsNotFlagged() {
        assertTrue(beside(table, "141", "Natrium"))
        assertTrue(beside(table, "5.6", "Kalium"))
        assertTrue(beside(table, "138", "Non-HDL-Cholesterin"))
        assertTrue(beside(table, "52", "HDL-Cholesterin"))
        assertTrue(beside(table, "<0.5", "CRP"))
        assertTrue(beside(table, "positiv (+)", "Eiweiß"))
        // By the printed code when the model wrote the name out, or the other way round.
        assertTrue(beside(table, "58", "glomeruläre Filtrationsrate (eGFR)", "eGFR"))
        assertTrue(beside(table, "58", "eGFR (CKD-EPI)", "GFR"))
        assertTrue(beside("ALT (GPT)             22             U/l", "22", "Alanin-Aminotransferase", "GPT"))
        assertTrue(beside("Kreatinin:0,80mg/dl; Kalium:4,1mmol/l", "0,80", "Kreatinin"), "two tests on one line")
        assertTrue(beside("Kreatinin:0,80mg/dl; Kalium:4,1mmol/l", "4,1", "Kalium"))
        assertTrue(beside("血红蛋白145克/升", "145", "血红蛋白"))
    }

    /** Where the rows can't be told, nothing is claimed: a false alarm on every value would teach people to ignore the badge. */
    @Test
    fun aRowThatCannotBeToldIsNotFlagged() {
        // A PDF's text with the table's columns one after the other.
        assertTrue(beside("Natrium\nKalium\n141\n5,6", "141", "Kalium"))
        // A name that is printed with its own number, the result on the next line.
        assertTrue(beside("Vitamin B12\n512 pg/ml\nFerritin 86 ng/ml", "512", "Vitamin B12"))
        assertTrue(beside("HbA1c\n6,5 %", "6,5", "HB-A1C", "HbA1c"))
        // A name the report doesn't print, nor its code.
        assertTrue(beside(table, "141", "Sodium", "NA"))
        // A heading the model took for a test.
        assertTrue(beside("HEPATITIS PANEL\nHepatitis B Surface Antigen   NON-REACTIVE", "NON-REACTIVE", "HEPATITIS PANEL"))
        // Results in words aren't told apart: a row in words prints no result to compare with.
        assertTrue(beside(table, "positiv (+)", "Nitrit"))
        // A test named on two rows: printed on either is enough.
        assertTrue(beside("Kreatinin 1,12 mg/dl\nAlbumin/Kreatinin-Quotient 40,9 mg/g", "1,12", "Kreatinin"))
        // No result, or no name, claims nothing.
        assertTrue(beside(table, "-", "Kalium"))
        assertTrue(beside(table, "141", " "))
    }

    /** On a line of several tests, a result counts only in its test's own part: from its name up to the next test's. */
    @Test
    fun aNeighboursResultOnTheSameLineIsCaught() {
        val line = "KREA: 0,80 mg/dl; HARNS: 5,1 mg/dl; GPT: 59 U/l; GGT: 31 U/l"
        assertFalse(beside(line, "5,1", "Kreatinin", "KREA"), "printed on Kreatinin's line, but as HARNS's result")
        assertFalse(beside(line, "31", "Glutamin-Phosphat-Transaminase", "GPT"))
        assertTrue(beside(line, "0,80", "Kreatinin", "KREA"))
        assertTrue(beside(line, "5,1", "Harnsäure", "HARNS"))
        assertTrue(beside(line, "31", "GGT"))
        val lipids = "Cholesterin: 198 mg/dl; HDL-Cholesterin: 52 mg/dl; LDL-Cholesterin: 121 mg/dl"
        assertFalse(beside(lipids, "52", "Cholesterin"))
        assertFalse(beside(lipids, "121", "HDL-Cholesterin"))
        assertTrue(beside(lipids, "198", "Cholesterin"))
        assertTrue(beside(lipids, "121", "LDL-Cholesterin"))
        assertFalse(beside("Kreatinin:0,80mg/dl; Kalium:4,1mmol/l", "4,1", "Kreatinin"), "glued to its label and unit")
        val columns = "TSH (uIU/mL)   2.10    Free T4 (ng/dL)   1.21"
        assertFalse(beside(columns, "1.21", "TSH"))
        assertTrue(beside(columns, "2.10", "TSH"), "the unit in the label")
        // What stands between the name and its result is still the name's.
        assertTrue(beside("Glucose nüchtern: 104 mg/dl; Kalium: 4,1 mmol/l", "104", "Glucose"))
        // A row's own reference range, flag and previous result stay in its part.
        assertTrue(beside("Glucose     65   Low    92    04/02/2026   mg/dL   70-99", "92", "Glucose"))
        // Where the test's part prints no result, the whole line decides, as before.
        assertTrue(beside("141 mmol/l Natrium", "141", "Natrium"))
        // A line whose results stand in front of their names: each name's part is the next test's.
        assertTrue(beside("141 mmol/l Natrium   4,1 mmol/l Kalium", "141", "Natrium"))
        assertTrue(beside("141 mmol/l Natrium   4,1 mmol/l Kalium", "4,1", "Kalium"))
    }

    /** A result in words is no label of the next test, even where the row goes on to print a number. */
    @Test
    fun aResultInWordsBeforeANumberStaysTheTestsResult() {
        assertTrue(beside("Toxoplasma IgG   negative   <3.0 IU/mL", "negative", "Toxoplasma IgG"))
        assertTrue(beside("Rubella IgG   positiv   64 IU/ml", "positiv", "Rubella IgG"))
        assertTrue(beside("HIV-1/2 Ag/Ak   nicht reaktiv   0,2 S/CO", "nicht reaktiv", "HIV-1/2 Ag/Ak"))
    }

    /** A bare number one space in front of a word, as in "Vitamin D 25 OH", may still be part of the name. */
    @Test
    fun aSpacedNumberOfTheNameDoesNotEndItsPart() {
        assertTrue(beside("Vitamin D 25 OH   32 ng/ml", "32", "Vitamin D"))
        assertTrue(beside("Vitamin D 25 OH   32 ng/ml", "32", "Vitamin D 25 OH"))
        // A decimal result, or one with its unit or a column gap behind it, still ends at the next test's label.
        assertFalse(beside("Hb 14,2 MCV 88", "88", "Hb"))
        assertFalse(beside("KREA 0,80 mg/dl HARNS 5,1 mg/dl", "5,1", "KREA"))
        assertFalse(beside("Leukozyten 7   Erythrozyten 5", "5", "Leukozyten"))
    }

    @Test
    fun aNumberOfTheTestsOwnNameIsNoResult() {
        val b12 = "Vitamin B12                       512                  pg/mL      200-1100"
        assertFalse(beside(b12, "12", "Vitamin B12"), "the 12 of B12")
        assertTrue(beside(b12, "512", "Vitamin B12"))
        assertFalse(beside("Vitamin D 25-OH   32 ng/ml", "25", "Vitamin D"), "behind the name, still part of it")
        assertTrue(beside("Vitamin D 25-OH   32 ng/ml", "32", "Vitamin D"))
        assertFalse(beside("CD4 Lymphozyten 450 /µl", "4", "CD4"))
        assertTrue(beside("CD4 Lymphozyten 450 /µl", "450", "CD4"))
    }

    @Test
    fun aNameIsMatchedAsAWholeWord() {
        val rows = "TEST1: 15 mg/dl\nTEST10: 105 mg/dl"
        assertTrue(beside(rows, "15", "TEST1"))
        assertFalse(beside(rows, "105", "TEST1"), "TEST10 is no TEST1")
        assertFalse(beside("Thrombozyten 263 /nl\nThrombo-Zeit 12 s", "12", "Thrombozyten"))
    }

    @Test
    fun aRowSplitAcrossLinesIsNotFlagged() {
        // A PDF's text often puts the result (bold when out of range) on a line of its own.
        assertTrue(beside("Hämoglobin  13,5 - 17,5 g/dl\n14,2", "14,2", "Hämoglobin"), "a reference range is no result")
        assertTrue(beside("eGFR (CKD-EPI 2021)\n58 ml/min/1,73m²", "58", "eGFR"), "nor a year in the name")
        assertTrue(beside("Cholesterin  < 200 mg/dl\n245 H", "245", "Cholesterin"), "nor a threshold reference")
        // A line that continues the row starts with its value: the next test's row doesn't.
        assertFalse(beside("Natrium  141 mmol/l\nKalium 5,6 mmol/l", "5,6", "Natrium"))
        assertFalse(beside("Cholesterin  < 200 mg/dl\nHDL 52", "52", "Cholesterin"))
        // Nor does a row two lines down.
        assertFalse(beside("Cholesterin  < 200 mg/dl\n245\n52", "52", "Cholesterin"))
    }

    @Test
    fun aReferenceRangeOnTheRowIsNoResult() {
        // A range has no result in it: a row of names and ranges says nothing, the result being elsewhere.
        assertTrue(beside("Hämoglobin  13,5 - 17,5 g/dl\nHämatokrit 40 - 52 %\n14,2\n44", "14,2", "Hämoglobin"))
        // A limit of the range isn't the row's result.
        assertFalse(beside("Kalium 5,6 H mmol/l 3,5 - 5,1", "5,1", "Kalium"))
        assertFalse(beside("Kalium 5,6 H mmol/l 3,5–5,1", "3,5", "Kalium"))
        assertTrue(beside("Kalium 5,6 H mmol/l 3,5 - 5,1", "5,6", "Kalium"))
    }

    @Test
    fun theRowThatStartsWithTheNameIsItsRow() {
        // "HDL Cholesterin" names Cholesterin too, but the row that starts with it is total cholesterol's.
        val rows = "Cholesterin 200 mg/dl\nHDL Cholesterin 52 mg/dl"
        assertFalse(beside(rows, "52", "Cholesterin"))
        assertTrue(beside(rows, "200", "Cholesterin"))
        assertTrue(beside(rows, "52", "HDL Cholesterin"))
        assertFalse(beside("Kreatinin 1,12 mg/dl\nAlbumin/Kreatinin-Quotient 40,9 mg/g", "40,9", "Kreatinin"))
        // Where no row starts with it, every row naming it counts.
        assertTrue(beside("Gesamt Cholesterin 245\nHDL Cholesterin 52", "245", "Cholesterin"))
    }

    @Test
    fun aUnitWithASuperscriptOrAnAbbreviationIsPrintedAsTheModelWritesIt() {
        // The unit guide's own example is written "m2", reports print "m²".
        assertTrue(printsUnit("eGFR 58 ml/min/1,73m² KOF", "ml/min/1,73m2KOF"))
        assertTrue(printsUnit("eGFR 58 ml/min/1,73m2 KOF", "ml/min/1,73m²KOF"))
        assertTrue(printsUnit("Leukozyten 6,8 10³/µl", "10^3/µl"))
        assertTrue(printsUnit("Leukozyten 6,8 10^3/µl", "10³/µl"))
        assertTrue(printsUnit("Leukozyten 6,8 Tsd./µl", "Tsd/µl"))
        // Still whole units: a power's end is no unit, nor is a different power.
        assertFalse(printsUnit("Leukozyten 6,8 10³/µl", "/µl"))
        assertFalse(printsUnit("Leukozyten 6,8 10³/µl", "10^6/µl"))
        assertFalse(printsUnit("eGFR 58 ml/min/1,73m²", "ml/min/1,73m3"))
        assertFalse(printsUnit("Kalium 4,1 mmol/l", "mmol/l3"))
    }

    @Test
    fun aPowerBehindATimesSignIsPrinted() {
        assertTrue(printsUnit("WBC 6.8 x10^9/L", "10^9/L"))
        assertTrue(printsUnit("WBC 6.8x10^9/L", "10^9/L"))
        assertTrue(printsUnit("WBC 6.8 ×10^9/L", "10^9/L"))
        assertTrue(printsUnit("WBC 6.8 x10E3/uL", "10E3/uL"), "LabCorp")
        // A letter x that's part of a word isn't a times sign, and the power's end is still no unit.
        assertFalse(printsUnit("Box10 g/dl", "10 g/dl"))
        assertFalse(printsUnit("WBC 6.8 x10^9/L", "/L"))
    }

    @Test
    fun postProcessingFlagsAWrongUnitAndAResultOnAnotherRow() {
        val text = "Natrium 141 mmol/l\nKalium 5,6 mmol/l\nALT (GPT) 22 U/l < 35"
        fun flagged(vararg entries: AiLabEntry) =
            LabParsePipeline.postProcess(AiLabReport(entries.toList(), reportLanguage = "de"), text).values.map { it.needsSourceCheck }
        assertEquals(listOf(true), flagged(AiLabEntry("ALT", "ALT", "22", "/l")))
        assertEquals(listOf(false), flagged(AiLabEntry("ALT", "ALT", "22", "U/L")))
        assertEquals(listOf(true, false), flagged(AiLabEntry("K", "Kalium", "141", "mmol/l"), AiLabEntry("NA", "Natrium", "141", "mmol/l")))
        // No result claims nothing, not even a unit the report doesn't print.
        assertEquals(listOf(false), flagged(AiLabEntry("MALB-U", "Albumin im Urin", "-", "mg/l")))
        // A flag the model copied into the unit is no part of it.
        assertEquals(listOf(false), flagged(AiLabEntry("K", "Kalium", "5,6", "H mmol/l")))
    }

    private fun unitBeside(text: String, result: String, unit: String, vararg names: String) =
        ReportSourceText(text).printsUnitBesideTest(result, unit, names.toList())

    @Test
    fun anotherTestsUnitOnTheRowIsCaught() {
        val line = "KREA: 0,80 mg/dl; HARNS: 5,1 mg/dl; GPT: 59 U/l; GGT: 31 U/l"
        assertFalse(unitBeside(line, "0,80", "U/l", "Kreatinin", "KREA"), "printed on the line, but as GPT's unit")
        assertTrue(unitBeside(line, "0,80", "mg/dl", "Kreatinin", "KREA"))
        assertTrue(unitBeside(line, "59", "U/l", "GPT"))
        assertFalse(unitBeside("TSH: 1,9 mU/l; Ferritin: 88 ng/ml", "88", "mU/l", "Ferritin"), "in front of the result: the test before's")
        assertFalse(unitBeside(table, "141", "mg/dl", "Natrium"), "the unit of the rows below")
        assertFalse(unitBeside(table, "5,6", "ml/min/1,73m²", "Kalium"))
        val glucose = "Glycémie à jeun ........ 5,4 mmol/L (0,97 g/L)     [3,9 - 5,8]"
        assertFalse(unitBeside(glucose, "5,4", "g/L", "Glycémie à jeun"), "the unit of the result in the second unit")
        assertTrue(unitBeside(glucose, "0,97", "g/L", "Glycémie à jeun"), "the result in the second unit")
        assertFalse(unitBeside("Hémoglobine ...... 12,8 g/dL\nLeucocytes ..... 7,2 G/L", "12,8", "G/L", "Hémoglobine"))
    }

    @Test
    fun aUnitBehindItsResultIsNotFlagged() {
        assertTrue(unitBeside(table, "5,6", "mmol/l", "Kalium"), "behind a flag")
        assertTrue(unitBeside(table, "58", "ml/min/1,73m²", "eGFR"), "a unit with a number of its own")
        assertTrue(unitBeside(table, "<0,5", "mg/dl", "CRP"))
        assertTrue(unitBeside("Natrium 141 136 - 145 mmol/l", "141", "mmol/l", "Natrium"), "behind the reference range")
        assertTrue(unitBeside("Kalium 4,1  4,3  4,0 mmol/l", "4,1", "mmol/l", "Kalium"), "a cumulative report's columns")
        assertTrue(unitBeside("Leukozyten 6,8 H 10^9/l 3,9 - 10,2", "6,8", "10^9/l", "Leukozyten"))
        assertTrue(unitBeside("WBC 6.8 x10E3/uL", "6.8", "10E3/uL", "WBC"))
        assertTrue(unitBeside("Thrombozyten 263/nl", "263", "/nl", "Thrombozyten"))
        assertTrue(unitBeside("Hemoglobin A1c 5.9 H % of total Hgb <5.7", "5.9", "%", "Hemoglobin A1c"))
        assertTrue(unitBeside("Kreatinin  1,12 mg/dl  Befund vom 06.05.2026", "1,12", "mg/dl", "Kreatinin"), "a date is no next result")
        assertTrue(unitBeside("Hämoglobin\n14,2 g/dl  12 - 16", "14,2", "g/dl", "Hämoglobin"), "on the line that continues the row")
        assertTrue(unitBeside("血红蛋白145克/升", "145", "克/升", "血红蛋白"))
    }

    /** As with the result, a row that can't tell says nothing: the whole report decides. */
    @Test
    fun aRowWithoutAUnitBehindItsResultSaysNothing() {
        // The unit in a column a PDF's text put elsewhere, or a short label behind the result.
        assertTrue(unitBeside("Glucose 92 H\nBUN 18\nmg/dL\nmg/dL", "92", "mg/dL", "Glucose"))
        assertTrue(unitBeside("Hb 14,2   MCV 88\nEinheiten: g/dl, fl", "14,2", "g/dl", "Hb"), "the next test's label is no unit")
        assertFalse(unitBeside("Glucose 92 H\nBUN 18 mmol/l", "92", "mg/dL", "Glucose"), "printed nowhere")
        // No row prints the result.
        assertTrue(unitBeside("Natrium\nKalium\n141\n5,6\nmmol/l", "141", "mmol/l", "Kalium"))
        assertTrue(unitBeside("Kalium 4,1 mmol/l", "4,1", "mmol/l", "Potassium"), "no row names the test")
        // A result in words, no result, no unit.
        assertTrue(unitBeside("Eiweiß positiv (+) mg/dl", "positiv (+)", "mg/dl", "Eiweiß"))
        assertTrue(unitBeside(table, "-", "U/l", "Kalium"))
        assertTrue(unitBeside(table, "141", " ", "Natrium"))
    }

    /** What stands behind a result before its unit, or instead of it, is no unit of it. */
    @Test
    fun aWordOrTheNextTestBehindTheResultIsNoUnit() {
        // A cumulative report's flag, written out, and its previous result's column.
        val cumulative = "Glucose     65   Low    92    04/02/2026   mg/dL   70-99\n" +
            "Creatinine  1.32 High   1.10  04/02/2026   mg/dL   0.57-1.00\nSodium 138 mmol/L"
        assertTrue(unitBeside(cumulative, "65", "mg/dL", "Glucose"))
        assertTrue(unitBeside(cumulative, "1.32", "mg/dL", "Creatinine"))
        assertFalse(unitBeside(cumulative, "1.32", "mmol/L", "Creatinine"), "the unit of the next row")
        // The unit's column first, a word behind the result.
        val unitFirst = "CRP          mg/dl   0,3    bis 0,5\nTSH          mU/l    1,82   0,27 - 4,20"
        assertTrue(unitBeside(unitFirst, "0,3", "mg/dl", "CRP"))
        assertFalse(unitBeside(unitFirst, "0,3", "mU/l", "CRP"))
        assertFalse(unitBeside(unitFirst, "1,82", "mg/dl", "TSH"), "the unit in front of the result")
        // Two tests on a line, each unit in its label: the next one's is no unit of this result.
        val columns = "TSH (uIU/mL)   2.10    Free T4 (ng/dL)   1.21\nGlucose (mg/dL)  92   HbA1c (%) 5.6"
        assertTrue(unitBeside(columns, "2.10", "uIU/mL", "TSH"))
        assertFalse(unitBeside(columns, "2.10", "ng/dL", "TSH"))
        assertTrue(unitBeside(columns, "1.21", "ng/dL", "T4"))
        assertTrue(unitBeside(columns, "92", "mg/dL", "Glucose"))
        assertFalse(unitBeside(columns, "92", "%", "Glucose"))
        assertTrue(unitBeside(columns, "5.6", "%", "HbA1c"))
    }

    @Test
    fun postProcessingFlagsAnotherTestsUnitOnTheSameLine() {
        val text = "KREA: 0,80 mg/dl; HARNS: 5,1 mg/dl; GPT: 59 U/l\nTSH: 1,9 mU/l; Ferritin: 88 ng/ml"
        fun flagged(vararg entries: AiLabEntry) =
            LabParsePipeline.postProcess(AiLabReport(entries.toList(), reportLanguage = "de"), text).values.map { it.needsSourceCheck }
        assertEquals(listOf(true, false), flagged(AiLabEntry("KREA", "Kreatinin", "0,80", "U/l"), AiLabEntry("GPT", "GPT", "59", "U/l")))
        assertEquals(listOf(true, false), flagged(AiLabEntry("FERR", "Ferritin", "88", "mU/l"), AiLabEntry("TSH", "TSH", "1,9", "mU/l")))
    }

    private fun sampleReports(): List<Pair<String, List<Triple<String, String, List<String>>>>> {
        val reports = File("../evaluation/reports").listFiles { file -> file.name.endsWith(".txt") }!!.sortedBy { it.name }
        assertEquals(23, reports.size)
        return reports.map { report ->
            val expected = Json.parseToJsonElement(File(report.parentFile, report.nameWithoutExtension + ".expected.json").readText())
            report.readText() to expected.jsonObject.getValue("values").jsonArray.map { value ->
                val fields = value.jsonObject
                Triple(
                    fields.getValue("value").jsonPrimitive.content,
                    fields.getValue("unit").jsonPrimitive.content,
                    fields.getValue("names").jsonArray.map { it.jsonPrimitive.content },
                )
            }
        }
    }

    /**
     * No model needed: everything the sample reports print, as the people who
     * wrote them read it, must count as printed — the result, its unit, and on
     * its test's row. A false alarm on most rows would teach people to ignore the badge.
     */
    @Test
    fun theSampleReportsRaiseNoFalseAlarm() {
        for ((text, values) in sampleReports()) {
            val source = ReportSourceText(text)
            for ((number, unit, names) in values) {
                assertTrue(source.prints(number), "$number in ${names.first()}")
                assertTrue(source.printsUnit(unit), "$unit of ${names.first()}")
                assertTrue(source.printsBesideTest(number, names), "$number beside ${names.first()}")
                assertTrue(source.printsUnitBesideTest(number, unit, names), "$unit behind ${names.first()}'s $number")
                assertTrue(source.backs(number, unit, names), "${names.first()}")
            }
        }
    }

    /** Each other unit of a sample report put on a test whose row is found is caught. */
    @Test
    fun anotherUnitOfASampleReportIsCaught() {
        var swapped = 0
        for ((text, values) in sampleReports()) {
            val source = ReportSourceText(text)
            fun List<String>.namedIn(line: String) = any { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) in line }
            for ((number, unit, names) in values) {
                // The scorer's names are parts of words ("Triglyc"): no row of those is found.
                // Without a unit, the row prints none behind it to compare with ("Specific Gravity 1.018").
                if (unit.isEmpty() || text.lines().none { names.namedIn(it) }) continue
                for (other in values.map { it.second }.filter { it.isNotEmpty() }.distinct()) {
                    // The same unit, whatever the case ("G/L" and "g/L" too: the check reads units case-blind).
                    if (other.lowercase().replace('µ', 'u') == unit.lowercase().replace('µ', 'u')) continue
                    assertFalse(source.printsUnitBesideTest(number, other, names), "$other behind ${names.first()}'s $number")
                    swapped++
                }
            }
        }
        assertTrue(swapped > 150, "$swapped")
    }

    /** Each result of a sample report moved onto another test's row is caught, also onto another test of its own line. */
    @Test
    fun aResultMovedToAnotherTestOfASampleReportIsCaught() {
        var moved = 0
        for ((text, values) in sampleReports()) {
            val source = ReportSourceText(text)
            // The scorer's names are parts of words ("Triglycerid"); the check names a test by whole words only.
            fun List<String>.namedIn(line: String) = any { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) in line }
            // What each line prints before its first number: its first test's label.
            val labels = text.lines().map { line -> line.takeWhile { !it.isDigit() } }
            for ((number, _, names) in values) {
                for ((otherNumber, _, otherNames) in values) {
                    if (otherNumber.toDouble() == number.toDouble()) continue
                    if (text.lines().none { otherNames.namedIn(it) }) continue
                    // One label naming both ("Colesterolo HDL", "Albumin/Kreatinin-Quotient") is both tests' row;
                    // "KREA: 0,80 mg/dl; HARNS: 5,1 mg/dl" names each in its own part and is caught.
                    if (labels.any { label -> names.any { label.contains(it, ignoreCase = true) } && otherNames.namedIn(label) }) continue
                    assertFalse(source.printsBesideTest(number, otherNames), "$number beside ${otherNames.first()}")
                    moved++
                }
            }
        }
        assertTrue(moved > 1000, "$moved")
    }

    /**
     * Every result with "<" or ">" the sample reports print (their text results with the
     * printed `result`), as printed, backs; without it, it's caught.
     */
    @Test
    fun aQualifierDroppedFromASampleReportIsCaught() {
        var dropped = 0
        val reports = File("../evaluation/reports").listFiles { file -> file.name.endsWith(".txt") }!!
        for (report in reports) {
            val expected = Json.parseToJsonElement(File(report.parentFile, report.nameWithoutExtension + ".expected.json").readText())
            val source = ReportSourceText(report.readText())
            val texts = expected.jsonObject["notNumeric"]?.jsonArray.orEmpty().map { it.jsonObject }
            for (text in texts) {
                val result = text["result"]?.jsonPrimitive?.content ?: continue
                val number = Regex("^[<>]=?\\s*(\\d.*)$").matchEntire(result)?.groupValues?.get(1) ?: continue
                val names = text.getValue("names").jsonArray.map { it.jsonPrimitive.content }
                // The scorer's names are parts of words ("Filtrado"): only the rows the check can find.
                if (report.readLines().none { line -> names.any { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) in line } }) continue
                assertFalse(source.dropsQualifier(result, names), "$result of ${names.first()} in ${report.name}")
                assertTrue(source.dropsQualifier(number, names), "$number for $result of ${names.first()} in ${report.name}")
                dropped++
            }
        }
        assertTrue(dropped >= 20, "$dropped")
    }
}
