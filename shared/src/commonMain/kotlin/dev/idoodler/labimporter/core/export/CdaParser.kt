package dev.idoodler.labimporter.core.export

import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.datetime.LocalDate
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming
import kotlin.uuid.Uuid

/**
 * Reads a LabImporter C-CDA document (see [CdaExport]) back into a [LabReport].
 *
 * Only documents stamped with a supported export-schema version
 * (`LabImporter CDA v<N>` in the authoring device's `softwareName`) are
 * accepted; unversioned or unknown documents return null and are skipped, and
 * older versions are upgraded through [ReportSchema]. The rules mirror what
 * the iOS app has always applied when reading Apple Health back:
 *  - the report date is the first `effectiveTime` in the document;
 *  - an observation needs a LOINC code, a display name, a `PQ` value and a unit;
 *  - the `Unknown` patient / `LabImporter` organisation placeholders are ignored.
 */
object CdaParser {
    private const val LOINC_OID = "2.16.840.1.113883.6.1"
    private const val XSI_NS = "http://www.w3.org/2001/XMLSchema-instance"

    /** @param id the report's identity (on iOS: the Health sample's UUID). */
    fun parse(xml: String, id: String = Uuid.random().toString()): LabReport? {
        val state = ParserState()
        val reader = runCatching { xmlStreaming.newGenericReader(xml, true) }.getOrNull() ?: return null
        val ok = try {
            runCatching { read(reader, state) }.isSuccess
        } finally {
            runCatching { reader.close() }
        }
        if (!ok) return null
        val date = state.reportDate ?: return null

        val entries = state.observations.map { obs ->
            // The stored code is the LOINC code; prefer our localized name and
            // fall back to the display name carried in the document.
            val mapped = LabMapping.displayName(obs.loinc)
            LabReport.Entry(
                id = Uuid.random().toString(),
                code = obs.loinc,
                name = if (mapped == obs.loinc) obs.display else mapped,
                displayValue = NumberFormatting.cdaValue(obs.value),
                numericValue = obs.value,
                unit = obs.unit,
            )
        }
        val report = LabReport(id, date, state.patientFamily, state.authorOrg, entries)
        return ReportSchema.upgrade(report, state.schemaVersion)
    }

    /** True when [content] looks like a CDA ClinicalDocument (cheap sniff before parsing). */
    fun looksLikeCda(content: String): Boolean =
        content.trimStart().startsWith("<") && content.contains("ClinicalDocument")

    private class Observation(val loinc: String, val display: String, val value: Double, val unit: String)

    private class ParserState {
        var reportDate: LocalDate? = null
        var documentDateSet = false
        var patientFamily = ""
        var authorOrg = ""
        var schemaVersion: Int? = null
        val observations = mutableListOf<Observation>()
        val elementStack = mutableListOf<String>()
        val text = StringBuilder()
        var inObservation = false
        var obsLoinc: String? = null
        var obsDisplay: String? = null
        var obsValue: Double? = null
        var obsUnit: String? = null
    }

    private fun read(reader: XmlReader, s: ParserState) {
        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> start(reader, s)
                EventType.END_ELEMENT -> end(reader.localName, s)
                EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF, EventType.IGNORABLE_WHITESPACE ->
                    s.text.append(reader.text)
                else -> Unit
            }
        }
    }

    private fun XmlReader.attribute(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == name && getAttributeNamespace(it).isEmpty() }
            ?.let { getAttributeValue(it) }

    private fun XmlReader.xsiType(): String? =
        (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == "type" && getAttributeNamespace(it) == XSI_NS }
            ?.let { getAttributeValue(it) }

    private fun start(reader: XmlReader, s: ParserState) {
        val element = reader.localName
        s.elementStack += element
        s.text.clear()
        when {
            element == "effectiveTime" && !s.documentDateSet -> {
                reader.attribute("value")?.let { value ->
                    s.reportDate = hl7Date(value.take(8))
                    s.documentDateSet = true
                }
            }
            element == "observation" && reader.attribute("classCode") == "OBS" -> {
                s.inObservation = true
                s.obsLoinc = null; s.obsDisplay = null; s.obsValue = null; s.obsUnit = null
            }
            element == "code" && s.inObservation -> {
                if (reader.attribute("codeSystem") == LOINC_OID) {
                    s.obsLoinc = reader.attribute("code")
                    s.obsDisplay = reader.attribute("displayName")
                }
            }
            element == "value" && s.inObservation -> {
                if (reader.xsiType() == "PQ") {
                    reader.attribute("value")?.let { raw -> NumberFormatting.parse(raw)?.let { s.obsValue = it } }
                    s.obsUnit = reader.attribute("unit") ?: ""
                }
            }
        }
    }

    private fun end(element: String, s: ParserState) {
        val ancestors = s.elementStack.dropLast(1)
        val text = s.text.toString().trim()
        when {
            element == "family" && "patientRole" in ancestors -> {
                if (text.isNotEmpty() && text != "Unknown") s.patientFamily = text
            }
            element == "name" && "representedOrganization" in ancestors -> {
                if (text.isNotEmpty() && text != "LabImporter") s.authorOrg = text
            }
            element == "softwareName" -> s.schemaVersion = schemaVersion(s.text.toString())
            element == "observation" && s.inObservation -> {
                val loinc = s.obsLoinc
                val display = s.obsDisplay
                val value = s.obsValue
                val unit = s.obsUnit
                if (loinc != null && display != null && value != null && unit != null) {
                    s.observations += Observation(loinc, display, value, unit)
                }
                s.inObservation = false
            }
        }
        if (s.elementStack.isNotEmpty()) s.elementStack.removeAt(s.elementStack.lastIndex)
        s.text.clear()
    }

    /** `<N>` from a `LabImporter CDA v<N>` software name; null otherwise. */
    internal fun schemaVersion(text: String): Int? {
        val index = text.indexOf("CDA v")
        if (index < 0) return null
        return text.substring(index + 5).takeWhile { it.isDigit() }.toIntOrNull()
    }

    private fun hl7Date(value: String): LocalDate? {
        if (value.length != 8 || !value.all { it.isDigit() }) return null
        return runCatching {
            LocalDate(value.substring(0, 4).toInt(), value.substring(4, 6).toInt(), value.substring(6, 8).toInt())
        }.getOrNull()
    }
}
