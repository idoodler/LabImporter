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
 * The replies are committed (evaluation/replies), so CI replays them too;
 * `-Psummary=<file>` also writes the totals as JSON. `-Pheldout=true` reads the
 * held-out set (evaluation/heldout) instead, in either mode.
 */

/** What a sample report contains (written by hand from the report text). */
@Serializable
data class Expected(
    val reportDate: String? = null,
    val patientName: String? = null,
    val values: List<ExpectedValue>,
    /** Results that aren't numbers (negative, "< 0,5", ">60") — kept as text, never turned into numbers. */
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
data class ExpectedText(
    val names: List<String>,
    /**
     * The result as printed ("> 90", "<0,5"), where it's given: a reading of another one
     * (">1500" for ">150") is then wrong, not a text result kept. Without it, any text
     * result for the test counts.
     */
    val result: String? = null,
) {
    /** Whether [read] (a text result) is this one: the same after case, spacing and the decimal comma. */
    fun readsAs(read: String): Boolean = result == null || textForm(read) == textForm(result)
}

private fun textForm(text: String) = text.lowercase().filterNot { it.isWhitespace() }.replace(',', '.').let { form ->
    // "<0,30" is "<0.3": a threshold's number compared as a number.
    THRESHOLD_TEXT.matchEntire(form)?.let { "${it.groupValues[1]}${it.groupValues[2].toBigDecimal().stripTrailingZeros().toPlainString()}" } ?: form
}

private val THRESHOLD_TEXT = Regex("^([<>≤≥]=?)(\\d+(?:\\.\\d+)?)$")

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
        score(report.nameWithoutExtension, expected, LabParsePrompt.decodeJsonReply(reply)?.let { LabParsePipeline.postProcess(it, report.readText(), EVALUATION_DAY) },
            elapsed.inWholeMilliseconds, reply.length)
    }
    engine.close()
    printTotal(scores)
}

private fun replay() {
    println("Replaying the saved replies${if (heldOut) " of the held-out set" else ""}\n")
    printTotal(
        reports().map { report ->
            val reply = replyFile(report).readText()
            score(report.nameWithoutExtension, expectedFor(report), LabParsePrompt.decodeJsonReply(reply)?.let { LabParsePipeline.postProcess(it, report.readText(), EVALUATION_DAY) },
                0, reply.length)
        },
        mode = "replay",
    )
}

/**
 * `-Pheldout=true`: the held-out set (evaluation/heldout), scored to see how the Review check and the LOINC
 * ranking do on reports they weren't tuned on. Never change either to read it better (README.md, "Held-out set").
 */
private val heldOut = System.getProperty("eval.heldout") == "true"

/** The folder of the set being read: evaluation/ (the tuning set) or evaluation/heldout/. */
internal fun setFolder(heldOut: Boolean) = if (heldOut) "evaluation/heldout" else "evaluation"

private fun reports() = File(setFolder(heldOut), "reports").listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }

private fun expectedFor(report: File) =
    json.decodeFromString(Expected.serializer(), File(report.parentFile, report.nameWithoutExtension + ".expected.json").readText())

/**
 * Committed, so replay works on a fresh checkout (and in CI) without the model;
 * a model run overwrites them, and the diff shows what the model now reads differently.
 */
private fun replyFile(report: File) = File(File(setFolder(heldOut), "replies"), report.nameWithoutExtension + ".reply.json")

