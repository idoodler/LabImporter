import SwiftUI

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

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var showSettings = false
    @State private var trendSheet: TrendSheet?
    @State private var isEditing = false

    private struct TrendSheet: Identifiable {
        var id: String { code }
        let code: String
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                MetricsHomescreenGrid(
                    metrics: metrics,
                    prefs: $prefs,
                    isEditing: $isEditing,
                    onOpenTrend: { trendSheet = TrendSheet(code: $0) }
                )
                footer
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
            .contentShape(Rectangle())
            .onTapGesture { if isEditing { isEditing = false } }
        }
        .background { CategoryBackground(colors: backgroundColors) }
        .navigationTitle("Lab Results")
        .navigationBarTitleDisplayMode(.large)
        .toolbar { toolbarContent }
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

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        if isEditing {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { isEditing = false }
                    .fontWeight(.semibold)
            }
        } else {
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

    // Up to three distinct category colors from the visible metrics, used for
    // the subtle background wash.
    private var backgroundColors: [Color] {
        var seen = Set<LabCategory>()
        var result: [Color] = []
        for metric in dashboardSortedMetrics(metrics, prefs: prefs) {
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
