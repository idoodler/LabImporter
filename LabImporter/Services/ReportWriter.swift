import Foundation

/// The Apple Health writes and reads that saving, replacing and deleting reports need.
/// `HealthKitService` is the real one; tests use one that fails on cue.
protocol ReportDocumentStore: Sendable {
    /// Saves a CDA document; throws unless it's saved.
    func importCDADocument(_ xmlString: String, date: Date) async throws
    /// Deletes a document; throws unless it's gone.
    func deleteCDADocument(id: UUID) async throws
    func loadStoredReports() async throws -> StoredReports
}

extension HealthKitService: ReportDocumentStore {}

/// Saves, replaces and deletes reports in Apple Health in the order that never loses one:
/// a new version is saved before the old one goes, so a failed save leaves the old version,
/// and a failed removal afterwards leaves both (and says so), never neither.
struct ReportWriter: Sendable {
    let store: any ReportDocumentStore

    init(store: any ReportDocumentStore = HealthKitService.shared) {
        self.store = store
    }

    enum Failure: LocalizedError, Equatable {
        /// The report has values this build can't read: a new version would drop them.
        case readOnly(unreadValues: Int)

        var errorDescription: String? {
            switch self {
            case .readOnly(let unread): LabReport.readOnlyExplanation(unreadValues: unread)
            }
        }
    }

    enum SaveOutcome: Equatable {
        case saved
        /// The new version is saved, but the one it replaces couldn't be removed: both are in Apple Health.
        case savedOldVersionLeft
    }

    /// Saves reviewed values as a report, replacing `old` when editing one.
    /// Throws when nothing was saved; the old version is then untouched.
    func save(
        _ values: [LabValue], date: Date, patientName: String = "", authorName: String = "", replacing old: LabReport? = nil
    ) async throws -> SaveOutcome {
        if let old, old.isReadOnly { throw Failure.readOnly(unreadValues: old.unreadValues) }
        guard values.contains(where: \.isExportable) else { throw CDAExportError.noExportableValues }
        let xml = CDAExportService().generateCDA(labValues: values, date: date, patientName: patientName, authorName: authorName)
        try await store.importCDADocument(xml, date: date)
        guard let old else { return .saved }
        do {
            try await store.deleteCDADocument(id: old.id)
            return .saved
        } catch {
            // Not thrown: the new version is saved, and the caller warns about the old one.
            return .savedOldVersionLeft
        }
    }

    /// Deletes one report; throws unless it's gone.
    func delete(_ id: UUID) async throws {
        try await store.deleteCDADocument(id: id)
    }

    /// Deletes reports one by one, carrying on past a failure. Returns how many are left.
    func delete(_ ids: [UUID]) async -> Int {
        var failed = 0
        for id in ids {
            do {
                try await store.deleteCDADocument(id: id)
            } catch {
                // Counted: the caller says some couldn't be removed.
                failed += 1
            }
        }
        return failed
    }

    /// Saves reports as new ones (a transfer file's), carrying on past a failure. Returns how many weren't saved.
    func saveNew(_ reports: [LabReport]) async -> Int {
        var failed = 0
        for report in reports {
            do {
                _ = try await save(report.asLabValues, date: report.date, patientName: report.patientName, authorName: report.authorName)
            } catch {
                // Counted: the import summary says how many weren't saved.
                failed += 1
            }
        }
        return failed
    }
}
