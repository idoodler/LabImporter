import Foundation
import Testing
@testable import LabImporter

/// The Swift face of the shared Kotlin core: conversions at the boundary and
/// the rules the views now take from `LabImporterCore`. (Not importing the
/// core here: its model types share the app's names.)
struct SharedCoreTests {
    private func report(_ date: Date, _ readings: [(code: String, value: Double?)]) -> LabReport {
        LabReport(
            id: UUID(), date: date, patientName: "", authorName: "",
            entries: readings.map {
                LabReport.Entry(id: UUID(), code: $0.code, name: $0.code, displayValue: $0.value.map { "\($0)" } ?? "-",
                                numericValue: $0.value, unit: "u")
            }
        )
    }

    @Test func calendarDaysSurviveTheBridge() {
        // Leap day, a DST change (Europe) and the turn of a year.
        for (year, month, day) in [(2024, 2, 29), (2026, 3, 29), (2026, 10, 25), (2025, 12, 31)] {
            let date = Fixtures.date(year, month, day)
            #expect(Date(shared: date.sharedDate) == date)
            #expect(date.sharedDate.description() == String(format: "%04d-%02d-%02d", year, month, day))
        }
    }

    @Test func storedDocumentsReadBackThroughTheSharedParser() throws {
        let xml = CDAExportService().generateCDA(
            labValues: Fixtures.goldenValues, date: Fixtures.date(2026, 5, 6),
            patientName: "Max Mustermann", authorName: "Labor & Co"
        )
        let id = UUID()
        let report = try #require(HealthKitService.report(fromCDA: xml, id: id))
        #expect(report.id == id)
        #expect(report.date == Fixtures.date(2026, 5, 6))
        #expect(report.patientName == "Max Mustermann")
        #expect(report.authorName == "Labor & Co")
        // Only exportable values were written: mapped, selected, numeric.
        #expect(report.entries.map(\.code) == ["2160-0", "2823-3", "4548-4", "1742-6"])
        #expect(report.entries.map(\.numericValue) == [0.8, 4.1, 6.53, 12_345.6])
        #expect(report.entries.map(\.unit) == ["mg/dL", "mmol/L", "%", "U/L"])
        // Documents this app didn't write are skipped, never guessed at.
        #expect(HealthKitService.report(fromCDA: "<ClinicalDocument/>", id: id) == nil)
    }

    @Test func dashboardOrdersPinnedThenCustomThenByName() {
        let reports = [
            report(Fixtures.date(2026, 1, 1), [("2160-0", 1.0), ("4548-4", 6.0), ("2823-3", 4.0), ("2951-2", 140)]),
            report(Fixtures.date(2026, 2, 1), [("2160-0", 1.2)])
        ]
        var prefs = LabDisplayPreferences()
        prefs.pinnedCodes = ["2951-2"]
        prefs.orderedCodes = ["2823-3"]
        prefs.hiddenCodes = ["4548-4"]
        let content = DashboardView.Content(reports: reports, prefs: prefs)
        #expect(content.metrics.map(\.entry.code) == ["2951-2", "2823-3", "2160-0"])
        // The latest reading leads; the sparkline keeps every point in the window.
        #expect(content.metrics.last?.entry.numericValue == 1.2)
        #expect(content.metrics.last?.history.map(\.value) == [1.0, 1.2])
        #expect(content.backgroundColors.count <= 3)
        #expect(content.soleMetric == nil)
        #expect(!content.showsFewValuesHint)

        let single = DashboardView.Content(reports: [report(Fixtures.date(2026, 1, 1), [("4548-4", 5.4)])], prefs: .init())
        #expect(single.soleMetric?.entry.code == "4548-4")
        let pair = DashboardView.Content(reports: [report(Fixtures.date(2026, 1, 1), [("4548-4", 5.4), ("2160-0", 1)])], prefs: .init())
        #expect(pair.showsFewValuesHint)
    }

