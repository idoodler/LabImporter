package dev.idoodler.labimporter.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Which model read a report; the review screen labels it. */
enum class AiEngine { GEMINI_NANO, DEVELOPMENT_STAND_IN }

/**
 * Gemini Nano — or, in debug builds only, a development stand-in the
 * developer opts into on the unsupported-device screen (emulators have no
 * Gemini Nano). Release builds have no stand-in ([standIn] is null), so their
 * only path is the real model and its real availability.
 */
class SelectableAi(
    private val gemini: OnDeviceAi,
    private val standIn: OnDeviceAi?,
    useStandIn: Boolean,
    scope: CoroutineScope,
) : OnDeviceAi {

    private val usingStandIn = MutableStateFlow(useStandIn && standIn != null)

    /** True while the development stand-in replaces Gemini Nano. */
    val isUsingStandIn: StateFlow<Boolean> = usingStandIn.asStateFlow()

    val engine: AiEngine get() = if (usingStandIn.value) AiEngine.DEVELOPMENT_STAND_IN else AiEngine.GEMINI_NANO

    override val availability: StateFlow<AiAvailability> =
        combine(usingStandIn, gemini.availability) { stand, real -> if (stand) AiAvailability.Ready else real }
            .stateIn(scope, SharingStarted.Eagerly, if (usingStandIn.value) AiAvailability.Ready else gemini.availability.value)

    fun useStandIn(enabled: Boolean) {
        usingStandIn.value = enabled && standIn != null
    }

    private val active: OnDeviceAi get() = if (usingStandIn.value) standIn ?: gemini else gemini

    override suspend fun refresh() = active.refresh()

    override suspend fun prewarm() = active.prewarm()

    override suspend fun generate(instructions: String, prompt: String, onPartialReply: (String) -> Unit): String =
        active.generate(instructions, prompt, onPartialReply)
}
