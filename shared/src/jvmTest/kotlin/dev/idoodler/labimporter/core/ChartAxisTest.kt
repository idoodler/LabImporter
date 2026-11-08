package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.model.ChartAxis
import dev.idoodler.labimporter.core.model.MetricPoint
import dev.idoodler.labimporter.core.model.ReferenceRange
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartAxisTest {
    @Test
    fun ticksAreRoundAndCoverTheData() {
        assertEquals(listOf(90.0, 95.0, 100.0, 105.0), ChartAxis.ticks(92.36, 103.8))
        assertEquals(listOf(6.0, 6.2, 6.4, 6.6, 6.8), ChartAxis.ticks(6.03, 6.75))
        assertEquals(listOf(0.0, 50.0, 100.0, 150.0, 200.0, 250.0), ChartAxis.ticks(3.0, 240.0))
        assertEquals(listOf(0.78, 0.8, 0.82, 0.84), ChartAxis.ticks(0.78, 0.84))
    }

    @Test
    fun aFlatSeriesStillGetsAnAxis() {
        val ticks = ChartAxis.ticks(5.0, 5.0)
        assertTrue(ticks.first() < 5.0 && ticks.last() > 5.0 && ticks.size >= 3)
        val zero = ChartAxis.ticks(0.0, 0.0)
        assertTrue(zero.first() < 0.0 && zero.last() > 0.0)
    }

    @Test
    fun boundsMayComeInEitherOrder() {
        assertEquals(ChartAxis.ticks(1.0, 9.0), ChartAxis.ticks(9.0, 1.0))
    }

    private fun point(date: LocalDate, value: Double) = MetricPoint(date, value, "ng/mL", "report")

    /** Vitamin D with irregular gaps: two weeks, two years, four months. */
    private val irregular = listOf(
        point(LocalDate(2024, 1, 10), 32.0),
        point(LocalDate(2024, 1, 24), 35.0),
        point(LocalDate(2026, 1, 24), 33.0),
        point(LocalDate(2026, 5, 28), 34.0),
    )

    @Test
    fun theSharedScalePinsDomainTicksAndPositions() {
        val scale = ChartAxis.scale(irregular)!!
        assertEquals(0.0, scale.yLow)
        assertEquals(40.0, scale.yHigh)
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0), scale.yTicks)
        assertEquals(LocalDate(2024, 1, 10), scale.xStart)
        assertEquals(LocalDate(2026, 5, 28), scale.xEnd)
        assertEquals(listOf(0.0, 14.0 / 869, 745.0 / 869, 1.0), irregular.map { scale.xFraction(it.date) })
        assertEquals(listOf(0.8, 0.875, 0.825, 0.85), irregular.map { scale.yFraction(it.value) })
    }

    @Test
    fun readingsOnOneDateLabelThatDateNotTheAxisEnds() {
        val day = LocalDate(2026, 5, 28)
        assertEquals(listOf(irregular.first().date, irregular.last().date), ChartAxis.scale(irregular)!!.xLabels)
        assertEquals(listOf(day), ChartAxis.scale(listOf(point(day, 34.0)))!!.xLabels)
        assertEquals(listOf(day), ChartAxis.scale(listOf(point(day, 34.0), point(day, 36.0)))!!.xLabels)
    }

    @Test
    fun aLongGapTakesItsShareOfTheWidth() {
        val scale = ChartAxis.scale(irregular)!!
        val (start, twoWeeks, twoYears) = irregular.map { scale.xFraction(it.date) }
        assertEquals(731.0 / 14, (twoYears - twoWeeks) / (twoWeeks - start), 1e-9)
    }

    @Test
    fun theFingerSelectsTheNearestReadingByDate() {
        val scale = ChartAxis.scale(irregular)!!
        assertEquals(0, scale.nearestIndex(0.0))
        // A third of the way along the two-year gap is still nearer its older end.
        assertEquals(1, scale.nearestIndex(0.3))
        assertEquals(2, scale.nearestIndex(0.6))
        assertEquals(3, scale.nearestIndex(1.0))
    }

    @Test
    fun theAxisHasRoomForTheReferenceRange() {
        val scale = ChartAxis.scale(irregular, ReferenceRange(low = 30.0, high = 50.0))!!
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0), scale.yTicks)
    }

    @Test
    fun negativeAndZeroValuesStillGetAnAxisThroughZero() {
        val negative = ChartAxis.scale(listOf(point(LocalDate(2026, 1, 1), -3.0), point(LocalDate(2026, 2, 1), -1.0)))!!
        assertEquals(-3.0, negative.yLow)
        assertEquals(0.0, negative.yHigh)
        val zero = ChartAxis.scale(listOf(point(LocalDate(2026, 1, 1), 0.0), point(LocalDate(2026, 2, 1), 0.0)))!!
        assertEquals(0.0, zero.yLow)
        assertEquals(1.0, zero.yHigh)
    }

    @Test
    fun aLoneDateSitsInTheMiddle() {
        val day = LocalDate(2026, 3, 1)
        val one = ChartAxis.scale(listOf(point(day, 5.0)))!!
        assertEquals(0.5, one.xFraction(day))
        assertEquals(0, one.nearestIndex(0.9))
        val twoOnOneDay = ChartAxis.scale(listOf(point(day, 5.0), point(day, 6.0)))!!
        assertEquals(0.5, twoOnOneDay.xFraction(day))
        assertNull(ChartAxis.scale(emptyList()))
    }
}
