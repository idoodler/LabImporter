package dev.idoodler.labimporter.core.parse

import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.deduplicatedByLoinc
import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The structured shape the on-device model is asked to produce. Same fields as
 * the iOS `@Generable AILabReport`, so both platforms post-process identically.
 */
@Serializable
data class AiLabReport(
    val entries: List<AiLabEntry> = emptyList(),
    val reportDate: String = "",
    val patientName: String = "",
    val authorName: String = "",
    val reportLanguage: String = "",
)

@Serializable
data class AiLabEntry(
    val code: String = "",
    val name: String = "",
    val rawValue: String = "",
    val unit: String = "",
)

/** The reviewed-ready outcome of a parse. */
data class ParseResult(
    val values: List<LabValue>,
    val reportDate: LocalDate?,
    val patientName: String?,
    val authorName: String?,
)

/** Live progress while a model streams: entries so far and the latest test name. */
data class ParseProgress(val entryCount: Int, val latestName: String?)

/**
 * Prompt text shared by every model backend (Foundation Models on iOS, Gemini
 * Nano on Android). Keep the guidance concrete and example-driven — it
 * directly steers extraction quality.
 */
object LabParsePrompt {

    val instructions: String = """
        You are a medical lab report parser. Extract every lab test entry from the provided text.
        Lab reports follow the pattern: CODE: value unit; CODE2: value2 unit2; ...
        Preserve codes exactly as printed in `code`. For each entry, also give the test's standard name in the
        report's own language (do NOT translate it) so it can be matched to a coding system in that language.
        German practice software (e.g. PatMed) prints terse vendor mnemonics like KREA, MALB-U, MDRD, HB-A1C,
        C-PEPT, GADAAK or TSH-0 — keep the mnemonic in `code`, but always expand it to its full standard
        clinical name in `name`; never leave `name` as the raw mnemonic
        (KREA→Kreatinin, HARNS→Harnsäure, MALB-U→Albumin im Urin, MDRD→glomeruläre Filtrationsrate (eGFR),
        HB-A1C→HbA1c, C-PEPT→C-Peptid, GADAAK→Glutamatdecarboxylase-Antikörper, TSH-0→Thyreotropin).
        Do NOT emit specimen, material, or annotation labels as entries — a "value" of 'Serum', 'EDTA-Plasma',
        'Vollblut' or a standalone method tag is not a measured test and must be skipped.
        Detect the language of the report and return it as a BCP-47 code (e.g. 'de', 'en') in reportLanguage.
        Use '-' as rawValue when the result is negative or not detected; otherwise copy the value exactly as printed.
        If you can see a report date or blood draw date, return it in yyyy-MM-dd format; otherwise return an empty string.
        Extract the patient's full name if visible; otherwise return an empty string for patientName.
        Extract the lab or doctor name if visible; otherwise return an empty string for authorName.
    """.trimIndent()

    /** The JSON contract, for models without native structured output. */
    val jsonContract: String = """
        Respond with ONLY a single JSON object, no prose and no code fences, in exactly this shape:
        {"entries":[{"code":"KREA","name":"Kreatinin","rawValue":"0,80","unit":"mg/dl"}],
         "reportDate":"2026-05-06","patientName":"","authorName":"","reportLanguage":"de"}
    """.trimIndent()

    /**
     * Wraps the report in plain English so language detection has an anchor
     * even for terse, code-heavy reports (names stay in the report's language).
     */
    fun prompt(reportText: String, includeContract: Boolean): String = buildString {
        if (includeContract) {
            append(instructions).append("\n\n").append(jsonContract).append("\n\n")
        }
        append("You are reading a laboratory test report. The report text below may be very short ")
        append("and consist mostly of abbreviated test codes, numbers and units. Read it carefully ")
        append("and extract every laboratory test value it contains. Here is the report text:\n\n")
        append(reportText)
    }

    /**
     * Splits long text at line boundaries into chunks of at most [maxChars] —
     * small on-device models have short context windows (Gemini Nano: ~4k tokens).
     */
    fun chunks(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.lines()) {
            if (current.length + line.length + 1 > maxChars && current.isNotEmpty()) {
                result += current.toString(); current.clear()
            }
            current.append(line.take(maxChars)).append('\n')
        }
        if (current.isNotBlank()) result += current.toString()
        return result
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Decodes a model's JSON reply, tolerating code fences and surrounding prose. */
    fun decode(response: String): AiLabReport {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) return AiLabReport()
        return runCatching { json.decodeFromString(AiLabReport.serializer(), response.substring(start, end + 1)) }
            .getOrElse { AiLabReport() }
    }

    private val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]*)")

    /** Progress from a partially streamed JSON reply. */
    fun progress(partial: String): ParseProgress {
        val names = nameRegex.findAll(partial).map { it.groupValues[1] }.toList()
        return ParseProgress(entryCount = names.size, latestName = names.lastOrNull()?.takeIf { it.isNotBlank() })
    }

    /** Merges per-chunk results: entries concatenated, first non-empty metadata wins. */
    fun merge(parts: List<AiLabReport>): AiLabReport = AiLabReport(
        entries = parts.flatMap { it.entries },
        reportDate = parts.firstOrNull { it.reportDate.isNotBlank() }?.reportDate.orEmpty(),
        patientName = parts.firstOrNull { it.patientName.isNotBlank() }?.patientName.orEmpty(),
        authorName = parts.firstOrNull { it.authorName.isNotBlank() }?.authorName.orEmpty(),
        reportLanguage = parts.firstOrNull { it.reportLanguage.isNotBlank() }?.reportLanguage.orEmpty(),
    )
}

