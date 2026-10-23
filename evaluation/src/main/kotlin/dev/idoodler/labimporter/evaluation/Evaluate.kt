package dev.idoodler.labimporter.evaluation

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import dev.idoodler.labimporter.core.parse.LabParsePrompt
import dev.idoodler.labimporter.core.parse.ParseResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.time.TimeSource

/*
 * Reads every sample report in evaluation/reports with an on-device model —
 * the app's exact instructions, prompt and JSON schema, then the shared
 * post-processing — and scores the values against the report's
 * `.expected.json`. Usage:
 *
 *   ./gradlew :evaluation:run --args="<model.litertlm> [gpu|cpu] [schema|text] [think]"
 *
 * `--args="replay"` re-scores the replies saved by the last run without the
 * model — quick when only the shared post-processing (LOINC lookup) changed.
 */

/** What a sample report contains (written by hand from the report text). */
@Serializable
data class Expected(
    val reportDate: String? = null,
    val patientName: String? = null,
    val values: List<ExpectedValue>,
    /** Results that aren't numbers (negative, "< 0,5", ">60") — they must not become numbers. */
    val notNumeric: List<ExpectedText> = emptyList(),
)

@Serializable
data class ExpectedValue(
    /** Words one of which the read test name or code must contain (any language form). */
    val names: List<String>,
    val value: Double,
    val unit: String,
    /** The LOINC code the shared resolver should arrive at, where it's unambiguous. */
    val loinc: String? = null,
    /** Other readings of the same line that are also right (e.g. a value printed in two units). */
    val alternatives: List<Alternative> = emptyList(),
)

@Serializable
data class Alternative(val value: Double, val unit: String)

@Serializable
data class ExpectedText(val names: List<String>)

/** Also list the LOINC every value resolved to where the sample doesn't state one. */
private val verbose = System.getProperty("eval.verbose") == "true"

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

fun main(args: Array<String>) {
    LabCore.start(checkNotNull(System.getProperty("loinc.db")) { "loinc.db missing" }, "en-US") { LabDisplayPreferences() }
    if (args.firstOrNull() == "replay") return replay()
    val modelPath = args.getOrNull(0) ?: "${System.getProperty("user.home")}/.cache/labimporter-eval/gemma-4-E2B-it.litertlm"
    val useGpu = args.getOrNull(1) != "cpu"
    val useSchema = args.getOrNull(2) != "text"
    val thinking = args.getOrNull(3) == "think"

    val loadStart = TimeSource.Monotonic.markNow()
    val engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = if (useGpu) Backend.GPU() else Backend.CPU(),
            maxNumTokens = MAX_TOKENS,
            cacheDir = File(System.getProperty("java.io.tmpdir"), "litertlm-cache").apply { mkdirs() }.absolutePath,
        ),
    )
    engine.initialize()
    println("Model ${File(modelPath).name} on ${if (useGpu) "GPU" else "CPU"}, output ${if (useSchema) "JSON schema" else "free text"}" +
        (if (thinking) ", thinking" else "") + " — loaded in ${loadStart.elapsedNow()}\n")

    val scores = reports().map { report ->
        val expected = expectedFor(report)
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(LabParsePrompt.instructions + "\n\n" + LabParsePrompt.jsonContract),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                thinkingConfig = ThinkingConfig(enableThinking = thinking),
                enableResponseFormat = useSchema,
            ),
        )
        val started = TimeSource.Monotonic.markNow()
        val message = conversation.sendMessage(
            LabParsePrompt.prompt(report.readText()),
            responseFormat = if (useSchema) ResponseFormat.json(LabParsePrompt.jsonSchema) else null,
        )
        val elapsed = started.elapsedNow()
        conversation.close()
        val reply = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        replyFile(report).apply { parentFile.mkdirs() }.writeText(reply)
        score(report.nameWithoutExtension, expected, LabParsePrompt.decodeJsonReply(reply)?.let { LabParsePipeline.postProcess(it, report.readText()) },
            elapsed.inWholeMilliseconds, reply.length)
    }
    engine.close()
    printTotal(scores)
}

private fun replay() {
    println("Replaying the saved replies\n")
    printTotal(
        reports().map { report ->
            val reply = replyFile(report).readText()
            score(report.nameWithoutExtension, expectedFor(report), LabParsePrompt.decodeJsonReply(reply)?.let { LabParsePipeline.postProcess(it, report.readText()) },
                0, reply.length)
        },
    )
}

private fun reports() = File("evaluation/reports").listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }

private fun expectedFor(report: File) =
    json.decodeFromString(Expected.serializer(), File(report.parentFile, report.nameWithoutExtension + ".expected.json").readText())

