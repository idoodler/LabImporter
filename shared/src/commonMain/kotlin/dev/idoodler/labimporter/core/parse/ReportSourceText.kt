package dev.idoodler.labimporter.core.parse

import dev.idoodler.labimporter.core.platform.NumberFormatting

/**
 * The text a report was read from, to tell whether what the model returned is
 * printed in it. Models mis-copy digits (ALT 22 once came back as 222), drop
 * part of a unit (ALT's "U/l" once came back "/l") and put a result on the
 * wrong test, and the wrong value looks just as plausible as the right one, so
 * a value the text doesn't back is shown to the user to compare.
 *
 * Three checks ([prints], [printsUnit], [printsBesideTest]), none of which
 * flags a value the report prints, with its unit, on its row. The last one reads rows,
 * but only where a row can be trusted: OCR keeps one recognised line per line,
 * yet a PDF's text can put a table's names and values apart, so a line that
 * names the test and prints no result at all says nothing. A false alarm on
 * every other value would teach people to ignore the badge.
 */
internal class ReportSourceText(text: String) {
    private val decimalSeparator: Char? = decimalSeparatorIn(undated(text))
    private val whole = Printed(text, decimalSeparator)
    private val unitText: String = unitForm(text)
    private val lines: List<Printed> by lazy { text.lines().map { Printed(it, decimalSeparator) } }

    /**
     * Whether the report backs a value the model read: its [result] printed, on
     * a line of its test (by [names]) where one prints a result, and its [unit]
     * printed. A value without a result claims nothing, its unit included.
     */
    fun backs(result: String, unit: String, names: List<String>): Boolean {
        val trimmed = result.trim()
        if (trimmed.isEmpty() || trimmed == "-") return true
        return prints(trimmed) && printsUnit(unit) && printsBesideTest(trimmed, names)
    }

    /** Whether [result] is printed. No result (empty, `-`) claims nothing, so there's nothing to contradict. */
    fun prints(result: String): Boolean = whole.prints(result)

    /**
     * Whether [unit] is printed as a whole unit, whatever the case or spacing,
     * with µ and u alike, "m²" and "m2", "10³" and "10^3", "Tsd." and "Tsd":
     * "/l" isn't printed by "U/l", nor "mg" by "mg/dl". No unit claims nothing.
     */
    fun printsUnit(unit: String): Boolean {
        val wanted = unitForm(unit).filterNot { it.isWhitespace() }
        if (wanted.isEmpty()) return true
        return unitText.indices.any { start ->
            val end = unitMatchEnd(unitText, wanted, start)
            end != null && unitStartsCleanly(unitText, start) && unitEndsCleanly(unitText, end)
        }
    }

    /**
     * Whether [result] is printed on a line that names its test — by any of
     * [names], the model's test name and printed code. Where no line names the
     * test, or none of the lines that do prints any result (a PDF's text put the
     * values apart from the names), nothing can be told and nothing is claimed.
     * So a result on the wrong test is caught where a row prints another one.
     *
     * A row's reference range is no result of it ("3,5 - 5,1"), and a row goes on
     * to the next line when that one starts with the result (a PDF often puts a
     * bold result on a line of its own). Where some row starts with the test's
     * name, only those are its rows: "HDL Cholesterin" names Cholesterin too.
     */
    fun printsBesideTest(result: String, names: List<String>): Boolean {
        val trimmed = result.trim()
        if (trimmed.isEmpty() || trimmed == "-") return true
        val wanted = names.map { normalize(it).filterNot { char -> char.isWhitespace() } }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return true
        val naming = lines.indices.mapNotNull { index ->
            val named = wanted.filter { lines[index].names(it) }
            if (named.isEmpty()) null else index to named
        }
        val labelled = naming.filter { (index, named) -> named.any { lines[index].startsWithName(it) } }
        val rows = labelled.ifEmpty { naming }
        val onItsRow = rows.any { (index, _) ->
            lines[index].printsOnRow(trimmed) || lines.getOrNull(index + 1)?.startsWithResult(trimmed) == true
        }
        if (onItsRow) return true
        return rows.none { (index, named) -> lines[index].printsAResultBesides(named) }
    }

    /**
     * [result] as a number in this report's notation, or null when it isn't
     * one or the report doesn't say which it is. "263,000" is 263000 beside
     * decimal points and 263 beside decimal commas. Without such a sign, or for
     * "1.020" beside decimal commas (German reports print a specific gravity
     * that way, and thousands too), the user is asked rather than given a value
     * off by a factor of 1000.
     */
    fun numberFrom(result: String): Double? = numberIn(result, decimalSeparator)

    /** A piece of the report — all of it, or one line — and what it prints. */
    private class Printed(text: String, private val decimalSeparator: Char?) {
        private val undated: String = undated(text)
        private val numbers: Set<Double> = numbersIn(undated, decimalSeparator)

