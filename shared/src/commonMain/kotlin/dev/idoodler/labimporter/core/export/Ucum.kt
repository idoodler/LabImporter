package dev.idoodler.labimporter.core.export

import dev.idoodler.labimporter.core.LabCore

/** Maps common (largely German) printed lab units to UCUM codes. */
object Ucum {
    /** The LOINC property of a count per volume ("WBC [#/Vol]"): its units are particles, never grams. */
    const val NUMBER_CONCENTRATION = "NCnc"

    /**
     * The UCUM code for a [raw] printed unit, without a printed annotation ("mg/dL (calc)").
     *
     * "G/L" is giga-particles per litre (10⁹/L, how French and other European labs print
     * leukocytes and platelets; German ones print "Gpt/l") and "g/L" is grams per litre: the
     * term [loincCode] measures decides between them where it's known (a count per volume
     * or not), and the letter case where it isn't.
     */
    fun fromPrinted(raw: String, loincCode: String? = null): String {
        val unit = withoutAnnotation(raw).trim()
        return when (unit.lowercase()) {
            "mg/dl" -> "mg/dL"
            "mmol/l" -> "mmol/L"
            "u/", "u/l" -> "U/L"
            "mu/l", "miu/l" -> "m[IU]/L"
            "iu/l" -> "[IU]/L"
            "mmol/mol" -> "mmol/mol"
            "%" -> "%"
            "ml/min/1,73m2kof", "ml/min/1.73m2kof", "ml/min/1.73 m2" -> "mL/min/{1.73_m2}"
            "nmol/l" -> "nmol/L"
            "pmol/l" -> "pmol/L"
            "g/dl" -> "g/dL"
            "g/l" -> if (isCount(unit, loincCode)) GIGA_PER_LITRE else "g/L"
            "gpt/l", "giga/l" -> GIGA_PER_LITRE
            "t/l", "tpt/l", "tera/l" -> TERA_PER_LITRE
            "pg/ml" -> "pg/mL"
            "ng/ml" -> "ng/mL"
            "ng/dl" -> "ng/dL"
            "µg/dl", "ug/dl", "μg/dl" -> "ug/dL"
            "µg/l", "ug/l", "μg/l" -> "ug/L"
            "fl" -> "fL"
            "pg" -> "pg"
            "1/µl", "1/ul", "/µl", "/ul" -> "/uL"
            "1/nl", "/nl" -> "/nL"
            else -> unit
        }
    }

    /**
     * [unit] without what a report prints beside it in parentheses — "mg/dL (calc)", "U/l (37 °C)",
     * "mL/min/1.73m2 (CKD-EPI)" — which no unit system knows. A part of the unit in parentheses
     * stays ("mL/min/(1.73 m2)"), and so does a power written in them ("x10(9)/L", as Cerner and
     * MyChart print it) and a unit that is all in parentheses ("(mmol/l)").
     */
    fun withoutAnnotation(unit: String): String {
        val trimmed = unit.trim()
        return ANNOTATION.replace(trimmed) { match ->
            val before = trimmed.substring(0, match.range.first).trimEnd()
            val partOfUnit = before.isEmpty() || before.last() in UNIT_OPERATORS || POWER.matches(match.value) && before.last().isDigit()
            if (partOfUnit) match.value else ""
        }.trim()
    }

    /** Whether "g/L" is a count: by the term's LOINC property where it's known, else by a capital G. */
    private fun isCount(unit: String, loincCode: String?): Boolean {
        val property = loincCode?.let { LabCore.loinc?.detail(it)?.property }?.takeIf { it.isNotEmpty() }
        return if (property != null) property == NUMBER_CONCENTRATION else unit.first() == 'G'
    }

    const val GIGA_PER_LITRE = "10*9/L"
    const val TERA_PER_LITRE = "10*12/L"

    /** A parenthesised part with nothing parenthesised inside. */
    private val ANNOTATION = Regex("\\s*\\([^()]*\\)")

    /** A power in parentheses, straight behind its base: the (9) of "10(9)/L". */
    private val POWER = Regex("\\([-−]?\\d+\\)")

    /** What joins a unit's parts: a parenthesised part behind one of them belongs to the unit. */
    private const val UNIT_OPERATORS = "/*^.·"
}
