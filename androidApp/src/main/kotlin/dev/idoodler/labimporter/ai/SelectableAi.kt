package dev.idoodler.labimporter.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn

/** Which model read a report; the review screen labels it. */
enum class AiEngine { GEMINI_NANO, GEMMA, DEVELOPMENT_STAND_IN }

/**
 * Gemini Nano wherever the system offers it; on phones where it says Gemini
 * Nano is unsupported, the Gemma model the user can download ([gemma]); and,
 * in debug builds only, a development stand-in the developer opts into on the
 * unsupported-device screen (emulators have no Gemini Nano). Release builds
 * have no stand-in ([standIn] is null).
 */
class SelectableAi(
    private val gemini: OnDeviceAi,
    private val gemma: GemmaAi?,
    private val standIn: OnDeviceAi?,
    useStandIn: Boolean,
    scope: CoroutineScope,
) : OnDeviceAi {

    private val usingStandIn = MutableStateFlow(useStandIn && standIn != null)

    /** True while the development stand-in replaces the real models. */
    val isUsingStandIn: StateFlow<Boolean> = usingStandIn.asStateFlow()

    private val gemmaAvailability: StateFlow<AiAvailability> =
        gemma?.availability ?: MutableStateFlow(AiAvailability.Unsupported(UnsupportedReason.DEVICE))

    /** The downloaded Gemma model is on the phone (it can be removed in Settings). */
    val gemmaInstalled: StateFlow<Boolean> = gemma?.installed ?: MutableStateFlow(false)

    override val availability: StateFlow<AiAvailability> =
        combine(usingStandIn, gemini.availability, gemmaAvailability, ::merged)
            .stateIn(scope, SharingStarted.Eagerly, merged(usingStandIn.value, gemini.availability.value, gemmaAvailability.value))

    private fun merged(stand: Boolean, real: AiAvailability, downloadable: AiAvailability): AiAvailability = when {
        stand -> AiAvailability.Ready
        real !is AiAvailability.Unsupported -> real
        // The system's own verdict on Gemini Nano stays the reason; Gemma adds whether a download can help.
        downloadable is AiAvailability.Unsupported -> real.copy(modelDownload = downloadable.modelDownload)
        else -> downloadable
    }

    val engine: AiEngine
        get() = when {
            usingStandIn.value -> AiEngine.DEVELOPMENT_STAND_IN
            gemma != null && gemini.availability.value is AiAvailability.Unsupported -> AiEngine.GEMMA
            else -> AiEngine.GEMINI_NANO
        }

    fun useStandIn(enabled: Boolean) {
        usingStandIn.value = enabled && standIn != null
    }

    private val active: OnDeviceAi
        get() = when (engine) {
            AiEngine.DEVELOPMENT_STAND_IN -> standIn ?: gemini
            AiEngine.GEMMA -> gemma ?: gemini
            AiEngine.GEMINI_NANO -> gemini
        }

    override suspend fun refresh() {
        if (usingStandIn.value) return standIn?.refresh() ?: Unit
        gemini.refresh()
        gemma?.refresh()
    }

    override suspend fun tryAgain() {
        if (usingStandIn.value) return standIn?.tryAgain() ?: Unit
        gemini.tryAgain()
        // Retry what failed with Gemma only where Gemma is the model in use.
        gemma?.let { if (gemini.availability.value is AiAvailability.Unsupported) it.tryAgain() else it.refresh() }
    }

    /** The user's go-ahead to download Gemma. */
    suspend fun downloadModel(allowMobileData: Boolean) = gemma?.download(allowMobileData) ?: Unit

    /** Cancels the Gemma download or deletes the model. */
    suspend fun removeModel() = gemma?.remove() ?: Unit

    /** The background job's part once the system download completes (see [GemmaAi.finishDownload]). */
    suspend fun finishModelDownload() = gemma?.finishDownload() ?: Unit

    /** Steps of the Gemma download, for notifications while the app isn't on screen. */
    val modelEvents: Flow<ModelEvent> = gemma?.events ?: emptyFlow()

    fun onUiHidden() = gemma?.onUiHidden() ?: Unit

    fun onUiVisible() = gemma?.onUiVisible() ?: Unit

    override suspend fun prewarm() = active.prewarm()

    override suspend fun generate(instructions: String, prompt: String, responseSchema: String?, onPartialReply: (String) -> Unit): String =
        active.generate(instructions, prompt, responseSchema, onPartialReply)
}