/** The totals as JSON, for tools/quality/scorecard.py and the CI floor (`-Psummary=<file>`). */
@Serializable
data class Summary(
    val mode: String,
    /** "tuning" (evaluation/reports, the set the check was built against) or "heldout" (evaluation/heldout/reports). */
    val set: String,
    val reports: Int,
    val invalidReplies: Int,
    val valuesExpected: Int,
    val valuesCorrect: Int,
    val numbersRead: Int,
    val extraNumbers: Int,
    /** Correct / expected and correct / read, 0…1; null when there's nothing to divide by. */
    val recall: Double?,
    val precision: Double?,
    val loincExpected: Int,
    val loincRight: Int,
    val textExpected: Int,
    val textKept: Int,
    val datesExpected: Int,
    val datesRight: Int,
    /** A date that is there but wrong, and not flagged: the silent kind. A missing or flagged date is visible in Review. */
    val datesWrong: Int,
    /** Dates Review asks the user to check (day and month either way, a later date printed), and how many of them are right. */
    val datesFlagged: Int,
    val rightDatesFlagged: Int,
    val wrongValuesFlagged: Int,
    val wrongValuesNotFlagged: Int,
    val rightValues: Int,
    val falseFlags: Int,
    /**
     * Planted errors (PlantedErrors.kt): faults planted in the right readings of the
     * committed replies, how many Review flagged, and the right values it flagged anyway.
     */
    val plantedErrors: Int,
    val plantedFlagged: Int,
    val plantedNotFlagged: Int,
    val plantedByKind: Map<String, KindScore>,
    val plantedControls: Int,
    val plantedFalseFlags: Int,
    /** The reports' right dates, and how many of them Review asks the user to check anyway. */
    val plantedDateControls: Int,
    val plantedDateFalseFlags: Int,
)

private fun ratio(a: Int, b: Int) = if (b == 0) null else a.toDouble() / b

/** Every report with its saved reply, for [plantErrors]; a report whose reply isn't valid has no right readings to plant in. */
private fun plantedReports() = reports().mapNotNull { report ->
    val reply = LabParsePrompt.decodeJsonReply(replyFile(report).readText()) ?: return@mapNotNull null
    PlantedReport(report.nameWithoutExtension, report.readText(), reply, expectedFor(report))
}

private fun printTotal(scores: List<Score>, mode: String = "model") {
    val expectedCount = scores.sumOf { it.expected }
    val correct = scores.sumOf { it.correct }
    val read = scores.sumOf { it.readNumbers }
    println("\nTOTAL  correct ${correct}/$expectedCount (recall ${pct(correct, expectedCount)}), " +
        "extra numbers ${scores.sumOf { it.extra }}, precision ${pct(correct, read)}, " +
        "invalid replies ${scores.count { !it.valid }}, LOINC ${scores.sumOf { it.loincRight }}/${scores.sumOf { it.loincExpected }}, " +
        "text results ${scores.sumOf { it.textKept }}/${scores.sumOf { it.textExpected }}, " +
        "dates ${scores.count { it.dateRight }}/${scores.count { it.dateExpected }} " +
        "(flagged ${scores.count { it.dateFlagged }}, right ones ${scores.count { it.dateFlagged && it.dateRight }}; " +
        "wrong unflagged ${scores.count { it.dateWrong }}), " +
        "median time ${scores.map { it.millis }.sorted()[scores.size / 2] / 1000.0} s")
    println("SOURCE CHECK  wrong values flagged ${scores.sumOf { it.wrongFlagged }}, " +
        "wrong values NOT flagged ${scores.sumOf { it.wrongNotFlagged }}, " +
        "right values falsely flagged ${scores.sumOf { it.rightFlagged }}/${scores.sumOf { it.rightValues }}")
    println("\nPlanting errors in the right readings")
    val planted = plantErrors(plantedReports())
    println("PLANTED ERRORS  flagged ${planted.flagged}/${planted.planted} (${pct(planted.flagged, planted.planted)}), " +
        "right values falsely flagged ${planted.falseFlags}/${planted.controls}, " +
        "right dates flagged ${planted.dateFalseFlags}/${planted.dateControls}")
    println("    " + planted.byKind.entries.joinToString(", ") { (kind, s) -> "${Fault.valueOf(kind).label} ${s.flagged}/${s.planted}" })
    val summaryPath = System.getProperty("eval.summary")?.takeIf { it.isNotBlank() } ?: return
    val summary = Summary(
        mode = mode,
        set = if (heldOut) "heldout" else "tuning",
        reports = scores.size,
        invalidReplies = scores.count { !it.valid },
        valuesExpected = expectedCount,
        valuesCorrect = correct,
        numbersRead = read,
        extraNumbers = scores.sumOf { it.extra },
        recall = ratio(correct, expectedCount),
        precision = ratio(correct, read),
        loincExpected = scores.sumOf { it.loincExpected },
        loincRight = scores.sumOf { it.loincRight },
        textExpected = scores.sumOf { it.textExpected },
        textKept = scores.sumOf { it.textKept },
        datesExpected = scores.count { it.dateExpected },
        datesRight = scores.count { it.dateRight },
        datesWrong = scores.count { it.dateWrong },
        datesFlagged = scores.count { it.dateFlagged },
        rightDatesFlagged = scores.count { it.dateFlagged && it.dateRight },
        wrongValuesFlagged = scores.sumOf { it.wrongFlagged },
        wrongValuesNotFlagged = scores.sumOf { it.wrongNotFlagged },
        rightValues = scores.sumOf { it.rightValues },
        falseFlags = scores.sumOf { it.rightFlagged },
        plantedErrors = planted.planted,
        plantedFlagged = planted.flagged,
        plantedNotFlagged = planted.planted - planted.flagged,
        plantedByKind = planted.byKind,
        plantedControls = planted.controls,
        plantedFalseFlags = planted.falseFlags,
        plantedDateControls = planted.dateControls,
        plantedDateFalseFlags = planted.dateFalseFlags,
    )
    File(summaryPath).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(Summary.serializer(), summary) + "\n")
    println("Summary written to $summaryPath")
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
    /** Results that aren't numbers, and how many of them were kept as a qualitative result to save. */
    val textExpected: Int = 0,
    val textKept: Int = 0,
    /** Review's "compare with the report" badge: did it land on the values that are wrong? */
    val rightValues: Int = 0,
    val rightFlagged: Int = 0,
    val wrongFlagged: Int = 0,
    val wrongNotFlagged: Int = 0,
    /** The report prints a date; the read one is it, or another one unflagged (a missing or flagged date isn't silently wrong). */
    val dateExpected: Boolean = false,
    val dateRight: Boolean = false,
    val dateWrong: Boolean = false,
    /** Review asks the user to check the date (`ParseResult.reportDateNeedsCheck`). */
    val dateFlagged: Boolean = false,
)

