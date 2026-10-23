package dev.idoodler.labimporter.core.loinc

/**
 * A catalog term found for a printed test name and how well it fits. Lower
 * is better, field by field in declaration order (see [compareTo]).
 */
data class LoincMatch(
    val code: String,
    /** Words of the printed name no label of the term has as a whole word (they matched only as a prefix). */
    val missingWords: Int,
    /** 0 = a term for the unit's own kind of quantity, 1 = a related kind (MCHC for g/dl). See [UnitKind]. */
    val unitPenalty: Int,
    /** Words the best label names beyond the printed name — MCHC's "korpuskuläre … Erythrozyten" for "Hämoglobin". */
    val extraWords: Int,
    /** The term's specimen differs from that of the name's best fit regardless of unit (blood vs. serum creatinine). */
    val otherSpecimen: Boolean,
    /** COMMON_TEST_RANK — lower is more commonly ordered. */
    val rank: Int,
) : Comparable<LoincMatch> {

    /** The label matches the printed name word for word, for a term of the unit's own kind. */
    val isExact: Boolean get() = missingWords == 0 && unitPenalty == 0 && extraWords == 0

    override fun compareTo(other: LoincMatch): Int = compareValuesBy(
        this, other, { it.missingWords }, { it.unitPenalty }, { it.extraWords }, { it.otherSpecimen }, { it.rank },
    )
}

/** A term the catalog search returned, with every label of it that matched. */
data class LoincCandidate(
    val code: String,
    val rank: Int,
    val property: String,
    val scale: String,
    val system: String,
    val labels: List<String>,
)

/**
 * The kind of quantity a printed unit measures, as LOINC properties: a value
 * in mg/dl is a mass concentration (`MCnc`), so neither the substance
 * concentration in mmol/l (`SCnc`) nor a urine-strip finding (`PrThr`) can
 * hold it. Unknown units constrain nothing.
 */
enum class UnitKind(private val pattern: Regex, val preferred: Set<String>, val accepted: Set<String> = emptySet()) {
    SUBSTANCE_CONCENTRATION(Regex("^[mµunpf]?mol/[dmc]?l$"), setOf("SCnc"), setOf("EntSCnc")),
    MASS_CONCENTRATION(Regex("^[kmµunp]?g/[dmcµu]?l$|^mg%$"), setOf("MCnc"), setOf("EntMCnc")),
    NUMBER_CONCENTRATION(
        Regex("^(/|1/|k/|m/|x?10\\^?\\*?e?\\d+/|\\[g]/|\\[t]/|gpt/|tpt/|tsd\\.?/|mio\\.?/)[npµumd]?l$|^/mm3$"),
        setOf("NCnc"),
    ),
    CATALYTIC_CONCENTRATION(Regex("^[mµun]?(u|kat)/[md]?l$|^[mµu]?(iu|ui)/[md]?l$"), setOf("CCnc", "ACnc")),
    FRACTION(Regex("^%$|^l/l$"), setOf("MFr", "NFr", "VFr", "SFr", "CFr", "AFr", "MFr.DF", "NFr.DF", "VFr.DF", "CFr.DF")),
    MOLAR_FRACTION(Regex("^mmol/mol$"), setOf("SFr", "SRto")),
    CELL_VOLUME(Regex("^[fµu]l$|^[µu]m3$"), setOf("EntVol", "EntMeanVol")),
    CELL_MASS(Regex("^pg$"), setOf("EntMass")),
    RATIO(Regex("^[mµu]?g/[mµuk]?(g|mol)$"), setOf("MRto", "SRto", "MSRat", "Ratio")),
    FLOW(Regex("^ml/min"), setOf("VRat", "ArVRat")),
    SEDIMENTATION(Regex("^mm/h$"), setOf("Vel", "Sedimentation Rate")),
    ;

