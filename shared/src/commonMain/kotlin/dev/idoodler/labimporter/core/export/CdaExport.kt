package dev.idoodler.labimporter.core.export

import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.deduplicatedByLoinc
import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

/**
 * Builds a C-CDA R2.1 Lab Report — the document format the iOS app stores in
 * Apple Health, and the share/export format on both platforms. Only values
 * that are selected, numeric and LOINC-mapped are included.
 */
object CdaExport {

    /** True when at least one value would be written. */
    fun hasExportableValues(values: List<LabValue>): Boolean = values.any { it.isExportable }

    /**
     * @param softwareIdentity provenance for `manufacturerModelName`, e.g.
     *   `LabImporter 1.0 (33)`.
     */
    fun generate(
        values: List<LabValue>,
        date: LocalDate,
        softwareIdentity: String,
        patientName: String = "",
        authorName: String = "",
    ): String {
        val dateStr = hl7Date(date)
        val docId = uuid()
        val orgId = uuid()
        val patientFamily = esc(patientName.trim().ifEmpty { "Unknown" })
        val authorTrimmed = authorName.trim()
        val authorOrgXml = if (authorTrimmed.isEmpty()) "" else
            "\n      <representedOrganization>\n        <name>${esc(authorTrimmed)}</name>\n      </representedOrganization>"

        val exportable = values.deduplicatedByLoinc().filter { it.isExportable }

        val narrativeRows = exportable.joinToString("\n              ") { value ->
            // Canonical catalog name, never a personal nickname.
            val canonical = LabCore.loinc?.term(value.code)?.name ?: value.name
            "<tr><td>${esc(canonical)}</td><td>${esc(value.displayValue)} ${esc(value.unit)}</td></tr>"
        }
        val components = exportable.mapNotNull { observationXml(it, dateStr) }.joinToString("\n              ")

        return """
<?xml version="1.0" encoding="UTF-8"?>
<ClinicalDocument xmlns="urn:hl7-org:v3"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <typeId root="2.16.840.1.113883.1.3" extension="POCD_HD000040"/>
  <templateId root="2.16.840.1.113883.10.20.22.1.1"/>
  <id root="$docId"/>
  <code code="11502-2"
        displayName="Laboratory report"
        codeSystem="2.16.840.1.113883.6.1"
        codeSystemName="LOINC"/>
  <title>Lab Results</title>
  <effectiveTime value="$dateStr"/>
  <confidentialityCode code="N" codeSystem="2.16.840.1.113883.5.25"/>
  <languageCode code="de-DE"/>
  <recordTarget>
    <patientRole>
      <id nullFlavor="UNK"/>
      <patient>
        <name>
          <family>$patientFamily</family>
        </name>
        <administrativeGenderCode nullFlavor="UNK"/>
        <birthTime nullFlavor="UNK"/>
      </patient>
    </patientRole>
  </recordTarget>
  <author>
    <time value="$dateStr"/>
    <assignedAuthor>
      <id nullFlavor="UNK"/>
      <assignedAuthoringDevice>
        <manufacturerModelName>${esc(softwareIdentity)}</manufacturerModelName>
        <softwareName>LabImporter CDA v${ReportSchema.VERSION}</softwareName>
      </assignedAuthoringDevice>$authorOrgXml
    </assignedAuthor>
  </author>
  <custodian>
    <assignedCustodian>
      <representedCustodianOrganization>
        <id nullFlavor="UNK"/>
        <name>LabImporter</name>
      </representedCustodianOrganization>
    </assignedCustodian>
  </custodian>
  <component>
    <structuredBody>
      <component>
        <section>
          <templateId root="2.16.840.1.113883.10.20.22.2.3.1"/>
          <code code="30954-2"
                displayName="Relevant diagnostic tests/laboratory data Narrative"
                codeSystem="2.16.840.1.113883.6.1"
                codeSystemName="LOINC"/>
          <title>Laborwerte</title>
          <text>
            <table border="1" width="100%">
              <thead><tr><th>Test</th><th>Ergebnis</th></tr></thead>
              <tbody>
              $narrativeRows
              </tbody>
            </table>
          </text>
          <entry typeCode="DRIV">
            <organizer classCode="BATTERY" moodCode="EVN">
              <templateId root="2.16.840.1.113883.10.20.22.4.1"/>
              <id root="$orgId"/>
              <code code="26436-6"
                    displayName="Laboratory studies (set)"
                    codeSystem="2.16.840.1.113883.6.1"
                    codeSystemName="LOINC"/>
              <statusCode code="completed"/>
              <effectiveTime value="$dateStr"/>
              $components
            </organizer>
          </entry>
        </section>
      </component>
    </structuredBody>
  </component>
</ClinicalDocument>""".trimStart('\n')
    }

    private fun observationXml(value: LabValue, date: String): String? {
        val num = value.numericValue ?: return null
        val (loinc, display) = LabMapping.loincCode(value.code) ?: return null
        val unit = Ucum.fromPrinted(value.unit)
        val valueStr = NumberFormatting.cdaValue(num)
        return """
<component>
                <observation classCode="OBS" moodCode="EVN">
                  <templateId root="2.16.840.1.113883.10.20.22.4.2"/>
                  <id root="${uuid()}"/>
                  <code code="${esc(loinc)}"
                        displayName="${esc(display)}"
                        codeSystem="2.16.840.1.113883.6.1"
                        codeSystemName="LOINC"/>
                  <statusCode code="completed"/>
                  <effectiveTime value="$date"/>
                  <value xsi:type="PQ" value="$valueStr" unit="${esc(unit)}"/>
                </observation>
              </component>
""".trim('\n')
    }

    fun hl7Date(date: LocalDate): String =
        "${date.year.toString().padStart(4, '0')}${(date.month.ordinal + 1).toString().padStart(2, '0')}" +
            date.day.toString().padStart(2, '0')

    private fun uuid(): String = Uuid.random().toString().lowercase()

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
