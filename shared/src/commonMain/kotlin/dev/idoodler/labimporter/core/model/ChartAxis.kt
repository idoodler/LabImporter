package dev.idoodler.labimporter.core.model

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * Where a metric's readings go on a chart: the same on iOS and Android, in the
 * trend chart and the card sparklines, so both apps tell one story about one
 * history. See [ChartAxis.scale] for the rule and why.
 */
class ChartScale internal constructor(
    /** The y axis' bounds: its first and last tick. */
    val yLow: Double,
    val yHigh: Double,
    val yTicks: List<Double>,
    /** The x axis' bounds, the first and the last reading (a day either side of a lone date). */
    val xStart: LocalDate,
    val xEnd: LocalDate,
    private val dates: List<LocalDate>,
) {
    /** What the x axis labels: its two ends, or the one date when every reading has it (the ends are then days nobody measured). */
    val xLabels: List<LocalDate>
        get() = dates.distinct().let { if (it.size > 1) listOf(xStart, xEnd) else it }

    /** Where [date] lies between the x axis' bounds: 0 at the start, 1 at the end. */
    fun xFraction(date: LocalDate): Double =
        (date.toEpochDays() - xStart.toEpochDays()).toDouble() / (xEnd.toEpochDays() - xStart.toEpochDays())

    /** Where [value] lies between the y axis' bounds: 0 at the bottom, 1 at the top. */
    fun yFraction(value: Double): Double = (value - yLow) / (yHigh - yLow)

    /** The reading nearest to [xFraction] of the x axis, by date: what a finger on the chart selects. */
    fun nearestIndex(xFraction: Double): Int =
        dates.indices.minByOrNull { abs(xFraction(dates[it]) - xFraction) } ?: 0
}

/** Axis scales for charts drawn by hand (the PDF, Android's charts) and for Swift Charts' domains. */
object ChartAxis {
    /**
     * The scale for [points] (oldest first), with room for the user's [range]. Null without readings.
     *
     * **y starts at zero** (and goes below it only for values that do). Lab values are amounts:
     * 35 is 9 % above 32, and an axis fitted to 32…35 draws that as the whole chart, so assay noise
     * looks like a swing and a stable value like an emergency. From zero, heights compare the way
     * the numbers do, and that is the only baseline under which a filled area means anything. The
     * cost: analytes held in a narrow band (sodium, pH) draw nearly flat. That understates a
     * change, which the printed value, the arrow and the reference range still show; a fitted axis
     * would invent one, and nothing on the screen could correct it.
     *
     * **x is proportional to time**, so a two-year gap takes its share of the width instead of
     * looking like the two weeks before it. Draw straight segments between readings: a curve
     * would claim values between two dates that nobody measured.
     */
    fun scale(points: List<MetricPoint>, range: ReferenceRange? = null): ChartScale? {
        if (points.isEmpty()) return null
        val values = points.map { it.value } + listOfNotNull(range?.low, range?.high)
        val low = minOf(0.0, values.min())
        val high = maxOf(0.0, values.max()).let { if (it == low) 1.0 else it }
        val ticks = ticks(low, high)
        val dates = points.map { it.date }
        val first = dates.min()
        val last = dates.max()
        val xStart = if (first == last) first.minus(DatePeriod(days = 1)) else first
        val xEnd = if (first == last) last.plus(DatePeriod(days = 1)) else last
        return ChartScale(ticks.first(), ticks.last(), ticks, xStart, xEnd, dates)
    }

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
