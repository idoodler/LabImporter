package dev.idoodler.labimporter.core.parse

import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.loinc.LoincDirectory
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.deduplicatedByLoinc
import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The structured shape the on-device model fills in — the same fields and
 * guide texts as the iOS `@Generable AILabReport`, so both platforms extract
 * against one schema and post-process identically.
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

/** Live progress of a streaming parse: entries so far and the latest test name. */
data class ParseProgress(val entryCount: Int, val latestName: String?)

/**
 * Guide texts of the extraction schema. Kotlin annotations (Android's ML Kit
 * `@Guide`) reference these constants directly; the iOS `@Generable` repeats
 * them verbatim and a unit test there checks they still match.
 */
object LabParseSchema {
    const val ENTRIES = "All lab test values found in the text"
    const val REPORT_DATE = "Report or blood draw date in yyyy-MM-dd format (e.g. 2026-05-06). Empty string if no date is visible."
    const val PATIENT_NAME = "Patient full name as printed on the report. Empty string if not visible."
    const val AUTHOR_NAME = "Lab or doctor name as printed on the report. Empty string if not visible."
    const val REPORT_LANGUAGE = "BCP-47 language code of the report text, e.g. 'de' or 'en'. Empty string if unsure."
    const val CODE = "Lab test code or abbreviation exactly as printed (e.g. KREA, HB-A1C, MALB-U, GADAAK)"
    const val NAME = "The test's full standard name in the report's own language — do NOT translate it, but DO expand " +
        "terse lab-software mnemonics into the complete clinical term so it can be matched to a coding system. " +
        "German practice software (e.g. PatMed) prints cryptic codes; expand them, never echo the raw mnemonic: " +
        "KREA→Kreatinin, HARNS→Harnsäure, MALB-U→Albumin im Urin, MDRD→glomeruläre Filtrationsrate (eGFR), " +
        "HB-A1C→HbA1c, HB-A1→HbA1c (IFCC), C-PEPT→C-Peptid, GADAAK→Glutamatdecarboxylase-Antikörper, " +
        "ICEA→Inselzell-Antikörper, ICEA2→IA-2-Antikörper, NONHDL→Non-HDL-Cholesterin, TSH-0→Thyreotropin. " +
        "For names already spelled out (e.g. 'Ferritin', 'Vitamin D 25-Hydroxy'), keep them as printed."
    const val RAW_VALUE = "Value exactly as printed — use '-' for negative/not-detected results"
    const val UNIT = "Unit of measurement as printed (e.g. mg/dl, %, mmol/mol, U/l, ml/min/1,73m2KOF). Empty string if none."
}

/** The model instructions and prompt — one text for Foundation Models (iOS) and Gemini Nano (Android). */
object LabParsePrompt {

    val instructions: String = """
        You are a medical lab report parser. Extract every lab test entry from the provided text.
        Lab reports follow the pattern: CODE: value unit; CODE2: value2 unit2; ...
        Preserve codes exactly as printed in `code`. For each entry, also give the test's standard name in the
        report's own language (do NOT translate it) so it can be matched to a coding system in that language.
        German practice software (e.g. PatMed) prints terse vendor mnemonics like KREA, MALB-U, MDRD, HB-A1C,
        C-PEPT, GADAAK or TSH-0 — keep the mnemonic in `code`, but always expand it to its full standard
        clinical name in `name`; never leave `name` as the raw mnemonic.
        Do NOT emit specimen, material, or annotation labels as entries — a "value" of 'Serum', 'EDTA-Plasma',
        'Vollblut' or a standalone method tag (e.g. 'JDF-E.') is not a measured test and must be skipped.
        Detect the language of the report and return it as a BCP-47 code (e.g. 'de', 'en') in reportLanguage.
        Use '-' as rawValue when the result is negative or not detected.
        If you can see a report date or blood draw date, return it in yyyy-MM-dd format; otherwise return an empty string.
        Extract the patient's full name if visible; otherwise return an empty string for patientName.
        Extract the lab or doctor name if visible; otherwise return an empty string for authorName.
    """.trimIndent()

    /**
     * Only for models that can't constrain their output to a schema: the
     * schema spelled out as a JSON contract (field meanings = [LabParseSchema]).
     */
    val jsonContract: String = buildString {
        append("Respond with ONLY one JSON object, no prose and no code fences, with exactly these fields:\n")
        append("{\"entries\": [{\"code\": string, \"name\": string, \"rawValue\": string, \"unit\": string}], ")
        append("\"reportDate\": string, \"patientName\": string, \"authorName\": string, \"reportLanguage\": string}\n")
        append("entries: ${LabParseSchema.ENTRIES}.\n")
        append("code: ${LabParseSchema.CODE}.\nname: ${LabParseSchema.NAME}\n")
        append("rawValue: ${LabParseSchema.RAW_VALUE}.\nunit: ${LabParseSchema.UNIT}\n")
        append("reportDate: ${LabParseSchema.REPORT_DATE}\npatientName: ${LabParseSchema.PATIENT_NAME}\n")
        append("authorName: ${LabParseSchema.AUTHOR_NAME}\nreportLanguage: ${LabParseSchema.REPORT_LANGUAGE}")
    }

