package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.loinc.LoincDirectory
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.deduplicatedByLoinc
import dev.idoodler.labimporter.core.model.duplicateLoincCodes
import dev.idoodler.labimporter.core.parse.AiLabEntry
import dev.idoodler.labimporter.core.parse.AiLabReport
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starts the shared core on the real, build-generated loinc.db. */
fun startCatalog(language: String = "de-DE", preferences: LabDisplayPreferences = LabDisplayPreferences()) {
    LabCore.start(System.getProperty("loinc.db"), language) { preferences }
    assertTrue(LabCore.loinc!!.isAvailable, "loinc.db missing — run :androidApp:buildLoincDb first")
}

class CatalogTest {

    @BeforeTest
    fun start() = startCatalog()

    @Test
    fun knownCodesResolveAndUnknownDoNot() {
        assertTrue(LabCore.loinc!!.isKnownLoinc("2160-0"))
        assertFalse(LabCore.loinc!!.isKnownLoinc("KREA"))
        assertEquals("2160-0", LabMapping.loincCode(" 2160-0 ")!!.loinc)
        assertNull(LabMapping.loincCode("KREA"))
        assertEquals("https://loinc.org/2160-0/", LabMapping.loincUrl("2160-0"))
        assertNull(LabMapping.loincUrl("KREA"))
    }

    @Test
    fun displayNamePrefersNickname() {
        startCatalog(preferences = LabDisplayPreferences(nicknames = mapOf("2160-0" to "Krea")))
        assertEquals("Krea", LabMapping.displayName("2160-0"))
        assertEquals("KREA", LabMapping.displayName("KREA"))
    }

    @Test
    fun categoriesFromTheCatalog() {
        assertEquals(LabCategory.RENAL, LabCategory.forCode("2160-0"))
        assertEquals(LabCategory.GLYCEMIC, LabCategory.forCode("4548-4"))
        assertEquals(LabCategory.OTHER, LabCategory.forCode("not-a-code"))
    }

    @Test
    fun searchFindsLocalizedNames() {
        val hits = LabCore.loinc!!.search("Kalium", language = "de", limit = 5)
        assertTrue(hits.any { it.code == "2823-3" }, hits.toString())
        assertTrue(LabCore.loinc!!.search("", limit = 10).size == 10)
    }

    @Test
    fun pipelineResolvesSuggestsAndKeepsUnmapped() {
        val report = AiLabReport(
            entries = listOf(
                AiLabEntry("2160-0", "Creatinin", "0,80", "mg/dl"),
                AiLabEntry("K", "Kalium", "4,1", " mmol/l "),
                AiLabEntry("XYZQ", "Völlig unbekannter Test", "12", "U/l"),
                AiLabEntry("CRP", "C-reaktives Protein", "< 5", "mg/l"),
                AiLabEntry("HIV", "HIV", "-", ""),
            ),
            reportDate = "2026-05-06", patientName = " Max ", reportLanguage = "de",
        )
        val result = LabParsePipeline.postProcess(report, "Befund vom 06.05.2026")
        val byName = result.values.associateBy { it.displayValue }
        assertEquals("2160-0", byName.getValue("0,80").code)
        assertFalse(byName.getValue("0,80").isSuggestedCode)
        assertEquals("2823-3", byName.getValue("4,1").code)
        assertTrue(byName.getValue("4,1").isSuggestedCode)
        assertEquals("mmol/l", byName.getValue("4,1").unit)
        // "< 5" is a qualified result, not the number 5.
        assertNull(byName.getValue("< 5").numericValue)
        assertNull(byName.getValue("-").numericValue)
        assertEquals("Max", result.patientName)
        assertEquals(kotlinx.datetime.LocalDate(2026, 5, 6), result.reportDate)
    }

    @Test
    fun everydaySpellingsResolveThroughOtherLanguages() {
        // German LOINC says "Creatinin"; reports and the model say "Kreatinin".
        assertEquals("2160-0" to true, LabParsePipeline.resolveLoinc("KREA", "Kreatinin", "de"))
    }

