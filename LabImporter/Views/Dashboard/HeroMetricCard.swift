import SwiftUI

// MARK: - HeroMetricCard

/// A large, featured presentation for a metric used when it's the *only* value
/// on the dashboard — see `DashboardView.soleMetric`. In that state a small grid
/// card plus a generic "import more" filler leaves the screen sparse and buries
/// the one thing the user actually has, so this leads with it instead: a bigger
/// number, the category icon, and (once there's a second reading) a taller
/// sparkline. With just one reading there's no trend to draw yet, so the card
/// explains that and offers the same import entry points inline.
struct HeroMetricCard<ImportMenu: View>: View {
    let metric: MetricData
    /// Invoked when the card is tapped to open the full trend sheet. Only wired
    /// up while there's a trend to show (`history.count > 1`) — with a single
    /// reading the card isn't a button, so its "Import Report" menu isn't
    /// nested inside another tappable control.
    let onSelectTrend: () -> Void
    @ViewBuilder let importMenu: () -> ImportMenu

    private var hasTrend: Bool { metric.history.count > 1 }

    private var trend: MetricTrend? { MetricTrend(history: metric.history) }

    private var category: LabCategory { LabCategory.forCode(metric.entry.code) }

    private var referenceRange: ReferenceRange? {
        LabMapping.referenceRange(for: metric.entry.code)
    }

    private var rangeStatus: RangeStatus? {
        LabMapping.rangeStatus(for: metric.entry.numericValue, code: metric.entry.code)
    }

    private var valueForeground: Color {
        guard let rangeStatus, rangeStatus.isOutOfRange else { return .primary }
        return rangeStatus.color
    }

    var body: some View {
        if hasTrend {
            Button(action: onSelectTrend) { card }
                .buttonStyle(.plain)
        } else {
            card
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            valueRow
            if hasTrend {
                MetricSparklineChart(history: metric.history, categoryColor: category.color, referenceRange: referenceRange)
                    .frame(height: 130)
            }
            if let date = metric.history.last?.date {
                Text("Last measured \(date.formatted(date: .abbreviated, time: .omitted))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if !hasTrend {
                importPrompt
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }

    private var header: some View {
        HStack(spacing: 10) {
            dockIcon
            VStack(alignment: .leading, spacing: 1) {
                Text(metric.entry.resolvedName)
                    .font(.headline)
                Text("Your only tracked value")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
    }

    /// The category-colored circle at the card's top-left. Once there's a
    /// trend to show, it doubles as the trend indicator (a directional arrow,
    /// matching the grid's `MetricCard` dock); with a single reading there's
    /// no direction yet, so it falls back to the category icon.
    @ViewBuilder
    private var dockIcon: some View {
        if let trend {
            dockCircle(systemName: trend.symbol, weight: .heavy)
                .accessibilityLabel(trend.accessibilityLabel)
        } else {
            dockCircle(systemName: category.icon, weight: .semibold)
                .accessibilityHidden(true)
        }
    }

    private func dockCircle(systemName: String, weight: Font.Weight) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 17, weight: weight))
            .foregroundStyle(.white)
            .frame(width: 40, height: 40)
            .background(category.color.gradient, in: Circle())
    }

    private var valueRow: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(metric.entry.displayValue)
                .font(.system(size: 44, weight: .bold))
                .foregroundStyle(valueForeground)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            if !metric.entry.unit.isEmpty {
                Text(metric.entry.unit)
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if let rangeStatus {
                RangeStatusBadge(status: rangeStatus, range: referenceRange, unit: metric.entry.unit)
            }
        }
    }

    private var importPrompt: some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider()
            Text("Import at least two reports containing this value to see a trend.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Menu {
                importMenu()
            } label: {
                Label("Import Report", systemImage: "plus")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.regular)
        }
    }
}

// MARK: - Previews

#if DEBUG
private let sampleNoTrend = MetricData(
    entry: LabReport.soleValueReport().entries[0],
    history: [SparkPoint(date: .now, value: 5.4)]
)

private let sampleWithTrend = MetricData(
    entry: LabReport.soleValueReport().entries[0],
    history: LabReport.soleValueTrend.map { report in
        SparkPoint(date: report.date, value: report.entries[0].numericValue ?? 0)
    }
)

@ViewBuilder
private var sampleImportMenu: some View {
    Button("Scan Document") {}
    Button("Choose File") {}
    Button("Paste from Clipboard") {}
    Button("Create Report Manually") {}
}

#Preview("No Trend") {
    HeroMetricCard(metric: sampleNoTrend, onSelectTrend: {}, importMenu: { sampleImportMenu })
        .padding()
}

#Preview("With Trend") {
    HeroMetricCard(metric: sampleWithTrend, onSelectTrend: {}, importMenu: { sampleImportMenu })
        .padding()
}

#Preview("Dark") {
    HeroMetricCard(metric: sampleWithTrend, onSelectTrend: {}, importMenu: { sampleImportMenu })
        .padding()
        .preferredColorScheme(.dark)
}
#endif
