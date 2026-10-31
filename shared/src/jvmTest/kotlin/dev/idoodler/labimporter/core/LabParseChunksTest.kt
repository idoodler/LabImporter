package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.parse.AiLabEntry
import dev.idoodler.labimporter.core.parse.AiLabReport
import dev.idoodler.labimporter.core.parse.LabParseChunks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabParseChunksTest {

    private val rows = (1..120).map { "TEST$it: ${it * 3},$it mg/dl  (Referenz 1,0 - 9,9)" }

    @Test
    fun aReportThatFitsIsReadAsIs() {
        val text = "KREA: 0,8 mg/dl\n\nTSH: 1,9 mU/l  "
        assertEquals(listOf(text), LabParseChunks.parts(text))
    }

    @Test
    fun aLongReportIsCutBetweenLinesWithinTheLimit() {
        val parts = LabParseChunks.parts(rows.joinToString("\n"))
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= LabParseChunks.MAX_CHARS }, parts.map { it.length }.toString())
        // Every row is in some part, whole.
        val lines = parts.flatMap { it.lines() }.toSet()
        assertTrue(rows.all { it in lines })
    }

    @Test
    fun eachPartRepeatsTheLastLinesOfTheOneBefore() {
        val parts = LabParseChunks.split((listOf("Kreatinin") + rows.take(40)).joinToString("\n"), maxChars = 600)
        for ((previous, next) in parts.zipWithNext()) {
            val carried = previous.lines().takeLast(LabParseChunks.OVERLAP_LINES)
            assertEquals(carried, next.lines().take(LabParseChunks.OVERLAP_LINES))
        }
        // A name at the end of one part arrives with its value at the start of the next.
        val text = "a".repeat(580) + "\nHämoglobin\n14,2 g/dl\n" + "b".repeat(100)
        val (first, second) = LabParseChunks.split(text, maxChars = 600)
        assertTrue(first.endsWith("Hämoglobin"))
        assertTrue(second.startsWith("Hämoglobin\n14,2 g/dl"), second)
    }

    @Test
    fun aPageThatArrivesAsOneLineIsWrappedAtWordBreaks() {
        val page = rows.joinToString("  ")
        val parts = LabParseChunks.parts(page)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= LabParseChunks.MAX_CHARS })
        val words = page.split(Regex("\\s+")).toSet()
        val read = parts.flatMap { it.split(Regex("\\s+")) }.toSet()
        assertEquals(words, read)
    }

    @Test
    fun aPartTheModelFindsTooLongIsHalvedDownToAMinimum() {
        val part = rows.take(30).joinToString("\n")
        val halves = LabParseChunks.smaller(part)!!
        assertTrue(halves.size >= 2)
        assertTrue(halves.all { it.length < part.length })
        assertNull(LabParseChunks.smaller("x".repeat(LabParseChunks.MIN_CHARS)))
    }

    @Test
    fun mergedRepliesKeepEveryValueOnceAndTheFirstHeaderFields() {
        val krea = AiLabEntry("KREA", "Kreatinin", "0,8", "mg/dl")
        val merged = LabParseChunks.merge(
            listOf(
                AiLabReport(listOf(krea), reportDate = "", patientName = "Max Mustermann", reportLanguage = "en"),
                AiLabReport(
                    listOf(krea.copy(code = " krea "), AiLabEntry("TSH", "Thyreotropin", "1,9", "mU/l")),
                    reportDate = "2026-05-06", patientName = "Someone Else", authorName = "Labor Dr. Muster", reportLanguage = "de",
                ),
                AiLabReport(listOf(AiLabEntry("HB", "Hämoglobin", "14,2", "g/dl")), reportLanguage = "de"),
            ),
        )
        assertEquals(listOf("KREA", "TSH", "HB"), merged.entries.map { it.code })
        assertEquals("2026-05-06", merged.reportDate)
        assertEquals("Max Mustermann", merged.patientName)
        assertEquals("Labor Dr. Muster", merged.authorName)
        assertEquals("de", merged.reportLanguage)
    }
}
