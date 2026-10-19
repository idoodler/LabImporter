package dev.idoodler.labimporter.core.platform

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Locale-independent number rendering for exported documents and as a UI
 * fallback. Common Kotlin has no `String.format`, so these do the rounding by
 * hand; they always use `.` as the decimal separator (CDA/FHIR require it).
 */
object NumberFormatting {

    /** `value` rounded to [decimals] places, trailing zeros trimmed (`6.50` → `6.5`). */
    fun fixed(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val places = decimals.coerceIn(0, 12)
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
     * Mirrors the Swift export's `%.0f` / `%.4g` choice: integers print without
     * a fraction, everything else with four significant digits.
     */
    fun significant(value: Double, digits: Int = 4): String {
        if (value == floor(value) && abs(value) < 1e15) return fixed(value, 0)
        if (value == 0.0) return "0"
        val magnitude = floor(log10(abs(value))).toInt()
        val decimals = (digits - 1 - magnitude).coerceAtLeast(0)
        return fixed(value, decimals)
    }

    /** Display rendering: up to four fraction digits, trimmed. */
    fun display(value: Double): String = fixed(value, 4)

    /** Parses a printed lab number, accepting `,` as the decimal separator. */
    fun parse(raw: String): Double? {
        val normalized = raw.trim().replace(',', '.')
        if (normalized.isEmpty() || normalized == "-") return null
        return normalized.toDoubleOrNull()
    }
}
