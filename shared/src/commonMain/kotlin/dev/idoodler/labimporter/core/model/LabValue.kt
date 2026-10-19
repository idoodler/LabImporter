package dev.idoodler.labimporter.core.model

import kotlin.uuid.Uuid

/**
 * An editable lab value, used while reviewing an import before it is saved.
 * Immutable data class — the UI edits by copying.
 */
data class LabValue(
    val id: String = Uuid.random().toString(),
    val code: String,
    val name: String,
    val displayValue: String,
    val numericValue: Double?,
    val unit: String,
    val isSelected: Boolean = true,
    /**
     * True when [code] came from a fuzzy catalog search on the AI's test name
     * rather than a confident mapping — the user should confirm it.
     */
    val isSuggestedCode: Boolean = false,
) {
    val resolvedName: String
        get() = LabMapping.displayName(code).let { if (it == code) name else it }

    /** Whether this row will be written to an export (selected, numeric, LOINC-mapped). */
    val isExportable: Boolean
        get() = isSelected && numericValue != null && LabMapping.loincCode(code) != null

    /** Whether this row collides with another exportable row on the same LOINC. */
    fun isDuplicate(duplicateLoincCodes: Set<String>): Boolean {
        if (!isSelected || numericValue == null) return false
        val loinc = LabMapping.loincCode(code)?.loinc ?: return false
        return loinc in duplicateLoincCodes
    }

    /** Selected + numeric beats numeric beats neither when collapsing duplicates. */
    internal val dedupRank: Int
        get() = when {
            isSelected && numericValue != null -> 2
            numericValue != null -> 1
            else -> 0
        }
}

/** LOINC codes carried by more than one exportable entry — must be resolved before saving. */
fun List<LabValue>.duplicateLoincCodes(): Set<String> {
    val counts = mutableMapOf<String, Int>()
    for (value in this) {
        if (!value.isSelected || value.numericValue == null) continue
        val loinc = LabMapping.loincCode(value.code)?.loinc ?: continue
        counts[loinc] = (counts[loinc] ?: 0) + 1
    }
    return counts.filterValues { it > 1 }.keys
}

/**
 * Collapses entries sharing a LOINC code (last-resort guarantee at the save
 * boundary). Unmapped rows pass through untouched; within a group the most
 * useful row wins and keeps the first-seen slot.
 */
fun List<LabValue>.deduplicatedByLoinc(): List<LabValue> {
    val slotForLoinc = mutableMapOf<String, Int>()
    val result = mutableListOf<LabValue>()
    for (value in this) {
        val loinc = LabMapping.loincCode(value.code)?.loinc
        if (loinc == null) {
            result += value
            continue
        }
        val slot = slotForLoinc[loinc]
        if (slot != null) {
            if (value.dedupRank > result[slot].dedupRank) result[slot] = value
        } else {
            slotForLoinc[loinc] = result.size
            result += value
        }
    }
    return result
}
