import LabImporterCore
import SwiftUI

// Builds an information-rich PDF from the reports already loaded from Apple
// Health. The flow is:
//
//   reports + options  →  PDFReportData (pure computation, off the main actor)
//                      →  [page views]   (PDFPageComposer, main actor)
//                      →  PDF file       (ImageRenderer → CGPDFContext)
//
// Like `CDAExportService` this is a thin, stateless service; unlike it, the
// render step must run on the main actor because it rasterises SwiftUI views.

// MARK: - Computed model

/// One metric's full picture for the export: its identity, the readings inside
/// the chosen time window, and the precomputed stats the pages display — all
/// computed by the shared core (LabImporterCore `PdfMetric`), as on Android.
struct PDFMetric: Identifiable {
    let shared: LabImporterCore.PdfMetric
    /// All readings, oldest → newest (used for the "latest" value).
    let allPoints: [SparkPoint]
    /// Readings within the selected time window, oldest → newest (charts/stats).
    let windowPoints: [SparkPoint]

    init(shared metric: LabImporterCore.PdfMetric) {
        shared = metric
        allPoints = metric.allPoints.map { SparkPoint(date: Date(shared: $0.date), value: $0.value) }
        windowPoints = metric.windowPoints.map { SparkPoint(date: Date(shared: $0.date), value: $0.value) }
    }

    var id: String { code }
    var code: String { shared.code }
    var name: String { shared.name }
    var category: LabCategory { LabCategory(sharedName: shared.category.name) }
    var unit: String { shared.unit }
    var referenceRange: ReferenceRange? { shared.referenceRange.map(ReferenceRange.init(shared:)) }
    var latest: SparkPoint? { allPoints.last }
    var latestStatus: RangeStatus? { shared.latestStatus.map(RangeStatus.init(shared:)) }

    /// True when there are enough in-window readings to draw a meaningful line.
    var hasTrend: Bool { shared.hasTrend }
    var minValue: Double? { shared.minValue?.doubleValue }
    var maxValue: Double? { shared.maxValue?.doubleValue }
    var averageValue: Double? { shared.averageValue?.doubleValue }
    /// Net change across the window (newest − oldest in-window reading).
    var change: Double? { shared.change?.doubleValue }
}

/// Everything a set of pages needs, assembled once up front (by the shared
/// core, plus the Health characteristics only iOS reads).
struct PDFReportData {
    let shared: LabImporterCore.PdfReportData
    let dateOfBirth: Date?
    let biologicalSexRaw: Int?
    let timeRange: PDFTimeRange
    let metrics: [PDFMetric]
    let generatedAt: Date

    var patientName: String { shared.patientName }
    var authorName: String { shared.authorName }
    var earliestDate: Date? { shared.earliestDate.map(Date.init(shared:)) }
    var latestDate: Date? { shared.latestDate.map(Date.init(shared:)) }
    var reportCount: Int { Int(shared.reportCount) }
    var totalValueCount: Int { Int(shared.totalValueCount) }

    /// Selected metrics whose most recent reading is outside its reference range.
    var outOfRangeCount: Int { Int(shared.outOfRangeCount) }

    /// Distinct categories among the selected metrics, in canonical order.
    var categories: [LabCategory] { shared.categories.map { LabCategory(sharedName: $0.name) } }

    /// Categories ordered by how many selected metrics they contain — drives the
    /// cover header's gradient so it reflects the report's emphasis.
    var dominantCategories: [LabCategory] { shared.dominantCategories.map { LabCategory(sharedName: $0.name) } }

    /// The Swift metric for a shared one (pages are planned by the shared core).
    func metric(_ shared: LabImporterCore.PdfMetric) -> PDFMetric? {
        metrics.first { $0.code == shared.code }
    }
}

// MARK: - Service

struct PDFExportService {

    enum PDFExportError: LocalizedError {
        case noContent
        case renderingFailed

        var errorDescription: String? {
            switch self {
            case .noContent:
                return String(localized: "Select at least one value to include in the PDF.")
            case .renderingFailed:
                return String(localized: "The PDF could not be generated. Please try again.")
            }
        }
    }

    // MARK: Data assembly

    /// Distills the loaded reports into the renderable model — the shared
    /// core's `PdfReport.build`, identical on Android. Pure and `nonisolated`
    /// so it can run before hopping to the main actor to render.
    nonisolated static func buildData(
        reports: [LabReport],
        options: PDFExportOptions,
        patient: HealthKitService.PatientCharacteristics?,
        fallbackPatientName: String
    ) -> PDFReportData {
        SharedCore.start()
        let shared = PdfReport.shared.build(
            reports: reports.map(\.shared),
            selectedCodes: options.selectedCodes,
            timeRange: options.timeRange.shared,
            configuredPatientName: fallbackPatientName,
            today: Date.now.sharedDate
        )
        return PDFReportData(
            shared: shared,
            dateOfBirth: patient?.dateOfBirth,
            biologicalSexRaw: patient?.biologicalSexRaw,
            timeRange: options.timeRange,
            metrics: shared.metrics.map(PDFMetric.init(shared:)),
            generatedAt: Date()
        )
    }

    // MARK: Rendering

    /// Renders the pages to a temporary PDF file and returns its URL for sharing.
    /// Runs on the main actor because `ImageRenderer` rasterises SwiftUI views.
    @MainActor
    func export(data: PDFReportData, options: PDFExportOptions) throws -> URL {
        guard !data.metrics.isEmpty else { throw PDFExportError.noContent }

        let theme = PDFTheme(mode: options.colorMode)
        let pages = PDFPageComposer(data: data, options: options, theme: theme).pages()
        guard !pages.isEmpty else { throw PDFExportError.noContent }

        let size = options.pageFormat.size
        var mediaBox = CGRect(origin: .zero, size: size)
        let url = Self.outputURL(for: data)

        guard let consumer = CGDataConsumer(url: url as CFURL),
              let context = CGContext(consumer: consumer, mediaBox: &mediaBox, nil) else {
            throw PDFExportError.renderingFailed
        }

        for page in pages {
            let renderer = ImageRenderer(content: page)
            renderer.proposedSize = ProposedViewSize(size)
            var rendered = false
            renderer.render { _, draw in
                context.beginPDFPage(nil)
                draw(context)
                context.endPDFPage()
                rendered = true
            }
            guard rendered else {
                context.closePDF()
                throw PDFExportError.renderingFailed
            }
        }
        context.closePDF()
        return url
    }

    private static func outputURL(for data: PDFReportData) -> URL {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        let datePart = data.latestDate.map { formatter.string(from: $0) } ?? formatter.string(from: data.generatedAt)
        let name = "LabReport-\(datePart).pdf"
        return FileManager.default.temporaryDirectory.appendingPathComponent(name)
    }
}
