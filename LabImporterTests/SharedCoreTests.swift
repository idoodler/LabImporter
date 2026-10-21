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
        #expect(report.entries.map(\.numericValue) == [0.8, 4.1, 6.53, 12_350])
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

    @Test func trendFollowsTheLastTwoReadings() {
        let day = Fixtures.date(2026, 1, 1)
        #expect(MetricTrend(history: [SparkPoint(date: day, value: 1), SparkPoint(date: day, value: 2)]) == .rising)
        #expect(MetricTrend(history: [SparkPoint(date: day, value: 2), SparkPoint(date: day, value: 1)]) == .falling)
        #expect(MetricTrend(history: [SparkPoint(date: day, value: 2), SparkPoint(date: day, value: 2)]) == .steady)
        #expect(MetricTrend(history: [SparkPoint(date: day, value: 2)]) == nil)
    }

    @Test func trendWindowsSpanTheSharedDays() {
        #expect(TrendsView.TrendWindow.allCases.map(\.days) == [92, 183, 366, nil])
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
        let file = ReportTransferService.exportContents(of: reports)
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
}
