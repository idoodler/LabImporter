import Foundation
import PDFKit
import Testing
import UIKit
@testable import LabImporter

/// PDF text extraction: embedded text where it can be trusted, OCR where it can't.
struct OCRServiceTests {
    @Test func privateUseCharactersAreDetected() {
        // Function Health's PDFs draw "<" as U+E097 and "(" ")" as U+E081 / U+E082.
        #expect(OCRService.hasPrivateUseCharacters("Lead In Range· \u{E097}1.0 mcg/dL"))
        #expect(OCRService.hasPrivateUseCharacters("Immunoglobulin A \u{E081}IgA\u{E082}"))
        #expect(!OCRService.hasPrivateUseCharacters("Kreatinin 0,8 mg/dl (< 1,1) · µmol/l ≤ 5"))
    }

    /// A page whose embedded text uses private-use code points is read from
    /// its rendered glyphs instead, and every page keeps its place.
    @Test func pagesWithPrivateUseTextAreReadFromTheirGlyphsInPageOrder() async throws {
        let url = try makePDF(pages: ["FIRST PAGE", "SECOND PAGE \u{E097}", "THIRD PAGE"])
        defer { try? FileManager.default.removeItem(at: url) }
        // The setup holds: PDFKit's embedded text for page 2 carries the private-use character.
        let embedded = try #require(PDFDocument(url: url)?.page(at: 1)?.string)
        #expect(OCRService.hasPrivateUseCharacters(embedded), "\(embedded.unicodeScalars.map { String($0.value, radix: 16) })")
        let text = try await OCRService().extractText(fromPDFAt: url)
        #expect(!OCRService.hasPrivateUseCharacters(text))
        let first = try #require(text.range(of: "FIRST"))
        let second = try #require(text.range(of: "SECOND", options: .caseInsensitive))
        let third = try #require(text.range(of: "THIRD"))
        #expect(first.lowerBound < second.lowerBound && second.lowerBound < third.lowerBound, "\(text)")
    }

    private func makePDF(pages: [String]) throws -> URL {
        let bounds = CGRect(x: 0, y: 0, width: 612, height: 792)
        let data = UIGraphicsPDFRenderer(bounds: bounds).pdfData { context in
            for page in pages {
                context.beginPage()
                (page as NSString).draw(at: CGPoint(x: 72, y: 72), withAttributes: [.font: UIFont.systemFont(ofSize: 36)])
            }
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        try data.write(to: url)
        return url
    }
}
