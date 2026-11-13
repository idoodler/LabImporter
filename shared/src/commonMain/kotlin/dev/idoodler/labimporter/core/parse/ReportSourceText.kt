package dev.idoodler.labimporter.core.parse

import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.datetime.LocalDate
import kotlin.math.abs

/**
 * The text a report was read from, to tell whether what the model returned is
 * printed in it. Models mis-copy digits (ALT 22 once came back as 222), drop
 * part of a unit (ALT's "U/l" once came back "/l") and put a result on the
 * wrong test, and the wrong value looks just as plausible as the right one, so
 * a value the text doesn't back is shown to the user to compare.
 *
 * Four checks ([prints], [printsUnit], [printsBesideTest], [printsUnitBesideTest]),
 * none of which flags a value the report prints, with its unit, on its row. The
 * last two read rows, but only where a row can be trusted: OCR keeps one
 * recognised line per line, yet a PDF's text can put a table's names and values
 * apart, so a line that names the test and prints no result at all says nothing,
 * nor does a row that prints no unit beside its result. A false alarm on every
 * other value would teach people to ignore the badge. Two more catch a result
 * the row prints, but not as this report's: one that lost its "<" or ">"
 * ([dropsQualifier]), and an earlier date's beside the current one
 * ([isAnotherDatesResult]); and in a cumulative report, a result from another
 * date's column ([isFromAnotherColumn]).
 *
 * @param today the day it is read on: a two-digit year after it is 19xx.
 */
internal class ReportSourceText(text: String, private val today: LocalDate? = null) {
    private val decimalSeparator: Char? = decimalSeparatorIn(undated(text))
    private val whole = Printed(text, decimalSeparator)
    private val unitText: String = unitForm(text)
    private val rawLines: List<String> = text.lines()
    private val lines: List<Printed> by lazy { rawLines.map { Printed(it, decimalSeparator) } }

    /**
     * The result columns of a cumulative report: a header of two or more result dates
     * ([ResultColumns.find]) over rows that print a result per date — at least half of the
     * rows right below it that print a result print two or more. A header of dates over
     * rows of one result each is no such table ("Collected  Received  Reported" over a
     * single-date report's values). Null where the report has none.
     */
    val resultColumns: ResultColumns? by lazy {
        val columns = ResultColumns.find(rawLines, today) ?: return@lazy null
        val rows = (columns.headerEnd + 1..rawLines.lastIndex).dropWhile { rawLines[it].isBlank() }.takeWhile { rawLines[it].isNotBlank() }
        val counts = rows.mapNotNull { index -> lines[index].labelEnd()?.let { lines[index].resultsFrom(it).size } }.filter { it > 0 }
        val several = counts.count { it >= 2 }
        columns.takeIf { several >= 1 && several * 2 >= counts.size }
    }

    /**
     * Whether [result], a number or threshold, may be another date's in a cumulative report
     * ([resultColumns]): on its test's rows that print more than one result (a reference range
     * and flags aside), the model could have taken the wrong one, so it is flagged unless it is
     * the result in the column of [reportDate] — or, where that date heads no column, of the
     * latest date, else the first. A row's result is the one printed under the column's date
     * where the row lines up with the header, else the one in the column's place. A row of one
     * result has nothing to mix up, and a report without result columns raises nothing.
     */
    fun isFromAnotherColumn(result: String, names: List<String>, reportDate: LocalDate?): Boolean {
        val columns = resultColumns ?: return false
        val trimmed = result.trim()
        val bare = trimmed.trimStart(*THRESHOLDS, '=').trim()
        if (bare.firstOrNull()?.isDigit() != true) return false
        val value = numberIn(bare, decimalSeparator)?.let { abs(it) } ?: return false
        val qualified = trimmed.first() in THRESHOLDS
        val column = columns.reportColumn(reportDate)
        val verdicts = rowsOf(names).filter { (index, _) -> index > columns.headerEnd }.mapNotNull { (index, named) ->
            val line = lines[index]
            val from = line.nameEnd(named).takeIf { it > 0 } ?: line.labelEnd() ?: return@mapNotNull null
            val cells = cellsOf(line.resultsFrom(from), columns) ?: return@mapNotNull null
            val cell = cells.getOrNull(column)
            cell == null || value !in cell.values || cell.qualified != qualified
        }
        return verdicts.isNotEmpty() && verdicts.all { it }
    }

