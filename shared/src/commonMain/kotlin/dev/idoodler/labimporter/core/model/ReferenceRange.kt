package dev.idoodler.labimporter.core.model

import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.serialization.Serializable

/**
 * A user-defined reference (normal) range for a single LOINC code. LOINC ships
 * no ranges (they depend on the lab, method and population), so these are
 * purely the user's own display preference — never written to an export.
 *
 * Either bound may be null for a one-sided limit; an all-null range is "empty"
 * and treated as no range at all. JSON shape matches the Swift `Codable` type
 * so the preferences blob stays interchangeable.
 */
@Serializable
data class ReferenceRange(
    val low: Double? = null,
    val high: Double? = null,
) {
    val isEmpty: Boolean get() = low == null && high == null

    /** Bounds are inclusive: a value equal to a limit counts as in range. */
    fun status(value: Double): RangeStatus = when {
        high != null && value > high -> RangeStatus.HIGH
        low != null && value < low -> RangeStatus.LOW
        else -> RangeStatus.NORMAL
    }

    /** `3.5–5.3`, `≤ 5.3` or `≥ 3.5`, with [unit] appended when given. */
    fun formatted(unit: String = "", number: (Double) -> String = NumberFormatting::display): String {
        val suffix = if (unit.isEmpty()) "" else " $unit"
        val body = when {
            low != null && high != null -> "${number(low)}–${number(high)}"
            high != null -> "≤ ${number(high)}"
            low != null -> "≥ ${number(low)}"
            else -> return ""
        }
        return body + suffix
    }
}

enum class RangeStatus {
    LOW, NORMAL, HIGH;

    val isOutOfRange: Boolean get() = this != NORMAL
}
