package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.model.LabReport

/**
 * Where saved reports live. There is no app database of record: on iOS this is
 * Apple Health (CDA documents), on Android Health Connect's Medical Records
 * (FHIR Observations) — with an on-device fallback where that isn't available.
 */
interface LabReportStore {
    suspend fun loadReports(): List<LabReport>

    /** Saves [report] whole, replacing what's stored under its ids; throws if any of its results can't be stored. */
    suspend fun save(report: LabReport)
    suspend fun delete(report: LabReport)

    /**
     * Second half of replacing [old], once [updated] has been saved: removes what the new
     * version no longer has. It's separate from [save] so a failure here can't undo a save.
     */
    suspend fun removeDropped(old: LabReport, updated: LabReport)

    /** Saved reports the last [loadReports] couldn't read. They stay stored and aren't listed. */
    val unreadableReports: Int get() = 0
}

/** The entries of [old] that [updated] no longer has, for stores that keep one record per entry. */
fun droppedEntryIds(old: LabReport, updated: LabReport): List<String> {
    val kept = updated.entries.mapTo(hashSetOf()) { it.id }
    return old.entries.map { it.id }.filterNot { it in kept }
}
