import LabImporterCore
import SwiftUI
import Charts

// MARK: - Supporting types

struct SparkPoint: Identifiable {
    let id = UUID()
    let date: Date
    let value: Double
}

/// One result that isn't a number ("Negativ", "1:160") of a metric — listed, never charted.
struct ResultPoint: Identifiable {
    let id = UUID()
    let date: Date
    let text: String
    let unit: String

    /// The result with its unit, as a row shows it ("<0.5 mg/L").
    var formatted: String { unit.isEmpty ? text : "\(text) \(unit)" }
}

struct MetricData: Identifiable {
    var id: String { entry.code }
    let entry: LabReport.Entry
    let history: [SparkPoint]
    /// For a metric whose readings are all results that aren't numbers: every
    /// one of them, oldest first (shared `Metric.results`). Empty otherwise.
    var results: [ResultPoint] = []

    /// Readings are results that aren't numbers: no sparkline, trend or range.
    var isQualitative: Bool { entry.numericValue == nil }
}

extension MetricData {
    init(shared metric: LabImporterCore.Metric) {
        self.init(
            entry: LabReport.Entry(shared: metric.entry),
            history: metric.history.map { SparkPoint(date: Date(shared: $0.date), value: $0.value) },
            results: metric.results.map { ResultPoint(date: Date(shared: $0.date), text: $0.text, unit: $0.unit) }
        )
    }
}

/// Direction of a metric's latest reading relative to the previous one, shared
/// by the grid's `MetricCard` and the single-metric `HeroMetricCard`. `nil`
/// when there's no prior value to compare against.
enum MetricTrend {
    case rising, falling, steady

    /// Shared rule (LabImporterCore `MetricTrend`): latest vs previous reading.
    init?(history: [SparkPoint]) {
        let points = history.map { LabImporterCore.MetricPoint(date: $0.date.sharedDate, value: $0.value, unit: "", reportId: "") }
        guard let trend = LabImporterCore.MetricTrend.companion.of(points: points) else { return nil }
        switch trend {
        case .rising: self = .rising
        case .falling: self = .falling
        default: self = .steady
        }
    }

    var symbol: String {
        switch self {
        case .rising: return "arrow.up.right"
        case .falling: return "arrow.down.right"
        case .steady: return "arrow.right"
        }
    }

    var accessibilityLabel: Text {
        switch self {
        case .rising: return Text("Trending up")
        case .falling: return Text("Trending down")
        case .steady: return Text("No change")
        }
    }
}

// MARK: - MetricCard

struct MetricCard: View {
    let metric: MetricData
    let isPinned: Bool

    /// The trend arrow speaks, so it grows with the text beside it.
    @ScaledMetric(relativeTo: .footnote) private var dockSize: CGFloat = 18

    private var categoryColor: Color {
        LabCategory.forCode(metric.entry.code).color
    }

    /// The user's reference range for this metric's code, if set.
    private var referenceRange: ReferenceRange? {
        LabMapping.referenceRange(for: metric.entry.code)
    }

    /// Out-of-range status of the latest reading against the user's reference
    /// range for this code, or `nil` when there's no value or no range.
    private var rangeStatus: RangeStatus? {
        LabMapping.rangeStatus(for: metric.entry.numericValue, code: metric.entry.code)
    }

    /// The value's colour: tinted by its out-of-range status, else primary.
    private var valueForeground: Color {
        if let rangeStatus, rangeStatus.isOutOfRange { return rangeStatus.color }
        return .primary
    }

    private var trend: MetricTrend? { MetricTrend(history: metric.history) }

