package dev.idoodler.labimporter.core.transfer

import dev.idoodler.labimporter.core.export.CdaParser
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.Ucum
import dev.idoodler.labimporter.core.model.LabReport
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
 * CDA document — the "Share CDA File" output — from either app.
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

    /** Every report as one FHIR Bundle (JSON). */
    fun export(reports: List<LabReport>, softwareIdentity: String): String {
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
                    for (resource in FhirLabCodec.encode(report, softwareIdentity)) {
                        add(buildJsonObject {
                            put("fullUrl", "urn:uuid:${resource.id}")
                            put("resource", json.parseToJsonElement(resource.json))
                        })
                    }
                }
            })
        }
        return bundle.toString()
    }

    /** What a file contained. */
    sealed interface ReadResult {
        /** Reports to import (may be empty when the file had no usable values). */
        data class Reports(val reports: List<LabReport>) : ReadResult

        /** Not a LabImporter transfer file or CDA document (or an unsupported version). */
        data object Unsupported : ReadResult
    }

    /** Reads a transfer Bundle or a single LabImporter CDA document. */
    fun read(content: String): ReadResult {
        val trimmed = content.trimStart().removePrefix("﻿")
        if (CdaParser.looksLikeCda(trimmed)) {
            val report = CdaParser.parse(trimmed) ?: return ReadResult.Unsupported
            return ReadResult.Reports(listOf(report))
        }
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return ReadResult.Unsupported
        if ((root["resourceType"] as? JsonPrimitive)?.contentOrNull != "Bundle") return ReadResult.Unsupported
        val resources = (root["entry"] as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.get("resource")?.toString()
        }
        if (resources.isNotEmpty() && FhirLabCodec.decode(resources).isEmpty()) return ReadResult.Unsupported
        return ReadResult.Reports(FhirLabCodec.decode(resources))
    }

    /**
     * Content identity of a report: its date plus every reading (LOINC code,
     * value at export precision, UCUM unit). Independent of storage ids,
     * patient/lab names and of how either platform spells units.
     */
    fun fingerprint(report: LabReport): String =
        report.date.toString() + "|" + report.entries
            .mapNotNull { entry ->
                entry.numericValue?.let { "${entry.code}=${NumberFormatting.cdaValue(it)}${Ucum.fromPrinted(entry.unit)}" }
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
