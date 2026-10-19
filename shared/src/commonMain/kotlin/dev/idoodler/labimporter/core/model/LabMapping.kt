package dev.idoodler.labimporter.core.model

import dev.idoodler.labimporter.core.LabCore

/** A validated LOINC code plus its English display, as written to exports. */
data class LoincExportCode(val loinc: String, val display: String)

/**
 * LOINC is the canonical identity for every lab value. This is a thin adapter
 * over the bundled catalog — no curated tables — for display and export.
 */
object LabMapping {

    /** The user's nickname, else the localized catalog name, else the raw code. */
    fun displayName(code: String): String {
        val trimmed = code.trim()
        LabCore.preferences.nickname(trimmed)?.let { return it }
        return LabCore.loinc?.term(trimmed)?.name ?: code
    }

    /** The catalog name ignoring any nickname (used where the default must show). */
    fun catalogName(code: String): String {
        val trimmed = code.trim()
        return LabCore.loinc?.term(trimmed)?.name ?: trimmed
    }

    /** The loinc.org details page, or null for anything not shaped like a LOINC number. */
    fun loincUrl(code: String): String? {
        val trimmed = code.trim()
        return if (LOINC_PATTERN.matches(trimmed)) "https://loinc.org/$trimmed/" else null
    }

    /**
     * Validates that [code] is a real LOINC code and returns it with its
     * English display. Null means "not exportable yet" (unknown/unmapped).
     */
    fun loincCode(code: String): LoincExportCode? {
        val term = LabCore.loinc?.term(code.trim()) ?: return null
        return LoincExportCode(term.code, term.englishName)
    }

    fun referenceRange(code: String): ReferenceRange? = LabCore.preferences.referenceRange(code)

    fun rangeStatus(value: Double?, code: String): RangeStatus? {
        if (value == null) return null
        return referenceRange(code)?.status(value)
    }

    private val LOINC_PATTERN = Regex("^\\d+-\\d$")
}
