package dev.idoodler.labimporter.core.parse

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * A cumulative report's header: the dates of its result columns, in the order printed
 * ("Analyse  Einheit  03.11.2026  18.08.2026  14.02.2026"). Found by [find]; whether the
 * rows below really print a result per date is [ReportSourceText.resultColumns]' call.
 *
 * @param dates each column's date.
 * @param spans where each date (with its time of day) is printed on the header line, to
 *   place a row's results under it; null when the dates stand one per line.
 * @param headerEnd the header's last line: the table's rows come after it.
 */
internal class ResultColumns(val dates: List<ColumnDate>, val spans: List<IntRange>?, val headerEnd: Int) {

    /** Whether the header's slashed or dashed dates put the day first: true, false, or null where none says. */
    private val dayFirst: Boolean? = run {
        val slashed = dates.filter { it.slashed }
        val dayShown = slashed.any { it.first > 12 && it.second <= 12 }
        val monthShown = slashed.any { it.second > 12 && it.first <= 12 }
        if (dayShown == monthShown) null else dayShown
    }

    /** The column whose date is the latest; null where that can't be told (day and month either way, no year and no order). */
    val newest: Int? = run {
        val orders = if (dayFirst != null || dates.none { it.slashed }) listOf(dayFirst ?: true) else listOf(true, false)
        orders.map { newestIn(dates.map { date -> date.reading(it) }) }.distinct().singleOrNull()
    }

    /** The column printed under [date]; null where none is. */
    fun columnOf(date: LocalDate): Int? = dates.indices.firstOrNull { column -> readings(column).any { it.matches(date) } }

    /** Whether [date] is the latest of the columns' dates: then it is clearly the report's. */
    fun isNewest(date: LocalDate): Boolean = newest?.let { column -> readings(column).any { it.matches(date) } } == true

    /** The column whose results are the report's: its date's, else the latest, else the first. */
    fun reportColumn(date: LocalDate?): Int = date?.let(::columnOf) ?: newest ?: 0

    private fun readings(column: Int): List<Reading> {
        val date = dates[column]
        return if (!date.slashed || dayFirst != null) {
            listOfNotNull(date.reading(dayFirst ?: true))
        } else {
            listOfNotNull(date.reading(true), date.reading(false)).distinct()
        }
    }

    /** A column's date as read: [year] null where the header prints none ("03.11."). */
    data class Reading(val year: Int?, val month: Int, val day: Int) {
        fun matches(date: LocalDate) = month == date.month.number && day == date.day && (year == null || year == date.year)
    }

    /**
     * A date as a header prints it: [first] and [second] in front of the year, the day first
     * unless [slashed] (02/11/26 may be either), [time] its time of day, if any.
     */
    data class ColumnDate(val first: Int, val second: Int, val year: Int?, val slashed: Boolean, val time: String) {
        fun reading(dayFirst: Boolean): Reading? {
            val (day, month) = if (dayFirst || !slashed) first to second else second to first
            return if (day in 1..31 && month in 1..12) Reading(year, month, day) else null
        }
    }

