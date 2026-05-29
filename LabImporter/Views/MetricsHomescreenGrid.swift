import SwiftUI
import Charts

// MARK: - MetricsHomescreenGrid

/// The dashboard's metric grid with drag-to-reorder. Built on SwiftUI's
/// `.draggable`/`.dropDestination` (UIKit drag-and-drop) so it coexists with the
/// surrounding `ScrollView`: a swipe scrolls, a long-press lifts a card so it
/// follows the finger, a drop reorders, and a tap opens the card's trend.
struct MetricsHomescreenGrid: View {
    let metrics: [MetricData]
    @Binding var prefs: LabDisplayPreferences
    let onOpenTrend: (String) -> Void

    @State private var dropTargetCode: String?

    var body: some View {
        LazyVGrid(
            columns: [GridItem(.flexible()), GridItem(.flexible())],
            spacing: 14
        ) {
            ForEach(sortedMetrics) { metric in
                metricCard(for: metric)
            }
        }
    }

    // MARK: - Card

    @ViewBuilder
    private func metricCard(for metric: MetricData) -> some View {
        let code = metric.entry.code
        let pinned = prefs.pinnedSet.contains(code)
        Button { onOpenTrend(code) } label: {
            MetricCard(metric: metric, isPinned: pinned)
        }
        .buttonStyle(.plain)
        .overlay {
            if dropTargetCode == code {
                RoundedRectangle(cornerRadius: 20)
                    .stroke(Color.accentColor, lineWidth: 2)
            }
        }
        .draggable(code) {
            MetricCard(metric: metric, isPinned: pinned)
                .frame(width: 180)
        }
        .dropDestination(for: String.self) { items, _ in
            dropTargetCode = nil
            guard let dragged = items.first else { return false }
            moveMetric(dragged, before: code)
            return true
        } isTargeted: { isTargeted in
            dropTargetCode = isTargeted ? code : (dropTargetCode == code ? nil : dropTargetCode)
        }
    }

    // MARK: - Ordering

    private var sortedMetrics: [MetricData] {
        dashboardSortedMetrics(metrics, prefs: prefs)
    }

    /// Moves `dragged` to sit immediately before `target`, then persists the new
    /// sequence. Pinned cards still float to the top per the sort rules, so this
    /// effectively reorders within the pin groups.
    private func moveMetric(_ dragged: String, before target: String) {
        guard dragged != target else { return }
        var order = sortedMetrics.map(\.entry.code)
        guard let from = order.firstIndex(of: dragged) else { return }
        order.remove(at: from)
        guard let targetIndex = order.firstIndex(of: target) else { return }
        order.insert(dragged, at: targetIndex)
        persistOrder(order)
    }

    /// Persists `codes` as the leading entries of `orderedCodes`, preserving any
    /// previously ordered codes that aren't currently shown (e.g. hidden metrics).
    private func persistOrder(_ codes: [String]) {
        var result = codes
        var seen = Set(codes)
        for code in prefs.orderedCodes where seen.insert(code).inserted {
            result.append(code)
        }
        var updated = prefs
        updated.orderedCodes = result
        prefs = updated
    }
}

/// Pinned-first ordering used by both the grid and the dashboard background.
func dashboardSortedMetrics(_ metrics: [MetricData], prefs: LabDisplayPreferences) -> [MetricData] {
    let pinned = prefs.pinnedSet
    let hidden = prefs.hiddenSet
    var orderMap: [String: Int] = [:]
    for (idx, code) in prefs.orderedCodes.enumerated() where orderMap[code] == nil {
        orderMap[code] = idx
    }
    return metrics
        .filter { !hidden.contains($0.entry.code) }
        .sorted { lhs, rhs in
            let aPin = pinned.contains(lhs.entry.code)
            let bPin = pinned.contains(rhs.entry.code)
            if aPin != bPin { return aPin }
            let aOrd = orderMap[lhs.entry.code] ?? Int.max
            let bOrd = orderMap[rhs.entry.code] ?? Int.max
            if aOrd != bOrd { return aOrd < bOrd }
            return lhs.entry.resolvedName < rhs.entry.resolvedName
        }
}

// MARK: - MetricData

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

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 5) {
                Circle()
                    .fill(categoryColor)
                    .frame(width: 8, height: 8)
                    .padding(.top, 3)
                Text(metric.entry.resolvedName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
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
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if !metric.entry.unit.isEmpty {
                    Text(metric.entry.unit)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            if metric.history.count > 1 {
                sparkline
            } else {
                Spacer().frame(height: 40)
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

    private var sparkline: some View {
        Chart(metric.history) { point in
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
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .frame(height: 40)
    }
}