private fun pct(a: Int, b: Int) = if (b == 0) "—" else "${(100.0 * a / b).toInt()}%"

private val SUPERSCRIPTS = mapOf('⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9', '⁻' to '-')

/** A count prefix standing alone before the slash: "G/L" is 10⁹ cells a litre, never "g/L" (grams); "T/L" is 10¹². */
private val COUNT_PREFIX = Regex("(?<![A-Za-z])([GT])(?=/)")

/**
 * A unit as a clinician reads it: case, spacing, µ/u, superscripts ("10³", "10^3") and the decimal comma don't
 * matter — except a count prefix's capital ("G/L" is "10^9/L", not "g/L"), read before the case goes.
 */
internal fun normalizeUnit(unit: String) = COUNT_PREFIX.replace(unit) { if (it.value == "G") "10^9" else "10^12" }
    .lowercase().filterNot { it.isWhitespace() }
    .map { SUPERSCRIPTS[it] ?: if (it == 'µ' || it == 'μ') 'u' else it }.joinToString("")
    .replace(',', '.').replace("^", "")

/** The same unit after [normalizeUnit]: a cut-off one ("mg" for "mg/dl", "/l" for "U/l") is wrong. */
internal fun unitMatches(read: String, expected: String) = normalizeUnit(read) == normalizeUnit(expected)

internal fun LabValue.matchesName(names: List<String>) =
    names.any { name.contains(it, ignoreCase = true) || code.contains(it, ignoreCase = true) }

internal fun LabValue.matchesValue(value: Double, unit: String) =
    numericValue?.let { abs(it - value) < 1e-9 } == true && unitMatches(this.unit, unit)