        /** The numbers a row can have as its result: not the limits of a reference range. */
        private val results: Set<Double> by lazy { numbersIn(undated.replace(RANGE, " "), decimalSeparator) }
        private val normalized: String = normalize(text)

        fun prints(result: String): Boolean = prints(result, numbers)

        /** Whether this prints [result] as a row's result: as [prints], but a reference range's limit isn't one. */
        fun printsOnRow(result: String): Boolean = prints(result, results)

        /** Whether this line starts with [result] as a value of its own ("14,2", "245 H", "58 ml/min"), continuing the row above. */
        fun startsWithResult(result: String): Boolean {
            val value = LEADING_VALUE.find(undated)?.value ?: return false
            return Printed(value, decimalSeparator).prints(result)
        }

        private fun prints(result: String, among: Set<Double>): Boolean {
            val trimmed = result.trim()
            if (trimmed.isEmpty() || trimmed == "-") return true
            val number = numberIn(trimmed, decimalSeparator)
            return when {
                number == null -> printsText(trimmed)
                number in among -> true
                // Exponent notation isn't one number token: "1.2e5" is compared as printed.
                else -> trimmed.contains('e', ignoreCase = true) && printsText(trimmed)
            }
        }

        /** Whether this names a test by [wanted] (normalized, without spacing) as a whole word. */
        fun names(wanted: String): Boolean = normalized.indices.any { start -> namesAt(wanted, start) }

        /** Whether this line starts with the test's name [wanted], as its label: no word comes before it. */
        fun startsWithName(wanted: String): Boolean {
            val start = normalized.indexOfFirst { it.isLetterOrDigit() }
            return start >= 0 && namesAt(wanted, start)
        }

        private fun namesAt(wanted: String, start: Int): Boolean {
            val end = matchEnd(normalized, wanted, start)
            return end != null && wordStartsCleanly(normalized, start, wanted.first()) && endsCleanly(normalized, end, wanted.last())
        }

        /**
         * Whether this prints a result other than the numbers in the test's own
         * [names] (the 12 of "Vitamin B12"): a number, a threshold or a titer. A
         * reference range's limits are none.
         */
        fun printsAResultBesides(names: List<String>): Boolean {
            val ofNames = names.flatMapTo(mutableSetOf()) { numbersIn(it, decimalSeparator) }
            return (results - ofNames).isNotEmpty() || THRESHOLD_NUMBER.containsMatchIn(undated) || TITER.containsMatchIn(undated)
        }

        /** [result] appears as its own word or number, whatever the case or spacing ("<0,5" is "< 0.5"). */
        private fun printsText(result: String): Boolean {
            val wanted = normalize(result).filterNot { it.isWhitespace() }
            if (wanted.isEmpty()) return true
            return normalized.indices.any { start ->
                val end = matchEnd(normalized, wanted, start)
                end != null && startsCleanly(start, wanted.first()) && endsCleanly(normalized, end, wanted.last())
            }
        }

        private fun startsCleanly(start: Int, first: Char): Boolean = !isNegated(start, first) && wordStartsCleanly(normalized, start, first)

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

        /** A threshold result: "<0,5", ">= 90". */
        val THRESHOLD_NUMBER = Regex("[<>≤≥]=?\\s*[.,]?\\d")

        /** A reference range: "3,5 - 5,1", "136–145". */
        val RANGE = Regex("(?<![\\d.,])\\d+(?:[.,]\\d+)?\\s*[-–—−]\\s*\\d+(?:[.,]\\d+)?(?![\\d.,])")

        /** A value a line starts with, on its own: "14,2", "245 H", "< 0,5", "263/nl" — not the 25 of "25-OH-Vitamin D". */
        val LEADING_VALUE = Regex("^\\s*(?:[<>≤≥]=?\\s*)?[-−]?\\d+(?:[.,]\\d+)*(?=\\s|$|[%/])")

        /** Superscript digits, written in units as a power: "m²" is "m^2". */
        const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

        val MINUS = charArrayOf('-', '–', '—', '−')
        val THRESHOLDS = charArrayOf('<', '>', '≤', '≥')

        /** What a unit is written with besides letters and digits: "10^9/l", "10*3/ul", "m²", "%". */
        const val UNIT_MARKS = "/^*×·%²³¹⁰⁴⁵⁶⁷⁸⁹⁻"

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

        fun Char.isUnitChar(): Boolean = isWordChar() || this in UNIT_MARKS

        fun numberIn(result: String, decimalSeparator: Char?): Double? {
            val trimmed = result.trim()
            val number = NumberFormatting.parse(trimmed) ?: return null
            if (!THOUSANDS_OR_DECIMALS.matches(trimmed)) return number
            return when {
                trimmed.first { !it.isDigit() } == decimalSeparator -> number
                decimalSeparator == '.' -> NumberFormatting.parse(trimmed.filter { it.isDigit() })
                else -> null
            }
        }

