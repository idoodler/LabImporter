import SwiftUI
import Charts

// MARK: - Dashboard

struct DashboardView: View {
    let reports: [LabReport]
    let onScan: () -> Void
    let onPickFile: () -> Void
    let onPaste: () -> Void
    let onManual: () -> Void
    let scannerAvailable: Bool
    let clipboardAvailable: Bool
    let isProcessing: Bool
    /// When the dashboard is the detail pane of an iPad sidebar, the sidebar
    /// already hosts the Reports/Settings/Import entry points, so the dashboard
    /// hides its own toolbar chrome to avoid duplicating them. Defaults to `true`
    /// so the standalone (iPhone) presentation is unchanged.
    var showsLibraryToolbarItems = true

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var showSettings = false
    @State private var trendSheet: TrendSheet?

    private struct TrendSheet: Identifiable {
        var id: String { code }
        let code: String
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                metricSections
                footer
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .background { CategoryBackground(colors: backgroundColors) }
        .navigationTitle("Lab Results")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            if showsLibraryToolbarItems {
                ToolbarItem(placement: .topBarLeading) {
                    NavigationLink(destination: HistoryView()) {
                        Image(systemName: "doc.text")
                    }
                    .accessibilityLabel("Reports")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    importMenu
                }
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView(prefs: $prefs, allCodes: allCodeNames)
        }
        .sheet(item: $trendSheet) { sheet in
            NavigationStack {
                TrendsView(reports: reports, initialCode: sheet.code, onDismiss: { trendSheet = nil })
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
    }

    // MARK: - Import menu

    @ViewBuilder
    private var importMenu: some View {
        Menu {
            Button(action: onScan) {
                Label("Scan Document", systemImage: "doc.viewfinder")
            }
            .disabled(!scannerAvailable)
            Button(action: onPickFile) {
                Label("Choose File", systemImage: "folder")
            }
            Button(action: onPaste) {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
            }
            .disabled(!clipboardAvailable)
            Button(action: onManual) {
                Label("Create Report Manually", systemImage: "square.and.pencil")
            }
        } label: {
            Image(systemName: "plus")
                .fontWeight(.semibold)
        }
    }

    // MARK: - Metrics grid

    /// Pinned metrics get their own labeled section on top; the rest flow
    /// underneath with no header. When nothing is pinned this collapses to a
    /// single unlabeled grid — identical to the pre-sectioning layout.
    @ViewBuilder
    private var metricSections: some View {
        let pinned = pinnedMetrics
        if !pinned.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                pinnedHeader
                grid(pinned)
            }
        }
        grid(unpinnedMetrics)
    }

    private func grid(_ items: [MetricData]) -> some View {
        LazyVGrid(columns: gridColumns, spacing: 14) {
            ForEach(items) { metric in
                metricCard(for: metric)
            }
        }
    }

    private var pinnedHeader: some View {
        HStack(spacing: 6) {
            Image(systemName: "pin.fill")
                .font(.caption)
            Text("Pinned")
                .font(.subheadline.weight(.semibold))
            Spacer()
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 2)
    }

    /// Two fixed columns on compact widths (iPhone); on regular widths (iPad) the
    /// cards flow to fill the wider canvas with a sensible minimum so they don't
    /// stretch into a sparse two-up grid.
    private var gridColumns: [GridItem] {
        if horizontalSizeClass == .regular {
            return [GridItem(.adaptive(minimum: 200, maximum: 280), spacing: 14)]
        }
        return [GridItem(.flexible()), GridItem(.flexible())]
    }

    @ViewBuilder
    private func metricCard(for metric: MetricData) -> some View {
        let code = metric.entry.code
        let pinned = prefs.pinnedSet.contains(code)
        Button { trendSheet = TrendSheet(code: code) } label: {
            MetricCard(metric: metric, isPinned: pinned)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { togglePin(code) } label: {
                Label(
                    pinned ? "Unpin" : "Pin to Top",
                    systemImage: pinned ? "pin.slash" : "pin"
                )
            }
        }
    }

