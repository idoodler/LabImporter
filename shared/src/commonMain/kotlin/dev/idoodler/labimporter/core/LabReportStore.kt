package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.model.LabReport

/**
 * Where saved reports live. There is no app database of record: on iOS this is
 * Apple Health (CDA documents), on Android Health Connect's Medical Records
 * (FHIR Observations) — with an on-device fallback where that isn't available.
 */
interface LabReportStore {
    suspend fun loadReports(): List<LabReport>
    suspend fun save(report: LabReport)
    suspend fun delete(report: LabReport)
}
