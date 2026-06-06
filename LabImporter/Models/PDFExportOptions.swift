import SwiftUI

// User-tunable settings for a PDF export, configured in `PDFExportView` and
// consumed by `PDFExportService`. Everything here is purely about *presentation*
// of data the app already holds — a PDF export never reaches Apple Health and is
// not a clinical document (see the disclaimer stamped on the cover page).

/// How the exported PDF is coloured. Color uses the app's clinical category
/// palette; monochrome renders a print-friendly black-and-white document where
/// out-of-range values are conveyed by weight and the High/Low badge rather than
/// hue, so the report stays legible on a grayscale printer or photocopier.
enum PDFColorMode: String, CaseIterable, Identifiable {
    case color
    case monochrome

    var id: Self { self }

    var label: LocalizedStringKey {
        switch self {
        case .color: return "Color"
        case .monochrome: return "Black & White"
        }
    }
}

/// Time window applied to the trend charts (the latest-results table always
/// reflects the most recent reading regardless of this). Mirrors the dashboard's
/// `TrendsView.TrendWindow` but kept standalone so the export module doesn't
/// depend on a view's nested type.
enum PDFTimeRange: String, CaseIterable, Identifiable {
    case month3, month6, year1, year2, all

    var id: Self { self }

    /// Visible span in days, or `nil` for "include everything".
    var days: Int? {
        switch self {
        case .month3: return 92
        case .month6: return 183
        case .year1: return 366
        case .year2: return 731
        case .all: return nil
        }
    }

    var label: LocalizedStringKey {
        switch self {
        case .month3: return "3M"
        case .month6: return "6M"
        case .year1: return "1Y"
        case .year2: return "2Y"
        case .all: return "All"
        }
    }

    /// Short label as a plain `String`, for rendering inside the PDF (where a
    /// `LocalizedStringKey` can't be drawn directly).
    var shortLabel: String {
        switch self {
        case .month3: return String(localized: "3M")
        case .month6: return String(localized: "6M")
        case .year1: return String(localized: "1Y")
        case .year2: return String(localized: "2Y")
        case .all: return String(localized: "All")
        }
    }

    /// Localized long form used in the PDF header (e.g. "Last 12 months").
    var longLabel: String {
        switch self {
        case .month3: return String(localized: "Last 3 months")
        case .month6: return String(localized: "Last 6 months")
        case .year1: return String(localized: "Last 12 months")
        case .year2: return String(localized: "Last 2 years")
        case .all: return String(localized: "All time")
        }
    }
}

/// Selectable paper size for the export. All options are at least A4-wide, so
/// the latest-results table's fixed columns hold across formats; only the page
/// height (and therefore how much fits per page) varies.
enum PDFPageFormat: String, CaseIterable, Identifiable {
    case isoA4, usLetter, legal

    var id: Self { self }

    /// Page dimensions in points (72 dpi, 1 pt == 1/72").
    var size: CGSize {
        switch self {
        case .isoA4:    return CGSize(width: 595.28, height: 841.89)  // 210 × 297 mm
        case .usLetter: return CGSize(width: 612, height: 792)        // 8.5 × 11 in
        case .legal:    return CGSize(width: 612, height: 1008)       // 8.5 × 14 in
        }
    }

    /// Shown verbatim — A4 / US Letter / Legal are standard international paper
    /// names, not translated.
    var label: String {
        switch self {
        case .isoA4:    return "A4"
        case .usLetter: return "US Letter"
        case .legal:    return "Legal"
        }
    }

    /// A sensible default for the current device. iOS exposes no "paper size"
    /// setting, so this keys off the device's **Region** (Settings → General →
    /// Language & Region): the handful of regions that use US Letter paper get
    /// Letter, everywhere else gets A4. The user can still override in the sheet.
    static var deviceDefault: PDFPageFormat {
        // Regions where US Letter (or its near-identical local equivalent) is the
        // standard office paper size rather than ISO A4.
        let letterRegions: Set<String> = [
            "US", "CA", "MX", "PH", "CL", "CO", "CR", "GT", "NI", "PA", "DO", "SV", "VE", "PR"
        ]
        let region = Locale.current.region?.identifier ?? ""
        return letterRegions.contains(region) ? .usLetter : .isoA4
    }
}

