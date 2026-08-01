import SwiftUI
import Charts

// MARK: - Supporting types

struct SparkPoint: Identifiable {
    let id = UUID()
    let date: Date
    let value: Double
}

struct MetricData: Identifiable {
    var id: String { entry.code }
    let entry: LabReport.Entry
    let history: [SparkPoint]
}

/// Direction of a metric's latest reading relative to the previous one, shared
/// by the grid's `MetricCard` and the single-metric `HeroMetricCard`. `nil`
/// when there's no prior value to compare against.
enum MetricTrend {
    case rising, falling, steady

    init?(history: [SparkPoint]) {
        guard history.count > 1 else { return nil }
        let latest = history[history.count - 1].value
        let previous = history[history.count - 2].value
        if latest > previous {
            self = .rising
        } else if latest < previous {
            self = .falling
        } else {
            self = .steady
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
                .font(.system(size: 9, weight: .heavy))
                .foregroundStyle(.white)
                .frame(width: 18, height: 18)
                .background(categoryColor.gradient, in: Circle())
                .accessibilityLabel(trend.accessibilityLabel)
        } else {
            Circle()
                .fill(categoryColor.gradient)
                .frame(width: 18, height: 18)
                .accessibilityHidden(true)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 7) {
                dock
                Text(metric.entry.resolvedName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 1)
                Spacer(minLength: 0)
                if isPinned {
                    Image(systemName: "pin.fill")
                        .font(.caption2)
                        .foregroundStyle(Color.yellow)
                }
            }

            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(metric.entry.displayValue)
                    .font(.title2.bold())
                    .foregroundStyle(valueForeground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if !metric.entry.unit.isEmpty {
                    Text(metric.entry.unit)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                if let rangeStatus {
                    RangeStatusBadge(status: rangeStatus,
                                     range: referenceRange,
                                     unit: metric.entry.unit)
                }
            }

            if metric.history.count > 1 {
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
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
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

            // Faint dashed guides at the reference bounds. No explicit Y scale is
            // set, so Charts auto-includes these in the domain — the marks stay
            // inside the plot (never spilling past the card) and the data simply
            // shares the height with them.
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
    }
}

// MARK: - Previews

#if DEBUG
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
