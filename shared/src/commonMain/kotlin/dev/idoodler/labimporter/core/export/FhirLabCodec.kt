package dev.idoodler.labimporter.core.export

import dev.idoodler.labimporter.core.model.LabMapping
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One FHIR resource ready to store: its type/id and the JSON body. */
data class FhirResource(val resourceType: String, val id: String, val json: String)

/**
 * Encodes reports as FHIR R4 laboratory `Observation`s — the format Android's
 * Health Connect *Medical Records* API stores and the cross-platform transfer
 * file carries — and decodes them back.
 *
 * Health Connect has no report container (`DiagnosticReport` isn't supported),
 * so every Observation carries the report's id as a `meta.tag`; decoding
 * regroups by that tag. A second tag stamps [ReportSchema.VERSION], like the
 * CDA `softwareName`: resources without a supported version are ignored on
 * read-back, and [ReportSchema] chains upgrades.
 */
object FhirLabCodec {
    const val FHIR_VERSION = "4.0.1"
    const val REPORT_TAG_SYSTEM = "https://github.com/idoodler/LabImporter/fhir/report"
    const val SCHEMA_TAG_SYSTEM = "https://github.com/idoodler/LabImporter/fhir/schema"
    private const val LOINC_SYSTEM = "http://loinc.org"
    private const val UCUM_SYSTEM = "http://unitsofmeasure.org"
    private const val CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(report: LabReport, softwareIdentity: String): List<FhirResource> =
        report.entries.mapNotNull { entry ->
            val value = entry.numericValue ?: return@mapNotNull null
            val loinc = LabMapping.loincCode(entry.code) ?: return@mapNotNull null
            val body = buildJsonObject {
                put("resourceType", "Observation")
                put("id", entry.id)
                put("meta", buildJsonObject {
                    put("source", softwareIdentity)
                    put("tag", buildJsonArray {
                        add(coding(REPORT_TAG_SYSTEM, report.id, null))
                        add(coding(SCHEMA_TAG_SYSTEM, ReportSchema.VERSION.toString(), null))
                    })
                })
                put("status", "final")
                put("category", buildJsonArray {
                    add(buildJsonObject {
                        put("coding", buildJsonArray { add(coding(CATEGORY_SYSTEM, "laboratory", "Laboratory")) })
                    })
                })
                put("code", buildJsonObject {
                    put("coding", buildJsonArray { add(coding(LOINC_SYSTEM, loinc.loinc, loinc.display)) })
                    put("text", entry.name.ifBlank { loinc.display })
                })
                if (report.patientName.isNotBlank()) {
                    put("subject", buildJsonObject { put("display", report.patientName) })
                }
                put("effectiveDateTime", report.date.toString())
                if (report.authorName.isNotBlank()) {
                    put("performer", buildJsonArray { add(buildJsonObject { put("display", report.authorName) }) })
                }
                put("valueQuantity", buildJsonObject {
                    put("value", value)
                    if (entry.unit.isNotBlank()) {
                        put("unit", entry.unit)
                        put("system", UCUM_SYSTEM)
                        put("code", Ucum.fromPrinted(entry.unit))
                    }
                })
            }
            FhirResource("Observation", entry.id, body.toString())
        }

    /** Regroups stored Observations into reports, newest first. Foreign/unversioned resources are skipped. */
    fun decode(resources: List<String>): List<LabReport> {
        data class Row(val reportId: String, val version: Int, val date: LocalDate, val patient: String,
                       val author: String, val entry: LabReport.Entry)

        val rows = resources.mapNotNull { raw ->
            runCatching {
                val obj = json.parseToJsonElement(raw).jsonObject
                if (obj.string("resourceType") != "Observation") return@runCatching null
                val tags = obj["meta"]?.jsonObject?.get("tag")?.jsonArray.orEmpty().map { it.jsonObject }
                val reportId = tags.firstOrNull { it.string("system") == REPORT_TAG_SYSTEM }?.string("code")
                    ?: return@runCatching null
                val version = tags.firstOrNull { it.string("system") == SCHEMA_TAG_SYSTEM }?.string("code")
                    ?.toIntOrNull() ?: return@runCatching null
                val coding = obj["code"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().map { it.jsonObject }
                    .firstOrNull { it.string("system") == LOINC_SYSTEM } ?: return@runCatching null
                val quantity = obj["valueQuantity"]?.jsonObject
                val value = quantity?.get("value")?.jsonPrimitive?.doubleOrNull
                val date = obj.string("effectiveDateTime")?.take(10)?.let(LocalDate::parse) ?: return@runCatching null
                Row(
                    reportId = reportId,
                    version = version,
                    date = date,
                    patient = obj["subject"]?.jsonObject?.string("display").orEmpty(),
                    author = (obj["performer"] as? JsonArray)?.firstOrNull()?.jsonObject?.string("display").orEmpty(),
                    entry = LabReport.Entry(
                        id = obj.string("id") ?: reportId,
                        code = coding.string("code").orEmpty(),
                        name = obj["code"]?.jsonObject?.string("text") ?: coding.string("display").orEmpty(),
                        displayValue = value?.let(NumberFormatting::cdaValue) ?: "-",
                        numericValue = value,
                        unit = quantity?.string("unit").orEmpty(),
                    ),
                )
            }.getOrNull()
        }

        return rows.groupBy { it.reportId }.mapNotNull { (reportId, group) ->
            val first = group.first()
            val report = LabReport(
                id = reportId, date = first.date, patientName = first.patient, authorName = first.author,
                entries = group.map { it.entry },
            )
            ReportSchema.upgrade(report, group.minOf { it.version })
        }.sortedByDescending { it.date }
    }

    private fun coding(system: String, code: String, display: String?) = buildJsonObject {
        put("system", system)
        put("code", code)
        if (display != null) put("display", display)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
