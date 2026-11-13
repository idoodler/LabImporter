package dev.idoodler.labimporter.evaluation

import dev.idoodler.labimporter.core.export.Ucum
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.parse.AiLabEntry
import dev.idoodler.labimporter.core.parse.AiLabReport
import dev.idoodler.labimporter.core.parse.LabParsePipeline
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/*
 * Planted errors: does Review's "compare with the report" check catch a wrong reading?
 *
 * The committed replies hold almost no wrong value, so they can't say. Here every value
 * the model read right is made wrong in the ways a reader goes wrong — deterministically,
 * no model needed — and each faulty reply goes through the shared post-processing. A
 * planted error counts as caught when the value comes out flagged (`needsSourceCheck`),
 * a planted date when it doesn't come out as the planted date unflagged (it's corrected,
 * left empty or flagged to check, which Review shows) — a flag only where the report's
 * right date comes out unflagged, else it tells nothing apart. Controls are the right
 * values, as read and in harmless spellings, and each report's right date: a flag on one
 * of them is a false alarm.
 */

/**
 * The day the evaluation reads its reports on: no report is dated after it, and a replay
 * reads the same whatever the calendar says. Move it on when a report dated later joins.
 */
val EVALUATION_DAY = LocalDate(2026, 11, 9)

/** The ways a value or date is planted wrong. */
enum class Fault(val label: String) {
    TENFOLD("tenfold"),
    TENTH("a tenth"),
    DIGIT_DROPPED("digit dropped"),
    DIGIT_DOUBLED("digit doubled"),
    NEIGHBOUR("neighbouring test's value"),
    QUALIFIER_DROPPED("\"<\"/\">\" dropped"),
    WRONG_UNIT("another test's unit"),
    DATE_COLUMN("value from another date column"),
    DATE_DAY("date a day off"),
    DATE_YEAR("date a year off"),
    DATE_SWAPPED("day and month swapped"),
}

/** A right value written the way a reader may also write it: never to be flagged. */
enum class Control { AS_READ, DECIMAL_SEPARATOR, LITRE_CASE }

private val PLAIN_NUMBER = Regex("^\\d+(?:[.,]\\d+)?$")
/** The litre of a unit ("mg/dl", "ml/min", "10^3/µL"), not the l of "mmol". */
private val LITRE = Regex("(?<=(?:^|/)[dmµμunpfc]?)[lL](?![A-Za-z])")
private val QUALIFIED = Regex("^\\s*[<>≤≥]=?\\s*(\\d.*)$")

private fun decimal(raw: String) = BigDecimal(raw.replace(',', '.'))

/** [value] written with [raw]'s decimal separator. */
private fun written(value: BigDecimal, raw: String) = value.toPlainString().let { if (',' in raw) it.replace('.', ',') else it }

/** A plain number with a changed value, or null when [candidate] isn't one or reads the same. */
private fun changedNumber(raw: String, candidate: String): String? {
    val cleaned = candidate.trimEnd('.', ',')
    if (!PLAIN_NUMBER.matches(cleaned)) return null
    val value = decimal(cleaned)
    return cleaned.takeIf { value.signum() != 0 && value.compareTo(decimal(raw)) != 0 }
}

/**
 * The faults planted in entry [index] of [entries], which reads a right value: each a
 * replacement entry. Only plain numbers ("4,52") get number faults, only a result with
 * "<"/">" loses it, and a wrong unit is the next other unit the report's entries carry.
 */
