import Accessibility
import LabImporterCore
import SwiftUI
import Charts

struct TrendsView: View {
    let reports: [LabReport]
    let initialCode: String?
    let onDismiss: (() -> Void)?

    init(reports: [LabReport], initialCode: String? = nil, onDismiss: (() -> Void)? = nil) {
        self.reports = reports
        self.initialCode = initialCode
        self.onDismiss = onDismiss
    }

    @AppStorage("trendsSelectedCode") private var selectedCode: String = ""
    @AppStorage("trendsWindow") private var window: TrendWindow = .opening
    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var selectedDate: Date?
    @State private var selectedTerm: LoincTerm?
    @State private var valueColor: Color = .accentColor
    @State private var showHideConfirmation = false
    @State private var renamingCode: String?
    @State private var renameDraft = ""
    @State private var rangingCode: String?
    @State private var rangeLowDraft = ""
    @State private var rangeHighDraft = ""
    private let selectionFeedback = UISelectionFeedbackGenerator()
    private let impactFeedback = UIImpactFeedbackGenerator(style: .medium)

    struct DataPoint: Identifiable {
        let id = UUID()
        let date: Date
        let value: Double
        let unit: String
    }

    /// Every metric with a numeric reading — hidden ones included, so their
    /// history stays reachable — in dashboard order (shared rule, identical on
    /// Android).
    private var availableCodes: [(code: String, name: String)] {
        SharedCore.start()
        return LabImporterCore.Dashboard.shared.trendMetrics(reports: reports.map(\.shared), preferences: prefs.shared)
            .map { (code: $0.code, name: $0.name) }
    }

    /// Every reading of the metric on screen, oldest first.
    private var allReadings: [LabImporterCore.MetricPoint] {
        reports
            .flatMap { report in
                report.entries
                    .filter { $0.code == selectedCode }
                    .compactMap { entry -> LabImporterCore.MetricPoint? in
                        guard let value = entry.numericValue else { return nil }
                        return LabImporterCore.MetricPoint(date: report.date.sharedDate, value: value, unit: entry.unit, reportId: report.id.uuidString)
                    }
            }
            .sorted { $0.date.compareTo(other: $1.date) < 0 }
    }

    /// The readings in the selected window, what the chart plots (shared rule, as on Android).
    var dataPoints: [DataPoint] {
        window.shared.visible(points: allReadings).map { DataPoint(date: Date(shared: $0.date), value: $0.value, unit: $0.unit) }
    }

    var currentUnit: String { allReadings.first?.unit ?? "" }

    private var selectedDataPoint: DataPoint? {
        guard let selectedDate else { return nil }
        return dataPoints.min(by: {
            abs($0.date.timeIntervalSince(selectedDate)) < abs($1.date.timeIntervalSince(selectedDate))
        })
    }

    private var isPinned: Bool { prefs.pinnedSet.contains(selectedCode) }

    /// The user's reference range for the metric on screen, or `nil` if unset.
    /// Drives the dashed band lines and the per-point out-of-range tinting.
    private var referenceRange: ReferenceRange? { prefs.referenceRange(for: selectedCode) }

    /// Colour for a plotted point: the out-of-range status tint when the value
    /// falls outside the reference range, otherwise the metric's category colour.
    private func pointColor(_ value: Double) -> Color {
        if let status = referenceRange?.status(for: value), status.isOutOfRange { return status.color }
        return valueColor
    }

    /// The selected metric's display name (also read by the audio graph).
    var selectedName: String { name(of: selectedCode, in: availableCodes) }

    private func name(of code: String, in codes: [(code: String, name: String)]) -> String {
        codes.first(where: { $0.code == code })?.name ?? String(localized: "Trends")
    }

