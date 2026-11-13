import Foundation
import LabImporterCore

/// Writes reports as C-CDA R2.1 Lab Report documents — the format stored in
/// Apple Health and shared with doctors. The document itself (structure, UCUM
/// units, number notation, schema version `LabImporter CDA v<N>`) is generated
/// by the shared module (`LabImporterCore.CdaExport`), so iOS and Android write
/// byte-identical documents; see "Migrating the exported CDA" in CLAUDE.md
/// before changing what an export means.
struct CDAExportService {
    /// The human-readable part of a document: the language it declares and its headings.
    /// Apple Health shows them. The read-back ignores them, so any language reads back.
    struct Narrative {
        let languageCode: String
        let documentTitle: String
        let sectionTitle: String
        let testHeader: String
        let resultHeader: String

        /// In the language the app is showing, which is the language of the catalog names in the table.
        static var current: Narrative {
            Narrative(
                languageCode: Bundle.main.preferredLocalizations.first ?? "en",
                documentTitle: String(localized: "Lab Results"),
                sectionTitle: String(localized: "Lab Values"),
                testHeader: String(localized: "Test"),
                resultHeader: String(localized: "Result")
            )
        }

        fileprivate var shared: CdaNarrative {
            CdaNarrative(
                languageCode: languageCode, documentTitle: documentTitle, sectionTitle: sectionTitle,
                testHeader: testHeader, resultHeader: resultHeader
            )
        }
    }

    /// Human-readable provenance ("LabImporter 1.0 (33)") for the authoring
    /// device's `manufacturerModelName`.
    static var softwareIdentity: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "LabImporter \(version) (\(build))"
    }

    // Returns a C-CDA R2.1 Lab Report XML string. Values without a LOINC
    // mapping or numeric result, and deselected ones, are omitted.
    func generateCDA(
        labValues: [LabValue], date: Date, patientName: String = "", authorName: String = "", narrative: Narrative = .current
    ) -> String {
        SharedCore.start()
        return CdaExport.shared.generate(
            values: labValues.map(\.shared),
            date: date.sharedDate,
            softwareIdentity: Self.softwareIdentity,
            narrative: narrative.shared,
            patientName: patientName,
            authorName: authorName
        )
    }

    // Writes the CDA for a share sheet and returns the URL. The sheet's dismissal removes it (`ExportFiles`).
    func exportToTempFile(labValues: [LabValue], date: Date, patientName: String = "", authorName: String = "") throws -> URL {
        SharedCore.start()
        guard CdaExport.shared.hasExportableValues(values: labValues.map(\.shared)) else {
            throw CDAExportError.noExportableValues
        }
        let xml = generateCDA(labValues: labValues, date: date, patientName: patientName, authorName: authorName)
        let name = "LabResults-\(date.sharedDate.description()).xml"
        let url = try ExportFiles.url(named: name)
        try xml.write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}

enum CDAExportError: LocalizedError {
    case noExportableValues

    var errorDescription: String? {
        String(localized: "No values can be exported. Make sure at least one value is enabled and has a numeric result.")
    }
}
