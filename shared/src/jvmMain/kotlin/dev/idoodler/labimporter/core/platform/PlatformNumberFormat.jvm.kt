package dev.idoodler.labimporter.core.platform

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal actual object PlatformNumberFormat {
    actual fun formatG(value: Double, precision: Int): String {
        if (!value.isFinite()) return value.toString()
        if (value == 0.0) return "0"
        val p = precision.coerceAtLeast(1)
        // Exact binary value, rounded half-even to p significant digits — what C does.
        val rounded = BigDecimal(value).round(MathContext(p, RoundingMode.HALF_EVEN))
        val exponent = rounded.precision() - rounded.scale() - 1
        return if (exponent < -4 || exponent >= p) {
            val mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
            val sign = if (exponent < 0) "-" else "+"
            mantissa + "e" + sign + kotlin.math.abs(exponent).toString().padStart(2, '0')
        } else {
            rounded.stripTrailingZeros().toPlainString()
        }
    }

    actual fun formatF0(value: Double): String {
        if (!value.isFinite()) return value.toString()
        return BigDecimal(value).setScale(0, RoundingMode.HALF_EVEN).toPlainString()
    }
}

