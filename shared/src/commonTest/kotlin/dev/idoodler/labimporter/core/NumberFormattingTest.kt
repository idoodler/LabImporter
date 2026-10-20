package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumberFormattingTest {

    /** Generated with Swift's `String(format:)` — what the iOS app always wrote (`%.0f` / `%.4g`). */
    private val iosReference = listOf(
        80.0 to "80", 0.8 to "0.8", 6.53 to "6.53", 123.45 to "123.5", 0.000123456 to "0.0001235",
        1.23456e-05 to "1.235e-05", 12345.6 to "1.235e+04", 9.99996 to "10", 99999.5 to "1e+05",
        1e20 to "100000000000000000000", -0.5 to "-0.5", 1234.5 to "1234", 0.125 to "0.125", 2.5 to "2.5",
        1234.45 to "1234", 0.1 to "0.1", 1.0005 to "1", 59.0 to "59", 4.1 to "4.1", 0.93 to "0.93",
        198.0 to "198", 1e-05 to "1e-05", 123456789.5 to "1.235e+08", 0.00099995 to "0.001", 6.5 to "6.5",
        -12.75 to "-12.75", 3.14159265 to "3.142",
    )

    @Test
    fun cdaValueMatchesIosFormatting() {
        for ((value, expected) in iosReference) assertEquals(expected, NumberFormatting.cdaValue(value), "for $value")
    }

    @Test
    fun parseAcceptsPlainNumbersOnly() {
        assertEquals(0.8, NumberFormatting.parse("0,80"))
        assertEquals(6.5, NumberFormatting.parse(" 6.5 "))
        assertEquals(5.0, NumberFormatting.parse("+5"))
        assertEquals(0.5, NumberFormatting.parse(".5"))
        assertEquals(1000.0, NumberFormatting.parse("1e3"))
        // Qualified, flagged or textual results are not numbers — never guess a value.
        for (raw in listOf("-", "", "< 5", "<5", ">1000", "6,5 H", "positiv", "NaN", "Infinity", "5d", "1.2.3", "5 mg")) {
            assertNull(NumberFormatting.parse(raw), "'$raw' must not parse")
        }
    }

    @Test
    fun displayTrimsZeros() {
        assertEquals("6.5", NumberFormatting.display(6.5))
        assertEquals("0.8333", NumberFormatting.display(0.83333))
        assertEquals("120", NumberFormatting.display(120.0))
    }
}