    var body: some View {
        // Derived once per update.
        let codes = availableCodes
        let selectedName = name(of: selectedCode, in: codes)
        VStack(spacing: 0) {
            if codes.isEmpty {
                noDataView
            } else {
                trendContent
            }
        }
        .navigationTitle(selectedName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let onDismiss {
                ToolbarItem(placement: .topBarLeading) {
                    Button(role: .close, action: onDismiss)
                }
            }
            if !selectedCode.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    moreMenu
                }
            }
        }
        .alert("Hide \(selectedName)?", isPresented: $showHideConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Hide", role: .destructive) { hideSelected() }
        } message: {
            Text("This metric will no longer appear on your dashboard. You can show it again from Settings.")
        }
        .renameLabAlert(code: $renamingCode, draft: $renameDraft, prefs: $prefs)
        .referenceRangeAlert(code: $rangingCode, low: $rangeLowDraft, high: $rangeHighDraft,
                             unit: currentUnit, prefs: $prefs)
        .onAppear {
            let available = codes.map(\.code)
            if let initial = initialCode, available.contains(initial) {
                selectedCode = initial
            } else if selectedCode.isEmpty || !available.contains(selectedCode) {
                selectedCode = available.first ?? ""
            }
            selectedTerm = LoincDirectory.shared.term(for: selectedCode)
            valueColor = LabCategory.forCode(selectedCode).color
        }
        .onChange(of: selectedCode) { _, code in
            selectedTerm = LoincDirectory.shared.term(for: code)
            valueColor = LabCategory.forCode(code).color
        }
    }

    // MARK: - Toolbar

    private var moreMenu: some View {
        Menu {
            Button {
                renameDraft = prefs.nickname(for: selectedCode) ?? ""
                renamingCode = selectedCode
            } label: {
                Label("Rename", systemImage: "pencil")
            }
            Button {
                startSetRange()
            } label: {
                Label("Set Reference Range", systemImage: "ruler")
            }
            Button {
                togglePin(selectedCode)
            } label: {
                Label(
                    isPinned ? "Unpin" : "Pin to Top",
                    systemImage: isPinned ? "pin.slash" : "pin"
                )
            }
            Button(role: .destructive) {
                showHideConfirmation = true
            } label: {
                Label("Hide", systemImage: "eye.slash")
            }
        } label: {
            Label("More", systemImage: "ellipsis")
        }
    }

    // MARK: - Subviews

    private var noDataView: some View {
        EmptyStateView(
            title: "No Numeric Data",
            systemImage: "chart.line.uptrend.xyaxis",
            message: "Import reports with numeric lab values to see trends."
        ) { EmptyView() }
    }

    @ViewBuilder
    private var trendContent: some View {
        if allReadings.count < 2 {
            VStack(spacing: 16) {
                EmptyStateView(
                    title: "Not Enough Data",
                    systemImage: "chart.xyaxis.line",
                    message: "Import at least two reports containing this value to see a trend."
                ) { EmptyView() }
                descriptionCard
            }
        } else {
            ScrollView {
                VStack(spacing: 16) {
                    windowPicker
                    trendChart
                    descriptionCard
                }
                .padding()
            }
        }
    }

    // Description of the selected LOINC value, shown below the graph, linking to
    // the full LOINC details. Tapping a metric on the dashboard opens this sheet.
    @ViewBuilder
    private var descriptionCard: some View {
        if let term = selectedTerm {
            ValueDescriptionCard(term: term)
        }
    }

}

// MARK: - Chart