    @Test func resultsThatAreNotNumbersAreListedOnTheDashboard() {
        func text(_ date: Date, _ result: String) -> LabReport {
            LabReport(id: UUID(), date: date, patientName: "", authorName: "", entries: [
                LabReport.Entry(id: UUID(), code: "5196-1", name: "HBsAg", displayValue: result, numericValue: nil, unit: "")
            ])
        }
        let reports = [
            report(Fixtures.date(2026, 1, 1), [("2160-0", 1.0)]),
            text(Fixtures.date(2026, 2, 1), "Non-reactive"),
            text(Fixtures.date(2026, 3, 1), "Reactive"),
        ]
        let content = DashboardView.Content(reports: reports, prefs: .init())
        let results = content.metrics.first { $0.entry.code == "5196-1" }
        #expect(results?.isQualitative == true)
        #expect(results?.entry.displayValue == "Reactive")
        #expect(results?.results.map(\.text) == ["Non-reactive", "Reactive"])
        #expect(results?.history.isEmpty == true)
        // The hero card charts: a lone qualitative metric stays a grid card.
        #expect(DashboardView.Content(reports: [text(Fixtures.date(2026, 2, 1), "Reactive")], prefs: .init()).soleMetric == nil)
    }

    @Test func trendFollowsTheLastTwoReadings() {
        let day = Fixtures.date(2026, 1, 1)
        #expect(MetricTrend(history: [SparkPoint(date: day, value: 1), SparkPoint(date: day, value: 2)]) == .rising)
        #expect(MetricTrend(history: [SparkPoint(date: day, value: 2), SparkPoint(date: day, value: 1)]) == .falling)
        #expect(MetricTrend(history: [SparkPoint(date: day, value: 2), SparkPoint(date: day, value: 2)]) == .steady)
        #expect(MetricTrend(history: [SparkPoint(date: day, value: 2)]) == nil)
    }

    @Test func chartsUseTheSharedScale() throws {
        let dates = [Fixtures.date(2024, 1, 10), Fixtures.date(2024, 1, 24), Fixtures.date(2026, 5, 28)]
        let domain = try #require(ChartDomain(dates: dates, values: [32, 35, 34], range: nil))
        #expect(domain.yDomain == 0...40)
        #expect(domain.yTicks == [0, 10, 20, 30, 40])
        #expect(domain.xDomain == dates[0]...dates[2])
        #expect(domain.xLabels == [dates[0], dates[2]])

        let sameDay = try #require(ChartDomain(dates: [dates[0], dates[0]], values: [32, 35], range: nil))
        #expect(sameDay.xLabels == [dates[0]])

        let withRange = try #require(ChartDomain(dates: dates, values: [32, 35, 34], range: ReferenceRange(low: 30, high: 50)))
        #expect(withRange.yDomain == 0...50)

        let lone = try #require(ChartDomain(dates: [dates[0]], values: [32], range: nil))
        #expect(lone.xLabels == [dates[0]])
        #expect(lone.xDomain.lowerBound < dates[0] && lone.xDomain.upperBound > dates[0])
        #expect(ChartDomain(dates: [], values: [], range: nil) == nil)
    }

    @Test func trendWindowsSpanTheSharedDays() {
        #expect(TrendsView.TrendWindow.allCases.map(\.days) == [92, 183, 366, nil])
        #expect(TrendsView.TrendWindow.opening == .all)
    }

    @Test func siriTellsTermsWithOneNameApart() throws {
        let codes = ["2345-7", "14749-6", "4548-4"].map { CodeName(code: $0, name: "") }
        #expect(LabMapping.displayName(for: "2345-7") == LabMapping.displayName(for: "14749-6"))
        #expect(codes.codesSharingAName == ["2345-7", "14749-6"])
        #expect(LabMapping.catalogName(for: "2345-7") != LabMapping.catalogName(for: "14749-6"))

        let subtitles = try ["2345-7", "14749-6"].map { code in
            try #require(LabMetricEntity(id: code, name: LabMapping.displayName(for: code)).displayRepresentation.subtitle)
        }
        #expect(String(localized: subtitles[0]) == LabMapping.catalogName(for: "2345-7"))
        #expect(String(localized: subtitles[1]) == LabMapping.catalogName(for: "14749-6"))
    }

    @Test func codeNamesListEachCodeOnceInFirstSeenOrder() {
        let reports = [
            report(Fixtures.date(2026, 1, 1), [("4548-4", 6.0), ("LOCAL", nil)]),
            report(Fixtures.date(2026, 2, 1), [("LOCAL", 1.0), ("2160-0", 1.0)])
        ]
        #expect(reports.codeNames.map(\.code) == ["4548-4", "LOCAL", "2160-0"])
        // An unmapped code keeps the name printed on the report.
        #expect(reports.codeNames[1].name == "LOCAL")
    }

