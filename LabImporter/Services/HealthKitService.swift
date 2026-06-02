import HealthKit
import Foundation

actor HealthKitService {

    static let shared = HealthKitService()

    nonisolated(unsafe) private let store = HKHealthStore()

    static var isHealthDataAvailable: Bool {
        HKHealthStore.isHealthDataAvailable()
    }

    private var cdaType: HKDocumentType? {
        HKObjectType.documentType(forIdentifier: .CDA)
    }

    // MARK: - Authorization

    /// Returns the share authorization status for the CDA document type.
    /// HealthKit deliberately hides read-permission status, so this is the only
    /// signal we have for whether the user has been asked / what they answered.
    nonisolated func cdaWriteAuthorizationStatus() -> HKAuthorizationStatus {
        guard let type = HKObjectType.documentType(forIdentifier: .CDA) else {
            return .notDetermined
        }
        return store.authorizationStatus(for: type)
    }

    /// Asks for every permission the app needs up-front (CDA share+read plus the
    /// patient characteristics used in Settings). Returns `true` only when the
    /// CDA share authorization comes back as granted — the only authorization
    /// state HealthKit will tell us about.
    func requestInitialAuthorization() async throws -> Bool {
        var typesToShare: Set<HKSampleType> = []
        var typesToRead: Set<HKObjectType> = []
        if let cda = HKObjectType.documentType(forIdentifier: .CDA) {
            typesToShare.insert(cda)
            typesToRead.insert(cda)
        }
        if let dob = HKObjectType.characteristicType(forIdentifier: .dateOfBirth) {
            typesToRead.insert(dob)
        }
        if let sex = HKObjectType.characteristicType(forIdentifier: .biologicalSex) {
            typesToRead.insert(sex)
        }
        try await store.requestAuthorization(toShare: typesToShare, read: typesToRead)
        return cdaWriteAuthorizationStatus() == .sharingAuthorized
    }

    // MARK: - Write

    func importCDADocument(_ xmlString: String, date: Date) async throws {
        guard let data = xmlString.data(using: .utf8),
              let documentType = cdaType else { return }
        try await store.requestAuthorization(toShare: [documentType], read: [documentType])
        let sample = try HKCDADocumentSample(data: data, start: date, end: date, metadata: nil)
        try await store.save(sample)
        await Self.notifyReportsChanged()
    }

    // MARK: - Read

    func loadCDADocuments() async throws -> [LabReport] {
        guard let documentType = cdaType else { return [] }
        try await store.requestAuthorization(toShare: [documentType], read: [documentType])

        let sources = try await appSources(for: documentType)
        let predicate = HKQuery.predicateForObjects(from: sources)

        return try await withCheckedThrowingContinuation { continuation in
            var results: [LabReport] = []
            var finished = false
            let query = HKDocumentQuery(
                documentType: documentType,
                predicate: predicate,
                limit: HKObjectQueryNoLimit,
                sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate,
                                                   ascending: false)],
                includeDocumentData: true
            ) { _, samples, done, error in
                guard !finished else { return }
                if let error {
                    finished = true
                    continuation.resume(throwing: error)
                    return
                }
                if let cdaSamples = samples as? [HKCDADocumentSample] {
                    let parsed = cdaSamples.compactMap { sample -> LabReport? in
                        guard let xmlData = sample.document?.documentData else { return nil }
                        return CDADocumentParser.parse(data: xmlData, id: sample.uuid)
                    }
                    results.append(contentsOf: parsed)
                }
                if done {
                    finished = true
                    continuation.resume(returning: results)
                }
            }
            store.execute(query)
        }
    }

    // MARK: - File import

    /// Reconstructs a `LabReport` from a CDA document supplied as a file the user
    /// explicitly chose to import (Files / share sheet), reading its values
    /// structurally — no OCR and no on-device AI. Unlike Health read-back
    /// (`loadCDADocuments`), an explicitly imported file is accepted even when it
    /// carries no recognized LabImporter schema version: foreign or legacy C-CDA
    /// lab reports have no migration history to honor, and the user deliberately
    /// picked this file, so there's no implicit-migration concern. Returns `nil`
    /// when the data isn't a parseable CDA lab document.
    nonisolated static func report(fromCDAFileData data: Data) -> LabReport? {
        CDADocumentParser.parse(data: data, id: UUID(), allowUnversioned: true)
    }

    // MARK: - Characteristics

    struct PatientCharacteristics {
        let dateOfBirth: Date?
        let biologicalSexRaw: Int?
    }

    func readPatientCharacteristics() async throws -> PatientCharacteristics {
        var typesToRead = Set<HKObjectType>()
        if let dobType = HKObjectType.characteristicType(forIdentifier: .dateOfBirth) {
            typesToRead.insert(dobType)
        }
        if let sexType = HKObjectType.characteristicType(forIdentifier: .biologicalSex) {
            typesToRead.insert(sexType)
        }
        guard !typesToRead.isEmpty else {
            return PatientCharacteristics(dateOfBirth: nil, biologicalSexRaw: nil)
        }
        try await store.requestAuthorization(toShare: [], read: typesToRead)
        let dobComps = try? store.dateOfBirthComponents()
        let dob = dobComps.flatMap { Calendar.current.date(from: $0) }
        let sexWrapper = try? store.biologicalSex()
        let sexRaw = sexWrapper.map { $0.biologicalSex.rawValue }.flatMap { $0 == 0 ? nil : $0 }
        return PatientCharacteristics(dateOfBirth: dob, biologicalSexRaw: sexRaw)
    }

    private func appSources(for sampleType: HKSampleType) async throws -> Set<HKSource> {
        let bundleID = Bundle.main.bundleIdentifier ?? ""
        return try await withCheckedThrowingContinuation { continuation in
            let query = HKSourceQuery(sampleType: sampleType, samplePredicate: nil) { _, sources, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                var result = (sources ?? []).filter { $0.bundleIdentifier == bundleID }
                result.insert(HKSource.default())
                continuation.resume(returning: result)
            }
            self.store.execute(query)
        }
    }

    // MARK: - Delete

    func deleteCDADocument(id: UUID) async throws {
        guard let documentType = cdaType else { return }
        let predicate = HKQuery.predicateForObject(with: id)

        let sample: HKDocumentSample? = try await withCheckedThrowingContinuation { continuation in
            var finished = false
            // HKDocumentQuery may deliver results across several handler
            // invocations, with `done` only set on the last one. Capture the
            // match as it arrives instead of relying on the final callback's
            // `samples` (which can be nil once everything was already delivered).
            var match: HKDocumentSample?
            let query = HKDocumentQuery(
                documentType: documentType,
                predicate: predicate,
                limit: 1,
                sortDescriptors: nil,
                includeDocumentData: false
            ) { _, samples, done, error in
                guard !finished else { return }
                if let error {
                    finished = true
                    continuation.resume(throwing: error)
                    return
                }
                if let first = samples?.first { match = first }
                if done {
                    finished = true
                    continuation.resume(returning: match)
                }
            }
            store.execute(query)
        }

        if let sample {
            try await store.delete(sample)
            await Self.notifyReportsChanged()
        }
    }

    /// Broadcasts that the CDA store was mutated so any view showing reports can
    /// reload from Health — the single source of truth — without waiting to
    /// become active again. Posted on the main actor so SwiftUI observers receive
    /// it on the expected thread.
    private static func notifyReportsChanged() async {
        await MainActor.run {
            NotificationCenter.default.post(name: .labReportsDidChange, object: nil)
        }
    }
}

