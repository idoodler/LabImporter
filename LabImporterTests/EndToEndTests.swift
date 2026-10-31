import Foundation
import Testing
import UIKit
@testable import LabImporter

/// The import end to end, as far as a simulator can go: a PDF through OCR,
/// reading in parts, the shared post-processing with the bundled catalog,
/// review rules, the CDA saved to and read back from Apple Health, the
/// dashboard and the transfer file. Only the on-device model is a stand-in
/// (`StandInReport.read`, reading a Function Health report the way the real
/// model does — status labels, flags and a section heading included).
struct EndToEndTests {
    /// Every part the stand-in model was asked to read.
    private actor PartLog {
        private(set) var parts: [String] = []
        func add(_ part: String) { parts.append(part) }
    }

    @Test func aFunctionHealthReportFromPDFToTheDashboard() async throws {
        // 1. The PDF: the report as embedded text, and a page whose font draws with
        //    private-use characters (Function Health's "<") — that page is OCR'd.
        let url = try makePDF(pages: [StandInReport.text, "Page 2 of 2 \u{E097}"])
        defer { try? FileManager.default.removeItem(at: url) }
        let text = try await OCRService().extractText(fromPDFAt: url)
        #expect(!OCRService.hasPrivateUseCharacters(text))
        #expect(text.contains("Uric Acid") && text.contains("Hepatitis B Surface Antigen"))

        // 2. Read in parts (the report is longer than one), each part once.
        let log = PartLog()
        let parser = LabParserService { part, progress in
            await log.add(part)
            return try await StandInReport.read(part, progress)
        }
        let result = try await parser.parseLabValues(from: text)
        #expect(await log.parts.count > 1)
        #expect(result.reportDate.map { Calendar.current.dateComponents([.year, .month, .day], from: $0) }
            == DateComponents(year: 2026, month: 8, day: 12))
        #expect(result.patientName == "Jane Roe")

        // 3. What review shows: English names resolved, labels and flags gone,
        //    "<1.0" and "NON-REACTIVE" kept as results, the heading never saved.
        let values = result.values
        func value(_ code: String) throws -> LabValue { try #require(values.first { $0.code == code }, "\(code) in \(values.map(\.code))") }
        #expect(try value("3084-1").numericValue == 5.1)        // Uric Acid, "In Range· 5.1"
        #expect(try value("2028-9").numericValue == 24)         // Carbon Dioxide → total CO₂
        #expect(try value("777-3").numericValue == 263)         // Platelet Count, Thousand/uL
        #expect(try value("3016-3").numericValue == 1.9)        // TSH
        #expect(try value("58709-7").numericValue == 4.5)       // Celiac – Deamidated Gliadin IgA, "4.5 *"
        let lead = try value("77307-7")                         // venous lead, "In Range· <1.0"
        #expect(lead.numericValue == nil && lead.qualitativeResult == "<1.0" && lead.isExportable)
        let hbsAg = try #require(values.first { $0.qualitativeResult == "NON-REACTIVE" && $0.isExportable })
        #expect(hbsAg.isSuggestedCode)
        let heading = try #require(values.first { $0.name.localizedCaseInsensitiveContains("Hepatitis Panel") })
        #expect(!heading.isExportable, "A section heading is never saved: \(heading.code)")
        #expect(values.filter(\.isExportable).count == 7)
        #expect(values.duplicateLoincCodes().isEmpty)

        // 4. Saved as a CDA document and read back the way Apple Health returns it.
        let xml = CDAExportService().generateCDA(
            labValues: values, date: try #require(result.reportDate), patientName: "Jane Roe", authorName: "Function Health"
        )
        #expect(xml.contains(#"<value xsi:type="ST">&lt;1.0 mcg/dL</value>"#))
        let saved = try #require(HealthKitService.report(fromCDA: xml, id: UUID()))
        #expect(saved.entries.count == 7)
        let savedLead = try #require(saved.entries.first { $0.code == "77307-7" })
        #expect(savedLead.displayValue == "<1.0" && savedLead.unit == "mcg/dL" && savedLead.numericValue == nil)
        #expect(saved.entries.first { $0.code == "3084-1" }?.numericValue == 5.1)

        // 5. The dashboard charts the numbers and lists the results that aren't numbers.
        let dashboard = DashboardView.Content(reports: [saved], prefs: LabDisplayPreferences())
        #expect(dashboard.metrics.count == 7)
        let leadMetric = try #require(dashboard.metrics.first { $0.entry.code == "77307-7" })
        #expect(leadMetric.isQualitative && leadMetric.results.map(\.formatted) == ["<1.0 mcg/dL"])
        #expect(dashboard.metrics.first { $0.entry.code == "3084-1" }?.isQualitative == false)

        // 6. The iPhone ⇄ Android transfer file carries all of it and recognizes it again.
        let file = ReportTransferService.exportContents(of: [saved])
        let plan = try ReportTransferService.plan(contents: file, existing: [])
        let transferred = try #require(plan.reports.first)
        #expect(Set(transferred.entries.map(\.code)) == Set(saved.entries.map(\.code)))
        #expect(transferred.entries.first { $0.code == "77307-7" }?.displayValue == "<1.0")
        #expect(try ReportTransferService.plan(contents: file, existing: [saved]).alreadyPresent == 1)
    }

    /// A part the model still finds too long is read in halves; one that's
    /// too long even then reaches the user as the "too long" advice.
    @Test func aPartTheModelFindsTooLongIsReadInHalvesOrExplained() async throws {
        let halving = LabParserService { part, progress in
            if part.count > 1_200 { throw LabParserError.documentTooLong }
            return try await StandInReport.read(part, progress)
        }
        let result = try await halving.parseLabValues(from: StandInReport.text)
        #expect(result.values.filter(\.isExportable).count == 7)

        let refusing = LabParserService { _, _ in throw LabParserError.documentTooLong }
        await #expect(throws: LabParserError.documentTooLong) {
            _ = try await refusing.parseLabValues(from: StandInReport.text)
        }
    }

    private func makePDF(pages: [String]) throws -> URL {
        let bounds = CGRect(x: 0, y: 0, width: 612, height: 792)
        let data = UIGraphicsPDFRenderer(bounds: bounds).pdfData { context in
            for page in pages {
                context.beginPage()
                (page as NSString).draw(
                    in: bounds.insetBy(dx: 36, dy: 36),
                    withAttributes: [.font: UIFont.monospacedSystemFont(ofSize: 7, weight: .regular)]
                )
            }
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        try data.write(to: url)
        return url
    }
}
