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

    private fun sampleReports(): List<Pair<String, List<Triple<String, String, List<String>>>>> {
        val reports = File("../evaluation/reports").listFiles { file -> file.name.endsWith(".txt") }!!.sortedBy { it.name }
        assertEquals(9, reports.size)
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
                assertTrue(source.backs(number, unit, names), "${names.first()}")
            }
        }
    }

    /** Each result of a sample report moved onto another test's row is caught; one row can't tell its tests apart. */
    @Test
    fun aResultMovedToAnotherTestOfASampleReportIsCaught() {
        var moved = 0
        for ((text, values) in sampleReports()) {
            val source = ReportSourceText(text)
            // The scorer's names are parts of words ("Triglycerid"); the check names a test by whole words only.
            fun List<String>.namedIn(line: String) = any { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) in line }
            for ((number, _, names) in values) {
                for ((otherNumber, _, otherNames) in values) {
                    if (otherNumber.toDouble() == number.toDouble()) continue
                    if (text.lines().none { otherNames.namedIn(it) }) continue
                    // "KREA: 0,80 mg/dl; HARNS: 5,1 mg/dl" is one line.
                    if (text.lines().any { line -> names.any { line.contains(it, ignoreCase = true) } && otherNames.namedIn(line) }) continue
                    assertFalse(source.printsBesideTest(number, otherNames), "$number beside ${otherNames.first()}")
                    moved++
                }
            }
        }
        assertTrue(moved > 500, "$moved")
    }
}
