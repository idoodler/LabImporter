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
/// the chosen time window, and the precomputed stats the pages display.
struct PDFMetric: Identifiable {
    let code: String
    let name: String
    let category: LabCategory
    let unit: String
    let referenceRange: ReferenceRange?
    /// All readings, oldest → newest (used for the "latest" value).
    let allPoints: [SparkPoint]
    /// Readings within the selected time window, oldest → newest (charts/stats).
    let windowPoints: [SparkPoint]

    var id: String { code }

    var latest: SparkPoint? { allPoints.last }

    var latestStatus: RangeStatus? {
        guard let value = latest?.value, let referenceRange else { return nil }
        return referenceRange.status(for: value)
    }

    /// True when there are enough in-window readings to draw a meaningful line.
    var hasTrend: Bool { windowPoints.count >= 2 }

    var minValue: Double? { windowPoints.map(\.value).min() }
    var maxValue: Double? { windowPoints.map(\.value).max() }
    var averageValue: Double? {
        guard !windowPoints.isEmpty else { return nil }
        return windowPoints.map(\.value).reduce(0, +) / Double(windowPoints.count)
    }

    /// Net change across the window (newest − oldest in-window reading).
    var change: Double? {
        guard let first = windowPoints.first?.value, let last = windowPoints.last?.value,
              windowPoints.count >= 2 else { return nil }
        return last - first
    }
}

/// Everything a set of pages needs, assembled once up front.
struct PDFReportData {
    let patientName: String
    let dateOfBirth: Date?
    let biologicalSexRaw: Int?
    let authorName: String
    let earliestDate: Date?
    let latestDate: Date?
    let reportCount: Int
    let totalValueCount: Int
    let timeRange: PDFTimeRange
    let metrics: [PDFMetric]
    let generatedAt: Date

    /// Selected metrics whose most recent reading is outside its reference range.
    var outOfRangeCount: Int {
        metrics.filter { $0.latestStatus?.isOutOfRange == true }.count
    }

    /// Distinct categories among the selected metrics, in canonical order.
    var categories: [LabCategory] {
        let present = Set(metrics.map(\.category))
        return LabCategory.allCases.filter { present.contains($0) }
    }

    /// Categories ordered by how many selected metrics they contain — drives the
    /// cover header's gradient so it reflects the report's emphasis.
    var dominantCategories: [LabCategory] {
        var counts: [LabCategory: Int] = [:]
        for metric in metrics { counts[metric.category, default: 0] += 1 }
        return counts
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.key.rawValue < $1.key.rawValue }
            .map(\.key)
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

    /// Distills the loaded reports into the renderable model. Pure and
    /// `nonisolated` so it can run before hopping to the main actor to render.
    nonisolated static func buildData(
        reports: [LabReport],
        options: PDFExportOptions,
        patient: HealthKitService.PatientCharacteristics?,
        fallbackPatientName: String
    ) -> PDFReportData {
        let sorted = reports.sorted { $0.date < $1.date }
        let cutoff = options.timeRange.days.map { days in
            Date().addingTimeInterval(-Double(days) * 86_400)
        }

        // Gather every numeric reading per selected code across all reports.
        var pointsByCode: [String: [SparkPoint]] = [:]
        var metaByCode: [String: (name: String, unit: String)] = [:]
        for report in sorted {
            for entry in report.entries where options.selectedCodes.contains(entry.code) {
                guard let value = entry.numericValue else { continue }
                pointsByCode[entry.code, default: []].append(SparkPoint(date: report.date, value: value))
                // Keep the most recent name/unit (sorted ascending → last wins).
                metaByCode[entry.code] = (entry.resolvedName, entry.unit)
            }
        }

        let metrics: [PDFMetric] = pointsByCode.compactMap { code, points in
            guard let meta = metaByCode[code] else { return nil }
            let windowPoints = cutoff.map { cut in points.filter { $0.date >= cut } } ?? points
            return PDFMetric(
                code: code,
                name: meta.name,
                category: LabCategory.forCode(code),
                unit: meta.unit,
                referenceRange: LabMapping.referenceRange(for: code),
                allPoints: points,
                windowPoints: windowPoints
            )
        }
        .sorted { lhs, rhs in
            // Group by category (canonical order), then by name within a category.
            if lhs.category != rhs.category {
                let order = LabCategory.allCases
                return (order.firstIndex(of: lhs.category) ?? 0) < (order.firstIndex(of: rhs.category) ?? 0)
            }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }

        // Prefer the user's configured name; otherwise fall back to the name
        // stored on the most recent report.
        let patientName = fallbackPatientName.isEmpty ? (sorted.last?.patientName ?? "") : fallbackPatientName
        let totalValues = reports.reduce(0) { $0 + $1.entries.count }

        return PDFReportData(
            patientName: patientName,
            dateOfBirth: patient?.dateOfBirth,
            biologicalSexRaw: patient?.biologicalSexRaw,
            authorName: sorted.last?.authorName ?? "",
            earliestDate: sorted.first?.date,
            latestDate: sorted.last?.date,
            reportCount: reports.count,
            totalValueCount: totalValues,
            timeRange: options.timeRange,
            metrics: metrics,
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