extension Notification.Name {
    /// Posted after a lab report is saved, replaced, or deleted in Apple Health.
    static let labReportsDidChange = Notification.Name("dev.idoodler.labimporter.labReportsDidChange")
}

// MARK: - CDA XML Parser

private struct CDAObservation {
    let loinc: String
    let display: String
    let value: Double
    let unit: String
}

private final class CDADocumentParser: NSObject, XMLParserDelegate {

    private var reportDate: Date?
    // Patient name parts (CDA splits a person's name into given + family).
    private var patientGiven: [String] = []
    private var patientFamily = ""
    // The report's author/lab: an organization, an individual person (given +
    // family), or — as a last resort — the document's custodian organization.
    private var authorOrg = ""
    private var authorGiven: [String] = []
    private var authorFamily = ""
    private var custodianOrg = ""
    private var observations: [CDAObservation] = []

    private var elementStack: [String] = []
    private var currentText = ""
    private var documentDateSet = false

    private var inObservation = false
    private var obsLoinc: String?
    private var obsDisplay: String?
    private var obsValue: Double?
    private var obsUnit: String?

    /// CDA export-schema version parsed from the authoring device's softwareName
    /// ("LabImporter CDA v<N>"). nil for legacy documents written before
    /// versioning, which are ignored on read-back.
    private var schemaVersion: Int?

