package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.model.ChartAxis
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
