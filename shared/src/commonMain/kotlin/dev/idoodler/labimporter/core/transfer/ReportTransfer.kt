package dev.idoodler.labimporter.core.transfer

import dev.idoodler.labimporter.core.export.CdaParser
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.ReportReadProblem
import dev.idoodler.labimporter.core.export.Ucum
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.QualitativeResult
import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Moves reports between devices and platforms (iPhone ⇄ Android) as a file the
 * user carries themselves — there is no server and no account.
 *
 * The file is a standard FHIR R4 `collection` Bundle of the reports' laboratory
 * Observations (the same resources Health Connect stores), so it is readable
 * by other health software too. Importing also accepts a single LabImporter
 * CDA document — the "Share Report File" output — from either app.
 *
 * Importing is idempotent: a report whose [fingerprint] already exists is
 * skipped, so the same file can be imported twice, or sent back and forth,
 * without creating duplicates.
 */
object ReportTransfer {
    const val MIME_TYPE = "application/fhir+json"
    const val FILE_EXTENSION = "json"

    private const val BUNDLE_TAG_SYSTEM = "https://github.com/idoodler/LabImporter/fhir/transfer"
    private val json = Json { ignoreUnknownKeys = true }

    /** Suggested file name for an export made on [date]. */
    fun fileName(date: LocalDate): String = "LabImporter-$date.$FILE_EXTENSION"

    /**
     * A transfer file's [contents]. Only a value this build can write as a FHIR resource goes in;
     * the caller must act on the rest: [leftOutResults] are values the user would otherwise
     * think they carried over, and with no [writtenResults] the file is empty.
     */
    data class Export(val contents: String, val writtenResults: Int, val leftOutResults: Int)

    /** Every report as one FHIR Bundle (JSON). */
    fun export(reports: List<LabReport>, softwareIdentity: String): Export {
        var written = 0
        var leftOut = 0
        val bundle = buildJsonObject {
            put("resourceType", "Bundle")
            put("type", "collection")
            put("meta", buildJsonObject {
                put("source", softwareIdentity)
                put("tag", buildJsonArray {
                    add(buildJsonObject { put("system", BUNDLE_TAG_SYSTEM); put("code", "reports") })
                })
            })
            put("entry", buildJsonArray {
                for (report in reports.sortedBy { it.date }) {
                    val resources = FhirLabCodec.encode(report, softwareIdentity)
                    written += resources.size
                    leftOut += (report.entries.count { it.hasResult } - resources.size).coerceAtLeast(0)
                    for (resource in resources) {
                        add(buildJsonObject {
                            put("fullUrl", "urn:uuid:${resource.id}")
                            put("resource", json.parseToJsonElement(resource.json))
                        })
                    }
                }
            })
        }
        return Export(bundle.toString(), written, leftOut)
    }

    /** What a file contained. */
    sealed interface ReadResult {
        /**
         * Reports to import (may be empty when the file had no usable values), how many more the
         * file holds that only a newer version of the app can read, and how many it holds damaged.
         * A damaged report can be among [reports] with the values that could be read, so the
         * caller must say so: the summary would otherwise read like the whole file came across.
         */
        data class Reports(val reports: List<LabReport>, val newerVersionReports: Int = 0, val unreadableReports: Int = 0) : ReadResult

        /** A LabImporter file with only reports from a newer version of the app. */
        data object NewerVersion : ReadResult

        /** Not a LabImporter transfer file or CDA document (or an unsupported version). */
        data object Unsupported : ReadResult
    }

    /** Reads a transfer Bundle or a single LabImporter CDA document. */
    fun read(content: String): ReadResult {
        val trimmed = content.trimStart().removePrefix("﻿")
        if (CdaParser.looksLikeCda(trimmed)) {
            val report = CdaParser.parse(trimmed)
                ?: return if (CdaParser.problem(trimmed) == ReportReadProblem.NEWER_VERSION) ReadResult.NewerVersion else ReadResult.Unsupported
            return ReadResult.Reports(listOf(report))
        }
        // Not JSON: reported as the file not being one of ours.
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return ReadResult.Unsupported
        if ((root["resourceType"] as? JsonPrimitive)?.contentOrNull != "Bundle") return ReadResult.Unsupported
        val resources = (root["entry"] as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.get("resource")?.toString()
        }
        val stored = FhirLabCodec.decodeStored(resources)
        if (resources.isNotEmpty() && stored.reports.isEmpty()) {
            return if (stored.newerVersionReports > 0) ReadResult.NewerVersion else ReadResult.Unsupported
        }
        return ReadResult.Reports(stored.reports, stored.newerVersionReports, stored.unreadableReports)
    }

    /**
     * Content identity of a report: its date plus every reading (LOINC code,
     * value at four significant digits as in older exports, or qualitative result; UCUM unit). Independent
     * of storage ids, patient/lab names and of how either platform spells units.
     */
    fun fingerprint(report: LabReport): String =
        report.date.toString() + "|" + report.entries
            .mapNotNull { entry ->
                val value = entry.numericValue?.let(NumberFormatting::cdaValue)
                    ?: QualitativeResult.of(entry.displayValue)?.let { QualitativeResult.stored(it, entry.unit).lowercase() }
                value?.let { "${entry.code}=$it${if (entry.numericValue != null) Ucum.fromPrinted(entry.unit) else ""}" }
            }
            .sorted()
            .joinToString(";")

    /** Which incoming reports are new, and how many were already present. */
    data class ImportPlan(val toImport: List<LabReport>, val alreadyPresent: Int)

    fun plan(existing: List<LabReport>, incoming: List<LabReport>): ImportPlan {
        val known = existing.mapTo(mutableSetOf(), ::fingerprint)
        val fresh = mutableListOf<LabReport>()
        var duplicates = 0
        for (report in incoming) {
            if (known.add(fingerprint(report))) fresh += report else duplicates++
        }
        return ImportPlan(fresh, duplicates)
    }
}
