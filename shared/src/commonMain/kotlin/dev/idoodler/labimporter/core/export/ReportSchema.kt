package dev.idoodler.labimporter.core.export

import dev.idoodler.labimporter.core.model.LabReport

/**
 * Version of the conventions every LabImporter export follows — the CDA
 * documents in Apple Health (stamped as `LabImporter CDA v<N>`) and the FHIR
 * resources in Health Connect / transfer files (a `meta.tag`). One version for
 * both formats, because a migration changes what a *report* means, not how it
 * is serialized.
 *
 * Whenever an export's meaning changes (a LOINC remap, a unit convention, …):
 * bump [VERSION] and register a [ReportMigration] from the old version.
 * Reports read back below [MINIMUM_SUPPORTED_VERSION], newer than this build,
 * or without any version stamp (legacy exports) are ignored — there is no
 * implicit data migration.
 */
object ReportSchema {
    const val VERSION: Int = 1
    const val MINIMUM_SUPPORTED_VERSION: Int = 1

    /** Ordered single-step migrations. Empty: version 1 is the baseline. */
    val migrations: List<ReportMigration> = emptyList()

    /** [report] upgraded to [VERSION], or null when the source version isn't supported. */
    fun upgrade(report: LabReport, fromVersion: Int?): LabReport? {
        if (fromVersion == null || fromVersion < MINIMUM_SUPPORTED_VERSION || fromVersion > VERSION) return null
        var current = report
        for (version in fromVersion until VERSION) {
            val step = migrations.firstOrNull { it.fromVersion == version } ?: return null
            current = step.migrate(current)
        }
        return current
    }
}

/** Upgrades a report written by [fromVersion] to `fromVersion + 1`. */
interface ReportMigration {
    val fromVersion: Int
    fun migrate(report: LabReport): LabReport
}
