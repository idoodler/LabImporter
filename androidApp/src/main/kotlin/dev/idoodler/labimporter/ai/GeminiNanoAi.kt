package dev.idoodler.labimporter.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.GenAiException.ErrorCode
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Gemini Nano through the ML Kit GenAI Prompt API (AICore) — the Android
 * counterpart of Apple's Foundation Models.
 *
 * Availability follows the system's own status: AVAILABLE → ready;
 * DOWNLOADABLE/DOWNLOADING → the model is fetched right away (with progress);
 * UNAVAILABLE or an incompatible AICore → unsupported, which blocks the app.
 * Errors map to [AiProblem]s with actionable messages (see [GenAiErrors]).
 *
 * @param scope outlives screens — the model download runs in it.
 */
class GeminiNanoAi(
    private val scope: CoroutineScope,
    private val client: () -> GenerativeModel = { Generation.getClient() },
) : OnDeviceAi {

    private val state = MutableStateFlow<AiAvailability>(AiAvailability.Checking)
    override val availability: StateFlow<AiAvailability> = state.asStateFlow()

    /** Why no client could be created, for the unsupported screen's details. */
    private var clientFailure: String? = null

    private val model: GenerativeModel? by lazy {
        try {
            client()
        } catch (e: Exception) {
            Log.w(TAG, "No ML Kit GenAI client on this device", e)
            clientFailure = "ML Kit client: ${e.javaClass.simpleName}"
            null
        }
    }
    private var download: Job? = null

    override suspend fun refresh() {
        // "Check Again" on the unsupported screen shows that it's checking.
        if (state.value is AiAvailability.Unsupported) state.value = AiAvailability.Checking
        // Only the system's own answer makes a phone "unsupported"; anything that
        // isn't an answer (no client, no reply, an unexpected error) is "couldn't check".
        val model = model ?: return unsupported(UnsupportedReason.NOT_DETERMINED, clientFailure)
        val status = try {
            withTimeout(STATUS_TIMEOUT_MS) { model.checkStatus() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "AICore didn't report a status in time", e)
            return unsupported(UnsupportedReason.NOT_DETERMINED, "AICore: no status within ${STATUS_TIMEOUT_MS / 1000} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            Log.w(TAG, "checkStatus failed (${e.errorCode})", e)
            return unsupported(GenAiErrors.unsupportedReason(e.errorCode), "AICore: ${GenAiErrors.describe(e.errorCode)}")
        } catch (e: Exception) {
            Log.w(TAG, "checkStatus failed", e)
            return unsupported(UnsupportedReason.NOT_DETERMINED, "checkStatus: ${e.javaClass.simpleName}")
        }
        Log.i(TAG, "Gemini Nano feature status: $status")
        when (status) {
            FeatureStatus.AVAILABLE -> state.value = AiAvailability.Ready
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> startDownload(model)
            FeatureStatus.UNAVAILABLE -> unsupported(UnsupportedReason.DEVICE, "Prompt API: UNAVAILABLE")
            else -> unsupported(UnsupportedReason.NOT_DETERMINED, "Prompt API: status $status")
        }
    }

    private fun unsupported(reason: UnsupportedReason, detail: String?) {
        state.value = AiAvailability.Unsupported(reason, detail)
    }

    /** Gemini Nano is fetched by AICore; the flow reports its progress. */
    private fun startDownload(model: GenerativeModel) {
        if (download?.isActive == true) return
        if (state.value !is AiAvailability.Preparing) state.value = AiAvailability.Preparing()
        download = scope.launch {
            var total: Long? = null
            try {
                model.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            total = status.bytesToDownload.takeIf { it > 0 }
                            state.value = AiAvailability.Preparing(0, total)
                        }
                        is DownloadStatus.DownloadProgress -> state.value = AiAvailability.Preparing(status.totalBytesDownloaded, total)
                        is DownloadStatus.DownloadCompleted -> state.value = AiAvailability.Ready
                        is DownloadStatus.DownloadFailed -> {
                            Log.w(TAG, "Gemini Nano download failed (${status.e.errorCode})", status.e)
                            state.value = AiAvailability.PreparationFailed(GenAiErrors.downloadProblem(status.e.errorCode))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenAiException) {
                Log.w(TAG, "Gemini Nano download failed (${e.errorCode})", e)
                state.value = AiAvailability.PreparationFailed(GenAiErrors.downloadProblem(e.errorCode))
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano download failed", e)
                state.value = AiAvailability.PreparationFailed(AiProblem.DOWNLOAD_FAILED)
            }
            // The flow can end without a final event (e.g. AICore finished on its own).
            if (state.value is AiAvailability.Preparing) refresh()
        }
    }

    override suspend fun prewarm() {
        if (state.value != AiAvailability.Ready) return
        try {
            model?.warmup()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Warm-up skipped", e) // Only an optimisation; the parse loads the model itself.
        }
    }

    // The Prompt API has no JSON Schema mode; the instructions spell the reply out instead.
    override suspend fun generate(instructions: String, prompt: String, responseSchema: String?, onPartialReply: (String) -> Unit): String {
        val model = model ?: throw AiException(AiProblem.UNAVAILABLE)
        try {
            val request = request(model, instructions, prompt)
            ensureFits(model, request)
            var attempt = 0
            while (true) {
                try {
                    return stream(model, request, onPartialReply)
                } catch (e: GenAiException) {
                    // AICore rations inference per app: back off and retry a busy model.
                    if (e.errorCode != ErrorCode.BUSY || attempt == BUSY_RETRIES) throw e
                    delay(e.retryDelay.toMillis().takeIf { it > 0 } ?: (BUSY_BACKOFF_MS shl attempt))
                    attempt++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            throw e
        } catch (e: GenAiException) {
            Log.w(TAG, "Generation failed (${e.errorCode})", e)
            // The model went away (system update, configuration change): re-check so the app reflects it.
            if (GenAiErrors.affectsAvailability(e.errorCode)) scope.launch { refresh() }
            throw AiException(GenAiErrors.generationProblem(e.errorCode), e)
        } catch (e: Exception) {
            Log.w(TAG, "Generation failed", e)
            throw AiException(AiProblem.FAILED, e)
        }
    }

    private suspend fun request(model: GenerativeModel, instructions: String, prompt: String): GenerateContentRequest {
        val configure: GenerateContentRequest.Builder.() -> Unit = {
            temperature = 0.1f
            topK = 1
            maxOutputTokens = MAX_OUTPUT_TOKENS
        }
        val systemPrompt = try {
            model.isSystemPromptAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return if (systemPrompt) {
            generateContentRequest(SystemInstruction(instructions), TextPart(prompt), configure)
        } else {
            generateContentRequest(TextPart(instructions + "\n\n" + prompt), configure)
        }
    }

    /** Fails fast with the iOS "too long" advice instead of a truncated read. */
    private suspend fun ensureFits(model: GenerativeModel, request: GenerateContentRequest) {
        val fits = try {
            model.countTokens(request).totalTokens <= model.getTokenLimit()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Token count unavailable; relying on the model's own limit check", e)
            true
        }
        if (!fits) throw AiException(AiProblem.DOCUMENT_TOO_LONG)
    }

    private suspend fun stream(model: GenerativeModel, request: GenerateContentRequest, onPartialReply: (String) -> Unit): String {
        val reply = StringBuilder()
        var finishReason: Int? = null
        model.generateContentStream(request).collect { response ->
            val candidate = response.candidates.firstOrNull() ?: return@collect
            reply.append(candidate.text.orEmpty())
            candidate.finishReason?.let { finishReason = it }
            onPartialReply(reply.toString())
        }
        // A reply cut off at the output limit is an incomplete read, never a result.
        if (finishReason == Candidate.FinishReason.MAX_TOKENS) throw AiException(AiProblem.DOCUMENT_TOO_LONG)
        return reply.toString()
    }

    private companion object {
        const val TAG = "GeminiNanoAi"
        /** AICore binds and may fetch its configuration on the first call; give it time. */
        const val STATUS_TIMEOUT_MS = 30_000L
        const val BUSY_RETRIES = 3
        const val BUSY_BACKOFF_MS = 1_000L

        /** Room for a long panel; the ML Kit guidance is to stay well below 4K output tokens. */
        const val MAX_OUTPUT_TOKENS = 2_048
    }
}

/** How ML Kit GenAI error codes reach the user. */
object GenAiErrors {
    fun generationProblem(code: Int): AiProblem = when (code) {
        ErrorCode.REQUEST_TOO_LARGE, ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR -> AiProblem.DOCUMENT_TOO_LONG
        ErrorCode.BUSY -> AiProblem.BUSY
        ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> AiProblem.QUOTA_EXCEEDED
        ErrorCode.BACKGROUND_USE_BLOCKED -> AiProblem.BACKGROUND
        ErrorCode.NOT_ENOUGH_DISK_SPACE -> AiProblem.NOT_ENOUGH_STORAGE
        ErrorCode.NOT_AVAILABLE, ErrorCode.NOT_SUPPORTED, ErrorCode.NEEDS_SYSTEM_UPDATE, ErrorCode.AICORE_INCOMPATIBLE -> AiProblem.UNAVAILABLE
        else -> AiProblem.FAILED
    }

    fun downloadProblem(code: Int): AiProblem = when (code) {
        ErrorCode.NOT_ENOUGH_DISK_SPACE -> AiProblem.NOT_ENOUGH_STORAGE
        ErrorCode.NOT_AVAILABLE, ErrorCode.NOT_SUPPORTED, ErrorCode.NEEDS_SYSTEM_UPDATE, ErrorCode.AICORE_INCOMPATIBLE -> AiProblem.UNAVAILABLE
        else -> AiProblem.DOWNLOAD_FAILED
    }

    /** What a failed status check means: only "not available/supported" is a verdict on the phone. */
    fun unsupportedReason(code: Int): UnsupportedReason = when (code) {
        ErrorCode.NEEDS_SYSTEM_UPDATE, ErrorCode.AICORE_INCOMPATIBLE -> UnsupportedReason.UPDATE_REQUIRED
        ErrorCode.NOT_AVAILABLE, ErrorCode.NOT_SUPPORTED -> UnsupportedReason.DEVICE
        else -> UnsupportedReason.NOT_DETERMINED
    }

    /** An error code as ML Kit names it, for the details line (e.g. `604 NEEDS_SYSTEM_UPDATE`). */
    fun describe(code: Int): String = "$code ${NAMES[code] ?: "error"}"

    private val NAMES = mapOf(
        ErrorCode.UNKNOWN to "UNKNOWN", ErrorCode.NOT_AVAILABLE to "NOT_AVAILABLE", ErrorCode.NOT_SUPPORTED to "NOT_SUPPORTED",
        ErrorCode.BUSY to "BUSY", ErrorCode.NEEDS_SYSTEM_UPDATE to "NEEDS_SYSTEM_UPDATE",
        ErrorCode.AICORE_INCOMPATIBLE to "AICORE_INCOMPATIBLE", ErrorCode.NOT_ENOUGH_DISK_SPACE to "NOT_ENOUGH_DISK_SPACE",
        ErrorCode.CANCELLED to "CANCELLED", ErrorCode.BACKGROUND_USE_BLOCKED to "BACKGROUND_USE_BLOCKED",
        ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED to "PER_APP_BATTERY_USE_QUOTA_EXCEEDED",
    )

    fun affectsAvailability(code: Int): Boolean = generationProblem(code) == AiProblem.UNAVAILABLE
}
