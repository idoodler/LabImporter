import Foundation
import Testing
@testable import LabImporter

/// A store whose first read takes a snapshot and then waits until the test lets it finish:
/// a read that started before a save and finishes after the next one.
private actor SlowFirstRead: ReportDocumentStore {
    let health: FakeHealthStore
    private var reads = 0
    private var started: CheckedContinuation<Void, Never>?
    private var release: CheckedContinuation<Void, Never>?
    private var hasStarted = false

    init(_ health: FakeHealthStore) { self.health = health }

    func importCDADocument(_ xmlString: String, date: Date) async throws {
        try await health.importCDADocument(xmlString, date: date)
    }

    func deleteCDADocument(id: UUID) async throws {
        try await health.deleteCDADocument(id: id)
    }

    func loadStoredReports() async throws -> StoredReports {
        reads += 1
        let snapshot = try await health.loadStoredReports()
        guard reads == 1 else { return snapshot }
        hasStarted = true
        started?.resume()
        await withCheckedContinuation { release = $0 }
        return snapshot
    }

    func firstReadStarted() async {
        if hasStarted { return }
        await withCheckedContinuation { started = $0 }
    }

    func finishFirstRead() {
        release?.resume()
        release = nil
    }
}

/// A store whose first read lingers (up to 2 s) until another read starts: an import that overlaps
/// the first then reads the same list, before the first has saved anything.
private actor LingeringFirstRead: ReportDocumentStore {
    let health: FakeHealthStore
    private var reads = 0

    init(_ health: FakeHealthStore) { self.health = health }

    func importCDADocument(_ xmlString: String, date: Date) async throws {
        try await health.importCDADocument(xmlString, date: date)
    }

    func deleteCDADocument(id: UUID) async throws {
        try await health.deleteCDADocument(id: id)
    }

    func loadStoredReports() async throws -> StoredReports {
        reads += 1
        let snapshot = try await health.loadStoredReports()
        if reads == 1 {
            for _ in 0..<200 where reads == 1 { try? await Task.sleep(for: .milliseconds(10)) }
        }
        return snapshot
    }
}

@MainActor
struct OverlappingWritesTests {
    private let values = [LabValue(code: "2160-0", name: "Creatinine", displayValue: "0.8", numericValue: 0.8, unit: "mg/dl")]

    @Test func aSecondTapOnSaveWhileTheFirstIsWritingSavesOnce() async throws {
        let health = FakeHealthStore()
        let saving = SaveInFlight()
        let save: @MainActor () async -> Void = { [values] in
            _ = try? await ReportWriter(store: health).save(values, date: Fixtures.date(2026, 5, 6))
        }

        #expect(saving.start(save))
        #expect(saving.isSaving, "Save and Share are off from the first tap")
        #expect(!saving.start(save), "The second tap is ignored")
        await saving.finish()

        #expect(!saving.isSaving)
        #expect(await health.saveAttempts == 1)
        #expect(try await health.loadStoredReports().reports.count == 1)

        // Once it's done, saving works again (a failed save is retried this way).
        #expect(saving.start(save))
        await saving.finish()
        #expect(await health.saveAttempts == 2)
    }

    @Test func aReadThatFinishesAfterANewerOneIsDropped() async throws {
        let health = FakeHealthStore()
        let store = SlowFirstRead(health)
        let latest = LatestReportLoad()

        let older = Task { await latest.load(from: store) }
        await store.firstReadStarted() // Before the save: it sees no reports.
        _ = try await ReportWriter(store: health).save(values, date: Fixtures.date(2026, 5, 6))
        let newer = await latest.load(from: store)
        await store.finishFirstRead()
        let stale = await older.value

        #expect(try #require(newer).get().reports.count == 1)
        #expect(stale == nil, "The stale list, without the saved report, is never shown")
    }

    @Test func aFailedReadIsReportedNotEmpty() async throws {
        let health = FakeHealthStore()
        await health.failLoads()
        let result = try #require(await LatestReportLoad().load(from: health))
        #expect(throws: InjectedFault.self) { try result.get() }
    }

    /// Import Reports tapped again while a file is still importing: the second import finds the
    /// reports the first saved, instead of planning against the same list and saving them twice.
    @Test func twoOverlappingImportsOfOneFileSaveItsReportsOnce() async throws {
        let reports = [0.8, 0.9].enumerated().map { index, value in
            LabReport(
                id: UUID(), date: Fixtures.date(2026, 5, index + 1), patientName: "", authorName: "",
                entries: [LabReport.Entry(id: UUID(), code: "2160-0", name: "Creatinine", displayValue: "\(value)", numericValue: value, unit: "mg/dl")]
            )
        }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).json")
        try ReportTransferService.export(of: reports).contents.write(to: file, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: file) }
        let health = FakeHealthStore()
        let store = LingeringFirstRead(health)

        async let first = ReportTransferService.importFile(at: file, store: store)
        async let second = ReportTransferService.importFile(at: file, store: store)
        let summaries = try await [first, second]

        #expect(try await health.loadStoredReports().reports.count == 2)
        #expect(summaries.map(\.imported).sorted() == [0, 2])
        #expect(summaries.map(\.alreadyPresent).sorted() == [0, 2])
    }
}
