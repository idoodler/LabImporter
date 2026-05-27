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

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var showOrderSheet = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                metricsGrid
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
            ToolbarItem(placement: .topBarLeading) {
                Button { showOrderSheet = true } label: {
                    Image(systemName: "slider.horizontal.3")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                importMenu
            }
        }
        .sheet(isPresented: $showOrderSheet) {
            LabOrderSheet(
                prefs: $prefs,
                availableCodes: sortedMetrics.map { CodeName(code: $0.entry.code, name: $0.entry.name) }
            )
        }
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
                            Label(pinned ? "Unpin" : "Pin to Top",
                                  systemImage: pinned ? "pin.slash" : "pin")
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
        let orderMap = Dictionary(uniqueKeysWithValues: prefs.orderedCodes.enumerated().map { ($1, $0) })
        return metrics.sorted { a, b in
            let aPin = pinned.contains(a.entry.code)
            let bPin = pinned.contains(b.entry.code)
            if aPin != bPin { return aPin }
            let aOrd = orderMap[a.entry.code] ?? Int.max
            let bOrd = orderMap[b.entry.code] ?? Int.max
            if aOrd != bOrd { return aOrd < bOrd }
            return a.entry.name < b.entry.name
        }
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

// MARK: - Order sheet

private struct LabOrderSheet: View {
    @Binding var prefs: LabDisplayPreferences
    let availableCodes: [CodeName]

    @State private var items: [CodeName] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(items) { item in
                    HStack(spacing: 12) {
                        Button { togglePin(item.code) } label: {
                            Image(systemName: prefs.pinnedSet.contains(item.code) ? "pin.fill" : "pin")
                                .foregroundStyle(prefs.pinnedSet.contains(item.code) ? Color.accentColor : .secondary)
                                .frame(width: 20)
                        }
                        .buttonStyle(.plain)
                        Text(item.name)
                        Spacer()
                    }
                }
                .onMove { from, to in items.move(fromOffsets: from, toOffset: to) }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Edit Order")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { saveAndDismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
        .onAppear { initItems() }
    }

    private func initItems() {
        let pinned = prefs.pinnedSet
        let orderMap = Dictionary(uniqueKeysWithValues: prefs.orderedCodes.enumerated().map { ($1, $0) })
        items = availableCodes.sorted { a, b in
            let aPin = pinned.contains(a.code)
            let bPin = pinned.contains(b.code)
            if aPin != bPin { return aPin }
            let aOrd = orderMap[a.code] ?? Int.max
            let bOrd = orderMap[b.code] ?? Int.max
            if aOrd != bOrd { return aOrd < bOrd }
            return a.name < b.name
        }
    }

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

    private func saveAndDismiss() {
        let newOrder = items.map(\.code)
        let absent = prefs.orderedCodes.filter { !newOrder.contains($0) }
        var updated = prefs
        updated.orderedCodes = newOrder + absent
        prefs = updated
        dismiss()
    }
}
