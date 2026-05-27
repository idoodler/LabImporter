import SwiftUI
import Charts
import PhotosUI

// MARK: - Dashboard

struct DashboardView: View {
    let reports: [LabReport]
    @Binding var photosPickerItem: PhotosPickerItem?
    let onCamera: () -> Void
    let onPaste: () -> Void
    let clipboardAvailable: Bool
    let isProcessing: Bool

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var showOrderSheet = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                if isProcessing {
                    processingCard
                } else {
                    metricsGrid
                }
                trendsLink
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
                    showOrderSheet = true
                } label: {
                    Image(systemName: "slider.horizontal.3")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                importMenu
            }
        }
        .sheet(isPresented: $showOrderSheet) {
            LabOrderSheet(prefs: $prefs, allCodes: allCodeNames)
        }
    }

    // MARK: - Processing card

    private var processingCard: some View {
        VStack(spacing: 16) {
            ProgressView()
                .controlSize(.extraLarge)
            Text("Analyzing lab report…")
                .font(.headline)
            Text("Using on-device AI")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(40)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))
    }

    // MARK: - Import menu

    @ViewBuilder
    private var importMenu: some View {
        Menu {
            PhotosPicker(selection: $photosPickerItem, matching: .images) {
                Label("Choose from Photos", systemImage: "photo.on.rectangle")
            }
            Button(action: onCamera) {
                Label("Take a Photo", systemImage: "camera")
            }
            .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))
            Button(action: onPaste) {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
            }
            .disabled(!clipboardAvailable)
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
                MetricCard(metric: metric, isPinned: prefs.pinnedSet.contains(metric.entry.code))
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

    private var trendsLink: some View {
        NavigationLink(destination: TrendsView(reports: reports)) {
            HStack {
                Image(systemName: "chart.line.uptrend.xyaxis")
                    .font(.body.weight(.medium))
                Text("View All Trends")
                    .font(.body.weight(.medium))
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            .foregroundStyle(.primary)
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
            )
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
                return lhs.entry.name < rhs.entry.name
            }
    }

    // MARK: - Code names for order sheet

    private var allCodeNames: [CodeName] {
        var seen = Set<String>()
        var result: [CodeName] = []
        for report in reports {
            for entry in report.entries where seen.insert(entry.code).inserted {
                result.append(CodeName(code: entry.code, name: entry.name))
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
            if !updated.orderedCodes.contains(code) {
                updated.orderedCodes.append(code)
            }
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

private struct CodeName: Identifiable {
    var id: String { code }
    let code: String
    let name: String
}

// MARK: - LabOrderSheet

private struct LabOrderSheet: View {
    @Binding var prefs: LabDisplayPreferences
    let allCodes: [CodeName]
    @Environment(\.dismiss) private var dismiss

    @State private var visibleOrdered: [CodeName]
    @State private var hiddenSet: Set<String>
    @State private var pinnedSet: Set<String>

    init(prefs: Binding<LabDisplayPreferences>, allCodes: [CodeName]) {
        _prefs = prefs
        self.allCodes = allCodes

        let currentPrefs = prefs.wrappedValue
        let hidden = currentPrefs.hiddenSet

        var seen = Set<String>()
        var initial: [CodeName] = []
        for code in currentPrefs.orderedCodes where !hidden.contains(code) {
            if let item = allCodes.first(where: { $0.code == code }), seen.insert(code).inserted {
                initial.append(item)
            }
        }
        for item in allCodes where !hidden.contains(item.code) && seen.insert(item.code).inserted {
            initial.append(item)
        }

        _visibleOrdered = State(initialValue: initial)
        _hiddenSet = State(initialValue: hidden)
        _pinnedSet = State(initialValue: currentPrefs.pinnedSet)
    }

    var body: some View {
        NavigationStack {
            List {
                visibleSection
                hiddenSection
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Customize")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { save(); dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
    }

    @ViewBuilder
    private var visibleSection: some View {
        Section("Visible") {
            ForEach(visibleOrdered) { item in
                HStack(spacing: 12) {
                    Button { togglePin(item.code) } label: {
                        Image(systemName: pinnedSet.contains(item.code) ? "pin.fill" : "pin")
                            .foregroundStyle(pinnedSet.contains(item.code) ? Color.accentColor : Color.secondary)
                            .frame(width: 20)
                    }
                    .buttonStyle(.plain)
                    Text(item.name)
                    Spacer()
                    Button { hideCode(item.code) } label: {
                        Image(systemName: "eye.slash")
                            .foregroundStyle(Color.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .onMove { from, dest in visibleOrdered.move(fromOffsets: from, toOffset: dest) }
        }
    }

    @ViewBuilder
    private var hiddenSection: some View {
        let hiddenItems = allCodes.filter { hiddenSet.contains($0.code) }
        if !hiddenItems.isEmpty {
            Section("Hidden") {
                ForEach(hiddenItems) { item in
                    HStack {
                        Text(item.name)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button("Restore") { restoreCode(item.code) }
                            .buttonStyle(.plain)
                            .foregroundStyle(Color.accentColor)
                            .fontWeight(.medium)
                    }
                    .moveDisabled(true)
                }
            }
        }
    }

    private func togglePin(_ code: String) {
        if pinnedSet.contains(code) {
            pinnedSet.remove(code)
        } else {
            pinnedSet.insert(code)
        }
    }

    private func hideCode(_ code: String) {
        hiddenSet.insert(code)
        visibleOrdered.removeAll { $0.code == code }
    }

    private func restoreCode(_ code: String) {
        hiddenSet.remove(code)
        if let item = allCodes.first(where: { $0.code == code }) {
            visibleOrdered.append(item)
        }
    }

    private func save() {
        let hiddenOrdered = allCodes.filter { hiddenSet.contains($0.code) }
        var updated = prefs
        updated.orderedCodes = visibleOrdered.map(\.code) + hiddenOrdered.map(\.code)
        updated.pinnedCodes = Array(pinnedSet)
        updated.hiddenCodes = Array(hiddenSet)
        prefs = updated
    }
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

    private var statusLabel: String {
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
                Text(metric.entry.name)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                if isPinned {
                    Image(systemName: "pin.fill")
                        .font(.caption2)
                        .foregroundStyle(Color.accentColor)
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
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .frame(height: 40)
    }
}
