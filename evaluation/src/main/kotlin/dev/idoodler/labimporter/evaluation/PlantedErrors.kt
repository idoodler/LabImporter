package dev.idoodler.labimporter.evaluation

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
 * a planted date when it doesn't come out as the planted date (it's corrected or left
 * empty, which Review shows). Controls are the right values, as read and in harmless
 * spellings: a flag on one of them is a false alarm.
 */

/** The ways a value or date is planted wrong. */
enum class Fault(val label: String) {
    TENFOLD("tenfold"),
    TENTH("a tenth"),
    DIGIT_DROPPED("digit dropped"),
    DIGIT_DOUBLED("digit doubled"),
    NEIGHBOUR("neighbouring test's value"),
    QUALIFIER_DROPPED("\"<\"/\">\" dropped"),
    WRONG_UNIT("another test's unit"),
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
            others.firstOrNull { it.isNotBlank() && !unitMatches(it, entry.unit) }
                ?.let { add(Fault.WRONG_UNIT to entry.copy(unit = it)) }
        }
    }
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
)

/** One report's material: its text, the model's reply, what it really says. */
class PlantedReport(val name: String, val text: String, val reply: AiLabReport, val expected: Expected)

private class Outcome(val kind: Fault, val flagged: Boolean, val what: String)

/** One entry through the shared post-processing, on its own: the Review check looks at each value alone. */
private fun processed(report: PlantedReport, entry: AiLabEntry): LabValue? =
    LabParsePipeline.postProcess(report.reply.copy(entries = listOf(entry), reportDate = ""), report.text).values.singleOrNull()

/** What [value] rightly reads as: the expected value (with its alternatives) or text result it matches, or null when it's wrong. */
private fun rightReading(value: LabValue, expected: Expected): List<Alternative>? {
    if (value.numericValue != null) {
        return expected.values.map { listOf(Alternative(it.value, it.unit)) + it.alternatives }
            .firstOrNull { options -> options.any { value.matchesValue(it.value, it.unit) } }
    }
    return if (value.qualitativeResult != null && expected.notNumeric.any { value.matchesName(it.names) }) emptyList() else null
}

private fun isRight(value: LabValue, options: List<Alternative>) =
    if (options.isEmpty()) value.numericValue == null && value.qualitativeResult != null else options.any { value.matchesValue(it.value, it.unit) }

fun plantErrors(reports: List<PlantedReport>): PlantedSummary {
    val outcomes = mutableListOf<Outcome>()
    var controls = 0
    var falseFlags = 0
    for (report in reports) {
        val entries = report.reply.entries
        for (index in entries.indices) {
            val read = processed(report, entries[index]) ?: continue
            val options = rightReading(read, report.expected) ?: continue
            for ((kind, faulty) in plantedFaults(entries, index)) {
                val value = processed(report, faulty) ?: continue
                // A fault that still reads right (the value printed in a second unit) is no error.
                if (isRight(value, options)) continue
                outcomes += Outcome(kind, value.needsSourceCheck, "${report.name}: ${faulty.name} '${faulty.rawValue}' ${faulty.unit} (read '${entries[index].rawValue}' ${entries[index].unit})")
            }
            for ((_, respelled) in controls(entries[index])) {
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
        val date = report.expected.reportDate?.let(LocalDate::parse) ?: continue
        if (LabParsePipeline.postProcess(report.reply, report.text).reportDate != date) continue
        for ((kind, planted) in plantedDates(date)) {
            val read = LabParsePipeline.postProcess(report.reply.copy(reportDate = planted.toString()), report.text).reportDate
            outcomes += Outcome(kind, read != planted, "${report.name}: $planted (printed $date)")
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
    )
}