    /// - Parameter allowUnversioned: when `true`, a document that carries no
    ///   recognized LabImporter schema version is still accepted (parsed as-is)
    ///   rather than dropped. Used for explicit file imports — see
    ///   `HealthKitService.report(fromCDAFileData:)`. Health read-back leaves it
    ///   `false`, preserving the "ignore unversioned legacy exports" invariant.
    static func parse(data: Data, id: UUID, allowUnversioned: Bool = false) -> LabReport? {
        let delegate = CDADocumentParser()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()

        guard let date = delegate.reportDate else { return nil }

        let entries = delegate.observations.map { obs -> LabReport.Entry in
            // The stored code is the LOINC code itself; prefer our localized name,
            // falling back to the display name carried in the CDA document, then to
            // the raw code (an imported LOINC the catalog doesn't list still shows
            // the code rather than a blank name, and stays manually re-mappable).
            let mappedName = LabMapping.displayName(for: obs.loinc)
            let cdaName = obs.display.isEmpty ? obs.loinc : obs.display
            let name = mappedName == obs.loinc ? cdaName : mappedName
            let display = obs.value.truncatingRemainder(dividingBy: 1) == 0
                ? String(format: "%.0f", obs.value)
                : String(format: "%.4g", obs.value)
            return LabReport.Entry(
                id: UUID(),
                code: obs.loinc,
                name: name,
                displayValue: display,
                numericValue: obs.value,
                unit: obs.unit
            )
        }

        // Join the patient's given name(s) + family into a full name. App-written
        // exports store the whole name in <family> (no <given>), so this still
        // yields exactly that; foreign CDAs that split the name are reassembled.
        let patientName = (delegate.patientGiven + [delegate.patientFamily])
            .filter { !$0.isEmpty }
            .joined(separator: " ")

        // The "Lab / Doctor" field: prefer the author organization, then a named
        // author person, then the custodian organization.
        let authorPerson = (delegate.authorGiven + [delegate.authorFamily])
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        let authorName: String
        if !delegate.authorOrg.isEmpty {
            authorName = delegate.authorOrg
        } else if !authorPerson.isEmpty {
            authorName = authorPerson
        } else {
            authorName = delegate.custodianOrg
        }

        let report = LabReport(
            id: id,
            date: date,
            patientName: patientName,
            authorName: authorName,
            entries: entries
        )

        // An explicitly imported file with no version stamp (a foreign or legacy
        // C-CDA) has no migration history to honor and was deliberately chosen by
        // the user, so take it as-is. Health read-back keeps `allowUnversioned`
        // false: unversioned documents are skipped and versioned ones migrated.
        if delegate.schemaVersion == nil && allowUnversioned {
            return report
        }
        return CDAMigrator.upgrade(report, fromSchemaVersion: delegate.schemaVersion)
    }

    // MARK: XMLParserDelegate

