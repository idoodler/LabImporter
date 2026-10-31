package dev.idoodler.labimporter.ai

import dev.idoodler.labimporter.core.parse.LabParsePrompt
import dev.idoodler.labimporter.core.parse.ParseProgress
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Android parse path: shared prompt in, shared post-processing out, never a guess. */
class LabParserServiceTest {
    private val ai = FakeAi()
    private val parser = LabParserService(ai)

    @Test
    fun aWellFormedReplyBecomesReviewableValues() = runTest {
        ai.reply = {
            FakeAi.replyJson(
                listOf("KREA", "Kreatinin", "0,8", "mg/dl"),
                listOf("MALB-U", "Albumin im Urin", "-", "mg/l"),
            )
        }
        val progress = mutableListOf<ParseProgress>()
        val result = parser.parse("KREA: 0,8 mg/dl; MALB-U: - mg/l") { progress += it }

        assertEquals(listOf(0.8, null), result.values.map { it.numericValue })
        assertEquals(listOf("0,8", "-"), result.values.map { it.displayValue })
        assertEquals(LocalDate(2026, 5, 6), result.reportDate)
        assertEquals("Max Mustermann", result.patientName)
        assertEquals("Labor Dr. Muster", result.authorName)
        assertEquals(2, progress.last().entryCount)
    }

    @Test
    fun theModelGetsTheSharedInstructionsContractAndReport() = runTest {
        ai.reply = { FakeAi.replyJson() }
        parser.parse("TSH: 1,9 mU/l") {}
        val (instructions, prompt) = ai.requests.single()
        assertTrue(instructions.startsWith(LabParsePrompt.instructions))
        assertTrue(instructions.endsWith(LabParsePrompt.jsonContract))
        assertEquals(LabParsePrompt.prompt("TSH: 1,9 mU/l"), prompt)
    }

    @Test
    fun aFencedReplyIsAccepted() = runTest {
        ai.reply = { "```json\n" + FakeAi.replyJson(listOf("TSH", "Thyreotropin", "1,9", "mU/l")) + "\n```" }
        assertEquals(1, parser.parse("TSH: 1,9") {}.values.size)
    }

    @Test
    fun anIncompleteOrChattyReplyIsAFailureNotAnEmptyResult() = runTest {
        for (reply in listOf(FakeAi.replyJson(listOf("A", "B", "1", "u")).dropLast(3), "Here are the values: KREA 0,8", "")) {
            ai.reply = { reply }
            try {
                parser.parse("KREA 0,8") {}
                fail("Expected a failure for: $reply")
            } catch (e: AiException) {
                assertEquals(AiProblem.FAILED, e.problem)
            }
        }
    }

    @Test
    fun modelProblemsReachTheCallerUnchanged() = runTest {
        ai.reply = { throw AiException(AiProblem.DOCUMENT_TOO_LONG) }
        try {
            parser.parse("…") {}
            fail("Expected DOCUMENT_TOO_LONG")
        } catch (e: AiException) {
            assertEquals(AiProblem.DOCUMENT_TOO_LONG, e.problem)
        }
    }

    /** A model that reads every "CODE: value unit" row of the part it's given. */
    private fun readRows(): String {
        val part = ai.requests.last().second
        val rows = Regex("(TEST\\d+): (\\S+) (\\S+)").findAll(part).map { it.destructured.toList() }.toList()
        return FakeAi.replyJson(*rows.map { (code, value, unit) -> listOf(code, code, value, unit) }.toTypedArray())
    }

    private val longReport = (1..80).joinToString("\n") { "TEST$it: ${it}5 mg/dl  (Referenz 10 - 900)" }

    @Test
    fun aLongReportIsReadInPartsAndEveryValueOnce() = runTest {
        ai.reply = { readRows() }
        val progress = mutableListOf<ParseProgress>()
        val result = parser.parse(longReport) { progress += it }

        assertTrue(ai.requests.size > 1)
        assertEquals((1..80).map { "TEST$it" }, result.values.map { it.code })
        assertEquals(LocalDate(2026, 5, 6), result.reportDate)
        // The count keeps rising across parts (the overlap rows aside) instead of starting over.
        assertTrue(progress.last().entryCount >= 80)
        assertEquals(progress.map { it.entryCount }.sorted(), progress.map { it.entryCount })
    }

    @Test
    fun aPartTheModelStillFindsTooLongIsReadInHalves() = runTest {
        ai.reply = {
            if (ai.requests.last().second.length > 1_500) throw AiException(AiProblem.DOCUMENT_TOO_LONG)
            readRows()
        }
        val result = parser.parse(longReport) {}
        assertEquals((1..80).map { "TEST$it" }, result.values.map { it.code })
        // Halving stops at a minimum: a model that takes nothing is "too long", not a loop.
        ai.reply = { throw AiException(AiProblem.DOCUMENT_TOO_LONG) }
        try {
            parser.parse(longReport) {}
            fail("Expected DOCUMENT_TOO_LONG")
        } catch (e: AiException) {
            assertEquals(AiProblem.DOCUMENT_TOO_LONG, e.problem)
        }
    }

    @Test
    fun aReportWithoutADateLeavesItToTheReview() = runTest {
        ai.reply = { FakeAi.replyJson(listOf("TSH", "Thyreotropin", "1,9", "mU/l"), reportDate = "06.05.2026") }
        assertNull(parser.parse("TSH: 1,9") {}.reportDate)
    }
}
