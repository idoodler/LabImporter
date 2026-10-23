package dev.idoodler.labimporter.ai

import com.google.mlkit.genai.common.GenAiException.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Every ML Kit error the user can hit ends in an actionable, specific message. */
class GenAiErrorsTest {

    @Test
    fun generationErrorsBecomeActionableProblems() {
        val expected = mapOf(
            ErrorCode.REQUEST_TOO_LARGE to AiProblem.DOCUMENT_TOO_LONG,
            ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR to AiProblem.DOCUMENT_TOO_LONG,
            ErrorCode.BUSY to AiProblem.BUSY,
            ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED to AiProblem.QUOTA_EXCEEDED,
            ErrorCode.BACKGROUND_USE_BLOCKED to AiProblem.BACKGROUND,
            ErrorCode.NOT_ENOUGH_DISK_SPACE to AiProblem.NOT_ENOUGH_STORAGE,
            ErrorCode.NOT_AVAILABLE to AiProblem.UNAVAILABLE,
            ErrorCode.NOT_SUPPORTED to AiProblem.UNAVAILABLE,
            ErrorCode.NEEDS_SYSTEM_UPDATE to AiProblem.UNAVAILABLE,
            ErrorCode.AICORE_INCOMPATIBLE to AiProblem.UNAVAILABLE,
            ErrorCode.RESPONSE_GENERATION_ERROR to AiProblem.FAILED,
            ErrorCode.RESPONSE_PROCESSING_ERROR to AiProblem.FAILED,
            ErrorCode.UNKNOWN to AiProblem.FAILED,
        )
        expected.forEach { (code, problem) -> assertEquals("code $code", problem, GenAiErrors.generationProblem(code)) }
    }

    @Test
    fun downloadErrorsSayWhatToFix() {
        assertEquals(AiProblem.NOT_ENOUGH_STORAGE, GenAiErrors.downloadProblem(ErrorCode.NOT_ENOUGH_DISK_SPACE))
        assertEquals(AiProblem.UNAVAILABLE, GenAiErrors.downloadProblem(ErrorCode.NEEDS_SYSTEM_UPDATE))
        // A host-resolution failure is reported as code 0: a connection problem.
        assertEquals(AiProblem.DOWNLOAD_FAILED, GenAiErrors.downloadProblem(ErrorCode.UNKNOWN))
    }

    @Test
    fun onlyTheSystemsVerdictMakesAPhoneUnsupported() {
        assertEquals(UnsupportedReason.UPDATE_REQUIRED, GenAiErrors.unsupportedReason(ErrorCode.NEEDS_SYSTEM_UPDATE))
        assertEquals(UnsupportedReason.UPDATE_REQUIRED, GenAiErrors.unsupportedReason(ErrorCode.AICORE_INCOMPATIBLE))
        assertEquals(UnsupportedReason.DEVICE, GenAiErrors.unsupportedReason(ErrorCode.NOT_SUPPORTED))
        assertEquals(UnsupportedReason.DEVICE, GenAiErrors.unsupportedReason(ErrorCode.NOT_AVAILABLE))
        // Anything else is a failed check, not a verdict on the phone.
        listOf(ErrorCode.UNKNOWN, ErrorCode.BUSY, ErrorCode.CANCELLED, ErrorCode.REQUEST_PROCESSING_ERROR).forEach {
            assertEquals("code $it", UnsupportedReason.NOT_DETERMINED, GenAiErrors.unsupportedReason(it))
        }
    }

    @Test
    fun errorCodesAreNamedForTheDetailsLine() {
        assertEquals("604 NEEDS_SYSTEM_UPDATE", GenAiErrors.describe(ErrorCode.NEEDS_SYSTEM_UPDATE))
        assertEquals("16 NOT_SUPPORTED", GenAiErrors.describe(ErrorCode.NOT_SUPPORTED))
        assertEquals("12345 error", GenAiErrors.describe(12345))
    }

    @Test
    fun noMlKitClientMeansCouldNotCheckNotUnsupported() = runBlocking {
        val ai = GeminiNanoAi(this, client = { throw IllegalStateException("no AICore") })
        ai.refresh()
        assertEquals(AiAvailability.Unsupported(UnsupportedReason.NOT_DETERMINED, "ML Kit client: IllegalStateException"), ai.availability.value)
    }

    @Test
    fun onlyLosingTheModelTriggersARecheck() {
        assertTrue(GenAiErrors.affectsAvailability(ErrorCode.NOT_AVAILABLE))
        assertFalse(GenAiErrors.affectsAvailability(ErrorCode.BUSY))
        assertFalse(GenAiErrors.affectsAvailability(ErrorCode.REQUEST_TOO_LARGE))
    }

    @Test
    fun everyProblemHasAMessage() {
        AiProblem.entries.forEach { assertTrue(it.name, it.messageRes != 0) }
    }
}
