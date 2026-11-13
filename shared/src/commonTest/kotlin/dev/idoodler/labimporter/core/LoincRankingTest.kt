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
    fun aMixIsNamedOnlyByMoreThanOneMemberOrItsOwnName() {
        val mix = LoincCandidate(
            "23801-4", 11003, "PrThr", "Ord", "Ser",
            listOf(
                "(Arachis hypogaea+Kuhmilch+Eiweiß+Gadus morhua+Glycine max+Triticum aestivum) IgE-Antikörper [Nachweis] in Serum",
                "Food Allergen Mix 5 (Peanut+Cow milk+Egg white+Cod+Soybean+Wheat) IgE Ab [Presence] in Serum by Multidisk",
            ),
            synonyms = listOf("fx5"),
            component = "(Arachis hypogaea+Cow milk+Egg white+Gadus morhua+Glycine max+Triticum aestivum) Ab.IgE",
            loincClass = "ALLERGY",
        )
        val single = LoincCandidate(
            "6106-9", 1200, "ACnc", "Qn", "Ser", listOf("Eiweiß IgE-Antikörper [Einheiten/Volumen] in Serum"),
            component = "Egg white Ab.IgE", loincClass = "ALLERGY",
        )
        // One member, with or without what all members share: not the mix.
        assertNull(LoincRanking.best("Eiweiß", listOf(mix)))
        assertNull(LoincRanking.best("Eiweiß IgE", listOf(mix)))
        assertNull(LoincRanking.best("Wheat", listOf(mix)))
        assertEquals("6106-9", LoincRanking.best("Eiweiß", listOf(mix, single))?.code)
        // Two members, the mix's own name or its code: the mix.
        assertEquals("23801-4", LoincRanking.best("Kuhmilch Eiweiß", listOf(mix))?.code)
        assertEquals("23801-4", LoincRanking.best("Food Allergen Mix 5", listOf(mix))?.code)
        assertEquals("23801-4", LoincRanking.best("fx5", listOf(mix))?.code)
        // Only an allergen's parenthesised list is a mix: a sum, a cell phenotype or an unbracketed list is not.
        val fluoxetine = LoincCandidate(
            "10339-0", 4000, "MCnc", "Qn", "Ser/Plas", listOf("Fluoxetina+Norfluoxetina in Siero/Plasma (MCnc)"),
            component = "Fluoxetine+Norfluoxetine", loincClass = "DRUG/TOX",
        )
        assertEquals("10339-0", LoincRanking.best("Fluoxetina", listOf(fluoxetine), unit = "ng/ml")?.code)
        val helpers = LoincCandidate(
            "24467-3", 1500, "NCnc", "Qn", "Bld", listOf("CD3+CD4+ (T4 helper) cells [#/volume] in Blood"),
            component = "Cells.CD3+CD4+", loincClass = "CELLMARK",
        )
        assertEquals("24467-3", LoincRanking.best("CD4", listOf(helpers), unit = "/µl")?.code)
        val horse = mix.copy(
            code = "30088-9", labels = listOf("Horse hair+Horse dander IgE Ab [Presence] in Serum"),
            synonyms = emptyList(), component = "Horse hair+Horse dander Ab.IgE",
        )
        assertEquals("30088-9", LoincRanking.best("Horse hair", listOf(horse))?.code)
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
        // A flag alone is no unit (hours are "h"); it would otherwise end up in a saved "1:160 H".
        assertEquals("", LabParsePipeline.unitWithoutFlag("H"))
        assertEquals("h", LabParsePipeline.unitWithoutFlag("h"))
        assertEquals("ml/min/1,73 m²", LabParsePipeline.unitWithoutFlag("ml/min/1,73 m²"))
    }
}
