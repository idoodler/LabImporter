package dev.idoodler.labimporter.ai

import android.util.Log
import dev.idoodler.labimporter.core.parse.AiLabReport
import dev.idoodler.labimporter.core.parse.LabParseChunks
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
 * both apps turn the same reply into the same values. A long report is read
 * in parts (`LabParseChunks`), like on iOS.
 */
class LabParserService(private val ai: OnDeviceAi) {

    /**
     * @throws AiException when the model is unavailable, a part of the report
     *   is too long even when split, or a reply isn't a complete, well-formed
     *   result — the user is told; nothing is guessed.
     */
    suspend fun parse(text: String, onProgress: (ParseProgress) -> Unit): ParseResult {
        val started = TimeSource.Monotonic.markNow()
        val pending = ArrayDeque(LabParseChunks.parts(text))
        val replies = mutableListOf<AiLabReport>()
        var entriesBefore = 0
        var latestName: String? = null
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            val reply = try {
                // Progress counts on across parts and keeps the last name until the next part names one.
                readPart(part) { progress ->
                    latestName = progress.latestName ?: latestName
                    onProgress(ParseProgress(entriesBefore + progress.entryCount, latestName))
                }
            } catch (e: AiException) {
                // The model's own count of a part can exceed the estimate: read it in halves.
                val halves = LabParseChunks.smaller(part)
                if (e.problem != AiProblem.DOCUMENT_TOO_LONG || halves == null) throw e
                halves.asReversed().forEach(pending::addFirst)
                continue
            }
            replies += reply
            entriesBefore += reply.entries.size
        }
        val generated = started.elapsedNow()
        val report = LabParseChunks.merge(replies)
        val result = withContext(Dispatchers.Default) { LabParsePipeline.postProcess(report, text) }
        Log.i(TAG, "Read ${result.values.size} values from ${replies.size} part(s): model $generated, post-processing ${started.elapsedNow() - generated}")
        return result
    }

    private suspend fun readPart(part: String, onProgress: (ParseProgress) -> Unit): AiLabReport {
        val reply = ai.generate(
            instructions = LabParsePrompt.instructions + "\n\n" + LabParsePrompt.jsonContract,
            prompt = LabParsePrompt.prompt(part),
            responseSchema = LabParsePrompt.jsonSchema,
        ) { partial -> onProgress(LabParsePrompt.progress(partial)) }
        return LabParsePrompt.decodeJsonReply(reply) ?: throw AiException(AiProblem.FAILED)
    }

    private companion object {
        const val TAG = "LabParserService"
    }
}
