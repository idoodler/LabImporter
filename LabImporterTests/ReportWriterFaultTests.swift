import AppIntents
import Foundation
import Testing
@testable import LabImporter

/// Every way saving, replacing, deleting and importing reports can go wrong, against the real
/// CDA export and the shared parser (the iOS side of Android's `ReportSavingFaultTest`):
/// the old or the new report always survives, and no failed write is reported as a success.
struct ReportWriterFaultTests {
    private let date = Fixtures.date(2026, 5, 6)

    private func values(_ readings: [(code: String, value: Double)]) -> [LabValue] {
        readings.map { LabValue(code: $0.code, name: $0.code, displayValue: "\($0.value)", numericValue: $0.value, unit: "mg/dl") }
    }

    private func readings(_ report: LabReport) -> [String: Double] {
        Dictionary(uniqueKeysWithValues: report.entries.map { ($0.code, $0.numericValue ?? .nan) })
    }

    /// A store holding one report of creatinine and potassium, and that report as the app lists it.
    private func storeWithOneReport() async throws -> (FakeHealthStore, LabReport) {
        let health = FakeHealthStore()
        let outcome = try await ReportWriter(store: health).save(values([("2160-0", 0.8), ("2823-3", 4.1)]), date: date)
        #expect(outcome == .saved)
        let reports = try await health.loadStoredReports().reports
        return (health, try #require(reports.first))
    }

    // MARK: - Replacing

    @Test func aFailingSaveKeepsTheOldReportAndReportsTheFailure() async throws {
        let (health, old) = try await storeWithOneReport()
        await health.failEverySave()

        await #expect(throws: InjectedFault.self) {
            _ = try await ReportWriter(store: health).save(values([("2823-3", 5.0)]), date: date, replacing: old)
        }

        let left = try await health.loadStoredReports().reports
        #expect(left.map(\.id) == [old.id])
        #expect(readings(try #require(left.first)) == ["2160-0": 0.8, "2823-3": 4.1])
    }

    @Test func aFailingDeleteOfTheReplacedVersionKeepsTheNewOneAndWarns() async throws {
        let (health, old) = try await storeWithOneReport()
        await health.failDeletes()

        let outcome = try await ReportWriter(store: health).save(values([("2823-3", 5.0)]), date: date, replacing: old)

        #expect(outcome == .savedOldVersionLeft)
        let stored = try await health.loadStoredReports().reports
        #expect(stored.count == 2)
        #expect(stored.contains { readings($0) == ["2823-3": 5.0] })
        #expect(stored.contains { $0.id == old.id })
    }

    @Test func aReplaceThatWorksLeavesOnlyTheNewVersion() async throws {
        let (health, old) = try await storeWithOneReport()

        let outcome = try await ReportWriter(store: health).save(values([("2823-3", 5.0)]), date: date, replacing: old)

        #expect(outcome == .saved)
        let stored = try await health.loadStoredReports().reports
        #expect(stored.map(readings) == [["2823-3": 5.0]])
    }

    // MARK: - Deleting

    @Test func aFailingDeleteReportsTheFailureAndKeepsTheReportListed() async throws {
        let (health, report) = try await storeWithOneReport()
        await health.failDeletes()
        let writer = ReportWriter(store: health)

        await #expect(throws: InjectedFault.self) { try await writer.delete(report.id) }
        #expect(await writer.delete([report.id, UUID()]) == 2)

        #expect(try await health.loadStoredReports().reports.map(\.id) == [report.id])
    }

    @Test func deletingSeveralReportsCarriesOnPastAFailure() async throws {
        let (health, report) = try await storeWithOneReport()
        #expect(await ReportWriter(store: health).delete([UUID(), report.id]) == 0)
        #expect(try await health.loadStoredReports().reports.isEmpty)
    }

    // MARK: - Importing a transfer file