    /**
     * Wraps the report in a few sentences of plain English: on-device models
     * detect the language of the *prompt* and can refuse terse, code-heavy
     * reports otherwise (names stay in the report's own language).
     */
    fun prompt(reportText: String): String =
        "You are reading a laboratory test report. The report text below may be very short " +
            "and consist mostly of abbreviated test codes, numbers and units. Read it carefully " +
            "and extract every laboratory test value it contains. Here is the report text:\n\n" + reportText

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decodes a model reply that was asked for [jsonContract]. Tolerates a
     * Markdown code fence around the object but nothing else: anything that
     * isn't a well-formed object of the schema returns null (the caller reports
     * a failed parse instead of inventing an empty result).
     */
    fun decodeJsonReply(response: String): AiLabReport? {
        val body = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (!body.startsWith("{") || !body.endsWith("}")) return null
        return runCatching { json.decodeFromString(AiLabReport.serializer(), body) }.getOrNull()
    }

    private val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]*)")

    /** Progress from a partially streamed JSON reply. */
    fun progress(partial: String): ParseProgress {
        val names = nameRegex.findAll(partial).map { it.groupValues[1] }.toList()
        return ParseProgress(entryCount = names.size, latestName = names.lastOrNull()?.takeIf { it.isNotBlank() })
    }
}

/** Turns the model's raw extraction into reviewable, LOINC-resolved values. */
object LabParsePipeline {

    fun postProcess(report: AiLabReport): ParseResult {
        val values = report.entries
            .filter { it.code.isNotBlank() || it.name.isNotBlank() }
            .map { entry ->
                val numeric = if (entry.rawValue == "-") null else NumberFormatting.parse(entry.rawValue)
                val (code, suggested) = resolveLoinc(entry.code, entry.name, report.reportLanguage)
                val display = LabMapping.displayName(code)
                LabValue(
                    code = code,
                    // An unmapped value shows the model's expanded name ("Kreatinin"), not the mnemonic.
                    name = if (display == code) entry.name.trim().ifEmpty { entry.code } else display,
                    displayValue = entry.rawValue,
                    numericValue = numeric,
                    unit = entry.unit.trim(),
                    isSuggestedCode = suggested,
                )
            }
        // A report can list a test twice and two names can resolve to the same
        // LOINC: keep one entry per LOINC, the app's canonical identity.
        return ParseResult(
            values = values.deduplicatedByLoinc(),
            reportDate = parseDate(report.reportDate),
            patientName = report.patientName.trim().ifEmpty { null },
            authorName = report.authorName.trim().ifEmpty { null },
        )
    }

    /**
     * Resolves a parsed entry to LOINC from the bundled catalog. A valid LOINC
     * passes through. Otherwise the model's test name is looked up — in the
     * report's own language (plus English), then in every catalog language
     * (everyday spellings often differ from the LOINC translation: "Kreatinin"
     * vs. "Creatinin"), then without little words ("Albumin im Urin"), then
     * by word stem ("Cholesterin" → "Cholesterol") — and finally the printed
     * code, as a whole word only ("TSH"; "KREA" must not prefix-match
     * "Kreatinkinase"). The most commonly ordered hit is offered
     * as a *suggestion* the user confirms in review. Unresolved entries keep
     * the printed code so they can be mapped by hand.
     */
    fun resolveLoinc(printed: String, name: String, language: String): Pair<String, Boolean> {
        val directory = LabCore.loinc ?: return printed to false
        val trimmedPrinted = printed.trim()
        if (directory.isKnownLoinc(trimmedPrinted)) return trimmedPrinted to false
        val lang = directory.resolvedLanguage(language)
        val trimmedName = name.trim()
        val match = trimmedName.takeIf { it.isNotEmpty() }?.let { term ->
            directory.bestMatch(term, lang)
                ?: directory.bestMatch(term, lang, anyLanguage = true)
                ?: LoincDirectory.withoutStopWords(term)?.let { directory.bestMatch(it, lang) }
                ?: LoincDirectory.stemmedQueries(term).firstNotNullOfOrNull { directory.bestMatch(it, lang, anyLanguage = true) }
        } ?: trimmedPrinted.takeIf { it.isNotEmpty() }?.let { directory.bestMatch(it, lang, prefix = false) }
        return match?.let { it.code to true } ?: (printed to false)
    }

    /** The model's `yyyy-MM-dd` date; anything else is no date (the user sets it in review). */
    fun parseDate(raw: String): LocalDate? {
        val trimmed = raw.trim()
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(trimmed)) return null
        return runCatching { LocalDate.parse(trimmed) }.getOrNull()
    }
}