fun plantedFaults(entries: List<AiLabEntry>, index: Int): List<Pair<Fault, AiLabEntry>> {
    val entry = entries[index]
    val raw = entry.rawValue.trim()
    return buildList {
        if (PLAIN_NUMBER.matches(raw)) {
            add(Fault.TENFOLD to entry.copy(rawValue = written(decimal(raw).movePointRight(1), raw)))
            add(Fault.TENTH to entry.copy(rawValue = written(decimal(raw).movePointLeft(1), raw)))
            raw.indices.filter { raw[it].isDigit() }
                .firstNotNullOfOrNull { changedNumber(raw, raw.removeRange(it, it + 1)) }
                ?.let { add(Fault.DIGIT_DROPPED to entry.copy(rawValue = it)) }
            raw.indices.filter { raw[it].isDigit() }
                .firstNotNullOfOrNull { changedNumber(raw, raw.substring(0, it + 1) + raw.substring(it)) }
                ?.let { add(Fault.DIGIT_DOUBLED to entry.copy(rawValue = it)) }
            val neighbour = entries.getOrNull(index + 1) ?: entries.getOrNull(index - 1)
            neighbour?.rawValue?.trim()?.let { changedNumber(raw, it) }
                ?.let { add(Fault.NEIGHBOUR to entry.copy(rawValue = it)) }
        }
        QUALIFIED.matchEntire(raw)?.let { add(Fault.QUALIFIER_DROPPED to entry.copy(rawValue = it.groupValues[1].trim())) }
        if (entry.unit.isNotBlank()) {
            val others = entries.indices.map { entries[(index + 1 + it) % entries.size].unit }
            // Another unit, not the same one with a printed note ("mg/dL (calc)" is mg/dL, read right).
            others.firstOrNull { it.isNotBlank() && !unitMatches(Ucum.withoutAnnotation(it), Ucum.withoutAnnotation(entry.unit)) }
                ?.let { add(Fault.WRONG_UNIT to entry.copy(unit = it)) }
        }
    }
}

/**
 * The test's results for other dates ([ExpectedValue.otherDates]: a cumulative report's older
 * columns, a previous result) read for [entry]'s, a plain number: each written as [entry]'s is.
 */
fun otherDateFaults(entry: AiLabEntry, otherDates: List<Double>): List<Pair<Fault, AiLabEntry>> {
    val raw = entry.rawValue.trim()
    if (!PLAIN_NUMBER.matches(raw)) return emptyList()
    return otherDates.mapNotNull { other ->
        changedNumber(raw, written(BigDecimal(other.toString()).stripTrailingZeros(), raw))
            ?.let { Fault.DATE_COLUMN to entry.copy(rawValue = it) }
    }
}

/** A cumulative header's date, day first ("02-11-2026"): the tuning set's form, rewritten by [headerVariants]. */
private val HEADER_DATE = Regex("(?<![\\d.-])(\\d{2})[-./](\\d{2})[-./](\\d{4})(?![\\d.-])")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val TIMES = listOf("07:12", "09:30", "08:05", "10:45")

/**
 * [text]'s cumulative header — two or more day-first dates side by side, nothing but spacing between —
 * written the other ways reports print one: with the month's name ("02-Nov-26"), without a year
 * ("02.11."), with a time ("02.11.26 07:12 Uhr", wider, so the rows no longer line up), and one date a
 * line before the table. Each by name; none where [text] has no such header.
 */
fun headerVariants(text: String): List<Pair<String, String>> {
    val lines = text.lines()
    val at = lines.indexOfFirst { line ->
        val dates = HEADER_DATE.findAll(line).toList()
        dates.size >= 2 && dates.zipWithNext().all { (a, b) -> line.substring(a.range.last + 1, b.range.first).isBlank() } &&
            dates.all { it.groupValues[2].toInt() in 1..12 }
    }
    if (at < 0) return emptyList()
    val header = lines[at]
    fun withHeader(vararg replacement: String) = (lines.take(at) + replacement + lines.drop(at + 1)).joinToString("\n")
    fun rewritten(format: (MatchResult, Int) -> String): String {
        var index = 0
        return withHeader(HEADER_DATE.replace(header) { format(it, index++).padEnd(it.value.length) })
    }
    val dates = HEADER_DATE.findAll(header).map { it.value }.toList()
    return listOf(
        "month names" to rewritten { m, _ -> "${m.groupValues[1]}-${MONTHS[m.groupValues[2].toInt() - 1]}-${m.groupValues[3].takeLast(2)}" },
        "no year" to rewritten { m, _ -> "${m.groupValues[1]}.${m.groupValues[2]}." },
        "times with Uhr" to rewritten { m, i -> "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3].takeLast(2)} ${TIMES[i % TIMES.size]} Uhr" },
        "one date a line" to withHeader(HEADER_DATE.replace(header, "").replace(Regex("\\s{2,}"), "   ").trimEnd(), *dates.toTypedArray()),
    )
}

