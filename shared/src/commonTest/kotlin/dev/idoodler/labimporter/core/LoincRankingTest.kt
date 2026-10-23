package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.loinc.LoincCandidate
import dev.idoodler.labimporter.core.loinc.LoincRanking
import dev.idoodler.labimporter.core.loinc.UnitKind
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoincRankingTest {

    private val hemoglobin = LoincCandidate("718-7", 17, "MCnc", "Qn", "Bld", listOf("Hämoglobin [Masse/Volumen] in Blut"))
    private val mchc = LoincCandidate(
        "786-4", 13, "EntMCnc", "Qn", "RBC",
        listOf("Mittlere korpuskuläre Hämoglobinkonzentration eines Erythrozyten (MCHC) [Entitische Masse/Volumen]"),
    )

    @Test
    fun theLabelThatNamesTheTestBeatsAMoreCommonTermThatOnlyMentionsIt() {
        // MCHC is ordered more often, but its label names a different measurement.
        assertEquals("718-7", LoincRanking.best("Hämoglobin", listOf(mchc, hemoglobin))?.code)
        val italianMchc = mchc.copy(labels = listOf("Emoglobina in RBC (EntMCnc, Conta automatica)"))
        val italianHb = hemoglobin.copy(labels = listOf("Emoglobina in Sangue (MCnc)"))
        // Both labels just say "Emoglobina": the unit's own kind of quantity decides.
        assertEquals("718-7", LoincRanking.best("Emoglobina", listOf(italianMchc, italianHb), unit = "g/dl")?.code)
    }

    @Test
    fun aTermThatCantHoldTheValueIsNeverSuggested() {
        val crystals = LoincCandidate("5817-2", 1607, "PrThr", "Ord", "Urine sed", listOf("Cristales de ácido úrico in Sedimento urinario (PrThr)"))
        assertNull(LoincRanking.best("Ácido úrico", listOf(crystals), unit = "mg/dl"))
        // Without a unit (a "negativ" finding) nothing is ruled out.
        assertEquals("5817-2", LoincRanking.best("Ácido úrico", listOf(crystals))?.code)
    }

    @Test
    fun aMolarUnitKeepsTheUsualSpecimen() {
        val serumMass = LoincCandidate("2160-0", 4, "MCnc", "Qn", "Ser/Plas", listOf("Kreatinin in Ser/Plaz (KütlKons)"))
        val bloodMoles = LoincCandidate("59826-8", 1891, "SCnc", "Qn", "Bld", listOf("Kreatinin in Kan (SubKons)"))
        val serumMoles = LoincCandidate("14682-9", 2641, "SCnc", "Qn", "Ser/Plas", listOf("Kreatinin in Ser/Plaz (SubKons)"))
        val all = listOf(serumMass, bloodMoles, serumMoles)
        assertEquals("2160-0", LoincRanking.best("Kreatinin", all, unit = "mg/dl")?.code)
        assertEquals("14682-9", LoincRanking.best("Kreatinin", all, unit = "µmol/l")?.code)
    }

    @Test
    fun englishMethodAbbreviationsDontCountAgainstALabel() {
        val auto = LoincCandidate("6690-2", 21, "NCnc", "Qn", "Bld", listOf("WBC Auto (Bld) [#/Vol]"))
        val unspecified = LoincCandidate("26464-8", 68, "NCnc", "Qn", "Bld", listOf("WBC (Bld) [#/Vol]"))
        assertEquals("6690-2", LoincRanking.best("WBC", listOf(unspecified, auto), unit = "10^3/µl")?.code)
    }

    @Test
    fun labelCoresDropSpecimenPropertyAndParentheses() {
        assertEquals("Hämoglobin", LoincRanking.labelCore("Hämoglobin [Masse/Volumen] in Blut"))
        assertEquals("Emoglobina", LoincRanking.labelCore("Emoglobina in Sangue (MCnc)"))
        assertEquals("Hemoglobin", LoincRanking.labelCore("Hemoglobin (Bld) [Mass/Vol]"))
        assertEquals("Cholesterol in HDL", LoincRanking.labelCore("Cholesterol in HDL [Masse/Volumen] in Serum oder Plasma"))
        assertEquals("Cholesterol.w HDL", LoincRanking.labelCore("Cholesterol.w HDL in surowica lub osocze (stężenie masowe)"))
        assertEquals("Cholesterol in LDL real size pattern Nom", LoincRanking.labelCore("Cholesterol in LDL real size pattern Nom"))
        assertEquals(listOf("hemoglobine", "a1c"), LoincRanking.foldedTokens("Hémoglobine A1c"))
    }

    @Test
    fun unitsTellTheirKindOfQuantity() {
        assertEquals(UnitKind.MASS_CONCENTRATION, UnitKind.of("mg/dl"))
        assertEquals(UnitKind.MASS_CONCENTRATION, UnitKind.of("g/l"))
        assertEquals(UnitKind.MASS_CONCENTRATION, UnitKind.of("mg/dL (calc)"))
        assertEquals(UnitKind.NUMBER_CONCENTRATION, UnitKind.of("G/l")) // giga per litre: cells
        assertEquals(UnitKind.NUMBER_CONCENTRATION, UnitKind.of("10^3/µl"))
        assertEquals(UnitKind.NUMBER_CONCENTRATION, UnitKind.of("/nl"))
        assertEquals(UnitKind.SUBSTANCE_CONCENTRATION, UnitKind.of("µmol/l"))
        assertEquals(UnitKind.CATALYTIC_CONCENTRATION, UnitKind.of("U/l"))
        assertEquals(UnitKind.CATALYTIC_CONCENTRATION, UnitKind.of("µUI/ml"))
        assertEquals(UnitKind.FRACTION, UnitKind.of("%"))
        assertEquals(UnitKind.MOLAR_FRACTION, UnitKind.of("mmol/mol"))
        assertEquals(UnitKind.CELL_VOLUME, UnitKind.of("fl"))
        assertEquals(UnitKind.RATIO, UnitKind.of("mg/g"))
        assertEquals(UnitKind.FLOW, UnitKind.of("ml/min/1,73m²"))
        assertNull(UnitKind.of(""))
        assertNull(UnitKind.of("mosm/kg"))
    }

    @Test
    fun aFlagReadIntoTheUnitIsDropped() {
        assertEquals("mmol/l", LabParsePipeline.unitWithoutFlag("H mmol/l"))
        assertEquals("mg/dl", LabParsePipeline.unitWithoutFlag(" L  mg/dl "))
        assertEquals("U/l", LabParsePipeline.unitWithoutFlag("U/l"))
        assertEquals("H", LabParsePipeline.unitWithoutFlag("H"))
        assertEquals("ml/min/1,73 m²", LabParsePipeline.unitWithoutFlag("ml/min/1,73 m²"))
    }
}
