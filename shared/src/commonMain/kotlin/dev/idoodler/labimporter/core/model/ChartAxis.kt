package dev.idoodler.labimporter.core.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/** Axis scales for charts drawn by hand (the PDF), like Swift Charts' automatic ticks. */
object ChartAxis {
    /**
     * Evenly spaced, rounded tick values (steps of 1, 2, 2.5 or 5 × 10ⁿ) that
     * cover [min]…[max] with about [count] intervals. The first and last tick
     * are the axis bounds.
     */
    fun ticks(min: Double, max: Double, count: Int = 4): List<Double> {
        require(min.isFinite() && max.isFinite()) { "Bounds must be finite" }
        var low = minOf(min, max)
        var high = maxOf(min, max)
        if (high - low < 1e-12) {
            // A flat series still gets an axis around its value.
            val pad = if (low == 0.0) 1.0 else abs(low) * 0.1
            low -= pad
            high += pad
        }
        val intervals = count.coerceAtLeast(1)
        // The rounded step whose axis has closest to [count] intervals; on a tie, the tighter axis.
        val magnitude = 10.0.pow(floor(log10((high - low) / intervals)))
        val step = listOf(0.1, 1.0, 10.0).flatMap { m -> STEPS.map { it * m * magnitude } }
            .minWith(compareBy<Double> { abs(span(low, high, it).second - intervals) }.thenBy { span(low, high, it).first })
        val (first, last) = bounds(low, high, step)
        val decimals = (-floor(log10(step))).toInt().coerceAtLeast(0) + 1
        val scale = 10.0.pow(decimals)
        return (first..last).map { round(it * step * scale) / scale }
    }

    private val STEPS = listOf(1.0, 2.0, 2.5, 5.0)

    /** The tick indices (as multiples of [step]) enclosing [low]…[high]. */
    private fun bounds(low: Double, high: Double, step: Double): Pair<Long, Long> =
        floor(low / step + 1e-9).toLong() to ceil(high / step - 1e-9).toLong()

    /** The axis range and interval count for [step]. */
    private fun span(low: Double, high: Double, step: Double): Pair<Double, Long> {
        val (first, last) = bounds(low, high, step)
        return (last - first) * step to (last - first)
    }
}