private fun replyFile(report: File) = File("evaluation/build/replies", report.nameWithoutExtension + ".reply.json")

private fun printTotal(scores: List<Score>) {
    val expectedCount = scores.sumOf { it.expected }
    val correct = scores.sumOf { it.correct }
    val read = scores.sumOf { it.readNumbers }
    println("\nTOTAL  correct ${correct}/$expectedCount (recall ${pct(correct, expectedCount)}), " +
        "extra numbers ${scores.sumOf { it.extra }}, precision ${pct(correct, read)}, " +
        "invalid replies ${scores.count { !it.valid }}, LOINC ${scores.sumOf { it.loincRight }}/${scores.sumOf { it.loincExpected }}, " +
        "median time ${scores.map { it.millis }.sorted()[scores.size / 2] / 1000.0} s")
}

private const val MAX_TOKENS = 4096

data class Score(
    val name: String,
    val valid: Boolean,
    val expected: Int,
    val correct: Int,
    val readNumbers: Int,
    val extra: Int,
    val loincExpected: Int,
    val loincRight: Int,
    val millis: Long,
)

private fun pct(a: Int, b: Int) = if (b == 0) "—" else "${(100.0 * a / b).toInt()}%"

private fun normalizeUnit(unit: String) = unit.lowercase().replace(" ", "").replace('µ', 'u').replace('μ', 'u')
    .replace("²", "2").replace(',', '.').replace("^", "")

private fun unitMatches(read: String, expected: String): Boolean {
    val a = normalizeUnit(read)
    val b = normalizeUnit(expected)
    return a == b || (b.isNotEmpty() && (a.startsWith(b) || b.startsWith(a) && a.isNotEmpty()))
}

private fun LabValue.matchesName(names: List<String>) =
    names.any { name.contains(it, ignoreCase = true) || code.contains(it, ignoreCase = true) }

private fun LabValue.matchesValue(value: Double, unit: String) =
    numericValue?.let { abs(it - value) < 1e-9 } == true && unitMatches(this.unit, unit)

private fun score(name: String, expected: Expected, result: ParseResult?, millis: Long, replyLength: Int): Score {
    val values = result?.values.orEmpty()
    val used = mutableSetOf<LabValue>()
    var correct = 0
    var loincRight = 0
    val problems = mutableListOf<String>()
    // A value is found by its number and unit (the read name is replaced by the catalog's name
    // in post-processing); its identity is then judged by the LOINC code it was resolved to.
    for (e in expected.values) {
        val options = listOf(Alternative(e.value, e.unit)) + e.alternatives
        val hit = values.firstOrNull { v -> v !in used && options.any { v.matchesValue(it.value, it.unit) } }
        if (hit != null) {
            used += hit
            correct++
            if (e.loinc != null && hit.code == e.loinc) loincRight++
            if (e.loinc != null && hit.code != e.loinc) problems += "LOINC ${e.names.first()}: ${hit.code} ${hit.name} (expected ${e.loinc})"
            if (e.loinc == null && verbose) problems += "unchecked ${e.names.first()}: ${hit.code} ${hit.name}"
        } else {
            problems += "MISSED ${e.names.first()} ${e.value} ${e.unit}"
        }
    }
    // Non-numeric results must never have been turned into numbers.
    for (t in expected.notNumeric) {
        values.firstOrNull { it !in used && it.matchesName(t.names) }?.let { v ->
            used += v
            if (v.numericValue != null) problems += "NUMBER FROM TEXT ${t.names.first()}: ${v.displayValue}"
        }
    }
    val extras = values.filter { it !in used && it.numericValue != null }
    extras.forEach { problems += "EXTRA ${it.name} (${it.code}) = ${it.displayValue} ${it.unit}" }
    if (expected.reportDate != null && result?.reportDate?.toString() != expected.reportDate) {
        problems += "DATE ${result?.reportDate} (expected ${expected.reportDate})"
    }
    if (expected.patientName != null && result?.patientName?.contains(expected.patientName, ignoreCase = true) != true) {
        problems += "PATIENT '${result?.patientName}' (expected ${expected.patientName})"
    }
    val loincExpected = expected.values.count { it.loinc != null }
    println("%-18s %s  %2d/%2d correct, %d extra, %.1f s, %d chars".format(
        name, if (result == null) "INVALID REPLY" else "ok", correct, expected.values.size, extras.size, millis / 1000.0, replyLength,
    ))
    problems.forEach { println("    $it") }
    return Score(
        name, result != null, expected.values.size, correct,
        values.count { it.numericValue != null }, extras.size, loincExpected, loincRight, millis,
    )
}