    func parser(_ parser: XMLParser, didStartElement element: String,
                namespaceURI: String?, qualifiedName: String?,
                attributes attrs: [String: String] = [:]) {
        elementStack.append(element)
        currentText = ""

        switch element {
        case "effectiveTime" where !documentDateSet:
            if let val = attrs["value"] {
                reportDate = hl7Date(String(val.prefix(8)))
                documentDateSet = true
            }

        case "observation" where attrs["classCode"] == "OBS":
            inObservation = true
            obsLoinc = nil; obsDisplay = nil
            obsValue = nil; obsUnit = nil

        // The observation's LOINC identity. We accept it from the primary <code>
        // or, when that uses a local coding system, from a <translation> child —
        // a common shape in lab-system CDAs. The primary LOINC wins (the guard
        // keeps the first one seen, and the primary <code> is parsed first).
        case "code" where inObservation, "translation" where inObservation:
            if attrs["codeSystem"] == "2.16.840.1.113883.6.1", obsLoinc == nil {
                obsLoinc = attrs["code"]
                obsDisplay = attrs["displayName"]
            }

        case "value" where inObservation:
            let xsiType = attrs["xsi:type"] ?? ""
            if xsiType == "PQ" {
                if let raw = attrs["value"], let num = Double(raw) { obsValue = num }
                obsUnit = attrs["unit"] ?? ""
            }

        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        currentText += string
    }

    /// Routes a person-name part (`<given>`/`<family>`) by its ancestor: the
    /// patient lives under `<patientRole>`, a named author/doctor under
    /// `<assignedAuthor>`. "Unknown" is the export's null placeholder, so skip it.
    private func captureNamePart(_ element: String, ancestors: ArraySlice<String>, text: String) {
        guard !text.isEmpty, text != "Unknown" else { return }
        if ancestors.contains("patientRole") {
            if element == "given" { patientGiven.append(text) } else { patientFamily = text }
        } else if ancestors.contains("assignedAuthor") {
            if element == "given" { authorGiven.append(text) } else { authorFamily = text }
        }
    }

    /// Captures an organization name: the author's is the lab/doctor, the
    /// custodian's a fallback. The "LabImporter" sentinel (this app's own
    /// custodian/author stamp) is ignored so it never surfaces as a real lab name.
    private func captureOrganizationName(ancestors: ArraySlice<String>, text: String) {
        guard !text.isEmpty, text != "LabImporter" else { return }
        if ancestors.contains("author"), ancestors.contains("representedOrganization") {
            authorOrg = text
        } else if ancestors.contains("representedCustodianOrganization") {
            custodianOrg = text
        }
    }

    func parser(_ parser: XMLParser, didEndElement element: String,
                namespaceURI: String?, qualifiedName: String?) {
        defer {
            if !elementStack.isEmpty { elementStack.removeLast() }
            currentText = ""
        }

        let ancestors = elementStack.dropLast()
        let trimmedText = currentText.trimmingCharacters(in: .whitespacesAndNewlines)

        switch element {
        case "given", "family":
            captureNamePart(element, ancestors: ancestors, text: trimmedText)

        case "name":
            captureOrganizationName(ancestors: ancestors, text: trimmedText)

        case "softwareName":
            schemaVersion = Self.parseSchemaVersion(currentText)

        case "observation" where inObservation:
            // A LOINC code is required — observations coded in any other system
            // are skipped, since without LOINC the value can't be mapped or
            // exported. The display name is optional: it's resolved from the
            // bundled catalog (the CDA's own displayName is only a fallback).
            if let loinc = obsLoinc, let value = obsValue, let unit = obsUnit {
                observations.append(CDAObservation(loinc: loinc, display: obsDisplay ?? "", value: value, unit: unit))
            }
            inObservation = false

        default:
            break
        }
    }

    // Extracts <N> from a "LabImporter CDA v<N>" softwareName; nil otherwise.
    private static func parseSchemaVersion(_ text: String) -> Int? {
        guard let range = text.range(of: "CDA v") else { return nil }
        let digits = text[range.upperBound...].prefix { $0.isNumber }
        return Int(digits)
    }

    private func hl7Date(_ value: String) -> Date? {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyyMMdd"
        fmt.locale = Locale(identifier: "en_US_POSIX")
        return fmt.date(from: value)
    }
}

// MARK: - CDA schema migration

/// Upgrades a `LabReport` parsed from one exported-document schema version to the
/// next. Add one conformer per version step (e.g. when a LOINC code is remapped);
/// `CDAMigrator` chains them automatically.
protocol CDAMigration {
    /// This migration upgrades a document written by `fromVersion` to `fromVersion + 1`.
    static var fromVersion: Int { get }
    static func migrate(_ report: LabReport) -> LabReport
}

/// Decides whether a parsed document is supported and brings it up to the
/// current `CDAExportService.schemaVersion`.
enum CDAMigrator {
    /// Oldest export-schema version still understood. Documents below this —
    /// including legacy exports that carry no version stamp — are ignored.
    static let minimumSupportedVersion = 1

    /// Ordered version-step migrations. Empty today (the current schema is the
    /// baseline); register `SomeMigration.self` here when conventions change.
    nonisolated(unsafe) private static let migrations: [any CDAMigration.Type] = []

    /// Returns `report` upgraded to the current schema, or `nil` when the source
    /// document is unversioned, older than `minimumSupportedVersion`, or no
    /// migration path exists for a step.
    static func upgrade(_ report: LabReport, fromSchemaVersion version: Int?) -> LabReport? {
        guard let version, version >= minimumSupportedVersion else { return nil }
        var current = report
        var step = version
        while step < CDAExportService.schemaVersion {
            guard let migration = migrations.first(where: { $0.fromVersion == step }) else { return nil }
            current = migration.migrate(current)
            step += 1
        }
        return current
    }
}
