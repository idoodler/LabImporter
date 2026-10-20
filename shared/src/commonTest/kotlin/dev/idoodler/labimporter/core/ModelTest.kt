package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaParser
import dev.idoodler.labimporter.core.export.ReportSchema
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.RangeStatus
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import dev.idoodler.labimporter.core.parse.LabParsePrompt
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {

    @Test
    fun preferencesJsonIsCompatibleWithIos() {
        val ios = """{"pinnedCodes":["4548-4"],"orderedCodes":[],"hiddenCodes":[],"customNames":{"4548-4":"A1c"},"referenceRanges":{"2160-0":{"high":1.2}}}"""
        val prefs = LabDisplayPreferences.decode(ios)
        assertEquals("A1c", prefs.nickname("4548-4"))
        assertEquals(RangeStatus.HIGH, prefs.referenceRange("2160-0")!!.status(1.5))
        assertEquals(prefs, LabDisplayPreferences.decode(prefs.encode()))
        // Blobs from builds before nicknames/ranges existed still decode.
        assertEquals(listOf("1"), LabDisplayPreferences.decode("""{"pinnedCodes":["1"],"orderedCodes":[],"hiddenCodes":[]}""").pinnedCodes)
        assertEquals(LabDisplayPreferences(), LabDisplayPreferences.decode("garbage"))
    }

    @Test
    fun encodingAlwaysWritesEveryKeyForOlderIosBuilds() {
        val encoded = LabDisplayPreferences(referenceRanges = mapOf("x" to ReferenceRange(high = 2.0))).encode()
        for (key in listOf("pinnedCodes", "orderedCodes", "hiddenCodes", "customNames", "referenceRanges")) {
            assertTrue("\"$key\"" in encoded, "missing $key in $encoded")
        }
        assertTrue("\"low\"" !in encoded, encoded)
    }

    @Test
    fun nicknamesAndRangesAreTrimmedAndCleared() {
        var prefs = LabDisplayPreferences().withNickname("  HbA1c  ", " 4548-4 ")
        assertEquals("HbA1c", prefs.nickname("4548-4"))
        prefs = prefs.withNickname("  ", "4548-4")
        assertNull(prefs.nickname("4548-4"))
        prefs = prefs.withReferenceRange(ReferenceRange(null, null), "2160-0")
        assertNull(prefs.referenceRange("2160-0"))
    }

    @Test
    fun referenceRanges() {
        val range = ReferenceRange(3.5, 5.3)
        assertEquals(RangeStatus.LOW, range.status(3.4))
        assertEquals(RangeStatus.NORMAL, range.status(3.5))
        assertEquals(RangeStatus.NORMAL, range.status(5.3))
        assertEquals(RangeStatus.HIGH, range.status(5.31))
        assertEquals("3.5–5.3 mmol/L", range.formatted("mmol/L"))
        assertEquals("≤ 5.3", ReferenceRange(high = 5.3).formatted())
        assertEquals("≥ 3.5", ReferenceRange(low = 3.5).formatted())
    }

    @Test
    fun modelRepliesMustBeWellFormed() {
        val reply = "```json\n{\"entries\":[{\"code\":\"KREA\",\"name\":\"Kreatinin\",\"rawValue\":\"0,80\",\"unit\":\"mg/dl\"}],\"reportDate\":\"2026-05-06\"}\n```"
        assertEquals("Kreatinin", LabParsePrompt.decodeJsonReply(reply)!!.entries.single().name)
        assertEquals(1, LabParsePrompt.progress(reply).entryCount)
        assertNull(LabParsePrompt.decodeJsonReply("Here are the values: KREA 0.8"))
        assertNull(LabParsePrompt.decodeJsonReply("{\"entries\": [ {\"code\": "))
    }

    @Test
    fun onlyIsoDatesAreAccepted() {
        assertEquals(LocalDate(2026, 5, 6), LabParsePipeline.parseDate("2026-05-06"))
        assertNull(LabParsePipeline.parseDate("06.05.2026"))
        assertNull(LabParsePipeline.parseDate("05/06/2026"))
        assertNull(LabParsePipeline.parseDate("2026-13-01"))
        assertNull(LabParsePipeline.parseDate(""))
    }

    @Test
    fun schemaVersionGatesReadBack() {
        val report = LabReport("r", LocalDate(2026, 1, 1), "", "", emptyList())
        assertEquals(report, ReportSchema.upgrade(report, ReportSchema.VERSION))
        assertNull(ReportSchema.upgrade(report, null))
        assertNull(ReportSchema.upgrade(report, 0))
        assertNull(ReportSchema.upgrade(report, ReportSchema.VERSION + 1))
        assertEquals(1, CdaParser.schemaVersion("LabImporter CDA v1"))
        assertEquals(12, CdaParser.schemaVersion("LabImporter CDA v12 (beta)"))
        assertNull(CdaParser.schemaVersion("Some Other App"))
    }
}
