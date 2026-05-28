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

    // MARK: - Write

    func importCDADocument(_ xmlString: String, date: Date) async throws {
        guard let data = xmlString.data(using: .utf8),
              let documentType = cdaType else { return }
        try await store.requestAuthorization(toShare: [documentType], read: [documentType])
        let sample = try HKCDADocumentSample(data: data, start: date, end: date, metadata: nil)
        try await store.save(sample)
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
                if done {
                    finished = true
                    continuation.resume(returning: samples?.first)
                }
            }
            store.execute(query)
        }

        if let sample {
            try await store.delete(sample)
        }
    }
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
    private var patientFamily = ""
    private var authorOrg = ""
    private var observations: [CDAObservation] = []

    private var elementStack: [String] = []
    private var currentText = ""
    private var documentDateSet = false

    private var inObservation = false
    private var obsLoinc: String?
    private var obsDisplay: String?
    private var obsValue: Double?
    private var obsUnit: String?

    static func parse(data: Data, id: UUID) -> LabReport? {
        let delegate = CDADocumentParser()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()

        guard let date = delegate.reportDate else { return nil }

        let entries = delegate.observations.map { obs -> LabReport.Entry in
            let internalCode = LabMapping.internalCode(forLoinc: obs.loinc) ?? obs.loinc
            let mappedName = LabMapping.displayName(for: internalCode)
            let name = mappedName == internalCode ? obs.display : mappedName
            let display = obs.value.truncatingRemainder(dividingBy: 1) == 0
                ? String(format: "%.0f", obs.value)
                : String(format: "%.4g", obs.value)
            return LabReport.Entry(
                id: UUID(),
                code: internalCode,
                name: name,
                displayValue: display,
                numericValue: obs.value,
                unit: obs.unit
            )
        }

        return LabReport(
            id: id,
            date: date,
            patientName: delegate.patientFamily,
            authorName: delegate.authorOrg,
            entries: entries
        )
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

        case "code" where inObservation:
            if attrs["codeSystem"] == "2.16.840.1.113883.6.1" {
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

    func parser(_ parser: XMLParser, didEndElement element: String,
                namespaceURI: String?, qualifiedName: String?) {
        defer {
            if !elementStack.isEmpty { elementStack.removeLast() }
            currentText = ""
        }

        switch element {
        case "family" where elementStack.dropLast().contains("patientRole"):
            let trimmed = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty && trimmed != "Unknown" { patientFamily = trimmed }

        case "name" where elementStack.dropLast().contains("representedOrganization"):
            let trimmed = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty && trimmed != "LabImporter" { authorOrg = trimmed }

        case "observation" where inObservation:
            if let loinc = obsLoinc, let display = obsDisplay,
               let value = obsValue, let unit = obsUnit {
                observations.append(CDAObservation(loinc: loinc, display: display, value: value, unit: unit))
            }
            inObservation = false

        default:
            break
        }
    }

    private func hl7Date(_ value: String) -> Date? {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyyMMdd"
        fmt.locale = Locale(identifier: "en_US_POSIX")
        return fmt.date(from: value)
    }
}
