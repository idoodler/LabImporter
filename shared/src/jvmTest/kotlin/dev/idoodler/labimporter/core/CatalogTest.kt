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
    fun discouragedCodesLabsStillPrintAreKnownButNeverSuggested() {
        // Quest's blood lead: DISCOURAGED in favour of 77307-7 (venous blood), still printed on reports.
        assertTrue(LabCore.loinc!!.isKnownLoinc("5671-3"))
        assertEquals("DISCOURAGED", LabCore.loinc!!.detail("5671-3")!!.status)
        assertEquals("5671-3" to false, LabParsePipeline.resolveLoinc("5671-3", "Lead, Blood", "en", "mcg/dL"))
        assertEquals("5671-3", LabMapping.loincCode("5671-3")!!.loinc)
        // A name alone still finds the recommended ACTIVE term, and a search lists the discouraged one last.
        val (byName, _) = LabParsePipeline.resolveLoinc("PB", "Lead", "en", "mcg/dL")
        assertEquals("ACTIVE", LabCore.loinc!!.detail(byName)!!.status)
        val lead = LabCore.loinc!!.search("Lead Bld", language = "en", limit = 200).map { it.code }
        assertTrue("5671-3" in lead && lead.indexOf("77307-7") < lead.indexOf("5671-3"), lead.toString())
        // LDH: 2532-0 is DISCOURAGED, the enzymatic reactions are the ACTIVE terms.
        assertTrue(LabCore.loinc!!.isKnownLoinc("2532-0"))
        val (ldh, _) = LabParsePipeline.resolveLoinc("LDH", "Lactate dehydrogenase", "en", "U/L")
        assertEquals("ACTIVE", LabCore.loinc!!.detail(ldh)!!.status)
    }

    @Test
    fun aResultInWordsNeverLandsOnACountOrAConcentration() {
        val loinc = LabCore.loinc!!
        // "Eiweiß: negativ" once became egg white IgE, "Leukozyten: negativ" a blood count.
        for ((name, language) in listOf("Eiweiß" to "de", "Leukozyten" to "de", "Nitrit" to "de", "Proteínas" to "es")) {
            val (code, _) = LabParsePipeline.resolveLoinc("", name, language, "", "negativ")
            val scale = loinc.detail(code)?.scale
            assertTrue(scale == null || scale in setOf("Ord", "Nom", "OrdQn", "Nar"), "$name → $code ($scale)")
        }
        val (titer, _) = LabParsePipeline.resolveLoinc("", "ANA Titer", "en", "", "1:160")
        assertEquals("Titr", loinc.detail(titer)?.property, titer)
        // A section heading with a result is never a panel.
        val (panel, _) = LabParsePipeline.resolveLoinc("HEPATITIS PANEL", "Hepatitis Panel", "en", "", "NON-REACTIVE")
        assertTrue(loinc.detail(panel)?.scale != "-", panel)
        // A threshold is a quantity: the unit still decides.
        val (crp, _) = LabParsePipeline.resolveLoinc("CRP", "CRP", "de", "mg/dl", "< 0,5")
        assertEquals("MCnc", loinc.detail(crp)?.property, crp)
    }

    @Test
    fun flagsCopiedIntoAValueOrUnitAreDropped() {
        assertEquals("231", LabParsePipeline.valueWithoutFlag("231 *"))
        assertEquals("1:160", LabParsePipeline.valueWithoutFlag("1:160 H"))
        assertEquals("positiv (+)", LabParsePipeline.valueWithoutFlag("positiv (+)"))
        assertEquals("2 +", LabParsePipeline.valueWithoutFlag("2 +"))
        assertEquals("", LabParsePipeline.unitWithoutFlag("H"))
        assertEquals("", LabParsePipeline.unitWithoutFlag("A"))
        assertEquals("mg/dl", LabParsePipeline.unitWithoutFlag("H mg/dl"))
        val report = AiLabReport(listOf(AiLabEntry("COL", "Colesterolo totale", "231 *", "mg/dL")), reportLanguage = "it")
        val value = LabParsePipeline.postProcess(report, "").values.single()
        assertEquals(231.0, value.numericValue)
        assertEquals("231", value.displayValue)
    }

    @Test
    fun everydayEnglishNamesResolveThroughLoincsLongNamesAndRelatedNames() {
        // The English label is LOINC's terse display name ("ALP [Catalytic activity/Vol]", "Urate…");
        // a US report's names (Quest, Function Health) match the long name or a related name instead.
        startCatalog("en-US")
        val cases = listOf(
            Triple("Alkaline Phosphatase", "U/L", "6768-6"), Triple("Uric Acid", "mg/dL", "3084-1"),
            Triple("Urea Nitrogen (BUN)", "mg/dL", "3094-0"), Triple("Platelet Count", "Thousand/uL", "777-3"),
            Triple("Thyroid-Stimulating Hormone (TSH)", "mIU/L", "3016-3"), Triple("DHEA Sulfate", "mcg/dL", "2191-5"),
            Triple("Methylmalonic Acid", "nmol/L", "13964-2"), Triple("Prostate Specific Antigen (PSA), Total", "ng/mL", "2857-1"),
            Triple("Thyroglobulin Antibodies", "IU/mL", "8098-6"), Triple("Thyroid Peroxidase Antibodies (TPO)", "IU/mL", "8099-4"),
            Triple("Tissue Transglutaminase Ab, IgG", "U/mL", "32998-7"), Triple("Deamidated Gliadin Ab (IgG)", "U/mL", "63459-2"),
            // #115: the right variant, not just the right analyte.
            Triple("Carbon Dioxide", "mmol/L", "2028-9"), Triple("Lead", "mcg/dL", "77307-7"),
            Triple("Celiac - Deamidated Gliadin IgA", "U/mL", "58709-7"), Triple("LDL Cholesterol", "mg/dL (calc)", "13457-7"),
            Triple("Lymphocytes", "%", "736-9"), Triple("Calcium", "mg/dL", "17861-6"),
        )
        for ((name, unit, expected) in cases) {
            assertEquals(expected to true, LabParsePipeline.resolveLoinc("", name, "en", unit), name)
        }
        // A related name is weaker than a name: "HGB" is hemoglobin, never the "DO-Hgb" deoxyhemoglobin.
        assertEquals("718-7" to true, LabParsePipeline.resolveLoinc("HGB", "HGB", "pl", "g/dl"))
        // A printed code never matches a related name: "AU" (ácido úrico) is no gold ("Au").
        assertEquals("AU" to false, LabParsePipeline.resolveLoinc("AU", "Ácido úrico", "es", "mg/dl"))
        // Total IgA still comes from its printed code — a single letter never spreads a name.
        assertEquals("2458-8" to true, LabParsePipeline.resolveLoinc("IgA", "Immunoglobulin A (IgA)", "en", "mg/dL"))
        // "% by wt" is a fraction: never the omega-3 concentration in moles.
        val (omega, _) = LabParsePipeline.resolveLoinc("", "Omega-3 Total", "en", "% by wt")
        assertTrue(LabCore.loinc!!.detail(omega)?.property != "SCnc", omega)
        // A formula for one population ("among black population") only when the report says so.
        val (gfr, _) = LabParsePipeline.resolveLoinc("", "Glomerular Filtration Rate", "en", "mL/min/1.73m2")
        assertFalse("population" in LabCore.loinc!!.detail(gfr)!!.method, LabCore.loinc!!.detail(gfr)!!.method)
        // The code picker finds a test by its everyday name too.
        assertTrue(LabCore.loinc!!.search("uric acid", language = "en", limit = 10).any { it.code == "3084-1" })
    }

    @Test
    fun aStatusLabelInFrontOfAValueIsDropped() {
        assertEquals("42", LabParsePipeline.valueWithoutFlag("In Range· 42"))
        assertEquals("6.1", LabParsePipeline.valueWithoutFlag("Above Range· 6.1"))
        assertEquals("<1.0", LabParsePipeline.valueWithoutFlag("In Range· <1.0"))
        assertEquals("231", LabParsePipeline.valueWithoutFlag("H 231"))
        // Not a flag: a blood group, or a result in words.
        assertEquals("A positive", LabParsePipeline.valueWithoutFlag("A positive"))
        assertEquals("Negative", LabParsePipeline.valueWithoutFlag("In Range· Negative"))
        val report = AiLabReport(listOf(AiLabEntry("DHEA-S", "DHEA Sulfate", "In Range· 211", "mcg/dL")), reportLanguage = "en")
        assertEquals(211.0, LabParsePipeline.postProcess(report, "").values.single().numericValue)
    }

    @Test
    fun unrankedChemistryTermsAreKnownButNeverSuggested() {
        val loinc = LabCore.loinc!!
        // OmegaCheck (Quest/Function Health), LDL subparticles, eGFR CKD-EPI 2021: no common-test rank in LOINC.
        for (code in listOf("90909-3", "90910-1", "90911-9", "90914-3", "90915-0", "96735-6", "98979-8")) {
            assertTrue(loinc.isKnownLoinc(code), code)
            assertEquals(code, LabMapping.loincCode(code)!!.loinc)
        }
        // Named by their readable long name, in English.
        assertTrue("Omega 6 fatty acids" in loinc.term("90910-1")!!.name, loinc.term("90910-1")!!.name)
        // A printed code passes through; a name never resolves to them.
        assertEquals("98979-8" to false, LabParsePipeline.resolveLoinc("98979-8", "eGFR", "de", "ml/min/1,73m2"))
        val (gfr, _) = LabParsePipeline.resolveLoinc("", "glomeruläre Filtrationsrate", "de", "ml/min/1,73m2")
        assertTrue(gfr != "98979-8")
        // The code picker finds them — after every ranked term.
        val omega = loinc.search("omega fatty acids", language = "en", limit = 200)
        assertTrue(omega.any { it.code == "90910-1" }, omega.map { it.code }.toString())
        assertTrue(omega.dropWhile { it.rank < 2_000_000 }.all { it.rank >= 2_000_000 })
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