    @ViewBuilder
    private var footer: some View {
        if let date = reports.max(by: { $0.date < $1.date })?.date {
            Text("Last updated \(date.formatted(date: .abbreviated, time: .omitted))")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Metrics computation

    private var metrics: [MetricData] {
        var latestEntry: [String: (entry: LabReport.Entry, date: Date)] = [:]
        var allPoints: [String: [SparkPoint]] = [:]

        for report in reports {
            for entry in report.entries {
                guard let value = entry.numericValue else { continue }
                allPoints[entry.code, default: []].append(SparkPoint(date: report.date, value: value))
                if let existing = latestEntry[entry.code] {
                    if report.date > existing.date {
                        latestEntry[entry.code] = (entry: entry, date: report.date)
                    }
                } else {
                    latestEntry[entry.code] = (entry: entry, date: report.date)
                }
            }
        }

        return latestEntry.values.map { item in
            let points = (allPoints[item.entry.code] ?? []).sorted { $0.date < $1.date }
            return MetricData(entry: item.entry, history: points)
        }
    }

    private var sortedMetrics: [MetricData] {
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

    private var pinnedMetrics: [MetricData] {
        let pinned = prefs.pinnedSet
        return sortedMetrics.filter { pinned.contains($0.entry.code) }
    }

    private var unpinnedMetrics: [MetricData] {
        let pinned = prefs.pinnedSet
        return sortedMetrics.filter { !pinned.contains($0.entry.code) }
    }

    // Up to three distinct category colors from the visible metrics, used for
    // the subtle background wash.
    private var backgroundColors: [Color] {
        var seen = Set<LabCategory>()
        var result: [Color] = []
        for metric in sortedMetrics {
            let category = LabCategory.forCode(metric.entry.code)
            if seen.insert(category).inserted {
                result.append(category.color)
            }
            if result.count == 3 { break }
        }
        return result
    }

    // MARK: - Code names for order sheet

    private var allCodeNames: [CodeName] {
        var seen = Set<String>()
        var result: [CodeName] = []
        for report in reports {
            for entry in report.entries where seen.insert(entry.code).inserted {
                result.append(CodeName(code: entry.code, name: entry.resolvedName))
            }
        }
        return result
    }

    // MARK: - Pin helpers

    private func togglePin(_ code: String) {
        var updated = prefs
        if updated.pinnedCodes.contains(code) {
            updated.pinnedCodes.removeAll { $0 == code }
        } else {
            updated.pinnedCodes.append(code)
        }
        prefs = updated
    }
}

// MARK: - Supporting types

private struct SparkPoint: Identifiable {
    let id = UUID()
    let date: Date
    let value: Double
}

private struct MetricData: Identifiable {
    var id: String { entry.code }
    let entry: LabReport.Entry
    let history: [SparkPoint]
}

// MARK: - MetricCard

private struct MetricCard: View {
    let metric: MetricData
    let isPinned: Bool

    private var categoryColor: Color {
        LabCategory.forCode(metric.entry.code).color
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
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if !metric.entry.unit.isEmpty {
                    Text(metric.entry.unit)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
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
        // Grow to absorb the card's remaining vertical space instead of leaving a
        // fixed-height chart with blank padding beneath it. `minHeight` keeps a
        // sensible floor when a card's row is short.
        .frame(minHeight: 44, maxHeight: .infinity)
    }
}

// MARK: - CategoryBackground

/// A soft, low-opacity wash of the dashboard's metric category colors — a subtle
/// tint behind the content in the spirit of the Health app. Falls back to a
/// gentle accent tint when there are no metrics yet.
struct CategoryBackground: View {
    let colors: [Color]

    private let anchors: [UnitPoint] = [.topLeading, .topTrailing, .bottomLeading]

    var body: some View {
        ZStack {
            ForEach(Array(palette.enumerated()), id: \.offset) { index, color in
                RadialGradient(
                    colors: [color.opacity(0.16), .clear],
                    center: anchors[index % anchors.count],
                    startRadius: 0,
                    endRadius: 480
                )
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    private var palette: [Color] {
        colors.isEmpty ? [Color.accentColor.opacity(0.6)] : colors
    }
}

// MARK: - Preview

#Preview("Dashboard") {
    NavigationStack {
        DashboardView(
            reports: LabReport.sampleHistory,
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true,
            clipboardAvailable: true,
            isProcessing: false
        )
    }
}

#Preview("Empty") {
    NavigationStack {
        DashboardView(
            reports: [],
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true,
            clipboardAvailable: false,
            isProcessing: false
        )
    }
}
