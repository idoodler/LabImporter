import SwiftUI

// Infrastructure shared by the PDF page views: the page composer that paginates
// content, the page scaffold (fixed A4 geometry + header/footer), and small
// reusable pieces. These are *print* views — solid fills only (no
// materials/glass, which `ImageRenderer` drops) and a forced light colour scheme
// — so the output renders identically regardless of the device's appearance.

// MARK: - Composer

/// Turns a `PDFReportData` + options into an ordered list of page views,
/// handling pagination of the latest-results table and the trend charts.
@MainActor
struct PDFPageComposer {
    let data: PDFReportData
    let options: PDFExportOptions
    let theme: PDFTheme

    private var size: CGSize { options.pageFormat.size }

    func pages() -> [AnyView] {
        var blocks: [Block] = []
        if options.includeSummary { blocks.append(.cover) }
        if options.includeLatestResults { blocks += latestBlocks() }
        if options.includeTrends { blocks += trendBlocks() }

        let total = blocks.count
        return blocks.enumerated().map { index, block in
            view(for: block, pageNumber: index + 1, total: total)
        }
    }

    private enum Block {
        case cover
        case latest(rows: [LatestRow], showTitle: Bool)
        case trends(metrics: [PDFMetric], showTitle: Bool)
    }

    private func latestBlocks() -> [Block] {
        var rows: [LatestRow] = []
        let grouped = Dictionary(grouping: data.metrics, by: \.category)
        for category in data.categories {
            guard let metrics = grouped[category] else { continue }
            rows.append(.category(category, count: metrics.count))
            rows.append(contentsOf: metrics.map { LatestRow.metric($0) })
        }
        guard !rows.isEmpty else { return [] }
        let capacity = PDFLayout.tableRows(for: size)
        return paginate(rows, first: capacity.first, rest: capacity.rest)
            .map { Block.latest(rows: $0.items, showTitle: $0.isFirst) }
    }

    private func trendBlocks() -> [Block] {
        let trendMetrics = data.metrics.filter(\.hasTrend)
        guard !trendMetrics.isEmpty else { return [] }
        let capacity = PDFLayout.charts(for: size)
        return paginate(trendMetrics, first: capacity.first, rest: capacity.rest)
            .map { Block.trends(metrics: $0.items, showTitle: $0.isFirst) }
    }

    /// Splits items across pages: the first page holds `first` items (it carries
    /// the section title), later pages hold `rest`.
    private func paginate<T>(_ items: [T], first: Int, rest: Int) -> [(items: [T], isFirst: Bool)] {
        var result: [(items: [T], isFirst: Bool)] = []
        var index = 0
        var isFirst = true
        while index < items.count {
            let size = isFirst ? first : rest
            let end = Swift.min(index + size, items.count)
            result.append((Array(items[index..<end]), isFirst))
            index = end
            isFirst = false
        }
        return result
    }

    private func view(for block: Block, pageNumber: Int, total: Int) -> AnyView {
        switch block {
        case .cover:
            return AnyView(PDFCoverPage(data: data, theme: theme, pageSize: size,
                                        pageNumber: pageNumber, totalPages: total))
        case let .latest(rows, showTitle):
            return AnyView(PDFLatestResultsPage(rows: rows, showTitle: showTitle, patientName: data.patientName,
                                                theme: theme, pageSize: size,
                                                pageNumber: pageNumber, totalPages: total))
        case let .trends(metrics, showTitle):
            return AnyView(PDFTrendsPage(metrics: metrics, showTitle: showTitle, range: data.timeRange,
                                         patientName: data.patientName, theme: theme, pageSize: size,
                                         pageNumber: pageNumber, totalPages: total))
        }
    }
}

/// A row in the latest-results table: either a category heading or a metric.
enum LatestRow: Identifiable {
    case category(LabCategory, count: Int)
    case metric(PDFMetric)

    var id: String {
        switch self {
        case let .category(category, _): return "cat-\(category.rawValue)"
        case let .metric(metric): return "val-\(metric.code)"
        }
    }
}

// MARK: - Page scaffold