    @Test
    fun littleWordsDontBlockAMatch() {
        val (code, suggested) = LabParsePipeline.resolveLoinc("MALB-U", "Albumin im Urin", "de")
        assertTrue(suggested)
        assertTrue(LabMapping.catalogName(code).contains("Urin"), LabMapping.catalogName(code))
        assertEquals("albumin urin", LoincDirectory.withoutStopWords("Albumin im Urin"))
        // Single letters and element symbols are never dropped; nothing to drop → null.
        assertNull(LoincDirectory.withoutStopWords("Vitamin A"))
        assertEquals("na serum", LoincDirectory.withoutStopWords("Na im Serum"))
    }

    @Test
    fun spellingVariantsResolveByStem() {
        // LOINC says "Cholesterol"; German reports say "Cholesterin".
        assertEquals("2093-3" to true, LabParsePipeline.resolveLoinc("CHOL", "Cholesterin", "de"))
        assertEquals("2085-9" to true, LabParsePipeline.resolveLoinc("HDL", "HDL-Cholesterin", "de"))
        assertEquals("43396-1" to true, LabParsePipeline.resolveLoinc("NONHDL", "Non-HDL-Cholesterin", "de"))
        assertEquals("2571-8" to true, LabParsePipeline.resolveLoinc("TRIG", "Triglyzeride", "de"))
        assertEquals(listOf("cholesteri", "cholester", "choleste", "cholest", "choles"), LoincDirectory.stemmedQueries("Cholesterin"))
        assertEquals(listOf("hdl cholesteri", "hdl cholester"), LoincDirectory.stemmedQueries("HDL-Cholesterin").take(2))
        assertEquals(emptyList(), LoincDirectory.stemmedQueries("Eisen im Serum")) // No word long enough to cut.
    }

    @Test
    fun aPrintedMnemonicMatchesOnlyAsAWholeWord() {
        // Without a usable name, "KREA" must not become "Kreatinkinase" by prefix.
        assertEquals("KREA" to false, LabParsePipeline.resolveLoinc("KREA", "", "de"))
        val (tsh, suggested) = LabParsePipeline.resolveLoinc("TSH", "", "de")
        assertTrue(suggested)
        assertEquals("3016-3", tsh)
    }

    @Test
    fun theTermThatNamesTheTestWinsOverOneThatMentionsIt() {
        // MCHC ("…Hämoglobinkonzentration eines Erythrozyten") is ordered more often than either.
        assertEquals("718-7" to true, LabParsePipeline.resolveLoinc("HB", "Hämoglobin", "de", "g/dl"))
        assertEquals("789-8" to true, LabParsePipeline.resolveLoinc("ERY", "Erythrozyten", "de", "/pl"))
        assertEquals("718-7" to true, LabParsePipeline.resolveLoinc("Hb", "Emoglobina", "it", "g/dl"))
        assertEquals("718-7" to true, LabParsePipeline.resolveLoinc("Hb", "Hémoglobine", "fr", "g/dl"))
        assertEquals("718-7" to true, LabParsePipeline.resolveLoinc("HGB", "HGB", "pl", "g/dl"))
        assertEquals("787-2" to true, LabParsePipeline.resolveLoinc("MCV", "MCV", "de", "fl"))
        assertEquals("6690-2" to true, LabParsePipeline.resolveLoinc("WBC", "WBC", "pl", "10^3/µl"))
        // Total cholesterol is plain "Cholesterol" in LOINC — never the cholesterol/HDL ratio.
        assertEquals("2093-3" to true, LabParsePipeline.resolveLoinc("CHOL", "Cholesterol", "pl", "mg/dl"))
        assertEquals("2093-3" to true, LabParsePipeline.resolveLoinc("CHOL", "Cholesterol, Total", "en", "mg/dL"))
        assertEquals("2093-3" to true, LabParsePipeline.resolveLoinc("COL", "Colesterolo totale", "it", "mg/dl"))
        assertEquals("2093-3" to true, LabParsePipeline.resolveLoinc("CT", "Cholestérol total", "fr", "g/l"))
        assertEquals("1975-2" to true, LabParsePipeline.resolveLoinc("BILI", "Bilirubin gesamt", "de", "mg/dl"))
        assertEquals("13457-7" to true, LabParsePipeline.resolveLoinc("LDL", "LDL-Cholesterol", "en", "mg/dL (calc)"))
    }

