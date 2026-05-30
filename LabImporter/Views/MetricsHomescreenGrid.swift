import SwiftUI
import Charts
import UniformTypeIdentifiers

// MARK: - MetricsHomescreenGrid

/// The dashboard's metric grid with iOS Home Screen–style drag-to-reorder.
///
/// There is no separate "edit"/jiggle mode: a swipe scrolls, a tap opens the
/// card's trend, and a long-press lifts a card so it follows the finger. While a
/// card is dragged the grid **reflows live** — the other cards animate aside to
/// open the gap where the card will land. Built on `.onDrag` + a `DropDelegate`
/// (UIKit drag-and-drop) so it coexists with the surrounding `ScrollView`.
struct MetricsHomescreenGrid: View {
    let metrics: [MetricData]
    @Binding var prefs: LabDisplayPreferences
    let onOpenTrend: (String) -> Void

    /// The code currently lifted, or `nil` when no drag is in progress. The
    /// lifted card is hidden in place so its slot reads as the open gap.
    @State private var draggingCode: String?
    /// The working order during an active drag. `nil` when idle, in which case
    /// the displayed order comes from `dashboardSortedMetrics`.
    @State private var liveOrder: [String]?

    var body: some View {
        LazyVGrid(
            columns: [GridItem(.flexible()), GridItem(.flexible())],
            spacing: 14
        ) {
            ForEach(displayMetrics) { metric in
                metricCard(for: metric)
            }
        }
        .animation(.snappy, value: displayedCodes)
        // Catches drops that land in the grid's gutters (not on a card) so the
        // drag still finalizes and resets.
        .onDrop(of: [.text], delegate: GridResetDropDelegate(
            draggingCode: $draggingCode,
            liveOrder: $liveOrder,
            onCommit: commitOrder
        ))
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
        .opacity(draggingCode == code ? 0 : 1)
        .onDrag {
            beginDrag(code)
            return NSItemProvider(object: code as NSString)
        } preview: {
            MetricCard(metric: metric, isPinned: pinned)
                .frame(width: 180)
                // Match the card's corners so the lift platter isn't a square.
                .contentShape(.dragPreview, RoundedRectangle(cornerRadius: 20))
        }
        .onDrop(of: [.text], delegate: ReorderDropDelegate(
            targetCode: code,
            draggingCode: $draggingCode,
            liveOrder: $liveOrder,
            onCommit: commitOrder
        ))
    }

    // MARK: - Ordering

    /// The codes in the order they should be shown right now: the live drag order
    /// while dragging, otherwise the persisted pinned-first order.
    private var displayedCodes: [String] {
        liveOrder ?? dashboardSortedMetrics(metrics, prefs: prefs).map(\.entry.code)
    }

    /// `displayedCodes` resolved back to their `MetricData`.
    private var displayMetrics: [MetricData] {
        let lookup = Dictionary(metrics.map { ($0.entry.code, $0) }) { first, _ in first }
        return displayedCodes.compactMap { lookup[$0] }
    }

    /// Starts a drag, first clearing any state left behind by a previous drag
    /// that ended without a drop (e.g. released over the navigation bar), so a
    /// stale hidden card can never persist into the next gesture.
    private func beginDrag(_ code: String) {
        draggingCode = code
        liveOrder = displayedCodes
    }

    /// Persists the final drag order. The drop delegates clear the transient drag
    /// state via their bindings; this only writes the result through `prefs`.
    private func commitOrder(_ order: [String]) {
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

// MARK: - Drop delegates

/// Per-card delegate that performs the live reflow: as the dragged card hovers a
/// new target, it moves within `liveOrder` (animated), so neighbouring cards part
/// to open the landing gap.
private struct ReorderDropDelegate: DropDelegate {
    let targetCode: String
    @Binding var draggingCode: String?
    @Binding var liveOrder: [String]?
    let onCommit: ([String]) -> Void

    func dropEntered(info: DropInfo) {
        guard let dragging = draggingCode, dragging != targetCode,
              var order = liveOrder,
              let from = order.firstIndex(of: dragging),
              let dest = order.firstIndex(of: targetCode)
        else { return }
        withAnimation(.snappy) {
            order.move(fromOffsets: IndexSet(integer: from), toOffset: dest > from ? dest + 1 : dest)
            liveOrder = order
        }
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: .move)
    }

    func performDrop(info: DropInfo) -> Bool {
        if let order = liveOrder { onCommit(order) }
        draggingCode = nil
        liveOrder = nil
        return true
    }
}

/// Grid-level fallback delegate: finalizes a drop that lands between cards, and
/// resets the drag state so a lifted card never stays hidden.
private struct GridResetDropDelegate: DropDelegate {
    @Binding var draggingCode: String?
    @Binding var liveOrder: [String]?
    let onCommit: ([String]) -> Void

    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: .move)
    }

    func performDrop(info: DropInfo) -> Bool {
        if let order = liveOrder { onCommit(order) }
        draggingCode = nil
        liveOrder = nil
        return true
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