    companion object {
        /**
         * The header of [lines]' result columns: the longest run of two or more different dates
         * side by side, nothing but spacing (and a time of day: "07:12 Uhr") between them — or one
         * per line, alone on its line. Dates in ISO, numeric ("03.11.2026", "02/11/26"), without a
         * year ("03.11.") or with the month's name ("03-Nov-26"). A date more than five years
         * before the latest is a birth date, no result's ("Muster, Anna  12.03.1965  03.11.2026");
         * dates with words between are labelled ones ("Collected: … Reported: …", "von … bis").
         * Two-digit years are 20xx, or 19xx where that is after [today]'s year.
         */
        fun find(lines: List<String>, today: LocalDate?): ResultColumns? {
            val pivot = (today?.year ?: 2050) % 100
            val runs = buildList {
                lines.forEachIndexed { index, line ->
                    val dates = datesIn(line, pivot)
                    var run = mutableListOf<PrintedDate>()
                    for (date in dates) {
                        val last = run.lastOrNull()
                        if (last != null && !COLUMN_GAP.matches(line.substring(last.range.last + 1, date.range.first))) {
                            add(Run(run, index, index, sameLine = true))
                            run = mutableListOf()
                        }
                        run += date
                    }
                    if (run.isNotEmpty()) add(Run(run, index, index, sameLine = true))
                }
                // Dates one per line: a header the text put apart, cell by cell.
                var run = mutableListOf<PrintedDate>()
                var start = 0
                lines.forEachIndexed { index, line ->
                    val alone = datesIn(line, pivot).singleOrNull()?.takeIf {
                        line.substring(0, it.range.first).isBlank() && TRAILING_TIME.matches(line.substring(it.range.last + 1))
                    }
                    if (alone != null) {
                        if (run.isEmpty()) start = index
                        run += alone
                    } else {
                        if (run.size >= 2) add(Run(run, start, index - 1, sameLine = false))
                        run = mutableListOf()
                    }
                }
                if (run.size >= 2) add(Run(run, start, lines.lastIndex, sameLine = false))
            }
            return runs.mapNotNull { it.withoutBirthDates() }.maxByOrNull { it.dates.size }?.let { run ->
                ResultColumns(
                    dates = run.dates.map { it.date },
                    spans = if (run.sameLine) run.dates.map { it.span } else null,
                    headerEnd = run.last,
                )
            }
        }

        private class PrintedDate(val date: ColumnDate, val range: IntRange, val span: IntRange)

        private class Run(val dates: List<PrintedDate>, val first: Int, val last: Int, val sameLine: Boolean) {
            /** The run without dates more than five years before its latest, or null when fewer than two different dates are left. */
            fun withoutBirthDates(): Run? {
                val latest = dates.mapNotNull { it.date.year }.maxOrNull()
                val kept = dates.filter { latest == null || it.date.year == null || it.date.year >= latest - 5 }
                return if (kept.map { it.date }.distinct().size >= 2) Run(kept, first, last, sameLine) else null
            }
        }

        /** The dates printed on [line], left to right, each with its time of day ([PrintedDate.span]). */
        private fun datesIn(line: String, pivot: Int): List<PrintedDate> {
            val found = mutableListOf<Pair<IntRange, ColumnDate>>()
            fun year(text: String?): Int? = when {
                text.isNullOrEmpty() -> null
                text.length == 4 -> text.toInt()
                text.toInt() > pivot -> 1900 + text.toInt()
                else -> 2000 + text.toInt()
            }
            fun add(range: IntRange, date: ColumnDate) {
                if (found.none { it.first.first <= range.last && it.first.last >= range.first }) found += range to date
            }
            ISO.findAll(line).forEach { m ->
                val (y, month, day) = m.destructured
                add(m.range, ColumnDate(day.toInt(), month.toInt(), y.toInt(), slashed = false, time = ""))
            }
            NUMERIC.findAll(line).forEach { m ->
                val (first, separator, second, y) = m.destructured
                add(m.range, ColumnDate(first.toInt(), second.toInt(), year(y), slashed = separator != ".", time = ""))
            }
            NAMED.findAll(line).forEach { m ->
                val (day, name, y) = m.destructured
                val month = monthNamed(name) ?: return@forEach
                add(m.range, ColumnDate(day.toInt(), month, year(y), slashed = false, time = ""))
            }
            NO_YEAR.findAll(line).forEach { m ->
                val (day, month) = m.destructured
                add(m.range, ColumnDate(day.toInt(), month.toInt(), null, slashed = false, time = ""))
            }
            return found.filter { (_, date) -> date.reading(true) != null || date.reading(false) != null }
                .sortedBy { it.first.first }
                .map { (range, date) ->
                    val time = TIME_BEHIND.find(line.substring(range.last + 1))
                    val timeText = time?.groupValues?.get(1)?.filter { it.isDigit() }.orEmpty()
                    PrintedDate(date.copy(time = timeText), range, range.first..(range.last + (time?.value?.trimEnd()?.length ?: 0)))
                }
        }

        /** The latest of [readings]: by year where each has one, else by the order they're printed in, where that runs one way. */
        private fun newestIn(readings: List<Reading?>): Int? {
            if (readings.any { it == null }) return null
            val dates = readings.filterNotNull()
            if (dates.all { it.year != null }) {
                return dates.indices.maxWithOrNull(compareBy({ dates[it].year!! }, { dates[it].month }, { dates[it].day }))
            }
            val keys = dates.map { it.month * 100 + it.day }
            return when {
                keys.zipWithNext().all { (a, b) -> a > b } -> 0
                keys.zipWithNext().all { (a, b) -> a < b } -> keys.lastIndex
                else -> null
            }
        }

        /** The month [name] stands for ("Nov", "Okt.", "März", "juil"); null where it is no month's name. */
        private fun monthNamed(name: String): Int? {
            val lower = name.lowercase()
            MONTH_ABBREVIATIONS[lower]?.let { return it }
            return MONTH_NAMES.entries.firstOrNull { (_, names) -> lower in names }?.key
        }

        private val MONTH_ABBREVIATIONS: Map<String, Int> = buildMap {
            listOf(
                1 to "jan jän janv ene gen", 2 to "feb fév fev févr fevr", 3 to "mar mär mrt", 4 to "apr avr abr",
                5 to "may mai mag mei maj", 6 to "jun juin giu", 7 to "jul juil lug", 8 to "aug aoû aou ago",
                9 to "sep sept set", 10 to "oct okt ott out", 11 to "nov", 12 to "dec dez déc dic",
            ).forEach { (month, names) -> names.split(' ').forEach { put(it, month) } }
        }

        /** Months written out, in English, German, French, Spanish, Italian and Dutch. */
        private val MONTH_NAMES: Map<Int, Set<String>> = mapOf(
            1 to setOf("january", "januar", "janvier", "enero", "gennaio", "januari"),
            2 to setOf("february", "februar", "février", "fevrier", "febrero", "febbraio", "februari"),
            3 to setOf("march", "märz", "maerz", "mars", "marzo", "maart"),
            4 to setOf("april", "avril", "abril", "aprile"),
            5 to setOf("mayo", "maggio"),
            6 to setOf("june", "juni", "junio", "giugno"),
            7 to setOf("july", "juli", "juillet", "julio", "luglio"),
            8 to setOf("august", "août", "aout", "agosto", "augustus"),
            9 to setOf("september", "septembre", "septiembre", "settembre"),
            10 to setOf("october", "oktober", "octobre", "octubre", "ottobre"),
            11 to setOf("november", "novembre", "noviembre"),
            12 to setOf("december", "dezember", "décembre", "decembre", "diciembre", "dicembre"),
        )

        private val ISO = Regex("(?<![\\d.,/-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\d,/-]|\\.\\d)")
        private val NUMERIC = Regex("(?<![\\d.,/-])(\\d{1,2})([./-])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\d,/-]|\\.\\d)")

        /** "03.11." — a day and a month, each with its full stop, and no year. */
        private val NO_YEAR = Regex("(?<![\\d.,/-])(\\d{1,2})\\.(\\d{1,2})\\.(?![\\d,/-]|\\.\\d)")

        /** "03-Nov-26", "3. Nov. 2026", "18 August": a day, a month's name, maybe a year. */
        private val NAMED = Regex(
            "(?<![\\p{L}\\d.,/-])(\\d{1,2})(?:[-./]|[-./]?[ \\u00A0])(\\p{L}{3,9})\\.?(?:(?:[-./]|[ \\u00A0])(\\d{4}|\\d{2}))?(?![\\p{L}\\d])",
        )

        /** A time of day behind a date: "07:12", "07:12 Uhr", "0830", "9.30h". */
        private const val TIME = "(\\d{1,2}[:.]\\d{2}(?::\\d{2})?|\\d{4})(?:[\\s\\u00A0]*(?:uhr|h|hrs|am|pm)\\.?)?"

        /** What may stand between two date columns' headings: spacing, and the first one's time of day. */
        private val COLUMN_GAP = Regex("[\\s\\u00A0]+(?:$TIME[\\s\\u00A0]+)?", RegexOption.IGNORE_CASE)

        /** A time of day right behind a date: not the next column's date ("03.11.2026   18.08.2026"). */
        private val TIME_BEHIND = Regex("^[\\s\\u00A0]+$TIME(?![\\d\\p{L}.,:/-])", RegexOption.IGNORE_CASE)

        /** What may follow a date alone on its line: its time of day, nothing else. */
        private val TRAILING_TIME = Regex("(?:[\\s\\u00A0]+$TIME)?[\\s\\u00A0]*", RegexOption.IGNORE_CASE)
    }
}