extension TrendsView {
    private var trendChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            chartHeader
            chartBody
        }
        .padding()
        .cardSurface()
    }

    /// Fixed readout above the plot, Apple-Health style: while scrubbing it
    /// shows the value under the finger, otherwise the latest reading. Living
    /// outside the scrollable plot, it needs no scroll-offset math and no
    /// edge clamping, and never covers the data under the finger.
    @ViewBuilder
    private var chartHeader: some View {
        if let point = selectedDataPoint ?? dataPoints.last {
            VStack(alignment: .leading, spacing: 2) {
                Text(point.date.formatted(date: .abbreviated, time: .omitted))
                    .font(.caption)
                    .foregroundStyle(.textSecondary)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(formatValue(point.value))
                        .font(.title2.weight(.semibold))
                        .contentTransition(.numericText())
                    if !point.unit.isEmpty {
                        Text(point.unit)
                            .font(.subheadline)
                            .foregroundStyle(.textSecondary)
                    }
                }
            }
            .animation(reduceMotion ? nil : .snappy(duration: 0.2), value: point.date)
        }
    }

    @ViewBuilder
    private var chartBody: some View {
        let points = dataPoints
        if let domain = ChartDomain(dates: points.map(\.date), values: points.map(\.value), range: referenceRange) {
            chart(points: points, domain: domain)
        }
    }

    private func chart(points: [DataPoint], domain: ChartDomain) -> some View {
        Chart {
            chartMarks(points: points)
        }
        .accessibilityChartDescriptor(self)
        .chartXScale(domain: domain.xDomain)
        .chartYScale(domain: domain.yDomain)
        .chartXSelection(value: $selectedDate)
        .onChange(of: selectedDataPoint?.date) { _, newDate in
            if newDate != nil { selectionFeedback.selectionChanged() }
        }
        .chartXAxis {
            // Anchored inwards: a label centered on the plot's last date has no room and truncates ("M…").
            AxisMarks(values: domain.xLabels) { value in
                AxisGridLine().foregroundStyle(valueColor.opacity(0.12))
                // Two whole dates in the largest text overlap: month and year fit side by side.
                AxisValueLabel(
                    format: dynamicTypeSize.isAccessibilitySize ? .dateTime.month(.abbreviated).year(.twoDigits) : .dateTime.day().month(.abbreviated).year(),
                    anchor: xLabelAnchor(index: value.index, count: value.count)
                )
                .foregroundStyle(.textSecondary)
            }
        }
        .chartYAxis {
            AxisMarks(values: domain.yTicks) { _ in
                AxisGridLine().foregroundStyle(valueColor.opacity(0.12))
                AxisValueLabel().foregroundStyle(.textSecondary)
            }
        }
        .frame(minHeight: 260)
    }

    @ChartContentBuilder
    private func chartMarks(points: [DataPoint]) -> some ChartContent {
        if let range = referenceRange {
            if let low = range.low {
                RuleMark(y: .value(String(localized: "Low"), low))
                    .foregroundStyle(RangeStatus.low.color.opacity(0.45))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    .accessibilityHidden(true)
            }
            if let high = range.high {
                RuleMark(y: .value(String(localized: "High"), high))
                    .foregroundStyle(RangeStatus.high.color.opacity(0.45))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    .accessibilityHidden(true)
            }
        }

        // Only `PointMark` carries VoiceOver content — `LineMark`/`AreaMark` plot
        // the exact same date/value pairs and would otherwise triple-announce
        // every reading.
        ForEach(points) { point in
            LineMark(
                x: .value(String(localized: "Date"), point.date),
                y: .value(currentUnit, point.value)
            )
            .foregroundStyle(valueColor.opacity(0.9))
            .accessibilityHidden(true)

            PointMark(
                x: .value(String(localized: "Date"), point.date),
                y: .value(currentUnit, point.value)
            )
            .foregroundStyle(pointColor(point.value))
            .accessibilityLabel(point.date.formatted(date: .abbreviated, time: .omitted))
            .accessibilityValue(accessibilityValue(for: point))

            AreaMark(
                x: .value(String(localized: "Date"), point.date),
                y: .value(currentUnit, point.value)
            )
            .foregroundStyle(valueColor.opacity(0.15))
            .accessibilityHidden(true)
        }

        if let selected = selectedDataPoint {
            RuleMark(x: .value(String(localized: "Date"), selected.date))
                .foregroundStyle(Color.textSecondary.opacity(0.5))
                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                .accessibilityHidden(true)

            PointMark(
                x: .value(String(localized: "Date"), selected.date),
                y: .value(currentUnit, selected.value)
            )
            .symbolSize(0)
            // Let the bubble overflow the plot at the first/last point instead
            // of letting Charts pad the scale to fit it — that padding resized
            // the plot and made the whole graph jump when scrubbing to an edge.
            .annotation(position: .overlay, spacing: 0,
                        overflowResolution: .init(x: .disabled, y: .disabled)) {
                selectedPointBubble
            }
            .accessibilityHidden(true)
        }
    }

    private func xLabelAnchor(index: Int, count: Int) -> UnitPoint {
        guard count > 1 else { return .top }
        return index == 0 ? .topLeading : .topTrailing
    }

    /// VoiceOver content for one plotted point: value, unit, and (when
    /// out-of-range) the same "High"/"Low" word the on-screen badge shows —
    /// reusing `RangeStatus.label` so this never drifts from the visual badge.
    private func accessibilityValue(for point: DataPoint) -> String {
        var value = formatValue(point.value)
        if !point.unit.isEmpty { value += " \(point.unit)" }
        guard let status = referenceRange?.status(for: point.value), status.isOutOfRange else { return value }
        return value + ", " + status.localizedLabel
    }

    private var selectedPointBubble: some View {
        Circle()
            .fill(valueColor)
            .frame(width: 10, height: 10)
            .padding(4)
            .glassEffect(.regular.interactive(), in: Circle())
            .overlay(
                Circle()
                    .stroke(valueColor.opacity(0.6), lineWidth: 1)
            )
            .shadow(color: valueColor.opacity(0.45), radius: 6, x: 0, y: 0)
    }

}

