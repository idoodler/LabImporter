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
     * The value notation of LabImporter's exported documents — exactly C's
     * `%.0f` for whole numbers and `%.4g` otherwise (correctly rounded,
     * half-even), which is what the iOS app has always written. Implemented
     * per platform so every digit matches.
     */
    fun cdaValue(value: Double): String =
        if (value.isFinite() && value == floor(value)) PlatformNumberFormat.formatF0(value)
        else PlatformNumberFormat.formatG(value, 4)

    /** Display rendering: up to four fraction digits, trimmed. */
    fun display(value: Double): String = fixed(value, 4)
}
