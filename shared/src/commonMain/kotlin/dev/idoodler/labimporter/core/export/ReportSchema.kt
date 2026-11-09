package dev.idoodler.labimporter.core.export

import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.LabReport

/**
 * Version of the conventions every LabImporter export follows — the CDA
 * documents in Apple Health (stamped as `LabImporter CDA v<N>`) and the FHIR
 * resources in Health Connect / transfer files (a `meta.tag`). One version for
 * both formats, because a migration changes what a *report* means, not how it
 * is serialized.
 *
 * Whenever an export's meaning changes (a LOINC remap, a unit convention, …):
 * bump [VERSION] and register a [ReportMigration] from the old version. So does
 * any change that older builds can't read in full (a new value type, a new kind
 * of observation), even an "additive" one: an older build then ignores such
 * reports instead of listing them in part. One it still lists in part is
 * read-only there ([LabReport.isReadOnly]), since saving it would drop the rest.
 * Reports read back below [MINIMUM_SUPPORTED_VERSION] or without any version
 * stamp (legacy exports) are ignored — there is no implicit data migration.
 * Reports newer than this build aren't listed either, but are counted
 * ([StoredReports]): updating the app brings them back, so the user is told.
 */
object ReportSchema {
    const val VERSION: Int = 2
    const val MINIMUM_SUPPORTED_VERSION: Int = 1

    /** Ordered single-step migrations; version 1 is the baseline. */
    val migrations: List<ReportMigration> = listOf(CountUnitMigration)

    /** Written by a later build: nothing here can read it, and updating the app will. */
    fun isNewer(version: Int): Boolean = version > VERSION

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

/**
 * What reading stored reports found: the [reports] it can list, and how many it can't.
 * Those stay stored; a store never deletes what it couldn't read.
 */
data class StoredReports(
    val reports: List<LabReport>,
    /** Saved by a newer version of the app, so updating it brings them back. */
    val newerVersionReports: Int = 0,
    /** Damaged in some way that a version of this schema can't explain. */
    val unreadableReports: Int = 0,
)

/** Why a stored document of this app didn't become a report. */
enum class ReportReadProblem { NEWER_VERSION, UNREADABLE }

/** Upgrades a report written by [fromVersion] to `fromVersion + 1`. */
interface ReportMigration {
    val fromVersion: Int
    fun migrate(report: LabReport): LabReport
}

/**
 * Version 1 → 2: version 1 exported "G/L" (giga-particles per litre, how European labs print
 * leukocytes and platelets) as grams per litre, `g/L`, and a printed annotation ("mg/dL (calc)")
 * as part of the unit. A count per volume (LOINC property [Ucum.NUMBER_CONCENTRATION]) in g/L
 * can only have been one of those counts, so it reads as [Ucum.GIGA_PER_LITRE]; a real mass
 * concentration in g/L (protein) stays as it is. Results in words keep their unit, as stored.
 */
object CountUnitMigration : ReportMigration {
    override val fromVersion: Int = 1

    override fun migrate(report: LabReport): LabReport = report.copy(
        entries = report.entries.map { entry ->
            if (entry.numericValue == null) return@map entry
            val unit = Ucum.withoutAnnotation(entry.unit)
            entry.copy(unit = if (unit in GRAMS_PER_LITRE && isCount(entry.code)) Ucum.GIGA_PER_LITRE else unit)
        },
    )

    /** How version 1 wrote grams per litre: the CDA's UCUM code, or the printed unit in FHIR's `unit`. */
    private val GRAMS_PER_LITRE = setOf("g/L", "g/l")

    private fun isCount(code: String): Boolean = LabCore.loinc?.detail(code)?.property == Ucum.NUMBER_CONCENTRATION
}
