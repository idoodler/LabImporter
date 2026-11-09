package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaParser
import dev.idoodler.labimporter.core.export.ReportSchema
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.RangeStatus
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.core.parse.AiLabReport
import dev.idoodler.labimporter.core.parse.CheckedDate
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import dev.idoodler.labimporter.core.parse.LabParsePrompt
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    fun theJsonSchemaDescribesExactlyTheDecodedFields() {
        val schema = Json.parseToJsonElement(LabParsePrompt.jsonSchema).jsonObject
        val properties = schema.getValue("properties").jsonObject
        assertEquals(setOf("entries", "reportDate", "patientName", "authorName", "reportLanguage"), properties.keys)
        val entry = properties.getValue("entries").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject
        assertEquals(setOf("code", "name", "rawValue", "unit"), entry.keys)
        // A reply shaped by the schema decodes.
        val reply = "{\"entries\":[{\"code\":\"\",\"name\":\"Ferritin\",\"rawValue\":\"88\",\"unit\":\"ng/ml\"}]," +
            "\"reportDate\":\"\",\"patientName\":\"\",\"authorName\":\"\",\"reportLanguage\":\"de\"}"
        assertEquals("Ferritin", LabParsePrompt.decodeJsonReply(reply)!!.entries.single().name)
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
    fun aDateTheReportDoesntPrintIsNoDate() {
        val text = "Patient geb. 12.03.1975\nPrélèvement du 03/10/2026 à 08:15, Referenz 3,92 - 5,08"
        assertEquals(setOf(LocalDate(1975, 3, 12), LocalDate(2026, 10, 3), LocalDate(2026, 3, 10)), LabParsePipeline.printedDates(text))
        assertEquals(LocalDate(2026, 10, 3), LabParsePipeline.verifiedDate(LocalDate(2026, 10, 3), text))
        // A mis-copied year: the one printed date with that day and month is what the model read.
        assertEquals(LocalDate(2026, 10, 3), LabParsePipeline.verifiedDate(LocalDate(2031, 10, 3), text))
        assertNull(LabParsePipeline.verifiedDate(LocalDate(2026, 10, 4), text))
        // Two printed dates share day and month: no way to tell which, so none.
        assertNull(LabParsePipeline.verifiedDate(LocalDate(2031, 10, 3), "03.10.2026 und 03.10.2025"))
        // Nothing numeric to check against (month names): the model's date stands.
        assertEquals(LocalDate(2026, 10, 3), LabParsePipeline.verifiedDate(LocalDate(2026, 10, 3), "3. Oktober 2026"))
        assertEquals(setOf(LocalDate(2026, 9, 30)), LabParsePipeline.printedDates("Collected: 09/30/2026 07:42"))
        assertEquals(setOf(LocalDate(2026, 5, 6)), LabParsePipeline.printedDates("2026-05-06 und 06.05.26"))
        assertNull(LabParsePipeline.verifiedDate(null, text))
    }

    /** On a fixed day (9 November 2026), as dates after it are no report's. */
    private fun checked(date: LocalDate, text: String, language: String) =
        LabParsePipeline.checkedDate(date, text, language, today = LocalDate(2026, 11, 9))

    @Test
    fun aDayAndMonthNothingOrdersAreFlagged() {
        // A US report (evaluation's en_us_wellness): 11/04/2026 is 4 November or 11 April, and no other date says which.
        val us = "DOB: 05/07/1958\nCollected: 11/04/2026 08:12"
        assertEquals(CheckedDate(LocalDate(2026, 4, 11), needsCheck = true), checked(LocalDate(2026, 4, 11), us, "en"))
        assertEquals(CheckedDate(LocalDate(2026, 11, 4), needsCheck = true), checked(LocalDate(2026, 11, 4), us, "en"))
        val result = LabParsePipeline.postProcess(AiLabReport(reportDate = "2026-04-11", reportLanguage = "en"), us)
        assertEquals(LocalDate(2026, 4, 11), result.reportDate)
        assertTrue(result.reportDateNeedsCheck)
        // Day and month alike read the same either way.
        assertEquals(CheckedDate(LocalDate(2026, 5, 5)), checked(LocalDate(2026, 5, 5), "Collected: 05/05/2026", "en"))
        // Dates that show both orders say nothing, nor does the language then.
        val mixed = "DOB: 23/04/1979\nPrevious: 12/19/2025\nCollected: 06/03/2026"
        assertEquals(CheckedDate(LocalDate(2026, 6, 3), needsCheck = true), checked(LocalDate(2026, 6, 3), mixed, "fr"))
    }

    @Test
    fun aSwapIsCorrectedWhereTheReportOrdersDayAndMonth() {
        // Another date that reads one way only (a day or a month above 12) says which comes first.
        val british = "DOB: 23/04/1979\nSample collected: 06/03/2026"
        assertEquals(CheckedDate(LocalDate(2026, 3, 6)), checked(LocalDate(2026, 3, 6), british, "en"))
        assertEquals(CheckedDate(LocalDate(2026, 3, 6)), checked(LocalDate(2026, 6, 3), british, "en"))
        val american = "DOB: 12/19/1970\nCollected: 02/05/2026 10:31"
        assertEquals(CheckedDate(LocalDate(2026, 2, 5)), checked(LocalDate(2026, 5, 2), american, "en"))
        assertEquals(CheckedDate(LocalDate(2026, 2, 5)), checked(LocalDate(2026, 2, 5), american, "en"))
        // A language that writes the day first says so too; a swap is then corrected but flagged,
        // since only the language said which (a Spanish report from the US may not).
        val french = "Né le 04/06/1985\nPrélevé le 07/02/2026"
        assertEquals(CheckedDate(LocalDate(2026, 2, 7)), checked(LocalDate(2026, 2, 7), french, "fr"))
        assertEquals(CheckedDate(LocalDate(2026, 2, 7), needsCheck = true), checked(LocalDate(2026, 7, 2), french, "fr-FR"))
        // Dots and ISO say which in every language; dashes are read like slashes.
        assertEquals(CheckedDate(LocalDate(2026, 3, 9)), checked(LocalDate(2026, 3, 9), "Abnahmedatum: 09.03.2026", "de"))
        assertEquals(CheckedDate(LocalDate(2026, 3, 9)), checked(LocalDate(2026, 9, 3), "Abnahmedatum: 09.03.2026", "en"))
        assertEquals(CheckedDate(LocalDate(2026, 4, 7)), checked(LocalDate(2026, 7, 4), "Provtagningsdatum: 2026-04-07", "sv"))
        assertEquals(CheckedDate(LocalDate(2026, 1, 5), needsCheck = true), checked(LocalDate(2026, 5, 1), "Afnamedatum: 05-01-2026", "nl"))
    }

    @Test
    fun aDateLongBeforeAnotherPrintedOneIsFlagged() {
        // evaluation's nl_uitslag: the model took the birth date for the report's.
        val dutch = "Geb.datum: 11-08-1990\nAfnamedatum: 05-01-2026"
        assertEquals(CheckedDate(LocalDate(1990, 8, 11), needsCheck = true), checked(LocalDate(1990, 8, 11), dutch, "nl"))
        assertEquals(CheckedDate(LocalDate(2026, 1, 5)), checked(LocalDate(2026, 1, 5), dutch, "nl"))
        // A cumulative report's earlier results, or the next day's print-out, are no such date.
        val cumulative = "Date Collected: 10/14/2026\nGlucose 65 Low 92 04/02/2026 mg/dL\nPrinted: 10/15/2026"
        assertEquals(CheckedDate(LocalDate(2026, 10, 14)), checked(LocalDate(2026, 10, 14), cumulative, "en"))
    }

    @Test
    fun aBirthDateWithATwoDigitYearIsNoLaterDate() {
        // "58" is 1958, not 2058: no date a year after the report's.
        val german = "Patient: Max Muster, geb. 12.03.58\nAbnahmedatum: 15.10.2026"
        assertEquals(CheckedDate(LocalDate(2026, 10, 15)), checked(LocalDate(2026, 10, 15), german, "de"))
        assertEquals(CheckedDate(LocalDate(2026, 10, 15)), checked(LocalDate(2026, 10, 15), "DOB: 07/05/58\nCollected: 10/15/2026", "en"))
        // Taking that birth date for the report's is flagged.
        assertEquals(CheckedDate(LocalDate(1958, 3, 12), needsCheck = true), checked(LocalDate(1958, 3, 12), german, "de"))
        // This year's two-digit year stays 20xx.
        assertEquals(CheckedDate(LocalDate(2026, 10, 15)), checked(LocalDate(2026, 10, 15), "Abnahme 15.10.26", "de"))
        assertEquals(setOf(LocalDate(1958, 3, 12), LocalDate(2026, 10, 15)), LabParsePipeline.printedDates("12.03.58 15.10.26", LocalDate(2026, 11, 9)))
    }

    @Test
    fun aDateAfterTodayIsNoLaterReportDate() {
        // A next appointment printed more than a year ahead says nothing about the report's date.
        val next = "Abnahmedatum: 15.10.2026\nNächste Kontrolle: 01.03.2028"
        assertEquals(CheckedDate(LocalDate(2026, 10, 15)), checked(LocalDate(2026, 10, 15), next, "de"))
    }

    @Test
    fun aRegionThatOrdersDayAndMonthIsKept() {
        // en-GB and en-AU write the day first, en-US the month: the date is no longer "either way".
        assertEquals(CheckedDate(LocalDate(2026, 3, 6)), checked(LocalDate(2026, 3, 6), "Sample collected: 06/03/2026", "en-GB"))
        assertEquals(CheckedDate(LocalDate(2026, 3, 6)), checked(LocalDate(2026, 3, 6), "Collected: 06/03/2026", "en_AU"))
        assertEquals(CheckedDate(LocalDate(2026, 6, 3)), checked(LocalDate(2026, 6, 3), "Collected: 06/03/2026", "en-US"))
        // A swap is corrected, and flagged: only the region said which comes first.
        assertEquals(CheckedDate(LocalDate(2026, 3, 6), needsCheck = true), checked(LocalDate(2026, 6, 3), "Sample collected: 06/03/2026", "en-GB"))
        // A region that doesn't say (Canada writes both) leaves the date "either way".
        assertEquals(CheckedDate(LocalDate(2026, 3, 6), needsCheck = true), checked(LocalDate(2026, 3, 6), "Collected: 06/03/2026", "en-CA"))
    }

    @Test
    fun aReadingAfterTodayIsNoOtherReportDate() {
        // 08/12/2026 on 9 November 2026: 8 December hasn't come yet, so it's 12 August.
        assertEquals(CheckedDate(LocalDate(2026, 8, 12)), checked(LocalDate(2026, 8, 12), "Collected: 08/12/2026", "en"))
        // The model's reading of it as 8 December is the one that may be wrong.
        assertEquals(CheckedDate(LocalDate(2026, 12, 8), needsCheck = true), checked(LocalDate(2026, 12, 8), "Collected: 08/12/2026", "en"))
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
