package dev.idoodler.labimporter.core.parse

import dev.idoodler.labimporter.core.platform.NumberFormatting

/**
 * The text a report was read from, to tell whether a result the model returned
 * is printed in it at all. Models mis-copy digits (ALT 22 once came back as
 * 222), and the wrong number looks just as plausible as the right one, so a
 * result the text doesn't print is shown to the user to compare.
 *
 * It only says whether the result is printed somewhere, not on the test's own
 * line: OCR order can't be trusted to keep a row together, and a false alarm
 * on every other value would teach people to ignore the badge. So a wrong
 * number that happens to be printed elsewhere goes unnoticed.
 */
internal class ReportSourceText(text: String) {
    private val undated: String = text.replace(LabParsePipeline.ISO_DATE, " ").replace(LabParsePipeline.NUMERIC_DATE, " ")
    private val decimalSeparator: Char? = decimalSeparatorIn(undated)
    private val numbers: Set<Double> = numbersIn(undated, decimalSeparator)
    private val normalized: String = normalize(text)

    /** Whether [result] is printed. No result (empty, `-`) claims nothing, so there's nothing to contradict. */
    fun prints(result: String): Boolean {
        val trimmed = result.trim()
        if (trimmed.isEmpty() || trimmed == "-") return true
        val number = numberFrom(trimmed)
        return when {
            number == null -> printsText(trimmed)
            number in numbers -> true
            // Exponent notation isn't one number token: "1.2e5" is compared as printed.
            else -> trimmed.contains('e', ignoreCase = true) && printsText(trimmed)
        }
    }

    /**
     * [result] as a number in this report's notation, or null when it isn't
     * one or the report doesn't say which it is. "263,000" is 263000 beside
     * decimal points and 263 beside decimal commas. Without such a sign, or for
     * "1.020" beside decimal commas (German reports print a specific gravity
     * that way, and thousands too), the user is asked rather than given a value
     * off by a factor of 1000.
     */
    fun numberFrom(result: String): Double? {
        val trimmed = result.trim()
        val number = NumberFormatting.parse(trimmed) ?: return null
        if (!THOUSANDS_OR_DECIMALS.matches(trimmed)) return number
        return when {
            trimmed.first { !it.isDigit() } == decimalSeparator -> number
            decimalSeparator == '.' -> NumberFormatting.parse(trimmed.filter { it.isDigit() })
            else -> null
        }
    }

    /** [result] appears as its own word or number, whatever the case or spacing ("<0,5" is "< 0.5"). */
    private fun printsText(result: String): Boolean {
        val wanted = normalize(result).filterNot { it.isWhitespace() }
        if (wanted.isEmpty()) return true
        return normalized.indices.any { start ->
            val end = matchEnd(wanted, start)
            end != null && startsCleanly(start, wanted.first()) && endsCleanly(end, wanted.last())
        }
    }

    /** Where [wanted] ends if it starts at [start], skipping spacing inside the text; null when it isn't there. */
    private fun matchEnd(wanted: String, start: Int): Int? {
        var index = start
        for ((position, char) in wanted.withIndex()) {
            if (position > 0) while (index < normalized.length && normalized[index].isWhitespace()) index++
            if (index >= normalized.length || normalized[index] != char) return null
            index++
        }
        return index
    }

    private fun startsCleanly(start: Int, first: Char): Boolean {
        if (isNegated(start, first)) return false
        if (!first.isWordChar() || start == 0) return true
        val before = normalized[start - 1]
        val beforeThat = normalized.getOrNull(start - 2)
        return when {
            before.isWordChar() -> false
            // "Reactive" isn't printed by "Non-Reactive", the opposite result.
            before == '-' && beforeThat?.isLetter() == true -> false
            // "5" isn't printed by "1.5".
            before == '.' && first.isDigit() && beforeThat?.isDigit() == true -> false
            else -> true
        }
    }

    /** "Detected" isn't printed by "Not Detected", nor 检出 by 未检出: the opposite result. */
    private fun isNegated(start: Int, first: Char): Boolean {
        if (!first.isLetter() || start == 0) return false
        if (normalized[start - 1] in UNSPACED_NEGATIONS) return true
        var end = start
        while (end > 0 && normalized[end - 1].isWhitespace()) end--
        if (end == start) return false
        var wordStart = end
        while (wordStart > 0 && normalized[wordStart - 1].isLetter()) wordStart--
        return normalized.substring(wordStart, end) in NEGATIONS
    }