/// Shared page chrome: fixed page geometry, white background, optional running
/// header, and a footer with the page number. Content fills the space between.
struct PDFPageScaffold<Content: View>: View {
    let theme: PDFTheme
    let pageSize: CGSize
    let pageNumber: Int
    let totalPages: Int
    var runningTitle: String?
    var patientName: String = ""
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let runningTitle {
                runningHeader(runningTitle)
                    .padding(.bottom, 16)
            }
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            footer
        }
        .padding(PDFLayout.margin)
        .frame(width: pageSize.width, height: pageSize.height, alignment: .topLeading)
        .background(theme.pageBackground)
        .environment(\.colorScheme, .light)
    }

    private func runningHeader(_ title: String) -> some View {
        VStack(spacing: 8) {
            HStack {
                Text(title)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(theme.textSecondary)
                Spacer()
                if !patientName.isEmpty {
                    Text(patientName)
                        .font(.system(size: 11))
                        .foregroundStyle(theme.textTertiary)
                }
            }
            Rectangle().fill(theme.hairline).frame(height: 0.5)
        }
    }

    private var footer: some View {
        VStack(spacing: 6) {
            Rectangle().fill(theme.hairline).frame(height: 0.5)
            HStack {
                Text("LabImporter")
                    .font(.system(size: 9, weight: .medium))
                    .foregroundStyle(theme.textTertiary)
                Spacer()
                Text("Page \(pageNumber) of \(totalPages)")
                    .font(.system(size: 9))
                    .foregroundStyle(theme.textTertiary)
            }
        }
        .padding(.top, 10)
    }
}

// MARK: - Shared small views

struct PDFSectionHeader: View {
    let title: String
    var subtitle: String?
    let theme: PDFTheme

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(theme.textPrimary)
            if let subtitle {
                Text(subtitle)
                    .font(.system(size: 11))
                    .foregroundStyle(theme.textSecondary)
            }
        }
    }
}

struct PDFStatusBadge: View {
    let status: RangeStatus
    let theme: PDFTheme

    var body: some View {
        HStack(spacing: 2) {
            Image(systemName: status.symbolName)
                .font(.system(size: 7, weight: .bold))
            Text(status.label)
                .font(.system(size: 8, weight: .semibold))
        }
        .foregroundStyle(theme.statusColor(status))
        .padding(.horizontal, 5)
        .padding(.vertical, 2)
        // Colour mode: a soft tinted capsule. Monochrome: no fill (saves toner),
        // just an outline so the badge still reads.
        .background(theme.statusColor(status).opacity(theme.isColor ? 0.14 : 0), in: Capsule())
        .overlay {
            if !theme.isColor {
                Capsule().stroke(theme.statusColor(status), lineWidth: 0.5)
            }
        }
    }
}

// MARK: - Helpers

enum PDFFormat {
    /// Whole numbers without decimals, otherwise up to four significant figures —
    /// matching the value formatting used across the app's trend screens.
    static func value(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0
            ? String(format: "%.0f", value)
            : String(format: "%.4g", value)
    }
}

/// A minimal wrapping HStack for the cover legend — `ImageRenderer` supports the
/// `Layout` protocol, so this lays category chips out across lines without
/// relying on a fixed column count.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let maxWidth = proposal.width ?? PDFLayout.contentWidth(for: PDFPageFormat.isoA4.size)
        var rows: [[CGSize]] = [[]]
        var lineWidth: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if lineWidth + size.width > maxWidth, !rows[rows.count - 1].isEmpty {
                rows.append([])
                lineWidth = 0
            }
            rows[rows.count - 1].append(size)
            lineWidth += size.width + spacing
        }
        let height = rows.reduce(CGFloat(0)) { partial, row in
            partial + (row.map(\.height).max() ?? 0) + lineSpacing
        } - (rows.isEmpty ? 0 : lineSpacing)
        return CGSize(width: maxWidth, height: max(height, 0))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        var posX = bounds.minX
        var posY = bounds.minY
        var lineHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if posX + size.width > bounds.maxX, posX > bounds.minX {
                posX = bounds.minX
                posY += lineHeight + lineSpacing
                lineHeight = 0
            }
            subview.place(at: CGPoint(x: posX, y: posY), proposal: ProposedViewSize(size))
            posX += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}
