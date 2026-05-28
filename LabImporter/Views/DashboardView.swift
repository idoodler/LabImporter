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

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var showSettings = false
    @State private var trendSheet: TrendSheet?

    private struct TrendSheet: Identifiable {
        var id: String { code }
        let code: String
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                metricsGrid
                footer
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .navigationTitle("Lab Results")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                NavigationLink(destination: HistoryView()) {
                    Image(systemName: "clock.arrow.circlepath")
                }
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
        .sheet(isPresented: $showSettings) {
            SettingsView(visibleCodes: allCodeNames)
        }
        .sheet(item: $trendSheet) { sheet in
            NavigationStack {
                TrendsView(reports: reports, initialCode: sheet.code, onDismiss: { trendSheet = nil })
            }
            .presentationDetents([.medium])
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

    private var metricsGrid: some View {
        LazyVGrid(
            columns: [GridItem(.flexible()), GridItem(.flexible())],
            spacing: 14
        ) {
            ForEach(sortedMetrics) { metric in
                Button { trendSheet = TrendSheet(code: metric.entry.code) } label: {
                    MetricCard(metric: metric, isPinned: prefs.pinnedSet.contains(metric.entry.code))
                }
                .buttonStyle(.plain)
                .contextMenu {
                    let pinned = prefs.pinnedSet.contains(metric.entry.code)
                    Button { togglePin(metric.entry.code) } label: {
                        Label(
                            pinned ? "Unpin" : "Pin to Top",
                            systemImage: pinned ? "pin.slash" : "pin"
                        )
                    }
                }
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
            let status = item.entry.numericValue.flatMap { value in
                LabMapping.referenceRange(for: item.entry.code)?.status(for: value)
            }
            return MetricData(entry: item.entry, history: points, status: status)
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
    let status: RangeStatus?
}

// MARK: - MetricCard

private struct MetricCard: View {
    let metric: MetricData
    let isPinned: Bool

    private var statusColor: Color {
        switch metric.status {
        case .normal:    return Color.green
        case .borderline: return Color.orange
        case .abnormal:  return Color.red
        case .none:      return Color.secondary
        }
    }

    private var statusLabel: LocalizedStringKey {
        switch metric.status {
        case .normal:    return "Normal"
        case .borderline: return "Borderline"
        case .abnormal:  return "Elevated"
        case .none:      return ""
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 4) {
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

            if metric.status != nil {
                HStack(spacing: 5) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 7, height: 7)
                    Text(statusLabel)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(statusColor)
                }
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
            .foregroundStyle(statusColor.opacity(0.85))

            AreaMark(
                x: .value("Date", point.date),
                y: .value("Value", point.value)
            )
            .foregroundStyle(
                LinearGradient(
                    colors: [statusColor.opacity(0.3), statusColor.opacity(0.05)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )

            PointMark(
                x: .value("Date", point.date),
                y: .value("Value", point.value)
            )
            .foregroundStyle(statusColor)
            .symbolSize(20)
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .frame(height: 40)
    }
}