    @Test
    fun theUnitRulesOutTermsThatCantHoldTheValue() {
        // Only uric acid *crystals* are called "ácido úrico" in Spanish LOINC: unmapped beats wrong.
        assertEquals("AU" to false, LabParsePipeline.resolveLoinc("AU", "Ácido úrico", "es", "mg/dl"))
        // "G/l" counts cells (giga per litre).
        assertEquals("6690-2" to true, LabParsePipeline.resolveLoinc("LEU", "Leucocytes", "fr", "G/l"))
        assertEquals("777-3" to true, LabParsePipeline.resolveLoinc("PLT", "Plaquettes", "fr", "G/l"))
        // A quantity in mg/l isn't the urine-strip finding.
        val (albumin, _) = LabParsePipeline.resolveLoinc("MALB-U", "Albumin im Urin", "de", "mg/l")
        assertEquals("MCnc", LabCore.loinc!!.detail(albumin)!!.property)
        assertEquals("Urine", LabCore.loinc!!.detail(albumin)!!.system)
        // Moles keep the usual specimen: serum creatinine, blood hemoglobin.
        assertEquals("14682-9" to true, LabParsePipeline.resolveLoinc("KREA", "Kreatinin", "de", "µmol/l"))
        assertEquals("59260-0" to true, LabParsePipeline.resolveLoinc("HB", "Hämoglobin", "de", "mmol/l"))
    }

    @Test
    fun aSecondNameForTheSameLoincStaysUnmappedInsteadOfReplacingAValue() {
        val (glucose, _) = LabParsePipeline.resolveLoinc("", "Glucose", "de", "mg/dl")
        val twice = AiLabReport(
            entries = listOf(AiLabEntry("GLU", "Glucose", "92", "mg/dl"), AiLabEntry("GLUC", "Glucose", "95", "H mg/dl")),
            reportLanguage = "de",
        )
        val values = LabParsePipeline.postProcess(twice, "").values
        assertEquals(listOf("92", "95"), values.map { it.displayValue })
        assertEquals(listOf(glucose, "GLUC"), values.map { it.code })
        assertEquals(listOf(true, false), values.map { it.isSuggestedCode })
        assertEquals("mg/dl", values[1].unit)
        // A printed LOINC claims its code ahead of any suggestion.
        val printed = AiLabReport(
            entries = listOf(AiLabEntry("GLU", "Glucose", "92", "mg/dl"), AiLabEntry(glucose, "Glucose", "90", "mg/dl")),
            reportLanguage = "de",
        )
        assertEquals(listOf("GLU", glucose), LabParsePipeline.postProcess(printed, "").values.map { it.code })
    }

    @Test
    fun duplicatesAreDetectedAndCollapsed() {
        val a = LabValue(code = "2160-0", name = "Creatinin", displayValue = "0.8", numericValue = 0.8, unit = "mg/dl")
        val b = a.copy(id = "b", displayValue = "0.9", numericValue = 0.9)
        val unmapped = LabValue(code = "KREA", name = "KREA", displayValue = "1", numericValue = 1.0, unit = "")
        assertEquals(setOf("2160-0"), listOf(a, b, unmapped).duplicateLoincCodes())
        assertEquals(listOf(a, unmapped), listOf(a, b, unmapped).deduplicatedByLoinc())
        // A deselected row isn't exported, so it isn't a conflict — and a row with data beats one without.
        val deselected = b.copy(isSelected = false)
        assertEquals(emptySet(), listOf(a, deselected).duplicateLoincCodes())
        val empty = a.copy(id = "c", numericValue = null)
        assertEquals(listOf(a), listOf(empty, a).deduplicatedByLoinc())
    }
}
