package dev.idoodler.labimporter.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A scripted on-device model for tests. */
class FakeAi(initial: AiAvailability = AiAvailability.Ready) : OnDeviceAi {
    val state = MutableStateFlow(initial)
    override val availability: StateFlow<AiAvailability> = state

    var refreshes = 0
        private set

    /** Runs on every [refresh] (e.g. to settle a "Check Again"). */
    var onRefresh: FakeAi.() -> Unit = {}

    /** What the next [generate] returns — or throws. */
    var reply: () -> String = { error("No reply scripted") }

    /** Every (instructions, prompt) the model was asked. */
    val requests = mutableListOf<Pair<String, String>>()

    /** The response schema of every request. */
    val schemas = mutableListOf<String?>()

    var tryAgains = 0
        private set

    override suspend fun refresh() {
        refreshes++
        onRefresh()
    }

    override suspend fun tryAgain() {
        tryAgains++
        refresh()
    }

    override suspend fun prewarm() = Unit

    override suspend fun generate(instructions: String, prompt: String, responseSchema: String?, onPartialReply: (String) -> Unit): String {
        requests += instructions to prompt
        schemas += responseSchema
        val full = reply()
        onPartialReply(full.take(full.length / 2))
        onPartialReply(full)
        return full
    }

    companion object {
        /** A reply in the shared JSON contract: entries are (code, name, rawValue, unit). */
        fun replyJson(
            vararg entries: List<String>,
            reportDate: String = "2026-05-06",
            patientName: String = "Max Mustermann",
            authorName: String = "Labor Dr. Muster",
            language: String = "de",
        ): String = Json.encodeToString(
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
                put("reportDate", reportDate)
                put("patientName", patientName)
                put("authorName", authorName)
                put("reportLanguage", language)
            },
        )
    }
}
