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

    /// Direction of the latest reading relative to the previous one, used to show
    /// a small trend arrow in the overview. `nil` when there is no prior value to
    /// compare against.
    private enum Trend {
        case rising, falling, steady

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

    private var trend: Trend? {
        guard metric.history.count > 1 else { return nil }
        let latest = metric.history[metric.history.count - 1].value
        let previous = metric.history[metric.history.count - 2].value
        if latest > previous { return .rising }
        if latest < previous { return .falling }
        return .steady
    }

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
                sparkline
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

    /// Reference bounds to draw on the sparkline: only those close enough to the
    /// readings that showing them won't squash the trend (or stray off-card). A
    /// bound farther than the tolerance from the data is dropped. The tolerance
    /// is half the reading span, with a floor so a flat/near-flat series still
    /// admits a nearby bound.
    private var sparklineBounds: (low: Double?, high: Double?) {
        let values = metric.history.map(\.value)
        guard let dataLo = values.min(), let dataHi = values.max(), let range = referenceRange else {
            return (nil, nil)
        }
        let tolerance = max((dataHi - dataLo) * 0.5, abs(dataHi) * 0.1, 0.5)
        return (low: range.low.flatMap { $0 >= dataLo - tolerance ? $0 : nil },
                high: range.high.flatMap { $0 <= dataHi + tolerance ? $0 : nil })
    }

    /// Y-axis domain covering the readings *and* any drawn reference bound, padded
    /// so nothing hugs an edge. Folding the bound into the scale (rather than
    /// pinning to the data alone) keeps a near-the-max threshold from cramming
    /// against the top. A flat series gets a unit window so it doesn't collapse.
    private var sparklineDomain: ClosedRange<Double> {
        let values = metric.history.map(\.value)
        var dataLo = values.min() ?? 0
        var dataHi = values.max() ?? 1
        let bounds = sparklineBounds
        if let low = bounds.low { dataLo = min(dataLo, low) }
        if let high = bounds.high { dataHi = max(dataHi, high) }
        guard dataLo < dataHi else { return (dataLo - 1)...(dataHi + 1) }
        let pad = (dataHi - dataLo) * 0.15
        return (dataLo - pad)...(dataHi + pad)
    }

    private var sparkline: some View {
        let bounds = sparklineBounds
        return Chart {
            // Faint dashed guides at the reference bounds (those near the readings;
            // see `sparklineBounds`). Both are folded into the Y scale, so a guide
            // always sits inside the frame with margin.
            if let low = bounds.low {
                RuleMark(y: .value("Low", low))
                    .foregroundStyle(RangeStatus.low.color.opacity(0.35))
                    .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [3, 2]))
            }
            if let high = bounds.high {
                RuleMark(y: .value("High", high))
                    .foregroundStyle(RangeStatus.high.color.opacity(0.35))
                    .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [3, 2]))
            }

            ForEach(metric.history) { point in
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
        }
        .chartYScale(domain: sparklineDomain)
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        // Grow to absorb the card's remaining vertical space instead of leaving a
        // fixed-height chart with blank padding beneath it. `minHeight` keeps a
        // sensible floor when a card's row is short.
        .frame(minHeight: 44, maxHeight: .infinity)
        // Belt-and-suspenders: clip to the final frame so no mark can draw past
        // the chart (and the card edge) regardless of scale.
        .clipped()
    }
}