    @Test func transferFilesRoundTripAndSkipWhatIsAlreadyHere() throws {
        let reports = [
            report(Fixtures.date(2026, 5, 6), [("2160-0", 0.8), ("2823-3", 4.1)]),
            report(Fixtures.date(2026, 9, 10), [("4548-4", 6.5)])
        ]
        let file = ReportTransferService.export(of: reports).contents
        let fresh = try ReportTransferService.plan(contents: file, existing: [])
        #expect(fresh.reports.count == 2)
        #expect(fresh.alreadyPresent == 0)
        // Importing the same file again — or sending it back — adds nothing.
        let again = try ReportTransferService.plan(contents: file, existing: fresh.reports)
        #expect(again.reports.isEmpty)
        #expect(again.alreadyPresent == 2)
        // A CDA document shared from either app imports too.
        let cda = CDAExportService().generateCDA(labValues: reports[1].asLabValues, date: reports[1].date)
        #expect(try ReportTransferService.plan(contents: cda, existing: []).reports.count == 1)
        #expect(throws: ReportTransferService.Failure.self) {
            try ReportTransferService.plan(contents: "{\"resourceType\":\"Patient\"}", existing: [])
        }
    }

    private func transferFile(_ reports: [LabReport]) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).json")
        try ReportTransferService.export(of: reports).contents.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    /// A read that fails leaves Settings with no reports, so everything would come in as a duplicate.
    @Test func aFileIsNotImportedWhileTheSavedReportsCantBeRead() async throws {
        let file = try transferFile([report(Fixtures.date(2026, 5, 6), [("2160-0", 0.8)])])
        defer { try? FileManager.default.removeItem(at: file) }
        struct ReadFailed: Error {}
        await #expect(throws: ReportTransferService.Failure.savedReportsUnreadable) {
            try await ReportTransferService.importFile(at: file) { throw ReadFailed() }
        }
    }

    @Test func importingSkipsWhatAFreshReadFinds() async throws {
        let saved = report(Fixtures.date(2026, 5, 6), [("2160-0", 0.8)])
        let file = try transferFile([saved])
        defer { try? FileManager.default.removeItem(at: file) }
        let summary = try await ReportTransferService.importFile(at: file) { [saved] }
        #expect(summary.imported == 0)
        #expect(summary.alreadyPresent == 1)
    }

    @Test func aDamagedFileIsCounted() throws {
        let intact = report(Fixtures.date(2026, 9, 10), [("4548-4", 6.5)])
        let damaged = report(Fixtures.date(2026, 5, 6), [("2160-0", 0.8)])
        let file = ReportTransferService.export(of: [intact, damaged]).contents
            .replacingOccurrences(of: "\"2026-05-06\"", with: "\"not a date\"")
        let plan = try ReportTransferService.plan(contents: file, existing: [])
        #expect(plan.reports.count == 1)
        #expect(plan.damaged == 1)
    }

    @Test func anExportSaysWhatItLeftOut() {
        let mapped = report(Fixtures.date(2026, 5, 6), [("2160-0", 0.8), ("NOT-A-CODE", 1)])
        let export = ReportTransferService.export(of: [mapped])
        #expect(export.written == 1)
        #expect(export.leftOut == 1)
        #expect(export.contents.contains("2160-0") && !export.contents.contains("NOT-A-CODE"))
    }

    @Test func anExportWithNothingInItIsAnErrorNotAnEmptyFile() {
        let export = ReportTransferService.export(of: [report(Fixtures.date(2026, 5, 6), [("NOT-A-CODE", 1)])])
        #expect(throws: ReportTransferService.Failure.self) {
            try ReportTransferService.writeFile(export)
        }
    }

    @Test func aFileFromANewerVersionAsksForAnUpdate() throws {
        let cda = CDAExportService().generateCDA(
            labValues: report(Fixtures.date(2026, 5, 6), [("2160-0", 0.8)]).asLabValues, date: Fixtures.date(2026, 5, 6)
        )
        #expect(throws: ReportTransferService.Failure.newerVersion) {
            try ReportTransferService.plan(contents: cda.replacingOccurrences(of: "LabImporter CDA v1", with: "LabImporter CDA v99"), existing: [])
        }
        let current = "\"code\":\"1\""
        let file = ReportTransferService.export(of: [report(Fixtures.date(2026, 5, 6), [("2160-0", 0.8)])]).contents
        #expect(file.contains(current))
        #expect(throws: ReportTransferService.Failure.newerVersion) {
            try ReportTransferService.plan(contents: file.replacingOccurrences(of: current, with: "\"code\":\"99\""), existing: [])
        }
    }
}
