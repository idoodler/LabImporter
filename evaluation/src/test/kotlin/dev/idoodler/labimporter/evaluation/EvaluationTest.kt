package dev.idoodler.labimporter.evaluation

import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.parse.AiLabEntry
import dev.idoodler.labimporter.core.parse.AiLabReport
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvaluationTest {

    @Test
    fun theHeldOutSetIsItsOwnWithEveryReply() {
        // A held-out report that is also a tuning report would be scored on what the check was tuned against.
        fun reports(heldOut: Boolean) = File(setFolder(heldOut), "reports").listFiles { f -> f.name.endsWith(".txt") }!!.toList()
        val tuning = reports(heldOut = false)
        val heldOut = reports(heldOut = true)
        assertTrue(heldOut.size >= 10, "held-out reports: ${heldOut.size}")
        assertTrue(heldOut.none { h -> tuning.any { it.name == h.name || it.readText() == h.readText() } })
        for (report in heldOut) {
            assertTrue(File(report.parentFile, report.nameWithoutExtension + ".expected.json").isFile, "${report.name} has no expected values")
            assertTrue(File(File(setFolder(true), "replies"), report.nameWithoutExtension + ".reply.json").isFile, "${report.name} has no reply")
        }
    }

    @Test
    fun aCutOffUnitIsNoMatch() {
        assertFalse(unitMatches("mg", "mg/dl"))
        assertFalse(unitMatches("/l", "U/l"))
        assertFalse(unitMatches("mg/dl", "mg"))
        assertFalse(unitMatches("", "mg/dl"))
        assertFalse(unitMatches("mg/dL (calc)", "mg/dL"))
    }

    @Test
    fun unitsMatchAfterClinicalNormalisation() {
        assertTrue(unitMatches("mg/dL", "mg/dl"))
        assertTrue(unitMatches("µmol/L", "umol/l"))
        assertTrue(unitMatches("μg/l", "µg/L"))
        assertTrue(unitMatches("10³/µl", "10^3/uL"))
        assertTrue(unitMatches("ml/min/1,73m²", "mL/min/1.73 m2"))
        assertTrue(unitMatches("", ""))
        assertTrue(unitMatches("G/l", "G/L"))
        assertTrue(unitMatches("G/L", "10^9/L"))
        assertTrue(unitMatches("T/L", "10¹²/l"))
    }

    @Test
    fun aCountPrefixIsNoGram() {
        // "G/L" is 10⁹ cells a litre (French leucocytes, platelets); "g/L" is grams a litre.
        assertFalse(unitMatches("g/L", "G/L"))
        assertFalse(unitMatches("G/L", "g/l"))
        assertFalse(unitMatches("t/l", "T/L"))
        // So reading one for the other is planted as another test's unit.
        val entries = listOf(AiLabEntry(name = "Leucocytes", rawValue = "7,2", unit = "G/L"), AiLabEntry(name = "Albumine", rawValue = "41", unit = "g/L"))
        assertEquals("g/L", plantedFaults(entries, 0).single { it.first == Fault.WRONG_UNIT }.second.unit)
        assertEquals("G/L", plantedFaults(entries, 1).single { it.first == Fault.WRONG_UNIT }.second.unit)
    }

    @Test
    fun aUnitWithAPrintedNoteIsNoOtherUnit() {
        // "mg/dL (calc)" is LDL's mg/dL with the lab's note: planting it on total cholesterol plants nothing wrong.
        val entries = listOf(
            AiLabEntry(name = "Cholesterol", rawValue = "214", unit = "mg/dL"),
            AiLabEntry(name = "LDL", rawValue = "138", unit = "mg/dL (calc)"),
            AiLabEntry(name = "HbA1c", rawValue = "5.9", unit = "%"),
        )
        assertEquals("%", plantedFaults(entries, 0).single { it.first == Fault.WRONG_UNIT }.second.unit)
        assertEquals("%", plantedFaults(entries, 1).single { it.first == Fault.WRONG_UNIT }.second.unit)
    }

    @Test
    fun faultsArePlantedDeterministically() {
        val entries = listOf(AiLabEntry(name = "Kreatinin", rawValue = "0,80", unit = "mg/dl"), AiLabEntry(name = "GPT", rawValue = "59", unit = "U/l"))
        val faults = plantedFaults(entries, 0).associate { (kind, entry) -> kind to (entry.rawValue to entry.unit) }
        assertEquals(
            mapOf(
                Fault.TENFOLD to ("8,0" to "mg/dl"),
                Fault.TENTH to ("0,080" to "mg/dl"),
                Fault.DIGIT_DOUBLED to ("0,880" to "mg/dl"),
                Fault.NEIGHBOUR to ("59" to "mg/dl"),
                Fault.WRONG_UNIT to ("0,80" to "U/l"),
            ),
            faults,
        )
        // "0,80" has no digit to drop that changes it to another number but zero; "59" has.
        assertEquals("9", plantedFaults(entries, 1).single { it.first == Fault.DIGIT_DROPPED }.second.rawValue)
        assertEquals(plantedFaults(entries, 0), plantedFaults(entries, 0))
    }

    @Test
    fun aQualifierIsDroppedAndDatesAreMoved() {
        val crp = listOf(AiLabEntry(name = "CRP", rawValue = "< 0,5", unit = "mg/dl"))
        assertEquals(listOf(Fault.QUALIFIER_DROPPED to "0,5"), plantedFaults(crp, 0).map { it.first to it.second.rawValue })
        assertEquals(
            listOf(Fault.DATE_DAY to LocalDate(2026, 10, 4), Fault.DATE_YEAR to LocalDate(2025, 10, 3), Fault.DATE_SWAPPED to LocalDate(2026, 3, 10)),
            plantedDates(LocalDate(2026, 10, 3)),
        )
        assertEquals(listOf(Fault.DATE_DAY, Fault.DATE_YEAR), plantedDates(LocalDate(2026, 10, 13)).map { it.first })
    }

    @Test
    fun aTextResultMustBeThePrintedOne() {
        // en_us_wellness: the model read Vitamin D ">150" as ">1500"; Review flags it, and that's no false alarm.
        val vitaminD = ExpectedText(listOf("Vitamin D"), ">150")
        assertTrue(vitaminD.readsAs(">150"))
        assertTrue(vitaminD.readsAs("> 150"))
        assertFalse(vitaminD.readsAs(">1500"))
        assertFalse(vitaminD.readsAs("<150"))
        assertTrue(ExpectedText(listOf("PCR"), "< 0,30").readsAs("<0.3"))
        assertTrue(ExpectedText(listOf("HBs")).readsAs("NON-REACTIVE"), "without a printed result, any text counts")
    }

    @Test
    fun aDateIsCaughtByItsFlagOnlyWhereTheRightOneIsntFlagged() {
        LabCore.start(System.getProperty("loinc.db"), "en-US") { LabDisplayPreferences() }
        fun planted(text: String, date: String): PlantedSummary {
            val reply = AiLabReport(listOf(AiLabEntry(name = "TSH", rawValue = "2.84", unit = "mIU/L")), reportDate = date, reportLanguage = "en")
            val expected = Expected(reportDate = date, values = listOf(ExpectedValue(listOf("TSH"), 2.84, "mIU/L")))
            return plantErrors(listOf(PlantedReport("us", "$text\nTSH 2.84 mIU/L\n", reply, expected)))
        }
        // 08/12/2026 on the evaluation's day: 8 December hasn't come, so 12 August stands unflagged
        // and its swap, kept, is caught by its flag.
        val august = planted("Collected: 08/12/2026", "2026-08-12")
        assertEquals(KindScore(1, 1), august.byKind.getValue(Fault.DATE_SWAPPED.name))
        assertEquals(1 to 0, august.dateControls to august.dateFalseFlags)
        // 11/04/2026 reads either way: the right date is flagged too, which counts as a false alarm,
        // and a flag that is on either reading catches nothing.
        val either = planted("DOB: 05/07/1958\nCollected: 11/04/2026", "2026-11-04")
        assertEquals(KindScore(1, 0), either.byKind.getValue(Fault.DATE_SWAPPED.name))
        assertEquals(1 to 1, either.dateControls to either.dateFalseFlags)
    }

    @Test
    fun controlsAreHarmlessRespellings() {
        val respelled = controls(AiLabEntry(name = "Kalium", rawValue = "4,1", unit = "mmol/l")).associate { (kind, e) -> kind to (e.rawValue to e.unit) }
        assertEquals(
            mapOf(Control.AS_READ to ("4,1" to "mmol/l"), Control.DECIMAL_SEPARATOR to ("4.1" to "mmol/l"), Control.LITRE_CASE to ("4,1" to "mmol/L")),
            respelled,
        )
    }

    @Test
    fun reviewFlagsPlantedErrorsInAReport() {
        LabCore.start(System.getProperty("loinc.db"), "de-DE") { LabDisplayPreferences() }
        val text = "Befund vom 03.10.2026\nKreatinin 0,80 mg/dl\nKalium 4,1 mmol/l\n"
        val reply = AiLabReport(
            entries = listOf(AiLabEntry(name = "Kreatinin", rawValue = "0,80", unit = "mg/dl"), AiLabEntry(name = "Kalium", rawValue = "4,1", unit = "mmol/l")),
            reportDate = "2026-10-03",
            reportLanguage = "de",
        )
        val expected = Expected(
            reportDate = "2026-10-03",
            values = listOf(ExpectedValue(listOf("Kreatinin"), 0.8, "mg/dl"), ExpectedValue(listOf("Kalium"), 4.1, "mmol/l")),
        )
        val summary = plantErrors(listOf(PlantedReport("sample", text, reply, expected)))
        val tenfold = summary.byKind.getValue(Fault.TENFOLD.name)
        assertEquals(KindScore(2, 2), tenfold)
        assertEquals(KindScore(2, 2), summary.byKind.getValue(Fault.NEIGHBOUR.name))
        assertEquals(3, summary.byKind.getValue(Fault.DATE_DAY.name).flagged + summary.byKind.getValue(Fault.DATE_YEAR.name).flagged +
            summary.byKind.getValue(Fault.DATE_SWAPPED.name).flagged)
        assertEquals(summary.byKind.values.sumOf { it.planted }, summary.planted)
        assertEquals(6, summary.controls)
        assertEquals(0, summary.falseFlags)
    }
}
