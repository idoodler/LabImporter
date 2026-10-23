package dev.idoodler.labimporter.ai

import android.util.Log
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import dev.idoodler.labimporter.core.parse.LabParsePrompt
import dev.idoodler.labimporter.core.parse.ParseProgress
import dev.idoodler.labimporter.core.parse.ParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Reads lab values out of report text with the on-device model. The
 * instructions, the reply schema and every step after the model (number
 * parsing, LOINC resolution in the report's own language, checking the date
 * against the text, de-duplication) are
 * shared with iOS (LabImporterCore `LabParsePrompt` / `LabParsePipeline`), so
 * both apps turn the same reply into the same values.
 */
class LabParserService(private val ai: OnDeviceAi) {

    /**
     * @throws AiException when the model is unavailable, the report is too
     *   long, or the reply isn't a complete, well-formed result — the user is
     *   told; nothing is guessed.
     */
    suspend fun parse(text: String, onProgress: (ParseProgress) -> Unit): ParseResult {
        val started = TimeSource.Monotonic.markNow()
        val reply = ai.generate(
            instructions = LabParsePrompt.instructions + "\n\n" + LabParsePrompt.jsonContract,
            prompt = LabParsePrompt.prompt(text),
            responseSchema = LabParsePrompt.jsonSchema,
        ) { partial -> onProgress(LabParsePrompt.progress(partial)) }
        val generated = started.elapsedNow()
        val report = LabParsePrompt.decodeJsonReply(reply) ?: throw AiException(AiProblem.FAILED)
        val result = withContext(Dispatchers.Default) { LabParsePipeline.postProcess(report, text) }
        Log.i(TAG, "Read ${result.values.size} values: model $generated, post-processing ${started.elapsedNow() - generated}")
        return result
    }

    private companion object {
        const val TAG = "LabParserService"
    }
}
