package dev.idoodler.labimporter.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import dev.idoodler.labimporter.core.parse.AiLabReport
import dev.idoodler.labimporter.core.parse.HeuristicLabParser
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import dev.idoodler.labimporter.core.parse.LabParsePrompt
import dev.idoodler.labimporter.core.parse.ParseProgress
import dev.idoodler.labimporter.core.parse.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** State of the on-device language model (Gemini Nano via ML Kit / AICore). */
sealed interface AiStatus {
    data object Checking : AiStatus
    data object Available : AiStatus
    data object Downloadable : AiStatus
    data class Downloading(val bytes: Long) : AiStatus
    /** Not supported on this device (or AICore hasn't been configured yet). */
    data object Unavailable : AiStatus
}

/** Which engine produced a parse. */
enum class ParseEngine { GEMINI_NANO, RULES }

data class ParseOutcome(val result: ParseResult, val engine: ParseEngine)

/**
 * Parses report text into lab values entirely on device.
 *
 * Primary engine: Gemini Nano through the ML Kit GenAI Prompt API — the
 * Android counterpart of Apple's Foundation Models. The prompt, the JSON
 * contract and all post-processing (LOINC resolution, dedup) are shared with
 * iOS via `LabParsePrompt` / `LabParsePipeline`.
 *
 * Fallback: `HeuristicLabParser`, a rule-based reader, for hardware without
 * Gemini Nano (most devices today, and every emulator). Every result still
 * goes through user review.
 */
class LabParserService {

    private val _status = MutableStateFlow<AiStatus>(AiStatus.Checking)
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    private val model: GenerativeModel? by lazy { runCatching { Generation.getClient() }.getOrNull() }

    suspend fun refreshStatus() {
        val m = model ?: run { _status.value = AiStatus.Unavailable; return }
        _status.value = runCatching {
            when (m.checkStatus()) {
                FeatureStatus.AVAILABLE -> AiStatus.Available
                FeatureStatus.DOWNLOADABLE -> AiStatus.Downloadable
                FeatureStatus.DOWNLOADING -> AiStatus.Downloading(0)
                else -> AiStatus.Unavailable
            }
        }.getOrElse { AiStatus.Unavailable }
    }

    /** Asks AICore to fetch Gemini Nano (system-managed download). */
    suspend fun download() {
        val m = model ?: return
        runCatching {
            m.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> _status.value = AiStatus.Downloading(0)
                    is DownloadStatus.DownloadProgress -> _status.value = AiStatus.Downloading(status.totalBytesDownloaded)
                    DownloadStatus.DownloadCompleted -> _status.value = AiStatus.Available
                    is DownloadStatus.DownloadFailed -> {
                        Log.w(TAG, "Gemini Nano download failed", status.e)
                        refreshStatus()
                    }
                }
            }
        }.onFailure { if (it is CancellationException) throw it; refreshStatus() }
    }

    /** Loads the model into memory while the user is still scanning. */
    suspend fun prewarm() {
        if (_status.value == AiStatus.Available) runCatching { model?.warmup() }
    }

    suspend fun parse(text: String, onProgress: (ParseProgress) -> Unit): ParseOutcome = withContext(Dispatchers.Default) {
        if (_status.value == AiStatus.Checking) refreshStatus()
        val m = model
        if (_status.value == AiStatus.Available && m != null) {
            try {
                val report = parseWithGemini(m, text, onProgress)
                if (report.entries.isNotEmpty()) {
                    return@withContext ParseOutcome(LabParsePipeline.postProcess(report), ParseEngine.GEMINI_NANO)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano parse failed; falling back to rules", e)
            }
        }
        val report = HeuristicLabParser.parse(text)
        onProgress(ParseProgress(report.entries.size, report.entries.lastOrNull()?.name))
        ParseOutcome(LabParsePipeline.postProcess(report, fuzzyMatchCodes = false), ParseEngine.RULES)
    }

    private suspend fun parseWithGemini(m: GenerativeModel, text: String, onProgress: (ParseProgress) -> Unit): AiLabReport {
        val systemPrompt = runCatching { m.isSystemPromptAvailable() }.getOrDefault(false)
        var completed = 0
        val parts = LabParsePrompt.chunks(text, MAX_CHUNK_CHARS).map { chunk ->
            val request = if (systemPrompt) {
                generateContentRequest(
                    SystemInstruction(LabParsePrompt.instructions + "\n\n" + LabParsePrompt.jsonContract),
                    TextPart(LabParsePrompt.prompt(chunk, includeContract = false)),
                ) { configure() }
            } else {
                generateContentRequest(TextPart(LabParsePrompt.prompt(chunk, includeContract = true))) { configure() }
            }
            val reply = StringBuilder()
            m.generateContentStream(request).collect { response ->
                reply.append(response.candidates.firstOrNull()?.text.orEmpty())
                val progress = LabParsePrompt.progress(reply.toString())
                onProgress(progress.copy(entryCount = completed + progress.entryCount))
            }
            LabParsePrompt.decode(reply.toString()).also { completed += it.entries.size }
        }
        return LabParsePrompt.merge(parts)
    }

    private fun com.google.mlkit.genai.prompt.GenerateContentRequest.Builder.configure() {
        temperature = 0.1f
        topK = 1
        maxOutputTokens = 2048
    }

    private companion object {
        const val TAG = "LabParserService"

        /** Gemini Nano's input limit is ~4k tokens; leave room for the instructions. */
        const val MAX_CHUNK_CHARS = 5_000
    }
}
