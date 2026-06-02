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
                if showsFewValuesHint {
                    fewValuesHint
                }
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
            importMenuItems
        } label: {
            Image(systemName: "plus")
                .fontWeight(.semibold)
        }
    }

    /// The import actions, shared by the toolbar `+` menu and the few-values hint
    /// card's "Import Report" button so both offer identical entry points.
    @ViewBuilder
    private var importMenuItems: some View {
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
    /// stretch into a sparse two-up grid. With only one or two metrics the
    /// two-up grid would strand a card in a half-empty row, so it collapses to a
    /// single full-width column that reads as an intentional, larger card.
    private var gridColumns: [GridItem] {
        if horizontalSizeClass == .regular {
            return [GridItem(.adaptive(minimum: 200, maximum: 280), spacing: 14)]
        }
        if sortedMetrics.count <= 2 {
            return [GridItem(.flexible())]
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

    // MARK: - Few-values hint

    /// With only one or two metrics there are no sparklines yet and the grid
    /// leaves the screen mostly empty, so a gentle card explains why and offers a
    /// one-tap path to import another report and start building trends.
    private var showsFewValuesHint: Bool {
        (1...2).contains(sortedMetrics.count)
    }

    private var fewValuesHint: some View {
        VStack(spacing: 14) {
            Image(systemName: "chart.line.uptrend.xyaxis")
                .font(.system(size: 32))
                .foregroundStyle(LabCategory.endocrine.color.gradient)
            VStack(spacing: 6) {
                Text("Track Your Trends")
                    .font(.headline)
                Text("Import reports with numeric lab values to see trends.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Menu {
                importMenuItems
            } label: {
                Label("Import Report", systemImage: "plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .padding(24)
        // Cap and center the card so it stays a readable column rather than
        // stretching across an iPad's detail pane.
        .frame(maxWidth: 480)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
        .frame(maxWidth: .infinity)
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

    /// Trailing window (in months) each overview sparkline charts, measured back
    /// from that metric's *own* latest reading (not "today"). Keeps an old
    /// outlier from stretching the axis; older readings stay visible in Trends.
    private static let sparklineWindowMonths = 12

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
            let cutoff = Calendar.current.date(byAdding: .month, value: -Self.sparklineWindowMonths, to: item.date) ?? .distantPast
            let all = (allPoints[item.entry.code] ?? []).sorted { $0.date < $1.date }
            var points = all.filter { $0.date >= cutoff }
            // Never let the window collapse a real trend to a single point: with
            // two+ readings keep the two most recent so a sparkline still draws.
            if points.count < 2 && all.count >= 2 {
                points = Array(all.suffix(2))
            }
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

#Preview("Few values") {
    NavigationStack {
        DashboardView(
            reports: [LabReport(
                id: UUID(),
                date: Date(timeIntervalSince1970: 1_780_000_000),
                patientName: "Max Mustermann",
                authorName: "Laborzentrum München",
                entries: [
                    LabReport.Entry(id: UUID(), code: "4548-4", name: "HbA1c",
                                    displayValue: "5.4", numericValue: 5.4, unit: "%")
                ]
            )],
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true,
            clipboardAvailable: false,
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