/** The date faults planted in a right report [date], as the reply would write them. */
fun plantedDates(date: LocalDate): List<Pair<Fault, LocalDate>> = buildList {
    add(Fault.DATE_DAY to date.plus(DatePeriod(days = 1)))
    add(Fault.DATE_YEAR to date.minus(DatePeriod(years = 1)))
    if (date.day <= 12 && date.day != date.month.number) {
        add(Fault.DATE_SWAPPED to LocalDate(date.year, date.day, date.month.number))
    }
}

/** A right value, as read and in spellings that mean the same: the comma for the point, l for L. */
fun controls(entry: AiLabEntry): List<Pair<Control, AiLabEntry>> = buildList {
    add(Control.AS_READ to entry)
    val raw = entry.rawValue.trim()
    if (PLAIN_NUMBER.matches(raw) && (',' in raw || '.' in raw)) {
        add(Control.DECIMAL_SEPARATOR to entry.copy(rawValue = if (',' in raw) raw.replace(',', '.') else raw.replace('.', ',')))
    }
    val litre = LITRE.replace(entry.unit) { if (it.value == "l") "L" else "l" }
    if (litre != entry.unit) add(Control.LITRE_CASE to entry.copy(unit = litre))
}

/** How many of one kind were planted, and how many Review flagged. */
@Serializable
data class KindScore(val planted: Int, val flagged: Int)

@Serializable
data class PlantedSummary(
    val planted: Int,
    val flagged: Int,
    val byKind: Map<String, KindScore>,
    /** Right values, as read and respelled; and how many of them Review flagged anyway. */
    val controls: Int,
    val falseFlags: Int,
    /** Reports' right dates that come out as themselves; and how many of them Review asks to check anyway. */
    val dateControls: Int = 0,
    val dateFalseFlags: Int = 0,
)

/** One report's material: its text, the model's reply, what it really says. */
class PlantedReport(val name: String, val text: String, val reply: AiLabReport, val expected: Expected)

private class Outcome(val kind: Fault, val flagged: Boolean, val what: String)

/** One entry through the shared post-processing, on its own: the Review check looks at each value alone. */
private fun processed(report: PlantedReport, entry: AiLabEntry): LabValue? =
    LabParsePipeline.postProcess(report.reply.copy(entries = listOf(entry), reportDate = ""), report.text, EVALUATION_DAY).values.singleOrNull()

/** What [value] rightly reads as: the expected value (with its alternatives) or text result it matches, or null when it's wrong. */
private fun rightReading(value: LabValue, expected: Expected): List<Alternative>? {
    if (value.numericValue != null) {
        return expected.values.map { listOf(Alternative(it.value, it.unit)) + it.alternatives }
            .firstOrNull { options -> options.any { value.matchesValue(it.value, it.unit) } }
    }
    if (value.qualitativeResult == null) return null
    return if (expected.notNumeric.any { value.matchesName(it.names) && it.readsAs(value.displayValue) }) emptyList() else null
}

private fun isRight(value: LabValue, options: List<Alternative>) =
    if (options.isEmpty()) value.numericValue == null && value.qualitativeResult != null else options.any { value.matchesValue(it.value, it.unit) }

/**
 * Plants errors in [reports]' right readings ([plantedFaults], [otherDateFaults], [plantedDates]); with
 * [withHeaderVariants], also another date's value under each [headerVariants] of a cumulative report's
 * header (the tuning set only: the held-out set is scored as written).
 */
