package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.loinc.LoincDirectory
import dev.idoodler.labimporter.core.loinc.LoincPlainName
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.platform.openReadOnlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlainNameTest {

    private data class Term(val code: String, val label: String, val system: String, val property: String) {
        val specimen: String get() = if (system in USUAL_SYSTEMS) "blood, serum or plasma" else system
    }

    private val database: String = System.getProperty("loinc.db")

    /** Every label of the catalog by language, read past the code under test. */
    private val catalog: Map<String, List<Term>> by lazy {
        val connection = openReadOnlyDatabase(database)
        try {
            val statement = connection.prepare(
                "SELECT l.lang, l.code, l.name, t.system, t.property FROM label l JOIN term t ON t.code = l.code " +
                    "WHERE l.lang NOT IN ('long', 'syn')",
            )
            val rows = mutableListOf<Pair<String, Term>>()
            while (statement.step()) {
                rows += statement.getText(0) to Term(statement.getText(1), statement.getText(2), statement.getText(3), statement.getText(4))
            }
            statement.close()
            rows.groupBy({ it.first }, { it.second })
        } finally {
            connection.close()
        }
    }

    private fun plain(language: String, term: Term) = LoincPlainName.of(term.label, language, term.system, term.property)

    private fun name(language: String, code: String) = LoincDirectory(database, language).use { it.plainName(code) }

    @Test
    fun theExamples() {
        assertEquals("Glucose", name("en", "2345-7"))
        assertEquals("ALT", name("en", "1742-6"))
        assertEquals("HbA1c", name("en", "4548-4"))
        assertEquals("Glucose", name("en", "14749-6"), "moles share the name: the unit tells them apart")
        assertEquals("Protein (urine)", name("en", "2888-6"))
        assertEquals("Protein (24H urine)", name("en", "2889-4"))
        assertEquals("Cholesterol in LDL Calc", name("en", "13457-7"))
        assertEquals("Alanin-Aminotransferase", name("de", "1742-6"))
        assertEquals("Protein in Urin", name("de", "2888-6"))
        assertEquals("Bilirubin gesamt", name("de", "1975-2"))
        assertEquals("Cholesterol in LDL mittels Berechnung", name("de", "13457-7"))
        assertEquals("Protéines Urine", name("fr", "2888-6"))
        assertEquals("Protéines 24H Urine", name("fr", "2889-4"))
        assertEquals("Glucose", name("fr", "2345-7"))
        assertEquals("Cholestérol LDL (Résultat calculé)", name("fr", "13457-7"))
        assertEquals("Proteínas (Orina)", name("es", "2888-6"))
        assertEquals("Proteínas (Orina, Tira reactiva)", name("es", "5804-0"))
        assertEquals("Leucocitos (Contaje automático)", name("es", "6690-2"))
        assertEquals("Eiwit in urine", name("nl", "2888-6"))
        assertEquals("Creat", name("pt-BR", "2160-0"))
        assertEquals("Prot Ur", name("pt-BR", "2888-6"))
        assertEquals("Білок (Cеча)", name("uk", "2888-6"))
        assertEquals("蛋白质 (尿液)", name("zh-Hans", "2888-6"))
    }

    @Test
    fun aPropertyThatNamesTheTestStays() {
        assertEquals("Erythrocyte (Sedimentation Rate)", name("en", "30341-2"))
        assertEquals("Erythrocyte (DistWidth)", name("en", "30385-9"))
        assertEquals("Platelet Auto (Entitic mean vol)", name("en", "32623-1"))
        assertEquals("MCV Auto (RBC)", name("en", "787-2"))
        // No specimen printed, and two tests: the drug level and the antibiotic susceptibility.
        assertEquals("Vancomycin", name("en", "20578-1"))
        assertNotEquals("Vancomycin", name("en", "523-1"))
    }

    @Test
    fun aMissingLabelFallsBackToTheEnglishOne() {
        // 2024-8 has no German label.
        assertEquals("Carbon dioxide dissolved", name("de", "2024-8"))
        // Japanese is a catalog language with no labels yet.
        assertEquals("Protein (urine)", name("ja", "2888-6"))
        assertEquals(null, name("en", "KREA"))
    }

    @Test
    fun nicknameWinsAndCatalogNameStaysTheFullLabel() {
        startCatalog("de-DE", LabDisplayPreferences(nicknames = mapOf("2160-0" to "Krea")))
        assertEquals("Krea", LabMapping.displayName("2160-0"))
        assertEquals("Creatinin", LabMapping.plainName("2160-0"))
        assertEquals("Creatinin [Masse/Volumen] in Serum oder Plasma", LabMapping.catalogName("2160-0"))
        assertEquals("Glucose", LabMapping.displayName("2345-7"))
        assertEquals("Glucose [Masse/Volumen] in Serum oder Plasma", LabMapping.catalogName("2345-7"))
    }

    @Test
    fun exportsKeepTheLoincDisplay() {
        startCatalog("de-DE")
        assertEquals("Glucose [Mass/Vol]", LabMapping.loincCode("2345-7")!!.display)
    }

    @Test
    fun everyNameInEveryLanguageIsClean() {
        val problems = catalog.flatMap { (language, terms) ->
            terms.mapNotNull { term ->
                val name = plain(language, term)
                val stray = when {
                    name.isBlank() -> "blank"
                    '[' in name || ']' in name -> "bracket"
                    name.first() in STRAY_AT_START -> "start"
                    name.last() in STRAY_AT_END -> "end"
                    name.last() == '.' && name.substringAfterLast(' ').length > 5 -> "full stop"
                    name.last() == ')' && name.count { it == ')' } > name.count { it == '(' } -> "unmatched"
                    else -> null
                }
                stray?.let { "$language ${term.code} $it: ${term.label} -> $name" }
            }
        }
        assertTrue(problems.isEmpty(), problems.take(30).joinToString("\n"))
    }

    /** Unusual systems print a specimen; a word that marks one in a language must survive into the name. */
    @Test
    fun everyOtherSpecimenStaysInTheName() {
        val problems = mutableListOf<String>()
        var checked = 0
        for ((language, terms) in catalog) {
            val usualWords = terms.filter { it.system in USUAL_SYSTEMS }.flatMap { words(it.label).toSet() }.groupingBy { it }.eachCount()
            val usualCount = terms.count { it.system in USUAL_SYSTEMS }
            for ((system, ofSystem) in terms.filter { it.system !in USUAL_SYSTEMS }.groupBy { it.system }) {
                if (ofSystem.size < MIN_TERMS_FOR_A_SPECIMEN_WORD) continue
                // What nearly every label of this system prints and the labels of blood, serum and plasma don't: its specimen.
                val word = ofSystem.flatMap { words(it.label).toSet() }.groupingBy { it }.eachCount().entries
                    .filter { (candidate, count) ->
                        count >= ofSystem.size * 0.8 && (usualWords[candidate] ?: 0) < usualCount * 0.005
                    }
                    .maxByOrNull { it.value }?.key ?: continue
                val spelled = if (language == "en") ENGLISH_SPELLING[word] ?: word else word
                for (term in ofSystem) {
                    if (word !in words(term.label)) continue
                    checked++
                    val name = plain(language, term)
                    if (word !in words(name) && spelled !in words(name)) problems += "$language ${term.code} lost '$word': ${term.label} -> $name"
                }
            }
        }
        assertTrue(problems.isEmpty(), "${problems.size} lost their specimen:\n" + problems.take(30).joinToString("\n"))
        assertTrue(checked > 60_000, "only $checked terms checked")
    }

    @Test
    fun differentSpecimensNeverShareAName() {
        val merged = catalog.flatMap { (language, terms) ->
            terms.groupBy { plain(language, it) }
                // XXX is LOINC's "unspecified": there is no specimen to keep apart.
                .filter { (_, same) -> same.map { it.specimen }.filter { it != "XXX" }.distinct().size > 1 }
                .map { (name, same) -> "$language '$name': " + same.joinTo(StringBuilder(), limit = 4) { "${it.code} ${it.system}" } }
        }
        assertTrue(merged.isEmpty(), "${merged.size} names:\n" + merged.take(30).joinToString("\n"))
    }

    /** A name may leave out how an amount is reported, never which thing was measured: nothing else on the row would tell. */
    @Test
    fun differentMeasurementsNeverShareAName() {
        val merged = catalog.flatMap { (language, terms) ->
            terms.groupBy { plain(language, it) }
                .filter { (_, same) -> same.any { it.property in OWN_MEASUREMENTS } && same.map { it.property }.distinct().size > 1 }
                .map { (name, same) -> "$language '$name': " + same.joinTo(StringBuilder(), limit = 4) { "${it.code} ${it.property}" } }
        }
        assertTrue(merged.isEmpty(), "${merged.size} names:\n" + merged.take(30).joinToString("\n"))
    }

    @Test
    fun aPropertyTheNameNeedsIsNotLeftToTheUnit() {
        // Urine colour, appearance, specific gravity and osmolality are all an "Observación".
        assertEquals("Observación (Orina, Color)", name("es", "5778-6"))
        assertEquals("Observación (Orina, Aspecto)", name("es", "5767-9"))
        assertEquals("Observación (Orina, Peso específico)", name("es", "2965-2"))
        assertEquals("Observación (Orina, Osmolalidad)", name("es", "2695-5"))
        assertEquals("Observación (Osmolalidad)", name("es", "2692-2"))
        assertEquals("Waarneming in urine (aspect)", name("nl", "5767-9"))
        assertEquals("Waarneming in urine (osmolaliteit)", name("nl", "2695-5"))
        // A morphology finding isn't the count.
        assertEquals("Erythrozyten (Morphologie)", name("de", "6742-1"))
        assertEquals("Erythrozyten", name("de", "26453-1"))
        assertEquals("Leucocitos (Morfología)", name("es", "11156-7"))
        assertEquals("RBC Auto (Morph)", name("pt-BR", "11273-0"))
        assertEquals("RBC Auto", name("pt-BR", "789-8"))
        // pCO2 isn't total CO2.
        assertEquals("CO2 (Partial pressure)", name("en", "11557-6"))
        assertEquals("CO2", name("en", "2028-9"))
        assertEquals("CO2 (arterial blood) (Partial pressure)", name("en", "2019-8"))
        // A label that already says it doesn't say it twice.
        assertEquals("Osmolality (urine)", name("en", "2695-5"))
        // What the unit or the result says stays out, for the common tests above all.
        assertEquals("Coagulation PT (PPP)", name("en", "5902-2"))
        assertEquals("Hämoglobin", name("de", "718-7"))
    }

    /**
     * The price of plain names, for the record: mass and moles, serum and whole blood
     * share a name by design. Prints the count per language; the worst must stay that kind
     * (one analyte, measured several ways), and the common tests must each stay readable.
     */
    @Test
    fun collapsedTermsAreTheSameAnalyte() {
        for ((language, terms) in catalog.toSortedMap()) {
            val groups = terms.groupBy { plain(language, it) to it.specimen }.filter { it.value.size > 1 }
            val worst = groups.entries.sortedByDescending { it.value.size }.take(3).joinToString { "${it.key.first} ×${it.value.size}" }
            println("plain names $language: ${terms.size} terms, ${groups.values.sumOf { it.size }} in ${groups.size} shared names; worst: $worst")
        }
        val english = catalog.getValue("en").groupBy { plain("en", it) }
        assertEquals(setOf("2160-0", "38483-4", "59826-8", "14682-9", "35203-9", "77140-2"), english.getValue("Creatinine").map { it.code }.toSet())
    }

    private fun words(text: String) = text.split(WORD_BREAK).filter { it.isNotEmpty() }

    private companion object {
        val USUAL_SYSTEMS = setOf("Bld", "Ser", "Plas", "Ser/Plas", "Ser/Plas/Bld")
        /** What these measure isn't a way of reporting an amount, so a result wouldn't tell them from the amount's row. */
        val OWN_MEASUREMENTS = setOf("Color", "Aper", "SpGrav", "Osmol", "Temp", "Morph", "PPres", "Vel", "Visc")
        const val STRAY_AT_START = ",;:/.-)]"
        const val STRAY_AT_END = ",;:/-([^"
        const val MIN_TERMS_FOR_A_SPECIMEN_WORD = 10
        val WORD_BREAK = Regex("[\\s()\\[\\],;/+^.-]+")
        val ENGLISH_SPELLING = mapOf(
            "U" to "urine", "Stl" to "stool", "Sal" to "saliva", "Sem" to "semen", "Tiss" to "tissue", "Sput" to "sputum", "Spec" to "specimen", "Bld" to "blood",
            "Syn" to "synovial", "Amn" to "amniotic", "Pleur" to "pleural", "Periton" to "peritoneal", "fld" to "fluid", "DBS" to "dried",
            "BldA" to "arterial", "BldV" to "venous", "BldC" to "capillary", "BldCo" to "cord", "BldMV" to "mixed", "BldCoA" to "cord", "BldCoV" to "cord",
        )
    }
}