    /**
     * A row's [results] by column: each under the date it is printed under, where the row lines up
     * with the header (two or more of them under a date each, left to right); else in order, the
     * first under the first date. Null where the row prints fewer than two results.
     */
    private fun cellsOf(results: List<PrintedResult>, columns: ResultColumns): List<PrintedResult?>? {
        val spans = columns.spans
        if (spans != null) {
            val placed = results.map { r -> spans.indices.filter { spans[it].first <= r.range.last && spans[it].last >= r.range.first } }
            val under = placed.filter { it.isNotEmpty() }.map { it.first() }
            if (placed.all { it.size <= 1 } && under.size >= 2 && under.zipWithNext().all { (a, b) -> a < b }) {
                return spans.indices.map { column -> results.indices.firstOrNull { placed[it].singleOrNull() == column }?.let(results::get) }
            }
        }
        return if (results.size >= 2) results.take(columns.dates.size) else null
    }

    /**
     * Whether the report backs a value the model read: its [result] printed, on
     * a line of its test (by [names]) where one prints a result, and its [unit]
     * printed, behind that result where its row prints one. A value without a
     * result claims nothing, its unit included.
     */
    fun backs(result: String, unit: String, names: List<String>): Boolean {
        val trimmed = result.trim()
        if (trimmed.isEmpty() || trimmed == "-") return true
        return prints(trimmed) && printsBesideTest(trimmed, names) && printsUnitBesideTest(trimmed, unit, names) &&
            !dropsQualifier(trimmed, names) && !isAnotherDatesResult(trimmed, names)
    }

    /**
     * Whether [result], a number, is printed on its test's rows only as an earlier date's result
     * beside the current one: "Glucose  65 Low  92  04/02/2026" prints 92 as the result of
     * 04/02/2026 (a date right behind it, flags aside) and 65, with no date, as this report's.
     * A row whose only result is dated ("Glukose 92 03.11.2026") has no other to be the current
     * one, so nothing is claimed there; nor where no row of the test prints the number.
     */
    fun isAnotherDatesResult(result: String, names: List<String>): Boolean {
        val trimmed = result.trim()
        if (trimmed.firstOrNull()?.isDigit() != true) return false
        val value = numberIn(trimmed, decimalSeparator)?.let { abs(it) } ?: return false
        val verdicts = rowsOf(names).mapNotNull { (index, named) -> lines[index].isDatedResult(value, named) }
        return verdicts.isNotEmpty() && verdicts.all { it }
    }