/// The set of choices that fully describe one export.
struct PDFExportOptions {
    /// LOINC codes the user chose to include.
    var selectedCodes: Set<String>
    var timeRange: PDFTimeRange = .year1
    var colorMode: PDFColorMode = .color
    var pageFormat: PDFPageFormat = .deviceDefault
    /// Cover/summary page with patient details and headline stats.
    var includeSummary = true
    /// Table of the most recent value for every selected metric.
    var includeLatestResults = true
    /// One chart per selected metric that has at least two readings in range.
    var includeTrends = true
}

/// Resolves the export's colours from its `PDFColorMode`. Deliberately uses
/// solid colours only — `ImageRenderer` (which rasterises the SwiftUI pages into
/// the PDF) does not reproduce `.ultraThinMaterial`/`.glassEffect`, so the
/// on-screen card styling can't be reused for print.
struct PDFTheme {
    let mode: PDFColorMode

    var isColor: Bool { mode == .color }

    // MARK: Neutrals

    var pageBackground: Color { .white }
    var textPrimary: Color { Color(white: 0.12) }
    var textSecondary: Color { Color(white: 0.45) }
    var textTertiary: Color { Color(white: 0.6) }
    var hairline: Color { Color(white: 0.86) }
    /// Card fills and zebra striping are dropped in monochrome to save ink/toner
    /// — structure is carried by hairline borders instead.
    var cardBackground: Color { isColor ? Color(white: 0.975) : .white }
    var cardStroke: Color { Color(white: 0.82) }
    /// Subtle zebra fill for alternating table rows; none in monochrome.
    var rowAlternate: Color { isColor ? Color(white: 0.965) : .clear }

    // MARK: Category / accent

    /// Accent for a clinical category — its palette colour in colour mode, a
    /// neutral dark gray in monochrome.
    func color(for category: LabCategory) -> Color {
        isColor ? category.color : Color(white: 0.32)
    }

    /// Colour for a value given its range status. In colour mode out-of-range
    /// values take the status tint; in monochrome they go solid black (the
    /// High/Low badge and bold weight carry the meaning).
    func valueColor(for status: RangeStatus?) -> Color {
        guard let status, status.isOutOfRange else { return textPrimary }
        return isColor ? status.color : .black
    }

    /// Colour for a status badge capsule.
    func statusColor(_ status: RangeStatus) -> Color {
        isColor ? status.color : Color(white: 0.2)
    }

    var chartGrid: Color { Color(white: 0.9) }

    /// Gradient painted behind the cover header. Built from up to three of the
    /// report's dominant category colours (colour mode), or graphite in mono.
    func headerGradient(from categories: [LabCategory]) -> [Color] {
        guard isColor else {
            return [Color(white: 0.22), Color(white: 0.40)]
        }
        let colors = categories.prefix(3).map { $0.color }
        switch colors.count {
        case 0: return [Color.accentColor, Color.accentColor.opacity(0.7)]
        case 1: return [colors[0], colors[0].opacity(0.6)]
        default: return colors
        }
    }
}

/// Fixed page geometry for the export. A4 portrait at 72 dpi (1 pt == 1/72").
/// A4 is the most internationally portable choice given the app ships across
/// many European locales.
enum PDFLayout {
    static let margin: CGFloat = 42

    /// Usable text width inside the page margins for a given paper size.
    static func contentWidth(for size: CGSize) -> CGFloat { size.width - margin * 2 }

    // Pagination capacities are derived from the page height so each paper format
    // uses its available space (Legal fits more rows than A4, US Letter slightly
    // fewer). Everything is sized for the worst case — a two-line wrapped value
    // name, or a full-height chart card — so content never clips. The first page
    // of a section holds fewer items because it also carries the section title.
    private static let footer: CGFloat = 28
    private static let runningHeader: CGFloat = 30
    private static let sectionTitle: CGFloat = 56
    private static let rowUnit: CGFloat = 50
    private static let chartUnit: CGFloat = 212

    private static func usableHeight(_ size: CGSize) -> CGFloat {
        size.height - margin * 2 - footer - runningHeader
    }

    /// Latest-results rows per page: `(first page, continuation pages)`.
    static func tableRows(for size: CGSize) -> (first: Int, rest: Int) {
        let height = usableHeight(size)
        return (max(1, Int((height - sectionTitle) / rowUnit)), max(1, Int(height / rowUnit)))
    }

    /// Trend charts per page: `(first page, continuation pages)`.
    static func charts(for size: CGSize) -> (first: Int, rest: Int) {
        let height = usableHeight(size)
        return (max(1, Int((height - sectionTitle) / chartUnit)), max(1, Int(height / chartUnit)))
    }
}
