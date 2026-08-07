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
    @AppStorage("patientName") private var patientName: String = ""
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var showSettings = false
    @State private var showHistorySheet = false
    @State private var trendSheet: TrendSheet?

    private struct TrendSheet: Identifiable {
        var id: String { code }
        let code: String
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                greeting
                if let onlyMetric = soleMetric {
                    heroCard(for: onlyMetric)
                } else {
                    metricSections
                    if showsFewValuesHint {
                        fewValuesHint
                    }
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
                    NavigationLink(destination: HistoryView(initialReports: reports)) {
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
        .sheet(isPresented: $showHistorySheet) {
            NavigationStack { HistoryView(initialReports: reports) }
        }
        .sheet(item: $trendSheet) { sheet in
            NavigationStack {
                TrendsView(reports: reports, initialCode: sheet.code, onDismiss: { trendSheet = nil })
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .onAppear {
            guard ScreenshotMode.isActive else { return }
            switch ScreenshotMode.initialScreen {
            case "trends":
                guard trendSheet == nil, let firstCode = sortedMetrics.first?.entry.code else { return }
                trendSheet = TrendSheet(code: firstCode)
            case "history", "pdf":
                showHistorySheet = true
            case "settings":
                showSettings = true
            default:
                break
            }
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

    // MARK: - Hero card

    /// The dashboard's single metric, when that's all the user has. Rather than
    /// stranding one small grid card above a generic "import more" filler, the
    /// dashboard leads with a large featured card for that value instead.
    private var soleMetric: MetricData? {
        sortedMetrics.count == 1 ? sortedMetrics.first : nil
    }

    private func heroCard(for metric: MetricData) -> some View {
        HeroMetricCard(
            metric: metric,
            onSelectTrend: { trendSheet = TrendSheet(code: metric.entry.code) },
            importMenu: { importMenuItems }
        )
    }

    // MARK: - Few-values hint

    /// A single metric gets the dedicated `heroCard` treatment instead (see
    /// `soleMetric`), so this hint only fires for exactly two metrics: still too
    /// few for a balanced grid, but past the point of featuring just one.
    private var showsFewValuesHint: Bool {
        sortedMetrics.count == 2
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

}

// MARK: - Greeting

private extension DashboardView {
    /// The first whitespace-separated component of the saved patient name, used to
    /// greet the user by name. Empty when no name has been entered yet, in which
    /// case the greeting is omitted entirely rather than shown impersonally.
    var firstName: String {
        patientName
            .split(separator: " ", omittingEmptySubsequences: true)
            .first
            .map(String.init) ?? ""
    }

    /// A warm, personal welcome above the metrics. The localized greeting carries
    /// `^[…](inflect: true)` markup, so on languages with grammatical gender it
    /// agrees with the reader's system Term of Address automatically — no stored
    /// gender of our own is consulted.
    @ViewBuilder
    var greeting: some View {
        if !firstName.isEmpty {
            Text("Welcome back, \(firstName)")
                .font(.title3.weight(.semibold))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 2)
                .accessibilityAddTraits(.isHeader)
        }
    }
}

// MARK: - Code names & pin helpers

private extension DashboardView {
    var allCodeNames: [CodeName] {
        var seen = Set<String>()
        var result: [CodeName] = []
        for report in reports {
            for entry in report.entries where seen.insert(entry.code).inserted {
                result.append(CodeName(code: entry.code, name: entry.resolvedName))
            }
        }
        return result
    }

    func togglePin(_ code: String) {
        var updated = prefs
        if updated.pinnedCodes.contains(code) {
            updated.pinnedCodes.removeAll { $0 == code }
        } else {
            updated.pinnedCodes.append(code)
        }
        prefs = updated
    }
}

// MARK: - Preview

#if DEBUG
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
            reports: [.pairValueReport],
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true,
            clipboardAvailable: false,
            isProcessing: false
        )
    }
}

#Preview("Single Value") {
    NavigationStack {
        DashboardView(
            reports: [.soleValueReport()],
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true,
            clipboardAvailable: false,
            isProcessing: false
        )
    }
}

#Preview("Single Value · Trend") {
    NavigationStack {
        DashboardView(
            reports: LabReport.soleValueTrend,
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
#endif