/** Turns a model's raw extraction into reviewable, LOINC-resolved values. */
object LabParsePipeline {

    /**
     * @param fuzzyMatchCodes whether the printed code may also be fuzzy-matched
     *   when the name finds nothing. Right for model output (codes come with an
     *   expanded name); off for the rule-based reader, where a bare mnemonic
     *   like `KREA` prefix-matches unrelated terms — better unmapped than wrong.
     */
    fun postProcess(report: AiLabReport, fuzzyMatchCodes: Boolean = true): ParseResult {
        val language = report.reportLanguage.ifBlank { guessLanguage(report.entries.map { it.name }) }
        val values = report.entries
            .filter { it.code.isNotBlank() || it.name.isNotBlank() }
            .map { entry ->
                val numeric = if (entry.rawValue.trim() == "-") null else NumberFormatting.parse(cleanValue(entry.rawValue))
                val (code, suggested) = resolveLoinc(entry.code, entry.name, language, fuzzyMatchCodes)
                val display = LabMapping.displayName(code)
                LabValue(
                    code = code,
                    name = if (display == code) entry.name.ifBlank { entry.code } else display,
                    displayValue = entry.rawValue.trim(),
                    numericValue = numeric,
                    unit = entry.unit.trim(),
                    isSuggestedCode = suggested,
                )
            }
        return ParseResult(
            values = values.deduplicatedByLoinc(),
            reportDate = parseDate(report.reportDate),
            patientName = report.patientName.trim().ifEmpty { null },
            authorName = report.authorName.trim().ifEmpty { null },
        )
    }

    /**
     * Resolves a parsed entry to LOINC from the bundled catalog: a valid LOINC
     * passes through; otherwise the AI's test name (then the printed code) is
     * matched in the report's language (English fallback) and the top hit is
     * offered as a *suggestion*. Unresolved entries keep the printed text.
     */
    fun resolveLoinc(printed: String, name: String, language: String, fuzzyMatchCodes: Boolean = true): Pair<String, Boolean> {
        val directory = LabCore.loinc ?: return printed to false
        val trimmedPrinted = printed.trim()
        if (directory.isKnownLoinc(trimmedPrinted)) return trimmedPrinted to false
        val lang = directory.resolvedLanguage(language)
        for (query in if (fuzzyMatchCodes) listOf(name, printed) else listOf(name)) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) continue
            directory.search(trimmed, language = lang, limit = 1).firstOrNull()?.let { return it.code to true }
        }
        return trimmedPrinted.ifEmpty { name.trim() } to false
    }

    /**
     * When the reader couldn't tell the report's language (the rule-based
     * fallback never can), pick the catalog language whose labels match the
     * most test names. Ties favour the app language. Empty when nothing matches.
     */
    fun guessLanguage(names: List<String>): String {
        val directory = LabCore.loinc ?: return ""
        val candidates = names.map { it.trim() }.filter { it.length >= 3 }.distinct().take(20)
        if (candidates.isEmpty()) return ""
        val scores = directory.languages.filter { it != "en" }.associateWith { lang ->
            candidates.count { name -> directory.search(name, language = lang, limit = 1).isNotEmpty() }
        }
        val english = candidates.count { directory.search(it, language = "en", limit = 1).isNotEmpty() }
        val best = scores.maxByOrNull { (lang, score) -> score * 2 + if (lang == directory.language) 1 else 0 } ?: return ""
        return if (best.value > english) best.key else directory.language
    }

    /** Strips comparison prefixes/flags a model may copy along (`< 5`, `6,5 H`, `*`). */
    private fun cleanValue(raw: String): String =
        raw.trim().trimStart('<', '>', '≤', '≥', '=', ' ').trimEnd('*', '+', '-', ' ', 'H', 'L', 'h', 'l', '↑', '↓')

    fun parseDate(raw: String): LocalDate? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { LocalDate.parse(trimmed.take(10)) }.getOrNull()
            ?: DateScanner.firstDate(trimmed)
    }
}