    /**
     * Whether [result], a plain number, is its test's result printed behind a "<" or ">":
     * ">60" read as 60. A row's result is the first printing of the number behind the
     * test's name (or the value the next line starts with, where the row goes on there),
     * so what the row prints later — a reference "> OR = 60", a previous result —
     * neither hides a dropped qualifier nor raises a false alarm ("LDL 100 H mg/dL <100").
     * Where no row of the test prints the number, nothing is claimed.
     */
    fun dropsQualifier(result: String, names: List<String>): Boolean {
        val trimmed = result.trim()
        if (trimmed.firstOrNull()?.isDigit() != true) return false
        val value = numberIn(trimmed, decimalSeparator)?.let { abs(it) } ?: return false
        val verdicts = rowsOf(names).mapNotNull { (index, named) ->
            lines[index].isQualifiedAfterName(value, named) ?: lines.getOrNull(index + 1)?.isQualifiedAtStart(value)
        }
        return verdicts.isNotEmpty() && verdicts.all { it }
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
     * On a row of several tests, only the test's own part counts, as for its unit:
     * "KREA: 0,80 mg/dl; HARNS: 5,1 mg/dl" prints 5,1, but not as Kreatinin's. Nor
     * is a number of the test's own name its result (the 12 of "Vitamin B12").
     */
    fun printsBesideTest(result: String, names: List<String>): Boolean {
        val trimmed = result.trim()
        if (trimmed.isEmpty() || trimmed == "-") return true
        val rows = rowsOf(names)
        val onItsRow = rows.any { (index, named) ->
            lines[index].printsInPartOf(trimmed, named) || lines.getOrNull(index + 1)?.startsWithResult(trimmed) == true
        }
        if (onItsRow) return true
        return rows.none { (index, named) -> lines[index].printsAResultBesides(named) }
    }

    /**
     * Whether [unit] is printed as [result]'s unit on the row of its test (by [names]),
     * the row [printsBesideTest] finds it on, or on the line that continues that row:
     * behind it, or right in front of it ("CRP mg/dl 0,3", "Glucose (mg/dL) 92").
     * "KREA: 0,80 mg/dl; GPT: 59 U/l" prints U/l, but not as Kreatinin's unit. A row
     * that prints no unit beside its result ("Hb 14,2  MCV 88") says nothing, and
     * where no row prints the result, the unit is looked for in the whole report
     * ([printsUnit]). No result or no unit claims nothing.
     */
    fun printsUnitBesideTest(result: String, unit: String, names: List<String>): Boolean {
        val trimmed = result.trim()
        val wanted = unitForm(unit).filterNot { it.isWhitespace() }
        if (wanted.isEmpty() || trimmed.isEmpty() || trimmed == "-") return true
        val verdicts = rowsOf(names).flatMap { (index, _) ->
            listOfNotNull(
                lines[index].takeIf { it.printsOnRow(trimmed) }?.unitBehind(trimmed, wanted),
                // The line that continues the row, starting with its result: the test isn't named there.
                lines.getOrNull(index + 1)?.takeIf { it.startsWithResult(trimmed) }?.unitBehind(trimmed, wanted),
            )
        }
        return when {
            true in verdicts -> true
            false in verdicts -> false
            else -> printsUnit(unit)
        }
    }

    /**
     * The lines that name a test by any of [names] (the model's test name and printed
     * code), with the names each one prints. Where some line starts with one, only
     * those: "HDL Cholesterin" names Cholesterin too.
     */
    private fun rowsOf(names: List<String>): List<Pair<Int, List<String>>> {
        val wanted = names.map { normalize(it).filterNot { char -> char.isWhitespace() } }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return emptyList()
        val naming = lines.indices.mapNotNull { index ->
            val named = wanted.filter { lines[index].names(it) }
            if (named.isEmpty()) null else index to named
        }
        val labelled = naming.filter { (index, named) -> named.any { lines[index].startsWithName(it) } }
        return labelled.ifEmpty { naming }
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
    private class Printed(private val text: String, private val decimalSeparator: Char?) {
        private val undated: String = undated(text)
        private val numbers: Set<Double> = numbersIn(undated, decimalSeparator)

        /** The numbers a row can have as its result: not the limits of a reference range. */
        private val results: Set<Double> by lazy { numbersIn(undated.replace(RANGE, " "), decimalSeparator) }
        private val normalized: String = normalize(text)

        /** The text with its dates blanked out, so that every position is still the printed one. */
        private val blanked: String by lazy { blankDates(text) }

        /** Each number printed, where it is and what it reads as, without its sign. */
        private val tokens: List<NumberToken> by lazy {
            NUMBER.findAll(blanked).filterNot { isInsideWord(blanked, it.range) }
                .map { match -> NumberToken(match.range, numbersIn(match.value, decimalSeparator).mapTo(mutableSetOf()) { abs(it) }) }
                .toList()
        }

        private val unitIndexed: Pair<String, IntArray> by lazy { unitFormIndexed(text) }

        fun prints(result: String): Boolean = prints(result, numbers)

        /**
         * Whether [wanted] (a unit in [unitForm], without spacing) is the unit printed for [result]
         * here ([unitOf]): true where it is, false where another unit is printed there instead,
         * null where this line can't tell (no unit beside the result, or the result isn't found
         * as a number).
         */
        fun unitBehind(result: String, wanted: String): Boolean? {
            val value = numberIn(result.trim().trimStart(*THRESHOLDS, '=').trim(), decimalSeparator)?.let { abs(it) } ?: return null
            val units = unitsAt(wanted)
            var verdict: Boolean? = null
            for (token in tokens.filter { value in it.values }) {
                val unit = unitOf(token) ?: continue
                if (units.any { it in unit }) return true
                verdict = false
            }
            return verdict
        }

        /**
         * Where the unit of the result at [token] is printed: the first unit behind it, before
         * a word that labels the next test ("0,80 mg/dl; HARNS: 5,1", "2.10  Free T4 (ng/dL) 1.21",
         * "0,3 bis 0,5") — flags and other numbers may stand between, a previous result's column
         * ("65 Low 92 mg/dL") or a reference range ("141 136 - 145 mmol/l"). Without one there,
         * the word right in front of it, where the unit's column comes first or the test's label
         * holds it ("CRP mg/dl 0,3", "Glucose (mg/dL) 92"). Null where neither is a unit.
         */
        private fun unitOf(token: NumberToken): IntRange? {
            for (word in SPACED_WORD.findAll(blanked, token.range.last + 1)) {
                val bare = word.value.trim(*UNIT_WRAPPING)
                if (isUnit(bare)) return word.range
                if (isLabel(bare)) break
            }
            val before = SPACED_WORD.findAll(blanked.substring(0, token.range.first)).lastOrNull() ?: return null
            return before.range.takeIf { isUnit(before.value.trim(*UNIT_WRAPPING)) }
        }

        /** Where [wanted] is printed as a whole unit, by its first character's position in the line. */
        private fun unitsAt(wanted: String): List<Int> {
            val (form, from) = unitIndexed
            return form.indices.filter { start ->
                val end = unitMatchEnd(form, wanted, start)
                end != null && unitStartsCleanly(form, start) && unitEndsCleanly(form, end)
            }.map { from[it] }
        }

        /**
         * Whether the first number behind the test's name (the first of [names], normalized
         * without spacing, printed here) that reads [value] has a "<" or ">" in front of it;
         * null where none reads it.
         */
        fun isQualifiedAfterName(value: Double, names: List<String>): Boolean? {
            val from = nameEnd(names)
            val token = tokens.firstOrNull { it.range.first >= from && value in it.values } ?: return null
            return signAt(blanked, token.range.first) == null
        }

        /** Where this line prints a date. */
        private val dateRanges: List<IntRange> by lazy {
            (LabParsePipeline.ISO_DATE.findAll(text) + LabParsePipeline.NUMERIC_DATE.findAll(text)).map { it.range }.toList()
        }

        /** Where this line prints a reference range ("70-99"), whose limits are no results. */
        private val rangeSpans: List<IntRange> by lazy { RANGE.findAll(blanked).map { it.range }.toList() }

        /**
         * Whether [value], behind the test's name (the first of [names] printed here), is printed only
         * as a dated result ([isDated]) while the row also prints an undated result before it — the
         * current column; null where no number behind the name reads [value], or the line has no date.
         */
        fun isDatedResult(value: Double, names: List<String>): Boolean? {
            if (dateRanges.isEmpty()) return null
            val from = nameEnd(names)
            val results = tokens.filter { token -> token.range.first >= from && rangeSpans.none { token.range.first in it } }
            val reading = results.filter { value in it.values }.ifEmpty { return null }
            val firstDated = reading.firstOrNull { isDated(it) } ?: return false
            return reading.all { isDated(it) } && results.any { it.range.last < firstDated.range.first && !isDated(it) }
        }

        /** Whether a date follows the number at [token], with only spacing and a high/low flag between ("92  04/02/2026", "7,6 L 18.08.2026"). */
        private fun isDated(token: NumberToken): Boolean {
            val from = token.range.last + 1
            val gap = DATED_GAP.find(text.substring(from))?.value?.length ?: 0
            return dateRanges.any { it.first == from + gap }
        }

        /** Whether the value this line starts with ([LEADING_VALUE]) is [value] behind a "<" or ">"; null where it doesn't start with [value]. */
        fun isQualifiedAtStart(value: Double): Boolean? {
            val leading = LEADING_VALUE.find(blanked)?.value?.trim() ?: return null
            val number = numberIn(leading.trimStart(*THRESHOLDS, '=').trim(), decimalSeparator)?.let { abs(it) } ?: return null
            return if (number == value) leading.first() in THRESHOLDS else null
        }

        /** Where the row's label ends: the first word, where it holds a letter; null where the line starts otherwise. */
        fun labelEnd(): Int? = SPACED_WORD.find(blanked)?.takeIf { word -> word.value.any { it.isLetter() } }?.let { it.range.last + 1 }

        /**
         * The results this line prints in the test's own part behind [from], where its name ends
         * ([partFrom]): numbers and thresholds, not a reference range's limits nor a unit's
         * numbers ("10^9/l", "x10E3/uL", "mL/min/1.73m2"). None on a line that starts with a value.
         */
        fun resultsFrom(from: Int): List<PrintedResult> {
            if (LEADING_VALUE.containsMatchIn(undated)) return emptyList()
            val part = partFrom(from)
            return part.tokens.filter { token -> part.rangeSpans.none { token.range.first in it } && !isOfAUnit(part.blanked, token.range) }
                .map { PrintedResult(it.range.first + from..it.range.last + from, it.values, qualified = signAt(part.blanked, it.range.first) == null) }
        }

        /**
         * Where the first of [names] printed here ends, as a position of the printed text;
         * 0 where normalizing changed the line's length, so positions can't be compared.
         */
        fun nameEnd(names: List<String>): Int {
            if (normalized.length != text.length) return 0
            val start = normalized.indices.firstOrNull { start -> names.any { namesAt(it, start) } } ?: return 0
            return names.filter { namesAt(it, start) }.mapNotNull { matchEnd(normalized, it, start) }.maxOrNull() ?: 0
        }

        /** Whether this prints [result] as a row's result: as [prints], but a reference range's limit isn't one. */
        fun printsOnRow(result: String): Boolean = prints(result, results)

        /**
         * Whether this prints [result] as the result of the test named by [names] (normalized,
         * without spacing): in the test's own part of the line ([partsOf]) where one prints a
         * result. Where none does (the name stands behind its result, or positions can't be
         * compared), the whole line decides, but a number of the test's own name (the 12 of
         * "Vitamin B12") is still no result of it. So does it for a result in words
         * ("negative" of "Toxoplasma IgG  negative  <3.0 IU/mL": a part keeps only numbers
         * apart) and on a line that starts with a result ("141 mmol/l Natrium  4,1 mmol/l
         * Kalium": its names stand behind their results, so a name's part is the next test's).
         */
        fun printsInPartOf(result: String, names: List<String>): Boolean {
            if (result.none { it.isDigit() } || LEADING_VALUE.containsMatchIn(undated)) return printsOnRow(result)
            val parts = partsOf(names).filter { it.printsAResultBesides(emptyList()) }
            if (parts.isNotEmpty()) return parts.any { it.printsOnRow(result) }
            val ofNames = names.flatMapTo(mutableSetOf()) { numbersIn(it, decimalSeparator) }
            return prints(result, results - ofNames)
        }

        /**
         * The test's own parts of this line, one behind each place one of [names] is printed: from
         * the name's end up to the next test's label behind its result ("0,80 mg/dl;" of "KREA:
         * 0,80 mg/dl; HARNS: 5,1 mg/dl"), as [unitOf] reads a result's unit. Words between the name
         * and its result are still the name's and blanked out: the 25 of "Vitamin D 25-OH 32", the
         * "nüchtern" of "Glucose nüchtern: 104". None where normalizing changed the line's length.
         */
        private fun partsOf(names: List<String>): List<Printed> {
            if (normalized.length != text.length) return emptyList()
            val ends = normalized.indices.mapNotNull { start ->
                names.filter { namesAt(it, start) }.mapNotNull { matchEnd(normalized, it, start) }.maxOrNull()
            }
            return ends.distinct().map { partFrom(it) }
        }

        /**
         * The part of [partsOf] that starts where a name ends, at [from]. A whole number one space
         * in front of a word may still be the name's ("Vitamin D 25 OH   32 ng/ml"), so that word
         * doesn't end the part; a decimal, a unit or flag, a column's gap or a ";" in between does.
         */
        private fun partFrom(from: Int): Printed {
            val part = StringBuilder(text)
            var end = text.length
            var inResult = false
            var previous: MatchResult? = null
            for (word in PART_WORD.findAll(blanked, from)) {
                val bare = word.value.trim(*UNIT_WRAPPING)
                val labels = isLabel(bare) && !isUnit(bare)
                val ofTheName = previous != null && previous.value.all { it.isDigit() } &&
                    word.range.first == previous.range.last + 2 && blanked[previous.range.last + 1] == ' '
                when {
                    inResult && labels && !ofTheName -> { end = word.range.first; break }
                    inResult -> Unit
                    labels -> for (at in word.range) part[at] = ' '
                    word.value.any { it.isDigit() } -> inResult = true
                }
                previous = word
            }
            return Printed(part.substring(from, end), decimalSeparator)
        }

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

    /** A number printed at [range] of a line, and what it reads as. */
    private class NumberToken(val range: IntRange, val values: Set<Double>)

    /** A result a row prints: where on its line, what it reads as without its sign, and whether behind a "<" or ">". */
    private class PrintedResult(val range: IntRange, val values: Set<Double>, val qualified: Boolean)

    private companion object {
        /** A word: a run of letters. */
        val WORD = Regex("\\p{L}+")

        /** What a row prints beside a result that is no unit: a high/low flag, also written out, the x of a power, a sign's words. */
        val FLAG_WORDS = setOf("h", "l", "hh", "ll", "a", "n", "x", "hi", "lo", "high", "low", "abn", "pos", "neg")

        /** Units printed as a bare word, without a unit's marks: "fl", "pg", "U". */
        val BARE_UNITS = setOf("u", "iu", "mu", "miu", "fl", "pg", "g", "mg", "ug", "µg", "μg", "ng", "kg", "ml", "s", "sec", "min", "mm", "mmhg")

        /** What a unit is wrapped in on a row: "(mmol/l)", "mg/dl;". */
        val UNIT_WRAPPING = charArrayOf('(', ')', '[', ']', ';', ',', ':')

        /** A run of printed characters between spaces, a no-break space too. */
        val SPACED_WORD = Regex("[^\\s\\u00A0\\u202F]+")

        /** A [SPACED_WORD] that a colon or semicolon also ends: "KREA:0,80mg/dl;" is a label, a result and a unit. */
        val PART_WORD = Regex("[^\\s\\u00A0\\u202F:;]+")

        /**
         * Whether [word] labels a test: it holds a word of two letters or more that isn't a flag
         * ("MCV", "Free", "bis"), or letters and digits together ("T4", "B12").
         */
        fun isLabel(word: String): Boolean =
            WORD.findAll(word).any { it.value.length >= 2 && it.value.lowercase() !in FLAG_WORDS } ||
                (word.any { it.isLetter() } && word.any { it.isDigit() })

        /** Whether [word] is shaped like a unit: with a unit's marks ("mg/dl", "10^9/l", "%", "m²") or one of the [BARE_UNITS]. */
        fun isUnit(word: String): Boolean {
            val marked = word.any { it in "/%^" || it in SUPERSCRIPT_DIGITS } && (word.any { it.isLetter() } || '%' in word)
            return marked || word.lowercase() in BARE_UNITS
        }

        /**
         * Whether the number at [range] of [text] is part of a unit: behind a letter or a unit's
         * mark ("x10", "10E3", "/1.73", "^9") or a power's base ("10^9", "10*3").
         */
        fun isOfAUnit(text: String, range: IntRange): Boolean {
            val before = text.getOrNull(range.first - 1)
            val after = text.getOrNull(range.last + 1)
            return before != null && (before.isLetter() || before in "/^*×·") || after != null && (after in "^*" || after in SUPERSCRIPT_DIGITS)
        }

        /** [text] with its dates blanked out, character for character. */
        fun blankDates(text: String): String =
            text.replace(LabParsePipeline.ISO_DATE) { " ".repeat(it.value.length) }
                .replace(LabParsePipeline.NUMERIC_DATE) { " ".repeat(it.value.length) }

        /** [unitForm] of [text], with the position in [text] each of its characters comes from. */
        fun unitFormIndexed(text: String): Pair<String, IntArray> {
            val form = StringBuilder(text.length)
            val from = mutableListOf<Int>()
            text.forEachIndexed { index, char ->
                for (formed in unitForm(char.toString())) {
                    form.append(formed)
                    from += index
                }
            }
            return form.toString() to from.toIntArray()
        }

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

        /** What may stand between a result and the date of its column: spacing and a high/low flag. */
        val DATED_GAP = Regex("^[\\s\\u00A0]*(?:(?:hh|ll|h|l|high|low|[*↑↓!])[\\s\\u00A0]+)*", RegexOption.IGNORE_CASE)

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