// MARK: - Helpers

extension TrendsView {
    var windowPicker: some View {
        Picker("Time Range", selection: $window) {
            ForEach(TrendWindow.allCases) { option in
                Text(option.label).tag(option)
            }
        }
        .pickerStyle(.segmented)
    }

    func formatValue(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0 ? String(format: "%.0f", value) : String(format: "%.4g", value)
    }

    private func togglePin(_ code: String) {
        var updated = prefs
        if updated.pinnedCodes.contains(code) {
            updated.pinnedCodes.removeAll { $0 == code }
        } else {
            updated.pinnedCodes.append(code)
        }
        prefs = updated
        impactFeedback.impactOccurred()
    }

    /// Seeds the range fields from the current range (if any) and opens the editor
    /// for the metric on screen.
    func startSetRange() {
        let range = prefs.referenceRange(for: selectedCode)
        rangeLowDraft = range?.low.map { Self.fieldText($0) } ?? ""
        rangeHighDraft = range?.high.map { Self.fieldText($0) } ?? ""
        rangingCode = selectedCode
    }

    private static func fieldText(_ value: Double) -> String {
        value == value.rounded() ? String(format: "%.0f", value) : String(value)
    }

    /// Hides the selected metric from the dashboard and dismisses the trend view,
    /// since the value is no longer surfaced in the overview after hiding.
    private func hideSelected() {
        var updated = prefs
        if !updated.hiddenCodes.contains(selectedCode) {
            updated.hiddenCodes.append(selectedCode)
        }
        // Keep a hidden metric from also occupying a pinned slot.
        updated.pinnedCodes.removeAll { $0 == selectedCode }
        prefs = updated
        impactFeedback.impactOccurred()
        onDismiss?()
    }
}

extension View {
    /// Detents for a sheet showing `TrendsView`. Pass whether the *presenting*
    /// view is regular × regular (the sheet's own content can report a compact
    /// width). There the sheet opens at full height: a medium detent on an
    /// unfolded iPhone Duo's short, wide inner display leaves the chart cut off
    /// below its first gridline. Elsewhere it starts at medium, as before.
    func trendSheetPresentation(regular: Bool) -> some View {
        presentationDetents(regular || ScreenshotMode.opensSheetsInFull ? [.large] : [.medium, .large])
            .presentationDragIndicator(regular ? .hidden : .visible)
    }
}

// MARK: - Preview

#if DEBUG
#Preview("Trends") {
    NavigationStack {
        TrendsView(reports: LabReport.sampleHistory, initialCode: "4548-4")
    }
}

#Preview("Trends · Dark") {
    NavigationStack {
        TrendsView(reports: LabReport.sampleHistory, initialCode: "4548-4")
    }
    .preferredColorScheme(.dark)
}

#Preview("Trends · One Reading") {
    NavigationStack {
        TrendsView(reports: [LabReport.sample], initialCode: "4548-4")
    }
}
#endif

#Preview("No Data") {
    NavigationStack {
        TrendsView(reports: [])
    }
}
