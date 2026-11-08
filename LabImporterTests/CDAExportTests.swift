import Foundation
import HealthKit
import Testing
@testable import LabImporter

/// The CDA documents in Apple Health are the app's only storage, so their exact
/// shape is pinned: the same golden file is asserted by the Kotlin tests.
struct CDAExportTests {
    @Test func documentMatchesGolden() throws {
        let xml = CDAExportService().generateCDA(
            labValues: Fixtures.goldenValues,
            date: Fixtures.date(2026, 5, 6),
            patientName: "Max Mustermann",
            authorName: "Labor & Co",
            narrative: Fixtures.goldenNarrative
        )
        let golden = try Fixtures.golden("report.cda.xml")
        let actual = Fixtures.normalizeCDA(xml)
        #expect(actual == golden)
    }

    @Test func aDocumentSpeaksTheAppsLanguageAndHealthKitAcceptsIt() throws {
        let values = [LabValue(code: "2160-0", name: "x", displayValue: "0,8", numericValue: 0.8, unit: "mg/dl")]
        let xml = CDAExportService().generateCDA(labValues: values, date: Fixtures.date(2026, 5, 6))
        // The tests run in English.
        #expect(xml.contains("<languageCode code=\"en\"/>"))
        #expect(xml.contains("<th>Test</th><th>Result</th>"))
        #expect(!xml.contains("Laborwerte") && !xml.contains("Ergebnis"))
        _ = try HKCDADocumentSample(data: Data(xml.utf8), start: .now, end: .now, metadata: nil)

        let japanese = CDAExportService.Narrative(
            languageCode: "ja", documentTitle: "検査結果", sectionTitle: "検査値", testHeader: "検査", resultHeader: "結果"
        )
        let translated = CDAExportService().generateCDA(labValues: values, date: Fixtures.date(2026, 5, 6), narrative: japanese)
        _ = try HKCDADocumentSample(data: Data(translated.utf8), start: .now, end: .now, metadata: nil)
    }

    @Test func documentsReadBackInAnyLanguageIncludingTheOldGermanOnes() throws {
        let values = [LabValue(code: "2160-0", name: "x", displayValue: "0,8", numericValue: 0.8, unit: "mg/dl")]
        let german = try Fixtures.golden("report.cda.xml")
        #expect(german.contains("<languageCode code=\"de-DE\"/>") && german.contains("<th>Ergebnis</th>"))
        let oldReport = try #require(HealthKitService.report(fromCDA: german, id: UUID()))
        #expect(oldReport.entries.map(\.code) == ["2160-0", "2823-3", "4548-4", "1742-6"])

        let english = CDAExportService().generateCDA(labValues: values, date: Fixtures.date(2026, 5, 6))
        #expect(HealthKitService.report(fromCDA: english, id: UUID())?.entries.map(\.code) == ["2160-0"])
    }

    @Test func reportsThatCanNotBeListedAreCountedNotLost() {
        let values = [LabValue(code: "2160-0", name: "x", displayValue: "0,8", numericValue: 0.8, unit: "mg/dl")]
        let xml = CDAExportService().generateCDA(labValues: values, date: Fixtures.date(2026, 5, 6))
        let stored = HealthKitService.storedReports(fromDocuments: [
            (Data(xml.utf8), UUID()),
            (Data(xml.replacingOccurrences(of: "LabImporter CDA v1", with: "LabImporter CDA v98").utf8), UUID()),
            (Data(xml.replacingOccurrences(of: "LabImporter CDA v1", with: "LabImporter CDA v99").utf8), UUID()),
            // Cut off, but still recognizably this app's.
            (Data(xml.components(separatedBy: "</assignedAuthor>")[0].utf8), UUID()),
            (nil, UUID()),
            // An unversioned legacy export is ignored by design, without a word.
            (Data(xml.replacingOccurrences(of: "LabImporter CDA v1", with: "Other App").utf8), UUID())
        ])
        #expect(stored.reports.count == 1)
        #expect(stored.newerVersion == 2)
        #expect(stored.unreadable == 2)
    }

    @Test func exportRefusesReportsWithoutExportableValues() {
        let unmapped = [LabValue(code: "KREA", name: "x", displayValue: "1", numericValue: 1, unit: "")]
        #expect(throws: CDAExportError.self) {
            _ = try CDAExportService().exportToTempFile(labValues: unmapped, date: .now)
        }
    }
}
