package dev.idoodler.labimporter.core.model

import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** A saved report — what gets persisted to, and read back from, the health store. */
@Serializable
data class LabReport(
    val id: String,
    val date: LocalDate,
    val patientName: String,
    val authorName: String,
    val entries: List<Entry>,
    /**
     * Values stored with this report that this build couldn't read, so [entries] isn't all of it
     * (a later version wrote something this one doesn't know, or a value is damaged). Saving the
     * report again would drop them for good, so it's [isReadOnly].
     */
    val unreadValues: Int = 0,
    /** The stored records of [unreadValues] that have an id (Health Connect), so deleting the report removes them too. */
    val unreadRecordIds: List<String> = emptyList(),
) {
    /** Shown and deleted, never edited or replaced: a new version would lose the [unreadValues]. */
    val isReadOnly: Boolean
        get() = unreadValues > 0

    @Serializable
    data class Entry(
        val id: String,
        val code: String,
        val name: String,
        val displayValue: String,
        val numericValue: Double?,
        val unit: String,
    ) {
        val resolvedName: String
            get() = LabMapping.displayName(code).let { if (it == code) name else it }

        /** Whether there is a result to store: a number or a qualitative result. */
        val hasResult: Boolean
            get() = numericValue != null || QualitativeResult.of(displayValue) != null
    }

    /** Bridges to editable values for the review flow. */
    val asLabValues: List<LabValue>
        get() = entries.map {
            LabValue(
                id = it.id, code = it.code, name = it.resolvedName, displayValue = it.displayValue,
                numericValue = it.numericValue, unit = it.unit,
            )
        }

    /** The category most values belong to (ties break by canonical order). */
    val dominantCategory: LabCategory?
        get() = LabCategory.ranked(entries.map { LabCategory.forCode(it.code) }).firstOrNull()

    companion object {
        /** Builds a report from reviewed values, keeping only exportable rows (one per LOINC). */
        fun fromValues(
            values: List<LabValue>,
            date: LocalDate,
            patientName: String,
            authorName: String,
            id: String = Uuid.random().toString(),
        ): LabReport = LabReport(
            id = id,
            date = date,
            patientName = patientName.trim(),
            authorName = authorName.trim(),
            entries = values.deduplicatedByLoinc().filter { it.isExportable }.map {
                val loinc = LabMapping.loincCode(it.code)!!.loinc
                Entry(
                    id = it.id, code = loinc, name = it.name,
                    displayValue = it.numericValue?.let(NumberFormatting::exactValue) ?: it.qualitativeResult!!,
                    numericValue = it.numericValue, unit = it.unit.trim(),
                )
            },
        )
    }
}

/** The most recent numeric reading for [code] across every report. */
fun List<LabReport>.latestEntry(code: String): Pair<LabReport.Entry, LocalDate>? {
    var latest: Pair<LabReport.Entry, LocalDate>? = null
    for (report in this) {
        for (entry in report.entries) {
            if (entry.code != code || entry.numericValue == null) continue
            val current = latest
            if (current != null && current.second >= report.date) continue
            latest = entry to report.date
        }
    }
    return latest
}

/** Distinct codes with at least one numeric reading. */
val List<LabReport>.distinctNumericCodes: Set<String>
    get() = flatMap { r -> r.entries.filter { it.numericValue != null }.map { it.code } }.toSet()
