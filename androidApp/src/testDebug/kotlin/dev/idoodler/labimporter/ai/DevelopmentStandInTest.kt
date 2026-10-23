package dev.idoodler.labimporter.ai

import dev.idoodler.labimporter.core.parse.LabParsePrompt
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

/** Debug builds: the emulator stand-in speaks the real reply contract, so everything after the model runs for real. */
class DevelopmentStandInTest {
    private val ai = checkNotNull(DevelopmentStandIn.create())

    @Test
    fun theStandInIsOfferedAndAlwaysReady() {
        assertNotNull(DevelopmentStandIn.texts)
        assertNotNull(DevelopmentStandIn.sampleReport)
        assertEquals(AiAvailability.Ready, ai.availability.value)
    }

    @Test
    fun itStreamsAReplyInTheSharedContract() = runTest {
        val partials = mutableListOf<String>()
        val reply = ai.generate(LabParsePrompt.instructions, LabParsePrompt.prompt("anything"), LabParsePrompt.jsonSchema) { partials += it }
        assertTrue(partials.size > 1)
        assertTrue(partials.all { reply.startsWith(it) })
        val report = checkNotNull(LabParsePrompt.decodeJsonReply(reply))
        assertEquals(8, report.entries.size)
        assertEquals(Clock.System.todayIn(TimeZone.currentSystemDefault()).toString(), report.reportDate)
        assertEquals("Development stand-in", report.authorName)
    }

    @Test
    fun itsValuesGoThroughTheSharedPipeline() = runTest {
        val result = LabParserService(ai).parse("anything") {}
        assertEquals(8, result.values.size)
        assertEquals(7, result.values.count { it.numericValue != null }) // MALB-U is "-" (not detected).
    }
}
