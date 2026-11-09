import Foundation
@testable import LabImporter

import Testing

private final class BundleToken {}

/// Shared fixtures for the unit tests.
enum Fixtures {
    /// A golden fixture shared with the Kotlin tests (shared/src/jvmTest/golden),
    /// bundled into this test target by tools/sync_xcode_project.rb.
    static func golden(_ name: String) throws -> String {
        let url = try #require(Bundle(for: BundleToken.self).url(forResource: name, withExtension: nil))
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// Local midnight of a calendar day, the way reports carry their date.
    static func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        Calendar.current.date(from: DateComponents(year: year, month: month, day: day))!
    }

    /// Same fixture as `GoldenCda.narrative` in the Kotlin tests.
    static let goldenNarrative = CDAExportService.Narrative(
        languageCode: "de-DE", documentTitle: "Lab Results", sectionTitle: "Laborwerte", testHeader: "Test", resultHeader: "Ergebnis"
    )

    /// Same fixture as `GoldenCda.values` in the Kotlin tests.
    static let goldenValues: [LabValue] = [
        LabValue(code: "2160-0", name: "Creatinine", displayValue: "0,8", numericValue: 0.8, unit: "mg/dl"),
        LabValue(code: "2823-3", name: "Potassium", displayValue: "4,1", numericValue: 4.1, unit: "mmol/l"),
        LabValue(code: "4548-4", name: "HbA1c", displayValue: "6,53", numericValue: 6.53, unit: "%"),
        LabValue(code: "1742-6", name: "ALT", displayValue: "12345,6", numericValue: 12_345.6, unit: "U/l"),
        LabValue(code: "KREA", name: "unmapped", displayValue: "1", numericValue: 1, unit: ""),
        LabValue(code: "2093-3", name: "deselected", displayValue: "198", numericValue: 198, unit: "mg/dl", isSelected: false),
        LabValue(code: "2951-2", name: "no value", displayValue: "-", numericValue: nil, unit: "mmol/l")
    ]

    /// Random document/observation ids and the build-specific identity → fixed tokens.
    static func normalizeCDA(_ xml: String) -> String {
        xml.replacingOccurrences(of: #"root="[0-9a-f-]{36}""#, with: #"root="UUID""#, options: .regularExpression)
            .replacingOccurrences(
                of: #"<manufacturerModelName>[^<]*</manufacturerModelName>"#,
                with: "<manufacturerModelName>IDENTITY</manufacturerModelName>",
                options: .regularExpression
            )
    }
}

/// A write or read that the test made fail.
struct InjectedFault: Error {}

/// Apple Health as the report flows use it, over the real CDA export and the shared parser:
/// documents are the XML the app writes, read back as `HealthKitService` reads them. Any
/// write, and the read, can be made to fail.
actor FakeHealthStore: ReportDocumentStore {
    private(set) var documents: [(id: UUID, xml: String)] = []
    private(set) var saveAttempts = 0
    /// Which save attempts fail, counting from 1.
    private var failingSaves: Set<Int> = []
    private var failDeletes = false
    private var failLoads = false

    func failSaves(_ attempts: Set<Int>) { failingSaves = attempts }
    func failEverySave() { failingSaves = Set(1...1_000) }
    func failDeletes(_ fail: Bool = true) { failDeletes = fail }
    func failLoads(_ fail: Bool = true) { failLoads = fail }

    /// Stores a document as it is, the way another version of the app may have written it.
    func store(_ xml: String) -> UUID {
        let id = UUID()
        documents.append((id, xml))
        return id
    }

    func importCDADocument(_ xmlString: String, date: Date) async throws {
        saveAttempts += 1
        if failingSaves.contains(saveAttempts) { throw InjectedFault() }
        documents.append((UUID(), xmlString))
    }

    func deleteCDADocument(id: UUID) async throws {
        if failDeletes { throw InjectedFault() }
        documents.removeAll { $0.id == id }
    }

    func loadStoredReports() async throws -> StoredReports {
        if failLoads { throw InjectedFault() }
        return HealthKitService.storedReports(fromDocuments: documents.map { (data: Data($0.xml.utf8), id: $0.id) })
    }
}