    companion object {
        private val NOTE = Regex("\\([^)]*\\)")

        /** The kind of [unit] (as printed, flag already removed), or null when it isn't recognized. */
        fun of(unit: String): UnitKind? {
            // "mg/dL (calc)": the note says how, not what.
            var normalized = unit.replace(NOTE, "").replace(" ", "").replace('μ', 'µ').replace(',', '.').replace("²", "2").replace("³", "3")
            // "G/l" and "T/l" count cells (giga, tera); "g/l" weighs.
            if (normalized.startsWith("G/") || normalized.startsWith("T/")) normalized = "[" + normalized.take(1) + "]" + normalized.drop(1)
            normalized = normalized.lowercase()
            return entries.firstOrNull { it.pattern.containsMatchIn(normalized) }
        }
    }
}

/** Picks the term that fits a printed test name best — pure, so it's testable without the catalog. */
object LoincRanking {

    /**
     * The best fit for [query] among [candidates], or null. A term whose
     * kind of quantity can't hold a value in [unit] is never a fit: an
     * unmapped value the user places by hand beats a wrong suggestion.
     */
    fun best(query: String, candidates: List<LoincCandidate>, unit: String = ""): LoincMatch? {
        if (candidates.isEmpty()) return null
        val words = foldedTokens(query).toSet()
        val fits = candidates.map { it to labelFit(words, it.labels) }
        // The name's best fit regardless of unit tells its usual specimen: "Kreatinin" in µmol/l is
        // still serum creatinine, not the rarer blood creatinine that also comes in moles.
        val specimen = fits.minWith(compareBy({ it.second.first }, { it.second.second }, { it.first.rank })).first.system
        val kind = UnitKind.of(unit)
        return fits.mapNotNull { (candidate, fit) ->
            val unitPenalty = when {
                kind == null -> 0
                candidate.scale != "Qn" -> return@mapNotNull null
                candidate.property in kind.preferred -> 0
                candidate.property in kind.accepted -> 1
                else -> return@mapNotNull null
            }
            LoincMatch(candidate.code, fit.first, unitPenalty, fit.second, candidate.system != specimen, candidate.rank)
        }.minOrNull()
    }

    /** (missing words, extra words) of the term's best-fitting label. */
    private fun labelFit(query: Set<String>, labels: List<String>): Pair<Int, Int> = labels.map { label ->
        val words = foldedTokens(label).toSet()
        val core = foldedTokens(labelCore(label)).toSet() - METHOD_WORDS - LoincDirectory.STOP_WORDS
        query.count { it !in words } to (core - query).size
    }.minWith(compareBy({ it.first }, { it.second }))

    /**
     * What a label names, without specimen, property and method: catalog
     * labels come as "Hämoglobin [Masse/Volumen] in Blut", "Emoglobina in
     * Sangue (MCnc)" or "Hemoglobin (Bld) [Mass/Vol]" — all "Hämoglobin" /
     * "Emoglobina" / "Hemoglobin" here.
     */
    fun labelCore(label: String): String {
        val head = when {
            '[' in label -> label.substringBefore('[')
            // "Component in Specimen (Property)" — not an English "Cholesterol in LDL Elph Qn".
            " in " in label && " (" in label.substringAfterLast(" in ") -> label.substringBeforeLast(" in ")
            else -> label.substringBefore(" (")
        }
        return head.replace(PARENTHESES, " ").trim()
    }

    /** Words lowercased and without accents, so "Hemoglobine" and "Hémoglobine" are one word. */
    fun foldedTokens(text: String): List<String> =
        text.lowercase().split(WORD_BREAK).filter { it.isNotEmpty() }.map { word ->
            buildString(word.length) { word.forEach { c -> append(ACCENTED.indexOf(c).let { if (it < 0) c else PLAIN[it] }) } }
        }

    private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")
    private val PARENTHESES = Regex("\\([^)]*\\)")
    private const val ACCENTED = "àáâãäåāăąçćčďđèéêëēėęěìíîïīįıłñńňòóôõöøōőŕřśšşșťţțùúûüūůűųýÿžźżæœ"
    private const val PLAIN = "aaaaaaaaacccddeeeeeeeeiiiiiiilnnnoooooooorrsssstttuuuuuuuuyyzzzao"

    /** Method abbreviations of the English labels ("WBC Auto", "LDL Calc"), which say how, not what. */
    private val METHOD_WORDS = setOf("auto", "calc", "manual", "qn", "cnt", "ia", "lm", "strip", "est")
}
