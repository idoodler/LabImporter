import SwiftUI
import Charts

// MARK: - MetricsHomescreenGrid

/// The dashboard's metric grid with iOS Home Screen–style drag-to-reorder.
///
/// There is no separate "edit"/jiggle mode: a swipe scrolls, a tap opens the
/// card's trend, and a long-press lifts a card so it follows the finger. While a
/// card is dragged the grid **reflows live** — the other cards animate aside to
/// open the gap where the card will land, and on release the card settles into
/// its slot.
///
/// Reordering is driven by an in-app long-press + drag gesture (not the system
/// `.onDrag`/`.onDrop` data-transfer machinery), so there is no cross-app drag
/// session: backgrounding the app or releasing anywhere cleanly ends the drag,
/// and the drop animates into place instead of dissolving.
struct MetricsHomescreenGrid: View {
    let metrics: [MetricData]
    @Binding var prefs: LabDisplayPreferences
    let onOpenTrend: (String) -> Void

    /// The code currently lifted, or `nil` when no drag is in progress. The
    /// lifted card is hidden in place (its slot reads as the open gap) while a
    /// floating copy follows the finger.
    @State private var draggingCode: String?
    /// The working order during an active drag. `nil` when idle, in which case
    /// the displayed order comes from `dashboardSortedMetrics`.
    @State private var liveOrder: [String]?
    /// Live frames of every card, in the grid's coordinate space — used to find
    /// the slot under the finger and to settle the floating card on drop.
    @State private var cardFrames: [String: CGRect] = [:]
    /// Top-leading position of the floating card, in the grid's coordinate space.
    @State private var floatingOrigin: CGPoint = .zero
    /// Scale of the floating card (lifts to >1 while dragging, settles to 1).
    @State private var floatingScale: CGFloat = 1
    /// Offset from the lifted card's top-leading to the finger at grab time, kept
    /// constant so the float tracks the finger regardless of how the grid reflows.
    @State private var grabOffset: CGSize?

    private static let gridSpace = "LabGrid"

    var body: some View {
        LazyVGrid(
            columns: [GridItem(.flexible()), GridItem(.flexible())],
            spacing: 14
        ) {
            ForEach(displayMetrics) { metric in
                metricCard(for: metric)
            }
        }
        .coordinateSpace(.named(Self.gridSpace))
        .animation(.snappy, value: displayedCodes)
        .overlay(alignment: .topLeading) { floatingCard }
    }

    // MARK: - Card

    @ViewBuilder
    private func metricCard(for metric: MetricData) -> some View {
        let code = metric.entry.code
        let pinned = prefs.pinnedSet.contains(code)
        MetricCard(metric: metric, isPinned: pinned)
            .contentShape(RoundedRectangle(cornerRadius: 20))
            // The lifted card is hidden in place so its slot reads as the gap.
            .opacity(draggingCode == code ? 0 : 1)
            .onGeometryChange(for: CGRect.self) { proxy in
                proxy.frame(in: .named(Self.gridSpace))
            } action: { cardFrames[code] = $0 }
            .onTapGesture { onOpenTrend(code) }
            .gesture(reorderGesture(for: code))
    }

    /// The floating copy of the lifted card that tracks the finger.
    @ViewBuilder
    private var floatingCard: some View {
        if let code = draggingCode, let metric = metric(for: code) {
            MetricCard(metric: metric, isPinned: prefs.pinnedSet.contains(code))
                .frame(width: liftedSize.width, height: liftedSize.height)
                .scaleEffect(floatingScale)
                .shadow(color: .black.opacity(0.18), radius: 10, y: 6)
                .offset(x: floatingOrigin.x, y: floatingOrigin.y)
                .allowsHitTesting(false)
        }
    }

    // MARK: - Gesture

    private func reorderGesture(for code: String) -> some Gesture {
        LongPressGesture(minimumDuration: 0.3)
            .sequenced(before: DragGesture(coordinateSpace: .named(Self.gridSpace)))
            .onChanged { value in
                guard case .second(true, let drag) = value else { return }
                if draggingCode != code { beginDrag(code) }
                if let drag { updateDrag(drag) }
            }
            .onEnded { _ in endDrag() }
    }

    private func beginDrag(_ code: String) {
        draggingCode = code
        liveOrder = displayedCodes
        grabOffset = nil
        let frame = cardFrames[code] ?? .zero
        floatingOrigin = CGPoint(x: frame.minX, y: frame.minY)
        withAnimation(.snappy) { floatingScale = 1.05 }
    }

    private func updateDrag(_ drag: DragGesture.Value) {
        guard let dragging = draggingCode else { return }
        // Capture the finger's offset within the card once, from the original
        // slot, so the float stays pinned under the finger as the grid reflows.
        if grabOffset == nil {
            let origin = cardFrames[dragging]?.origin ?? .zero
            grabOffset = CGSize(width: drag.location.x - origin.x, height: drag.location.y - origin.y)
        }
        let offset = grabOffset ?? .zero
        floatingOrigin = CGPoint(x: drag.location.x - offset.width, y: drag.location.y - offset.height)
        relocate(to: drag.location)
    }

    /// Moves the dragged code to sit at whatever slot the finger is over, driving
    /// the live reflow. The grid's `.animation(value: displayedCodes)` animates
    /// the other cards parting.
    private func relocate(to point: CGPoint) {
        guard let dragging = draggingCode, var order = liveOrder else { return }
        guard let target = cardFrames.first(where: { $0.key != dragging && $0.value.contains(point) })?.key,
              let from = order.firstIndex(of: dragging),
              let dest = order.firstIndex(of: target), from != dest
        else { return }
        order.move(fromOffsets: IndexSet(integer: from), toOffset: dest > from ? dest + 1 : dest)
        liveOrder = order
    }

    private func endDrag() {
        guard let dragging = draggingCode else { return }
        if let liveOrder { persistOrder(liveOrder) }
        // Settle the floating card into its final slot, then drop the overlay so
        // the in-grid card reappears exactly where the float landed.
        let landing = cardFrames[dragging].map { CGPoint(x: $0.minX, y: $0.minY) } ?? floatingOrigin
        withAnimation(.snappy) {
            floatingOrigin = landing
            floatingScale = 1
        } completion: {
            draggingCode = nil
            liveOrder = nil
        }
    }

    // MARK: - Ordering

    /// The codes in the order they should be shown right now: the live drag order
    /// while dragging, otherwise the persisted pinned-first order.
    private var displayedCodes: [String] {
        liveOrder ?? dashboardSortedMetrics(metrics, prefs: prefs).map(\.entry.code)
    }

    /// `displayedCodes` resolved back to their `MetricData`.
    private var displayMetrics: [MetricData] {
        displayedCodes.compactMap { metric(for: $0) }
    }

    private func metric(for code: String) -> MetricData? {
        metrics.first { $0.entry.code == code }
    }

    private var liftedSize: CGSize {
        (draggingCode.flatMap { cardFrames[$0] } ?? .zero).size
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
