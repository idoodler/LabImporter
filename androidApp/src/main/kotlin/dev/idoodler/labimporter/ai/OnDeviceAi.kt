package dev.idoodler.labimporter.ai

import kotlinx.coroutines.flow.StateFlow

/**
 * The on-device language model that reads lab reports — Gemini Nano on
 * Android, the counterpart of Apple Intelligence in the iOS app, or on phones
 * that don't offer Gemini Nano to apps the Gemma model the user chose to
 * download ([GemmaAi]). As on iOS there is no lesser fallback that would guess
 * values: when no model can be used the app says so
 * ([AiAvailability.Unsupported] blocks the app, like iOS's "Device Not
 * Supported"), and problems during a parse surface as an [AiException] the
 * user is told about.
 */
interface OnDeviceAi {
    /** Whether the model can be used right now; starts as [AiAvailability.Checking]. */
    val availability: StateFlow<AiAvailability>

    /**
     * Asks the system again — on launch and on resume — and starts the model
     * download when the device needs one.
     */
    suspend fun refresh()

    /** "Check Again" / "Try Again": like [refresh], and also retries what failed last time. */
    suspend fun tryAgain() = refresh()

    /** Loads the model ahead of a parse, while the user is still picking or scanning. */
    suspend fun prewarm()

    /**
     * Runs [prompt] under [instructions] and returns the complete reply,
     * reporting the reply so far through [onPartialReply] while it streams.
     *
     * @param responseSchema a JSON Schema the reply must follow, for models
     *   that can constrain their output to one (the instructions also spell it out).
     * @throws AiException when the model can't produce a complete reply.
     */
    suspend fun generate(instructions: String, prompt: String, responseSchema: String?, onPartialReply: (String) -> Unit): String
}

/** What the on-device model can do right now. */
sealed interface AiAvailability {
    /** Still asking the system. */
    data object Checking : AiAvailability

    /** Ready to read reports. */
    data object Ready : AiAvailability

    /**
     * Supported, but the model is still being fetched. [totalBytes] is null
     * until the download reports its size.
     */
    data class Preparing(
        val downloadedBytes: Long = 0,
        val totalBytes: Long? = null,
        val stage: PreparingStage = PreparingStage.DOWNLOADING,
    ) : AiAvailability {
        /** 0…1, or null while the size is unknown. */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }

    /** The model download stopped; [problem] says why. "Try Again" restarts it. */
    data class PreparationFailed(val problem: AiProblem) : AiAvailability

    /**
     * This device can't run the model (or can't yet). LabImporter can't work
     * without it — exactly like the iOS app on hardware without Apple
     * Intelligence. [detail] is the system's own answer (a status or error
     * code), shown as-is so the reason can be reported and checked.
     *
     * @param modelDownload the phone can run the Gemma model instead, once
     *   the user downloads it ([GemmaModel]).
     */
    data class Unsupported(
        val reason: UnsupportedReason,
        val detail: String? = null,
        val modelDownload: Boolean = false,
    ) : AiAvailability
}

/** What [AiAvailability.Preparing] is waiting for. */
enum class PreparingStage {
    DOWNLOADING,

    /** The download only runs on Wi-Fi (the default) and there is none. */
    WAITING_FOR_WIFI,

    /** No connection at all; the download continues once there is one. */
    WAITING_FOR_NETWORK,

    /** Checking the downloaded file against its pinned SHA-256. */
    VERIFYING,
}

enum class UnsupportedReason {
    /**
     * The system says this phone doesn't offer Gemini Nano to apps (ML Kit
     * Prompt API) — also phones that use Gemini Nano only for their own features.
     */
    DEVICE,

    /** Gemini Nano needs newer system software (Android, Play services or AICore). */
    UPDATE_REQUIRED,

    /**
     * The system didn't give an answer (no reply in time, an unexpected error,
     * no ML Kit client) — not a verdict on the phone, so it isn't called unsupported.
     */
    NOT_DETERMINED,
}

/** Why the model couldn't read a report (or get ready), as the user is told. */
enum class AiProblem {
    /** The report doesn't fit the model's context — the same advice as iOS. */
    DOCUMENT_TOO_LONG,

    /** AICore is busy with other requests (still, after retrying). */
    BUSY,

    /** The app used up its long-term (e.g. daily) on-device AI quota. */
    QUOTA_EXCEEDED,

    /** Android only runs the model for the app on screen. */
    BACKGROUND,

    /** No room to download the model. */
    NOT_ENOUGH_STORAGE,

    /** The model download failed, e.g. without an internet connection. */
    DOWNLOAD_FAILED,

    /** The model is no longer available on this device. */
    UNAVAILABLE,

    /** The downloaded model couldn't be loaded on this phone (or the app closed while it loaded). */
    LOAD_FAILED,

    /** Anything else: the model produced no usable reply. */
    FAILED,
}

class AiException(val problem: AiProblem, cause: Throwable? = null) : Exception(problem.name, cause)
