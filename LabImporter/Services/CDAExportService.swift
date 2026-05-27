import Foundation

struct CDAExportService {

    // Returns a C-CDA R2.1 Lab Report XML string.
    // Values without a LOINC mapping, numeric result, or that are deselected are omitted.
    // swiftlint:disable:next function_body_length
    func generateCDA(labValues: [LabValue], date: Date, patientName: String = "") -> String {
        let dateStr = hl7Date(date)
        let docId   = uuid()
        let orgId   = uuid()
        let patientFamily = esc(patientName.trimmingCharacters(in: .whitespaces).isEmpty ? "Unknown" : patientName)

        let exportable = labValues.filter {
            $0.isSelected && $0.numericValue != nil && LabMapping.loincCode(for: $0.code) != nil
        }

        let narrativeRows = exportable.map { labValue in
            "<tr><td>\(esc(labValue.name))</td><td>\(esc(labValue.displayValue)) \(esc(labValue.unit))</td></tr>"
        }.joined(separator: "\n              ")

        let components = exportable.compactMap { observationXML($0, date: dateStr) }
            .joined(separator: "\n              ")

        return """
<?xml version="1.0" encoding="UTF-8"?>
<ClinicalDocument xmlns="urn:hl7-org:v3"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="urn:hl7-org:v3 CDA.xsd">
  <typeId root="2.16.840.1.113883.1.3" extension="POCD_HD000040"/>
  <templateId root="2.16.840.1.113883.10.20.22.1.1"/>
  <id root="\(docId)"/>
  <code code="11502-2"
        displayName="Laboratory report"
        codeSystem="2.16.840.1.113883.6.1"
        codeSystemName="LOINC"/>
  <title>Lab Results</title>
  <effectiveTime value="\(dateStr)"/>
  <confidentialityCode code="N" codeSystem="2.16.840.1.113883.5.25"/>
  <languageCode code="de-DE"/>
  <recordTarget>
    <patientRole>
      <id nullFlavor="UNK"/>
      <patient>
        <name>
          <family>\(patientFamily)</family>
        </name>
        <administrativeGenderCode nullFlavor="UNK"/>
        <birthTime nullFlavor="UNK"/>
      </patient>
    </patientRole>
  </recordTarget>
  <author>
    <time value="\(dateStr)"/>
    <assignedAuthor>
      <id nullFlavor="UNK"/>
      <assignedAuthoringDevice>
        <softwareName>LabImporter</softwareName>
      </assignedAuthoringDevice>
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
              \(narrativeRows)
              </tbody>
            </table>
          </text>
          <entry typeCode="DRIV">
            <organizer classCode="BATTERY" moodCode="EVN">
              <templateId root="2.16.840.1.113883.10.20.22.4.1"/>
              <id root="\(orgId)"/>
              <code code="26436-6"
                    displayName="Laboratory studies (set)"
                    codeSystem="2.16.840.1.113883.6.1"
                    codeSystemName="LOINC"/>
              <statusCode code="completed"/>
              <effectiveTime value="\(dateStr)"/>
              \(components)
            </organizer>
          </entry>
        </section>
      </component>
    </structuredBody>
  </component>
</ClinicalDocument>
"""
    }

    // Writes the CDA to a temp file and returns the URL for sharing.
    func exportToTempFile(labValues: [LabValue], date: Date, patientName: String = "") throws -> URL {
        let xml = generateCDA(labValues: labValues, date: date, patientName: patientName)
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        let name = "LabResults-\(formatter.string(from: date)).xml"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try xml.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    // MARK: - Private helpers

    private func observationXML(_ value: LabValue, date: String) -> String? {
        guard let num = value.numericValue,
              let (loincCode, loincDisplay) = LabMapping.loincCode(for: value.code) else { return nil }

        let unit = ucum(value.unit)
        // Format as integer when possible, otherwise 4 significant figures
        let valueStr = num.truncatingRemainder(dividingBy: 1) == 0
            ? String(format: "%.0f", num)
            : String(format: "%.4g", num)

        return """
<component>
                <observation classCode="OBS" moodCode="EVN">
                  <templateId root="2.16.840.1.113883.10.20.22.4.2"/>
                  <id root="\(uuid())"/>
                  <code code="\(esc(loincCode))"
                        displayName="\(esc(loincDisplay))"
                        codeSystem="2.16.840.1.113883.6.1"
                        codeSystemName="LOINC"/>
                  <statusCode code="completed"/>
                  <effectiveTime value="\(date)"/>
                  <value xsi:type="PQ" value="\(valueStr)" unit="\(esc(unit))"/>
                </observation>
              </component>
"""
    }

    // Maps common German lab unit strings to UCUM codes.
    private func ucum(_ raw: String) -> String {
        switch raw.lowercased().trimmingCharacters(in: .whitespaces) {
        case "mg/dl":                           return "mg/dL"
        case "mmol/l":                          return "mmol/L"
        case "u/l":                             return "U/L"
        case "mu/l", "miu/l":                   return "m[IU]/L"
        case "mmol/mol":                        return "mmol/mol"
        case "%":                               return "%"
        case "ml/min/1,73m2kof",
             "ml/min/1.73m2kof",
             "ml/min/1.73 m2":                 return "mL/min/{1.73_m2}"
        default:                               return raw
        }
    }

    private func hl7Date(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd"
        return formatter.string(from: date)
    }

    private func uuid() -> String {
        UUID().uuidString.lowercased()
    }

    private func esc(_ string: String) -> String {
        string.replacingOccurrences(of: "&", with: "&amp;")
              .replacingOccurrences(of: "<", with: "&lt;")
              .replacingOccurrences(of: ">", with: "&gt;")
              .replacingOccurrences(of: "\"", with: "&quot;")
    }
}
