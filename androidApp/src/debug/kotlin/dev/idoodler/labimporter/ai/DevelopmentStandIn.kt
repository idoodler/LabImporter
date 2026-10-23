package dev.idoodler.labimporter.ai

import dev.idoodler.labimporter.ui.preview.PreviewSamples
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Debug builds only. Emulators have no Gemini Nano, so — like a release build
 * on an unsupported phone — the app opens on "Device Not Supported". From
 * there a developer can opt into this stand-in to try everything after the
 * model on an emulator. It never reaches a release build.
 */
object DevelopmentStandIn {
    val texts: StandInTexts? = StandInTexts(
        use = "Use Development Stand-in",
        explanation = "Debug build only. Imports return sample values instead of reading the document, so the rest " +
            "of the app can be tried on an emulator.",
        reviewBanner = "Development stand-in: these are sample values, not read from your document.",
        active = "Development stand-in (debug build). Imports return sample values instead of reading the document.",
        useGemini = "Use Gemini Nano",
        insertSample = "Insert Sample Report",
    )

    /** A report to paste, for trying the real model (or the stand-in) without a document at hand. */
    val sampleReport: String? = PreviewSamples.SAMPLE_REPORT_TEXT

    fun create(): OnDeviceAi? = DevelopmentAi()
}

/**
 * Streams a fixed, realistic model reply — a German panel with the vendor
 * mnemonics the real prompt is tuned for — whatever the document says. The
 * shared post-processing (numbers, LOINC resolution, de-duplication), review,
 * saving and the dashboard then run exactly as with Gemini Nano. Values vary a
 * little per import so repeated imports draw trends.
 */
internal class DevelopmentAi : OnDeviceAi {
    private val state = MutableStateFlow<AiAvailability>(AiAvailability.Ready)
    override val availability: StateFlow<AiAvailability> = state.asStateFlow()

    override suspend fun refresh() = Unit

    override suspend fun prewarm() = Unit

    override suspend fun generate(instructions: String, prompt: String, responseSchema: String?, onPartialReply: (String) -> Unit): String {
        val reply = sampleReply(Random(prompt.hashCode() xor Clock.System.now().epochSeconds.toInt()))
        // Stream value by value, like the model, so the progress dialog behaves as on a phone.
        var from = 0
        while (true) {
            val end = reply.indexOf("},", from).takeIf { it >= 0 }?.plus(2) ?: break
            onPartialReply(reply.substring(0, end))
            delay(STEP_DELAY_MS)
            from = end
        }
        onPartialReply(reply)
        return reply
    }

    private fun sampleReply(random: Random): String {
        fun vary(base: Double, decimals: Int): String {
            val value = base * (1 + (random.nextDouble() - 0.5) * 0.2)
            return "%.${decimals}f".format(java.util.Locale.GERMANY, value)
        }
        val entries = listOf(
            listOf("KREA", "Kreatinin", vary(0.82, 2), "mg/dl"),
            listOf("HB-A1C", "HbA1c", vary(5.6, 1), "%"),
            listOf("GPT", "Alanin-Aminotransferase", vary(23.0, 0), "U/l"),
            listOf("CHOL", "Cholesterin", vary(196.0, 0), "mg/dl"),
            listOf("NONHDL", "Non-HDL-Cholesterin", vary(138.0, 0), "mg/dl"),
            listOf("TSH-0", "Thyreotropin", vary(1.9, 2), "mU/l"),
            listOf("FERR", "Ferritin", vary(85.0, 0), "ng/ml"),
            listOf("MALB-U", "Albumin im Urin", "-", "mg/l"),
        )
        return Json.encodeToString(
            buildJsonObject {
                putJsonArray("entries") {
                    entries.forEach { (code, name, value, unit) ->
                        addJsonObject {
                            put("code", code)
                            put("name", name)
                            put("rawValue", value)
                            put("unit", unit)
                        }
                    }
                }
                put("reportDate", Clock.System.todayIn(TimeZone.currentSystemDefault()).toString())
                put("patientName", "")
                put("authorName", "Development stand-in")
                put("reportLanguage", "de")
            },
        )
    }

    private companion object {
        const val STEP_DELAY_MS = 120L
    }
}