fun plantErrors(reports: List<PlantedReport>, withHeaderVariants: Boolean = false): PlantedSummary {
    val outcomes = mutableListOf<Outcome>()
    var controls = 0
    var falseFlags = 0
    var dateControls = 0
    var dateFalseFlags = 0
    /** Plants in [report]'s right readings: every kind, or with [onlyOtherDates] only another date's value (and the reading as is as the control). */
    fun plantValues(report: PlantedReport, onlyOtherDates: Boolean) {
        val entries = report.reply.entries
        for (index in entries.indices) {
            val read = processed(report, entries[index]) ?: continue
            val options = rightReading(read, report.expected) ?: continue
            val otherDates = report.expected.values.firstOrNull { expected ->
                (listOf(Alternative(expected.value, expected.unit)) + expected.alternatives).any { read.matchesValue(it.value, it.unit) }
            }?.otherDates.orEmpty()
            val faults = otherDateFaults(entries[index], otherDates) + if (onlyOtherDates) emptyList() else plantedFaults(entries, index)
            for ((kind, faulty) in faults) {
                val value = processed(report, faulty) ?: continue
                // A fault that still reads right (the value printed in a second unit) is no error.
                if (isRight(value, options)) continue
                outcomes += Outcome(kind, value.needsSourceCheck, "${report.name}: ${faulty.name} '${faulty.rawValue}' ${faulty.unit} (read '${entries[index].rawValue}' ${entries[index].unit})")
            }
            val respellings = if (onlyOtherDates) listOf(Control.AS_READ to entries[index]) else controls(entries[index])
            for ((_, respelled) in respellings) {
                val value = processed(report, respelled) ?: continue
                // A respelling that changes the value isn't harmless ("1.018" as "1,018"): no control.
                if (!isRight(value, options)) continue
                controls++
                if (value.needsSourceCheck) {
                    falseFlags++
                    println("    FALSE ALARM ${report.name}: ${respelled.name} '${respelled.rawValue}' ${respelled.unit}")
                }
            }
        }
    }
    for (report in reports) {
        plantValues(report, onlyOtherDates = false)
        // The same cumulative table under the other headers reports print: another date's value must be caught there too.
        if (withHeaderVariants) {
            for ((shape, text) in headerVariants(report.text)) {
                plantValues(PlantedReport("${report.name} ($shape)", text, report.reply, report.expected), onlyOtherDates = true)
            }
        }
        val date = report.expected.reportDate?.let(LocalDate::parse) ?: continue
        // The right date as the control, whatever the model read: Review shouldn't ask about it.
        val right = LabParsePipeline.postProcess(report.reply.copy(reportDate = date.toString()), report.text, EVALUATION_DAY)
        val rightFlagged = right.reportDate == date && right.reportDateNeedsCheck
        if (right.reportDate == date) {
            dateControls++
            if (rightFlagged) {
                dateFalseFlags++
                println("    FALSE ALARM ${report.name}: date $date")
            }
        }
        if (LabParsePipeline.postProcess(report.reply, report.text, EVALUATION_DAY).reportDate != date) continue
        for ((kind, planted) in plantedDates(date)) {
            val read = LabParsePipeline.postProcess(report.reply.copy(reportDate = planted.toString()), report.text, EVALUATION_DAY)
            // Caught when corrected, left empty, or kept but flagged for the user to check — where the right date isn't.
            val caught = read.reportDate != planted || (read.reportDateNeedsCheck && !rightFlagged)
            outcomes += Outcome(kind, caught, "${report.name}: $planted (printed $date)")
        }
    }
    outcomes.filterNot { it.flagged }.forEach { println("    NOT FLAGGED ${it.kind.label}: ${it.what}") }
    return PlantedSummary(
        planted = outcomes.size,
        flagged = outcomes.count { it.flagged },
        byKind = Fault.entries.associate { kind ->
            kind.name to outcomes.filter { it.kind == kind }.let { KindScore(it.size, it.count { o -> o.flagged }) }
        },
        controls = controls,
        falseFlags = falseFlags,
        dateControls = dateControls,
        dateFalseFlags = dateFalseFlags,
    )
}