    @Test func aTransferImportWithOneFailingSaveImportsTheRestAndReportsTheCount() async throws {
        let reports = [0.8, 0.9, 1.0].enumerated().map { index, value in
            LabReport(
                id: UUID(), date: Fixtures.date(2026, 5, index + 1), patientName: "", authorName: "",
                entries: [LabReport.Entry(id: UUID(), code: "2160-0", name: "Creatinine", displayValue: "\(value)", numericValue: value, unit: "mg/dl")]
            )
        }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).json")
        try ReportTransferService.export(of: reports).contents.write(to: file, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: file) }
        let health = FakeHealthStore()
        await health.failSaves([2])

        let summary = try await ReportTransferService.importFile(at: file, store: health)

        #expect(summary.imported == 2)
        #expect(summary.notSaved == 1)
        #expect(await health.saveAttempts == 3)
        #expect(try await health.loadStoredReports().reports.count == 2)
    }

    @Test func aTransferImportWhereEverySaveFailsImportsNothing() async throws {
        let report = LabReport(
            id: UUID(), date: date, patientName: "", authorName: "",
            entries: [LabReport.Entry(id: UUID(), code: "2160-0", name: "Creatinine", displayValue: "0.8", numericValue: 0.8, unit: "mg/dl")]
        )
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).json")
        try ReportTransferService.export(of: [report]).contents.write(to: file, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: file) }
        let health = FakeHealthStore()
        await health.failEverySave()

        let summary = try await ReportTransferService.importFile(at: file, store: health)

        #expect(summary.imported == 0)
        #expect(summary.notSaved == 1)
    }

    // MARK: - Values this build can't read

    /// A document whose first observation holds a value type this build doesn't know (a later
    /// build's coded result), written under the current schema version.
    private func documentWithAnUnreadableValue() -> String {
        var xml = CDAExportService().generateCDA(labValues: values([("2160-0", 0.8), ("2823-3", 4.1)]), date: date)
        if let first = xml.range(of: #"<value xsi:type="PQ"[^>]*/>"#, options: .regularExpression) {
            xml.replaceSubrange(first, with: #"<value xsi:type="CD" code="260385009" codeSystem="2.16.840.1.113883.6.96"/>"#)
        }
        return xml
    }

    @Test func aReportWithAValueThisBuildCanNotReadIsNeverReplaced() async throws {
        let health = FakeHealthStore()
        let xml = documentWithAnUnreadableValue()
        _ = await health.store(xml)
        let shown = try #require(try await health.loadStoredReports().reports.first)
        #expect(shown.isReadOnly)
        #expect(shown.unreadValues == 1)
        #expect(shown.entries.map(\.code) == ["2823-3"])

        // An edit that keeps every value it can see would still lose the one it can't.
        await #expect(throws: ReportWriter.Failure.readOnly(unreadValues: 1)) {
            _ = try await ReportWriter(store: health).save(shown.asLabValues, date: date, replacing: shown)
        }

        #expect(await health.saveAttempts == 0)
        let documents = await health.documents
        #expect(documents.map { $0.xml } == [xml])
    }

    @Test func aReportWithAValueThisBuildCanNotReadCanStillBeDeleted() async throws {
        let health = FakeHealthStore()
        _ = await health.store(documentWithAnUnreadableValue())
        let shown = try #require(try await health.loadStoredReports().reports.first)

        try await ReportWriter(store: health).delete(shown.id)

        #expect(await health.documents.isEmpty)
    }

    // MARK: - Apple Health without lab reports

    @Test func withoutTheDocumentTypeNothingPassesForSavedOrDeleted() async {
        let service = HealthKitService(cdaType: nil)
        await #expect(throws: HealthKitServiceError.documentsUnavailable) {
            try await service.importCDADocument("<ClinicalDocument/>", date: .now)
        }
        await #expect(throws: HealthKitServiceError.documentsUnavailable) {
            try await service.deleteCDADocument(id: UUID())
        }
    }

    // MARK: - Siri

    /// A lab value is only spoken once the phone is unlocked.
    @Test func siriAsksForAnUnlockBeforeSpeakingAValue() {
        guard case .requiresAuthentication = AskLabValueIntent.authenticationPolicy else {
            Issue.record("AskLabValueIntent runs on a locked phone: \(AskLabValueIntent.authenticationPolicy)")
            return
        }
        // Scanning speaks no value and opens the app, which needs an unlocked phone anyway.
        #expect(StartLabScanIntent.openAppWhenRun)
    }
}
