package dev.idoodler.labimporter.core.platform

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Locale-independent number handling shared by both apps. Exported documents
 * (CDA, FHIR) always use `.` as the decimal separator; the UIs format numbers
 * for display themselves.
 */
object NumberFormatting {

    private val STRICT_NUMBER = Regex("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$")

    /**
     * Parses a number exactly as printed on a report, accepting `,` as the
     * decimal separator. Anything that isn't a plain finite number — `-`,
     * `< 5`, `6,5 H`, `positiv`, `NaN` — is **not** a number: a qualified or
     * flagged result must never silently become a different value.
     */
    fun parse(raw: String): Double? {
        val normalized = raw.trim().replace(',', '.')
        if (!STRICT_NUMBER.matches(normalized)) return null
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** `value` rounded to [decimals] places, trailing zeros trimmed (`6.50` → `6.5`). */
    fun fixed(value: Double, decimals: Int): String {
        if (!value.isFinite()) return value.toString()
        val places = decimals.coerceIn(0, 12)
        if (abs(value) >= 1e15) return PlatformNumberFormat.formatF0(value)
        val scale = 10.0.pow(places)
        val scaled = (abs(value) * scale).roundToLong()
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        if (places == 0) return sign + scaled.toString()
        val digits = scaled.toString().padStart(places + 1, '0')
        val whole = digits.dropLast(places)
        val fraction = digits.takeLast(places).trimEnd('0')
        return if (fraction.isEmpty()) sign + whole else "$sign$whole.$fraction"
    }

    /**
     * A reading at four significant digits — exactly C's `%.0f` for whole
     * numbers and `%.4g` otherwise (correctly rounded, half-even), the notation
     * older documents were written in. Implemented per platform so every digit
     * matches. It rounds, so it's for comparing and showing readings; what gets
     * saved is [exactValue].
     */
    fun cdaValue(value: Double): String =
        if (value.isFinite() && value == floor(value)) PlatformNumberFormat.formatF0(value)
        else PlatformNumberFormat.formatG(value, 4)

    /**
     * The value notation of exported documents: [cdaValue] when that reads back
     * as [value], otherwise as few further digits as it takes. 12345.6 stays
     * 12345.6 rather than `1.235e+04`, which is 12350 to whoever reads it.
     */
    fun exactValue(value: Double): String {
        if (!value.isFinite() || value == floor(value)) return cdaValue(value)
        for (precision in 4 until MAX_DOUBLE_DIGITS) {
            val text = PlatformNumberFormat.formatG(value, precision)
            if (text.toDouble() == value) return text
        }
        return PlatformNumberFormat.formatG(value, MAX_DOUBLE_DIGITS)
    }

    /** Significant decimal digits that always identify a `Double`. */
    private const val MAX_DOUBLE_DIGITS = 17

    /** Display rendering: up to four fraction digits, trimmed. */
    fun display(value: Double): String = fixed(value, 4)
}