        fun undated(text: String): String = text.replace(LabParsePipeline.ISO_DATE, " ").replace(LabParsePipeline.NUMERIC_DATE, " ")

        fun normalize(text: String): String = buildString(text.length) {
            // Turkish "İ" and "ı" have no case partner elsewhere: NEGATİF is Negatif.
            val folded = text.replace('İ', 'i').replace('ı', 'i').replace("̇", "")
            for (char in folded.lowercase()) append(
                when (char) {
                    ',' -> '.'
                    '–', '—', '−' -> '-'
                    else -> char
                },
            )
        }

        /** [text] as units are compared: [normalize]d, with the micro sign (and Greek mu) written u and a superscript as a power. */
        fun unitForm(text: String): String = buildString(text.length) {
            for (char in normalize(text).replace('µ', 'u').replace('μ', 'u')) {
                val power = SUPERSCRIPT_DIGITS.indexOf(char)
                if (power >= 0) append('^').append(power) else append(char)
            }
        }

        /**
         * [matchEnd] for units, which are written several ways: a power's caret is
         * optional ("m2" is "m^2", from "m²"), and so is an abbreviation's full stop
         * ("Tsd/µl" is "Tsd./µl"), in the text or in [wanted].
         */
        fun unitMatchEnd(text: String, wanted: String, start: Int): Int? {
            var index = start
            var position = 0
            while (position < wanted.length) {
                if (position > 0) while (index < text.length && text[index].isWhitespace()) index++
                val printed = text.getOrNull(index) ?: return null
                when {
                    printed == wanted[position] -> { position++; index++ }
                    position == 0 -> return null
                    isOptionalUnitMark(wanted, position, printed) -> position++
                    isOptionalUnitMark(text, index, wanted[position]) -> index++
                    else -> return null
                }
            }
            return index
        }

        /** Whether [text]'s mark at [at] may be left out where the other side has [other]: a caret before a digit, a full stop after a letter. */
        fun isOptionalUnitMark(text: String, at: Int, other: Char): Boolean = when (text[at]) {
            '^' -> other.isDigit()
            '.' -> text[at - 1].isLetter()
            else -> false
        }

        /** Where [wanted] ends if it starts at [start] of [text], skipping spacing inside it; null when it isn't there. */
        fun matchEnd(text: String, wanted: String, start: Int): Int? {
            var index = start
            for ((position, char) in wanted.withIndex()) {
                if (position > 0) while (index < text.length && text[index].isWhitespace()) index++
                if (index >= text.length || text[index] != char) return null
                index++
            }
            return index
        }

        fun wordStartsCleanly(text: String, start: Int, first: Char): Boolean {
            if (!first.isWordChar() || start == 0) return true
            val before = text[start - 1]
            val beforeThat = text.getOrNull(start - 2)
            return when {
                before.isWordChar() -> false
                // "Reactive" isn't printed by "Non-Reactive", the opposite result.
                before == '-' && beforeThat?.isLetter() == true -> false
                // "5" isn't printed by "1.5".
                before == '.' && first.isDigit() && beforeThat?.isDigit() == true -> false
                else -> true
            }
        }

        fun endsCleanly(text: String, end: Int, last: Char): Boolean {
            if (!last.isWordChar() || end >= text.length) return true
            val after = text[end]
            val afterThat = text.getOrNull(end + 1)
            // "0.5" isn't printed by "0.55", and "1" isn't printed by "1.5".
            return !after.isWordChar() && !(after == '.' && last.isDigit() && afterThat?.isDigit() == true)
        }

        /**
         * A unit starts where nothing of another unit is in front of it ("/l" in "U/l"
         * doesn't), behind its value even when glued to it ("14g/dl", "263/nl"), but
         * not behind the number of a power or a factor ("10^9/l", "1,73m²"). A power
         * starts behind its times sign ("x10^9/l", LabCorp's "x10E3/uL").
         */
        fun unitStartsCleanly(text: String, start: Int): Boolean {
            var index = start
            while (index > 0 && (text[index - 1].isDigit() || text[index - 1] == '.')) index--
            val before = text.getOrNull(index - 1) ?: return true
            return when {
                index == start -> !before.isUnitChar() || isTimesSign(text, index - 1) && text[start].isDigit()
                // Glued to its value, which is glued to its label: "Hb14g/dl".
                else -> !before.isUnitChar() || before.isLetter()
            }
        }

        /** An "x" or "×" between a value and a power ("6.8 x10^9", "6.8x10^9"), not the end of a word ("Box10"). */
        fun isTimesSign(text: String, at: Int): Boolean = text[at] in "x×" && text.getOrNull(at - 1)?.isLetter() != true

        /** A unit ends where nothing of a longer unit follows: "mg" isn't printed by "mg/dl". */
        fun unitEndsCleanly(text: String, end: Int): Boolean {
            val after = text.getOrNull(end) ?: return true
            if (after == '.') return text.getOrNull(end + 1)?.isWordChar() != true
            return !after.isUnitChar()
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
