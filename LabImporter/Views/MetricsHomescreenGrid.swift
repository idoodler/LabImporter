import SwiftUI
import Charts

// MARK: - MetricsHomescreenGrid

/// The dashboard's metric grid with iOS-homescreen-style drag-to-reorder:
/// press-and-hold a card briefly to pick it up (a little "sticky"), then drag
/// it around and the rest of the grid reflows live to make room. A quick tap
/// still opens the card's trend.
struct MetricsHomescreenGrid: View {
    let metrics: [MetricData]
    @Binding var prefs: LabDisplayPreferences
    let onOpenTrend: (String) -> Void

    /// The transient lift/drag, driven by the gesture. Using `@GestureState`
    /// means SwiftUI snaps it back to `nil` automatically the moment the finger
    /// lifts or the gesture is cancelled — so a tap (or an interrupted press)
    /// can never leave a card stuck enlarged.
    @GestureState private var activeDrag: ActiveDrag?
    @State private var workingOrder: [String] = []
    @State private var cellFrames: [String: CGRect] = [:]

    private let haptics = UIImpactFeedbackGenerator(style: .medium)
    private let gridSpace = "dashboardGrid"

    private struct ActiveDrag: Equatable {
        let code: String
        var location: CGPoint?
    }

    var body: some View {
        LazyVGrid(
            columns: [GridItem(.flexible()), GridItem(.flexible())],
            spacing: 14
        ) {
            ForEach(displayedMetrics) { metric in
                metricCard(for: metric)
            }
        }
        .coordinateSpace(.named(gridSpace))
    }

    // MARK: - Card

    @ViewBuilder
    private func metricCard(for metric: MetricData) -> some View {
        let code = metric.entry.code
        let pinned = prefs.pinnedSet.contains(code)
        let lifted = activeDrag?.code == code
        MetricCard(metric: metric, isPinned: pinned)
            .contentShape(RoundedRectangle(cornerRadius: 20))
            .scaleEffect(lifted ? 1.05 : 1)
            .offset(lifted ? dragOffset(for: code) : .zero)
            .opacity(lifted ? 0.95 : 1)
            .shadow(color: .black.opacity(lifted ? 0.22 : 0), radius: lifted ? 12 : 0, y: lifted ? 6 : 0)
            .zIndex(lifted ? 1 : 0)
            .animation(.interactiveSpring(response: 0.25, dampingFraction: 0.86), value: activeDrag)
            .onGeometryChange(for: CGRect.self) {
                $0.frame(in: .named(gridSpace))
            } action: { cellFrames[code] = $0 }
            .onTapGesture { onOpenTrend(code) }
            .gesture(reorderGesture(for: code))
    }

    // MARK: - Sticky drag gesture

    /// A short long-press "grabs" the card (so it doesn't fight the scroll view
    /// or a quick tap), then the drag moves it with live reflow. The visible
    /// lift is bound to `@GestureState` (`updating`), which auto-resets on
    /// release; `onChanged`/`onEnded` only handle the reorder bookkeeping.
    private func reorderGesture(for code: String) -> some Gesture {
        LongPressGesture(minimumDuration: 0.22)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named(gridSpace)))
            .updating($activeDrag) { value, state, _ in
                switch value {
                case .first(true):
                    state = ActiveDrag(code: code, location: nil)
                case .second(true, let drag):
                    state = ActiveDrag(code: code, location: drag?.location)
                default:
                    break
                }
            }
            .onChanged { value in
                switch value {
                case .first(true):
                    pickUp()
                case .second(true, let drag):
                    if let drag { updateDrag(code, to: drag.location) }
                default:
                    break
                }
            }
            .onEnded { _ in commitOrder() }
    }

    /// Long press recognized: snapshot the current order for live reflow. Runs
    /// fresh on every press, so it never depends on the previous drag's cleanup.
    private func pickUp() {
        workingOrder = sortedMetrics.map(\.entry.code)
        haptics.impactOccurred()
    }

    /// Live reflow: when the finger crosses into another card's slot, move the
    /// dragged card there and let the grid animate the rest out of the way.
    private func updateDrag(_ code: String, to location: CGPoint) {
        guard let target = cellFrames.first(where: { $0.key != code && $0.value.contains(location) })?.key,
              let from = workingOrder.firstIndex(of: code),
              let dest = workingOrder.firstIndex(of: target),
              from != dest
        else { return }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.75)) {
            let moved = workingOrder.remove(at: from)
            workingOrder.insert(moved, at: dest)
        }
        haptics.impactOccurred(intensity: 0.4)
    }

    private func commitOrder() {
        guard !workingOrder.isEmpty else { return }
        persistOrder(workingOrder)
    }

    /// Offset that keeps the lifted card glued under the finger, regardless of
    /// how the grid has reflowed beneath it. Zero while merely pressing.
    private func dragOffset(for code: String) -> CGSize {
        guard let location = activeDrag?.location, let frame = cellFrames[code] else { return .zero }
        return CGSize(width: location.x - frame.midX, height: location.y - frame.midY)
    }

    // MARK: - Ordering

    private var sortedMetrics: [MetricData] {
        dashboardSortedMetrics(metrics, prefs: prefs)
    }

    /// While dragging, follow the live `workingOrder` so reflow is immediate;
    /// otherwise use the persisted sort.
    private var displayedMetrics: [MetricData] {
        guard activeDrag != nil, !workingOrder.isEmpty else { return sortedMetrics }
        let byCode = Dictionary(metrics.map { ($0.entry.code, $0) }, uniquingKeysWith: { first, _ in first })
        return workingOrder.compactMap { byCode[$0] }
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
