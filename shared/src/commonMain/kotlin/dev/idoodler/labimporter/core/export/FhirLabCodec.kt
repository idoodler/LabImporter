package dev.idoodler.labimporter.core.export

import dev.idoodler.labimporter.core.model.LabMapping
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One FHIR resource ready to store: its type/id and the JSON body. */
data class FhirResource(val resourceType: String, val id: String, val json: String)

/** A report with results that can't be stored (see [FhirLabCodec.encodeForSaving]). Counts only: it gets logged. */
class ReportEncodingException(val unencoded: Int, val results: Int) :
    Exception("$unencoded of $results results couldn't be encoded")

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
 *
 * A number is a `valueQuantity`; a qualitative result ("Negativ", "1:160",
 * "<0.5") is a `valueString` with its unit in the text (see
 * [QualitativeResult]) — not a comparator quantity, which older builds would
 * read as the bare number.
 */
object FhirLabCodec {
    const val FHIR_VERSION = "4.0.1"
    const val REPORT_TAG_SYSTEM = "https://github.com/idoodler/LabImporter/fhir/report"
    const val SCHEMA_TAG_SYSTEM = "https://github.com/idoodler/LabImporter/fhir/schema"
    private const val LOINC_SYSTEM = "http://loinc.org"
    private const val UCUM_SYSTEM = "http://unitsofmeasure.org"
    private const val CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [encode] for saving: every result must make it into the resources. An entry
     * [encode] can't map (the catalog isn't open yet, a code it doesn't know) would
     * otherwise vanish, and with nothing left a store would "save" the report by
     * writing nothing. Entries without a result have nothing to lose.
     *
     * @throws ReportEncodingException
     */
    fun encodeForSaving(report: LabReport, softwareIdentity: String): List<FhirResource> {
        val resources = encode(report, softwareIdentity)
        val results = report.entries.count { it.hasResult }
        if (resources.size < results || resources.isEmpty()) throw ReportEncodingException(results - resources.size, results)
        return resources
    }

    fun encode(report: LabReport, softwareIdentity: String): List<FhirResource> =
        report.entries.mapNotNull { entry ->
            val value = entry.numericValue
            val qualitative = if (value == null) QualitativeResult.of(entry.displayValue) ?: return@mapNotNull null else null
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
                if (value != null) {
                    put("valueQuantity", buildJsonObject {
                        put("value", value)
                        if (entry.unit.isNotBlank()) {
                            put("unit", entry.unit)
                            put("system", UCUM_SYSTEM)
                            put("code", Ucum.fromPrinted(entry.unit))
                        }
                    })
                } else if (qualitative != null) {
                    put("valueString", QualitativeResult.stored(qualitative, entry.unit))
                }
            }
            FhirResource("Observation", entry.id, body.toString())
        }

    /** The reports in [resources]; see [decodeStored], for anything showing or moving them. */
    fun decode(resources: List<String>): List<LabReport> = decodeStored(resources).reports

    /**
     * Regroups stored Observations into reports, newest first, and counts what it can't list.
     * Resources of other apps and unversioned ones are skipped without a word.
     */
    fun decodeStored(resources: List<String>): StoredReports {
        data class Row(val reportId: String, val version: Int, val date: LocalDate, val patient: String,
                       val author: String, val entry: LabReport.Entry)

        val rows = mutableListOf<Row>()
        val newer = hashSetOf<String>()
        val damaged = hashSetOf<String>()
        var damagedWithoutId = 0
        for (raw in resources) {
            var reportId: String? = null
            try {
                val obj = json.parseToJsonElement(raw).jsonObject
                if (obj.string("resourceType") != "Observation") continue
                val tags = obj["meta"]?.jsonObject?.get("tag")?.jsonArray.orEmpty().map { it.jsonObject }
                val id = tags.firstOrNull { it.string("system") == REPORT_TAG_SYSTEM }?.string("code") ?: continue
                reportId = id
                val version = tags.firstOrNull { it.string("system") == SCHEMA_TAG_SYSTEM }?.string("code")
                    ?.toIntOrNull() ?: continue
                // Checked before anything else is read: a later version may have changed the rest.
                if (ReportSchema.isNewer(version)) {
                    newer += id
                    continue
                }
                val coding = obj["code"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().map { it.jsonObject }
                    .firstOrNull { it.string("system") == LOINC_SYSTEM } ?: error("No LOINC code")
                val quantity = obj["valueQuantity"]?.jsonObject
                val value = quantity?.get("value")?.jsonPrimitive?.doubleOrNull
                val text = if (value == null) obj.string("valueString")?.takeIf { it.isNotBlank() }?.let(QualitativeResult::split) else null
                val date = obj.string("effectiveDateTime")?.take(10)?.let(LocalDate::parse) ?: error("No date")
                rows += Row(
                    reportId = id,
                    version = version,
                    date = date,
                    patient = obj["subject"]?.jsonObject?.string("display").orEmpty(),
                    author = (obj["performer"] as? JsonArray)?.firstOrNull()?.jsonObject?.string("display").orEmpty(),
                    entry = LabReport.Entry(
                        id = obj.string("id") ?: id,
                        code = coding.string("code").orEmpty(),
                        name = obj["code"]?.jsonObject?.string("text") ?: coding.string("display").orEmpty(),
                        displayValue = value?.let(NumberFormatting::exactValue) ?: text?.first ?: "-",
                        numericValue = value,
                        unit = text?.second ?: quantity?.string("unit").orEmpty(),
                    ),
                )
            } catch (_: Exception) {
                // Only counted: the exception's message would quote a health record.
                if (reportId != null) damaged += reportId else damagedWithoutId++
            }
        }

        // A report with any newer resource isn't listed at all: half of it can't be read, and edits would lose the rest.
        val reports = rows.filter { it.reportId !in newer }.groupBy { it.reportId }.mapNotNull { (reportId, group) ->
            val first = group.first()
            val report = LabReport(
                id = reportId, date = first.date, patientName = first.patient, authorName = first.author,
                entries = group.map { it.entry },
            )
            ReportSchema.upgrade(report, group.minOf { it.version })
        }.sortedByDescending { it.date }
        return StoredReports(reports, newer.size, (damaged - newer).size + damagedWithoutId)
    }

    private fun coding(system: String, code: String, display: String?) = buildJsonObject {
        put("system", system)
        put("code", code)
        if (display != null) put("display", display)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
