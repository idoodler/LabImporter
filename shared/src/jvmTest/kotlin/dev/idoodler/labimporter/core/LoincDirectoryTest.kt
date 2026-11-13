package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.loinc.LoincMatch
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the catalog hands the apps, field by field: the loinc.org details page, a term's
 * names and ranking, and how one match is ranked above another. Each field is read on its
 * own (a data class's `equals` reads the fields, not what the apps read).
 */
class LoincDirectoryTest {

    @BeforeTest
    fun start() = startCatalog()

    private val loinc get() = LabCore.loinc!!

    @Test
    fun theCatalogDescribesItself() {
        assertEquals("2.82", loinc.version)
        assertEquals(25192, loinc.count)
        assertTrue(loinc.license.startsWith("Copyright Notice and License"), loinc.license.take(40))
        assertEquals(listOf("en", "de", "es", "fr", "it", "ja", "nl", "pl", "pt-BR", "ru", "tr", "uk", "zh-Hans"), loinc.languages)
        assertEquals("de", loinc.language)
        assertTrue(loinc.isAvailable)
    }

    @Test
    fun aReportLanguageResolvesToACatalogLanguage() {
        assertEquals("fr", loinc.resolvedLanguage("fr-CA"))
        assertEquals("pt-BR", loinc.resolvedLanguage("pt_br"))
        assertEquals("zh-Hans", loinc.resolvedLanguage("zh-TW"))
        assertEquals("en", loinc.resolvedLanguage("EN"))
        // No tag, or one the catalog has no labels for: the app's language.
        assertEquals("de", loinc.resolvedLanguage(""))
        assertEquals("de", loinc.resolvedLanguage("xx"))
    }

    @Test
    fun theDetailsPageHasEveryField() {
        val mcv = loinc.detail(" 787-2 ")!!
        assertEquals("787-2", mcv.code)
        assertEquals("Mittleres korpuskuläres Zellvolumen eines Erythrozyten (MCV) [Entitisches mittleres Volumen] mittels automatisierter Zählung", mcv.name)
        assertEquals("Beobachtung in Erythrozyten (Entitisches mittleres Volumen, Automatisierte Zählung)", mcv.description)
        assertEquals("Observation", mcv.component)
        assertEquals("EntMeanVol", mcv.property)
        assertEquals("Pt", mcv.timing)
        assertEquals("RBC", mcv.system)
        assertEquals("Qn", mcv.scale)
        assertEquals("Automated count", mcv.method)
        assertEquals("HEM/BC", mcv.loincClass)
        assertEquals("ACTIVE", mcv.status)
        assertEquals("MCV [Entitic mean volume] in Red Blood Cells by Automated count", mcv.longName)
        assertEquals("MCV RBC Auto", mcv.shortName)
        assertEquals("fL", mcv.ucum)
        // A term without a method has an empty one, not another field's text.
        assertEquals("", loinc.detail("2160-0")!!.method)
        assertNull(loinc.detail("KREA"))
    }

    @Test
    fun aTermHasItsNamesUnitAndRank() {
        val creatinine = loinc.term("2160-0")!!
        assertEquals("2160-0", creatinine.code)
        assertEquals("Creatinin [Masse/Volumen] in Serum oder Plasma", creatinine.name)
        assertEquals("Creatinine [Mass/Vol]", creatinine.englishName)
        assertEquals("Creatinin in Serum oder Plasma (Massenkonzentration)", creatinine.description)
        assertEquals("mg/dL", creatinine.ucum)
        assertEquals(4, creatinine.rank)
        assertEquals(14, loinc.term("787-2")!!.rank)
        assertNull(loinc.term("KREA"))
    }

    @Test
    fun aMatchSaysHowItFits() {
        val urate = loinc.bestMatch("Harnsäure", "de", unit = "mg/dl")!!
        assertEquals("3084-1", urate.code)
        assertEquals(0, urate.missingWords)
        assertEquals(0, urate.unitPenalty)
        assertFalse(urate.bySynonym)
        assertEquals(0, urate.extraWords)
        assertFalse(urate.unusualSpecimen)
        assertFalse(urate.otherSpecimen)
        assertEquals(181, urate.rank)
        assertTrue(urate.isExact)
        // "BUN" is only a related name of urea nitrogen: a match, but never an exact one.
        val bun = loinc.bestMatch("BUN", "de", unit = "mg/dL")!!
        assertEquals("3094-0", bun.code)
        assertTrue(bun.bySynonym)
        assertFalse(bun.isExact)
        assertEquals(5, loinc.bestMatch("GFR", "de", unit = "mL/min/1.73m2")!!.extraWords)
    }

    @Test
    fun matchesAreRankedFieldByFieldInOrder() {
        val best = LoincMatch("1", missingWords = 0, unitPenalty = 0, bySynonym = false, extraWords = 0, unusualSpecimen = false, otherSpecimen = false, rank = 10)
        val worse = listOf(
            best.copy(missingWords = 1), best.copy(unitPenalty = 1), best.copy(bySynonym = true), best.copy(extraWords = 1),
            best.copy(unusualSpecimen = true), best.copy(otherSpecimen = true), best.copy(rank = 11),
        )
        worse.forEach { assertTrue(it > best, "$it") }
        // An earlier field outweighs every later one.
        for (index in worse.indices.drop(1)) {
            val earlier = worse[index - 1]
            val later = worse.drop(index).fold(best) { acc, it -> merge(acc, it) }
            assertTrue(earlier > later.copy(rank = 0), "$earlier vs $later")
        }
        assertEquals(0, best.compareTo(best.copy(code = "2")))
    }

    @Test
    fun onlyANameWordForWordForTheUnitIsExact() {
        val exact = LoincMatch("1", 0, 0, false, 0, unusualSpecimen = true, otherSpecimen = true, rank = 999)
        assertTrue(exact.isExact)
        assertFalse(exact.copy(missingWords = 1).isExact)
        assertFalse(exact.copy(unitPenalty = 1).isExact)
        assertFalse(exact.copy(bySynonym = true).isExact)
        assertFalse(exact.copy(extraWords = 1).isExact)
    }

    /** [a] made worse wherever [b] is worse. */
    private fun merge(a: LoincMatch, b: LoincMatch) = LoincMatch(
        a.code, maxOf(a.missingWords, b.missingWords), maxOf(a.unitPenalty, b.unitPenalty), a.bySynonym || b.bySynonym,
        maxOf(a.extraWords, b.extraWords), a.unusualSpecimen || b.unusualSpecimen, a.otherSpecimen || b.otherSpecimen, maxOf(a.rank, b.rank),
    )
}
