package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.RangeStatus
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.core.parse.DateScanner
import dev.idoodler.labimporter.core.parse.HeuristicLabParser
import dev.idoodler.labimporter.core.parse.LabParsePrompt
import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PureLogicTest {

    @Test
    fun significantMatchesSwiftFormatting() {
        assertEquals("80", NumberFormatting.significant(80.0))
        assertEquals("0.8", NumberFormatting.significant(0.80))
        assertEquals("6.53", NumberFormatting.significant(6.53))
        assertEquals("123.5", NumberFormatting.significant(123.45))
        assertEquals("-0.0123", NumberFormatting.significant(-0.0123))
    }

    @Test
    fun parsesCommaDecimals() {
        assertEquals(0.8, NumberFormatting.parse("0,80"))
        assertNull(NumberFormatting.parse("-"))
    }

    @Test
    fun preferencesJsonIsCompatibleWithIos() {
        val ios = """{"pinnedCodes":["4548-4"],"orderedCodes":[],"hiddenCodes":[],"customNames":{"4548-4":"A1c"},"referenceRanges":{"2160-0":{"high":1.2}}}"""
        val prefs = LabDisplayPreferences.decode(ios)
        assertEquals("A1c", prefs.nickname("4548-4"))
        assertEquals(RangeStatus.HIGH, prefs.referenceRange("2160-0")!!.status(1.5))
        assertEquals(prefs, LabDisplayPreferences.decode(prefs.encode()))
        assertEquals(LabDisplayPreferences(), LabDisplayPreferences.decode("garbage"))
    }

    @Test
    fun rangeFormatting() {
        assertEquals("3.5–5.3 mmol/L", ReferenceRange(3.5, 5.3).formatted("mmol/L"))
        assertEquals("≤ 5.3", ReferenceRange(high = 5.3).formatted())
    }

    @Test
    fun decodesFencedModelReply() {
        val reply = "```json\n{\"entries\":[{\"code\":\"KREA\",\"name\":\"Kreatinin\",\"rawValue\":\"0,80\",\"unit\":\"mg/dl\"}],\"reportDate\":\"2026-05-06\"}\n```"
        val report = LabParsePrompt.decode(reply)
        assertEquals("Kreatinin", report.entries.single().name)
        assertEquals("2026-05-06", report.reportDate)
        assertEquals(1, LabParsePrompt.progress(reply).entryCount)
    }

    @Test
    fun heuristicParserReadsTerseReports() {
        val report = HeuristicLabParser.parse("Datum: 06.05.2026\nKREA: 0,80 mg/dl; GPT: 59 U/l; HB-A1C: 6.5 %\nKalium 4,1 mmol/l (3,5 - 5,1)")
        assertEquals(listOf("KREA", "GPT", "HB-A1C", "Kalium"), report.entries.map { it.code })
        assertEquals(listOf("", "", "", "Kalium"), report.entries.map { it.name })
        assertEquals("mmol/l", report.entries.last().unit)
        assertEquals("2026-05-06", report.reportDate)
    }

    @Test
    fun heuristicParserSkipsDates() {
        val report = HeuristicLabParser.parse("Befund vom 10.09.2026; Kalium: 4,6 mmol/l")
        assertEquals(listOf("Kalium"), report.entries.map { it.code })
        assertEquals("2026-09-10", report.reportDate)
    }

    @Test
    fun scansDates() {
        assertEquals(LocalDate(2026, 5, 6), DateScanner.firstDate("Abnahme 6.5.2026 08:00"))
    }
}