    /// The colored category "dock" at the card's top-left. When a trend is
    /// available it doubles as the trend indicator and hosts a directional
    /// arrow; otherwise it is a simple category-color dot filling the same
    /// circle. Both variants share an 18×18 footprint so the title alignment —
    /// and the dot/arrow size — stays consistent across cards.
    @ViewBuilder
    private var dock: some View {
        if let trend {
            Image(systemName: trend.symbol)
                .font(.system(size: dockSize / 2, weight: .heavy))
                .foregroundStyle(.white)
                .frame(width: dockSize, height: dockSize)
                .background(categoryColor.gradient, in: Circle())
                .accessibilityLabel(trend.accessibilityLabel)
        } else {
            Circle()
                .fill(categoryColor.gradient)
                .frame(width: dockSize, height: dockSize)
                .accessibilityHidden(true)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 7) {
                dock
                Text(metric.entry.resolvedName)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 1)
                Spacer(minLength: 0)
                if isPinned {
                    Image(systemName: "pin.fill")
                        .font(.caption2)
                        .foregroundStyle(.pinnedMark)
                        .accessibilityLabel("Pinned")
                }
            }

            // A result can be long ("Non-reactive") and the text large: it moves down a line, whole.
            FittingRow(spacing: 3) {
                Text(metric.entry.displayValue)
                    .font(.title2.bold())
                    .foregroundStyle(valueForeground)
                if !metric.entry.unit.isEmpty {
                    Text(metric.entry.unit)
                        .font(.caption)
                        .foregroundStyle(.textSecondary)
                }
                if let rangeStatus {
                    RangeStatusBadge(status: rangeStatus,
                                     range: referenceRange,
                                     unit: metric.entry.unit)
                }
            }

            if metric.isQualitative {
                RecentResultsList(results: metric.results, limit: 3)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            } else if metric.history.count > 1 {
                MetricSparklineChart(history: metric.history, categoryColor: categoryColor, referenceRange: referenceRange)
                    // Grow to absorb the card's remaining vertical space instead of
                    // leaving a fixed-height chart with blank padding beneath it.
                    // `minHeight` keeps a sensible floor when a card's row is short.
                    .frame(minHeight: 44, maxHeight: .infinity)
            } else {
                Spacer(minLength: 44)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 158, alignment: .topLeading)
        .cardSurface()
    }
}

// MARK: - RecentResultsList

/// The latest results that aren't numbers, newest first — what a qualitative
/// metric's card shows in place of a sparkline: there's nothing to plot.
struct RecentResultsList: View {
    let results: [ResultPoint]
    let limit: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            ForEach(results.suffix(limit).reversed()) { result in
                FittingRow(alignment: .center, spacing: 6) {
                    Text(result.date.formatted(date: .abbreviated, time: .omitted))
                        .foregroundStyle(.textSecondary)
                    Text(result.formatted)
                        .fontWeight(.medium)
                }
                .font(.caption2)
                .accessibilityElement(children: .combine)
            }
        }
    }
}

// MARK: - MetricSparklineChart

/// The line+area+point sparkline shared by the grid's `MetricCard` and the
/// dashboard's single-metric hero card. Callers control size via `.frame`.
struct MetricSparklineChart: View {
    let history: [SparkPoint]
    let categoryColor: Color
    let referenceRange: ReferenceRange?

    var body: some View {
        if let domain = ChartDomain(dates: history.map(\.date), values: history.map(\.value), range: referenceRange) {
            chart
                .chartXScale(domain: domain.xDomain)
                .chartYScale(domain: domain.yDomain)
        }
    }

    private var chart: some View {
        Chart {
            ForEach(history) { point in
                LineMark(
                    x: .value("Date", point.date),
                    y: .value("Value", point.value)
                )
                .foregroundStyle(categoryColor.opacity(0.85))

                AreaMark(
                    x: .value("Date", point.date),
                    y: .value("Value", point.value)
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [categoryColor.opacity(0.3), categoryColor.opacity(0.05)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )

                PointMark(
                    x: .value("Date", point.date),
                    y: .value("Value", point.value)
                )
                .foregroundStyle(categoryColor)
                .symbolSize(20)
            }

            // Faint dashed guides at the reference bounds. The shared scale has
            // room for them, so the marks stay inside the plot (never spilling past
            // the card) and the data simply shares the height with them.
            if let low = referenceRange?.low {
                RuleMark(y: .value("Low", low))
                    .foregroundStyle(RangeStatus.low.color.opacity(0.4))
                    .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [3, 2]))
            }
            if let high = referenceRange?.high {
                RuleMark(y: .value("High", high))
                    .foregroundStyle(RangeStatus.high.color.opacity(0.4))
                    .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [3, 2]))
            }
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        // Decorative here: the card's value text, range badge, and trend arrow
        // already say everything this sparkline shows, as text. Without this,
        // Swift Charts' default per-mark accessibility would read every point
        // three times over (once per Line/Area/Point mark) for every card in
        // the dashboard grid.
        .accessibilityHidden(true)
    }
}