private fun score(name: String, expected: Expected, result: ParseResult?, millis: Long, replyLength: Int): Score {
    val values = result?.values.orEmpty()
    val used = mutableSetOf<LabValue>()
    // What the expected values match is right; a number or text result that matches nothing is wrong.
    val right = mutableListOf<LabValue>()
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
            right += hit
            correct++
            if (e.loinc != null && hit.code == e.loinc) loincRight++
            if (e.loinc != null && hit.code != e.loinc) problems += "LOINC ${e.names.first()}: ${hit.code} ${hit.name} (expected ${e.loinc})"
            if (e.loinc == null && verbose) problems += "unchecked ${e.names.first()}: ${hit.code} ${hit.name}"
        } else {
            problems += "MISSED ${e.names.first()} ${e.value} ${e.unit}"
        }
    }
    // Non-numeric results must never have been turned into numbers, and are saved as text.
    var textKept = 0
    for (t in expected.notNumeric) {
        val v = values.firstOrNull { it !in used && it.matchesName(t.names) }
        when {
            v == null -> problems += "MISSED TEXT ${t.names.first()}"
            v.numericValue != null -> problems += "NUMBER FROM TEXT ${t.names.first()}: ${v.displayValue}"
            v.qualitativeResult != null && t.readsAs(v.displayValue) -> {
                textKept++
                right += v
            }
            // Another text than the printed one: wrong, scored with the wrong values below.
            v.qualitativeResult != null -> problems += "WRONG TEXT ${t.names.first()}: '${v.displayValue}' (expected '${t.result}')"
            else -> problems += "TEXT NOT KEPT ${t.names.first()}: '${v.displayValue}'"
        }
        if (v != null && (v.qualitativeResult == null || t.readsAs(v.displayValue))) used += v
    }
    if (verbose) values.filter { it !in used && it.numericValue == null }.forEach { problems += "unscored ${it.name} (${it.code}) = '${it.displayValue}'" }
    val extras = values.filter { it !in used && it.numericValue != null }
    extras.forEach { problems += "EXTRA ${it.name} (${it.code}) = ${it.displayValue} ${it.unit}" }
    if (expected.reportDate != null && result?.reportDate?.toString() != expected.reportDate) {
        problems += "DATE ${result?.reportDate}${if (result?.reportDateNeedsCheck == true) ", flagged" else ""} (expected ${expected.reportDate})"
    }
    if (expected.patientName != null && result?.patientName?.contains(expected.patientName, ignoreCase = true) != true) {
        problems += "PATIENT '${result?.patientName}' (expected ${expected.patientName})"
    }
    val wrong = values.filter { it !in used && it.hasResult }
    right.filter { it.needsSourceCheck }.forEach { problems += "FALSE ALARM ${it.name} = ${it.displayValue} ${it.unit}" }
    wrong.forEach { problems += "WRONG, ${if (it.needsSourceCheck) "flagged" else "NOT flagged"}: ${it.name} = ${it.displayValue} ${it.unit}" }
    val loincExpected = expected.values.count { it.loinc != null }
    println("%-18s %s  %2d/%2d correct, %d extra, %.1f s, %d chars".format(
        name, if (result == null) "INVALID REPLY" else "ok", correct, expected.values.size, extras.size, millis / 1000.0, replyLength,
    ))
    problems.forEach { println("    $it") }
    return Score(
        name, result != null, expected.values.size, correct,
        values.count { it.numericValue != null }, extras.size, loincExpected, loincRight, millis,
        expected.notNumeric.size, textKept,
        right.size, right.count { it.needsSourceCheck }, wrong.count { it.needsSourceCheck }, wrong.count { !it.needsSourceCheck },
        dateExpected = expected.reportDate != null,
        dateRight = expected.reportDate != null && result?.reportDate?.toString() == expected.reportDate,
        dateWrong = expected.reportDate != null && result?.reportDate != null && result.reportDate.toString() != expected.reportDate &&
            !result.reportDateNeedsCheck,
        dateFlagged = result?.reportDate != null && result.reportDateNeedsCheck,
    )
}
