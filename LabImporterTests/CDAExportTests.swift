import Foundation
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
            authorName: "Labor & Co"
        )
        let golden = try Fixtures.golden("report.cda.xml")
        let actual = Fixtures.normalizeCDA(xml)
        #expect(actual == golden)
    }

    @Test func exportRefusesReportsWithoutExportableValues() {
        let unmapped = [LabValue(code: "KREA", name: "x", displayValue: "1", numericValue: 1, unit: "")]
        #expect(throws: CDAExportError.self) {
            _ = try CDAExportService().exportToTempFile(labValues: unmapped, date: .now)
        }
    }
}
