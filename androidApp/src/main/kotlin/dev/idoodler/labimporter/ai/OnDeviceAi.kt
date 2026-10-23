package dev.idoodler.labimporter.ai

import kotlinx.coroutines.flow.StateFlow

/**
 * The on-device language model that reads lab reports — Gemini Nano on
 * Android, the counterpart of Apple Intelligence in the iOS app. As on iOS
 * there is no lesser fallback that would guess values: when the model can't
 * be used the app says so ([AiAvailability.Unsupported] blocks the app, like
 * iOS's "Device Not Supported"), and problems during a parse surface as an
 * [AiException] the user is told about.
 */
interface OnDeviceAi {
    /** Whether the model can be used right now; starts as [AiAvailability.Checking]. */
    val availability: StateFlow<AiAvailability>

    /**
     * Asks the system again — on launch, on resume and from "Check Again" —
     * and starts the model download when the device needs one.
     */
    suspend fun refresh()

    /** Loads the model ahead of a parse, while the user is still picking or scanning. */
    suspend fun prewarm()

    /**
     * Runs [prompt] under [instructions] and returns the complete reply,
     * reporting the reply so far through [onPartialReply] while it streams.
     *
     * @throws AiException when the model can't produce a complete reply.
     */
    suspend fun generate(instructions: String, prompt: String, onPartialReply: (String) -> Unit): String
}

/** What the on-device model can do right now. */
sealed interface AiAvailability {
    /** Still asking the system. */
    data object Checking : AiAvailability

    /** Ready to read reports. */
    data object Ready : AiAvailability

    /**
     * Supported, but the system is still fetching the model. [totalBytes] is
     * null until the download reports its size.
     */
    data class Preparing(val downloadedBytes: Long = 0, val totalBytes: Long? = null) : AiAvailability {
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
     */
    data class Unsupported(val reason: UnsupportedReason, val detail: String? = null) : AiAvailability
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

    /** Anything else: the model produced no usable reply. */
    FAILED,
}

class AiException(val problem: AiProblem, cause: Throwable? = null) : Exception(problem.name, cause)
