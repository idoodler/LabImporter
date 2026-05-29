import SwiftUI
import Charts

// MARK: - MetricsHomescreenGrid

/// The dashboard's metric grid with iOS-homescreen-style arranging:
/// long-press a card to enter edit mode (cards jiggle), drag with live reflow,
/// remove (✕) and pin badges, and tap to open a trend when not arranging.
struct MetricsHomescreenGrid: View {
    let metrics: [MetricData]
    @Binding var prefs: LabDisplayPreferences
    @Binding var isEditing: Bool
    let onOpenTrend: (String) -> Void

    @State private var workingOrder: [String] = []
    @State private var draggingCode: String?
    @State private var dragLocation: CGPoint?
    @State private var cellFrames: [String: CGRect] = [:]

    private let haptics = UIImpactFeedbackGenerator(style: .medium)
    private let gridSpace = "dashboardGrid"

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
        .onChange(of: isEditing) { _, editing in
            if editing {
                if workingOrder.isEmpty { workingOrder = sortedMetrics.map(\.entry.code) }
            } else {
                commit()
            }
        }
    }

    // MARK: - Card

    @ViewBuilder
    private func metricCard(for metric: MetricData) -> some View {
        let code = metric.entry.code
        let pinned = prefs.pinnedSet.contains(code)
        let dragging = draggingCode == code
        MetricCard(metric: metric, isPinned: pinned)
            .contentShape(RoundedRectangle(cornerRadius: 20))
            .overlay(alignment: .topLeading) {
                if isEditing { removeBadge(code) }
            }
            .overlay(alignment: .topTrailing) {
                if isEditing { pinBadge(code, pinned: pinned) }
            }
            .modifier(JiggleEffect(active: isEditing && !dragging, code: code))
            .scaleEffect(dragging ? 1.06 : 1)
            .offset(dragging ? dragOffset(for: code) : .zero)
            .opacity(dragging ? 0.92 : 1)
            .shadow(color: .black.opacity(dragging ? 0.22 : 0), radius: dragging ? 12 : 0, y: dragging ? 6 : 0)
            .zIndex(dragging ? 2 : (isEditing ? 1 : 0))
            .onGeometryChange(for: CGRect.self) {
                $0.frame(in: .named(gridSpace))
            } action: { cellFrames[code] = $0 }
            .onTapGesture { handleTap(code) }
            .gesture(reorderGesture(for: code))
    }

    // MARK: - Edit-mode badges

    private func removeBadge(_ code: String) -> some View {
        Button { hideCode(code) } label: {
            Image(systemName: "xmark")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Circle().fill(Color.black.opacity(0.55)))
        }
        .buttonStyle(.plain)
        .offset(x: -7, y: -7)
        .accessibilityLabel("Remove from Dashboard")
        .transition(.scale.combined(with: .opacity))
    }

    private func pinBadge(_ code: String, pinned: Bool) -> some View {
        Button { togglePin(code) } label: {
            Image(systemName: pinned ? "pin.fill" : "pin")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(pinned ? Color.yellow : .white)
                .frame(width: 22, height: 22)
                .background(Circle().fill(Color.black.opacity(0.55)))
        }
        .buttonStyle(.plain)
        .offset(x: 7, y: -7)
        .accessibilityLabel(pinned ? "Unpin" : "Pin to Top")
        .transition(.scale.combined(with: .opacity))
    }

    // MARK: - Tap & reorder gesture

    private func handleTap(_ code: String) {
        guard !isEditing else { return }
        onOpenTrend(code)
    }

    private func reorderGesture(for code: String) -> some Gesture {
        LongPressGesture(minimumDuration: 0.35)
            .sequenced(before: DragGesture(coordinateSpace: .named(gridSpace)))
            .onChanged { value in
                switch value {
                case .first(true):
                    pickUp(code)
                case .second(true, let drag):
                    if let drag { updateDrag(to: drag.location) }
                default:
                    break
                }
            }
            .onEnded { _ in drop() }
    }

    // MARK: - Drag lifecycle

    /// Long press recognized: enter edit mode (if needed) and lift the card.
    private func pickUp(_ code: String) {
        if !isEditing {
            workingOrder = sortedMetrics.map(\.entry.code)
            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { isEditing = true }
        }
        if workingOrder.isEmpty { workingOrder = sortedMetrics.map(\.entry.code) }
        draggingCode = code
        if let frame = cellFrames[code] {
            dragLocation = CGPoint(x: frame.midX, y: frame.midY)
        }
        haptics.impactOccurred()
    }

    /// Live reflow: when the finger crosses into another card's slot, move the
    /// dragged card there and let the grid animate the rest out of the way.
    private func updateDrag(to location: CGPoint) {
        guard let dragging = draggingCode else { return }
        dragLocation = location
        guard let target = cellFrames.first(where: { $0.key != dragging && $0.value.contains(location) })?.key,
              let from = workingOrder.firstIndex(of: dragging),
              let dest = workingOrder.firstIndex(of: target),
              from != dest
        else { return }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.75)) {
            let moved = workingOrder.remove(at: from)
            workingOrder.insert(moved, at: dest)
        }
        haptics.impactOccurred(intensity: 0.4)
    }

    private func drop() {
        guard draggingCode != nil else { return }
        persistOrder(workingOrder)
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
            draggingCode = nil
            dragLocation = nil
        }
    }

    private func commit() {
        persistOrder(workingOrder)
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
            draggingCode = nil
            dragLocation = nil
        }
    }

    /// Offset that keeps the lifted card glued under the finger, regardless of
    /// how the grid has reflowed beneath it.
    private func dragOffset(for code: String) -> CGSize {
        guard let location = dragLocation, let frame = cellFrames[code] else { return .zero }
        return CGSize(width: location.x - frame.midX, height: location.y - frame.midY)
    }

    // MARK: - Ordering

    private var sortedMetrics: [MetricData] {
        dashboardSortedMetrics(metrics, prefs: prefs)
    }

    /// What the grid actually renders. While arranging, follow the live
    /// `workingOrder` so reflow is immediate; otherwise use the persisted sort.
    private var displayedMetrics: [MetricData] {
        guard isEditing else { return sortedMetrics }
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

    private func togglePin(_ code: String) {
        var updated = prefs
        if updated.pinnedCodes.contains(code) {
            updated.pinnedCodes.removeAll { $0 == code }
        } else {
            updated.pinnedCodes.append(code)
        }
        prefs = updated
        haptics.impactOccurred(intensity: 0.5)
    }

    private func hideCode(_ code: String) {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
            workingOrder.removeAll { $0 == code }
        }
        var updated = prefs
        if !updated.hiddenCodes.contains(code) { updated.hiddenCodes.append(code) }
        updated.pinnedCodes.removeAll { $0 == code }
        prefs = updated
        persistOrder(workingOrder)
        haptics.impactOccurred()
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

// MARK: - JiggleEffect

/// The iOS-homescreen wobble applied to every card while arranging. Each card
/// uses a slightly different period so the grid doesn't jiggle in lockstep.
private struct JiggleEffect: ViewModifier {
    let active: Bool
    let code: String
    @State private var wobble = false

    private var amplitude: Double { 1.6 }
    private var period: Double { 0.13 + Double(abs(code.hashValue) % 5) * 0.012 }

    func body(content: Content) -> some View {
        content
            .rotationEffect(.degrees(active ? (wobble ? amplitude : -amplitude) : 0))
            .onAppear { updateWobble(active) }
            .onChange(of: active) { _, now in updateWobble(now) }
    }

    private func updateWobble(_ active: Bool) {
        if active {
            withAnimation(.easeInOut(duration: period).repeatForever(autoreverses: true)) {
                wobble = true
            }
        } else {
            withAnimation(.easeInOut(duration: 0.1)) { wobble = false }
        }
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
