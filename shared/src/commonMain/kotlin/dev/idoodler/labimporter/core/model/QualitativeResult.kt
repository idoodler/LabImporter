package dev.idoodler.labimporter.core.model

/**
 * A printed result that isn't a plain number but still is a result: words
 * ("Negativ", "not detected", "Speckled"), a titer ("1:160"), a threshold
 * ("<0.5", "> 90") or a urine-strip grade ("2+"). They are saved as text —
 * CDA `ST`, FHIR `valueString` — because neither format gives a text a unit
 * slot, the unit travels in the text and [split] takes it back out.
 */
object QualitativeResult {

    /**
     * [text] as a qualitative result, trimmed, or null when it's no result
     * (empty, `-`) or a number with stray text ("6.5 %", "0,8 H") — a misread
     * the user fixes in review, never something to save as text.
     */
    fun of(text: String): String? {
        val trimmed = text.trim()
        if (trimmed in NO_RESULT) return null
        val words = trimmed.none { it.isDigit() } && trimmed.any { it.isLetter() }
        return trimmed.takeIf { words || WITH_DIGITS.matches(it) }
    }

    /** The text to store for [result] in [unit]: "<0.5 mg/dL", or the result alone. */
    fun stored(result: String, unit: String): String =
        if (unit.isBlank()) result.trim() else result.trim() + " " + unit.trim()

    /**
     * A stored text back as (result, unit): "<0.5 mg/dL" → ("<0.5", "mg/dL").
     * Words keep everything as the result — "not detected" has no unit to
     * tell apart — which saves the same text again.
     */
    fun split(stored: String): Pair<String, String> {
        val trimmed = stored.trim()
        val match = WITH_UNIT.matchEntire(trimmed) ?: return trimmed to ""
        return match.groupValues[1] to match.groupValues[2]
    }

    private val NO_RESULT = setOf("", "-", "–", "—")
    private const val NUMBER = "\\d+(?:[.,]\\d+)?"
    private const val SHAPES = "(?:<=|>=|<|>|≤|≥)\\s*$NUMBER|\\d+\\s*:\\s*\\d+|[1-4]?\\+{1,4}"
    private val WITH_DIGITS = Regex("^(?:$SHAPES)$")
    private val WITH_UNIT = Regex("^($SHAPES)\\s+(\\S.*)$")
}
