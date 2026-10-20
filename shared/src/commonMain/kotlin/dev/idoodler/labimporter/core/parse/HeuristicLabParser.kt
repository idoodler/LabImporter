package dev.idoodler.labimporter.core.parse

import kotlinx.datetime.LocalDate

/**
 * A rule-based fallback for devices without an on-device language model (and
 * for emulators). It recognizes `Label: value unit` / `Label value unit`
 * segments line by line and lets the shared pipeline resolve the labels
 * against the LOINC catalog. Far less capable than the model — every result
 * still goes through user review — but it keeps the app usable everywhere.
 */
object HeuristicLabParser {

    private val entryRegex = Regex(
        "^\\s*([\\p{L}][\\p{L}\\p{N} .,'/()+\\-]*?)\\s*[:=]?\\s+" +
            // The lookahead rejects numbers that are really part of a date (10.09.2026).
            "([<>≤≥]?\\s*-?\\d+(?:[.,]\\d+)?)(?!\\d|[./]\\d)\\s*" +
            "([^\\s;]*[\\p{L}%µμ][^\\s;]*)?",
    )
    private val ignoredLabels = Regex(
        "^(datum|date|seite|page|tel|telefon|fax|geb|geboren|born|dob|patient|name|id|nr|no|uhrzeit|time)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val patientRegex = Regex("(?im)^\\s*(?:patient(?:in)?|name)\\s*:\\s*([^\\n;,]{3,60})$")

    private val mnemonic = Regex("^[A-Z0-9][A-Z0-9\\-/.]{1,9}$")

    fun parse(text: String): AiLabReport {
        val entries = text.split('\n', ';')
            .filterNot { patientRegex.containsMatchIn(it) }
            .mapNotNull { segment -> parseSegment(segment) }
            .distinctBy { it.code.lowercase() }
        return AiLabReport(
            entries = entries,
            reportDate = DateScanner.firstDate(text)?.toString().orEmpty(),
            patientName = patientRegex.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
        )
    }

    private fun parseSegment(segment: String): AiLabEntry? {
        val match = entryRegex.find(segment) ?: return null
        val label = match.groupValues[1].trim().trimEnd(':', '.', ',', '-').trim()
        if (label.length < 2 || ignoredLabels.containsMatchIn(label)) return null
        if (DateScanner.firstDate(segment) != null && label.length < 4) return null
        val value = match.groupValues[2].replace(" ", "")
        // Terse vendor mnemonics (KREA, HB-A1C) carry no searchable name; only
        // a language model can expand them, so leave the name empty.
        val name = if (mnemonic.matches(label)) "" else label
        return AiLabEntry(code = label, name = name, rawValue = value, unit = match.groupValues[3].trim())
    }
}

/** Finds the first plausible calendar date (`dd.MM.yyyy`, `yyyy-MM-dd`, `dd/MM/yyyy`). */
object DateScanner {
    private val iso = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
    private val dotted = Regex("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})")

    fun firstDate(text: String): LocalDate? {
        val candidates = buildList {
            iso.findAll(text).forEach { m ->
                add(m.range.first to runCatching {
                    LocalDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                }.getOrNull())
            }
            dotted.findAll(text).forEach { m ->
                add(m.range.first to runCatching {
                    LocalDate(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
                }.getOrNull())
            }
        }
        return candidates.filter { it.second != null }.minByOrNull { it.first }?.second
    }
}
