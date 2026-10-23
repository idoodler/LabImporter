package dev.idoodler.labimporter.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectableAiTest {
    private val gemini = FakeAi(AiAvailability.Unsupported(UnsupportedReason.DEVICE)).apply { reply = { "gemini" } }
    private val standIn = FakeAi().apply { reply = { "stand-in" } }

    @Test
    fun geminiNanoDecidesUnlessTheStandInIsChosen() = runTest(UnconfinedTestDispatcher()) {
        val ai = SelectableAi(gemini, gemma = null, standIn = standIn, useStandIn = false, scope = backgroundScope)
        assertEquals(AiAvailability.Unsupported(UnsupportedReason.DEVICE), ai.availability.value)
        assertEquals(AiEngine.GEMINI_NANO, ai.engine)

        ai.useStandIn(true)
        assertEquals(AiAvailability.Ready, ai.availability.value)
        assertEquals(AiEngine.DEVELOPMENT_STAND_IN, ai.engine)
        assertEquals("stand-in", ai.generate("i", "p", null) {})

        ai.useStandIn(false)
        assertEquals(AiAvailability.Unsupported(UnsupportedReason.DEVICE), ai.availability.value)
        assertEquals("gemini", ai.generate("i", "p", null) {})
    }

    @Test
    fun realAvailabilityChangesShowThrough() = runTest(UnconfinedTestDispatcher()) {
        val ai = SelectableAi(gemini, gemma = null, standIn = standIn, useStandIn = false, scope = backgroundScope)
        gemini.state.value = AiAvailability.Preparing(1, 2)
        assertEquals(AiAvailability.Preparing(1, 2), ai.availability.value)
        gemini.state.value = AiAvailability.Ready
        assertEquals(AiAvailability.Ready, ai.availability.value)
    }

    @Test
    fun releaseBuildsCannotSwitchAwayFromGemini() = runTest(UnconfinedTestDispatcher()) {
        val ai = SelectableAi(gemini, gemma = null, standIn = null, useStandIn = true, scope = backgroundScope)
        assertFalse(ai.isUsingStandIn.value)
        ai.useStandIn(true)
        assertFalse(ai.isUsingStandIn.value)
        assertEquals(AiEngine.GEMINI_NANO, ai.engine)
        assertTrue(ai.availability.value is AiAvailability.Unsupported)
    }

    @Test
    fun checkingAgainAsksTheActiveModel() = runTest(UnconfinedTestDispatcher()) {
        val ai = SelectableAi(gemini, gemma = null, standIn = standIn, useStandIn = false, scope = backgroundScope)
        ai.refresh()
        assertEquals(1, gemini.refreshes)
        assertEquals(0, standIn.refreshes)
    }
}
