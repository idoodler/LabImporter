import SwiftUI
import Charts

// The trend-chart pages of the export: one card per selected metric that has at
// least two readings inside the chosen time window, each with a static chart,
// reference-range guides, and a min/max/avg/change summary.

struct PDFTrendsPage: View {
    let metrics: [PDFMetric]
    let showTitle: Bool
    let range: PDFTimeRange
    let patientName: String
    let theme: PDFTheme
    let pageSize: CGSize
    let pageNumber: Int
    let totalPages: Int

    var body: some View {
        PDFPageScaffold(theme: theme, pageSize: pageSize, pageNumber: pageNumber, totalPages: totalPages,
                        runningTitle: String(localized: "Lab Report"), patientName: patientName) {
            VStack(alignment: .leading, spacing: 14) {
                if showTitle {
                    PDFSectionHeader(title: String(localized: "Trends"),
                                     subtitle: range.longLabel, theme: theme)
                }
                ForEach(metrics) { metric in
                    PDFTrendCard(metric: metric, theme: theme)
                }
                Spacer(minLength: 0)
            }
        }
    }
}

struct PDFTrendCard: View {
    let metric: PDFMetric
    let theme: PDFTheme

    private var accent: Color { theme.color(for: metric.category) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            chart
            statsRow
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.cardBackground, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.cardStroke, lineWidth: 0.5))
    }

    private var header: some View {
        HStack(spacing: 8) {
            Circle().fill(accent).frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: 1) {
                Text(metric.name)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                Text(metric.code)
                    .font(.system(size: 8))
                    .foregroundStyle(theme.textTertiary)
                    .textCase(.uppercase)
            }
            Spacer(minLength: 8)
            if let status = metric.latestStatus, status.isOutOfRange {
                PDFStatusBadge(status: status, theme: theme)
            }
            if let latest = metric.latest {
                HStack(alignment: .firstTextBaseline, spacing: 3) {
                    Text(PDFFormat.value(latest.value))
                        .font(.system(size: 14, weight: .bold).monospacedDigit())
                        .foregroundStyle(theme.valueColor(for: metric.latestStatus))
                    if !metric.unit.isEmpty {
                        Text(metric.unit)
                            .font(.system(size: 9))
                            .foregroundStyle(theme.textSecondary)
                    }
                }
            }
        }
    }

    private var chart: some View {
        Chart {
            if let range = metric.referenceRange {
                if let low = range.low {
                    RuleMark(y: .value(String(localized: "Low"), low))
                        .foregroundStyle(theme.statusColor(.low).opacity(0.5))
                        .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [4, 3]))
                }
                if let high = range.high {
                    RuleMark(y: .value(String(localized: "High"), high))
                        .foregroundStyle(theme.statusColor(.high).opacity(0.5))
                        .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [4, 3]))
                }
            }
            ForEach(metric.windowPoints) { point in
                // The area fill is colour-mode only — in monochrome it would just
                // be a gray wash that wastes toner, so the line carries the trend.
                if theme.isColor {
                    AreaMark(x: .value(String(localized: "Date"), point.date),
                             y: .value(metric.unit, point.value))
                        .foregroundStyle(LinearGradient(colors: [accent.opacity(0.22), accent.opacity(0.03)],
                                                        startPoint: .top, endPoint: .bottom))
                }
                LineMark(x: .value(String(localized: "Date"), point.date),
                         y: .value(metric.unit, point.value))
                    .foregroundStyle(accent)
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
                PointMark(x: .value(String(localized: "Date"), point.date),
                          y: .value(metric.unit, point.value))
                    .foregroundStyle(pointColor(point.value))
                    .symbolSize(16)
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                AxisGridLine().foregroundStyle(theme.chartGrid)
                AxisValueLabel(format: .dateTime.month(.abbreviated).year(.twoDigits))
                    .font(.system(size: 7))
                    .foregroundStyle(theme.textSecondary)
            }
        }
        .chartYAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                AxisGridLine().foregroundStyle(theme.chartGrid)
                AxisValueLabel()
                    .font(.system(size: 7))
                    .foregroundStyle(theme.textSecondary)
            }
        }
        .frame(height: 118)
    }

    private func pointColor(_ value: Double) -> Color {
        if let status = metric.referenceRange?.status(for: value), status.isOutOfRange {
            return theme.statusColor(status)
        }
        return accent
    }

    private var statsRow: some View {
        HStack(spacing: 0) {
            stat(String(localized: "Min"), metric.minValue)
            divider
            stat(String(localized: "Max"), metric.maxValue)
            divider
            stat(String(localized: "Avg"), metric.averageValue)
            divider
            changeStat
        }
    }

    private var divider: some View {
        Rectangle().fill(theme.hairline).frame(width: 0.5, height: 22)
    }

    private func stat(_ label: String, _ value: Double?) -> some View {
        VStack(spacing: 2) {
            Text(value.map { PDFFormat.value($0) } ?? "—")
                .font(.system(size: 11, weight: .semibold).monospacedDigit())
                .foregroundStyle(theme.textPrimary)
            Text(label)
                .font(.system(size: 8))
                .foregroundStyle(theme.textTertiary)
        }
        .frame(maxWidth: .infinity)
    }

    private var changeStat: some View {
        VStack(spacing: 2) {
            HStack(spacing: 2) {
                if let change = metric.change, change != 0 {
                    Image(systemName: change > 0 ? "arrow.up.right" : "arrow.down.right")
                        .font(.system(size: 8, weight: .bold))
                }
                Text(changeText)
                    .font(.system(size: 11, weight: .semibold).monospacedDigit())
            }
            .foregroundStyle(theme.textPrimary)
            Text("Change")
                .font(.system(size: 8))
                .foregroundStyle(theme.textTertiary)
        }
        .frame(maxWidth: .infinity)
    }

    private var changeText: String {
        guard let change = metric.change else { return "—" }
        let prefix = change > 0 ? "+" : ""
        return prefix + PDFFormat.value(change)
    }
}