// MARK: - Previews

#if DEBUG
private enum Fixture {
    static func day(_ year: Int, _ month: Int, _ day: Int) -> Date {
        Calendar.current.date(from: DateComponents(year: year, month: month, day: day)) ?? .now
    }
}

private func sampleCards() -> [MetricData] {
    let history = [SparkPoint(date: Fixture.day(2024, 1, 10), value: 32), SparkPoint(date: Fixture.day(2026, 5, 28), value: 35)]
    return LabReport.sample.entries.prefix(4).map { MetricData(entry: $0, history: history) }
}

#Preview("Cards") {
    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
        ForEach(sampleCards()) { metric in
            MetricCard(metric: metric, isPinned: false)
        }
    }
    .padding()
}

#Preview("Cards · Dark, Pinned") {
    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
        ForEach(sampleCards()) { metric in
            MetricCard(metric: metric, isPinned: true)
        }
    }
    .padding()
    .preferredColorScheme(.dark)
}

#Preview("Results That Aren't Numbers") {
    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
        ForEach(MetricData.sampleQualitative) { metric in
            MetricCard(metric: metric, isPinned: false)
        }
    }
    .padding()
}

#Preview("Results That Aren't Numbers · Dark") {
    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
        ForEach(MetricData.sampleQualitative) { metric in
            MetricCard(metric: metric, isPinned: true)
        }
    }
    .padding()
    .preferredColorScheme(.dark)
}

#Preview("Sparkline") {
    MetricSparklineChart(
        history: [
            SparkPoint(date: Date(timeIntervalSince1970: 1_700_000_000), value: 6.5),
            SparkPoint(date: Date(timeIntervalSince1970: 1_710_000_000), value: 6.3),
            SparkPoint(date: Date(timeIntervalSince1970: 1_720_000_000), value: 6.2)
        ],
        categoryColor: LabCategory.glycemic.color,
        referenceRange: ReferenceRange(low: 4.0, high: 5.6)
    )
    .frame(height: 120)
    .padding()
}

#Preview("Sparkline · No Range") {
    MetricSparklineChart(
        history: [
            SparkPoint(date: Date(timeIntervalSince1970: 1_700_000_000), value: 6.5),
            SparkPoint(date: Date(timeIntervalSince1970: 1_710_000_000), value: 6.3),
            SparkPoint(date: Date(timeIntervalSince1970: 1_720_000_000), value: 6.2)
        ],
        categoryColor: LabCategory.glycemic.color,
        referenceRange: nil
    )
    .frame(height: 120)
    .padding()
}

#Preview("Sparkline · Irregular Gaps") {
    MetricSparklineChart(
        history: [
            SparkPoint(date: Fixture.day(2024, 1, 10), value: 32),
            SparkPoint(date: Fixture.day(2024, 1, 24), value: 35),
            SparkPoint(date: Fixture.day(2026, 1, 24), value: 33),
            SparkPoint(date: Fixture.day(2026, 5, 28), value: 34)
        ],
        categoryColor: LabCategory.nutrition.color,
        referenceRange: nil
    )
    .frame(height: 120)
    .padding()
}

#Preview("Sparkline · Dark") {
    MetricSparklineChart(
        history: [
            SparkPoint(date: Date(timeIntervalSince1970: 1_700_000_000), value: 6.5),
            SparkPoint(date: Date(timeIntervalSince1970: 1_710_000_000), value: 6.3),
            SparkPoint(date: Date(timeIntervalSince1970: 1_720_000_000), value: 6.2)
        ],
        categoryColor: LabCategory.glycemic.color,
        referenceRange: ReferenceRange(low: 4.0, high: 5.6)
    )
    .frame(height: 120)
    .padding()
    .preferredColorScheme(.dark)
}
#endif
