import SwiftUI
import UIKit

/// Renders one or more `LabReport`s into a polished, print-ready PDF that mirrors
/// the app's visual language — clinical category colors, grouped sections and
/// clean typography. Everything is produced on device; no lab data leaves the app.
///
/// The layout is paginated up front into fixed-height blocks (cover, report
/// header, section header, value row) so each block renders at a known height and
/// the greedy packer can fill A4 pages deterministically — no overlap, no clipping.
@MainActor
struct PDFExportService {
    /// A4 portrait, expressed in PDF points (1/72").
    static let pageSize = CGSize(width: 595, height: 842)
    static let margin: CGFloat = 40
    static let footerHeight: CGFloat = 26

    private var usableHeight: CGFloat {
        Self.pageSize.height - Self.margin * 2 - Self.footerHeight
    }

    /// Builds the PDF, writes it to a temp file and returns the URL for sharing.
    func exportToTempFile(reports: [LabReport]) throws -> URL {
        guard !reports.isEmpty else { throw PDFExportError.noReports }
        let ordered = reports.sorted { $0.date > $1.date }
        var builder = PDFPageBuilder(usableHeight: usableHeight)
        layout(ordered, into: &builder)
        let pages = builder.finish()
        guard !pages.isEmpty else { throw PDFExportError.noReports }
        return try render(pages: pages, fileName: fileName(for: ordered))
    }

    // MARK: - Pagination

    private func layout(_ reports: [LabReport], into builder: inout PDFPageBuilder) {
        if reports.count > 1 {
            builder.place(.cover(coverInfo(for: reports)))
        }
        for (index, report) in reports.enumerated() {
            if index > 0 || reports.count > 1 { builder.startNewPage() }
            builder.place(.reportHeader(headerInfo(for: report)))
            for group in categoryGroups(for: report) {
                let rows = group.entries.map { rowInfo(for: $0, color: group.category.color) }
                builder.placeSection(category: group.category, count: group.entries.count, rows: rows)
            }
        }
    }

    private func categoryGroups(
        for report: LabReport
    ) -> [(category: LabCategory, entries: [LabReport.Entry])] {
        let grouped = Dictionary(grouping: report.entries) { LabCategory.forCode($0.code) }
        return LabCategory.allCases.compactMap { category in
            guard let entries = grouped[category], !entries.isEmpty else { return nil }
            let sorted = entries.sorted {
                $0.resolvedName.localizedCaseInsensitiveCompare($1.resolvedName) == .orderedAscending
            }
            return (category, sorted)
        }
    }

    private func headerInfo(for report: LabReport) -> PDFReportHeaderInfo {
        let groups = categoryGroups(for: report)
        let chips = groups.map {
            PDFChip(name: $0.category.displayName, color: $0.category.color, count: $0.entries.count)
        }
        return PDFReportHeaderInfo(
            dateText: report.date.formatted(date: .long, time: .omitted),
            patient: report.patientName,
            author: report.authorName,
            valueCount: report.entries.count,
            dominant: report.dominantCategory?.color ?? .accentColor,
            chips: chips.count > 1 ? chips : []
        )
    }

    private func rowInfo(for entry: LabReport.Entry, color: Color) -> PDFRowInfo {
        let value = entry.displayValue == "-"
            ? "–"
            : "\(entry.displayValue) \(entry.unit)".trimmingCharacters(in: .whitespaces)
        return PDFRowInfo(name: entry.resolvedName, code: entry.code, value: value, color: color)
    }

    private func rangeText(earliest: Date, latest: Date) -> String {
        let start = earliest.formatted(date: .abbreviated, time: .omitted)
        let end = latest.formatted(date: .abbreviated, time: .omitted)
        return "\(start) – \(end)"
    }

    private func coverInfo(for reports: [LabReport]) -> PDFCoverInfo {
        let dates = reports.map(\.date)
        let earliest = dates.min() ?? Date.now
        let latest = dates.max() ?? Date.now
        let range = Calendar.current.isDate(earliest, inSameDayAs: latest)
            ? latest.formatted(date: .long, time: .omitted)
            : rangeText(earliest: earliest, latest: latest)
        var categories: [LabCategory: Int] = [:]
        for report in reports {
            for entry in report.entries {
                categories[LabCategory.forCode(entry.code), default: 0] += 1
            }
        }
        let colors = categories
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.key.rawValue < $1.key.rawValue }
            .prefix(3)
            .map(\.key.color)
        return PDFCoverInfo(
            title: String(localized: "Lab Reports"),
            dateRange: range,
            patient: reports.first(where: { !$0.patientName.isEmpty })?.patientName ?? "",
            reportCount: reports.count,
            valueCount: reports.reduce(0) { $0 + $1.entries.count },
            categoryCount: categories.count,
            colors: colors
        )
    }

    // MARK: - Rendering

    private func render(pages: [PDFPage], fileName: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        var mediaBox = CGRect(origin: .zero, size: Self.pageSize)
        guard let consumer = CGDataConsumer(url: url as CFURL),
              let context = CGContext(consumer: consumer, mediaBox: &mediaBox, nil) else {
            throw PDFExportError.renderFailed
        }
        let generatedOn = Date.now.formatted(date: .abbreviated, time: .omitted)
        for (index, page) in pages.enumerated() {
            let view = PDFPageView(
                page: page,
                pageNumber: index + 1,
                pageCount: pages.count,
                generatedOn: generatedOn,
                size: Self.pageSize,
                margin: Self.margin,
                footerHeight: Self.footerHeight
            )
            let renderer = ImageRenderer(content: view)
            renderer.proposedSize = ProposedViewSize(Self.pageSize)
            renderer.render { _, draw in
                context.beginPDFPage(nil)
                draw(context)
                context.endPDFPage()
            }
        }
        context.closePDF()
        return url
    }

    private func fileName(for reports: [LabReport]) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        if reports.count == 1 {
            return "LabReport-\(formatter.string(from: reports[0].date)).pdf"
        }
        return "LabReports-\(formatter.string(from: Date.now)).pdf"
    }
}

