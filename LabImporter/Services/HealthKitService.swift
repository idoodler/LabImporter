import HealthKit
import LabImporterCore
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

    /// Deliberately does **not** call `requestAuthorization` — that's done once
    /// up front (`requestInitialAuthorization`, at onboarding). Calling it again
    /// here is what was forcing Siri to foreground the app on every
    /// `AskLabValueIntent`/`LabMetricEntityQuery` read: `requestAuthorization`
    /// *can* present a system sheet, so the OS conservatively activates the app
    /// whenever an App Intent calls it, even when authorization is already
    /// granted and no sheet would actually appear. A query against
    /// already-granted permissions needs no such call.
    func loadCDADocuments() async throws -> [LabReport] {
        guard let documentType = cdaType else { return [] }

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
                        guard let xmlData = sample.document?.documentData,
                              let xml = String(data: xmlData, encoding: .utf8) else { return nil }
                        return Self.report(fromCDA: xml, id: sample.uuid)
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

    /// Rebuilds a report from a stored CDA document with the shared parser,
    /// which ignores unversioned/foreign documents and upgrades older schema
    /// versions (LabImporterCore `ReportSchema`).
    nonisolated static func report(fromCDA xml: String, id: UUID) -> LabReport? {
        SharedCore.start()
        return CdaParser.shared.parse(xml: xml, id: id.uuidString).map(LabReport.init(shared:))
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
