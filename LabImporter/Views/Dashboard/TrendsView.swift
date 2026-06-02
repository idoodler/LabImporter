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
    @AppStorage("trendsWindow") private var window: TrendWindow = .year1
    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var selectedDate: Date?
    /// Leading edge (oldest visible date) of the scrollable chart. Re-anchored to
    /// the latest reading whenever the metric or window changes.
    @State private var scrollPositionX = Date.now
    @State private var selectedTerm: LoincTerm?
    @State private var valueColor: Color = .accentColor
    @State private var showHideConfirmation = false
    @State private var renamingCode: String?
    @State private var renameDraft = ""
    private let selectionFeedback = UISelectionFeedbackGenerator()
    private let impactFeedback = UIImpactFeedbackGenerator(style: .medium)

    private struct DataPoint: Identifiable {
        let id = UUID()
        let date: Date
        let value: Double
        let unit: String
    }

    private var availableCodes: [(code: String, name: String)] {
        var seen = Set<String>()
        var result: [(code: String, name: String)] = []
        for report in reports {
            for entry in report.entries where entry.numericValue != nil && seen.insert(entry.code).inserted {
                result.append((code: entry.code, name: entry.resolvedName))
            }
        }
        let pinned = prefs.pinnedSet
        var orderMap: [String: Int] = [:]
        for (idx, code) in prefs.orderedCodes.enumerated() where orderMap[code] == nil {
            orderMap[code] = idx
        }
        return result.sorted { lhs, rhs in
            let aPin = pinned.contains(lhs.code)
            let bPin = pinned.contains(rhs.code)
            if aPin != bPin { return aPin }
            let aOrd = orderMap[lhs.code] ?? Int.max
            let bOrd = orderMap[rhs.code] ?? Int.max
            if aOrd != bOrd { return aOrd < bOrd }
            return lhs.name < rhs.name
        }
    }

    private var dataPoints: [DataPoint] {
        reports
            .flatMap { report in
                report.entries
                    .filter { $0.code == selectedCode }
                    .compactMap { entry -> DataPoint? in
                        guard let value = entry.numericValue else { return nil }
                        return DataPoint(date: report.date, value: value, unit: entry.unit)
                    }
            }
            .sorted { $0.date < $1.date }
    }

    private var currentUnit: String { dataPoints.first?.unit ?? "" }

    private var selectedDataPoint: DataPoint? {
        guard let selectedDate else { return nil }
        return dataPoints.min(by: {
            abs($0.date.timeIntervalSince(selectedDate)) < abs($1.date.timeIntervalSince(selectedDate))
        })
    }

    private var isPinned: Bool { prefs.pinnedSet.contains(selectedCode) }

    private var selectedName: String {
        availableCodes.first(where: { $0.code == selectedCode })?.name ?? "Trends"
    }

    var body: some View {
        VStack(spacing: 0) {
            if availableCodes.isEmpty {
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
        .onAppear {
            let codes = availableCodes.map(\.code)
            if let initial = initialCode, codes.contains(initial) {
                selectedCode = initial
            } else if selectedCode.isEmpty || !codes.contains(selectedCode) {
                selectedCode = codes.first ?? ""
            }
            selectedTerm = LoincDirectory.shared.term(for: selectedCode)
            valueColor = LabCategory.forCode(selectedCode).color
            anchorScroll()
        }
        .onChange(of: selectedCode) { _, code in
            selectedTerm = LoincDirectory.shared.term(for: code)
            valueColor = LabCategory.forCode(code).color
            anchorScroll()
        }
        .onChange(of: window) { _, _ in
            anchorScroll()
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
        ContentUnavailableView(
            "No Numeric Data",
            systemImage: "chart.line.uptrend.xyaxis",
            description: Text("Import reports with numeric lab values to see trends.")
        )
    }

    @ViewBuilder
    private var trendContent: some View {
        if dataPoints.count < 2 {
            VStack(spacing: 16) {
                ContentUnavailableView(
                    "Not Enough Data",
                    systemImage: "chart.xyaxis.line",
                    description: Text("Import at least two reports containing this value to see a trend.")
                )
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

    private var trendChart: some View {
        Chart {
            ForEach(dataPoints) { point in
                LineMark(
                    x: .value(String(localized: "Date"), point.date),
                    y: .value(currentUnit, point.value)
                )
                .foregroundStyle(valueColor.opacity(0.9))

                PointMark(
                    x: .value(String(localized: "Date"), point.date),
                    y: .value(currentUnit, point.value)
                )
                .foregroundStyle(valueColor)

                AreaMark(
                    x: .value(String(localized: "Date"), point.date),
                    y: .value(currentUnit, point.value)
                )
                .foregroundStyle(valueColor.opacity(0.15))
            }

            if let selected = selectedDataPoint {
                RuleMark(x: .value(String(localized: "Date"), selected.date))
                    .foregroundStyle(.secondary.opacity(0.5))
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))

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
            }
        }
        .chartScrollableAxes(.horizontal)
        .chartXVisibleDomain(length: visibleDomainSeconds)
        .chartScrollPosition(x: $scrollPositionX)
        .chartXSelection(value: $selectedDate)
        .chartOverlay { proxy in
            GeometryReader { geo in
                if let selected = selectedDataPoint,
                   let plotFrame = proxy.plotFrame.map({ geo[$0] }),
                   let xPos = proxy.position(forX: selected.date) {
                    scrubCallout(selected)
                        .position(
                            x: clampedX(xPos, in: plotFrame),
                            y: plotFrame.minY + 22
                        )
                        .allowsHitTesting(false)
                }
            }
        }
        .onChange(of: selectedDataPoint?.date) { _, newDate in
            if newDate != nil { selectionFeedback.selectionChanged() }
        }
        .chartXAxis {
            AxisMarks(values: .automatic) { _ in
                AxisGridLine().foregroundStyle(valueColor.opacity(0.12))
                AxisValueLabel(format: .dateTime.day().month(.abbreviated))
                    .foregroundStyle(valueColor.opacity(0.8))
            }
        }
        .chartYAxis {
            AxisMarks { _ in
                AxisGridLine().foregroundStyle(valueColor.opacity(0.12))
                AxisValueLabel().foregroundStyle(valueColor.opacity(0.8))
            }
        }
        .frame(minHeight: 260)
        .padding()
        .overlay(alignment: .topLeading) { unitBadge }
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }

    private func scrubCallout(_ point: DataPoint) -> some View {
        VStack(alignment: .center, spacing: 1) {
            Text(point.date.formatted(date: .abbreviated, time: .omitted))
                .font(.caption2)
                .foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(formatValue(point.value))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                if !point.unit.isEmpty {
                    Text(point.unit)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .fixedSize()
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .glassEffect(.regular, in: Capsule())
        .overlay(
            Capsule()
                .stroke(Color.primary.opacity(0.15), lineWidth: 0.5)
        )
        .shadow(color: Color.black.opacity(0.2), radius: 8, x: 0, y: 3)
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
    /// Selectable time window; a finite case fixes the visible x-span, `.all` fits everything.
    enum TrendWindow: String, CaseIterable, Identifiable {
        case month3, month6, year1, all
        var id: Self { self }

        /// Visible span in days, or `nil` for "fit everything".
        var days: Int? {
            switch self {
            case .month3: return 92
            case .month6: return 183
            case .year1: return 366
            case .all: return nil
            }
        }

        var label: LocalizedStringKey {
            switch self {
            case .month3: return "3M"
            case .month6: return "6M"
            case .year1: return "1Y"
            case .all: return "All"
            }
        }
    }

    /// Static unit caption pinned to the card (`chartYAxisLabel` rides the scrollable plot).
    @ViewBuilder
    var unitBadge: some View {
        if !currentUnit.isEmpty {
            Text(currentUnit)
                .font(.caption.weight(.medium))
                .foregroundStyle(valueColor.opacity(0.8))
                .padding(EdgeInsets(top: 8, leading: 12, bottom: 0, trailing: 0))
                .allowsHitTesting(false)
        }
    }

    var windowPicker: some View {
        Picker("Time Range", selection: $window) {
            ForEach(TrendWindow.allCases) { option in
                Text(option.label).tag(option)
            }
        }
        .pickerStyle(.segmented)
    }

    /// Visible x-axis window width in seconds; for `.all`, the full span padded 10%.
    var visibleDomainSeconds: TimeInterval {
        if let days = window.days {
            return Double(days) * 86_400
        }
        guard let first = dataPoints.first?.date, let last = dataPoints.last?.date else { return 86_400 }
        return max(last.timeIntervalSince(first) * 1.1, 86_400)
    }

    /// Anchors the scroll so the latest reading is in view (whole range for `.all`).
    func anchorScroll() {
        guard let first = dataPoints.first?.date, let last = dataPoints.last?.date else { return }
        if window.days == nil {
            scrollPositionX = first.addingTimeInterval(-visibleDomainSeconds * 0.05)
        } else {
            scrollPositionX = last.addingTimeInterval(-visibleDomainSeconds * 0.95)
        }
    }

    private func clampedX(_ xPos: CGFloat, in frame: CGRect) -> CGFloat {
        let halfBubble: CGFloat = 60
        return min(max(xPos, frame.minX + halfBubble), frame.maxX - halfBubble)
    }

    private func formatValue(_ value: Double) -> String {
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

// MARK: - ValueDescriptionCard

/// Tappable summary of the selected value's LOINC term shown beneath the trend
/// chart; navigates to the full structured details (and the loinc.org link).
private struct ValueDescriptionCard: View {
    let term: LoincTerm

    var body: some View {
        NavigationLink {
            LoincTermDetailView(term: term)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("About this value")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                if let description {
                    Text(description)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Text(verbatim: "LOINC \(term.code)")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
            )
            .padding(.horizontal)
        }
        .buttonStyle(.plain)
    }

    private var description: String? {
        if let description = term.description, !description.isEmpty { return description }
        // Fall back to the English name when it adds detail beyond the title.
        return term.englishName == term.name ? nil : term.englishName
    }
}

// MARK: - Preview

#Preview("Trends") {
    NavigationStack {
        TrendsView(reports: LabReport.sampleHistory, initialCode: "4548-4")
    }
}

#Preview("No Data") {
    NavigationStack {
        TrendsView(reports: [])
    }
}