enum PDFExportError: LocalizedError {
    case noReports
    case renderFailed

    var errorDescription: String? {
        switch self {
        case .noReports:    return String(localized: "There are no reports to export.")
        case .renderFailed: return String(localized: "The PDF could not be created.")
        }
    }
}

// MARK: - Page model

/// One laid-out page: an ordered list of fixed-height blocks, top-aligned.
struct PDFPage {
    let blocks: [PDFBlock]
}

/// A single laid-out element. Each case reports a fixed `height` so the paginator
/// can pack pages without measuring SwiftUI views.
enum PDFBlock {
    case cover(PDFCoverInfo)
    case reportHeader(PDFReportHeaderInfo)
    case sectionHeader(PDFSectionInfo)
    case row(PDFRowInfo)

    var height: CGFloat {
        switch self {
        case .cover:                  return PDFMetrics.coverHeight
        case .reportHeader(let info): return info.height
        case .sectionHeader:          return PDFMetrics.sectionHeaderHeight
        case .row:                    return PDFMetrics.rowHeight
        }
    }

    /// Colors this block contributes to the page's subtle background wash.
    var washColors: [Color] {
        switch self {
        case .cover(let info):        return info.colors
        case .reportHeader(let info): return [info.dominant]
        case .sectionHeader(let info): return [info.category.color]
        case .row(let info):          return [info.color]
        }
    }
}

/// Shared layout constants so the paginator's height math and the views agree.
enum PDFMetrics {
    static let coverHeight: CGFloat = 300
    static let sectionHeaderHeight: CGFloat = 40
    static let rowHeight: CGFloat = 30

    static func reportHeaderHeight(hasChips: Bool) -> CGFloat {
        hasChips ? 116 : 88
    }
}

struct PDFCoverInfo {
    let title: String
    let dateRange: String
    let patient: String
    let reportCount: Int
    let valueCount: Int
    let categoryCount: Int
    let colors: [Color]
}

struct PDFReportHeaderInfo {
    let dateText: String
    let patient: String
    let author: String
    let valueCount: Int
    let dominant: Color
    let chips: [PDFChip]

    var height: CGFloat {
        PDFMetrics.reportHeaderHeight(hasChips: !chips.isEmpty)
    }
}

struct PDFChip {
    let name: String
    let color: Color
    let count: Int
}

struct PDFSectionInfo {
    let category: LabCategory
    let count: Int
    let continued: Bool
}

struct PDFRowInfo {
    let name: String
    let code: String
    let value: String
    let color: Color
}

// MARK: - Greedy page packer

/// Fills pages top to bottom with fixed-height blocks, breaking to a new page
/// when the next block would overflow. Section headers are kept with at least
/// their first row, and re-emitted (as "continued") when a long section spills
/// onto the following page.
private struct PDFPageBuilder {
    let usableHeight: CGFloat

    private var pages: [PDFPage] = []
    private var current: [PDFBlock] = []
    private var cursor: CGFloat = 0

    mutating func startNewPage() {
        if !current.isEmpty {
            pages.append(PDFPage(blocks: current))
            current = []
        }
        cursor = 0
    }

    mutating func place(_ block: PDFBlock) {
        if cursor + block.height > usableHeight && !current.isEmpty {
            startNewPage()
        }
        current.append(block)
        cursor += block.height
    }

    mutating func placeSection(category: LabCategory, count: Int, rows: [PDFRowInfo]) {
        let header = PDFSectionInfo(category: category, count: count, continued: false)
        let continued = PDFSectionInfo(category: category, count: count, continued: true)
        // Avoid orphaning a header: if it plus its first row won't fit, break first.
        let needed = PDFMetrics.sectionHeaderHeight + (rows.first.map { _ in PDFMetrics.rowHeight } ?? 0)
        if cursor + needed > usableHeight && !current.isEmpty {
            startNewPage()
        }
        place(.sectionHeader(header))
        for row in rows {
            if cursor + PDFMetrics.rowHeight > usableHeight {
                startNewPage()
                place(.sectionHeader(continued))
            }
            place(.row(row))
        }
    }

    mutating func finish() -> [PDFPage] {
        startNewPage()
        return pages
    }
}