    private fun endsCleanly(end: Int, last: Char): Boolean {
        if (!last.isWordChar() || end >= normalized.length) return true
        val after = normalized[end]
        val afterThat = normalized.getOrNull(end + 1)
        // "0.5" isn't printed by "0.55", and "1" isn't printed by "1.5".
        return !after.isWordChar() && !(after == '.' && last.isDigit() && afterThat?.isDigit() == true)
    }

    private companion object {
        /** Digits with decimal or grouping separators inside: "5", "5,1", "1.234,5", "1.2.3". */
        val NUMBER = Regex("\\d+(?:[.,]\\d+)*")

        /** A number in thousands by its separators alone: "263,000", "1.234.567". */
        val GROUPED = Regex("(?<![\\d.,])\\d{1,3}(?:[.,]\\d{3})+(?!\\d)")

        /** A number in thousands that spacing makes certain, with any decimals: "12 345", "1 234,5", "1'234.5". */
        val SPACED = Regex("(?<![\\d.,])\\d{1,3}(?:[ '\\u00A0\\u202F]\\d{3})+(?:[.,]\\d+)?(?!\\d)")

        /** Either 263000 or 263: one separator, three digits behind it, and nothing to rule out grouping as "0,125" does. */
        val THOUSANDS_OR_DECIMALS = Regex("^[1-9]\\d{0,2}[.,]\\d{3}$")

        /** "1:160", "1 : 160" — not "Test 1: 5.1". */
        val TITER = Regex("(?<![\\d.,])\\d+(?::|\\s+:\\s+)\\d+(?![.,]?\\d)")

        val MINUS = charArrayOf('-', '–', '—', '−')
        val THRESHOLDS = charArrayOf('<', '>', '≤', '≥')

        /** Words that turn the result after them into its opposite, in the languages the app ships. */
        val NEGATIONS = setOf(
            "not", "non", "no", "nicht", "kein", "keine", "ohne", "pas", "sans", "sin", "senza", "não", "nao", "sem",
            "niet", "geen", "nie", "ne", "nije", "nem", "ei", "ej", "inte", "ikke", "ingen", "nu", "fără", "fara",
            "не", "нет", "ні", "без", "μη", "δεν", "όχι", "οχι",
        )

        /** Chinese and Japanese negations, written straight onto the word. */
        const val UNSPACED_NEGATIONS = "未不非无無"

        /** Chinese and Japanese leave no space around a result, so nothing there marks where a word starts. */
        fun Char.isInUnspacedScript(): Boolean =
            code in 0x3040..0x30FF || code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF

        fun Char.isWordChar(): Boolean = isLetterOrDigit() && !isInUnspacedScript()

        fun normalize(text: String): String = buildString(text.length) {
            // Turkish "İ" and "ı" have no case partner elsewhere: NEGATİF is Negatif.
            val folded = text.replace('İ', 'i').replace('ı', 'i').replace("\u0307", "")
            for (char in folded.lowercase()) append(
                when (char) {
                    ',' -> '.'
                    '–', '—', '−' -> '-'
                    else -> char
                },
            )
        }

        /**
         * Which separator this report writes decimals with, from the numbers that can't
         * be anything else ("5,1", "0,125", "1.234,5"); null when none does or both do.
         */
        fun decimalSeparatorIn(text: String): Char? {
            var comma = false
            var point = false
            for (match in NUMBER.findAll(text)) {
                // "v1.2" and "Hb14,5" say little: a version or an OCR slip.
                if (text.getOrNull(match.range.first - 1)?.isLetter() == true) continue
                val separators = match.value.filter { it == '.' || it == ',' }
                val decimal = when {
                    separators.isEmpty() || THOUSANDS_OR_DECIMALS.matches(match.value) -> null
                    separators.length == 1 || separators.first() != separators.last() -> separators.last()
                    else -> null
                }
                if (decimal == ',') comma = true
                if (decimal == '.') point = true
            }
            return when {
                comma == point -> null
                comma -> ','
                else -> '.'
            }
        }

        /**
         * Every number printed in [text] as a plain reading, compared by value so
         * "5,10" prints 5.1. A number is read whole, so 22 and 222 are no part of 2220
         * or 22.5, and with its sign, so 2.1 isn't printed by "-2.1". A threshold or a
         * titer is another result altogether: 0.5 isn't printed by "<0,5", nor 160 by
         * "1:160". Dates are left out: a wrong 12 must not pass because of "08/12/2026".
         */
        fun numbersIn(text: String, decimal: Char?): Set<Double> = buildSet {
            val titers = TITER.findAll(text).map { it.range }.toList()
            fun signOf(start: Int): Double? = if (titers.any { start in it }) null else signAt(text, start)
            for (match in NUMBER.findAll(text)) {
                if (isInsideWord(text, match.range)) continue
                val sign = signOf(match.range.first) ?: continue
                val token = match.value
                val separators = token.filter { it == '.' || it == ',' }
                when {
                    // Beside decimal points, "263,000" can only be thousands, which are added below.
                    THOUSANDS_OR_DECIMALS.matches(token) && decimal == '.' && separators == "," -> Unit
                    separators.length <= 1 -> addNumber(token, sign)
                    // "1.234,5": only the last separator is a decimal one.
                    separators.first() != separators.last() -> {
                        val at = token.lastIndexOfAny(charArrayOf('.', ','))
                        addNumber(token.substring(0, at).filter { it.isDigit() } + "." + token.substring(at + 1), sign)
                    }
                    // Grouped thousands are added below; otherwise it's a list of numbers.
                    else -> token.split('.', ',').forEach { addNumber(it, sign) }
                }
                // ".5" is 0.5, and glued to a word ("Hb,14") it's still 14.
                if (text.getOrNull(match.range.first - 1).let { it == '.' || it == ',' }) addNumber("0.$token", sign)
            }
            for (match in GROUPED.findAll(text)) {
                val token = match.value
                // In the report's own decimal notation, "1,025" is no 1025.
                if (THOUSANDS_OR_DECIMALS.matches(token) && token.first { !it.isDigit() } == decimal) continue
                val sign = signOf(match.range.first) ?: continue
                addNumber(token.filter { it.isDigit() }, sign)
            }
            for (match in SPACED.findAll(text)) {
                val sign = signOf(match.range.first) ?: continue
                addNumber(match.value.filter { it.isDigit() || it == '.' || it == ',' }, sign)
            }
        }

        fun MutableSet<Double>.addNumber(token: String, sign: Double) {
            NumberFormatting.parse(token)?.let { add(sign * it) }
        }

        /** The sign of the plain reading at [start], or null behind a threshold ("<0,5", ">= 90"). */
        fun signAt(text: String, start: Int): Double? {
            var first = start
            if (text.getOrNull(first - 1).let { it == '.' || it == ',' }) first--
            val before = text.getOrNull(first - 1)
            var index = first
            while (index > 0 && text[index - 1].isWhitespace()) index--
            val previous = text.getOrNull(index - 1)
            if (previous != null && previous in THRESHOLDS) return null
            if (previous == '=' && text.getOrNull(index - 2)?.let { it in THRESHOLDS } == true) return null
            if (before == null || before !in MINUS) return 1.0
            // A minus sign has space or punctuation in front; "3-5", "10 -20" and "ALT-22" have none of that.
            val glued = text.getOrNull(first - 2)
            if (glued != null && (glued.isLetterOrDigit() || glued in MINUS)) return 1.0
            var behind = first - 2
            while (behind >= 0 && text[behind].isWhitespace()) behind--
            return if (text.getOrNull(behind)?.isDigit() == true) 1.0 else -1.0
        }

        /**
         * The 1 in "HbA1c" is part of a name, not a printed result. A longer number
         * or a decimal between letters is a result the spacing was lost around
         * ("Hb14g/dl"), and Chinese and Japanese never have any ("血红蛋白145克/升").
         */
        fun isInsideWord(text: String, range: IntRange): Boolean {
            val before = text.getOrNull(range.first - 1)
            val after = text.getOrNull(range.last + 1)
            return range.first == range.last && before?.isLetter() == true && !before.isInUnspacedScript() &&
                after?.isLetter() == true && !after.isInUnspacedScript()
        }
    }
}
